package com.yeosal.api.realtime;

import com.yeosal.api.auth.JwtService;
import com.yeosal.api.auth.UserPrincipal;
import com.yeosal.api.room.RoomMemberRepository;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * STOMP inbound-channel auth gate.
 *
 * <ul>
 *   <li>{@code CONNECT}: requires {@code Authorization: Bearer <jwt>}.
 *       Sets the resolved {@link UserPrincipal} as the channel principal so
 *       {@code SimpMessagingTemplate.convertAndSendToUser} can route per-user
 *       events back to this connection.</li>
     *   <li>{@code SUBSCRIBE}: requires an authenticated principal AND, for any
     *       {@code /topic/rooms.{id}.(chat|members|survival)} destination,
     *       room membership. Unknown destination patterns are denied
     *       (deny-by-default).</li>
 * </ul>
 *
 * <p>Returning {@code null} from {@link #preSend} short-circuits the
 * Spring messaging pipeline so the broker never sees the unauthorized
 * frame.
 */
@Component
public class JwtChannelInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(JwtChannelInterceptor.class);

    private static final Pattern ROOM_TOPIC = Pattern.compile(
            "^/topic/rooms\\.(\\d+)\\.(chat|members|survival)$");
    private static final String USER_QUEUE_PREFIX = "/user/";

    private final JwtService jwtService;
    private final RoomMemberRepository roomMembers;

    public JwtChannelInterceptor(JwtService jwtService, RoomMemberRepository roomMembers) {
        this.jwtService = jwtService;
        this.roomMembers = roomMembers;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        StompCommand command = accessor.getCommand();
        if (command == null) {
            return message;
        }
        return switch (command) {
            case CONNECT -> authenticateConnect(message, accessor);
            case SUBSCRIBE -> authoriseSubscribe(message, accessor);
            default -> message;
        };
    }

    private Message<?> authenticateConnect(Message<?> message, StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return null;
        }
        String token = header.substring(7);
        try {
            UserPrincipal principal = jwtService.parse(token);
            UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                    principal, null, principal.getAuthorities());
            accessor.setUser(auth);
            // Re-build the message so downstream interceptors see the
            // mutated headers — required because the original accessor's
            // headers may already be sealed once the message exists.
            return MessageBuilder
                    .createMessage(message.getPayload(), accessor.getMessageHeaders());
        } catch (RuntimeException ex) {
            log.debug("[realtime] CONNECT rejected — invalid token: {}", ex.toString());
            return null;
        }
    }

    private Message<?> authoriseSubscribe(Message<?> message, StompHeaderAccessor accessor) {
        Object userObj = accessor.getUser();
        if (!(userObj instanceof Authentication auth)
                || !(auth.getPrincipal() instanceof UserPrincipal principal)) {
            return null;
        }
        String destination = accessor.getDestination();
        if (destination == null) {
            return null;
        }
        if (destination.startsWith(USER_QUEUE_PREFIX)) {
            // Authenticated principal already gates user-scoped destinations;
            // Spring routes /user/* to the per-session principal.
            return message;
        }
        Matcher m = ROOM_TOPIC.matcher(destination);
        if (!m.matches()) {
            return null;
        }
        long roomId = Long.parseLong(m.group(1));
        if (!roomMembers.existsByRoomIdAndUserId(roomId, principal.id())) {
            return null;
        }
        return message;
    }
}
