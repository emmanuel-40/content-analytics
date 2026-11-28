package com.contentanalytics.service;

import com.contentanalytics.dto.*;
import com.contentanalytics.entity.Document;
import com.contentanalytics.entity.DocumentStatus;
import com.contentanalytics.entity.User;
import com.contentanalytics.exception.DocumentNotFoundException;
import com.contentanalytics.exception.UserNotFoundException;
import com.contentanalytics.repository.DocumentRepository;
import com.contentanalytics.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class DocumentService {

    private final DocumentRepository documentRepository;
    private final UserRepository userRepository;
    private final StorageService storageService; // We'll create this next
    private final JobQueue jobQueue;

    @Value("${app.max-upload-size:10485760}") // 10 MB default
    private long maxUploadSize;

    @Value("${app.allowed-file-types:pdf,txt,docx,pptx}")
    private String allowedFileTypes;

    /**
     * Snapshot handed to a worker after a successful atomic claim. It deliberately contains
     * no managed JPA entity: the claim transaction has committed by the time work starts.
     */
    public record ClaimedDocument(UUID id, UUID userId, String fileName,
                                  String fileKey, String mimeType) {
    }

    /**
     * Analysis results persisted together with the terminal COMPLETED transition.
     */
    public record ProcessedDocument(String extractedText, int wordCount, int characterCount,
                                    String summary, String keyTopics, String sentiment,
                                    Float confidenceScore) {
    }

    /**
     * Upload a document
     * Validates file, saves to storage, creates DB record
     */
    public DocumentResponseDto uploadDocument(UUID userId, DocumentUploadRequestDto request) {
        log.info("Document upload started for user: {}", userId);

        MultipartFile file = request.getFile();

        // Validate user exists
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        // Validate file is not empty
        if (file.isEmpty()) {
            throw new IllegalArgumentException("File cannot be empty");
        }

        // Validate file size
        if (file.getSize() > maxUploadSize) {
            throw new IllegalArgumentException("File size exceeds maximum allowed size of " + maxUploadSize + " bytes");
        }

        // Validate file type
        String fileName = file.getOriginalFilename();
        String fileExtension = getFileExtension(fileName);
        if (!isAllowedFileType(fileExtension)) {
            throw new IllegalArgumentException("File type ." + fileExtension + " is not allowed");
        }

        // Save file to storage (S3, local, etc.)
        String fileKey = storageService.saveFile(file, userId);
        log.debug("File saved to storage with key: {}", fileKey);

        // Create document record in database
        Document document = Document.builder()
                .fileName(fileName)
                .fileKey(fileKey)
                .fileSize(file.getSize())
                .mimeType(file.getContentType())
                .pageCount(0) // Will be updated during processing
                .description(request.getDescription())
                .status(DocumentStatus.UPLOADED)
                .processingProgress(0f)
                .user(user)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        try {
            document = documentRepository.save(document);
        } catch (RuntimeException e) {
            // Storage happens before the DB insert commits. If the insert fails, do not leave
            // an orphaned object behind. Never mask the original persistence exception.
            try {
                storageService.deleteFile(fileKey);
            } catch (RuntimeException cleanupError) {
                log.warn("Failed to remove orphaned upload after DB insert failure: {}", fileKey, cleanupError);
            }
            throw e;
        }
        log.info("Document created with ID: {} for user: {}", document.getId(), userId);

        // Queue only after the INSERT is committed, otherwise a worker can claim a
        // document the creating transaction may still roll back.
        enqueueAfterCommit(document.getId(), userId);

        return mapToResponseDto(document);
    }

    /**
     * Get document with detailed analysis
     * Verify ownership before returning
     */
    public DocumentResponseDto getDocument(UUID documentId, UUID userId) {
        log.debug("Fetching document: {} for user: {}", documentId, userId);

        Document document = documentRepository.findByIdAndUserId(documentId, userId)
                .orElseThrow(() -> {
                    log.warn("Unauthorized access attempt to document: {} by user: {}", documentId, userId);
                    return new DocumentNotFoundException("Document not found");
                });

        return mapToResponseDto(document);
    }

    /**
     * List documents for user with pagination
     */
    public PaginationResponseDto<DocumentListItemDto> listDocuments(UUID userId, int pageNumber, int pageSize) {
        log.debug("Listing documents for user: {} - page: {}, size: {}", userId, pageNumber, pageSize);

        // Validate pagination params
        pageNumber = Math.max(0, pageNumber);
        pageSize = Math.min(pageSize, 100); // Max 100 items per page
        pageSize = Math.max(pageSize, 1);

        // Create pageable
        Pageable pageable = PageRequest.of(pageNumber, pageSize, Sort.by("createdAt").descending());

        // Fetch page
        Page<Document> page = documentRepository.findByUserId(userId, pageable);

        // Convert to DTOs
        var content = page.getContent()
                .stream()
                .map(this::mapToListItemDto)
                .collect(Collectors.toList());

        return PaginationResponseDto.<DocumentListItemDto>builder()
                .content(content)
                .pageNumber(page.getNumber())
                .pageSize(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .isFirst(page.isFirst())
                .isLast(page.isLast())
                .hasNext(page.hasNext())
                .hasPrevious(page.hasPrevious())
                .build();
    }

    /**
     * Whitelist of properties clients may sort by. Passing arbitrary client input straight into
     * {@code Sort.by(...)} allows probing internal entity properties and yields unhandled
     * 500s when the property does not exist.
     */
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of(
            "createdAt", "updatedAt", "fileName", "fileSize", "wordCount", "status", "sentiment"
    );

    /**
     * Search & filter documents
     */
    public PaginationResponseDto<DocumentListItemDto> searchDocuments(UUID userId, DocumentSearchFilterDto filter) {
        log.debug("Searching documents for user: {} with filters", userId);

        // Validate pagination
        int pageNumber = Math.max(0, filter.getPageNumber());
        int pageSize = Math.min(filter.getPageSize(), 100);
        pageSize = Math.max(pageSize, 1);

        // Parse sort direction
        Sort.Direction direction = "DESC".equalsIgnoreCase(filter.getSortDirection())
                ? Sort.Direction.DESC
                : Sort.Direction.ASC;

        String requestedSort = filter.getSortBy() != null ? filter.getSortBy() : "createdAt";
        String sortBy = ALLOWED_SORT_FIELDS.contains(requestedSort) ? requestedSort : "createdAt";
        if (!sortBy.equals(requestedSort)) {
            log.warn("Rejected unsupported sort field '{}', falling back to 'createdAt'", requestedSort);
        }

        Pageable pageable = PageRequest.of(pageNumber, pageSize, Sort.by(direction, sortBy));

        // Execute appropriate query based on filters
        Page<Document> page;

        if (filter.getFileName() != null && !filter.getFileName().isBlank()) {
            page = documentRepository.searchByFileName(userId, filter.getFileName(), pageable);
        } else if (filter.getStatus() != null && !filter.getStatus().isBlank()) {
            DocumentStatus status = parseStatus(filter.getStatus());
            page = documentRepository.findByUserIdAndStatus(userId, status, pageable);
        } else if (filter.getSentiment() != null && !filter.getSentiment().isBlank()) {
            page = documentRepository.findBySentiment(userId, filter.getSentiment(), pageable);
        } else if (filter.getTopic() != null && !filter.getTopic().isBlank()) {
            page = documentRepository.findByTopic(userId, filter.getTopic(), pageable);
        } else if (filter.getStartDate() != null && filter.getEndDate() != null) {
            page = documentRepository.findByUserIdAndDateRange(userId, filter.getStartDate(), filter.getEndDate(), pageable);
        } else {
            page = documentRepository.findByUserId(userId, pageable);
        }

        // Convert results
        var content = page.getContent()
                .stream()
                .map(this::mapToListItemDto)
                .collect(Collectors.toList());

        return PaginationResponseDto.<DocumentListItemDto>builder()
                .content(content)
                .pageNumber(page.getNumber())
                .pageSize(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .isFirst(page.isFirst())
                .isLast(page.isLast())
                .hasNext(page.hasNext())
                .hasPrevious(page.hasPrevious())
                .build();
    }

    /**
     * Retry processing for failed document
     */
    public void retryFailedDocument(UUID documentId, UUID userId) {
        Document document = documentRepository.findByIdAndUserId(documentId, userId)
                .orElseThrow(() -> new DocumentNotFoundException("Document not found"));
        log.debug("Retry requested for document {} owned by {}", document.getId(), document.getUser().getId());

        // Guarded, row-locked transition: two concurrent retries cannot both requeue.
        boolean reset = transition(documentId, EnumSet.of(DocumentStatus.FAILED),
                DocumentStatus.UPLOADED, 0f, null, d -> d.setProcessingError(null));
        if (!reset) {
            throw new IllegalArgumentException("Only failed documents can be retried");
        }

        log.info("Retrying processing for document: {}", documentId);
        enqueueAfterCommit(documentId, userId);
    }

    /**
     * Delete document
     * Verify ownership, delete from storage and DB. Deletion is rejected while a worker
     * owns the row (PROCESSING) so storage and database cannot diverge mid-analysis.
     */
    public void deleteDocument(UUID documentId, UUID userId) {
        log.info("Deleting document: {} for user: {}", documentId, userId);

        Document document = documentRepository.findByIdAndUserIdForUpdate(documentId, userId)
                .orElseThrow(() -> new DocumentNotFoundException("Document not found"));

        if (document.getStatus() == DocumentStatus.PROCESSING) {
            throw new IllegalStateException("Cannot delete a document while it is being processed");
        }

        // Delete from storage
        storageService.deleteFile(document.getFileKey());
        log.debug("File deleted from storage: {}", document.getFileKey());

        // Delete from database
        documentRepository.delete(document);
        log.info("Document deleted: {}", documentId);
    }

    /**
     * Mark the original upload as removed after successful storage deletion.
     * Guarded by status so a stale cleanup cannot touch a document that was reopened.
     */
    @Transactional
    public void markSourceFileDeleted(UUID documentId) {
        int updated = documentRepository.markSourceFileDeleted(
                documentId, DocumentStatus.COMPLETED, LocalDateTime.now());
        if (updated == 0) {
            log.warn("Source file cleanup flag not applied for document {} - status changed", documentId);
        }
    }

    /**
     * Build a downloadable text report from persisted analysis results.
     *
     * The original upload is intentionally not read: it is deleted as soon as analysis
     * completes so the storage bucket does not grow with every document.
     *
     * @throws com.contentanalytics.exception.DocumentNotFoundException if the document
     * does not exist or is not owned by the requesting user (avoids leaking existence)
     */
    @Transactional(readOnly = true)
    public AnalysisReportDto generateAnalysisReport(UUID documentId, UUID userId) {
        log.debug("Building analysis report for document: {} (user: {})", documentId, userId);

        Document document = documentRepository.findByIdAndUserId(documentId, userId)
                .orElseThrow(() -> {
                    log.warn("Unauthorized report attempt for document: {} by user: {}", documentId, userId);
                    return new DocumentNotFoundException("Document not found");
                });

        if (document.getStatus() != DocumentStatus.COMPLETED) {
            throw new IllegalArgumentException("Analysis report is available only after processing completes");
        }

        StringBuilder report = new StringBuilder();
        report.append("CONTENT ANALYTICS REPORT\n");
        report.append("========================\n\n");
        report.append("Source file: ").append(valueOrNa(document.getFileName())).append('\n');
        report.append("Completed: ").append(valueOrNa(document.getProcessingCompletedAt())).append('\n');
        report.append("Status: ").append(valueOrNa(document.getStatus())).append("\n\n");

        report.append("SUMMARY\n");
        report.append("-------\n");
        report.append(document.getSummary() == null || document.getSummary().isBlank()
                ? "No AI summary was generated for this document."
                : document.getSummary().trim()).append("\n\n");

        report.append("KEY TOPICS\n");
        report.append("----------\n");
        report.append(formatTopics(document.getKeyTopics())).append("\n\n");

        report.append("SENTIMENT\n");
        report.append("---------\n");
        report.append(valueOrNa(document.getSentiment())).append("\n\n");

        report.append("CONFIDENCE\n");
        report.append("----------\n");
        report.append(document.getConfidenceScore() == null
                ? "N/A"
                : Math.round(document.getConfidenceScore() * 100) + "%").append("\n\n");

        report.append("STATISTICS\n");
        report.append("----------\n");
        report.append("Words: ").append(valueOrNa(document.getWordCount())).append('\n');
        report.append("Characters: ").append(valueOrNa(document.getCharacterCount())).append('\n');
        report.append("Pages: ").append(valueOrNa(document.getPageCount())).append('\n');

        return AnalysisReportDto.builder()
                .fileName(reportFileName(document.getFileName()))
                .contentType("text/plain; charset=utf-8")
                .content(report.toString())
                .build();
    }

    private String valueOrNa(Object value) {
        return value == null || String.valueOf(value).isBlank() ? "N/A" : String.valueOf(value);
    }

    private String formatTopics(String rawTopics) {
        if (rawTopics == null || rawTopics.isBlank()) {
            return "N/A";
        }

        String topics = rawTopics.trim();
        if (topics.length() >= 2 && topics.startsWith("[") && topics.endsWith("]")) {
            topics = topics.substring(1, topics.length() - 1).replace('"', ' ');
        }
        return topics.isBlank() ? "N/A" : topics.trim();
    }

    private String reportFileName(String originalFileName) {
        String base = originalFileName == null || originalFileName.isBlank()
                ? "document"
                : originalFileName.trim();

        int lastDot = base.lastIndexOf('.');
        if (lastDot > 0) {
            base = base.substring(0, lastDot);
        }
        base = base.replaceAll("[^a-zA-Z0-9._ -]+", "_");
        if (base.isBlank()) {
            base = "document";
        }
        return base + "-analysis.txt";
    }

    /**
     * Get document statistics
     */
    public DocumentStatsDto getDocumentStats(UUID userId) {
        log.debug("Fetching document stats for user: {}", userId);

        long total = documentRepository.countByUserId(userId);
        long completed = documentRepository.countByUserIdAndStatus(userId, DocumentStatus.COMPLETED);
        long processing = documentRepository.countByUserIdAndStatus(userId, DocumentStatus.PROCESSING);
        long failed = documentRepository.countByUserIdAndStatus(userId, DocumentStatus.FAILED);

        // Get stats from query (COALESCE ensures no nulls)
        Object[] stats = (Object[]) documentRepository.getUserDocumentStats(userId);
        long totalWords = stats != null && stats.length > 4 ? ((Number) stats[4]).longValue() : 0L;
        double avgWords = total > 0 ? (double) totalWords / total : 0;

        return DocumentStatsDto.builder()
                .totalDocuments(total)
                .completedDocuments(completed)
                .processingDocuments(processing)
                .failedDocuments(failed)
                .totalWords(totalWords)
                .averageWordCount(avgWords)
                .build();
    }

    /**
     * Atomically claim a document for processing. Returns {@code null} when another worker
     * owns the row or the work is already finished, so duplicate deliveries are harmless.
     */
    public ClaimedDocument claimForProcessing(UUID documentId) {
        return documentRepository.findByIdAndStatusForUpdate(documentId, DocumentStatus.UPLOADED)
                .map(document -> {
                    document.setStatus(DocumentStatus.PROCESSING);
                    document.setProcessingProgress(10f);
                    document.setProcessingError(null);
                    document.setProcessingCompletedAt(null);
                    document.setProcessingStartedAt(LocalDateTime.now());
                    documentRepository.save(document);

                    log.info("Document {} claimed for processing", documentId);
                    return new ClaimedDocument(document.getId(), document.getUser().getId(),
                            document.getFileName(), document.getFileKey(), document.getMimeType());
                })
                .orElse(null);
    }

    /**
     * Guarded progress/terminal status update. Every call locks the row and validates the
     * current status before writing; a stale worker can never move a document backwards.
     *
     * @return {@code true} when the transition was applied
     */
    public boolean updateProcessingStatus(UUID documentId, DocumentStatus status,
                                          Float progress, String error) {
        EnumSet<DocumentStatus> expected = switch (status) {
            case PROCESSING -> EnumSet.of(DocumentStatus.PROCESSING);
            case COMPLETED -> EnumSet.of(DocumentStatus.PROCESSING);
            case FAILED -> EnumSet.of(DocumentStatus.PROCESSING, DocumentStatus.UPLOADED);
            case UPLOADED -> EnumSet.of(DocumentStatus.FAILED, DocumentStatus.PROCESSING);
        };
        return transition(documentId, expected, status, progress, error, d -> {
        });
    }

    /**
     * Persist analysis results and move PROCESSING  to  COMPLETED in one transaction.
     */
    public boolean completeProcessing(UUID documentId, ProcessedDocument result) {
        Objects.requireNonNull(result, "result");
        return transition(documentId, EnumSet.of(DocumentStatus.PROCESSING),
                DocumentStatus.COMPLETED, 100f, null, document -> {
                    document.setExtractedText(result.extractedText());
                    document.setWordCount(result.wordCount());
                    document.setCharacterCount(result.characterCount());
                    document.setSummary(result.summary());
                    document.setKeyTopics(result.keyTopics());
                    document.setSentiment(result.sentiment());
                    document.setConfidenceScore(result.confidenceScore());
                });
    }

    /**
     * Terminal failure. Returns {@code false} if another worker already finished the job,
     * in which case no failure notification may be emitted.
     */
    public boolean failProcessing(UUID documentId, String error) {
        return transition(documentId,
                EnumSet.of(DocumentStatus.PROCESSING, DocumentStatus.UPLOADED),
                DocumentStatus.FAILED, 0f, error, d -> d.setProcessingCompletedAt(null));
    }

    /**
     * Return a claimed document to the retryable UPLOADED state between bounded attempts.
     * Publishing failures are moved to FAILED by {@link #markEnqueueFailed} so the user can
     * retry them explicitly.
     */
    public void resetForRetry(UUID documentId, String error) {
        int updated = documentRepository.resetClaimedDocument(documentId,
                EnumSet.of(DocumentStatus.PROCESSING, DocumentStatus.UPLOADED),
                DocumentStatus.UPLOADED, error, LocalDateTime.now());
        if (updated == 0) {
            log.warn("Could not mark document {} retryable - it is no longer UPLOADED/PROCESSING",
                    documentId);
        }
    }

    private boolean transition(UUID documentId, Set<DocumentStatus> expectedStatuses,
                               DocumentStatus target, Float progress, String error,
                               Consumer<Document> mutator) {
        Document document = documentRepository.findByIdForUpdate(documentId).orElse(null);
        if (document == null || !expectedStatuses.contains(document.getStatus())) {
            log.warn("Skipping stale transition of document {} (current: {}) to {}",
                    documentId, document == null ? "MISSING" : document.getStatus(), target);
            return false;
        }

        document.setStatus(target);
        document.setProcessingProgress(progress);
        document.setUpdatedAt(LocalDateTime.now());

        if (target == DocumentStatus.PROCESSING && document.getProcessingStartedAt() == null) {
            document.setProcessingStartedAt(LocalDateTime.now());
        }
        if (target == DocumentStatus.COMPLETED) {
            document.setProcessingCompletedAt(LocalDateTime.now());
        }
        if (error != null) {
            document.setProcessingError(error);
        }

        mutator.accept(document);
        documentRepository.save(document);
        log.debug("Document {} status updated to: {} (progress: {}%)", documentId, target, progress);
        return true;
    }

    /**
     * Broker publish failed after commit: move to FAILED with the reason so the document is
     * visible in the dashboard and can be retried with the existing retry endpoint.
     */
    public void markEnqueueFailed(UUID documentId, String error) {
        int updated = documentRepository.resetClaimedDocument(documentId,
                EnumSet.of(DocumentStatus.PROCESSING, DocumentStatus.UPLOADED),
                DocumentStatus.FAILED, error, LocalDateTime.now());
        if (updated == 0) {
            log.warn("Could not mark document {} as failed after enqueue error", documentId);
        }
    }

    /**
     * Publish only after the creating/creating-retry transaction has committed. A broker
     * outage leaves the document FAILED (with a visible reason), never half-created and
     * never silently stuck in PROCESSING.
     */
    private void enqueueAfterCommit(UUID documentId, UUID userId) {
        Runnable publish = () -> {
            try {
                jobQueue.enqueueDocumentProcessing(documentId, userId);
            } catch (Exception e) {
                log.error("Failed to enqueue document {} for processing; marking it FAILED for explicit retry",
                        documentId, e);
                try {
                    markEnqueueFailed(documentId, "Failed to enqueue processing job: " + e.getMessage());
                } catch (Exception resetFailure) {
                    log.error("Could not mark document {} FAILED after enqueue failure",
                            documentId, resetFailure);
                }
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish.run();
                }
            });
        } else {
            publish.run();
        }
    }

    /**
     * Safely parse a client-supplied status value into the enum, producing a 400 rather than
     * an unhandled 500 when the value is not a valid status.
     */
    private DocumentStatus parseStatus(String value) {
        try {
            return DocumentStatus.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Invalid status '" + value + "'. Allowed values: " + Arrays.toString(DocumentStatus.values()));
        }
    }

    // Helper methods

    private String getFileExtension(String fileName) {
        if (fileName == null || !fileName.contains(".")) {
            return "";
        }
        return fileName.substring(fileName.lastIndexOf(".") + 1).toLowerCase();
    }

    private boolean isAllowedFileType(String extension) {
        String[] allowed = allowedFileTypes.split(",");
        for (String type : allowed) {
            if (type.trim().equals(extension)) {
                return true;
            }
        }
        return false;
    }

    private DocumentResponseDto mapToResponseDto(Document document) {
        return DocumentResponseDto.builder()
                .id(document.getId())
                .fileName(document.getFileName())
                .fileSize(document.getFileSize())
                .mimeType(document.getMimeType())
                .pageCount(document.getPageCount())
                .description(document.getDescription())
                .status(document.getStatus().name())
                .processingProgress(document.getProcessingProgress())
                .processingError(document.getProcessingError())
                .createdAt(document.getCreatedAt())
                .updatedAt(document.getUpdatedAt())
                .processingStartedAt(document.getProcessingStartedAt())
                .processingCompletedAt(document.getProcessingCompletedAt())
                .wordCount(document.getWordCount())
                .characterCount(document.getCharacterCount())
                .summary(document.getSummary())
                .keyTopics(document.getKeyTopics())
                .sentiment(document.getSentiment())
                .confidenceScore(document.getConfidenceScore())
                .build();
    }

    private DocumentListItemDto mapToListItemDto(Document document) {
        return DocumentListItemDto.builder()
                .id(document.getId())
                .fileName(document.getFileName())
                .fileSize(document.getFileSize())
                .status(document.getStatus().name())
                .processingProgress(document.getProcessingProgress())
                .sentiment(document.getSentiment())
                .wordCount(document.getWordCount())
                .summary(document.getSummary())
                .processingError(document.getProcessingError())
                .createdAt(document.getCreatedAt())
                .processingCompletedAt(document.getProcessingCompletedAt())
                .build();
    }

}
