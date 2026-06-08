package com.yeosal.api.ceremony;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.yeosal.api.realtime.RealtimePublisher;
import com.yeosal.api.survival.SurvivalStateRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Story 7.2 — orchestration-level coverage for {@link FinalThreeJob}.
 * Covers the empty / generated / skipped / zero-survivor / failure
 * branches of {@code processRoom}, the page-walk loop, parallel
 * execution thread-name capture, the budget-exceeded ERROR log seam,
 * clock semantics at day + year boundary, broker-failure isolation,
 * and the {@code Summary} record field projection. Mocked
 * {@link FinalThreeService} keeps Batik out of the unit suite — real
 * Batik runs in {@code FinalThreeJobIT}.
 */
@ExtendWith(MockitoExtension.class)
class FinalThreeJobTest {

    @Mock private SurvivalStateRepository survivalStates;
    @Mock private FinalThreeService finalThreeService;
    @Mock private RealtimePublisher realtimePublisher;

    private final Clock clock = Clock.fixed(
            Instant.parse("2026-06-01T03:14:15Z"), ZoneId.of("UTC"));

    private ListAppender<ILoggingEvent> logCapture;

    @BeforeEach
    void attachLogAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(FinalThreeJob.class);
        logCapture = new ListAppender<>();
        logCapture.start();
        logger.addAppender(logCapture);
    }

    @AfterEach
    void detachLogAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(FinalThreeJob.class);
        logger.detachAppender(logCapture);
    }

    @Test
    @DisplayName("runBatch: no eligible rooms → service untouched, no publish, summary all-zero")
    void runBatch_emptyEligible_zeroSummary() {
        FinalThreeJob job = newJob(new SyncTaskExecutor());
        YearMonth target = YearMonth.of(2026, 5);
        emptyPage();

        FinalThreeJob.Summary summary = job.runBatch(target);

        assertThat(summary.eligible()).isZero();
        assertThat(summary.generated()).isZero();
        assertThat(summary.skipped()).isZero();
        assertThat(summary.zeroSurvivor()).isZero();
        assertThat(summary.failed()).isZero();
        verify(finalThreeService, never()).generatePosterWithResult(anyLong(), any());
        verify(realtimePublisher, never()).publishMonthlyPosterReady(anyLong(), any());
    }

    @Test
    @DisplayName("runBatch: single fresh room → service called once, publish fires with exact payload")
    void runBatch_freshGeneration_publishesOnce() {
        FinalThreeJob job = newJob(new SyncTaskExecutor());
        YearMonth target = YearMonth.of(2026, 5);
        singlePage(42L);
        FinalThreePoster generated = new FinalThreePoster(42L, "2026-05", "<svg/>", "url");
        when(finalThreeService.generatePosterWithResult(42L, target))
                .thenReturn(new FinalThreeService.GenerationResult(Optional.of(generated), true));

        FinalThreeJob.Summary summary = job.runBatch(target);

        assertThat(summary.eligible()).isEqualTo(1);
        assertThat(summary.generated()).isEqualTo(1);
        assertThat(summary.skipped()).isZero();
        assertThat(summary.zeroSurvivor()).isZero();
        assertThat(summary.failed()).isZero();
        verify(realtimePublisher).publishMonthlyPosterReady(
                42L, MonthlyPosterReadyPayload.of(42L, target));
    }

    @Test
    @DisplayName("runBatch: idempotent rerun (poster pre-existed) → skipped++, NO publish")
    void runBatch_preExisted_skippedNoPublish() {
        FinalThreeJob job = newJob(new SyncTaskExecutor());
        YearMonth target = YearMonth.of(2026, 5);
        singlePage(42L);
        FinalThreePoster existing = new FinalThreePoster(42L, "2026-05", "<svg/>", "url");
        when(finalThreeService.generatePosterWithResult(42L, target))
                .thenReturn(new FinalThreeService.GenerationResult(Optional.of(existing), false));

        FinalThreeJob.Summary summary = job.runBatch(target);

        assertThat(summary.eligible()).isEqualTo(1);
        assertThat(summary.skipped()).isEqualTo(1);
        assertThat(summary.generated()).isZero();
        verify(realtimePublisher, never()).publishMonthlyPosterReady(anyLong(), any());
    }

    @Test
    @DisplayName("runBatch: zero-survivor race (Optional.empty) → zeroSurvivor++, NO publish")
    void runBatch_zeroSurvivor_noPublish() {
        FinalThreeJob job = newJob(new SyncTaskExecutor());
        YearMonth target = YearMonth.of(2026, 5);
        singlePage(42L);
        when(finalThreeService.generatePosterWithResult(42L, target))
                .thenReturn(new FinalThreeService.GenerationResult(Optional.empty(), false));

        FinalThreeJob.Summary summary = job.runBatch(target);

        assertThat(summary.zeroSurvivor()).isEqualTo(1);
        assertThat(summary.generated()).isZero();
        verify(realtimePublisher, never()).publishMonthlyPosterReady(anyLong(), any());
    }

    @Test
    @DisplayName("runBatch: one room throws → next room still processes, failed=1, ERROR log emitted")
    void runBatch_perRoomFailure_isolated() {
        FinalThreeJob job = newJob(new SyncTaskExecutor());
        YearMonth target = YearMonth.of(2026, 5);
        Pageable p1 = PageRequest.of(0, FinalThreeJob.PAGE_SIZE);
        when(survivalStates.findRoomIdsWithAtLeastOneActive(p1))
                .thenReturn(new PageImpl<>(List.of(1L, 2L), p1, 2));
        when(finalThreeService.generatePosterWithResult(1L, target))
                .thenThrow(new RuntimeException("boom"));
        FinalThreePoster good = new FinalThreePoster(2L, "2026-05", "<svg/>", "url");
        when(finalThreeService.generatePosterWithResult(2L, target))
                .thenReturn(new FinalThreeService.GenerationResult(Optional.of(good), true));

        FinalThreeJob.Summary summary = job.runBatch(target);

        assertThat(summary.eligible()).isEqualTo(2);
        assertThat(summary.generated()).isEqualTo(1);
        assertThat(summary.failed()).isEqualTo(1);
        verify(realtimePublisher).publishMonthlyPosterReady(
                2L, MonthlyPosterReadyPayload.of(2L, target));
        assertThat(logCapture.list).anySatisfy(entry -> {
            assertThat(entry.getLevel()).isEqualTo(Level.ERROR);
            assertThat(entry.getFormattedMessage())
                    .contains("[ceremony][month-job]")
                    .contains("room failed")
                    .contains("roomId=1");
        });
    }

    @Test
    @DisplayName("runBatch: 250 rooms across 2 pages → both pages iterated, contiguous coverage")
    void runBatch_pageBoundary_walksAllPages() {
        FinalThreeJob job = newJob(new SyncTaskExecutor());
        YearMonth target = YearMonth.of(2026, 5);
        Pageable p1 = PageRequest.of(0, FinalThreeJob.PAGE_SIZE);
        List<Long> firstPage = new java.util.ArrayList<>();
        for (long i = 1; i <= 200; i++) firstPage.add(i);
        Page<Long> page1 = new PageImpl<>(firstPage, p1, 250);
        Pageable p2 = page1.nextPageable();
        List<Long> secondPage = new java.util.ArrayList<>();
        for (long i = 201; i <= 250; i++) secondPage.add(i);
        Page<Long> page2 = new PageImpl<>(secondPage, p2, 250);
        when(survivalStates.findRoomIdsWithAtLeastOneActive(p1)).thenReturn(page1);
        when(survivalStates.findRoomIdsWithAtLeastOneActive(p2)).thenReturn(page2);
        when(finalThreeService.generatePosterWithResult(anyLong(), any()))
                .thenAnswer(inv -> generatedResult(inv.getArgument(0)));

        FinalThreeJob.Summary summary = job.runBatch(target);

        assertThat(summary.eligible()).isEqualTo(250);
        assertThat(summary.generated()).isEqualTo(250);
        verify(survivalStates).findRoomIdsWithAtLeastOneActive(p1);
        verify(survivalStates).findRoomIdsWithAtLeastOneActive(p2);
    }

    @Test
    @DisplayName("runBatch: production-sized executor drains per page so 300 slow rooms do not saturate queue 256")
    void runBatch_productionExecutor_drainsPerPageWithoutQueueRejection() {
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(8);
        pool.setMaxPoolSize(8);
        pool.setQueueCapacity(256);
        pool.setThreadNamePrefix("monthly-poster-");
        pool.initialize();
        try {
            FinalThreeJob job = newJob(pool);
            YearMonth target = YearMonth.of(2026, 5);
            Pageable p1 = PageRequest.of(0, FinalThreeJob.PAGE_SIZE);
            List<Long> firstPage = new java.util.ArrayList<>();
            for (long i = 1; i <= 200; i++) firstPage.add(i);
            Page<Long> page1 = new PageImpl<>(firstPage, p1, 300);
            Pageable p2 = page1.nextPageable();
            List<Long> secondPage = new java.util.ArrayList<>();
            for (long i = 201; i <= 300; i++) secondPage.add(i);
            Page<Long> page2 = new PageImpl<>(secondPage, p2, 300);
            when(survivalStates.findRoomIdsWithAtLeastOneActive(p1)).thenReturn(page1);
            when(survivalStates.findRoomIdsWithAtLeastOneActive(p2)).thenReturn(page2);
            when(finalThreeService.generatePosterWithResult(anyLong(), any()))
                    .thenAnswer(inv -> {
                        TimeUnit.MILLISECONDS.sleep(10);
                        return generatedResult(inv.getArgument(0));
                    });

            FinalThreeJob.Summary summary = job.runBatch(target);

            assertThat(summary.eligible()).isEqualTo(300);
            assertThat(summary.generated()).isEqualTo(300);
            assertThat(summary.failed()).isZero();
        } finally {
            pool.shutdown();
        }
    }

    @Test
    @DisplayName("runBatch: parallel execution via real ThreadPoolTaskExecutor → monthly-poster- thread name observed")
    void runBatch_parallelExecution_capturesThreadName() throws Exception {
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(4);
        pool.setMaxPoolSize(4);
        pool.setThreadNamePrefix("monthly-poster-");
        pool.initialize();
        try {
            FinalThreeJob job = newJob(pool);
            YearMonth target = YearMonth.of(2026, 5);
            Pageable p1 = PageRequest.of(0, FinalThreeJob.PAGE_SIZE);
            when(survivalStates.findRoomIdsWithAtLeastOneActive(p1))
                    .thenReturn(new PageImpl<>(List.of(1L, 2L, 3L, 4L), p1, 4));
            Set<String> threadNames = java.util.Collections.synchronizedSet(new HashSet<>());
            CountDownLatch latch = new CountDownLatch(4);
            when(finalThreeService.generatePosterWithResult(anyLong(), any())).thenAnswer(inv -> {
                threadNames.add(Thread.currentThread().getName());
                latch.countDown();
                latch.await(1, TimeUnit.SECONDS); // hold so multiple threads collide
                return generatedResult(inv.getArgument(0));
            });

            FinalThreeJob.Summary summary = job.runBatch(target);

            assertThat(summary.generated()).isEqualTo(4);
            assertThat(threadNames).anyMatch(name -> name.startsWith("monthly-poster-"));
        } finally {
            pool.shutdown();
        }
    }

    @Test
    @DisplayName("runBatch: budget-exceeded seam triggers [ceremony][month-job][budget-exceeded] ERROR log")
    void runBatch_budgetExceeded_emitsErrorLog() {
        FinalThreeJob real = newJob(new SyncTaskExecutor());
        FinalThreeJob job = spy(real);
        doReturn(true).when(job).isOverBudget(anyLong());
        YearMonth target = YearMonth.of(2026, 5);
        emptyPage();

        job.runBatch(target);

        assertThat(logCapture.list).anySatisfy(entry -> {
            assertThat(entry.getLevel()).isEqualTo(Level.ERROR);
            assertThat(entry.getFormattedMessage())
                    .contains("[ceremony][month-job][budget-exceeded]")
                    .contains("elapsedMs=")
                    .contains("thresholdMs=");
        });
    }

    @Test
    @DisplayName("targetMonth: Clock at 2026-06-01T06:30 KST → target = YearMonth(2026, 5)")
    void targetMonth_dayBoundary_returnsPriorMonth() {
        // 06:30 KST on 2026-06-01 == 21:30 UTC on 2026-05-31. The withZone(KST)
        // shift is what makes YearMonth.now() return June (not May), so
        // .minusMonths(1) lands on May.
        Clock kstSix30 = Clock.fixed(
                Instant.parse("2026-05-31T21:30:00Z"), ZoneId.of("UTC"));
        FinalThreeJob job = new FinalThreeJob(
                survivalStates, finalThreeService, realtimePublisher,
                new SyncTaskExecutor(), kstSix30);

        assertThat(job.targetMonth()).isEqualTo(YearMonth.of(2026, 5));
    }

    @Test
    @DisplayName("targetMonth: Clock at 2026-01-01T06:30 KST → target = YearMonth(2025, 12) cross-year")
    void targetMonth_crossYearBoundary_returnsPriorDecember() {
        Clock kstJan1 = Clock.fixed(
                Instant.parse("2025-12-31T21:30:00Z"), ZoneId.of("UTC"));
        FinalThreeJob job = new FinalThreeJob(
                survivalStates, finalThreeService, realtimePublisher,
                new SyncTaskExecutor(), kstJan1);

        assertThat(job.targetMonth()).isEqualTo(YearMonth.of(2025, 12));
    }

    @Test
    @DisplayName("runBatch: broker failure swallowed by publisher keeps generated room out of failed counter")
    void runBatch_brokerFailureSwallowed_generatedNotFailed() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        doThrow(new MessagingException("broker down"))
                .when(template).convertAndSend(anyString(), any(Object.class));
        RealtimePublisher swallowingPublisher = new RealtimePublisher(template);
        FinalThreeJob job = new FinalThreeJob(
                survivalStates, finalThreeService, swallowingPublisher, new SyncTaskExecutor(), clock);
        YearMonth target = YearMonth.of(2026, 5);
        singlePage(42L);
        FinalThreePoster generated = new FinalThreePoster(42L, "2026-05", "<svg/>", "url");
        when(finalThreeService.generatePosterWithResult(42L, target))
                .thenReturn(new FinalThreeService.GenerationResult(Optional.of(generated), true));

        FinalThreeJob.Summary summary = job.runBatch(target);

        assertThat(summary.generated()).isEqualTo(1);
        assertThat(summary.failed()).isZero();
    }

    @Test
    @DisplayName("Summary record field projection — eligible/generated/skipped/zeroSurvivor/failed/elapsedMs round-trip")
    void summary_recordFieldShape() {
        FinalThreeJob.Summary s = new FinalThreeJob.Summary(
                YearMonth.of(2026, 5), 5, 3, 1, 1, 0, 1234L);
        assertThat(s.month()).isEqualTo(YearMonth.of(2026, 5));
        assertThat(s.eligible()).isEqualTo(5);
        assertThat(s.generated()).isEqualTo(3);
        assertThat(s.skipped()).isEqualTo(1);
        assertThat(s.zeroSurvivor()).isEqualTo(1);
        assertThat(s.failed()).isZero();
        assertThat(s.elapsedMs()).isEqualTo(1234L);
    }

    // ---- helpers ----

    private FinalThreeJob newJob(TaskExecutor executor) {
        return new FinalThreeJob(
                survivalStates, finalThreeService, realtimePublisher, executor, clock);
    }

    private void emptyPage() {
        Pageable p1 = PageRequest.of(0, FinalThreeJob.PAGE_SIZE);
        when(survivalStates.findRoomIdsWithAtLeastOneActive(p1))
                .thenReturn(new PageImpl<>(List.of(), p1, 0));
    }

    private void singlePage(long roomId) {
        Pageable p1 = PageRequest.of(0, FinalThreeJob.PAGE_SIZE);
        when(survivalStates.findRoomIdsWithAtLeastOneActive(p1))
                .thenReturn(new PageImpl<>(List.of(roomId), p1, 1));
    }

    private static FinalThreeService.GenerationResult generatedResult(long roomId) {
        return new FinalThreeService.GenerationResult(Optional.of(
                new FinalThreePoster(roomId, "2026-05", "<svg/>", "url")), true);
    }
}
