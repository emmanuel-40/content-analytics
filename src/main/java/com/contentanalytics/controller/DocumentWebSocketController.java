package com.contentanalytics.controller;

import com.contentanalytics.dto.WebSocketMessage;
import com.contentanalytics.entity.Document;
import com.contentanalytics.interceptor.WebSocketInterceptor;
import com.contentanalytics.repository.DocumentRepository;
import com.contentanalytics.service.WebSocketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;

import java.security.Principal;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
@Slf4j
public class DocumentWebSocketController {

    private final DocumentRepository documentRepository;
    private final WebSocketService webSocketService;

    /**
     * Handle incoming WebSocket messages from clients
     * Client sends to: /app/documents/track/{documentId}
     * Server broadcasts to: /topic/documents/track/{documentId}
     *
     * Both the destination id and the payload are validated against the authenticated
     * principal; the client cannot track somebody else's document by editing the payload.
     */
    @MessageMapping("/documents/track/{documentId}")
    @SendTo("/topic/documents/track/{documentId}")
    public WebSocketMessage trackDocumentProgress(
            @Payload WebSocketMessage message,
            @DestinationVariable("documentId") String documentId,
            SimpMessageHeaderAccessor headerAccessor) {

        UUID userId = authenticatedUserId(headerAccessor);
        UUID documentUuid = parseDocumentId(documentId);
        requireOwnership(documentUuid, userId);

        // Always derive identity from the authenticated session, never from the payload.
        message.setDocumentId(documentUuid);
        message.setUserId(userId.toString());
        message.setTimestamp(LocalDateTime.now());

        log.debug("Document tracking message accepted for document: {}", documentUuid);
        return message;
    }

    /**
     * Client sends to: /app/documents/status with a document id.
     * The current status is pushed only to the owning user's private queue.
     */
    @MessageMapping("/documents/status")
    public void requestDocumentStatus(
            @Payload String documentId,
            SimpMessageHeaderAccessor headerAccessor) {

        UUID userId = authenticatedUserId(headerAccessor);
        UUID documentUuid = parseDocumentId(documentId);
        Document document = requireOwnership(documentUuid, userId);

        log.debug("Status request accepted for document: {} from user: {}", documentUuid, userId);
        webSocketService.sendDocumentProgress(
                userId,
                documentUuid,
                document.getFileName(),
                document.getProcessingProgress() != null ? document.getProcessingProgress() : 0f,
                document.getStatus().name(),
                null
        );
    }

    private UUID authenticatedUserId(SimpMessageHeaderAccessor headerAccessor) {
        Principal principal = headerAccessor.getUser();
        if (principal instanceof WebSocketInterceptor.WebSocketPrincipal authenticated) {
            return authenticated.getUserId();
        }

        // Fallback for sessions established before the Principal was set explicitly.
        Map<String, Object> attributes = headerAccessor.getSessionAttributes();
        Object raw = attributes != null ? attributes.get(WebSocketInterceptor.USER_ID_SESSION_KEY) : null;
        if (raw instanceof UUID uuid) {
            return uuid;
        }
        if (raw instanceof String text) {
            return UUID.fromString(text);
        }
        throw new AccessDeniedException("WebSocket session is not authenticated");
    }

    private Document requireOwnership(UUID documentId, UUID userId) {
        Document document = documentRepository.findByIdAndUserId(documentId, userId)
                .orElseThrow(() -> new AccessDeniedException("Document does not belong to the authenticated user"));
        return document;
    }

    private UUID parseDocumentId(String documentId) {
        try {
            return UUID.fromString(documentId == null ? "" : documentId.trim());
        } catch (IllegalArgumentException e) {
            throw new AccessDeniedException("Invalid document id", e);
        }
    }

}
