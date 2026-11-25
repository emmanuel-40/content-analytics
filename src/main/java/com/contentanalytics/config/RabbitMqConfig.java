package com.contentanalytics.config;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ topology for document processing.
 *
 * Only created when {@code app.queue.type=rabbitmq}, so no connection is attempted in the
 * default in-process mode. Every queue is durable and the work queue dead-letters rejected
 * deliveries instead of requeuing them forever.
 */
@Configuration
@ConditionalOnProperty(name = "app.queue.type", havingValue = "rabbitmq")
public class RabbitMqConfig {

    @Value("${app.queue.exchange:content-analytics}")
    private String exchange;

    @Value("${app.queue.document-queue:document-processing}")
    private String queue;

    @Value("${app.queue.routing-key:document.process}")
    private String routingKey;

    @Value("${app.queue.exchange:content-analytics}.dlq")
    private String deadLetterExchange;

    @Value("${app.queue.document-queue:document-processing}.dlq")
    private String deadLetterQueue;

    @Bean
    public DirectExchange contentAnalyticsExchange() {
        return new DirectExchange(exchange, true, false);
    }

    /**
     * Durable queue: messages survive a broker restart. Rejects (NACK without requeue) are
     * routed to the DLQ, where exhausted jobs are kept for inspection.
     */
    @Bean
    public Queue documentProcessingQueue() {
        return QueueBuilder.durable(queue)
                .deadLetterExchange(deadLetterExchange)
                .deadLetterRoutingKey(deadLetterQueue)
                .build();
    }

    @Bean
    public Binding documentProcessingBinding() {
        return BindingBuilder.bind(documentProcessingQueue())
                .to(contentAnalyticsExchange())
                .with(routingKey);
    }

    @Bean
    public DirectExchange contentAnalyticsDeadLetterExchange() {
        return new DirectExchange(deadLetterExchange, true, false);
    }

    @Bean
    public Queue documentDeadLetterQueue() {
        return QueueBuilder.durable(deadLetterQueue).build();
    }

    @Bean
    public Binding documentDeadLetterBinding() {
        return BindingBuilder.bind(documentDeadLetterQueue())
                .to(contentAnalyticsDeadLetterExchange())
                .with(deadLetterQueue);
    }

    /**
     * Explicit manual acknowledgement. The listener ACKs/NACKs only after the guarded
     * processor has finished; rejected deliveries go straight to the DLQ.
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        factory.setContainerCustomizer(container -> container.setDefaultRequeueRejected(false));
        return factory;
    }

}
