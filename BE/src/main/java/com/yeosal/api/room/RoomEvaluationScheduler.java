package com.yeosal.api.room;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fires once a month, at 00:10 KST on the 1st, and walks every room in
 * page-sized chunks delegating each room to
 * {@link RoomService#evaluateRoom(long, YearMonth)}.
 *
 * <p>The "10 minutes past midnight" choice deliberately avoids the wall
 * clock boundary: tail writes from the last day of the prior month land
 * in their own daily entry before the cron picks the previous-month
 * window, and Postgres sequence/index churn from 00:00:00 dailies
 * settles before we start counting.
 *
 * <p>Per-room evaluation is wrapped in try/catch so a single misbehaving
 * row never aborts the whole monthly run. The fan-out is deliberately
 * sequential — at the room counts we expect a parallel stream would buy
 * latency we do not need at the cost of less predictable DB pressure.
 */
@Component
public class RoomEvaluationScheduler {

    private static final Logger log = LoggerFactory.getLogger(RoomEvaluationScheduler.class);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final int PAGE_SIZE = 200;

    private final RoomRepository rooms;
    private final RoomService roomService;
    private final Clock clock;

    public RoomEvaluationScheduler(RoomRepository rooms, RoomService roomService, Clock clock) {
        this.rooms = rooms;
        this.roomService = roomService;
        this.clock = clock;
    }

    @Scheduled(cron = "0 10 0 1 * *", zone = "Asia/Seoul")
    public void evaluatePreviousMonth() {
        runEvaluation(targetMonth());
    }

    /**
     * Package-private hook so tests (and ad-hoc admin tools) can drive
     * the loop without going through the cron scheduler. Returns counts
     * for assertion / observability without mutating any controller
     * state.
     */
    Summary runEvaluation(YearMonth target) {
        long started = System.currentTimeMillis();
        log.info("[evaluator] start target={}", target);
        int total = 0;
        int failed = 0;

        Pageable page = PageRequest.of(0, PAGE_SIZE);
        Page<Long> roomIds;
        do {
            roomIds = rooms.findAllIdsOrderById(page);
            for (Long roomId : roomIds.getContent()) {
                total += 1;
                try {
                    roomService.evaluateRoom(roomId, target);
                } catch (RuntimeException ex) {
                    failed += 1;
                    log.error("[evaluator] room failed target={} roomId={}: {}",
                            target, roomId, ex.toString(), ex);
                }
            }
            page = roomIds.hasNext() ? roomIds.nextPageable() : null;
        } while (page != null);

        long elapsed = System.currentTimeMillis() - started;
        log.info("[evaluator] done target={} rooms={} failed={} elapsedMs={}",
                target, total, failed, elapsed);
        return new Summary(target, total, failed, elapsed);
    }

    YearMonth targetMonth() {
        return YearMonth.now(clock.withZone(KST)).minusMonths(1);
    }

    record Summary(YearMonth month, int rooms, int failed, long elapsedMs) {}
}
