package com.contentanalytics.service;

import com.contentanalytics.entity.DocumentStatus;
import com.contentanalytics.exception.InsufficientCreditsException;
import com.contentanalytics.service.DocumentService.ClaimedDocument;
import com.contentanalytics.service.DocumentService.ProcessedDocument;
import com.contentanalytics.service.JobQueue.DocumentJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Document processing worker.
 *
 * All transports (in-process {@code @Async} and RabbitMQ) call {@link #processJob}, which is
 * the single place where a failed attempt is retried. The listener only decides whether to
 * ACK/NACK the broker delivery; it never retries on its own. A claim is taken through
 * {@link DocumentService#claimForProcessing(java.util.UUID)} so duplicate deliveries are
 * no-ops instead of double-processing the same document.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DocumentProcessingService {

    private final WebSocketService webSocketService;
    private final DocumentService documentService;
    private final FileExtractionService fileExtractionService;
    private final AiAnalysisService aiAnalysisService;
    private final CreditService creditService;

    /**
     * Credits charged for a single AI analysis.
     */
    private static final int AI_ANALYSIS_CREDIT_COST = 1;

    /**
     * Total attempts for one logical job, including the first delivery. Attempts are bounded
     * here (not by broker redelivery) so a poison document ends in FAILED + DLQ, not a loop.
     */
    public static final int MAX_ATTEMPTS = 3;

    @Value("${app.anthropic.max-tokens:2048}")
    private int maxTokens;

    public enum JobOutcome {
        COMPLETED, FAILED
    }

    private enum AttemptResult {
        DONE, RETRY, FAILED
    }

    /**
     * In-process entry point. Must be invoked through the Spring proxy so {@code @Async}
     * actually applies.
     */
    @Async
    public void processJobAsync(DocumentJob job) {
        processJob(job);
    }

    /**
     * The one guarded retry path shared by every queue implementation.
     */
    public JobOutcome processJob(DocumentJob job) {
        Objects.requireNonNull(job, "job");
        int firstAttempt = Math.max(1, job.attempt());

        for (int attempt = firstAttempt; attempt <= MAX_ATTEMPTS; attempt++) {
            AttemptResult result = attemptProcessing(job.documentId(), job.jobId(), attempt);
            if (result == AttemptResult.DONE) {
                return JobOutcome.COMPLETED;
            }
            if (result == AttemptResult.FAILED) {
                return JobOutcome.FAILED;
            }
            log.warn("Retrying document job {} (document {}) - attempt {}/{}",
                    job.jobId(), job.documentId(), attempt + 1, MAX_ATTEMPTS);
        }
        return JobOutcome.FAILED;
    }

    /**
     * Execute one attempt. Returns DONE for a successful or already-finished job, RETRY for
     * a transient failure with budget left, and FAILED when the terminal state is persisted.
     */
    private AttemptResult attemptProcessing(java.util.UUID documentId,
                                            java.util.UUID jobId, int attempt) {
        ClaimedDocument document = documentService.claimForProcessing(documentId);
        if (document == null) {
            // Another worker owns it, or it is already COMPLETED: duplicate delivery.
            log.info("Skipping duplicate/finished document job {} for document {}", jobId, documentId);
            return AttemptResult.DONE;
        }

        long startTime = System.currentTimeMillis();

        try {
            webSocketService.sendDocumentProgress(document.userId(), document.id(), document.fileName(),
                    10f, "PROCESSING", "extracting_text");

            String extractedText = fileExtractionService.extractText(document.fileKey(), document.mimeType());
            if (extractedText == null || extractedText.isBlank()) {
                throw new IllegalStateException("Failed to extract text from document");
            }

            requireTransition(documentService.updateProcessingStatus(
                    documentId, DocumentStatus.PROCESSING, 30f, null), documentId, DocumentStatus.PROCESSING);
            webSocketService.sendDocumentProgress(document.userId(), document.id(), document.fileName(),
                    30f, "PROCESSING", "analyzing");

            int wordCount = countWords(extractedText);
            int charCount = extractedText.length();

            // AI analysis is optional; basic analysis always succeeds. Credits are charged
            // idempotently per document, so a retry after a failed AI call is not double-billed.
            DocumentAnalysisResult analysis = null;
            if (aiAnalysisService.isEnabled()) {
                try {
                    creditService.consumeCredits(document.userId(), AI_ANALYSIS_CREDIT_COST, documentId);
                    analysis = aiAnalysisService.analyze(extractedText);
                } catch (InsufficientCreditsException e) {
                    log.warn("No credits available for document {}, continuing with basic analysis: {}",
                            documentId, e.getMessage());
                } catch (Exception e) {
                    log.warn("AI analysis failed for document {}, continuing with basic analysis: {}",
                            documentId, e.getMessage());
                }
            }

            requireTransition(documentService.updateProcessingStatus(
                    documentId, DocumentStatus.PROCESSING, 70f, null), documentId, DocumentStatus.PROCESSING);
            webSocketService.sendDocumentProgress(document.userId(), document.id(), document.fileName(),
                    70f, "PROCESSING", "saving_results");

            ProcessedDocument result = new ProcessedDocument(
                    extractedText,
                    wordCount,
                    charCount,
                    analysis != null ? analysis.getSummary() : null,
                    analysis != null ? analysis.getKeyTopics() : null,
                    analysis != null ? analysis.getSentiment() : null,
                    analysis != null ? analysis.getConfidenceScore() : null
            );

            // The DB status is committed inside completeProcessing before the notification
            // is sent, so clients never see COMPLETED before it is durable.
            boolean persisted = documentService.completeProcessing(documentId, result);
            if (!persisted) {
                log.warn("Document {} was no longer PROCESSING when results were saved - "
                        + "skipping completion notification", documentId);
                return AttemptResult.DONE;
            }

            webSocketService.sendDocumentCompleted(
                    document.userId(),
                    document.id(),
                    document.fileName(),
                    result.summary(),
                    result.keyTopics(),
                    result.sentiment(),
                    result.confidenceScore(),
                    result.wordCount(),
                    System.currentTimeMillis() - startTime
            );

            log.info("Document processing completed successfully: {} (job {})", documentId, jobId);
            return AttemptResult.DONE;

        } catch (Exception e) {
            log.error("Attempt {}/{} failed for document {} (job {})",
                    attempt, MAX_ATTEMPTS, documentId, jobId, e);

            if (attempt < MAX_ATTEMPTS) {
                // Make the row claimable again for the next attempt.
                documentService.resetForRetry(documentId, e.getMessage());
                return AttemptResult.RETRY;
            }

            if (documentService.failProcessing(documentId, e.getMessage())) {
                webSocketService.sendDocumentFailed(
                        document.userId(), document.id(), document.fileName(),
                        e.getMessage(), "PROCESSING_ERROR");
            } else {
                log.warn("Document {} was not PROCESSING at final failure - no notification sent",
                        documentId);
            }
            return AttemptResult.FAILED;
        }
    }

    private void requireTransition(boolean applied, java.util.UUID documentId, DocumentStatus expected) {
        if (!applied) {
            throw new IllegalStateException(
                    "Document " + documentId + " is no longer in state " + expected);
        }
    }

    /**
     * Count words in text
     */
    private Integer countWords(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return text.trim().split("\\s+").length;
    }

}
