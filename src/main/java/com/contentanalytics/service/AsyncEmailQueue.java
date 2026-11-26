package com.contentanalytics.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Broker-free email transport. Used by tests and whenever {@code app.queue.type=async}.
 */
@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.queue.type", havingValue = "async", matchIfMissing = true)
public class AsyncEmailQueue implements EmailQueue {

    private final EmailDeliveryService emailDeliveryService;

    @Override
    public void enqueue(EmailJobRef job) {
        log.debug("Delivering email job {} in-process", job.jobId());
        emailDeliveryService.deliverAsync(job);
    }

    @Override
    public String getImplementationName() {
        return "async";
    }
}
