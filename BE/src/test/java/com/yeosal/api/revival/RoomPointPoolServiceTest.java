package com.yeosal.api.revival;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Story 4.1 BE-7 — unit assertions for the {@link RoomPointPoolService}
 * single chokepoint introduced by AC3.
 *
 * <p>Six cases mirror the BE-7 spec checklist:
 * <ol>
 *   <li>{@code delta = -1} → {@link IllegalArgumentException}, no DB
 *       writes, no publish.</li>
 *   <li>{@code delta = 0} → {@link IllegalArgumentException} (boundary —
 *       the contract is "strictly positive", not "non-negative").</li>
 *   <li>Positive delta — locks the row, increments, publishes, returns
 *       the post-increment total.</li>
 *   <li>{@code selectForUpdate} returns empty → {@link IllegalStateException}
 *       ("row missing"), no increment, no publish.</li>
 *   <li>{@code incrementTotal} returns 0 rows updated →
 *       {@link IllegalStateException} ("row vanished mid-transaction"),
 *       no publish.</li>
 *   <li>{@link PointPoolChangeEvent} fields match — {@code roomId},
 *       {@code delta}, {@code newTotal} (computed pre-increment + delta),
 *       {@code sourceRevivalEventId}, {@code occurredAt} are all
 *       forwarded verbatim.</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class RoomPointPoolServiceTest {

    private static final long ROOM_ID = 42L;
    private static final long EVENT_ID = 9001L;
    private static final Instant NOW = Instant.parse("2026-06-01T03:14:15Z");

    @Mock private RoomPointPoolRepository repo;
    @Mock private ApplicationEventPublisher eventPublisher;

    private RoomPointPoolService service;

    @BeforeEach
    void setUp() {
        service = new RoomPointPoolService(repo, eventPublisher);
    }

    @Test
    @DisplayName("AC3 — negative delta throws IllegalArgumentException, no DB writes, no publish")
    void applyDelta_negativeDelta_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.applyDelta(ROOM_ID, -1, EVENT_ID, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pool delta must be positive")
                .hasMessageContaining("-1");

        verify(repo, never()).selectForUpdate(anyLong());
        verify(repo, never()).incrementTotal(anyLong(), anyInt());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("AC3 boundary — zero delta throws IllegalArgumentException (strictly positive contract)")
    void applyDelta_zeroDelta_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.applyDelta(ROOM_ID, 0, EVENT_ID, NOW))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pool delta must be positive")
                .hasMessageContaining("0");

        verify(repo, never()).selectForUpdate(anyLong());
        verify(repo, never()).incrementTotal(anyLong(), anyInt());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("AC3 happy — selectForUpdate then incrementTotal in order, returns pre+delta total")
    void applyDelta_positiveDelta_acquiresLockAndIncrements() {
        when(repo.selectForUpdate(ROOM_ID)).thenReturn(Optional.of(poolWithTotal(10)));
        when(repo.incrementTotal(ROOM_ID, 5)).thenReturn(1);

        int newTotal = service.applyDelta(ROOM_ID, 5, EVENT_ID, NOW);

        assertThat(newTotal).isEqualTo(15);

        InOrder ordered = inOrder(repo, eventPublisher);
        ordered.verify(repo).selectForUpdate(ROOM_ID);
        ordered.verify(repo).incrementTotal(ROOM_ID, 5);
        ordered.verify(eventPublisher).publishEvent(any(PointPoolChangeEvent.class));
    }

    @Test
    @DisplayName("AC3 defence — missing pool row → IllegalStateException, no increment, no publish")
    void applyDelta_missingPoolRow_throwsIllegalState() {
        when(repo.selectForUpdate(ROOM_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.applyDelta(ROOM_ID, 5, EVENT_ID, NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("room_point_pool row missing")
                .hasMessageContaining(String.valueOf(ROOM_ID));

        verify(repo, never()).incrementTotal(anyLong(), anyInt());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("AC3 defence — incrementTotal returns 0 rows updated → IllegalStateException, no publish")
    void applyDelta_zeroRowsUpdated_throwsIllegalState() {
        when(repo.selectForUpdate(ROOM_ID)).thenReturn(Optional.of(poolWithTotal(10)));
        when(repo.incrementTotal(ROOM_ID, 5)).thenReturn(0);

        assertThatThrownBy(() -> service.applyDelta(ROOM_ID, 5, EVENT_ID, NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("row vanished mid-transaction")
                .hasMessageContaining(String.valueOf(ROOM_ID));

        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("AC4 — PointPoolChangeEvent carries roomId/delta/newTotal/sourceRevivalEventId/occurredAt verbatim")
    void applyDelta_publishesPointPoolChangeEventWithComputedNewTotal() {
        when(repo.selectForUpdate(ROOM_ID)).thenReturn(Optional.of(poolWithTotal(20)));
        when(repo.incrementTotal(ROOM_ID, 3)).thenReturn(1);

        service.applyDelta(ROOM_ID, 3, EVENT_ID, NOW);

        ArgumentCaptor<PointPoolChangeEvent> captor =
                ArgumentCaptor.forClass(PointPoolChangeEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());

        PointPoolChangeEvent published = captor.getValue();
        assertThat(published.roomId()).isEqualTo(ROOM_ID);
        assertThat(published.delta()).isEqualTo(3);
        assertThat(published.newTotal()).isEqualTo(23);
        assertThat(published.sourceRevivalEventId()).isEqualTo(EVENT_ID);
        assertThat(published.occurredAt()).isEqualTo(NOW);
    }

    private static RoomPointPool poolWithTotal(int total) {
        return new RoomPointPool(ROOM_ID, total);
    }
}
