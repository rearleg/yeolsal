package com.yeosal.api.survival;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yeosal.api.common.BadRequestException;
import com.yeosal.api.common.ForbiddenException;
import com.yeosal.api.common.NotFoundException;
import com.yeosal.api.room.Room;
import com.yeosal.api.room.RoomMemberRepository;
import com.yeosal.api.room.RoomRepository;
import com.yeosal.api.room.RoomService;
import com.yeosal.api.room.chat.ChatService;
import com.yeosal.api.user.User;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Write site and member-visible snapshot for the per-room monthly rule.
 * Month arithmetic uses the injected {@link Clock} and the named Seoul
 * zone so boundary behavior remains deterministic.
 */
@Service
public class RoomRuleService {

    private static final Logger log = LoggerFactory.getLogger(RoomRuleService.class);

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String SUPPORTED_PRESET = "DAILY_UPDATE";

    private final RoomRepository rooms;
    private final RoomMemberRepository roomMembers;
    private final RoomRuleVersionRepository ruleVersions;
    private final RoomService roomService;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ChatService chatService;

    public RoomRuleService(
            RoomRepository rooms,
            RoomMemberRepository roomMembers,
            RoomRuleVersionRepository ruleVersions,
            RoomService roomService,
            Clock clock,
            ObjectMapper objectMapper,
            ChatService chatService
    ) {
        this.rooms = rooms;
        this.roomMembers = roomMembers;
        this.ruleVersions = ruleVersions;
        this.roomService = roomService;
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.chatService = chatService;
    }

    /**
     * Persist a leader-staged rule edit for the next calendar month. The
     * UNIQUE {@code (room_id, effective_from_month)} constraint is resolved
     * by a race-free {@code ON CONFLICT DO UPDATE} so a same-month re-edit
     * replaces the pending row instead of duplicating it.
     */
    @Transactional
    public RoomRuleVersionDto updateRule(User me, long roomId, String preset, boolean weekendInclude) {
        Room room = rooms.findById(roomId)
                .orElseThrow(() -> new NotFoundException("방을 찾을 수 없습니다."));
        roomService.requireLeader(room, me);
        if (!SUPPORTED_PRESET.equals(preset)) {
            throw new BadRequestException("preset은 DAILY_UPDATE만 허용됩니다.");
        }
        String nextMonth = nextMonthKST();
        String payloadJson = serializePayload(preset, weekendInclude);
        ruleVersions.upsertRule(roomId, nextMonth, payloadJson, me.getId());
        RoomRuleVersion saved = ruleVersions
                .findByRoomIdAndEffectiveFromMonth(roomId, nextMonth)
                .orElseThrow(() -> new IllegalStateException(
                        "room_rule_versions row missing after upsert roomId="
                                + roomId + " month=" + nextMonth));
        RoomRuleVersionDto dto = RoomRuleVersionDto.from(saved);
        // Story 5.4 — defer the chat broadcast until this @Transactional commits
        // so a rolled-back rule edit cannot leave a "rule changed" announcement
        // behind. Inner try/catch keeps any broker / DB hiccup from leaking
        // back into the caller once the outer commit has succeeded.
        publishAfterCommit(() -> {
            try {
                chatService.publishRuleChangeSystemMessage(
                        roomId,
                        saved.getId(),
                        saved.getEffectiveFromMonth(),
                        dto.preset(),
                        dto.weekendInclude());
            } catch (RuntimeException ex) {
                log.warn("[chat] rule-change publish failed roomId={} ruleVersionId={}: {}",
                        roomId, saved.getId(), ex.toString());
            }
        });
        return dto;
    }

    /**
     * Defers {@code task} until the surrounding @Transactional commits, or
     * runs it inline when invoked outside a transaction (test fixtures).
     * Byte-identical to {@code DailyService.publishAfterCommit} — the helper
     * is duplicated rather than extracted because the cross-module shape will
     * only be worth abstracting once a third caller appears.
     */
    private void publishAfterCommit(Runnable task) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            task.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                task.run();
            }
        });
    }

    /**
     * Member-visible snapshot of the current rule plus any pending edit.
     * Reads two rows inside one read-only transaction so the leader's
     * pending row and the active current row are observed from the same
     * Postgres snapshot.
     */
    @Transactional(readOnly = true)
    public RoomRuleStateDto getRule(User viewer, long roomId) {
        if (!rooms.existsById(roomId)) {
            throw new NotFoundException("방을 찾을 수 없습니다.");
        }
        if (!roomMembers.existsByRoomIdAndUserId(roomId, viewer.getId())) {
            throw new ForbiddenException("방 멤버만 접근할 수 있습니다.");
        }
        YearMonth currentMonth = currentMonthKST();
        String nextMonth = currentMonth.plusMonths(1).toString();
        RoomRuleVersion currentRow = ruleVersions
                .findTopByRoomIdAndEffectiveFromMonthLessThanEqualOrderByEffectiveFromMonthDesc(
                        roomId, currentMonth.toString())
                .orElseThrow(() -> new IllegalStateException(
                        "rule version missing for roomId=" + roomId));
        Optional<RoomRuleVersion> pendingRow =
                ruleVersions.findByRoomIdAndEffectiveFromMonth(roomId, nextMonth);
        RoomRuleVersionDto pendingDto = pendingRow.map(RoomRuleVersionDto::from).orElse(null);
        return new RoomRuleStateDto(RoomRuleVersionDto.from(currentRow), pendingDto);
    }

    private YearMonth currentMonthKST() {
        LocalDate todayKst = LocalDate.ofInstant(clock.instant(), KST);
        return YearMonth.from(todayKst);
    }

    private String nextMonthKST() {
        LocalDate todayKst = LocalDate.ofInstant(clock.instant(), KST);
        return YearMonth.from(todayKst).plusMonths(1).toString();
    }

    private String serializePayload(String preset, boolean weekendInclude) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("preset", preset);
        node.put("weekendInclude", weekendInclude);
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("rule payload serialization failed", ex);
        }
    }
}
