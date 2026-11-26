package com.contentanalytics.service;

import com.contentanalytics.entity.EmailOutbox;
import com.contentanalytics.entity.EmailType;
import com.contentanalytics.repository.EmailOutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Entry point for all outbound email.
 *
 * Nothing here performs network I/O. Each request writes one row to the transactional
 * outbox inside the caller's database transaction; the publisher/worker deliver it later.
 * This makes email atomic with the state change that requested it and makes retries safe.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final EmailOutboxRepository emailOutboxRepository;

    public void sendEmailVerificationLink(String userEmail, String userName, String verificationLink) {
        enqueue(EmailType.EMAIL_VERIFICATION, userEmail, userName,
                "Verify Your Email Address",
                buildEmailVerificationTemplate(userName, verificationLink),
                verificationLink);
    }

    public void sendPasswordResetLink(String userEmail, String userName, String resetLink) {
        enqueue(EmailType.PASSWORD_RESET, userEmail, userName,
                "Reset Your Password",
                buildPasswordResetTemplate(userName, resetLink),
                resetLink);
    }

    public void sendWelcomeEmail(String userEmail, String userName) {
        enqueue(EmailType.WELCOME, userEmail, userName,
                "Welcome to Content Analytics Platform",
                buildWelcomeTemplate(userName),
                userEmail);
    }

    public void sendNotificationEmail(String userEmail, String subject, String htmlContent) {
        sendNotificationEmail(userEmail, subject, htmlContent, UUID.randomUUID().toString());
    }

    /**
     * Use a caller-supplied event id (for example userId:tokenVersion) when the same
     * notification must never be emitted twice for one state transition.
     */
    public void sendNotificationEmail(String userEmail, String subject, String htmlContent,
                                       String eventId) {
        enqueue(EmailType.NOTIFICATION, userEmail, null,
                subject,
                htmlContent,
                eventId);
    }

    /**
     * Atomic, duplicate-safe outbox write. The unique dedupe key is derived from the
     * logical artifact (token, account, event), not from a random job id, so two callers
     * asking for the same email collapse into one row.
     */
    private void enqueue(EmailType type, String recipient, String displayName,
                         String subject, String htmlContent, String uniquePart) {
        String dedupeKey = type.name() + ":" + UUID.nameUUIDFromBytes(
                String.valueOf(uniquePart).getBytes(StandardCharsets.UTF_8));

        int inserted = emailOutboxRepository.insertIfAbsent(
                UUID.randomUUID(),
                dedupeKey,
                type.name(),
                recipient,
                displayName,
                subject,
                htmlContent,
                LocalDateTime.now());

        if (inserted == 0) {
            log.debug("Duplicate email suppressed ({}): {}", type, dedupeKey);
        } else {
            log.info("Email queued ({}) for {}", type, recipient);
        }
    }

    // ================= FIXED TEMPLATES =================

    private String buildEmailVerificationTemplate(String userName, String verificationLink) {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <style>
                    body { font-family: Arial, sans-serif; background-color: #f4f4f4; }
                    .container { max-width: 600px; margin: 0 auto; background-color: white; padding: 20px; border-radius: 8px; }
                    .header { color: #333; margin-bottom: 20px; }
                    .content { color: #666; line-height: 1.6; }
                    .button { background-color: #007bff; color: white; padding: 12px 30px; text-decoration: none; border-radius: 4px; display: inline-block; margin: 20px 0; }
                    .footer { color: #999; font-size: 12px; margin-top: 20px; border-top: 1px solid #eee; padding-top: 20px; }
                </style>
            </head>
            <body>
                <div class="container">
                    <h2 class="header">Welcome, %s!</h2>

                    <div class="content">
                        <p>Thank you for registering with Content Analytics Platform. To complete your registration and verify your email address, please click the button below:</p>

                        <a href="%s" class="button">Verify Email Address</a>

                        <p>This link will expire in 24 hours.</p>

                        <p>If you didn't create this account, please ignore this email.</p>

                        <p><strong>Or copy and paste this link in your browser:</strong><br>
                        <small>%s</small></p>
                    </div>

                    <div class="footer">
                        <p>Content Analytics Platform Team</p>
                        <p>This is an automated message, please do not reply.</p>
                    </div>
                </div>
            </body>
            </html>
            """.formatted(userName, verificationLink, verificationLink);
    }

    private String buildPasswordResetTemplate(String userName, String resetLink) {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <style>
                    body { font-family: Arial, sans-serif; background-color: #f4f4f4; }
                    .container { max-width: 600px; margin: 0 auto; background-color: white; padding: 20px; border-radius: 8px; }
                    .header { color: #d9534f; margin-bottom: 20px; }
                    .content { color: #666; line-height: 1.6; }
                    .button { background-color: #d9534f; color: white; padding: 12px 30px; text-decoration: none; border-radius: 4px; display: inline-block; margin: 20px 0; }
                    .footer { color: #999; font-size: 12px; margin-top: 20px; border-top: 1px solid #eee; padding-top: 20px; }
                </style>
            </head>
            <body>
                <div class="container">
                    <h2 class="header">Password Reset Request</h2>

                    <div class="content">
                        <p>Hi %s,</p>

                        <p>We received a request to reset your password. Click the button below to set a new password:</p>

                        <a href="%s" class="button">Reset Password</a>

                        <p><strong>Important:</strong> This link will expire in 1 hour for security reasons.</p>

                        <p>If you didn't request a password reset, please ignore this email.</p>

                        <p><strong>Or copy and paste this link in your browser:</strong><br>
                        <small>%s</small></p>
                    </div>

                    <div class="footer">
                        <p>Content Analytics Platform Team</p>
                        <p>This is an automated message, please do not reply.</p>
                    </div>
                </div>
            </body>
            </html>
            """.formatted(userName, resetLink, resetLink);
    }

    private String buildWelcomeTemplate(String userName) {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <style>
                    body { font-family: Arial, sans-serif; background-color: #f4f4f4; }
                    .container { max-width: 600px; margin: 0 auto; background-color: white; padding: 20px; border-radius: 8px; }
                    .header { color: #28a745; margin-bottom: 20px; }
                    .content { color: #666; line-height: 1.6; }
                    .features { background-color: #f9f9f9; padding: 15px; border-left: 4px solid #28a745; margin: 20px 0; }
                    .features li { margin: 10px 0; }
                    .footer { color: #999; font-size: 12px; margin-top: 20px; border-top: 1px solid #eee; padding-top: 20px; }
                </style>
            </head>
            <body>
                <div class="container">
                    <h2 class="header">Welcome to Content Analytics Platform, %s!</h2>

                    <div class="content">
                        <p>Your email has been verified and your account is now active. You can start using our platform right away.</p>

                        <div class="features">
                            <h3>What you can do now:</h3>
                            <ul>
                                <li>📄 Upload documents</li>
                                <li>🤖 AI analysis</li>
                                <li>📊 Analytics</li>
                            </ul>
                        </div>
                    </div>

                    <div class="footer">
                        <p>Content Analytics Platform Team</p>
                        <p>This is an automated message, please do not reply.</p>
                    </div>
                </div>
            </body>
            </html>
            """.formatted(userName);
    }
}
