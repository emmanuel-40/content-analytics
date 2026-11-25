package com.contentanalytics.controller;

import com.contentanalytics.dto.WebSocketMessage;
import com.contentanalytics.entity.Document;
import com.contentanalytics.entity.DocumentStatus;
import com.contentanalytics.interceptor.WebSocketInterceptor.WebSocketPrincipal;
import com.contentanalytics.repository.DocumentRepository;
import com.contentanalytics.service.WebSocketService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DocumentWebSocketController")
class DocumentWebSocketControllerTest {

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private WebSocketService webSocketService;

    @InjectMocks
    private DocumentWebSocketController controller;

    private SimpMessageHeaderAccessor headers(UUID userId) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create();
        accessor.setUser(new WebSocketPrincipal(userId, "alice"));
        return accessor;
    }

    @Test
    @DisplayName("tracking rejects a document the principal does not own")
    void trackingRejectsForeignDocument() {
        UUID userId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        when(documentRepository.findByIdAndUserId(documentId, userId)).thenReturn(Optional.empty());

        WebSocketMessage message = WebSocketMessage.builder().documentId(documentId).build();
        assertThatThrownBy(() -> controller.trackDocumentProgress(
                message, documentId.toString(), headers(userId)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("tracking overwrites client-supplied user id with the authenticated one")
    void trackingUsesAuthenticatedUserId() {
        UUID userId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        Document document = Document.builder().id(documentId).status(DocumentStatus.PROCESSING).build();
        when(documentRepository.findByIdAndUserId(documentId, userId)).thenReturn(Optional.of(document));

        WebSocketMessage message = WebSocketMessage.builder()
                .documentId(UUID.randomUUID())
                .userId(UUID.randomUUID().toString())
                .build();

        WebSocketMessage result = controller.trackDocumentProgress(
                message, documentId.toString(), headers(userId));

        assertThat(result.getUserId()).isEqualTo(userId.toString());
        assertThat(result.getDocumentId()).isEqualTo(documentId);
        assertThat(result.getTimestamp()).isNotNull();
    }

    @Test
    @DisplayName("status request pushes an update only for an owned document")
    void statusRequestForOwnedDocument() {
        UUID userId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        Document document = Document.builder()
                .id(documentId)
                .fileName("notes.txt")
                .status(DocumentStatus.COMPLETED)
                .processingProgress(100f)
                .build();
        when(documentRepository.findByIdAndUserId(documentId, userId)).thenReturn(Optional.of(document));

        controller.requestDocumentStatus(documentId.toString(), headers(userId));

        verify(webSocketService).sendDocumentProgress(
                userId, documentId, "notes.txt", 100f, "COMPLETED", null);
    }

    @Test
    @DisplayName("status request for a foreign document sends nothing")
    void statusRequestForForeignDocumentRejected() {
        UUID userId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        when(documentRepository.findByIdAndUserId(documentId, userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.requestDocumentStatus(documentId.toString(), headers(userId)))
                .isInstanceOf(AccessDeniedException.class);
        verify(webSocketService, never()).sendDocumentProgress(any(), any(), any(), any(), any(), any());
    }

}
