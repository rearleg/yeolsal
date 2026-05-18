package com.yeosal.api.room.chat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yeosal.api.common.BadRequestException;
import com.yeosal.api.common.ForbiddenException;
import com.yeosal.api.common.NotFoundException;
import com.yeosal.api.common.SpectatorWriteForbiddenException;
import com.yeosal.api.friend.Friendship;
import com.yeosal.api.friend.FriendshipRepository;
import com.yeosal.api.friend.FriendshipStatus;
import com.yeosal.api.room.RoomMemberRepository;
import com.yeosal.api.survival.SurvivalState;
import com.yeosal.api.survival.SurvivalStateRepository;
import com.yeosal.api.survival.SurvivalStatus;
import com.yeosal.api.user.User;
import com.yeosal.api.user.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Story 3.5 — kudos send orchestrator. Companion to {@link ChatService} but
 * scoped to the invitation-toned {@code kind='KUDOS'} write path, which
 * has distinct semantics:
 *
 * <ul>
 *   <li>Body cap is 60 chars, not 2000 (kudos is a postcard).</li>
 *   <li>Friendship gate ({@code ACCEPTED} only) — chat does not require this.</li>
 *   <li>Per (sender, target, KST day) dedupe via the V12 partial unique
 *       index {@code ux_kudos_one_per_day}.</li>
 *   <li>Non-null {@code sender_user_id} (the only non-USER kind that has one).</li>
 * </ul>
 *
 * <h2>Two-layer dedupe defence (Architecture §5.1)</h2>
 *
 * <ol>
 *   <li><strong>Primary — partial unique index.</strong>
 *       {@code ux_kudos_one_per_day (sender_user_id, payload->>'targetUserId',
 *       (created_at at time zone 'Asia/Seoul')::date) WHERE kind = 'KUDOS'}.
 *       The {@code ON CONFLICT DO NOTHING} returns a zero row count which
 *       surfaces as {@link KudosAlreadySentTodayException}.</li>
 *   <li><strong>Secondary — service-layer catch.</strong> Any
 *       {@link DataIntegrityViolationException} that escapes (Hibernate
 *       flush past the {@code on conflict} clause) is remapped via
 *       {@link #isKudosDedupConflict} so the wire response stays 409 /
 *       {@code KUDOS_ALREADY_SENT_TODAY} rather than the generic 500 the
 *       {@code ApiExceptionHandler.dataIntegrity} handler would emit.</li>
 * </ol>
 */
@Service
public class KudosService {

    private static final Logger log = LoggerFactory.getLogger(KudosService.class);
    static final int MAX_MESSAGE_LENGTH = 60;
    static final int MESSAGE_PREVIEW_LENGTH = 40;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String DEDUP_CONSTRAINT = "ux_kudos_one_per_day";

    private final ChatMessageRepository messages;
    private final RoomMemberRepository roomMembers;
    private final SurvivalStateRepository survivalStates;
    private final FriendshipRepository friendships;
    private final UserRepository users;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public KudosService(
            ChatMessageRepository messages,
            RoomMemberRepository roomMembers,
            SurvivalStateRepository survivalStates,
            FriendshipRepository friendships,
            UserRepository users,
            ApplicationEventPublisher eventPublisher,
            ObjectMapper objectMapper,
            Clock clock) {
        this.messages = messages;
        this.roomMembers = roomMembers;
        this.survivalStates = survivalStates;
        this.friendships = friendships;
        this.users = users;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Send a kudos message from {@code me} to {@code targetUserId} in
     * {@code roomId}. See class-level javadoc for the dedupe contract.
     *
     * <p>The AC3 sequence is executed in this exact order:
     * member check → self-target check → target-member check →
     * sender-spectator gate → target-eligibility gate → friendship gate →
     * message normalisation → INSERT → DIVE→typed exception translation →
     * id fetch → event publish.
     */
    @Transactional
    public KudosDto sendKudos(long roomId, User me, long targetUserId, String message) {
        long senderUserId = me.getId();

        // (2) Cheap precheck — sender must be a room member.
        if (!roomMembers.existsByRoomIdAndUserId(roomId, senderUserId)) {
            throw new ForbiddenException("방 멤버만 응원을 보낼 수 있어요.");
        }
        // (3) Self-target check.
        if (senderUserId == targetUserId) {
            throw new BadRequestException("자기 자신에게는 응원을 보낼 수 없어요.");
        }
        // (4) Target must also be a room member.
        if (!roomMembers.existsByRoomIdAndUserId(roomId, targetUserId)) {
            throw new NotFoundException("대상 멤버를 찾을 수 없어요.");
        }

        // (5) Sender spectator gate — reuse the canonical 403 wire code.
        SurvivalState senderState = survivalStates
                .findByRoomIdAndUserId(roomId, senderUserId)
                .orElse(null);
        if (senderState != null && senderState.getStatus() == SurvivalStatus.SPECTATOR) {
            throw new SpectatorWriteForbiddenException();
        }

        // (6) Target eligibility gate — kudos goes only to RED/SPECTATOR.
        SurvivalState targetState = survivalStates
                .findByRoomIdAndUserId(roomId, targetUserId)
                .orElseThrow(() -> new KudosTargetNotEligibleException(
                        "응원은 회생을 기다리는 멤버에게만 보낼 수 있어요."));
        SurvivalStatus targetStatus = targetState.getStatus();
        if (targetStatus != SurvivalStatus.RED && targetStatus != SurvivalStatus.SPECTATOR) {
            throw new KudosTargetNotEligibleException(
                    "응원은 회생을 기다리는 멤버에게만 보낼 수 있어요.");
        }

        // (7) Friendship gate — must have an ACCEPTED friendship row in either direction.
        User target = users.findById(targetUserId)
                .orElseThrow(() -> new NotFoundException("대상 멤버를 찾을 수 없어요."));
        Optional<Friendship> friendship = friendships.findBetween(me, target)
                .filter(f -> f.getStatus() == FriendshipStatus.ACCEPTED);
        if (friendship.isEmpty()) {
            throw new NotFriendsException("친구가 된 멤버에게만 응원을 보낼 수 있어요.");
        }

        // (8) Message normalisation — trim, treat empty as null-equivalent.
        String trimmedMessage = normalizeMessage(message);

        // (9) INSERT with idempotency via the V12 partial unique index.
        String body = renderBody(me);
        String payloadJson = buildPayloadJson(senderUserId, targetUserId, trimmedMessage);
        int inserted;
        try {
            inserted = messages.insertKudosIfAbsent(roomId, senderUserId, body, payloadJson);
        } catch (DataIntegrityViolationException ex) {
            // (10) Defence in depth — translate the partial unique index
            // conflict if Hibernate flush escapes the on-conflict path.
            if (isKudosDedupConflict(ex)) {
                throw new KudosAlreadySentTodayException("오늘은 이미 응원을 보냈어요.");
            }
            throw ex;
        }
        if (inserted == 0) {
            throw new KudosAlreadySentTodayException("오늘은 이미 응원을 보냈어요.");
        }

        // Recover the just-inserted row id deterministically.
        Instant occurredAt = clock.instant();
        LocalDate kstToday = LocalDate.ofInstant(occurredAt, KST);
        long kudosId = messages
                .findKudosId(senderUserId, String.valueOf(targetUserId), kstToday)
                .orElseThrow(() -> new IllegalStateException(
                        "kudos row missing after insertKudosIfAbsent returned 1: "
                                + "senderUserId=" + senderUserId
                                + " targetUserId=" + targetUserId));

        // (11) Publish AFTER_COMMIT-driven realtime + push fan-out.
        String storedMessage = trimmedMessage == null ? "" : trimmedMessage;
        eventPublisher.publishEvent(new KudosSentEvent(
                roomId, senderUserId, targetUserId,
                messagePreview(body), occurredAt));

        if (log.isInfoEnabled()) {
            log.info("[kudos] roomId={} senderUserId={} targetUserId={} kudosId={}",
                    roomId, senderUserId, targetUserId, kudosId);
        }
        return new KudosDto(
                kudosId, roomId, senderUserId, targetUserId,
                storedMessage, occurredAt);
    }

    /**
     * Trim and length-check. Treats null / whitespace-only as null (omitted
     * message). The {@code @Valid @Size(max = 60)} on the DTO is the primary
     * gate; this is the storage-path defensive guard mirroring
     * {@link ChatService#normalizeBody} so a buggy caller that bypasses the
     * validator still gets a clean 400.
     */
    private static String normalizeMessage(String message) {
        if (message == null) {
            return null;
        }
        String trimmed = message.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_MESSAGE_LENGTH) {
            throw new BadRequestException(
                    "응원 메시지는 " + MAX_MESSAGE_LENGTH + "자까지 보낼 수 있어요.");
        }
        return trimmed;
    }

    /**
     * Locked Korean body string — "{nickname}이 응원을 보냈어요". Brand-voice
     * contract: zero AVOID-lexicon words (verified at test time via the
     * lint banned[] array). Format matches the receiver-side display
     * intent at epics 583.
     */
    private static String renderBody(User sender) {
        return sender.getNickname() + "이 응원을 보냈어요";
    }

    /**
     * Stable JSON shape consumed by the V12 partial unique index expression
     * {@code payload->>'targetUserId'}. Both ids stored as JSON strings —
     * V8/V9 milestone-dedup convention so a future numeric writer doesn't
     * silently break the index (the {@code text->>} operator returns text
     * regardless). {@code message} is always present (empty string when the
     * donor omitted it) so consumers can read a stable shape.
     */
    private String buildPayloadJson(long senderId, long targetId, String message) {
        // LinkedHashMap preserves key order for stable serialisation —
        // makes the on-disk shape deterministic for diffing test snapshots.
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("senderUserId", String.valueOf(senderId));
        payload.put("targetUserId", String.valueOf(targetId));
        payload.put("message", message == null ? "" : message);
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            // The payload is built from primitive longs + a short string —
            // serialisation cannot fail in practice. Wrap defensively so a
            // future shape change doesn't smuggle a checked exception out.
            throw new IllegalStateException("kudos payload serialisation failed", ex);
        }
    }

    private static String messagePreview(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= MESSAGE_PREVIEW_LENGTH
                ? body
                : body.substring(0, MESSAGE_PREVIEW_LENGTH);
    }

    /**
     * Service-layer DIVE discriminator — true iff the root cause message
     * names the V12 partial unique index. Mirrors
     * {@code RevivalService.isRevivalDedupConflict}.
     */
    private static boolean isKudosDedupConflict(DataIntegrityViolationException ex) {
        Throwable cause = ex.getMostSpecificCause();
        if (cause == null) {
            return false;
        }
        String message = cause.getMessage();
        return message != null && message.contains(DEDUP_CONSTRAINT);
    }
}
