package com.yeosal.api.survival;

import com.yeosal.api.daily.EntryDateResolver;
import com.yeosal.api.room.RoomRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly 06:00 KST survival-state evaluator (Story 1.2 AC1, AC10, AC11).
 *
 * <p>Walks every room in page-sized chunks ({@link #PAGE_SIZE}) and delegates
 * each room to {@link SurvivalStateService#evaluateRoom(long, LocalDate)}.
 * Per-room evaluation is wrapped in try/catch so a single misbehaving room
 * never aborts the whole nightly run (mirrors {@code RoomEvaluationScheduler}).
 *
 * <p>{@code targetDate()} subtracts one minute from {@code clock.instant()}
 * before resolving to KST so the cron deterministically lands on the prior
 * entry-date even if the scheduler fires a few milliseconds early.
 *
 * <p>AC11 mass-elimination alert: a single nightly run that produces more
 * RED transitions than {@code yeosal.evaluator.mass-elimination-alert-threshold}
 * (default 20) logs at {@code ERROR} with prefix
 * {@code [evaluator][mass-elimination]}. Ops alert rules subscribe to this
 * log line; Sentry-integrated logging captures it as a server-bug event when
 * an SDK is wired.
 */
@Component
public class SurvivalStateEvaluatorJob {

    private static final Logger log = LoggerFactory.getLogger(SurvivalStateEvaluatorJob.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final int PAGE_SIZE = 200;

    private final RoomRepository rooms;
    private final SurvivalStateService survivalStateService;
    private final EntryDateResolver entryDateResolver;
    private final Clock clock;
    private final int massEliminationThreshold;

    public SurvivalStateEvaluatorJob(
            RoomRepository rooms,
            SurvivalStateService survivalStateService,
            EntryDateResolver entryDateResolver,
            Clock clock,
            @Value("${yeosal.evaluator.mass-elimination-alert-threshold:20}")
                    int massEliminationThreshold) {
        this.rooms = rooms;
        this.survivalStateService = survivalStateService;
        this.entryDateResolver = entryDateResolver;
        this.clock = clock;
        this.massEliminationThreshold = massEliminationThreshold;
    }

    @Scheduled(cron = "0 0 6 * * *", zone = "Asia/Seoul")
    public void evaluatePriorDay() {
        runEvaluation(targetDate());
    }

    /**
     * Package-private hook so tests (and ad-hoc admin invocations) can drive
     * the loop without going through the cron scheduler. Returns counts for
     * assertion / observability without mutating any controller state.
     */
    Summary runEvaluation(LocalDate priorEntryDate) {
        long started = System.currentTimeMillis();
        log.info("[evaluator] start date={} threshold={}", priorEntryDate, massEliminationThreshold);

        int evaluatedRooms = 0;
        int failedRooms = 0;
        int totalEvaluated = 0;
        int totalCompliant = 0;
        int totalFrozen = 0;
        int totalToYellow = 0;
        int totalToRed = 0;
        int totalSkipped = 0;

        Pageable page = PageRequest.of(0, PAGE_SIZE);
        Page<Long> roomIds;
        do {
            roomIds = rooms.findAllIdsOrderById(page);
            for (Long roomId : roomIds.getContent()) {
                evaluatedRooms += 1;
                try {
                    SurvivalStateService.EvaluationResult result =
                            survivalStateService.evaluateRoom(roomId, priorEntryDate);
                    totalEvaluated += result.evaluated();
                    totalCompliant += result.compliant();
                    totalFrozen += result.frozen();
                    totalToYellow += result.toYellow();
                    totalToRed += result.toRed();
                    totalSkipped += result.skipped();
                } catch (RuntimeException ex) {
                    failedRooms += 1;
                    log.error("[evaluator] room failed date={} roomId={}: {}",
                            priorEntryDate, roomId, ex.toString(), ex);
                }
            }
            page = roomIds.hasNext() ? roomIds.nextPageable() : null;
        } while (page != null);

        long elapsed = System.currentTimeMillis() - started;
        log.info("[evaluator] done date={} rooms={} failed={} evaluated={} compliant={} "
                        + "frozen={} toYellow={} toRed={} skipped={} elapsedMs={}",
                priorEntryDate, evaluatedRooms, failedRooms, totalEvaluated, totalCompliant,
                totalFrozen, totalToYellow, totalToRed, totalSkipped, elapsed);

        if (totalToRed > massEliminationThreshold) {
            log.error("[evaluator][mass-elimination] redCount={} threshold={} targetDate={} "
                            + "totalRoomsEvaluated={}",
                    totalToRed, massEliminationThreshold, priorEntryDate, evaluatedRooms);
        }

        return new Summary(
                priorEntryDate,
                evaluatedRooms,
                failedRooms,
                totalEvaluated,
                totalCompliant,
                totalFrozen,
                totalToYellow,
                totalToRed,
                totalSkipped,
                elapsed);
    }

    LocalDate targetDate() {
        return entryDateResolver.resolve(clock.instant().minus(Duration.ofMinutes(1)), KST);
    }

    record Summary(
            LocalDate date,
            int evaluatedRooms,
            int failedRooms,
            int totalEvaluated,
            int totalCompliant,
            int totalFrozen,
            int totalToYellow,
            int totalToRed,
            int totalSkipped,
            long elapsedMs) {}
}
