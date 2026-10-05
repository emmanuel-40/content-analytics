package com.contentanalytics.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import okhttp3.*;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    @Value("${app.brevo.api-key}")
    private String brevoApiKey;

    @Value("${app.brevo.api-url:https://api.brevo.com/v3}")
    private String brevoApiUrl;

    @Value("${app.email.from-address}")
    private String fromAddress;

    @Value("${app.email.from-name:Content Analytics}")
    private String fromName;

    private final OkHttpClient httpClient = new OkHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public void sendEmailVerificationLink(String userEmail, String userName, String verificationLink) {
        log.info("Sending email verification to: {}", userEmail);

        String subject = "Verify Your Email Address";
        String htmlContent = buildEmailVerificationTemplate(userName, verificationLink);

        sendEmail(userEmail, subject, htmlContent);
    }

    public void sendPasswordResetLink(String userEmail, String userName, String resetLink) {
        log.info("Sending password reset link to: {}", userEmail);

        String subject = "Reset Your Password";
        String htmlContent = buildPasswordResetTemplate(userName, resetLink);

        sendEmail(userEmail, subject, htmlContent);
    }

    public void sendWelcomeEmail(String userEmail, String userName) {
        log.info("Sending welcome email to: {}", userEmail);

        String subject = "Welcome to Content Analytics Platform";
        String htmlContent = buildWelcomeTemplate(userName);

        sendEmail(userEmail, subject, htmlContent);
    }

    public void sendNotificationEmail(String userEmail, String subject, String htmlContent) {
        log.info("Sending notification email to: {}", userEmail);
        sendEmail(userEmail, subject, htmlContent);
    }

    private void sendEmail(String toEmail, String subject, String htmlContent) {
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("subject", subject);

            Map<String, Object> sender = new HashMap<>();
            sender.put("email", fromAddress);
            sender.put("name", fromName);
            requestBody.put("sender", sender);

            Map<String, Object> to = new HashMap<>();
            to.put("email", toEmail);

            requestBody.put("to", List.of(to));

            requestBody.put("htmlContent", htmlContent);

            String jsonBody = objectMapper.writeValueAsString(requestBody);

            RequestBody body = RequestBody.create(
                    jsonBody,
                    MediaType.get("application/json")
            );

            Request request = new Request.Builder()
                    .url(brevoApiUrl + "/smtp/email")
                    .addHeader("api-key", brevoApiKey)
                    .addHeader("content-type", "application/json")
                    .post(body)
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {

                if (!response.isSuccessful()) {
                    String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                    log.error("Brevo API error: {} - {}", response.code(), errorBody);
                    throw new RuntimeException("Failed to send email: " + response.code());
                }

                log.info("Email sent successfully to: {}", toEmail);
            }

        } catch (IOException e) {
            log.error("Error sending email to: {}", toEmail, e);
            throw new RuntimeException("Failed to send email", e);
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