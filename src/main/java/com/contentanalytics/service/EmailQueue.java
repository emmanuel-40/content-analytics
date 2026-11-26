package com.contentanalytics.service;

import java.util.UUID;

/**
 * Transport for outbox rows handed to a mail worker.
 *
 * As with document jobs there are two implementations: RabbitMQ (durable) and in-process
 * {@code @Async} (zero setup, used by tests and as a broker-down fallback). The outbox row
 * itself is the source of truth, so a message only needs to carry the job id.
 */
public interface EmailQueue {

    void enqueue(EmailJobRef job);

    String getImplementationName();

    /**
     * @param jobId     primary key of the committed email_outbox row
     * @param dedupeKey stable logical key, carried for logs and broker headers
     */
    record EmailJobRef(UUID jobId, String dedupeKey) {
    }
}
