package com.contentanalytics.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * RabbitMQ-backed {@link JobQueue}.
 * <p>
 * Enabled with {@code app.queue.type=rabbitmq}. The broker (for example a CloudAMQP free-tier
 * instance) provides durability: if the application restarts mid-analysis the job stays
 * queued instead of being lost.
 * <p>
 * If the broker cannot be reached the job is executed in-process instead, so an outage of the
 * message broker degrades performance rather than functionality.
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "app.queue.type", havingValue = "rabbitmq")
public class RabbitMqJobQueue implements JobQueue {

    private final RabbitTemplate rabbitTemplate;
    private final DocumentProcessingService documentProcessingService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.queue.exchange:content-analytics}")
    private String exchange;

    @Value("${app.queue.routing-key:document.process}")
    private String routingKey;

    public RabbitMqJobQueue(RabbitTemplate rabbitTemplate,
                            DocumentProcessingService documentProcessingService) {
        this.rabbitTemplate = rabbitTemplate;
        this.documentProcessingService = documentProcessingService;
        log.info("Job queue: RabbitMQ (exchange={})", exchange);
    }

    @Override
    public void enqueueDocumentProcessing(UUID documentId, UUID userId) {
        try {
            DocumentJobMessage payload = new DocumentJobMessage(documentId, userId);
            byte[] body = objectMapper.writeValueAsBytes(payload);

            rabbitTemplate.convertAndSend(exchange, routingKey, body, message -> {
                message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                message.getMessageProperties().setContentType("application/json");
                return message;
            });

            log.debug("Published document {} to RabbitMQ", documentId);

        } catch (Exception e) {
            // Never lose the job because the broker is down - process it locally instead.
            log.error("Failed to publish document {} to RabbitMQ, falling back to in-process processing",
                    documentId, e);
            documentProcessingService.queueForProcessing(documentId);
        }
    }

    @Override
    public String getImplementationName() {
        return "rabbitmq";
    }

    /**
     * Payload published to the queue and consumed by {@link DocumentJobListener}.
     */
    public record DocumentJobMessage(UUID documentId, UUID userId) {
    }

}