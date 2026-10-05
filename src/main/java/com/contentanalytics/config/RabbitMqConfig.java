package com.contentanalytics.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ topology for document processing.
 * <p>
 * Only created when {@code app.queue.type=rabbitmq}, so no connection is attempted in the
 * default in-process mode.
 * <p>
 * Declarative queues are used so the topology is reproducible across environments.
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

    @Bean
    public DirectExchange contentAnalyticsExchange() {
        return new DirectExchange(exchange, true, false);
    }

    /**
     * Durable queue: messages survive a broker restart.
     */
    @Bean
    public Queue documentProcessingQueue() {
        return new Queue(queue, true);
    }

    @Bean
    public Binding documentProcessingBinding() {
        return BindingBuilder.bind(documentProcessingQueue())
                .to(contentAnalyticsExchange())
                .with(routingKey);
    }

}