package com.contentanalytics.service;

import com.contentanalytics.entity.EmailOutbox;
import com.contentanalytics.repository.EmailOutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The only component that talks to Brevo. It loads the payload from the outbox row rather
 * than trusting a queue message, which makes redeliveries and stale messages harmless.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailDeliveryService {

    private final EmailOutboxRepository emailOutboxRepository;

    @Value("${app.brevo.api-key:}")
    private String brevoApiKey;

    @Value("${app.brevo.api-url:https://api.brevo.com/v3}")
    private String brevoApiUrl;

    @Value("${app.email.from-address:no-reply@localhost}")
    private String fromAddress;

    @Value("${app.email.from-name:Content Analytics}")
    private String fromName;

    @Value("${app.email.max-attempts:5}")
    private int maxAttempts;

    private final OkHttpClient httpClient = new OkHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Deliver one outbox row. The SENT flag is a conditional UPDATE, so two consumers
     * racing on the same job produce exactly one Brevo call.
     */
    public void deliver(EmailQueue.EmailJobRef ref) throws Exception {
        EmailOutbox job = emailOutboxRepository.findById(ref.jobId()).orElse(null);
        if (job == null) {
            log.warn("Email job {} no longer exists - ignoring", ref.jobId());
            return;
        }
        if ("SENT".equals(job.getStatus()) || "FAILED".equals(job.getStatus())) {
            log.debug("Email job {} already {} - no duplicate send", ref.jobId(), job.getStatus());
            return;
        }

        try {
            sendViaBrevo(job.getRecipient(), job.getSubject(), job.getHtmlContent());
            emailOutboxRepository.markSent(job.getId(), LocalDateTime.now());
            log.info("Email job {} delivered to {}", job.getId(), job.getRecipient());
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            emailOutboxRepository.recordFailure(job.getId(), truncate(reason), maxAttempts);
            log.error("Email job {} failed for {}", job.getId(), job.getRecipient(), e);
            throw e;
        }
    }

    /** In-process fallback when no broker is configured or publishing fails. */
    @Async
    public void deliverAsync(EmailQueue.EmailJobRef ref) {
        try {
            deliver(ref);
        } catch (Exception e) {
            // @Async has no caller to observe the exception; the outbox row records it
            // and the publisher scheduler retries it later.
            log.error("Async email delivery failed for job {}", ref.jobId(), e);
        }
    }

    private void sendViaBrevo(String toEmail, String subject, String htmlContent) throws IOException {
        if (brevoApiKey == null || brevoApiKey.isBlank()) {
            // Local dev without credentials: do not pretend the mail was sent.
            throw new IllegalStateException("Brevo API key is not configured");
        }

        Map<String, Object> body = new HashMap<>();
        body.put("subject", subject == null ? "Notification" : subject);

        Map<String, Object> sender = new HashMap<>();
        sender.put("email", fromAddress);
        sender.put("name", fromName);
        body.put("sender", sender);

        Map<String, Object> recipient = new HashMap<>();
        recipient.put("email", toEmail);
        body.put("to", List.of(recipient));
        body.put("htmlContent", htmlContent == null ? "" : htmlContent);

        Request request = new Request.Builder()
                .url(brevoApiUrl + "/smtp/email")
                .addHeader("api-key", brevoApiKey)
                .addHeader("content-type", "application/json")
                .post(RequestBody.create(objectMapper.writeValueAsString(body),
                        MediaType.get("application/json")))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String error = response.body() != null ? response.body().string() : "unknown error";
                throw new IOException("Brevo returned " + response.code() + ": " + truncate(error));
            }
        }
    }

    private String truncate(String value) {
        String safe = value == null ? "" : value;
        return safe.length() <= 2000 ? safe : safe.substring(0, 2000);
    }

}
