package com.yeosal.api.survival;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.yeosal.api.daily.EntryDateResolver;
import com.yeosal.api.room.RoomRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Scheduler-level tests for {@link SurvivalStateEvaluatorJob} — paging,
 * per-room failure isolation, mass-elimination ERROR log (AC11), and
 * the targetDate "minus one minute" hardening (BE-5.2).
 */
@ExtendWith(MockitoExtension.class)
class SurvivalStateEvaluatorJobTest {

    @Mock private RoomRepository rooms;
    @Mock private SurvivalStateService survivalStateService;
    @Mock private EntryDateResolver entryDateResolver;

    private final Clock clock = Clock.fixed(
            Instant.parse("2026-05-11T03:14:15Z"), ZoneId.of("UTC"));

    private ListAppender<ILoggingEvent> logCapture;

    @BeforeEach
    void attachLogAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(SurvivalStateEvaluatorJob.class);
        logCapture = new ListAppender<>();
        logCapture.start();
        logger.addAppender(logCapture);
    }

    @AfterEach
    void detachLogAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(SurvivalStateEvaluatorJob.class);
        logger.detachAppender(logCapture);
    }

    @Test
    @DisplayName("targetDate: subtracts one minute, then resolves via EntryDateResolver in KST")
    void targetDateSubtractsOneMinuteBeforeResolving() {
        SurvivalStateEvaluatorJob job = newJob(20);
        Instant expectedInput = clock.instant().minusSeconds(60);
        when(entryDateResolver.resolve(expectedInput, ZoneId.of("Asia/Seoul")))
                .thenReturn(LocalDate.of(2026, 5, 10));

        LocalDate target = job.targetDate();

        assertThat(target).isEqualTo(LocalDate.of(2026, 5, 10));
        verify(entryDateResolver).resolve(expectedInput, ZoneId.of("Asia/Seoul"));
    }

    @Test
    @DisplayName("runEvaluation: empty pages short-circuit, no service calls, 0-count summary")
    void runEvaluation_emptyRooms_returnsZeroCounts() {
        SurvivalStateEvaluatorJob job = newJob(20);
        Pageable p1 = PageRequest.of(0, SurvivalStateEvaluatorJob.PAGE_SIZE);
        when(rooms.findAllIdsOrderById(p1)).thenReturn(new PageImpl<>(List.of(), p1, 0));

        SurvivalStateEvaluatorJob.Summary summary =
                job.runEvaluation(LocalDate.of(2026, 5, 10));

        assertThat(summary.evaluatedRooms()).isZero();
        assertThat(summary.failedRooms()).isZero();
        assertThat(summary.totalToRed()).isZero();
        verify(survivalStateService, never()).evaluateRoom(anyLong(), any());
    }

    @Test
    @DisplayName("runEvaluation: walks every page, delegates per-room, aggregates counts")
    void runEvaluation_walksAllPagesAndAggregates() {
        SurvivalStateEvaluatorJob job = newJob(20);
        LocalDate date = LocalDate.of(2026, 5, 10);

        Pageable p1 = PageRequest.of(0, SurvivalStateEvaluatorJob.PAGE_SIZE);
        // totalElements=400 forces pageCount=2 → page1.hasNext=true.
        Page<Long> page1 = new PageImpl<>(List.of(1L, 2L), p1, 400);
        Pageable p2 = page1.nextPageable();
        Page<Long> page2 = new PageImpl<>(List.of(3L), p2, 400);
        when(rooms.findAllIdsOrderById(p1)).thenReturn(page1);
        when(rooms.findAllIdsOrderById(p2)).thenReturn(page2);

        when(survivalStateService.evaluateRoom(1L, date)).thenReturn(
                new SurvivalStateService.EvaluationResult(1L, date, 3, 2, 0, 1, 0, 0));
        when(survivalStateService.evaluateRoom(2L, date)).thenReturn(
                new SurvivalStateService.EvaluationResult(2L, date, 5, 4, 1, 0, 0, 0));
        when(survivalStateService.evaluateRoom(3L, date)).thenReturn(
                new SurvivalStateService.EvaluationResult(3L, date, 4, 3, 0, 0, 1, 0));

        SurvivalStateEvaluatorJob.Summary summary = job.runEvaluation(date);

        assertThat(summary.evaluatedRooms()).isEqualTo(3);
        assertThat(summary.totalEvaluated()).isEqualTo(12);
        assertThat(summary.totalCompliant()).isEqualTo(9);
        assertThat(summary.totalFrozen()).isEqualTo(1);
        assertThat(summary.totalToYellow()).isEqualTo(1);
        assertThat(summary.totalToRed()).isEqualTo(1);
        assertThat(summary.failedRooms()).isZero();
    }

    @Test
    @DisplayName("runEvaluation: per-room exception is isolated, others continue, failedRooms++")
    void runEvaluation_perRoomFailureIsolated() {
        SurvivalStateEvaluatorJob job = newJob(20);
        LocalDate date = LocalDate.of(2026, 5, 10);

        Pageable p1 = PageRequest.of(0, SurvivalStateEvaluatorJob.PAGE_SIZE);
        Page<Long> page = new PageImpl<>(List.of(1L, 2L, 3L), p1, 3);
        when(rooms.findAllIdsOrderById(p1)).thenReturn(page);

        lenient().when(survivalStateService.evaluateRoom(1L, date)).thenReturn(
                new SurvivalStateService.EvaluationResult(1L, date, 1, 1, 0, 0, 0, 0));
        lenient().when(survivalStateService.evaluateRoom(2L, date))
                .thenThrow(new RuntimeException("simulated room failure"));
        lenient().when(survivalStateService.evaluateRoom(3L, date)).thenReturn(
                new SurvivalStateService.EvaluationResult(3L, date, 2, 2, 0, 0, 0, 0));

        SurvivalStateEvaluatorJob.Summary summary = job.runEvaluation(date);

        assertThat(summary.evaluatedRooms()).isEqualTo(3);
        assertThat(summary.failedRooms()).isEqualTo(1);
        assertThat(summary.totalCompliant()).isEqualTo(3); // 1 + 2, room 2 failed
    }

    @Test
    @DisplayName("runEvaluation: totalToRed > threshold → ERROR log with [evaluator][mass-elimination] prefix (AC11)")
    void runEvaluation_thresholdBreach_emitsMassEliminationErrorLog() {
        SurvivalStateEvaluatorJob job = newJob(20);
        LocalDate date = LocalDate.of(2026, 5, 10);

        Pageable p1 = PageRequest.of(0, SurvivalStateEvaluatorJob.PAGE_SIZE);
        Page<Long> page = new PageImpl<>(List.of(1L), p1, 1);
        when(rooms.findAllIdsOrderById(p1)).thenReturn(page);
        // 21 RED transitions in one room — breaches the default threshold of 20.
        when(survivalStateService.evaluateRoom(1L, date)).thenReturn(
                new SurvivalStateService.EvaluationResult(1L, date, 100, 0, 0, 0, 21, 0));

        SurvivalStateEvaluatorJob.Summary summary = job.runEvaluation(date);

        assertThat(summary.totalToRed()).isEqualTo(21);
        assertThat(logCapture.list)
                .anySatisfy(entry -> {
                    assertThat(entry.getLevel()).isEqualTo(Level.ERROR);
                    assertThat(entry.getFormattedMessage())
                            .contains("[evaluator][mass-elimination]")
                            .contains("redCount=21")
                            .contains("threshold=20")
                            .contains("targetDate=2026-05-10");
                });
    }

    @Test
    @DisplayName("runEvaluation: totalToRed == threshold → no mass-elimination alert (strict greater-than)")
    void runEvaluation_atThreshold_doesNotEmitMassEliminationLog() {
        SurvivalStateEvaluatorJob job = newJob(20);
        LocalDate date = LocalDate.of(2026, 5, 10);

        Pageable p1 = PageRequest.of(0, SurvivalStateEvaluatorJob.PAGE_SIZE);
        Page<Long> page = new PageImpl<>(List.of(1L), p1, 1);
        when(rooms.findAllIdsOrderById(p1)).thenReturn(page);
        when(survivalStateService.evaluateRoom(1L, date)).thenReturn(
                new SurvivalStateService.EvaluationResult(1L, date, 100, 0, 0, 0, 20, 0));

        job.runEvaluation(date);

        assertThat(logCapture.list)
                .noneMatch(entry -> entry.getFormattedMessage()
                        .contains("[evaluator][mass-elimination]"));
    }

    private SurvivalStateEvaluatorJob newJob(int threshold) {
        return new SurvivalStateEvaluatorJob(
                rooms, survivalStateService, entryDateResolver, clock, threshold);
    }
}
