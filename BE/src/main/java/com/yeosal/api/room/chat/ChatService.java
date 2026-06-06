package com.yeosal.api.room.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.yeosal.api.common.BadRequestException;
import com.yeosal.api.common.ForbiddenException;
import com.yeosal.api.common.NotFoundException;
import com.yeosal.api.common.SpectatorWriteForbiddenException;
import com.yeosal.api.realtime.RealtimePublisher;
import com.yeosal.api.room.GoalMinimumDays;
import com.yeosal.api.room.GroupMemberMinimum;
import com.yeosal.api.room.GroupMemberMinimumRepository;
import com.yeosal.api.room.Room;
import com.yeosal.api.room.RoomMemberRepository;
import com.yeosal.api.room.RoomRepository;
import com.yeosal.api.survival.RulePresetPreview;
import com.yeosal.api.survival.SurvivalState;
import com.yeosal.api.survival.SurvivalStateRepository;
import com.yeosal.api.survival.SurvivalStatus;
import com.yeosal.api.user.User;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Objects;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChatService {

    /** Hard cap on a single fetch page so a misbehaving client cannot bomb the API. */
    static final int MAX_PAGE_SIZE = 50;
    static final int DEFAULT_PAGE_SIZE = 30;
    /** Cap on a single message body — Postgres TEXT can hold more, but we don't need to. */
    static final int MAX_BODY_LENGTH = 2000;

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);

    /** Shared JSON parser; payloads are always small ({} or a few short fields). */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ChatMessageRepository messages;
    private final RoomRepository rooms;
    private final RoomMemberRepository roomMembers;
    private final GroupMemberMinimumRepository minimums;
    private final RealtimePublisher realtime;
    private final SurvivalStateRepository survivalStates;

    public ChatService(
            ChatMessageRepository messages,
            RoomRepository rooms,
            RoomMemberRepository roomMembers,
            GroupMemberMinimumRepository minimums,
            RealtimePublisher realtime,
            SurvivalStateRepository survivalStates
    ) {
        this.messages = messages;
        this.rooms = rooms;
        this.roomMembers = roomMembers;
        this.minimums = minimums;
        this.realtime = realtime;
        this.survivalStates = survivalStates;
    }

    /**
     * Cursor pagination, descending by id. The response is reversed back to
     * ascending so the FE can render bottom-anchored without flipping again.
     * {@code cursor=null} fetches the most recent page.
     */
    @Transactional(readOnly = true)
    public MessagePage list(User viewer, long roomId, Long cursor, int limit) {
        Room room = requireRoom(roomId);
        requireMembership(room, viewer);
        int capped = limit <= 0 ? DEFAULT_PAGE_SIZE : Math.min(limit, MAX_PAGE_SIZE);
        Long anchor = cursor == null ? Long.MAX_VALUE : cursor;
        List<ChatMessage> desc = messages.findByRoomIdAndIdLessThanOrderByIdDesc(
                room.getId(), anchor, PageRequest.of(0, capped));
        Long nextCursor = desc.size() == capped ? desc.get(desc.size() - 1).getId() : null;
        // Reverse to ascending so newest appears at the bottom of the FE list.
        List<ChatMessage> asc = new ArrayList<>(desc);
        Collections.reverse(asc);
        return new MessagePage(asc.stream().map(MessageDto::from).toList(), nextCursor);
    }

    /**
     * User-authored message. {@code SYSTEM*} kinds go through the publish
     * helpers below so the controller can never write a non-USER message.
     */
    @Transactional
    public MessageDto sendUserMessage(User viewer, long roomId, String body) {
        Room room = requireRoom(roomId);
        requireMembership(room, viewer);
        // Story 2.1 AC4 — BE half of the NFR-9.2.5 double enforcement.
        // FE hides the input for SPECTATOR; this guard rejects writes from a
        // buggy or tampered client. System messages bypass via publishSystem.
        requireNotSpectator(room, viewer);
        String trimmed = normalizeBody(body);
        ChatMessage saved = messages.save(
                new ChatMessage(room.getId(), viewer.getId(), ChatMessageKind.USER, trimmed));
        MessageDto dto = MessageDto.from(saved);
        // Realtime fan-out — every connected member subscribed to
        // /topic/rooms.{id}.chat receives the row immediately. The publisher
        // swallows broker errors so a WS hiccup never rolls back the save.
        realtime.publishChatMessage(room.getId(), dto);
        return dto;
    }

    /**
     * PR G hook entry point. Writes a system-speech row with no sender and a
     * structured payload. Membership is *not* re-checked because the caller
     * (e.g. the daily-service goal hook) is already inside an authenticated
     * write transaction; the room must still exist (asserted here) so that a
     * caller bug can't mask as a silent DB FK failure.
     *
     * <p>Runs in {@link Propagation#REQUIRES_NEW}: the actor's primary write
     * (e.g. {@code DailyService.createOrReplace}) must not roll back if a
     * single fan-out room can't accept the system message. This mirrors the
     * isolation pattern PR A applied to {@code NotificationService.sendEvent}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ChatMessage publishSystem(long roomId, ChatMessageKind kind, String body, String payload) {
        if (kind == null) {
            throw new IllegalArgumentException("publishSystem kind is required");
        }
        if (kind == ChatMessageKind.USER) {
            throw new IllegalArgumentException("publishSystem must not write USER kind");
        }
        requireRoom(roomId);
        ChatMessage saved = messages.save(new ChatMessage(
                roomId, null, kind, normalizeBody(body), parsePayload(payload)));
        // System rows (GOAL, REFLECTION, AUTO_LEAVE, …) ride the same
        // realtime channel as user chat so an open chat screen sees them
        // without waiting for the next polling refresh.
        realtime.publishChatMessage(roomId, MessageDto.from(saved));
        return saved;
    }

    /**
     * Story 1.6 — emits a SYSTEM-kind chat row when a new member joins a room.
     *
     * <p>Body is the locked Korean copy "{nickname} 함께합니다 🌿"; payload carries
     * the joined user's id/displayName so the FE doesn't need an extra fetch to
     * render. Reuses {@link #publishSystem} which runs in {@code REQUIRES_NEW}
     * — a chat-write failure therefore does NOT roll back the membership insert
     * inside {@code RoomService.joinByCode}. This matches the canonical
     * "system fan-out is best-effort" pattern (cf. {@code publishGoalSystemMessages},
     * {@code publishAutoLeaveAfterCommit}).
     */
    public ChatMessage publishMemberJoinedSystemMessage(Room room, User newMember) {
        Objects.requireNonNull(room, "room is required");
        Objects.requireNonNull(newMember, "newMember is required");
        String nickname = newMember.getNickname();
        String body = nickname + " 함께합니다 🌿";
        String payload = String.format(
                "{\"userId\":\"%d\",\"displayName\":%s}",
                newMember.getId(),
                JSON.valueToTree(nickname).toString());
        return publishSystem(room.getId(), ChatMessageKind.SYSTEM, body, payload);
    }

    /**
     * Story 5.4 — emits a SYSTEM chat row announcing a leader-staged rule
     * change. Body opens with the locked positive-frame prefix
     * "다음 달부터 새 규칙이 적용됩니다: " and is suffixed with a one-line preview
     * (preset label + weekend-include phrase). Payload carries the structured
     * tuple {ruleVersionId, effectiveFromMonth, preview} so a future consumer
     * can render a sub-pill or deep-link to the rule editor without re-parsing
     * the body string.
     *
     * <p>Runs in {@link Propagation#REQUIRES_NEW} and reuses
     * {@link #publishSystem}'s validation, persistence, and fan-out path so a
     * chat-row failure cannot roll back the caller's rule upsert, and the
     * standard {@code /topic/rooms.{id}.chat} fan-out fires automatically.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ChatMessage publishRuleChangeSystemMessage(
            long roomId,
            long ruleVersionId,
            String effectiveFromMonth,
            String preset,
            boolean weekendInclude) {
        String preview = RulePresetPreview.format(preset, weekendInclude);
        String body = "다음 달부터 새 규칙이 적용됩니다: " + preview;
        // ruleVersionId is rendered as a JSON string so `payload->>` operators
        // line up with the V8/V9 milestone-dedup convention; the other two
        // string fields route through JSON.valueToTree to escape safely.
        String payload = String.format(
                "{\"ruleVersionId\":\"%d\",\"effectiveFromMonth\":%s,\"preview\":%s}",
                ruleVersionId,
                JSON.valueToTree(effectiveFromMonth).toString(),
                JSON.valueToTree(preview).toString());
        return publishSystem(roomId, ChatMessageKind.SYSTEM, body, payload);
    }

    /**
     * Reflection-time MILESTONE fan-out. For each room {@code actor}
     * belongs to, publishes a daily progress chat row of the form
     * "alice님 15일 중 7일 완료!" — the announcement now fires every
     * day the actor files both today's goal AND today's reflection,
     * not only when the monthly threshold is finally reached.
     *
     * <p>Idempotent per (room, actor, date) via the V9 partial unique
     * expression index, so a same-day retry / second reflection
     * collapses to one inserted row.
     *
     * <p>Per-room failures (room deleted mid-tx, JSON encode hiccup) are
     * caught and logged so a noisy room can never block the rest. Runs
     * in {@link Propagation#REQUIRES_NEW} for the same reason
     * {@link #publishSystem} does — a chat row failure must not roll
     * back the actor's reflection write.
     *
     * @return number of rooms that received a freshly-published MILESTONE
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int publishMilestonesForActor(
            User actor, YearMonth month, LocalDate date, int completed) {
        if (actor == null || month == null || date == null) {
            throw new IllegalArgumentException("actor, month, and date are required");
        }
        if (completed <= 0) {
            // No progress to announce.
            return 0;
        }
        List<Room> rooms;
        try {
            rooms = roomMembers.findRoomsByUser(actor);
        } catch (RuntimeException ex) {
            log.warn("[chat] milestone room lookup failed actor={}: {}",
                    actor.getId(), ex.toString());
            return 0;
        }
        String dateKey = date.toString();
        int published = 0;
        for (Room room : rooms) {
            try {
                GroupMemberMinimum min = minimums
                        .findByRoomIdAndUserId(room.getId(), actor.getId())
                        .orElse(null);
                if (min == null) {
                    continue;
                }
                int required = GoalMinimumDays.effectiveRequiredDays(
                        min.getMinDailyGoalDays(), month);
                String body = normalizeBody(milestoneBody(actor, completed, required));
                String payload = milestonePayload(actor, dateKey, completed, required);
                // V9 partial unique index serializes the (room, user, date)
                // tuple, so a same-day duplicate publish collapses to one
                // inserted row.
                published += messages.insertMilestoneIfAbsent(room.getId(), body, payload);
            } catch (RuntimeException ex) {
                log.warn("[chat] milestone publish failed actor={} room={}: {}",
                        actor.getId(), room.getId(), ex.toString());
            }
        }
        return published;
    }

    private static String milestoneBody(User actor, int completed, int required) {
        return actor.getNickname()
                + "님 " + required + "일 중 " + completed + "일 완료!";
    }

    /**
     * Stable JSON shape for the dedup index.
     * {@code userId} is rendered as a string so {@code payload->>'userId'}
     * matches what callers (and the V9 partial unique index) expect.
     * {@code date} is "YYYY-MM-DD" KST per actor's group day.
     */
    private static String milestonePayload(
            User actor, String dateKey, int completed, int required) {
        return String.format(
                "{\"userId\":\"%d\",\"date\":\"%s\",\"completedDays\":%d,\"requiredDays\":%d}",
                actor.getId(), dateKey, completed, required);
    }

    private static String normalizeBody(String body) {
        String trimmed = body == null ? "" : body.trim();
        if (trimmed.isEmpty()) {
            throw new BadRequestException("메시지 내용을 입력하세요.");
        }
        if (trimmed.length() > MAX_BODY_LENGTH) {
            throw new BadRequestException("메시지가 너무 깁니다. (" + MAX_BODY_LENGTH + "자 이하)");
        }
        return trimmed;
    }

    private static JsonNode parsePayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return JsonNodeFactory.instance.objectNode();
        }
        try {
            JsonNode node = JSON.readTree(payload);
            if (!node.isObject()) {
                throw new IllegalArgumentException("system message payload must be a JSON object");
            }
            return node;
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("system message payload must be valid JSON", ex);
        }
    }

    private Room requireRoom(long roomId) {
        return rooms.findById(roomId)
                .orElseThrow(() -> new NotFoundException("방을 찾을 수 없습니다."));
    }

    private void requireMembership(Room room, User user) {
        roomMembers.findByRoomAndUser(room, user)
                .orElseThrow(() -> new ForbiddenException("방 멤버만 접근할 수 있습니다."));
    }

    /**
     * Story 2.1 AC4 — block user-authored writes when the caller's
     * {@code survival_state.status} for this room is {@code SPECTATOR}.
     *
     * <p>A missing row is treated as pass-through (defensive). V11's backfill
     * should have created a row for every existing membership, but if one is
     * absent the chat path MUST NOT 500 — the worst-case impact of a single
     * missing row is a single user briefly able to post, not an outage.
     */
    private void requireNotSpectator(Room room, User user) {
        SurvivalState state = survivalStates
                .findByRoomIdAndUserId(room.getId(), user.getId())
                .orElse(null);
        if (state != null && state.getStatus() == SurvivalStatus.SPECTATOR) {
            throw new SpectatorWriteForbiddenException();
        }
    }

    public record MessageDto(
            long id,
            long roomId,
            Long senderUserId,
            ChatMessageKind kind,
            String body,
            JsonNode payload,
            Instant createdAt
    ) {
        public static MessageDto from(ChatMessage m) {
            return new MessageDto(
                    m.getId(),
                    m.getRoomId(),
                    m.getSenderUserId(),
                    m.getKind(),
                    m.getBody(),
                    m.getPayload(),
                    m.getCreatedAt()
            );
        }
    }

    public record MessagePage(List<MessageDto> messages, Long nextCursor) {}
}
