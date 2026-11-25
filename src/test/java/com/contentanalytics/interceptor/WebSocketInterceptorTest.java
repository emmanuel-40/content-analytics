package com.contentanalytics.interceptor;

import com.contentanalytics.entity.Document;
import com.contentanalytics.repository.DocumentRepository;
import com.contentanalytics.security.JwtTokenProvider;
import com.contentanalytics.security.TokenSessionValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;

import java.security.Principal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("WebSocketInterceptor")
class WebSocketInterceptorTest {

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private TokenSessionValidator tokenSessionValidator;

    @Mock
    private DocumentRepository documentRepository;

    @InjectMocks
    private WebSocketInterceptor interceptor;

    private final MessageChannel channel = mock(MessageChannel.class);

    private Message<byte[]> connectMessage(String token) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        if (token != null) {
            accessor.setNativeHeader("Authorization", "Bearer " + token);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private Message<byte[]> subscribeMessage(String destination, Principal user) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        accessor.setUser(user);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    @DisplayName("valid CONNECT installs an authenticated Principal and String user id")
    void validConnectAuthenticates() {
        UUID userId = UUID.randomUUID();
        when(tokenSessionValidator.isValidSession("token")).thenReturn(true);
        when(jwtTokenProvider.getUserIdFromToken("token")).thenReturn(userId);
        when(jwtTokenProvider.getUsernameFromToken("token")).thenReturn("alice");

        Message<?> result = interceptor.preSend(connectMessage("token"), channel);
        StompHeaderAccessor headers = StompHeaderAccessor.wrap(result);

        assertThat(headers.getUser()).isInstanceOf(WebSocketInterceptor.WebSocketPrincipal.class);
        WebSocketInterceptor.WebSocketPrincipal principal =
                (WebSocketInterceptor.WebSocketPrincipal) headers.getUser();
        assertThat(principal.getUserId()).isEqualTo(userId);
        // Spring resolves /user/queue/documents via getName(), so it must be the id.
        assertThat(principal.getName()).isEqualTo(userId.toString());
        assertThat(principal.getUsername()).isEqualTo("alice");
        assertThat(headers.getSessionAttributes())
                .containsEntry(WebSocketInterceptor.USER_ID_SESSION_KEY, userId.toString())
                .containsEntry(WebSocketInterceptor.USERNAME_SESSION_KEY, "alice");
    }

    @Test
    @DisplayName("CONNECT without a bearer token is rejected")
    void connectWithoutTokenRejected() {
        assertThatThrownBy(() -> interceptor.preSend(connectMessage(null), channel))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("CONNECT with an invalid token is rejected")
    void connectWithInvalidTokenRejected() {
        when(tokenSessionValidator.isValidSession("bad")).thenReturn(false);

        assertThatThrownBy(() -> interceptor.preSend(connectMessage("bad"), channel))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("CONNECT with a refresh token is rejected")
    void connectWithRefreshTokenRejected() {
        when(tokenSessionValidator.isValidSession("refresh")).thenReturn(false);

        assertThatThrownBy(() -> interceptor.preSend(connectMessage("refresh"), channel))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("an owner may subscribe to their document's tracking topic")
    void ownerCanSubscribeToDocument() {
        UUID userId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        when(documentRepository.findByIdAndUserId(documentId, userId))
                .thenReturn(Optional.of(mock(Document.class)));

        Principal principal = new WebSocketInterceptor.WebSocketPrincipal(userId, "alice");
        interceptor.preSend(subscribeMessage("/topic/documents/track/" + documentId, principal), channel);
    }

    @Test
    @DisplayName("subscribing to another user's document is rejected")
    void foreignDocumentSubscriptionRejected() {
        UUID userId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        when(documentRepository.findByIdAndUserId(documentId, userId)).thenReturn(Optional.empty());

        Principal principal = new WebSocketInterceptor.WebSocketPrincipal(userId, "alice");
        assertThatThrownBy(() -> interceptor.preSend(
                subscribeMessage("/topic/documents/track/" + documentId, principal), channel))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("subscribing to another user's queue is rejected")
    void foreignQueueSubscriptionRejected() {
        UUID userId = UUID.randomUUID();
        Principal principal = new WebSocketInterceptor.WebSocketPrincipal(userId, "alice");

        assertThatThrownBy(() -> interceptor.preSend(
                subscribeMessage("/user/" + UUID.randomUUID() + "/queue/documents", principal), channel))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("a user may subscribe to the broker-resolved private queue")
    void ownPrivateQueueSubscriptionAllowed() {
        UUID userId = UUID.randomUUID();
        Principal principal = new WebSocketInterceptor.WebSocketPrincipal(userId, "alice");

        interceptor.preSend(subscribeMessage("/user/queue/documents", principal), channel);
    }

    @Test
    @DisplayName("wildcard subscriptions cannot bypass document ownership")
    void wildcardSubscriptionRejected() {
        UUID userId = UUID.randomUUID();
        Principal principal = new WebSocketInterceptor.WebSocketPrincipal(userId, "alice");

        assertThatThrownBy(() -> interceptor.preSend(
                subscribeMessage("/topic/documents/track/**", principal), channel))
                .isInstanceOf(AccessDeniedException.class);
    }

}
