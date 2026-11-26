package com.contentanalytics.service;

import com.contentanalytics.entity.EmailOutbox;
import com.contentanalytics.repository.EmailOutboxRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Email delivery idempotency")
class EmailDeliveryServiceTest {

    @Mock private EmailOutboxRepository emailOutboxRepository;

    @InjectMocks private EmailDeliveryService deliveryService;

    private EmailOutbox job(String status) {
        return EmailOutbox.builder()
                .id(UUID.randomUUID())
                .dedupeKey("EMAIL_VERIFICATION:abc")
                .recipient("student@example.com")
                .subject("Verify")
                .htmlContent("<p>Verify</p>")
                .status(status)
                .createdAt(LocalDateTime.now())
                .build();
    }

    @Test
    @DisplayName("a SENT outbox row is never delivered twice")
    void alreadySentIsIgnored() throws Exception {
        EmailOutbox sent = job("SENT");
        when(emailOutboxRepository.findById(sent.getId())).thenReturn(Optional.of(sent));

        deliveryService.deliver(new EmailQueue.EmailJobRef(sent.getId(), sent.getDedupeKey()));

        verify(emailOutboxRepository, never()).markSent(any(), any());
        verify(emailOutboxRepository, never()).recordFailure(any(), anyString(), anyInt());
    }

    @Test
    @DisplayName("a provider failure makes the row retryable instead of losing it")
    void failedDeliveryIsRecordedForRetry() {
        EmailOutbox pending = job("PENDING");
        when(emailOutboxRepository.findById(pending.getId())).thenReturn(Optional.of(pending));
        // No Brevo key in this unit test: delivery fails before any network call.
        ReflectionTestUtils.setField(deliveryService, "brevoApiKey", "");
        ReflectionTestUtils.setField(deliveryService, "maxAttempts", 5);

        try {
            deliveryService.deliver(new EmailQueue.EmailJobRef(pending.getId(), pending.getDedupeKey()));
        } catch (Exception expected) {
            // The failure is propagated so Rabbit can dead-letter this delivery.
        }

        verify(emailOutboxRepository).recordFailure(eq(pending.getId()), anyString(), eq(5));
        verify(emailOutboxRepository, never()).markSent(any(), any());
    }

}
