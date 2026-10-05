package com.contentanalytics.service;

import com.contentanalytics.dto.WebSocketMessage;
import com.contentanalytics.dto.NotificationMessage;
import com.contentanalytics.dto.AnalyticsUpdateMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class WebSocketService {

    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Send document processing progress to specific user
     * /user/{userId}/queue/documents
     */
    public void sendDocumentProgress(UUID userId, UUID documentId, String fileName,
                                     Float progress, String status, String processingStage) {
        log.debug("Sending progress update to user: {} for document: {}", userId, documentId);

        WebSocketMessage message = WebSocketMessage.builder()
                .messageId(UUID.randomUUID().toString())
                .messageType("PROGRESS")
                .timestamp(LocalDateTime.now())
                .documentId(documentId)
                .fileName(fileName)
                .userId(userId)
                .progress(progress)
                .status(status)
                .processingStage(processingStage)
                .build();

        // Send to user's private queue
        messagingTemplate.convertAndSendToUser(
                String.valueOf(userId),
                "/queue/documents",
                message
        );
    }

    /**
     * Send document processing completion to specific user
     */
    public void sendDocumentCompleted(UUID userId, UUID documentId, String fileName,
                                      String summary, String keyTopics, String sentiment,
                                      Float confidenceScore, Integer wordCount, Long processingTimeMs) {
        log.info("Sending completion update to user: {} for document: {}", userId, documentId);

        WebSocketMessage message = WebSocketMessage.builder()
                .messageId(UUID.randomUUID().toString())
                .messageType("COMPLETED")
                .timestamp(LocalDateTime.now())
                .documentId(documentId)
                .fileName(fileName)
                .userId(userId)
                .progress(100f)
                .status("COMPLETED")
                .summary(summary)
                .keyTopics(keyTopics)
                .sentiment(sentiment)
                .confidenceScore(confidenceScore)
                .wordCount(wordCount)
                .processingTimeMs(processingTimeMs)
                .build();

        // Send to user's private queue
        messagingTemplate.convertAndSendToUser(
                String.valueOf(userId),
                "/queue/documents",
                message
        );
    }

    /**
     * Send document processing failure to specific user
     */
    public void sendDocumentFailed(UUID userId, UUID documentId, String fileName,
                                   String errorMessage, String errorCode) {
        log.error("Sending failure update to user: {} for document: {}", userId, documentId);

        WebSocketMessage message = WebSocketMessage.builder()
                .messageId(UUID.randomUUID().toString())
                .messageType("FAILED")
                .timestamp(LocalDateTime.now())
                .documentId(documentId)
                .fileName(fileName)
                .userId(userId)
                .progress(0f)
                .status("FAILED")
                .errorMessage(errorMessage)
                .errorCode(errorCode)
                .build();

        // Send to user's private queue
        messagingTemplate.convertAndSendToUser(
                String.valueOf(userId),
                "/queue/documents",
                message
        );
    }

    /**
     * Broadcast notification to all connected users
     * /topic/notifications
     */
    public void broadcastNotification(String type, String title, String message) {
        log.info("Broadcasting notification to all users: {}", title);

        NotificationMessage notification = NotificationMessage.builder()
                .notificationId(UUID.randomUUID().toString())
                .type(type)
                .title(title)
                .message(message)
                .isRead(false)
                .createdAt(LocalDateTime.now())
                .build();

        // Broadcast to all subscribers
        messagingTemplate.convertAndSend("/topic/notifications", notification);
    }

    /**
     * Send notification to specific user
     * /user/{userId}/queue/notifications
     */
    public void sendNotificationToUser(String userId, String type, String title,
                                       String message, String relatedId) {
        log.debug("Sending notification to user: {}", userId);

        NotificationMessage notification = NotificationMessage.builder()
                .notificationId(UUID.randomUUID().toString())
                .type(type)
                .title(title)
                .message(message)
                .relatedId(relatedId)
                .isRead(false)
                .createdAt(LocalDateTime.now())
                .build();

        // Send to user's private queue
        messagingTemplate.convertAndSendToUser(
                userId,
                "/queue/notifications",
                notification
        );
    }

    /**
     * Send analytics update to specific user
     * /user/{userId}/queue/analytics
     */
    public void sendAnalyticsUpdate(String userId, AnalyticsUpdateMessage analyticsUpdate) {
        log.debug("Sending analytics update to user: {}", userId);

        messagingTemplate.convertAndSendToUser(
                userId,
                "/queue/analytics",
                analyticsUpdate
        );
    }

    /**
     * Broadcast analytics to all users (system-wide stats)
     */
    public void broadcastAnalytics(AnalyticsUpdateMessage analyticsUpdate) {
        log.debug("Broadcasting analytics update to all users");

        messagingTemplate.convertAndSend("/topic/analytics", analyticsUpdate);
    }

}