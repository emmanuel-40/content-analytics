package com.contentanalytics.service;

/**
 * Abstraction over the background job queue.
 * <p>
 * Two implementations exist so the application runs both with and without external
 * infrastructure:
 * <ul>
 *   <li>{@link AsyncJobQueue} - in-process, used by default. Zero setup.</li>
 *   <li>{@link RabbitMqJobQueue} - durable broker, selected with {@code app.queue.type=rabbitmq}.</li>
 * </ul>
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

}