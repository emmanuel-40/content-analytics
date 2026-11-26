package com.contentanalytics.service;

import com.contentanalytics.entity.EmailOutbox;
import com.contentanalytics.repository.EmailOutboxRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Transactional email outbox")
class EmailOutboxTest {

    @Autowired private EmailService emailService;
    @Autowired private EmailOutboxRepository emailOutboxRepository;

    @Test
    @DisplayName("duplicate verification links collapse into one queued email")
    void verificationLinkIsQueuedOnce() {
        emailOutboxRepository.deleteAll();

        emailService.sendEmailVerificationLink("student@example.com", "Student",
                "http://localhost:3000/verify-email?token=abc123");
        emailService.sendEmailVerificationLink("student@example.com", "Student",
                "http://localhost:3000/verify-email?token=abc123");

        List<EmailOutbox> rows = emailOutboxRepository.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getType()).isEqualTo("EMAIL_VERIFICATION");
        assertThat(rows.get(0).getStatus()).isEqualTo("PENDING");
        assertThat(rows.get(0).getRecipient()).isEqualTo("student@example.com");
        assertThat(rows.get(0).getHtmlContent()).contains("abc123");
    }

    @Test
    @DisplayName("guarded status transitions make publish and send single-winner operations")
    void statusTransitionsAreGuarded() {
        emailOutboxRepository.deleteAll();
        emailService.sendPasswordResetLink("student@example.com", "Student",
                "http://localhost:3000/reset-password?token=unique-token");
        EmailOutbox row = emailOutboxRepository.findAll().get(0);

        assertThat(emailOutboxRepository.claimForPublish(row.getId(), LocalDateTime.now())).isEqualTo(1);
        assertThat(emailOutboxRepository.claimForPublish(row.getId(), LocalDateTime.now())).isZero();
        assertThat(emailOutboxRepository.markSent(row.getId(), LocalDateTime.now())).isEqualTo(1);
        assertThat(emailOutboxRepository.markSent(row.getId(), LocalDateTime.now())).isZero();
        assertThat(emailOutboxRepository.findById(row.getId()).orElseThrow().getStatus()).isEqualTo("SENT");
    }

    @Test
    @DisplayName("a failed delivery returns to PENDING and is capped at the retry budget")
    void failureIsRetryableThenCapped() {
        emailOutboxRepository.deleteAll();
        emailService.sendWelcomeEmail("retry@example.com", "Retry");
        EmailOutbox row = emailOutboxRepository.findAll().get(0);
        emailOutboxRepository.claimForPublish(row.getId(), LocalDateTime.now());

        assertThat(emailOutboxRepository.recordFailure(row.getId(), "brevo down", 2)).isEqualTo(1);
        assertThat(emailOutboxRepository.findById(row.getId()).orElseThrow().getStatus()).isEqualTo("PENDING");

        emailOutboxRepository.claimForPublish(row.getId(), LocalDateTime.now());
        assertThat(emailOutboxRepository.recordFailure(row.getId(), "brevo down", 2)).isEqualTo(1);
        assertThat(emailOutboxRepository.findById(row.getId()).orElseThrow().getStatus()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("different tokens and accounts remain distinct emails")
    void distinctEventsRemainDistinct() {
        emailOutboxRepository.deleteAll();

        emailService.sendEmailVerificationLink("student@example.com", "Student",
                "http://localhost:3000/verify-email?token=one");
        emailService.sendEmailVerificationLink("student@example.com", "Student",
                "http://localhost:3000/verify-email?token=two");
        emailService.sendPasswordResetLink("student@example.com", "Student",
                "http://localhost:3000/reset-password?token=three");
        emailService.sendWelcomeEmail("student@example.com", "Student");
        emailService.sendWelcomeEmail("student@example.com", "Student");

        assertThat(emailOutboxRepository.findAll()).hasSize(4);
    }

}
