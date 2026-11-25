package com.contentanalytics.service;

/**
 * Abstraction over the background job queue.
 *
 * Two implementations exist so the application runs both with and without external
 * infrastructure:
 *
 *   {@link AsyncJobQueue} - in-process, used by default. Zero setup.
 * {@link RabbitMqJobQueue} - durable broker, selected with {@code app.queue.type=rabbitmq}.
 *
 * Keeping this behind an interface means the broker is swappable configuration rather than
 * a code change.
 */
public interface JobQueue {

    /**
     * Queue a document for processing.
     *
     * @param documentId the document to analyse
     * @param userId     the owning user, used for WebSocket progress updates
     */
    void enqueueDocumentProcessing(java.util.UUID documentId, java.util.UUID userId);

    /**
     * @return the active implementation's name, for logging and health endpoints.
     */
    String getImplementationName();

    /**
     * Shared job contract used by every transport (in-process {@code @Async} and RabbitMQ).
     * The job id is minted once at enqueue time and is carried unchanged through retries,
     * so a document can never be processed twice from two copies of the same logical job.
     *
     * @param jobId     stable correlation id for the logical job
     * @param documentId document to process
     * @param userId    owning user (used for notifications)
     * @param attempt   1-based delivery attempt
     */
    record DocumentJob(java.util.UUID jobId, java.util.UUID documentId,
                       java.util.UUID userId, int attempt) {

        public static DocumentJob of(java.util.UUID documentId, java.util.UUID userId) {
            return new DocumentJob(java.util.UUID.randomUUID(), documentId, userId, 1);
        }
    }

}
