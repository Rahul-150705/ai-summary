package com.ai.teachingassistant.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * WebSocketConfig — configures the STOMP WebSocket message broker.
 *
 * WHY STOMP (not raw WebSocket)?
 * - STOMP gives us topic routing (/topic/...), pub-sub semantics, and
 * SimpMessagingTemplate for server-to-client push — all without manual
 * frame parsing. Raw WebSocket would require us to track connections
 * per lectureId manually and is much harder to scale.
 *
 * WHY SockJS?
 * - SockJS automatically falls back to HTTP long-polling if a network/proxy
 * blocks WebSocket upgrades. The React client uses the same SockJS lib.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /**
     * Register the /ws/lectures endpoint.
     * React SockJS client connects to: http://localhost:8080/ws/lectures
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws/lectures")
                .setAllowedOriginPatterns("*") // tighten in production
                .withSockJS();
    }

    /**
     * Configure the in-memory STOMP message broker.
     *
     * /topic → server-to-client broadcasts (what we use for streaming chunks)
     * /app → client-to-server messages (prefix for @MessageMapping endpoints)
     *
     * SERVER pushes to: /topic/lectures/{lectureId}
     * CLIENT subscribes to: /topic/lectures/{lectureId}
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");
        registry.setApplicationDestinationPrefixes("/app");
    }
}
