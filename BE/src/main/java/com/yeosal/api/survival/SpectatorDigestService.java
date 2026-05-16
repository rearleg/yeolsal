package com.yeosal.api.survival;

import com.yeosal.api.daily.DailyEntryRepository;
import com.yeosal.api.room.chat.ChatMessageRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Story 2.2 — aggregates the previous KST day's activity for each room where
 * the caller is a spectator. The scheduler invokes
 * {@link #evaluateForUser(long, LocalDate)} once per user; an empty list
 * means no push is dispatched (an empty room is silently dropped, not a
 * "zero counts" push).
 *
 * <p>Window contract: for {@code priorEntryDate = D}, counts cover
 * {@code [D 06:00 KST, D+1 06:00 KST)} — the same boundary Story 1.2's
 * survival evaluator uses. The 09:00 KST cron passes {@code today - 1}
 * (KST) as {@code priorEntryDate}, summarizing yesterday's room.
 */
@Service
public class SpectatorDigestService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final SurvivalStateRepository survivalStates;
    private final ChatMessageRepository chatMessages;
    private final DailyEntryRepository dailyEntries;

    public SpectatorDigestService(
            SurvivalStateRepository survivalStates,
            ChatMessageRepository chatMessages,
            DailyEntryRepository dailyEntries) {
        this.survivalStates = survivalStates;
        this.chatMessages = chatMessages;
        this.dailyEntries = dailyEntries;
    }

    /**
     * Returns one {@link DigestEntry} per spectator-room with non-zero activity
     * on {@code priorEntryDate}. Rooms with all-zero counts are dropped.
     *
     * <p>Per-room cost: at most three count queries. For 50k users × ~8 rooms
     * each (project ceiling), the 09:00 KST cron runs ≤ 400k counts —
     * acceptable for a non-essential digest; v1 deliberately skips a
     * denormalized counter cache.
     */
    @Transactional(readOnly = true)
    public List<DigestEntry> evaluateForUser(long userId, LocalDate priorEntryDate) {
        Instant from = priorEntryDate.atStartOfDay(KST).plusHours(6).toInstant();
        Instant to = priorEntryDate.plusDays(1).atStartOfDay(KST).plusHours(6).toInstant();

        List<SurvivalState> rows = survivalStates.findByUserIdFetchingRoom(userId);
        List<DigestEntry> entries = new ArrayList<>();
        for (SurvivalState state : rows) {
            if (state.getStatus() != SurvivalStatus.SPECTATOR) {
                continue;
            }
            long roomId = state.getRoom().getId();
            long chatCount = chatMessages
                    .countByRoomIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(roomId, from, to);
            long stateCount = survivalStates
                    .countByRoomIdAndLastStateChangeAtGreaterThanEqualAndLastStateChangeAtLessThan(
                            roomId, from, to);
            long dailyCount = dailyEntries.countByEntryDateAndRoomId(priorEntryDate, roomId);
            if (chatCount == 0 && stateCount == 0 && dailyCount == 0) {
                continue;
            }
            entries.add(new DigestEntry(
                    roomId,
                    state.getRoom().getName(),
                    Math.toIntExact(chatCount),
                    Math.toIntExact(stateCount),
                    Math.toIntExact(dailyCount)));
        }
        return entries;
    }

    /**
     * Per-room activity summary for one spectator on one KST day. Counts are
     * non-negative; an entry with all-zero counts is not emitted (the
     * aggregator drops it).
     */
    public record DigestEntry(
            long roomId,
            String roomName,
            int chatMessageCount,
            int stateChangeCount,
            int dailyEntryCount) {}
}
