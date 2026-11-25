package com.contentanalytics.interceptor;

import com.contentanalytics.repository.DocumentRepository;
import com.contentanalytics.security.JwtTokenProvider;
import com.contentanalytics.security.TokenSessionValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Authenticates STOMP CONNECT frames from the same JWT used by the REST API and rejects
 * subscriptions to documents/queues the authenticated user does not own.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WebSocketInterceptor implements ChannelInterceptor {

    public static final String USER_ID_SESSION_KEY = "userId";
    public static final String USERNAME_SESSION_KEY = "username";

    private static final String TRACK_DESTINATION_PREFIX = "/topic/documents/track/";
    private static final String USER_DESTINATION_PREFIX = "/user/";

    private final JwtTokenProvider jwtTokenProvider;
    private final TokenSessionValidator tokenSessionValidator;
    private final DocumentRepository documentRepository;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            authenticateConnection(accessor);
            // Return a new message so the Principal/session attributes reach the broker and
            // @MessageMapping methods; mutating a wrapped accessor alone is not enough.
            return MessageBuilder.createMessage(message.getPayload(), accessor.getMessageHeaders());
        }

        if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            authorizeSubscription(accessor);
        }

        return message;
    }

    /**
     * Validate the bearer token and install an authenticated Principal on the session.
     * Any failure aborts CONNECT (Spring converts the exception to a STOMP ERROR frame).
     */
    private void authenticateConnection(StompHeaderAccessor accessor) {
        String authHeader = accessor.getFirstNativeHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("WebSocket connection attempt without a bearer token");
            throw new AccessDeniedException("WebSocket connection requires a bearer token");
        }

        String token = authHeader.substring(7).trim();
        if (token.isEmpty() || !tokenSessionValidator.isValidSession(token)) {
            // Also checks token_version and user status: a logged-out user must not
            // keep streaming progress over an old, still-signed access token.
            log.warn("WebSocket connection attempt with an invalid, stale or refresh token");
            throw new AccessDeniedException("Invalid or expired access token");
        }

        UUID userId;
        String username;
        try {
            userId = jwtTokenProvider.getUserIdFromToken(token);
            username = jwtTokenProvider.getUsernameFromToken(token);
        } catch (Exception e) {
            throw new AccessDeniedException("Invalid token claims", e);
        }
        if (userId == null || username == null || username.isBlank()) {
            throw new AccessDeniedException("Invalid token claims");
        }

        WebSocketPrincipal principal = new WebSocketPrincipal(userId, username);
        accessor.setUser(principal);

        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
        if (sessionAttributes == null) {
            sessionAttributes = new HashMap<>();
            accessor.setSessionAttributes(sessionAttributes);
        }
        // Always stored as String: user destinations and @MessageMapping handlers use String ids.
        sessionAttributes.put(USER_ID_SESSION_KEY, userId.toString());
        sessionAttributes.put(USERNAME_SESSION_KEY, username);

        log.info("WebSocket authenticated for user: {}", username);
    }

    /**
     * Document-specific destinations are private to their owner. A forged subscription to
     * another user's document queue or another user's /user destination is rejected here.
     */
    private void authorizeSubscription(StompHeaderAccessor accessor) {
        Principal user = accessor.getUser();
        if (!(user instanceof WebSocketPrincipal principal)) {
            throw new AccessDeniedException("WebSocket session is not authenticated");
        }

        String destination = accessor.getDestination();
        if (destination == null) {
            return;
        }

        // Wildcards could match another user's document topic even though exact subscriptions
        // are ownership-checked below. Refuse them entirely; clients know their own ids.
        if (destination.contains("*") || destination.contains("#")) {
            log.warn("User {} attempted a wildcard subscription: {}", principal.getUserId(), destination);
            throw new AccessDeniedException("Wildcard subscriptions are not allowed");
        }

        if (destination.startsWith(TRACK_DESTINATION_PREFIX)) {
            String documentId = destination.substring(TRACK_DESTINATION_PREFIX.length());
            UUID parsedId;
            try {
                parsedId = UUID.fromString(documentId);
            } catch (IllegalArgumentException e) {
                throw new AccessDeniedException("Invalid document destination", e);
            }
            if (documentRepository.findByIdAndUserId(parsedId, principal.getUserId()).isEmpty()) {
                log.warn("User {} attempted to subscribe to document {}", principal.getUserId(), documentId);
                throw new AccessDeniedException("Document does not belong to the authenticated user");
            }
            return;
        }

        if (destination.startsWith(USER_DESTINATION_PREFIX)) {
            String remainder = destination.substring(USER_DESTINATION_PREFIX.length());
            // "/user/queue/..." and "/user/topic/..." are resolved against the current
            // Principal by the broker. Only the explicit "/user/{userId}/..." form needs
            // an ownership check.
            if (remainder.startsWith("queue/") || remainder.startsWith("topic/")) {
                return;
            }
            int slash = remainder.indexOf('/');
            String targetUser = slash < 0 ? remainder : remainder.substring(0, slash);
            if (!targetUser.isBlank()
                    && !targetUser.equals(principal.getUserId().toString())
                    && !targetUser.equals(principal.getName())) {
                log.warn("User {} attempted to subscribe to queue of '{}'", principal.getUserId(), targetUser);
                throw new AccessDeniedException("Cannot subscribe to another user's queue");
            }
        }
    }

    /**
     * Principal that carries the JWT user id alongside the Spring Security Principal name.
     */
    public static final class WebSocketPrincipal implements Principal {

        private final UUID userId;
        private final String username;

        public WebSocketPrincipal(UUID userId, String username) {
            this.userId = userId;
            this.username = username;
        }

        public UUID getUserId() {
            return userId;
        }

        /**
         * Spring resolves {@code /user/{name}/...} destinations against this value.
         * {@link com.contentanalytics.service.WebSocketService} addresses private queues with the
         * user id, so the principal name must be the id too (the username remains available via
         * {@link #getUsername()} for display/logging).
         */
        @Override
        public String getName() {
            return userId.toString();
        }

        public String getUsername() {
            return username;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof WebSocketPrincipal that)) {
                return false;
            }
            return Objects.equals(userId, that.userId) && Objects.equals(username, that.username);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, username);
        }
    }

}
