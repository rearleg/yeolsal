package com.yeosal.api.realtime;

import com.yeosal.api.room.chat.ChatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Thin typed facade over {@link SimpMessagingTemplate}. Centralises the
 * destination naming convention and absorbs broker errors so a publish
 * hiccup can never roll back the actor's primary write transaction.
 *
 * <p>Destination scheme:
 * <ul>
 *   <li>{@code /topic/rooms.{roomId}.chat}    — every persisted chat row</li>
 *   <li>{@code /topic/rooms.{roomId}.members} — membership change events</li>
 *   <li>{@code /user/{userId}/queue/notifications} — per-user fan-in events
 *       (friend requests, accepts, future user-scoped events)</li>
 * </ul>
 *
 * <p>The membership check on each subscription lives in
 * {@link JwtChannelInterceptor}; this class does not authorise — it
 * only sends.
 */
@Component
public class RealtimePublisher {

    private static final Logger log = LoggerFactory.getLogger(RealtimePublisher.class);

    private final SimpMessagingTemplate template;

    public RealtimePublisher(SimpMessagingTemplate template) {
        this.template = template;
    }

    public void publishChatMessage(long roomId, ChatService.MessageDto message) {
        sendTopic("/topic/rooms." + roomId + ".chat", message);
    }

    public void publishMemberAdded(long roomId, Object payload) {
        sendTopic("/topic/rooms." + roomId + ".members", payload);
    }

    public void publishUserEvent(long userId, RealtimeEvent event) {
        try {
            template.convertAndSendToUser(String.valueOf(userId), "/queue/notifications", event);
        } catch (RuntimeException ex) {
            log.warn("[realtime] user event publish failed userId={} kind={}: {}",
                    userId, event == null ? null : event.kind(), ex.toString());
        }
    }

    private void sendTopic(String destination, Object payload) {
        try {
            template.convertAndSend(destination, payload);
        } catch (RuntimeException ex) {
            log.warn("[realtime] topic publish failed destination={}: {}",
                    destination, ex.toString());
        }
    }
}
