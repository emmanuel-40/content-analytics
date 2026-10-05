package com.contentanalytics.interceptor;

import com.contentanalytics.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class WebSocketInterceptor implements ChannelInterceptor {

    private final JwtTokenProvider jwtTokenProvider;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);

        // Only authenticate on CONNECT
        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            authenticateConnection(accessor);
        }

        return message;
    }

    /**
     * Authenticate WebSocket connection using JWT
     */
    private void authenticateConnection(StompHeaderAccessor accessor) {
        String authHeader = accessor.getFirstNativeHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("WebSocket connection attempt without valid authorization header");
            accessor.setUser(null);
            return;
        }

        String token = authHeader.substring(7);

        try {
            // Validate token
            if (!jwtTokenProvider.validateToken(token)) {
                log.warn("WebSocket connection with invalid token");
                accessor.setUser(null);
                return;
            }

            // Ensure it's an access token, not refresh token
            if (!jwtTokenProvider.isAccessToken(token)) {
                log.warn("WebSocket connection with refresh token instead of access token");
                accessor.setUser(null);
                return;
            }

            // Extract user info
            String username = jwtTokenProvider.getUsernameFromToken(token);
            UUID userId = jwtTokenProvider.getUserIdFromToken(token);

            // Store in session attributes
            accessor.getSessionAttributes().put("userId", userId);
            accessor.getSessionAttributes().put("username", username);
            accessor.getSessionAttributes().put("token", token);

            log.info("WebSocket authenticated for user: {}", username);

        } catch (Exception e) {
            log.error("Error authenticating WebSocket connection", e);
            accessor.setUser(null);
        }
    }

}