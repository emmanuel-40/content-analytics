package com.contentanalytics.service;

import com.contentanalytics.dto.WebSocketMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayName("WebSocketService")
class WebSocketServiceTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private WebSocketService webSocketService() {
        return new WebSocketService(messagingTemplate);
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("completion payload uses UUID/String types and the public message type constant")
    void completionPayloadTypes() {
        UUID userId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();

        webSocketService().sendDocumentCompleted(
                userId, documentId, "notes.txt", "summary", "topic", "NEUTRAL", 0.75f, 12, 42L);

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSendToUser(
                eq(userId.toString()), eq("/queue/documents"), payload.capture());

        assertThat(payload.getValue()).isInstanceOf(WebSocketMessage.class);
        WebSocketMessage message = (WebSocketMessage) payload.getValue();
        assertThat(message.getMessageId()).isInstanceOf(UUID.class);
        assertThat(message.getUserId()).isEqualTo(userId.toString());
        assertThat(message.getDocumentId()).isEqualTo(documentId);
        assertThat(message.getMessageType()).isEqualTo(WebSocketMessage.PROCESSING_COMPLETED);
    }

    @Test
    @DisplayName("failure payload uses UUID/String types and the public message type constant")
    void failurePayloadTypes() {
        UUID userId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();

        webSocketService().sendDocumentFailed(userId, documentId, "notes.txt", "boom", "PROCESSING_ERROR");

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(messagingTemplate).convertAndSendToUser(
                eq(userId.toString()), eq("/queue/documents"), payload.capture());

        WebSocketMessage message = (WebSocketMessage) payload.getValue();
        assertThat(message.getMessageId()).isInstanceOf(UUID.class);
        assertThat(message.getUserId()).isEqualTo(userId.toString());
        assertThat(message.getMessageType()).isEqualTo(WebSocketMessage.PROCESSING_FAILED);
    }

    @Test
    @DisplayName("terminal notifications are withheld until the status transaction commits")
    void terminalNotificationWaitsForCommit() {
        UUID userId = UUID.randomUUID();
        TransactionSynchronizationManager.initSynchronization();

        webSocketService().sendDocumentFailed(userId, UUID.randomUUID(), "notes.txt", "boom", "ERROR");

        verifyNoInteractions(messagingTemplate);
        for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
            synchronization.afterCommit();
        }
        verify(messagingTemplate).convertAndSendToUser(
                eq(userId.toString()), eq("/queue/documents"), org.mockito.ArgumentMatchers.any());
    }

}
