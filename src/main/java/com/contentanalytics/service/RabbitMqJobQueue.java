package com.contentanalytics.service;

import com.contentanalytics.service.JobQueue.DocumentJob;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * RabbitMQ-backed {@link JobQueue}.
 *
 * Enabled with {@code app.queue.type=rabbitmq}. The broker (for example a CloudAMQP free-tier
 * instance) provides durability: if the application restarts mid-analysis the job stays
 * queued instead of being lost. The job id is stable across redeliveries and is present both
 * in the JSON body and in AMQP headers, so the listener can deduplicate reliably.
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "app.queue.type", havingValue = "rabbitmq")
public class RabbitMqJobQueue implements JobQueue {

    public static final String JOB_ID_HEADER = "x-job-id";
    public static final String ATTEMPT_HEADER = "x-document-attempt";

    private final RabbitTemplate rabbitTemplate;
    // Lazy to break the DocumentService - JobQueue - Worker - DocumentService cycle.
    private final ObjectProvider<DocumentProcessingService> documentProcessingService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.queue.exchange:content-analytics}")
    private String exchange;

    @Value("${app.queue.routing-key:document.process}")
    private String routingKey;

    public RabbitMqJobQueue(RabbitTemplate rabbitTemplate,
                            ObjectProvider<DocumentProcessingService> documentProcessingService) {
        this.rabbitTemplate = rabbitTemplate;
        this.documentProcessingService = documentProcessingService;
        log.info("Job queue: RabbitMQ (exchange={})", exchange);
    }

    @Override
    public void enqueueDocumentProcessing(UUID documentId, UUID userId) {
        DocumentJob job = DocumentJob.of(documentId, userId);
        try {
            DocumentJobMessage payload = DocumentJobMessage.from(job);
            byte[] body = objectMapper.writeValueAsBytes(payload);

            rabbitTemplate.convertAndSend(exchange, routingKey, body, message -> {
                message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                message.getMessageProperties().setContentType("application/json");
                // Stable correlation id + attempt count survive broker redeliveries.
                message.getMessageProperties().setMessageId(job.jobId().toString());
                message.getMessageProperties().setHeader(JOB_ID_HEADER, job.jobId().toString());
                message.getMessageProperties().setHeader(ATTEMPT_HEADER, job.attempt());
                return message;
            });

            log.debug("Published document job {} (document {}) to RabbitMQ", job.jobId(), documentId);

        } catch (Exception e) {
            // Never lose the job because the broker is down: process it with the same
            // contract/retry path in-process. DocumentService keeps the row retryable.
            log.error("Failed to publish document job {} to RabbitMQ, falling back to in-process processing",
                    job.jobId(), e);
            documentProcessingService.getObject().processJobAsync(job);
        }
    }

    @Override
    public String getImplementationName() {
        return "rabbitmq";
    }

    /**
     * Payload published to the queue and consumed by {@link DocumentJobListener}.
     */
    public record DocumentJobMessage(UUID jobId, UUID documentId, UUID userId, int attempt) {

        public static DocumentJobMessage from(DocumentJob job) {
            return new DocumentJobMessage(job.jobId(), job.documentId(), job.userId(), job.attempt());
        }

        public DocumentJob toJob() {
            UUID stableJobId = jobId != null ? jobId : UUID.randomUUID();
            int stableAttempt = attempt > 0 ? attempt : 1;
            return new DocumentJob(stableJobId, documentId, userId, stableAttempt);
        }
    }

}
