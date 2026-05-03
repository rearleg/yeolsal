package com.yeosal.api.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class RoomEvaluationSchedulerTest {

    @Mock private RoomRepository rooms;
    @Mock private RoomService roomService;

    @Test
    @DisplayName("targetMonth: derives previous calendar month in KST regardless of clock zone")
    void targetMonthIsPreviousMonthInKst() {
        // 2026-04-30T15:10:00Z is 2026-05-01T00:10:00 KST — start of May → target April.
        Clock clock = Clock.fixed(Instant.parse("2026-04-30T15:10:00Z"), ZoneId.of("UTC"));
        var scheduler = new RoomEvaluationScheduler(rooms, roomService, clock);

        assertThat(scheduler.targetMonth()).isEqualTo(YearMonth.of(2026, 4));
    }

    @Test
    @DisplayName("runEvaluation: walks each page and delegates per-room to RoomService")
    void walksPagesAndDelegates() {
        Clock clock = Clock.fixed(Instant.parse("2026-05-01T00:10:00Z"), ZoneId.of("UTC"));
        var scheduler = new RoomEvaluationScheduler(rooms, roomService, clock);
        YearMonth target = YearMonth.of(2026, 4);

        Pageable p1 = PageRequest.of(0, RoomEvaluationScheduler.PAGE_SIZE);
        // totalElements=400 forces totalPages=2 → page1.hasNext=true.
        Page<Long> page1 = new PageImpl<>(List.of(1L, 2L), p1, 400);
        Pageable p2 = page1.nextPageable();
        Page<Long> page2 = new PageImpl<>(List.of(3L), p2, 400);
        when(rooms.findAllIdsOrderById(p1)).thenReturn(page1);
        when(rooms.findAllIdsOrderById(p2)).thenReturn(page2);

        var summary = scheduler.runEvaluation(target);

        assertThat(summary.month()).isEqualTo(target);
        assertThat(summary.rooms()).isEqualTo(3);
        assertThat(summary.failed()).isEqualTo(0);
        verify(roomService).evaluateRoom(1L, target);
        verify(roomService).evaluateRoom(2L, target);
        verify(roomService).evaluateRoom(3L, target);
    }

    @Test
    @DisplayName("runEvaluation: per-room failures are isolated, others continue")
    void perRoomFailureIsolated() {
        Clock clock = Clock.fixed(Instant.parse("2026-05-01T00:10:00Z"), ZoneId.of("UTC"));
        var scheduler = new RoomEvaluationScheduler(rooms, roomService, clock);
        YearMonth target = YearMonth.of(2026, 4);

        Pageable p1 = PageRequest.of(0, RoomEvaluationScheduler.PAGE_SIZE);
        Page<Long> page = new PageImpl<>(List.of(1L, 2L, 3L), p1, 3);
        when(rooms.findAllIdsOrderById(p1)).thenReturn(page);
        // Strict stubbing would flag the 1L/3L invocations as argument mismatch
        // against this 2L-specific stub; lenient() lets the other paths pass
        // through Mockito's default void-returning behaviour.
        lenient()
                .doThrow(new RuntimeException("simulated"))
                .when(roomService).evaluateRoom(2L, target);

        var summary = scheduler.runEvaluation(target);

        assertThat(summary.rooms()).isEqualTo(3);
        assertThat(summary.failed()).isEqualTo(1);
        verify(roomService).evaluateRoom(1L, target);
        verify(roomService).evaluateRoom(2L, target);
        verify(roomService).evaluateRoom(3L, target);
    }

    @Test
    @DisplayName("runEvaluation: empty page short-circuits the loop")
    void emptyDatabaseIsNoop() {
        Clock clock = Clock.fixed(Instant.parse("2026-05-01T00:10:00Z"), ZoneId.of("UTC"));
        var scheduler = new RoomEvaluationScheduler(rooms, roomService, clock);
        YearMonth target = YearMonth.of(2026, 4);

        Pageable p1 = PageRequest.of(0, RoomEvaluationScheduler.PAGE_SIZE);
        when(rooms.findAllIdsOrderById(p1)).thenReturn(new PageImpl<>(List.of(), p1, 0));

        var summary = scheduler.runEvaluation(target);

        assertThat(summary.rooms()).isEqualTo(0);
        assertThat(summary.failed()).isEqualTo(0);
        verify(roomService, never()).evaluateRoom(anyLong(), any());
    }
}
