package com.yeosal.api.ceremony;

import com.yeosal.api.realtime.RealtimePublisher;
import com.yeosal.api.survival.SurvivalStateRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Story 7.2 — fires at 06:30 KST on day-1 of each calendar month and
 * generates Final-3 posters for every room with at least one ACTIVE
 * survivor from the prior month. Mirrors {@code RoomEvaluationScheduler}'s
 * monthly-cron shape; differs in the parallel-render orchestration
 * required to honor NFR-9.1.4 (10 min for 5K rooms).
 *
 * <p>Per-room work is fanned out onto {@link MonthlyPosterRenderExecutorConfig}'s
 * dedicated 8-thread pool via {@link CompletableFuture#runAsync(Runnable, java.util.concurrent.Executor)};
 * the loop drains via {@code allOf().join()} so the cron method only
 * returns once every submitted room has been processed (success or
 * fail). Per-room try/catch wraps the worker body so a single bad row
 * never aborts the batch.
 *
 * <p>The publish to {@code /topic/rooms.{id}.posters} fires <strong>only</strong>
 * when a poster row was freshly generated — idempotent reruns and
 * zero-survivor races are silent on the wire.
 */
@Component
public class FinalThreeJob {

    private static final Logger log = LoggerFactory.getLogger(FinalThreeJob.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final long BUDGET_MS = Duration.ofMinutes(10).toMillis();
    static final int PAGE_SIZE = 200;

    private final SurvivalStateRepository survivalStates;
    private final FinalThreeService finalThreeService;
    private final RealtimePublisher realtimePublisher;
    private final TaskExecutor executor;
    private final Clock clock;

    public FinalThreeJob(
            SurvivalStateRepository survivalStates,
            FinalThreeService finalThreeService,
            RealtimePublisher realtimePublisher,
            @Qualifier(MonthlyPosterRenderExecutorConfig.EXECUTOR_BEAN_NAME)
                    TaskExecutor executor,
            Clock clock) {
        this.survivalStates = survivalStates;
        this.finalThreeService = finalThreeService;
        this.realtimePublisher = realtimePublisher;
        this.executor = executor;
        this.clock = clock;
    }

    @Scheduled(cron = "0 30 6 1 * *", zone = "Asia/Seoul")
    public void runMonthlyBatch() {
        runBatch(targetMonth());
    }

    /**
     * Package-private hook so tests (and ad-hoc admin tools) can drive
     * the loop without going through the cron scheduler. Returns a
     * {@link Summary} for assertion + observability.
     */
    Summary runBatch(YearMonth target) {
        long started = System.currentTimeMillis();
        log.info("[ceremony][month-job] start target={} pageSize={}", target, PAGE_SIZE);

        AtomicInteger eligible = new AtomicInteger();
        AtomicInteger generated = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();      // existing row (idempotent)
        AtomicInteger zeroSurvivor = new AtomicInteger(); // race guard hit
        AtomicInteger failed = new AtomicInteger();

        Pageable page = PageRequest.of(0, PAGE_SIZE);
        Page<Long> roomIds;
        do {
            List<CompletableFuture<Void>> inflight = new ArrayList<>();
            roomIds = survivalStates.findRoomIdsWithAtLeastOneActive(page);
            for (Long roomId : roomIds.getContent()) {
                eligible.incrementAndGet();
                final long rid = roomId;
                CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
                    try {
                        processRoom(rid, target, generated, skipped, zeroSurvivor);
                    } catch (RuntimeException ex) {
                        failed.incrementAndGet();
                        log.error("[ceremony][month-job] room failed target={} roomId={}: {}",
                                target, rid, ex.toString(), ex);
                    }
                }, executor);
                inflight.add(task);
            }
            drain(inflight);
            page = roomIds.hasNext() ? roomIds.nextPageable() : null;
        } while (page != null);

        long elapsed = System.currentTimeMillis() - started;
        log.info("[ceremony][month-job] done target={} eligible={} generated={} "
                        + "skipped={} zeroSurvivor={} failed={} elapsedMs={}",
                target, eligible.get(), generated.get(), skipped.get(),
                zeroSurvivor.get(), failed.get(), elapsed);

        if (isOverBudget(elapsed)) {
            log.error("[ceremony][month-job][budget-exceeded] target={} elapsedMs={} "
                            + "eligible={} thresholdMs={}",
                    target, elapsed, eligible.get(), BUDGET_MS);
        }

        return new Summary(target, eligible.get(), generated.get(), skipped.get(),
                zeroSurvivor.get(), failed.get(), elapsed);
    }

    private void processRoom(
            long roomId, YearMonth target,
            AtomicInteger generated, AtomicInteger skipped, AtomicInteger zeroSurvivor) {
        FinalThreeService.GenerationResult result =
                finalThreeService.generatePosterWithResult(roomId, target);
        if (result.poster().isEmpty()) {
            zeroSurvivor.incrementAndGet();
            return;
        }
        if (!result.created()) {
            skipped.incrementAndGet();
            return;
        }
        generated.incrementAndGet();
        realtimePublisher.publishMonthlyPosterReady(
                roomId, MonthlyPosterReadyPayload.of(roomId, target));
    }

    private static void drain(List<CompletableFuture<Void>> inflight) {
        CompletableFuture.allOf(inflight.toArray(CompletableFuture[]::new)).join();
    }

    YearMonth targetMonth() {
        return YearMonth.now(clock.withZone(KST)).minusMonths(1);
    }

    /**
     * Package-private seam for AC10 test case 8 — unit tests
     * {@code Mockito.spy()} this method to force the budget-exceeded
     * branch without actually sleeping 10 minutes. Default returns
     * {@code elapsedMs > BUDGET_MS}.
     */
    boolean isOverBudget(long elapsedMs) {
        return elapsedMs > BUDGET_MS;
    }

    record Summary(
            YearMonth month,
            int eligible,
            int generated,
            int skipped,
            int zeroSurvivor,
            int failed,
            long elapsedMs) {}
}
