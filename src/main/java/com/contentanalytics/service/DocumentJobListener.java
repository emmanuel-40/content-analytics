package com.contentanalytics.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Consumes document-processing jobs published by {@link RabbitMqJobQueue}.
 * <p>
 * Only registered when {@code app.queue.type=rabbitmq}, so the application starts normally
 * without a broker.
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

    @RabbitListener(queues = "${app.queue.document-queue:document-processing}")
    public void onDocumentJob(byte[] payload) {
        UUID documentId = null;

        try {
            RabbitMqJobQueue.DocumentJobMessage message =
                    objectMapper.readValue(payload, RabbitMqJobQueue.DocumentJobMessage.class);

            documentId = message.documentId();
            log.info("Consuming document job: {}", documentId);

            documentProcessingService.queueForProcessing(documentId);

        } catch (Exception e) {
            // Throwing would requeue the message and cause an infinite retry loop for a
            // permanently bad payload, so log and drop instead.
            log.error("Failed to process document job {}: {}", documentId, e.getMessage(), e);
        }
    }

}