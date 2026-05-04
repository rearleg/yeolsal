package com.yeosal.api.realtime;

import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * STOMP-over-native-WebSocket configuration. SockJS fallback is intentionally
 * not enabled — the React Native client uses the global {@code WebSocket}
 * primitive and never falls back to XHR-streaming, so SockJS only adds bundle
 * weight to the FE without buying anything.
 *
 * <ul>
 *   <li>Endpoint: {@code /ws} (HTTP upgrade)</li>
 *   <li>App-to-server prefix: {@code /app} (unused today; reserved for future
 *       client-published frames)</li>
 *   <li>Server-to-client topics: {@code /topic/*}, {@code /queue/*}</li>
 *   <li>User destinations: {@code /user/*} (resolved per CONNECTed principal
 *       — see {@link JwtChannelInterceptor})</li>
 * </ul>
 *
 * <p>Allowed origins are sourced from the same {@code yeosal.cors.allowed-origins}
 * property the HTTP CORS bean uses, so the WS handshake aligns with the REST
 * surface without a duplicate config.
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtChannelInterceptor jwtChannelInterceptor;
    private final String allowedOrigins;

    public WebSocketConfig(
            JwtChannelInterceptor jwtChannelInterceptor,
            @Value("${yeosal.cors.allowed-origins:*}") String allowedOrigins) {
        this.jwtChannelInterceptor = jwtChannelInterceptor;
        this.allowedOrigins = allowedOrigins;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws").setAllowedOriginPatterns(parseOrigins());
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic", "/queue");
        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(jwtChannelInterceptor);
    }

    private String[] parseOrigins() {
        return Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }
}
