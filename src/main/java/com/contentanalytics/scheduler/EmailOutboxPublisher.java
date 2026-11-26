package com.contentanalytics.scheduler;

import com.contentanalytics.entity.EmailOutbox;
import com.contentanalytics.repository.EmailOutboxRepository;
import com.contentanalytics.service.EmailQueue;
import com.contentanalytics.service.EmailQueue.EmailJobRef;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Moves committed outbox rows to the configured mail transport.
 *
 * The row is claimed with a conditional UPDATE before publishing, so multiple application
 * instances cannot publish the same email. Broker I/O stays outside any DB transaction.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class EmailOutboxPublisher {

    private final EmailOutboxRepository emailOutboxRepository;
    private final EmailQueue emailQueue;

    @Value("${app.email.outbox.batch-size:100}")
    private int batchSize;

    @Value("${app.email.outbox.grace-seconds:5}")
    private int graceSeconds;

    @Value("${app.email.max-attempts:5}")
    private int maxAttempts;

    @Scheduled(
            initialDelayString = "${app.email.outbox.initial-delay-ms:10000}",
            fixedDelayString = "${app.email.outbox.interval-ms:30000}")
    public void publishPending() {
        int size = Math.max(1, batchSize);
        LocalDateTime cutoff = LocalDateTime.now().minusSeconds(Math.max(0, graceSeconds));

        List<EmailOutbox> candidates = emailOutboxRepository.findPending(
                cutoff, PageRequest.of(0, size));
        if (candidates.isEmpty()) {
            return;
        }

        log.debug("Email outbox publisher checking {} pending row(s)", candidates.size());
        for (EmailOutbox candidate : candidates) {
            LocalDateTime now = LocalDateTime.now();
            if (emailOutboxRepository.claimForPublish(candidate.getId(), now) == 0) {
                continue; // another instance claimed it first
            }
            EmailJobRef ref = new EmailJobRef(candidate.getId(), candidate.getDedupeKey());
            try {
                emailQueue.enqueue(ref);
            } catch (RuntimeException e) {
                // Both transports normally absorb failures; keep the row retryable anyway.
                emailOutboxRepository.recordFailure(candidate.getId(),
                        "publish failed: " + e.getMessage(), maxAttempts);
                log.error("Could not publish email job {}", candidate.getId(), e);
            }
        }
    }

}
