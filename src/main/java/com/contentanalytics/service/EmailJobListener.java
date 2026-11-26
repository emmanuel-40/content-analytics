package com.contentanalytics.service;

import com.contentanalytics.service.EmailQueue.EmailJobRef;
import com.contentanalytics.service.RabbitMqEmailQueue.EmailJobMessage;
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
 * Consumes durable email jobs. Manual ACK means a failed delivery goes to the dead-letter
 * queue; the outbox row is reset to PENDING so the publisher can retry it with fresh state.
 */
@Component
@Slf4j
@ConditionalOnProperty(name = "app.queue.type", havingValue = "rabbitmq")
public class EmailJobListener {

    private final EmailDeliveryService emailDeliveryService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EmailJobListener(EmailDeliveryService emailDeliveryService) {
        this.emailDeliveryService = emailDeliveryService;
    }

    @RabbitListener(queues = "${app.queue.email-queue:email-delivery}", ackMode = "MANUAL")
    public void onEmailJob(Message message, Channel channel,
                           @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag) {
        try {
            EmailJobRef job = toJobRef(message);
            log.info("Consuming email job {} ({})", job.jobId(), job.dedupeKey());
            emailDeliveryService.deliver(job);
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            log.error("Failed to deliver email job (delivery {}): {}", deliveryTag, e.getMessage(), e);
            try {
                // Do not requeue a poison message forever; the DLQ keeps it for inspection.
                channel.basicNack(deliveryTag, false, false);
            } catch (Exception nackFailure) {
                log.error("Could not NACK email delivery {}", deliveryTag, nackFailure);
            }
        }
    }

    private EmailJobRef toJobRef(Message message) throws Exception {
        String jobId = null;
        String dedupeKey = null;
        try {
            EmailJobMessage payload = objectMapper.readValue(message.getBody(), EmailJobMessage.class);
            if (payload != null) {
                jobId = payload.jobId() == null ? null : payload.jobId().toString();
                dedupeKey = payload.dedupeKey();
            }
        } catch (Exception ignored) {
            // Headers are authoritative; a legacy/plain-id body is still accepted below.
        }

        Object headerId = message.getMessageProperties().getHeader(RabbitMqEmailQueue.JOB_ID_HEADER);
        if (headerId instanceof UUID uuid) {
            jobId = uuid.toString();
        } else if (headerId != null && !headerId.toString().isBlank()) {
            jobId = headerId.toString();
        } else if (jobId == null && message.getMessageProperties().getMessageId() != null) {
            jobId = message.getMessageProperties().getMessageId();
        }

        Object headerKey = message.getMessageProperties().getHeader(RabbitMqEmailQueue.DEDUPE_KEY_HEADER);
        if (headerKey != null) {
            dedupeKey = headerKey.toString();
        }

        if (jobId == null || jobId.isBlank()) {
            throw new IllegalArgumentException("Email message has no job id");
        }
        return new EmailJobRef(UUID.fromString(jobId), dedupeKey);
    }

}
