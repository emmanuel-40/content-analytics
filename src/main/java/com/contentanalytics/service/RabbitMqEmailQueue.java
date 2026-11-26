package com.contentanalytics.service;

import com.contentanalytics.service.EmailQueue.EmailJobRef;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * RabbitMQ transport for committed outbox rows.
 *
 * The message carries only the job id: the consumer reloads the row from the database, so a
 * redelivered message can never re-send an already delivered email. A broker outage falls
 * back to the same in-process delivery path rather than losing the notification.
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "app.queue.type", havingValue = "rabbitmq")
public class RabbitMqEmailQueue implements EmailQueue {

    public static final String JOB_ID_HEADER = "x-email-job-id";
    public static final String DEDUPE_KEY_HEADER = "x-email-dedupe-key";

    private final RabbitTemplate rabbitTemplate;
    private final EmailDeliveryService emailDeliveryService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.queue.exchange:content-analytics}")
    private String exchange;

    @Value("${app.queue.email-routing-key:email.deliver}")
    private String routingKey;

    public RabbitMqEmailQueue(RabbitTemplate rabbitTemplate,
                              EmailDeliveryService emailDeliveryService) {
        this.rabbitTemplate = rabbitTemplate;
        this.emailDeliveryService = emailDeliveryService;
        log.info("Email queue: RabbitMQ (exchange={}, routingKey={})", exchange, routingKey);
    }

    @Override
    public void enqueue(EmailJobRef job) {
        try {
            byte[] body = objectMapper.writeValueAsBytes(new EmailJobMessage(job.jobId(), job.dedupeKey()));
            rabbitTemplate.convertAndSend(exchange, routingKey, body, message -> {
                message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                message.getMessageProperties().setContentType("application/json");
                message.getMessageProperties().setMessageId(job.jobId().toString());
                message.getMessageProperties().setHeader(JOB_ID_HEADER, job.jobId().toString());
                message.getMessageProperties().setHeader(DEDUPE_KEY_HEADER, job.dedupeKey());
                return message;
            });
            log.debug("Published email job {} to RabbitMQ", job.jobId());
        } catch (Exception e) {
            log.error("Failed to publish email job {} - falling back to in-process delivery",
                    job.jobId(), e);
            emailDeliveryService.deliverAsync(job);
        }
    }

    @Override
    public String getImplementationName() {
        return "rabbitmq";
    }

    public record EmailJobMessage(UUID jobId, String dedupeKey) {
    }
}
