package com.contentanalytics.config;

import com.contentanalytics.interceptor.WebSocketInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;


@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final WebSocketInterceptor webSocketInterceptor;

    /**
     * Browser origins allowed to open a socket. Read from
     * {@code app.websocket.allowed-origins} (not hardcoded) so the Vite dev
     * server and the deployed SPA are governed by the same properties files
     * as CORS - change it in one place.
     */
    @Value("${app.websocket.allowed-origins:http://localhost:5173}")
    private String allowedOrigins;

    /**
     * Register STOMP endpoints
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        String[] origins = allowedOrigins.split(",");
        registry.addEndpoint("/ws/documents")
                .setAllowedOrigins(origins)
                .withSockJS();

        registry.addEndpoint("/ws/notifications")
                .setAllowedOrigins(origins)
                .withSockJS();

        registry.addEndpoint("/ws/analytics")
                .setAllowedOrigins(origins)
                .withSockJS();
    }

    /**
     * Configure message broker
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic", "/queue");
        config.setApplicationDestinationPrefixes("/app");
        config.setUserDestinationPrefix("/user");
    }

    /**
     * Register WebSocket interceptor for authentication
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(webSocketInterceptor);
     }
    }