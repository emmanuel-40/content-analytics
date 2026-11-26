package com.contentanalytics.scheduler;

import com.contentanalytics.entity.EmailOutbox;
import com.contentanalytics.repository.EmailOutboxRepository;
import com.contentanalytics.service.EmailQueue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Email outbox publisher")
class EmailOutboxPublisherTest {

    @Mock private EmailOutboxRepository emailOutboxRepository;
    @Mock private EmailQueue emailQueue;

    @InjectMocks private EmailOutboxPublisher publisher;

    private EmailOutbox row() {
        return EmailOutbox.builder()
                .id(UUID.randomUUID())
                .dedupeKey("EMAIL_VERIFICATION:" + UUID.randomUUID())
                .recipient("student@example.com")
                .status("PENDING")
                .createdAt(LocalDateTime.now().minusMinutes(1))
                .build();
    }

    @Test
    @DisplayName("only rows won by the conditional claim are published")
    void claimsBeforePublishing() {
        EmailOutbox winner = row();
        EmailOutbox lostRace = row();
        ReflectionTestUtils.setField(publisher, "batchSize", 10);
        ReflectionTestUtils.setField(publisher, "graceSeconds", 5);
        ReflectionTestUtils.setField(publisher, "maxAttempts", 5);

        when(emailOutboxRepository.findPending(any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(List.of(winner, lostRace));
        when(emailOutboxRepository.claimForPublish(eq(winner.getId()), any(LocalDateTime.class)))
                .thenReturn(1);
        when(emailOutboxRepository.claimForPublish(eq(lostRace.getId()), any(LocalDateTime.class)))
                .thenReturn(0);

        publisher.publishPending();

        verify(emailQueue).enqueue(new EmailQueue.EmailJobRef(winner.getId(), winner.getDedupeKey()));
        verify(emailQueue, never()).enqueue(new EmailQueue.EmailJobRef(lostRace.getId(), lostRace.getDedupeKey()));
    }

}
