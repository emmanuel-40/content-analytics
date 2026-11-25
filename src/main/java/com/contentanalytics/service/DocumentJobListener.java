package com.contentanalytics.service;

import com.contentanalytics.service.DocumentProcessingService.JobOutcome;
import com.contentanalytics.service.JobQueue.DocumentJob;
import com.contentanalytics.service.RabbitMqJobQueue.DocumentJobMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Consumes document-processing jobs published by {@link RabbitMqJobQueue}.
 *
 * Only registered when {@code app.queue.type=rabbitmq}, so the application starts normally
 * without a broker. Acknowledgements are manual: the broker delivery is ACKed only after the
 * guarded processor has finished, and NACKed without requeue when retries are exhausted so it
 * lands in the dead-letter queue. Retry counting lives in {@link DocumentProcessingService},
 * never here.
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "app.queue.type", havingValue = "rabbitmq")
public class DocumentJobListener {

    private final DocumentProcessingService documentProcessingService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DocumentJobListener(DocumentProcessingService documentProcessingService) {
        this.documentProcessingService = documentProcessingService;
    }

    @RabbitListener(queues = "${app.queue.document-queue:document-processing}", ackMode = "MANUAL")
    public void onDocumentJob(Message message, Channel channel,
                              @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        JobOutcome outcome;
        try {
            DocumentJob job = toJob(message);
            log.info("Consuming document job {} (document {}, attempt {})",
                    job.jobId(), job.documentId(), job.attempt());
            outcome = documentProcessingService.processJob(job);

            // Terminal failure: do not requeue; the DLQ keeps it for inspection.
            settle(channel, deliveryTag, outcome != JobOutcome.FAILED);
            if (outcome == JobOutcome.FAILED) {
                log.warn("Document job delivery {} moved to dead-letter queue", deliveryTag);
            }
            return;
        } catch (Exception e) {
            // Malformed payload or unexpected processor failure: never requeue a poison pill
            // forever. The DLQ receives it instead.
            log.error("Failed to process document job delivery {}: {}", deliveryTag, e.getMessage(), e);
        }

        try {
            channel.basicNack(deliveryTag, false, false);
        } catch (Exception nackFailure) {
            log.error("Could not NACK delivery {}", deliveryTag, nackFailure);
        }
    }

    /**
     * Acknowledge exactly once. A transport-level ACK failure is logged rather than retried
     * with a NACK, which would risk double-settling the same delivery.
     */
    private void settle(Channel channel, long deliveryTag, boolean success) {
        try {
            if (success) {
                channel.basicAck(deliveryTag, false);
            } else {
                channel.basicNack(deliveryTag, false, false);
            }
        } catch (Exception e) {
            log.error("Could not settle RabbitMQ delivery {} (success={})", deliveryTag, success, e);
        }
    }

    /**
     * Resolve the stable job id/attempt from headers when present (broker redelivery), falling
     * back to the JSON body for producers that only send a payload.
     */
    private DocumentJob toJob(Message message) throws Exception {
        DocumentJobMessage payload = objectMapper.readValue(message.getBody(), DocumentJobMessage.class);
        DocumentJob job = payload.toJob();

        Object jobIdHeader = message.getMessageProperties().getHeader(RabbitMqJobQueue.JOB_ID_HEADER);
        if (jobIdHeader instanceof UUID uuid) {
            job = new DocumentJob(uuid, job.documentId(), job.userId(), job.attempt());
        } else if (jobIdHeader instanceof String text && !text.isBlank()) {
            job = new DocumentJob(UUID.fromString(text), job.documentId(), job.userId(), job.attempt());
        }

        if (jobIdHeader == null) {
            String messageId = message.getMessageProperties().getMessageId();
            if (messageId != null && !messageId.isBlank()) {
                job = new DocumentJob(UUID.fromString(messageId), job.documentId(), job.userId(), job.attempt());
            }
        }

        Object attemptHeader = message.getMessageProperties().getHeader(RabbitMqJobQueue.ATTEMPT_HEADER);
        if (attemptHeader instanceof Number number && number.intValue() > 0) {
            job = new DocumentJob(job.jobId(), job.documentId(), job.userId(), number.intValue());
        }
        return job;
    }

}
