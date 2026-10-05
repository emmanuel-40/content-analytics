package com.contentanalytics.service;

import com.contentanalytics.entity.Document;
import com.contentanalytics.entity.DocumentStatus;
import com.contentanalytics.exception.InsufficientCreditsException;
import com.contentanalytics.repository.DocumentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class DocumentProcessingService {

    private final WebSocketService webSocketService;
    private final DocumentRepository documentRepository;
    private final FileExtractionService fileExtractionService;
    private final AiAnalysisService aiAnalysisService;
    private final CreditService creditService;

    /**
     * Credits charged for a single AI analysis.
     */
    private static final int AI_ANALYSIS_CREDIT_COST = 1;

    @Value("${app.anthropic.max-tokens:2048}")
    private int maxTokens;

    /**
     * Queue document for asynchronous processing.
     * <p>
     * This method is the async entry point and MUST be invoked through the Spring proxy
     * (i.e. injected by another bean). Calling an {@code @Async} method from within the same
     * bean bypasses the proxy and silently degrades to synchronous execution.
     */
    @Async
    public void queueForProcessing(UUID documentId) {
        log.info("Queuing document for processing: {}", documentId);
        try {
            processDocument(documentId);
        } catch (Exception e) {
            log.error("Error processing document: {}", documentId, e);
            updateProcessingStatus(documentId, DocumentStatus.FAILED, 0f, e.getMessage());
        }
    }

    /**
     * Main document processing logic
     * Basic analysis (word count, character count) always works
     * AI analysis (Claude) is optional and gracefully degraded if disabled
     */
    private void processDocument(UUID documentId) {
        log.info("Starting processing for document: {}", documentId);

        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new RuntimeException("Document not found: " + documentId));

        UUID userId = document.getUser().getId();

        try {
            long startTime = System.currentTimeMillis();
            // Step 1: Update status to PROCESSING
            updateProcessingStatus(documentId, DocumentStatus.PROCESSING, 10f, null);
            webSocketService.sendDocumentProgress(userId, documentId, document.getFileName(), 10f, "PROCESSING", "extracting_text");

            // Step 2: Extract text from file
            log.debug("Extracting text from document: {}", documentId);
            String extractedText = fileExtractionService.extractText(document.getFileKey(), document.getMimeType());

            if (extractedText == null || extractedText.isBlank()) {
                throw new RuntimeException("Failed to extract text from document");
            }

            updateProcessingStatus(documentId, DocumentStatus.PROCESSING, 30f, null);
            webSocketService.sendDocumentProgress(userId, documentId, document.getFileName(), 30f, "PROCESSING", "analyzing");
            log.debug("Text extracted. Length: {} characters", extractedText.length());

            // Step 3: Basic Analysis (ALWAYS works - no Claude needed)
            log.debug("Performing basic analysis for document: {}", documentId);
            int wordCount = countWords(extractedText);
            int charCount = extractedText.length();

            document.setExtractedText(extractedText);
            document.setWordCount(wordCount);
            document.setCharacterCount(charCount);

            log.debug("Basic analysis complete - Words: {}, Characters: {}", wordCount, charCount);

            // Step 4: AI Analysis (OPTIONAL - only if Claude enabled)
            DocumentAnalysisResult analysis = null;

            if (aiAnalysisService.isEnabled()) {
                // Charge the credit before doing the work. If the balance is empty we still
                // complete basic analysis rather than failing the document outright.
                if (creditService.hasCredits(userId)) {
                    try {
                        creditService.consumeCredits(userId, AI_ANALYSIS_CREDIT_COST, documentId);
                        log.debug("Analysing document via AI provider '{}': {}", documentId,
                                aiAnalysisService.getActiveProvider());
                        analysis = aiAnalysisService.analyze(extractedText);

                        if (analysis != null) {
                            document.setSummary(analysis.getSummary());
                            document.setKeyTopics(analysis.getKeyTopics());
                            document.setSentiment(analysis.getSentiment());
                            document.setConfidenceScore(analysis.getConfidenceScore());
                            log.debug("AI analysis complete for document: {}", documentId);
                        }
                    } catch (InsufficientCreditsException e) {
                        log.warn("No credits available for document {}, continuing with basic analysis: {}",
                                documentId, e.getMessage());
                    } catch (Exception e) {
                        // Log warning but don't fail - basic analysis is enough
                        log.warn("AI analysis failed for document {}, continuing with basic analysis: {}",
                                documentId, e.getMessage());
                    }
                } else {
                    log.info("User {} has no credits; skipping AI analysis for document {}",
                            userId, documentId);
                }
            } else {
                log.debug("No AI provider available, skipping AI analysis for document: {}", documentId);
            }

            updateProcessingStatus(documentId, DocumentStatus.PROCESSING, 70f, null);
            webSocketService.sendDocumentProgress(userId, documentId, document.getFileName(), 70f, "PROCESSING", "saving_results");

            // Step 5: Mark COMPLETED (with or without AI analysis)
            document.setStatus(DocumentStatus.COMPLETED);
            document.setProcessingProgress(100f);
            document.setProcessingCompletedAt(java.time.LocalDateTime.now());

            documentRepository.save(document);

            // Send completion update
            long processingTime = System.currentTimeMillis() - startTime;

            // AFTER (CORRECT):
            String summary = analysis != null ? analysis.getSummary() : null;
            String topics = null;
            if (analysis != null && analysis.getKeyTopics() != null) {
                topics = String.join(",", analysis.getKeyTopics());  // ← Convert List to comma-separated String
            }
            String sentiment = analysis != null ? analysis.getSentiment() : null;
            Float confidenceScore = analysis != null ? analysis.getConfidenceScore() : null;

            webSocketService.sendDocumentCompleted(
                    userId,
                    documentId,
                    document.getFileName(),
                    summary,
                    topics,  // ← Now it's String!
                    sentiment,
                    confidenceScore,
                    document.getWordCount(),
                    processingTime
            );

            log.info("Document processing completed successfully: {}", documentId);

        } catch (Exception e) {
            log.error("Error during document processing: {}", documentId, e);
            updateProcessingStatus(documentId, DocumentStatus.FAILED, 0f, e.getMessage());

            webSocketService.sendDocumentFailed(
                    userId,
                    documentId,
                    document.getFileName(),
                    e.getMessage(),
                    "PROCESSING_ERROR"
            );
        }
    }

    /**
     * Update document processing status
     */
    private void updateProcessingStatus(UUID documentId, DocumentStatus status, Float progress, String error) {
        Document document = documentRepository.findById(documentId)
                .orElse(null);

        if (document == null) {
            log.warn("Document not found for status update: {}", documentId);
            return;
        }

        document.setStatus(status);
        document.setProcessingProgress(progress);

        if (status == DocumentStatus.PROCESSING && document.getProcessingStartedAt() == null) {
            document.setProcessingStartedAt(java.time.LocalDateTime.now());
        }

        if (status == DocumentStatus.COMPLETED) {
            document.setProcessingCompletedAt(java.time.LocalDateTime.now());
        }

        if (error != null) {
            document.setProcessingError(error);
        }

        documentRepository.save(document);
        log.debug("Document {} status updated to: {} (progress: {}%)", documentId, status, progress);
    }

    /**
     * Retry processing for a failed document.
     * <p>
     * Async entry point - must be called through the Spring proxy.
     */
    @Async
    public void retryDocumentProcessing(UUID documentId) {
        log.info("Retrying processing for document: {}", documentId);

        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new RuntimeException("Document not found: " + documentId));

        // Reset status
        document.setStatus(DocumentStatus.UPLOADED);
        document.setProcessingProgress(0f);
        document.setProcessingError(null);
        documentRepository.save(document);

        // Process again - direct call is safe now that this method is already async
        try {
            processDocument(documentId);
        } catch (Exception e) {
            log.error("Error retrying document: {}", documentId, e);
            updateProcessingStatus(documentId, DocumentStatus.FAILED, 0f, e.getMessage());
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
