package com.contentanalytics.service;

import com.contentanalytics.service.JobQueue.DocumentJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Default {@link JobQueue} implementation: executes work in-process on the async executor.
 *
 * Requires no external broker, which keeps local development and CI dependency-free.
 * It publishes the same {@link DocumentJob} contract as RabbitMQ and uses the very same
 * guarded retry path in {@link DocumentProcessingService#processJob(DocumentJob)}.
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "app.queue.type", havingValue = "async", matchIfMissing = true)
public class AsyncJobQueue implements JobQueue {

    // Resolved lazily: DocumentService - JobQueue - Worker - DocumentService is a
    // constructor cycle if the worker is injected eagerly.
    private final ObjectProvider<DocumentProcessingService> documentProcessingService;

    public AsyncJobQueue(ObjectProvider<DocumentProcessingService> documentProcessingService) {
        this.documentProcessingService = documentProcessingService;
        log.info("Job queue: in-process (@Async)");
    }

    @Override
    public void enqueueDocumentProcessing(UUID documentId, UUID userId) {
        DocumentJob job = DocumentJob.of(documentId, userId);
        log.debug("Queueing document job {} for in-process processing", job.jobId());
        documentProcessingService.getObject().processJobAsync(job);
    }

    @Override
    public String getImplementationName() {
        return "async";
    }

}
