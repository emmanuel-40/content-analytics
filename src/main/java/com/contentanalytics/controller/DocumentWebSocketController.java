package com.contentanalytics.controller;

import com.contentanalytics.dto.WebSocketMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;

import java.util.UUID;

@Controller
@RequiredArgsConstructor
@Slf4j
public class DocumentWebSocketController {

    /**
     * Handle incoming WebSocket messages from clients
     * Client sends to: /app/documents/track/{documentId}
     * Server broadcasts to: /topic/documents/track/{documentId}
     */
    @MessageMapping("/documents/track/{documentId}")
    @SendTo("/topic/documents/track/{documentId}")
    public WebSocketMessage trackDocumentProgress(
            @Payload WebSocketMessage message,
            SimpMessageHeaderAccessor headerAccessor) {

        log.debug("Received document tracking message: {}", message.getDocumentId());

        // Get user principal from WebSocket session
        UUID userId = (UUID) headerAccessor.getSessionAttributes().get("userId");
        message.setUserId(userId);

        // Add server timestamp
        message.setTimestamp(java.time.LocalDateTime.now());

        // Broadcast to all subscribers of this document
        return message;
    }

    /**
     * Client subscribes to: /user/{userId}/queue/documents
     * This endpoint handles status requests
     */
    @MessageMapping("/documents/status")
    public void requestDocumentStatus(
            @Payload String documentId,
            SimpMessageHeaderAccessor headerAccessor) {

        log.debug("Document status requested: {}", documentId);

        String userId = (String) headerAccessor.getSessionAttributes().get("userId");
        log.debug("Status request from user: {}", userId);

        // Status will be sent via WebSocketService.sendDocumentProgress()
    }

}