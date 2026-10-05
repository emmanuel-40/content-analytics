package com.contentanalytics.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Default {@link JobQueue} implementation: executes work in-process on the async executor.
 * <p>
 * Requires no external broker, which keeps local development and CI dependency-free.
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "app.queue.type", havingValue = "async", matchIfMissing = true)
public class AsyncJobQueue implements JobQueue {

    private final DocumentProcessingService documentProcessingService;

    public AsyncJobQueue(DocumentProcessingService documentProcessingService) {
        this.documentProcessingService = documentProcessingService;
        log.info("Job queue: in-process (@Async)");
    }

    @Override
    public void enqueueDocumentProcessing(UUID documentId, UUID userId) {
        log.debug("Queueing document {} for in-process processing", documentId);
        documentProcessingService.queueForProcessing(documentId);
    }

    @Override
    public String getImplementationName() {
        return "async";
    }

}