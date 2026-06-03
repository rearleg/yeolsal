package com.yeosal.api.realtime;

import com.yeosal.api.revival.PointPoolChangePayload;
import com.yeosal.api.room.LeadershipChangePayload;
import com.yeosal.api.room.chat.ChatService;
import com.yeosal.api.room.chat.KudosSentPayload;
import com.yeosal.api.survival.SurvivalStateChangePayload;
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
 *   <li>{@code /topic/rooms.{roomId}.survival} — survival-state transitions
 *       (Story 1.2 — same dot-separator convention as chat/members)</li>
 *   <li>{@code /topic/rooms.{roomId}.points} — group point-pool mutations
 *       (Story 3.1 self-revival; Story 4.1 will add the FE subscriber)</li>
 *   <li>{@code /topic/rooms.{roomId}.kudos} — kudos message events
 *       (Story 3.5)</li>
 *   <li>{@code /user/{userId}/queue/notifications} — per-user fan-in events
 *       (friend requests, accepts, future user-scoped events)</li>
 *   <li>{@code /user/{userId}/queue/private-survival} — immediate private
 *       survival frame to the affected user and room owner (Story 1.2
 *       AC5/AC7)</li>
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
        sendUser(userId, "/queue/notifications", event,
                event == null ? null : event.kind());
    }

    /**
     * Survival-state transition publish point (Story 1.2 AC5/AC7).
     * Always emits to the affected user's private queue; emits the broad
     * topic frame only when {@code privateOnly == false}.
     *
     * <p>Broker errors are swallowed (warn-and-continue) — a publish hiccup
     * must never roll back the actor's primary write transaction. For the
     * dispatcher's retry-on-broker-failure path, use
     * {@link #publishSurvivalStateBroadcast(long, SurvivalStateChangePayload)}
     * which returns a boolean status instead.
     */
    public void publishSurvivalStateChange(
            long roomId,
            long affectedUserId,
            SurvivalStateChangePayload payload,
            boolean privateOnly) {
        sendUser(affectedUserId, "/queue/private-survival", payload, "SURVIVAL_STATE_CHANGE");
        if (!privateOnly) {
            sendTopic("/topic/rooms." + roomId + ".survival", payload);
        }
    }

    /**
     * Story 3.1 — group point-pool mutation publish point. Emits to the
     * room topic so any client subscribed to {@code
     * /topic/rooms.{roomId}.points} (Story 4.1 wires the FE consumer) sees
     * the new total. Failures are warn-and-swallowed — a publish hiccup
     * must never roll back the surrounding revival transaction.
     */
    public void publishPointPoolChange(long roomId, PointPoolChangePayload payload) {
        sendTopic("/topic/rooms." + roomId + ".points", payload);
    }

    /**
     * Story 3.5 — kudos sent publish point. Emits to the room topic so
     * any authenticated member subscribed to {@code
     * /topic/rooms.{roomId}.kudos} sees the kudos frame in real time.
     * The {@link com.yeosal.api.room.chat.KudosRealtimeListener} fires this
     * post-commit so a rolled-back kudos write never lights up the
     * realtime fan-out. Failures are warn-and-swallowed via
     * {@link #sendTopic} — a broker hiccup must NEVER roll back the
     * surrounding kudos transaction.
     */
    public void publishKudos(long roomId, KudosSentPayload payload) {
        sendTopic("/topic/rooms." + roomId + ".kudos", payload);
    }

    /**
     * Story 5.2 — leader transfer publish point. Emits the {@code
     * LeadershipChange} frame on the room's survival topic so any
     * authenticated room member sees the new leader without waiting for a
     * manual refresh. Failures are warn-and-swallowed via {@link #sendTopic}
     * — a broker hiccup must NEVER roll back the surrounding transfer-
     * leadership transaction. The dual-channel survival shape (private +
     * broadcast) is intentionally NOT used here: a leader transfer carries
     * no privacy implication.
     */
    public void publishLeadershipChange(long roomId, LeadershipChangePayload payload) {
        sendTopic("/topic/rooms." + roomId + ".survival", payload);
    }

    /**
     * Broadcast-only emit used by the {@code PendingRealtimeBroadcastDispatcher}
     * to drain matured RED rows from {@code pending_realtime_broadcasts}.
     * Returns {@code true} on broker success; the caller marks the row
     * {@code emitted_at} only on a {@code true} return so a broker hiccup
     * stays eligible for the next dispatcher tick (Story 1.2 BE-6.3).
     */
    public boolean publishSurvivalStateBroadcast(
            long roomId, SurvivalStateChangePayload payload) {
        try {
            template.convertAndSend("/topic/rooms." + roomId + ".survival", payload);
            return true;
        } catch (RuntimeException ex) {
            log.warn("[realtime] survival broadcast failed roomId={}: {}",
                    roomId, ex.toString());
            return false;
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

    private void sendUser(long userId, String destination, Object payload, String kindForLog) {
        try {
            template.convertAndSendToUser(String.valueOf(userId), destination, payload);
        } catch (RuntimeException ex) {
            log.warn("[realtime] user publish failed userId={} destination={} kind={}: {}",
                    userId, destination, kindForLog, ex.toString());
        }
    }
}
