# Story 7.2: Monthly Final-3 scheduled job

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As the system,
I want a scheduled job that runs at **06:30 KST on the first day of each calendar month** and generates Final-3 posters for every eligible room with at least one ACTIVE survivor from the prior month,
So that the Home tab card (Story 7.3) is ready for surviving members to view + share on the morning of the month transition.

## Acceptance Criteria

### AC0 — Existing infrastructure inventory (NO REWORK, READ ONLY)

**Given** Story 7.1 already shipped the per-room render API (PR #93, merged `455a939` 2026-06-08, sprint-status line 174 `done`)
**When** Story 7.2 starts
**Then** the dev agent treats these as **immutable inputs** — do NOT modify, do NOT re-derive:

| Artifact | Path / location | Use |
|---|---|---|
| `FinalThreeService.generatePoster(long roomId, YearMonth yearMonth)` | `BE/src/main/java/com/yeosal/api/ceremony/FinalThreeService.java:93` | The per-room entry point this story's job calls. `@Transactional`, idempotent via `posterRepository.findById` short-circuit, returns `Optional<FinalThreePoster>` (empty on zero-survivor path with chat fallback published). |
| `FinalThreePosterRepository` + `FinalThreePoster` entity + `FinalThreePosterId` composite key | `BE/src/main/java/com/yeosal/api/ceremony/Final*.java` | Persistence layer. NO new repository methods needed for happy path — `findById` already wired inside `generatePoster`. |
| `final_three_posters` table (V11 step 10) | `BE/src/main/resources/db/migration/V11__survival_revival_economy.sql` | Composite PK `(room_id, year_month)`. NO new Flyway migration. |
| `RoomRepository.findAllIdsOrderById(Pageable)` | `BE/src/main/java/com/yeosal/api/room/RoomRepository.java:22-23` | Page-by-id room walk. Reference precedent — Story 7.2 uses a NEW analogous query on `SurvivalStateRepository` (see AC3) rather than this one because it filters by survival state. |
| `SurvivalStateRepository` | `BE/src/main/java/com/yeosal/api/survival/SurvivalStateRepository.java` | Existing — Story 7.2 adds a **single** new `@Query` method here (NOT a parallel repository). |
| `RealtimePublisher` | `BE/src/main/java/com/yeosal/api/realtime/RealtimePublisher.java` | The sole STOMP emit chokepoint. This story adds ONE new `publishMonthlyPosterReady(long roomId, MonthlyPosterReadyPayload payload)` method following the `publishLeadershipChange:120-122` shape. |
| `RealtimeEvent(String kind, Object payload)` | `BE/src/main/java/com/yeosal/api/realtime/RealtimeEvent.java:19` | Open record, NOT sealed. Per Story 7.1 trap #12 + architecture deviation #4: do NOT add a sealed variant. Use a typed `MonthlyPosterReadyPayload` record sent directly on a dedicated topic. |
| `@EnableAsync` + `@EnableScheduling` | `BE/src/main/java/com/yeosal/api/YeosalApiApplication.java:11-12` | Already enabled (Story 1.2 + Story 6.1). No application-config edit. |
| `PreviewCardRenderExecutorConfig` precedent | `BE/src/main/java/com/yeosal/api/kakaoshare/PreviewCardRenderExecutorConfig.java` | The dedicated `TaskExecutor` pattern this story mirrors for the ceremony executor. Same `@Bean(name=…)` + `ThreadPoolTaskExecutor` shape. |
| `SurvivalStateEvaluatorJob` precedent | `BE/src/main/java/com/yeosal/api/survival/SurvivalStateEvaluatorJob.java` | The canonical `@Scheduled` pattern: KST `zone`, `PAGE_SIZE=200`, per-room try/catch, package-private `runEvaluation(...)` hook for tests, `Summary` record with metrics. Story 7.2 follows this shape structurally. |
| `RoomEvaluationScheduler` precedent | `BE/src/main/java/com/yeosal/api/room/RoomEvaluationScheduler.java` | Monthly-cron precedent (`cron = "0 10 0 1 * *"`, `zone = "Asia/Seoul"`, `targetMonth()` via `YearMonth.now(clock.withZone(KST)).minusMonths(1)`). Story 7.2's cron differs (06:30 vs 00:10) but structure is identical. |
| `ChatService.publishMonthlyNoSurvivorsSystemMessage` | `BE/src/main/java/com/yeosal/api/room/chat/ChatService.java:218-226` | Story 7.1's locked-body REQUIRES_NEW chat publisher. Story 7.2 NEVER calls this directly — `FinalThreeService.generatePoster` is the sole call site. Story 7.2's job pre-filters rooms with ≥1 ACTIVE survivor so the zero-survivor branch is unreachable in steady state. |
| `application.yml` `yeosal.share.posters-dir` + nginx `/posters/` static route | `BE/src/main/resources/application.yml:33` + `infra/nginx/default.conf` | Story 7.1 already shipped both. No new config / nginx edit. |

**Anti-pattern (DO NOT IMPLEMENT):**

- Add new repository methods on `FinalThreePosterRepository` — `existsById` and `findById` are already inside `generatePoster`. The job NEVER reads/writes the table directly.
- Create a new `RoomEligibilityQuery` class — eligibility is a one-line `EXISTS`-style aggregate, lives as a single `@Query` on `SurvivalStateRepository`. See AC3.
- Re-enable `@EnableScheduling` / `@EnableAsync` — ALREADY on `YeosalApiApplication`. A second annotation is a no-op but signals confusion.
- Touch `FinalThreeService.generatePoster` to add pre-filter logic — pre-filtering is the job's responsibility (separation of concerns; service stays per-room).
- Add a new Flyway migration — schema is sufficient (FR-8.7.6 immutability + V11 (10) PK provides the entire idempotency contract).

### AC1 — `FinalThreeJob` `@Scheduled` cron at 06:30 KST on day-1 of each month (LOCKED CRON)

**Given** PRD FR-8.7.1 + Epic AC1 line 938-940 lock the trigger
**When** the dev agent implements the scheduler
**Then** `FinalThreeJob.runMonthlyBatch()` is annotated:

```java
@Scheduled(cron = "0 30 6 1 * *", zone = "Asia/Seoul")
public void runMonthlyBatch() {
    runBatch(targetMonth());
}
```

- **Cron expression** `"0 30 6 1 * *"` reads as: second=0, minute=30, hour=06, day-of-month=1, month=*, day-of-week=*. Verified against Spring's `CronExpression` grammar.
- **Zone** `"Asia/Seoul"` matches all sibling schedulers (SurvivalStateEvaluatorJob, RoomEvaluationScheduler, SpectatorDigestScheduler, NotificationScheduler). project-context line 92 day-boundary rule.
- **Target month** is the **prior** calendar month: `YearMonth.now(clock.withZone(KST)).minusMonths(1)` — structurally identical to `RoomEvaluationScheduler.targetMonth():87-89`. The June 1 06:30 KST run posts May's Final-3.
- **Package-private hook** `runBatch(YearMonth target)` returns a `Summary` record (see AC5) so tests drive the loop without going through the cron scheduler. Same shape as `SurvivalStateEvaluatorJob.runEvaluation(LocalDate):74`.

**Anti-pattern:**

- Use `fixedRate` or `fixedDelay` — fixed-interval cannot honor day-of-month boundaries.
- Cron `"0 30 6 * * 0"` (first Sunday) — wrong: epic locks "the 1st day of each calendar month", not "the first Sunday".
- Cron `"30 6 1 * *"` (no leading second field) — Spring 6's `CronExpression` requires 6 fields (seconds present).
- UTC `zone` — wrong by ~9h. KST is the project lock.
- Skip the package-private `runBatch(YearMonth)` overload — tests would need reflection / PowerMock to drive `runMonthlyBatch()`; AC10 requires deterministic test entry without reflection.

PRD: FR-8.7.1. Epic: line 938-940.

### AC2 — Dedicated `MonthlyPosterRenderExecutorConfig` `TaskExecutor` (NFR-9.1.4 budget enforcement)

**Given** NFR-9.1.4 requires batch completion within **10 minutes for up to 5,000 active rooms**
**When** the dev agent sizes the executor
**Then** a new `@Configuration` class declares a dedicated `TaskExecutor` bean:

**Math:**

- Per Story 7.1 trap #10: per-room generation ≈ **250-450ms p50, 600-900ms p99** (warm Batik + DB insert).
- Single-thread loop: 5,000 × 0.5s = **42 minutes** → exceeds NFR-9.1.4.
- 8 worker threads (parallel rooms): 5,000 / 8 × 0.5s = **5.2 minutes** → comfortably under 10-min budget.
- DB pressure check: Postgres `final_three_posters` PK insert + Story 7.1's `pg_advisory_xact_lock(hashtextextended(key,0))` per-room serialization means 8 concurrent inserters cannot collide on the same `(roomId, yearMonth)` row.

**Implementation shape** (mirrors `PreviewCardRenderExecutorConfig` structurally):

```java
package com.yeosal.api.ceremony;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class MonthlyPosterRenderExecutorConfig {

    public static final String EXECUTOR_BEAN_NAME = "monthlyPosterRenderExecutor";

    @Bean(name = EXECUTOR_BEAN_NAME)
    public TaskExecutor monthlyPosterRenderExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(256);
        executor.setThreadNamePrefix("monthly-poster-");
        executor.initialize();
        return executor;
    }
}
```

- **Sizing rationale:** 8 threads = comfortable headroom for 5K-room scale + memory-cheap (Batik PNGTranscoder ≈ 5MB heap per concurrent transcode = 40MB peak, well under JVM heap).
- **Queue 256** absorbs short-term back-pressure when the iterator runs faster than transcoders without unbounded memory growth (5,000 rooms / 8 threads ≈ 625 queued max at burst, so the iterator naturally throttles via the page-walk loop pacing).
- **`ThreadNamePrefix`** uses `monthly-poster-` for log grep + thread-dump readability (consistent with `preview-card-render-`).

**Anti-pattern:**

- Inject Spring's default `TaskExecutor` — would share capacity with `previewCardRenderExecutor` and any future `@Async` users. Dedicated pool isolates ceremony-job pressure.
- Wire ceremony to `previewCardRenderExecutor` ("reuse, KISS") — wrong: pool sizing differs (2 vs 8), purpose differs (idle re-render vs once-per-month burst), and a misbehaving ceremony render could starve preview cards (and vice versa).
- Use `Executors.newFixedThreadPool(8)` directly — bypasses Spring's `ApplicationContext` lifecycle; pool would not shut down cleanly on app stop.
- Set `corePoolSize < maxPoolSize` ("scale up under load") — incorrect for once-per-month batch; predictable fixed-size matches the synchronous burst pattern.
- Add `@Async` to the per-room loop body — wrong: this story uses **explicit `CompletableFuture.runAsync(..., executor)` + drain** for deterministic latency measurement and per-task error attribution. `@Async` swallows futures unless return type is `CompletableFuture`, which complicates metrics aggregation.

PRD: NFR-9.1.4. Architecture: §3.3 "BE scheduled jobs" line 157 (KISS, no ShedLock).

### AC3 — `SurvivalStateRepository.findRoomIdsWithAtLeastOneActive(Pageable)` eligibility query (NEW, SINGLE METHOD ADDITION)

**Given** epic AC1 line 940 specifies "for every room with ≥1 active surviving member from the prior month"
**Given** Story 7.1 `FinalThreeService` javadoc lines 83-88 explicitly delegate pre-filtering to Story 7.2
**When** the dev agent adds the eligibility query
**Then** **exactly one** new method is appended to `SurvivalStateRepository`:

```java
/**
 * Story 7.2 — paged scan of rooms with at least one ACTIVE survival_state
 * row. Used by {@link com.yeosal.api.ceremony.FinalThreeJob} to filter
 * eligible rooms before invoking
 * {@link com.yeosal.api.ceremony.FinalThreeService#generatePoster}.
 *
 * <p>Pre-filtering at SQL level (rather than per-room post-filter) is
 * required so {@code FinalThreeService.generatePoster}'s zero-survivor
 * chat fallback is unreachable in steady-state (the fallback is NOT
 * idempotent on replay — see Story 7.1 deferred-work entry #1).
 *
 * <p>Returns distinct room IDs in ascending order so a job restart picks
 * up at a deterministic page boundary. Matches the
 * {@link com.yeosal.api.room.RoomRepository#findAllIdsOrderById} contract.
 */
@Query("""
        select distinct s.room.id
        from SurvivalState s
        where s.status = com.yeosal.api.survival.SurvivalStatus.ACTIVE
        order by s.room.id
        """)
Page<Long> findRoomIdsWithAtLeastOneActive(Pageable pageable);
```

**Semantics:**

- **`distinct`** is critical — a room with N ACTIVE survivors would otherwise emit N rows. The PAGE_SIZE budget assumes one row per room.
- **`ACTIVE` status only** — YELLOW/RED/SPECTATOR are excluded per FR-8.7.1 ("active surviving member"). Matches `FinalThreeService.querySurvivors:166` predicate exactly.
- **`s.room.id`** (JPQL path navigation) — relies on the existing `@ManyToOne Room room` association on `SurvivalState`. The query is whole-room-scoped, NOT user-scoped, so no `user` join is needed (saves a join per row).
- **Status comparison via fully-qualified enum constant** (`com.yeosal.api.survival.SurvivalStatus.ACTIVE`) — Hibernate 6 JPQL accepts this form for `@Enumerated(EnumType.STRING)` fields. If the dev agent finds a project-wide precedent using the literal string form (e.g., `s.status = 'ACTIVE'`), prefer matching that precedent — verify by grepping `survival/SurvivalStateRepository.java` first.
- **Order `s.room.id` ASC** — deterministic across reruns + matches `findAllIdsOrderById` precedent.

**Anti-pattern:**

- Skip `distinct` — the result page count would lie (PAGE_SIZE 200 might return only 50 distinct rooms).
- Add the query on a parallel `RoomEligibilityRepository` interface — Spring scans many repositories already; a single method on the existing `SurvivalStateRepository` is the smallest possible diff and matches the entity ownership.
- Add a second filter `WHERE survival_state.last_state_change_at >= :monthStart` — wrong semantics. FR-8.7.1 says "active surviving member" at job time, not "transitioned this month". A member who was ACTIVE all of May and is still ACTIVE at 06:30 KST June 1 is a Final-3 candidate.
- Return `List<Long>` instead of `Page<Long>` — at 5K-room scale a single fetch of all room IDs is ~40KB and harmless, but page-by-id is the established pattern (`findAllIdsOrderById`) and survives growth.
- Fetch-join `s.room` — wasteful, only the id is needed.

PRD: FR-8.7.1. Architecture: §6.3 V11 step 3 (`survival_state` table). project-context: line 89 (`@Transactional` boundary for lazy associations — repository query is fine).

### AC4 — `MonthlyPosterReadyPayload` record + `RealtimePublisher.publishMonthlyPosterReady` (NEW PUBLISH SURFACE)

**Given** epic line 950 specifies "the job emits `RealtimeEvent.MonthlyPosterReady` per room so members get a Home tab refresh signal"
**Given** Story 7.1 trap #12 + architecture deviation #4: `RealtimeEvent` is an open record, NOT sealed — reuse the existing record envelope pattern (typed payload to dedicated topic), do NOT add a sealed variant
**When** the dev agent wires the realtime emit
**Then** TWO additions land:

**(a) New `MonthlyPosterReadyPayload` record** under `ceremony/`:

```java
package com.yeosal.api.ceremony;

import java.time.YearMonth;

/**
 * Story 7.2 — realtime payload signaling a Home tab refresh for the
 * newly-generated Final-3 poster. The FE (Story 7.3) consumes this
 * frame on {@code /topic/rooms.{roomId}.posters} and refetches
 * {@code GET /rooms/{id}/posters/{yearMonth}} (Story 7.1 endpoint) to
 * obtain the SVG + PNG URL.
 *
 * <p>Payload deliberately omits the SVG body — pushing kilobytes of
 * SVG over STOMP for 5K rooms × N subscribers would saturate the
 * broker. The frame is a lightweight "go fetch" signal.
 */
public record MonthlyPosterReadyPayload(long roomId, String yearMonth) {
    public MonthlyPosterReadyPayload {
        if (yearMonth == null) {
            throw new IllegalArgumentException("yearMonth is required");
        }
    }

    public static MonthlyPosterReadyPayload of(long roomId, YearMonth yearMonth) {
        return new MonthlyPosterReadyPayload(roomId, yearMonth.toString());
    }
}
```

**(b) New method on `RealtimePublisher`** (single method append, mirrors `publishLeadershipChange:120-122`):

```java
/**
 * Story 7.2 — Final-3 monthly poster ready publish point. Emits the
 * {@code MonthlyPosterReady} frame on the room's posters topic so any
 * authenticated room member (Story 7.3's Home tab subscriber) gets a
 * refresh signal as soon as the batch job inserts the poster row.
 * Failures are warn-and-swallowed via {@link #sendTopic} — a broker
 * hiccup must NEVER roll back the surrounding poster-generation
 * transaction.
 *
 * <p>Destination {@code /topic/rooms.{roomId}.posters} follows the
 * existing dot-separated convention ({@code .chat}, {@code .members},
 * {@code .survival}, {@code .points}, {@code .kudos}).
 */
public void publishMonthlyPosterReady(long roomId, MonthlyPosterReadyPayload payload) {
    sendTopic("/topic/rooms." + roomId + ".posters", payload);
}
```

**Topic destination lock:** `/topic/rooms.{roomId}.posters`

- Matches the existing dot-separated naming convention (see `RealtimePublisher` javadoc lines 19-33).
- Distinct from `.survival` to avoid mixing privacy semantics (survival has private-queue duality; posters are room-scoped public-to-members).
- Story 7.3's FE subscriber name is locked to this exact string.

**Call-site invariant** (in `FinalThreeJob`, see AC5):

```java
Optional<FinalThreePoster> poster = finalThreeService.generatePoster(roomId, target);
if (poster.isPresent() && !preExisted) {
    realtimePublisher.publishMonthlyPosterReady(
            roomId, MonthlyPosterReadyPayload.of(roomId, target));
}
// Note: Optional.empty() = zero-survivor path; do NOT publish.
//       preExisted = true = idempotent rerun; do NOT re-publish.
//       In steady-state the pre-filter (AC3) makes the empty branch unreachable.
```

**Anti-pattern:**

- Add a `RealtimeEvent("MONTHLY_POSTER_READY", ...)` kind string and publish via `publishUserEvent(...)` — wrong: this is room-scoped fan-out, not per-user. Use `sendTopic` like `publishKudos` / `publishPointPoolChange`.
- Include the SVG body in the payload — would push ~5-50KB per subscriber. The endpoint exists for fetching; FE re-fetches on the signal.
- Add `MonthlyPosterReadyPayload` under `realtime/` — wrong package; payloads live alongside their owning domain (cf. `KudosSentPayload` in `chat/`, `LeadershipChangePayload` in `room/`, `PointPoolChangePayload` in `revival/`).
- Make `RealtimeEvent` sealed in this story — out-of-scope refactor of cross-cutting code; per Story 7.1 architecture-deviation log #4, the open record stays open. Future-story scope.
- Publish on Optional.empty() (zero-survivor) — would signal a non-existent poster; the FE would 404 on refetch.
- Publish on idempotent rerun (poster pre-existed) — would re-fire to FE subscribers who already received the original signal. AC8 idempotency contract.
- Use a `Map<String,Object>` payload — typed record is the project convention (`KudosSentPayload`, `LeadershipChangePayload`, `PointPoolChangePayload` all use records).

PRD: FR-8.7.* + NFR-9.1.3 (broker latency < 500ms p95). Architecture: §6.1 line 598-599 (MonthlyPosterReady listed) + §5.1 (RealtimePublisher chokepoint).

### AC5 — `FinalThreeJob` orchestration: paged eligible-rooms scan + parallel `executor.submit` + result drain

**Given** the batch must scale to 5,000 rooms within 10 minutes (NFR-9.1.4)
**Given** per-room evaluation must NEVER abort the whole batch (epic AC2 idempotency + sibling scheduler precedent)
**When** the dev agent implements the orchestration
**Then** `FinalThreeJob.runBatch(YearMonth target)` follows this exact shape:

```java
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
        List<CompletableFuture<Void>> inflight = new ArrayList<>();
        do {
            roomIds = survivalStates.findRoomIdsWithAtLeastOneActive(page);
            for (Long roomId : roomIds.getContent()) {
                eligible.incrementAndGet();
                CompletableFuture<Void> task = CompletableFuture.runAsync(() -> {
                    try {
                        processRoom(roomId, target, generated, skipped, zeroSurvivor);
                    } catch (RuntimeException ex) {
                        failed.incrementAndGet();
                        log.error("[ceremony][month-job] room failed target={} roomId={}: {}",
                                target, roomId, ex.toString(), ex);
                    }
                }, executor);
                inflight.add(task);
            }
            page = roomIds.hasNext() ? roomIds.nextPageable() : null;
        } while (page != null);

        // Drain — block until every submitted room finishes (success or fail).
        CompletableFuture.allOf(inflight.toArray(CompletableFuture[]::new)).join();

        long elapsed = System.currentTimeMillis() - started;
        log.info("[ceremony][month-job] done target={} eligible={} generated={} "
                        + "skipped={} zeroSurvivor={} failed={} elapsedMs={}",
                target, eligible.get(), generated.get(), skipped.get(),
                zeroSurvivor.get(), failed.get(), elapsed);

        if (elapsed > BUDGET_MS) {
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
        boolean preExisted = finalThreeService.existsPoster(roomId, target);
        Optional<FinalThreePoster> result = finalThreeService.generatePoster(roomId, target);
        if (result.isEmpty()) {
            zeroSurvivor.incrementAndGet();
            return;
        }
        if (preExisted) {
            skipped.incrementAndGet();
            return;
        }
        generated.incrementAndGet();
        realtimePublisher.publishMonthlyPosterReady(
                roomId, MonthlyPosterReadyPayload.of(roomId, target));
    }

    YearMonth targetMonth() {
        return YearMonth.now(clock.withZone(KST)).minusMonths(1);
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
```

**Key invariants:**

- **Per-room try/catch** wraps `processRoom` so a single bad row never aborts the batch (sibling-scheduler precedent: `SurvivalStateEvaluatorJob:93-106`, `RoomEvaluationScheduler:70-77`).
- **`CompletableFuture.runAsync(..., executor)`** explicitly binds to `monthlyPosterRenderExecutor` (NOT `ForkJoinPool.commonPool()` — common pool is too small + shared with parallel streams elsewhere).
- **`AtomicInteger` counters** are thread-safe under the parallel executor. `Summary` is built only after the `allOf().join()` drain so reads see fully-stable values.
- **`processRoom` is `private`** — single chokepoint for the three outcomes (zero-survivor → return, pre-existed → skipped, fresh → generated + publish). All three branches mutate exactly one counter.
- **Budget alarm at 10min** logs `ERROR` with `[ceremony][month-job][budget-exceeded]` prefix — mirrors `SurvivalStateEvaluatorJob:117-120` mass-elimination alert convention. Ops alert rule subscribes to the substring; Sentry hook (when wired) captures.
- **`existsPoster` pre-check** is a thin method on `FinalThreeService` (AC6) that calls `posterRepository.existsById(new FinalThreePosterId(roomId, yearMonth.toString()))`. Story 7.1 already wires the same `existsById`-equivalent check inside `generatePoster` to short-circuit; the job-side check is the parity needed to skip the realtime publish on idempotent rerun.

**Anti-pattern:**

- Wrap the per-room call in `@Async` on `FinalThreeService.generatePoster` — wrong: that would change the service contract for every caller (Story 7.3's read path uses `getPosterForMember` not `generatePoster`, but ad-hoc admin tools may call `generatePoster` synchronously). Job-side `CompletableFuture.runAsync` keeps the async concern in the orchestrator.
- Skip the `inflight.add(task)` + `allOf().join()` drain — would let the cron method return before workers finish; budget-exceeded alarm + Summary metrics would lie.
- Catch + swallow inside `processRoom` — wrong: would hide failures from the `failed` counter. Catch is in the future-body lambda, not `processRoom`.
- Use `parallelStream().forEach` — bypasses the dedicated executor (uses common pool); difficult to set thread-name prefix; harder to drain deterministically.
- Re-emit `publishMonthlyPosterReady` on the idempotent existing-row branch ("FE might have missed the original") — wrong: FE re-fetches on app-foreground (TanStack Query) and on tab focus; broadcasting on every cron rerun is noise. Idempotent rerun = silent.
- Publish on the zero-survivor branch — `Optional.empty()` means no poster exists; FE refetch would 404.
- Inject the `RealtimePublisher` into `FinalThreeService` and emit from there — wrong: violates Story 7.1's deliberate decoupling (trap #12). Service is publish-agnostic; job owns fan-out.

PRD: FR-8.7.1, FR-8.7.6, NFR-9.1.4. Architecture: §3.3 line 157, §5.3 (concurrency patterns). project-context: line 117-120 (constructor injection only).

### AC6 — `FinalThreeService.existsPoster(long roomId, YearMonth yearMonth)` thin wrapper (SURGICAL EXTENSION)

**Given** AC5's `processRoom` needs to know whether a poster pre-existed (to gate the realtime publish)
**Given** Story 7.1 did NOT expose `existsById` — it's an internal short-circuit inside `generatePoster`
**When** the dev agent adds the pre-check
**Then** ONE new public read-only method appears on `FinalThreeService`:

```java
/**
 * Story 7.2 — pre-existence check used by {@link FinalThreeJob} to
 * decide whether the realtime publish should fire. {@code true} =
 * poster row already exists (idempotent rerun, no publish). {@code
 * false} = first generation (publish after generate). Read-only;
 * does NOT acquire the advisory lock.
 */
@Transactional(readOnly = true)
public boolean existsPoster(long roomId, YearMonth yearMonth) {
    return posterRepository.existsById(
            new FinalThreePosterId(roomId, yearMonth.toString()));
}
```

**Why this is acceptable as a Story 7.1 surface extension:**

- Method is **net-additive** — does not modify existing `generatePoster` / `getPosterForMember` signatures.
- Uses the **same repository** — no new `Repository` method.
- `readOnly = true` matches the pattern used by `getPosterForMember:140`.
- The alternative (job-side direct `posterRepository.existsById` call) would force the job to depend on the repository AND the service, doubling DI complexity. Service ownership of the table reads is cleaner.

**Anti-pattern:**

- Make `processRoom` call `generatePoster` first and infer "pre-existed" from the `Optional` shape — wrong: `Optional.present()` includes both "we just generated it" and "it was already there". The two states are indistinguishable in the return type alone.
- Add a `boolean wasNewlyCreated` field to `FinalThreePoster` entity — wrong: pollutes the persistence model with transient orchestration metadata.
- Refactor `generatePoster` to return a richer `GenerationResult { ENUM kind, FinalThreePoster poster }` — breaking change to Story 7.1's contract; out of scope.
- Add a `@PostLoad` hook to flip a transient field — fragile, depends on Hibernate's first-level cache behavior.

PRD: FR-8.7.6 (immutability). Architecture: §4.9 (`posters` table PK).

### AC7 — Boot-time scheduler registration verification (regression guard)

**Given** Spring's `@EnableScheduling` is on `YeosalApiApplication` and `@Scheduled` is auto-discovered from `@Component`-annotated classes
**Given** project-context line 87 forbids `@Autowired` field injection (constructor-only) — wrong injection would silently no-op the scheduler
**When** the dev agent declares the story complete
**Then** boot-time verification confirms the scheduler is registered:

- Application boots without `UnsatisfiedDependencyException` or `NoSuchBeanDefinitionException`.
- `ScheduledTaskHolder` reflection (in the IT — AC10) asserts that **a scheduled task exists with cron `"0 30 6 1 * *"` and zone `"Asia/Seoul"`**.
- Log line `[ceremony][month-job] start target=...` appears when `runBatch(YearMonth)` is invoked directly from the IT.

**Anti-pattern:**

- Skip the `ScheduledTaskHolder` assertion ("compile passed so registration will too") — Spring silently skips `@Scheduled` on a non-`@Component` class; the IT guard catches that class of error.
- Hard-code the cron string in the test as a magic literal — assert via `Trigger.nextExecution()` returning a non-null future Instant per the actual `CronExpression`, AND assert the literal string for explicit guard against typos. Both assertions together catch both classes of bug.
- Mark `FinalThreeJob` as `@Component @ConditionalOnProperty(...)` — wrong: there is no feature flag; the job is always enabled in v1 (architecture §3.3 KISS).

PRD: NFR-9.2.1 (idempotency). Architecture: §3.3.

### AC8 — Idempotency contract (FR-8.7.6) under retry / replay

**Given** PRD FR-8.7.6 locks posters as immutable
**Given** the job may be triggered twice (manual rerun, scheduler glitch on restart, future multi-instance fan-out — currently single-instance per architecture §3.3 line 157)
**When** the second run encounters an existing `final_three_posters` row
**Then** the following invariants hold:

1. **`generatePoster` short-circuits** via `posterRepository.findById` (Story 7.1 wiring at lines 95-104). No re-render. No PNG re-transcode. No DB write.
2. **`processRoom` increments `skipped` counter**, NOT `generated`.
3. **`publishMonthlyPosterReady` does NOT fire** on the idempotent path (`if (preExisted) return;` guard before the publish call).
4. **Zero-survivor chat fallback does NOT re-fire** — the pre-filter (AC3 query) excludes zero-survivor rooms from the iteration entirely.

**Edge case — room loses last ACTIVE survivor between pre-filter and `generatePoster`:** Pre-filter sees a room with ≥1 ACTIVE at scan time; worker thread executes `generatePoster` 30s later; another transaction has flipped the last survivor to RED (e.g., the 06:00 KST evaluator demotion); `querySurvivors` returns empty; `generatePoster` falls into the zero-survivor chat path. On retry of the SAME batch, the pre-filter would now exclude this room, so the chat message does NOT re-publish. The deferred-work entry from Story 7.1 (line 28) explicitly notes this is the intentional boundary.

**Anti-pattern:**

- Pre-existence-check using `findById` and discard the entity — wasteful row fetch when `existsById` would do.
- Add a `@SchedulerLock` (ShedLock) without adding the dependency + DB tables — would silently no-op. ShedLock is out-of-scope per architecture §3.3 line 157 ("v1 single-instance Compose deploy doesn't need it yet").
- Manual lock-row insertion to gate parallel runs — Postgres advisory lock already serializes per `(roomId, yearMonth)` inside `generatePoster:201-206`.
- Decline to emit the warning log on second-fire-within-month ("idempotent so silent OK") — the `[ceremony][month-job] done … skipped=N` summary IS the audit trail; ops can spot abnormal `skipped > 0` on fresh-month runs.

PRD: FR-8.7.6 + NFR-9.2.1. Architecture: §3.3 line 157, §5.3 (Postgres advisory locks).

### AC9 — File / scope fence (LOCKED ALLOW LIST)

**Given** the story's scope must be auditable in PR review
**When** the dev agent finishes
**Then** the diff touches **exactly** these files (no more, no less):

**NEW files (3 BE source + 5 BE test):**

```
BE/src/main/java/com/yeosal/api/ceremony/FinalThreeJob.java
BE/src/main/java/com/yeosal/api/ceremony/MonthlyPosterRenderExecutorConfig.java
BE/src/main/java/com/yeosal/api/ceremony/MonthlyPosterReadyPayload.java
BE/src/test/java/com/yeosal/api/ceremony/FinalThreeJobTest.java
BE/src/test/java/com/yeosal/api/ceremony/MonthlyPosterReadyPayloadTest.java
BE/src/test/java/com/yeosal/api/ceremony/FinalThreeJobIT.java
BE/src/test/java/com/yeosal/api/ceremony/FinalThreeJobSchedulerRegistrationIT.java
BE/src/test/java/com/yeosal/api/realtime/RealtimePublisherMonthlyPosterTest.java
```

**MODIFIED files (existing — surgical edits only):**

```
BE/src/main/java/com/yeosal/api/ceremony/FinalThreeService.java
  └── ADD ONE method `existsPoster(long roomId, YearMonth yearMonth)` (AC6).
       NO edits to existing methods. NO new fields. NO constructor param changes.

BE/src/main/java/com/yeosal/api/realtime/RealtimePublisher.java
  └── ADD ONE method `publishMonthlyPosterReady(long roomId, MonthlyPosterReadyPayload payload)`
       at the bottom of the public-method block (AC4).
       NO edits to existing emit methods.

BE/src/main/java/com/yeosal/api/survival/SurvivalStateRepository.java
  └── ADD ONE method `findRoomIdsWithAtLeastOneActive(Pageable)` with @Query (AC3).
       NO edits to existing query methods.
```

**BANNED PATHS (red lines — dev agent MUST NOT edit these):**

```
BE/build.gradle                            ← no new dependency, no test config change
BE/src/main/resources/db/migration/V*.sql  ← FR-8.7.6 PK already provides idempotency, NO new migration
BE/src/main/resources/application.yml      ← cron is hard-coded; no new yeosal.* keys
BE/src/main/resources/application-prod.yml ← same
BE/src/main/java/com/yeosal/api/YeosalApiApplication.java ← @EnableScheduling + @EnableAsync already present
BE/src/main/java/com/yeosal/api/realtime/RealtimeEvent.java ← stays an open record (Story 7.1 AC12 dev #4 + AC4 anti-pattern lock)
BE/src/main/java/com/yeosal/api/ceremony/FinalThreePoster.java ← no schema/field changes
BE/src/main/java/com/yeosal/api/ceremony/FinalThreePosterRepository.java ← no new methods (existsById suffices via service wrapper)
BE/src/main/java/com/yeosal/api/ceremony/FinalThreePosterId.java ← no field changes
BE/src/main/java/com/yeosal/api/ceremony/SvgRenderer.java ← Story 7.1 closed surface
BE/src/main/java/com/yeosal/api/ceremony/SurvivorTenureRow.java ← Story 7.1 closed surface
BE/src/main/java/com/yeosal/api/ceremony/PosterController.java ← Story 7.1 read endpoint untouched
BE/src/main/java/com/yeosal/api/ceremony/PosterDto.java ← Story 7.1 DTO untouched
BE/src/main/java/com/yeosal/api/ceremony/PosterNotFoundException.java ← Story 7.1 exception untouched
BE/src/main/java/com/yeosal/api/room/chat/ChatService.java ← publishMonthlyNoSurvivorsSystemMessage stays as-is (NOT called from this story's job)
BE/src/main/java/com/yeosal/api/kakaoshare/* ← reuse only (transitive via FinalThreeService -> PngRasterizer)
BE/src/main/java/com/yeosal/api/common/SecurityConfig.java ← no endpoint added
BE/src/main/java/com/yeosal/api/common/ApiExceptionHandler.java ← no new exception mapping (job swallows errors internally)
infra/docker-compose.yml, infra/nginx/default.conf ← Story 7.1 paths sufficient
FE/**                                       ← Story 7.3's scope, NO FE source/test in this story
docs/**, RUNBOOK.md                         ← Story 7.3 / Epic 7 retro follow-up
.github/workflows/*                         ← deferred-work entry 2026-06-08 (timeout-minutes:30) is a separate chore(ci) PR
```

**Diff sanity check (run before sprint-status flip):**

```bash
git diff --name-only main | sort | tee /tmp/story7-2-files.txt
# Expect exactly the union of NEW + MODIFIED above (11 files total).
# No file in BANNED PATHS list.
```

**Anti-pattern (DO NOT IMPLEMENT):**

- "While I'm in there" cleanup of Story 7.1's `FinalThreeService.writePngAtomically` — out of scope. File a follow-up.
- Add a `docs/ceremony.md` — Story 7.3 + Epic 7 retro own the documentation.
- Bump `.github/workflows/be-it-boot-smoke.yml timeout-minutes` from 30 → 60 — Story 7.1 deferred-work entry (2026-06-08) explicitly notes this is a separate `chore(ci):` PR. Bundling it here breaks the scope fence and slows review.
- Touch `FE/src/lib/realtime/topics.ts` or `FE/src/api/posters.ts` to wire the new `.posters` topic — Story 7.3 scope.
- Add `yeosal.ceremony.cron` to application.yml ("for ops flexibility") — KISS. Cron is locked by PRD; if ops needs to disable, a chore PR can bump the cron to a never-fire pattern.

PRD / Architecture: comprehensive scope fence documented across AC1–AC8.

### AC10 — Test matrix (NET-ADDITIVE, RED → GREEN order)

**Given** TDD is enforced (project-context line 145, common/testing.md ratio target 80%+)
**When** Story 7.2 ships
**Then** the test suite adds **net-additive** tests — no existing test is removed or weakened. RED → GREEN order documented per file:

| File | Cases | Type | Notes |
|---|---|---|---|
| `FinalThreeJobTest.java` | 12 | Unit (Mockito) | (1) Empty eligible-rooms → no service call, no publish, summary all-zero. (2) Single eligible room, poster newly generated → service called once, publish called once with exact payload `(roomId, target.toString())`, summary `eligible=1 generated=1 skipped=0 zeroSurvivor=0 failed=0`. (3) Single eligible room, poster already existed → service called once, publish NOT called, summary `skipped=1 generated=0`. (4) Single eligible room, `generatePoster` returns `Optional.empty()` (zero-survivor race) → publish NOT called, summary `zeroSurvivor=1`. (5) One room throws RuntimeException → next room still processes, summary `failed=1`, ERROR log emitted. (6) 250 rooms across 2 pages → both pages iterated, page-boundary contiguity verified. (7) Parallel execution observed via thread-name prefix `monthly-poster-` capture in `Thread.currentThread().getName()` from a stubbed `TaskExecutor`. (8) Budget-exceeded path: simulate elapsed > 10min via a sleep-stubbed service call OR by directly calling the budget-check branch via a test-only seam → `[ceremony][month-job][budget-exceeded]` ERROR log emitted. (9) `targetMonth()` clock test: Clock fixed at 2026-06-01T06:30:00 KST → target = `YearMonth.of(2026, 5)`. (10) `targetMonth()` cross-year: Clock fixed at 2026-01-01T06:30:00 KST → target = `YearMonth.of(2025, 12)`. (11) RealtimePublisher throws (broker failure) → swallowed by the publisher chokepoint, room still counted as `generated`. (12) Summary record field projection asserts. |
| `MonthlyPosterReadyPayloadTest.java` | 3 | Unit | (1) `of(42L, YearMonth.of(2026, 5))` → `MonthlyPosterReadyPayload(42L, "2026-05")`. (2) Constructor rejects null yearMonth with `IllegalArgumentException`. (3) JSON serialization (via project's default `ObjectMapper`) emits `{"roomId":42,"yearMonth":"2026-05"}` — guards STOMP wire-format. |
| `RealtimePublisherMonthlyPosterTest.java` | 3 | Unit (Mockito) | (1) Happy path: `publishMonthlyPosterReady(42L, payload)` → `template.convertAndSend("/topic/rooms.42.posters", payload)` exactly once. (2) Broker exception → swallowed via `sendTopic`'s warn-and-continue, no rethrow. (3) Destination string format guard: `roomId=0`, `roomId=Long.MAX_VALUE` → asserts no malformed path. |
| `FinalThreeJobIT.java` | 4 | IT (Testcontainers Postgres + real Spring context, opt-in `yeosal.boot-smoke`) | (1) Happy path: 3 rooms seeded (room A: 2 ACTIVE survivors; room B: 5 ACTIVE; room C: 0 survivors) → after `runBatch(YearMonth)`: rooms A + B have `final_three_posters` rows + PNG bytes, room C does NOT. Summary `eligible=2 generated=2 skipped=0 zeroSurvivor=0 failed=0`. (2) Idempotent rerun: same fixtures, run `runBatch` twice → second run summary `skipped=2 generated=0`; row count unchanged; STOMP fan-out fires twice on first run, zero times on second. (3) Race guard: pre-filter returns room A; between query and worker, test thread deletes survival_state rows to flip A to zero-survivor; `runBatch` completes with `zeroSurvivor=1`, chat fallback row IS inserted (Story 7.1 zero-survivor path), `final_three_posters` row NOT inserted. (4) Parallel correctness: 50 rooms × 5 ACTIVE each → all 50 posters present, no PK collision, elapsed < 30s (smoke margin against 10-min budget). |
| `FinalThreeJobSchedulerRegistrationIT.java` | 1 | IT (`@SpringBootTest`, opt-in `yeosal.boot-smoke`) | Inject `ScheduledTaskHolder`; assert exactly one task exists where the task's `Trigger` is a `CronTrigger` with `getExpression() == "0 30 6 1 * *"` AND `getTimeZone().getID() == "Asia/Seoul"`. Boot-time guard against silent registration failures. |

**Total: 23 BE tests** (12 + 3 + 3 + 4 + 1). Per AC9, **NO FE tests**.

**Coverage notes:**

- `FinalThreeJob` is exercised end-to-end by `FinalThreeJobTest` (mocked) + `FinalThreeJobIT` (real DB/broker). 100% line + branch coverage of `processRoom` + `runBatch`.
- `MonthlyPosterRenderExecutorConfig` is covered transitively by `FinalThreeJobIT.parallel-correctness` (asserts that 50 concurrent tasks succeed — requires the bean to wire).
- `MonthlyPosterReadyPayload` is exhaustively unit-covered.
- `RealtimePublisher.publishMonthlyPosterReady` gets a dedicated unit test in a NEW file under `realtime/` to keep the diff to the existing test file (if any) zero per AC9 scope fence.
- `FinalThreeService.existsPoster` is covered transitively by `FinalThreeJobIT.idempotent-rerun` (which asserts the second run's `skipped=2 generated=0` outcome, which only succeeds if `existsPoster` returns the correct boolean).
- `SurvivalStateRepository.findRoomIdsWithAtLeastOneActive` is covered transitively by `FinalThreeJobIT.happy-path` (which seeds rooms with mixed status and asserts only the ACTIVE-survivor rooms appear in eligible count).

**Why some IT are opt-in via `yeosal.boot-smoke`:**

- Project precedent (project-context line 142 + Stories 1.4 / 5.4 / 6.1 / 7.1 ITs all opt-in). Local dev `./gradlew test` does NOT run Testcontainers ITs by default; CI passes `-Dyeosal.boot-smoke=true` via `BE/build.gradle:310-318`.

**Anti-pattern (DO NOT IMPLEMENT):**

- Use `Thread.sleep` to coordinate parallel execution — use `CountDownLatch` for deterministic concurrency assertions instead.
- Assert exact log strings character-by-character — use `LoggerCapture` (Logback `ListAppender`) and assert `.contains("[ceremony][month-job]")` + `.contains("eligible=")` substring shape only.
- Run real Batik in `FinalThreeJobTest` — slow + flaky. Unit tests mock `FinalThreeService.generatePoster` to return canned `Optional<FinalThreePoster>`; IT exercises real Batik via `FinalThreeService`'s actual implementation.
- Use H2 for `FinalThreeJobIT` — project-context line 142 forbids H2. Use Testcontainers Postgres 16 (matches Story 7.1 `FinalThreeServiceIT` precedent).
- Hard-code the 10-min budget assertion to `< 30000L` in `FinalThreeJobIT.parallel-correctness` ("be strict") — use `< 30000L` as **smoke margin** (50 rooms × 8 threads × 500ms ≈ 3s expected; 30s buffer absorbs Testcontainers JVM cold start, NOT a real NFR-9.1.4 assertion). The NFR-9.1.4 assertion lives in production observability, not test wall-clock.
- Add `@MockBean(RealtimePublisher.class)` to `FinalThreeJobIT` ("simpler") — the IT exercises real publish so the STOMP destination string is verified end-to-end. Use a `SimpMessagingTemplate` recording stub via `@SpyBean` for the assertion shape.
- Skip the `FinalThreeJobSchedulerRegistrationIT` ("cron is text — what could go wrong") — Spring silently skips `@Scheduled` on non-`@Component` classes; this guard catches that class of regression cheaply.

PRD: FR-8.7.* coverage. Architecture: §4.15 (test-as-gate). project-context: line 137-159 (testing rules).

### AC11 — Verification matrix (gate before sprint-status flip)

**Given** the dev agent finishes implementation
**When** declaring story complete
**Then** the following 14 gates MUST all PASS in order before flipping sprint-status `in-progress → review`:

| # | Gate | Command | Expected |
|---|---|---|---|
| 1 | Compile | `cd BE && ./gradlew compileJava` | BUILD SUCCESSFUL |
| 2 | Generated tokens still produced | `ls BE/build/generated/sources/tokens/com/yeosal/api/theme/GeneratedTokens.java` | exists (Story 1.5 invariant; this story does not touch tokens) |
| 3 | Checkstyle hex-literal guard | `cd BE && ./gradlew checkstyleMain` | BUILD SUCCESSFUL (no hex literals introduced in any new file) |
| 4 | Ceremony unit tests | `cd BE && ./gradlew test --tests "com.yeosal.api.ceremony.FinalThreeJobTest"` `--tests "*MonthlyPosterReadyPayloadTest"` | All GREEN |
| 5 | Realtime unit test | `cd BE && ./gradlew test --tests "*RealtimePublisherMonthlyPosterTest"` | GREEN |
| 6 | Existing Story 7.1 ceremony tests still GREEN | `cd BE && ./gradlew test --tests "com.yeosal.api.ceremony.*Test"` (excludes ITs) | All previously-GREEN tests still GREEN (regression guard: this story's surgical edits to `FinalThreeService` must not break Story 7.1's `FinalThreeServiceTest`) |
| 7 | FinalThreeJobIT (Testcontainers) | `cd BE && ./gradlew test --tests "*FinalThreeJobIT" -Dyeosal.boot-smoke=true` (opt-in) | GREEN locally — required in PR-CI |
| 8 | Scheduler registration IT | `cd BE && ./gradlew test --tests "*FinalThreeJobSchedulerRegistrationIT" -Dyeosal.boot-smoke=true` (opt-in) | GREEN — asserts cron `"0 30 6 1 * *"` + zone `"Asia/Seoul"` registered |
| 9 | Full BE suite delta | `cd BE && ./gradlew test` | net-additive: BASELINE + 23 = NEW count, all GREEN |
| 10 | Hibernate ddl-auto validate (boot) | `cd BE && ./gradlew bootRun` (kill after boot log line) | Boot succeeds; `MonthlyPosterRenderExecutorConfig` bean initialized; scheduler registers without `NoUniqueBeanDefinitionException` |
| 11 | Realtime publish smoke (manual) | With BE running, invoke `runBatch(YearMonth.now().minusMonths(1))` via a one-shot test main OR ad-hoc admin invocation; subscribe a STOMP client to `/topic/rooms.{seedRoomId}.posters` | Receive `MonthlyPosterReadyPayload` frame OR — if no Docker/seed available — defer to PR-CI |
| 12 | Brand-voice lint (FE-side) | `cd FE && npx tsx tools/brand-voice-lint.ts` | 0 HARD violations (baseline; no FE changes in this story) |
| 13 | Diff sanity (scope fence) | `git diff --name-only main \| sort` | Exact union of AC9 NEW + MODIFIED (11 files); nothing in AC9 banned-paths |
| 14 | verify.sh | `bash scripts/verify.sh` | PASS — full FE + BE verification per project-context line 213 |

**Gate 7 + 8 + 10 + 11 caveat (Stories 5.4 / 6.1 / 7.1 precedent):**

- Docker-Compose / Testcontainers Postgres may not be available on dev hosts (`No usable Docker environment found`). In that case, the dev agent runs gates 1–6, 9, 12, 13, 14 locally and defers gates 7, 8, 10, 11 to PR-CI. The PR description MUST explicitly note this deferral.
- Story 7.1 PR description (PR #93) already established the exact deferral language; mirror it.

**Anti-pattern:**

- Skip Gate 6 ("Story 7.1 tests are not touched") — the surgical `FinalThreeService.existsPoster` addition (AC6) is a NEW public method. If the dev agent accidentally edits a method signature elsewhere in `FinalThreeService`, Gate 6 catches it.
- Skip Gate 8 ("Gate 10 covers boot") — Gate 10 confirms boot; Gate 8 confirms the *specific* cron task is registered with the exact expression + zone. Without it, a typo (`"0 30 6 1 * ?"` vs `"0 30 6 1 * *"`) would silently break the scheduler.
- Run Gate 9 first ("faster feedback") — Gate 1 must pass first because Gate 9 includes compilation. Order matters for first-failure attribution.

PRD: comprehensive scope. Architecture: §4.15. project-context: line 213 (verify.sh comprehensive run).

### AC12 — Architecture deviation notes (DOC FOLLOW-UP, NON-BLOCKER)

**Given** the implementation may deviate slightly from architecture text in places
**When** the story merges
**Then** an explicit log of deviations is included in the PR body so future architecture reviewers can update the doc (separate PR):

| Deviation | Architecture says | Story does | Justification |
|---|---|---|---|
| **No sealed `RealtimeEvent` variant** | §6.1 line 599 lists `RealtimeEvent.MonthlyPosterReady` as a sealed variant | Sends typed `MonthlyPosterReadyPayload` directly on `/topic/rooms.{id}.posters` via new `publishMonthlyPosterReady`; `RealtimeEvent` stays an open record | Inherits Story 7.1 architecture deviation #4. Sealing `RealtimeEvent` is a cross-cutting refactor that would also touch the existing `RealtimeEvent("FRIEND_REQUEST_RECEIVED", ...)` envelope and FE consumers. Out-of-scope for a single story. |
| **No new STOMP topic listed in architecture §2** | §2 line 113-116 lists `/topic/rooms/{id}/survival`, `/topic/rooms/{id}/points`, `/user/queue/friend-gifts` | Adds `/topic/rooms.{id}.posters` (dot-separator per existing `RealtimePublisher` convention) | Architecture diagram uses slash-separator but the existing publisher implementation uses dot-separator (chat, members, survival, points, kudos). Doc PR follow-up: align §2 diagram with implementation convention. |
| **`MonthlyPosterRenderExecutorConfig` dedicated pool, not shared with `previewCardRenderExecutor`** | §3.3 line 157 cites "Spring `@Scheduled` (already in spring-context)" + KISS, no explicit pool guidance | Dedicated 8-thread pool (mirrors `PreviewCardRenderExecutorConfig` pattern) | NFR-9.1.4 budget (10 min for 5K rooms) requires 8+ threads. Sharing with preview-card pool would cross-couple two unrelated workloads. |
| **`SurvivalStateRepository` is the eligibility-query owner, not a parallel `RoomEligibilityRepository`** | §6.1 ceremony module outline lists `FinalThreeJob` + `FinalThreeService` + entity/repository but no eligibility query class | Adds `findRoomIdsWithAtLeastOneActive` directly on `SurvivalStateRepository` (the entity owning the predicate) | Cross-module query would be the wrong package; eligibility filter is fundamentally a `survival_state` aggregate, so the query lives with that entity. |
| **No ShedLock / `@SchedulerLock` for multi-instance safety** | §3.3 line 157 + §7.2 line 847 explicitly mark ShedLock as "only needed if multi-instance deployment lands" | This story does NOT add ShedLock | Single-instance Compose deploy invariant holds. Phase-2 follow-up. |

**Doc PR follow-up tasks (NON-BLOCKER for Story 7.2):**

1. Update Architecture §6.1 ceremony module outline — list `FinalThreeJob`, `MonthlyPosterRenderExecutorConfig`, `MonthlyPosterReadyPayload` as cross-references.
2. Architecture §2 — align topic diagram with dot-separator convention.
3. Architecture §3.3 — note ceremony job's dedicated executor pool sizing rationale.

### AC13 — Sprint-status transitions

**Given** Story 7.2 is the **second** story in Epic 7 (epic-7 is already `in-progress` per sprint-status line 173)
**When** the workflow runs
**Then** status transitions are explicit:

1. **At story-creation time (THIS workflow):**
   - `7-2-monthly-final-3-scheduled-job: backlog` → `ready-for-dev`.
   - `last_updated` → `2026-06-08`.
   - Add a comment header noting the transition + reference Story 7.1's `done` state.

2. **At dev-story kickoff (next session):**
   - `7-2-monthly-final-3-scheduled-job: ready-for-dev` → `in-progress`.

3. **At implementation-complete (next session):**
   - `7-2-monthly-final-3-scheduled-job: in-progress` → `review`.

4. **At PR-merge / review-passed (later session):**
   - `7-2-monthly-final-3-scheduled-job: review` → `done`.
   - `epic-7` stays `in-progress` (7.3 remains backlog).

## Tasks / Subtasks

- [x] Verify AC0 inventory: Story 7.1 ceremony module merged + 9 source files present, `RoomRepository.findAllIdsOrderById` present, `SurvivalStateRepository` present, `RealtimePublisher` present, `@EnableScheduling` + `@EnableAsync` on `YeosalApiApplication`, V11 (10) `final_three_posters` table extant. (AC0)
- [x] Add `findRoomIdsWithAtLeastOneActive(Pageable)` to `SurvivalStateRepository.java`. RED first via assertion in `FinalThreeJobIT.happy-path` (which would fail compile without the method); GREEN by `@Query` annotation. (AC3, AC10)
- [x] Create `MonthlyPosterReadyPayload.java` record under `ceremony/`. RED first via `MonthlyPosterReadyPayloadTest` (null check + factory + JSON serialization). (AC4, AC10)
- [x] Add `publishMonthlyPosterReady` to `RealtimePublisher.java`. RED first via `RealtimePublisherMonthlyPosterTest` (destination + payload + swallow-broker-error). (AC4, AC10)
- [x] Create `MonthlyPosterRenderExecutorConfig.java` (`@Configuration` + `@Bean`-named TaskExecutor, 8 threads, queue 256). No direct unit test; coverage transitive via IT. (AC2)
- [x] Add `existsPoster(long roomId, YearMonth yearMonth)` to `FinalThreeService.java`. (AC6) Coverage transitive via `FinalThreeJobIT.idempotent-rerun`.
- [x] Create `FinalThreeJob.java`. RED first via `FinalThreeJobTest` (mocked deps); GREEN by orchestration shape from AC5. (AC1, AC5, AC8, AC10)
- [x] Create `FinalThreeJobIT.java` (Testcontainers Postgres, opt-in `yeosal.boot-smoke`, 4 cases per AC10). (AC10, AC11 gate 7)
- [x] Create `FinalThreeJobSchedulerRegistrationIT.java` (`@SpringBootTest`, opt-in, asserts cron via `ScheduledTaskHolder`). (AC7, AC10, AC11 gate 8)
- [x] Run Gate 1–14 verification matrix (AC11). Document Docker-bound deferrals in PR description per Story 7.1 precedent.
- [x] If all 14 gates GREEN locally + Docker-bound deferrals (gates 7, 8, 10, 11) are documented: flip sprint-status `in-progress → review` (AC13.3).

### Review Findings

- [x] [Review][Patch] Executor saturation can abort the 5K-room batch outside failure accounting [BE/src/main/java/com/yeosal/api/ceremony/FinalThreeJob.java:98] — `runBatch` submits every eligible room to `CompletableFuture.runAsync(..., executor)` and drains only after all pages are submitted, while `MonthlyPosterRenderExecutorConfig` uses 8 threads and queue capacity 256. At production scale, outstanding tasks can exceed active+queued capacity, `TaskRejectedException` is thrown at submission time outside the per-room try/catch, and the monthly batch can abort instead of satisfying AC2/AC5/NFR-9.1.4.
- [x] [Review][Patch] Overlapping reruns can double-count and duplicate `MonthlyPosterReady` publishes [BE/src/main/java/com/yeosal/api/ceremony/FinalThreeJob.java:134] — `preExisted` is checked before `FinalThreeService.generatePoster` acquires the advisory lock. If two job invocations overlap, both can observe `false`; the first inserts, the second returns the now-existing row after the service lock re-check, then still follows the fresh path because `preExisted` is stale. This violates AC8's retry/replay idempotency intent.
- [x] [Review][Patch] `.posters` topic is published but cannot be subscribed to [BE/src/main/java/com/yeosal/api/realtime/JwtChannelInterceptor.java:43] — `RealtimePublisher.publishMonthlyPosterReady` emits `/topic/rooms.{id}.posters`, and AC4 locks Story 7.3's subscriber to that exact destination, but `JwtChannelInterceptor.ROOM_TOPIC` only permits `(chat|members|survival|points|kudos)`. Authenticated room members will be denied at SUBSCRIBE time.
- [x] [Review][Patch] Scheduler registration IT boots without a test datasource [BE/src/test/java/com/yeosal/api/ceremony/FinalThreeJobSchedulerRegistrationIT.java:33] — the opt-in `@SpringBootTest` has no Testcontainers datasource override, so AC7/AC11 gate 8 can fail during Flyway/Postgres startup before it reaches the cron assertion.
- [x] [Review][Patch] Race-guard IT does not exercise the between-query-and-worker race [BE/src/test/java/com/yeosal/api/ceremony/FinalThreeJobIT.java:144] — AC10 asks for a test where the pre-filter returns a room and survivors disappear before worker execution, expecting `zeroSurvivor=1` and a chat fallback row. The current test deletes `survival_state` before `runBatch`, so the room is excluded by the pre-filter and asserts `eligible=0` / `zeroSurvivor=0`.
- [x] [Review][Patch] Broker-failure unit test contradicts the publisher swallow invariant [BE/src/test/java/com/yeosal/api/ceremony/FinalThreeJobTest.java:294] — the test makes mocked `RealtimePublisher.publishMonthlyPosterReady` throw and expects `failed=1`, but AC4 and the real `RealtimePublisher` contract swallow broker exceptions at the chokepoint so persisted poster generation should remain counted as generated, not as a failed room.
- [x] [Review][Patch] Happy-path IT misses required PNG assertion [BE/src/test/java/com/yeosal/api/ceremony/FinalThreeJobIT.java:106] — AC10 says generated rooms produce poster rows plus PNG bytes, but the test only asserts `existsById`; it does not verify a non-null `pngUrl` or that the PNG file was written.

## Dev Notes

### Context — what Stories 7.1 / 6.1 / 5.4 / 1.2 ship that Story 7.2 builds on

**Story 7.1 (merged 2026-06-08 PR #93 as `455a939`):**

- `com.yeosal.api.ceremony` module exists with 9 source files (verified via `find BE/src/main/java/com/yeosal/api/ceremony -name "*.java"`).
- `FinalThreeService.generatePoster(roomId, yearMonth):92-130` is the per-room entry point this story orchestrates. `@Transactional`, idempotent via `posterRepository.findById` short-circuit at lines 95-104.
- `FinalThreeService` constructor injects `PngRasterizer` (cross-module from `kakaoshare/`), `SvgRenderer`, `RoomRepository`, `RoomMemberRepository`, `ChatService`, `EntityManager`, plus `@Value` config strings for `posters-dir` and `preview-card-base` (reused from Story 6.1). NO `RealtimePublisher` injection per Story 7.1 trap #12 — deliberate, this story owns the publish surface.
- Story 7.1 javadoc on `generatePoster` (lines 83-88) EXPLICITLY says: "Story 7.2's batch job pre-filters rooms with at least one ACTIVE survivor before invoking this, so duplicate fallback messages cannot occur in steady-state." This story implements that pre-filter via AC3.
- Postgres advisory lock at `acquireGenerationLock:201-206` serializes concurrent calls for the same `(roomId, yearMonth)`. The parallel executor (AC2) is safe because the advisory lock is per-key.

**Story 6.1 (merged 2026-06-06 PR #90 as `f682be5`):**

- `kakaoshare/PreviewCardRenderExecutorConfig.java` is the structural precedent for `MonthlyPosterRenderExecutorConfig` (AC2). Different pool size, same shape.
- `kakaoshare/PreviewCardBackgroundRenderer.java` shows the `@Async(EXECUTOR_BEAN_NAME)` pattern, but Story 7.2 deliberately uses **explicit `CompletableFuture.runAsync(..., executor)` + drain** rather than `@Async` because the orchestrator needs to count outcomes (`generated`, `skipped`, `failed`, `zeroSurvivor`) atomically and assert latency budget.
- `PngRasterizer.@PostConstruct warmUp()` (already shipped) absorbs the first-Batik-call cost; Story 7.2's parallel runs do NOT pay that boot tax.

**Story 5.4 (merged 2026-06-03 PR #89):**

- `ChatService.publishRuleChangeSystemMessage:185-201` is the REQUIRES_NEW shape `ChatService.publishMonthlyNoSurvivorsSystemMessage:218-226` (Story 7.1) inherits. Story 7.2 does NOT call this directly — `FinalThreeService.generatePoster` is the sole caller — but the REQUIRES_NEW propagation matters for the race-guard test case (AC10 `FinalThreeJobIT.race-guard`): when the worker thread races into the zero-survivor branch, the chat row commits in its own transaction independent of the worker's surrounding transaction.

**Story 1.2 (canonical `@Scheduled` precedent, shipped Sprint W1):**

- `SurvivalStateEvaluatorJob:38-150` is the canonical pattern: KST zone, page-size 200 over `findAllIdsOrderById`, per-room try/catch, package-private `runEvaluation(LocalDate)` test hook, `Summary` record with metrics, mass-elimination threshold alarm via Logger ERROR.
- `RoomEvaluationScheduler:30-92` is the monthly-cron precedent: `cron = "0 10 0 1 * *"`, `targetMonth()` via `YearMonth.now(clock.withZone(KST)).minusMonths(1)`. Story 7.2 uses identical structure with `"0 30 6 1 * *"` cron.
- Both jobs use `Clock` injection (from `YeosalApiApplication.systemClock():18-21` returning `Clock.systemUTC()`) so tests can swap a fixed clock. Story 7.2 follows the same constructor convention.

### Implementation trap #1 — `@Scheduled` cron requires 6 fields including seconds

Spring 6 / Spring Boot 3.3's `CronExpression` parser requires **6 fields**: `second minute hour day-of-month month day-of-week`. Linux `crontab(5)` uses 5 fields (no seconds). Copy-pasting from a sysadmin reference will silently break.

**Correct:** `"0 30 6 1 * *"` (seconds=0, minute=30, hour=6, day-of-month=1, month=*, day-of-week=*).

**Wrong:** `"30 6 1 * *"` (5 fields, parse error at boot — `IllegalArgumentException: Cron expression must consist of 6 fields`).

**Defense:** AC11 Gate 8 + the `FinalThreeJobSchedulerRegistrationIT` assertion explicitly verify the registered cron string. Boot would fail loudly on a 5-field cron.

### Implementation trap #2 — `day-of-week` and `day-of-month` interaction

Spring's `CronExpression` follows Quartz's "OR" semantics when both `day-of-month` and `day-of-week` are restricted. `"0 30 6 1 * 1"` would fire on the 1st OR every Monday — almost certainly not what's intended.

**Correct:** `"0 30 6 1 * *"` — `*` in `day-of-week` means "any day-of-week", which combined with `day-of-month=1` means "the 1st of the month, regardless of which weekday". Verified semantic.

**Anti-pattern:** Use `"0 30 6 1 * ?"` — `?` is a Quartz extension; Spring's `CronExpression` does NOT accept `?`. Would throw at boot.

### Implementation trap #3 — `Clock.systemUTC()` requires `withZone(KST)` for `YearMonth.now()`

`YearMonth.now(clock)` returns the clock's ZoneId-based YearMonth. `Clock.systemUTC()` returns a UTC clock; calling `YearMonth.now(clock)` directly at 06:30 KST on June 1 (= 21:30 UTC on May 31) would return `YearMonth.of(2026, 5)` (UTC May), then `.minusMonths(1)` would yield `YearMonth.of(2026, 4)` — **wrong by one month**.

**Correct:** `YearMonth.now(clock.withZone(KST)).minusMonths(1)`. The `withZone(KST)` shifts the clock's view to KST before `now()` extracts the YearMonth.

**Defense:** `RoomEvaluationScheduler.targetMonth():87-89` uses exactly this pattern. AC10 test case 9 + 10 verify the clock semantics at the day-boundary and cross-year boundary.

### Implementation trap #4 — `TaskExecutor` `@Qualifier` placement on constructor parameter

Spring's autowiring is by type by default. Since Story 6.1 declares `previewCardRenderExecutor` of type `TaskExecutor`, this story's `monthlyPosterRenderExecutor` is the SECOND `TaskExecutor` bean. Without `@Qualifier`, Spring throws `NoUniqueBeanDefinitionException` at boot.

**Correct:**

```java
public FinalThreeJob(
    ...
    @Qualifier(MonthlyPosterRenderExecutorConfig.EXECUTOR_BEAN_NAME) TaskExecutor executor,
    ...) {
```

The `@Qualifier` MUST be on the **constructor parameter**, NOT on the field. Field-level `@Qualifier` is silently ignored when constructor injection is used (project-context line 87: constructor-only).

**Defense:** AC11 Gate 10 catches this — boot would fail with `NoUniqueBeanDefinitionException` listing both `previewCardRenderExecutor` and `monthlyPosterRenderExecutor`.

**Anti-pattern:** Inject the concrete `ThreadPoolTaskExecutor` instead of `TaskExecutor` interface — couples the job to the implementation. The interface is sufficient.

### Implementation trap #5 — `CompletableFuture.runAsync(..., executor)` exception handling

A `RuntimeException` thrown inside the `Runnable` is captured by the `CompletableFuture` and surfaces only when `.join()` / `.get()` is called. If the per-room try/catch is INSIDE the runnable lambda, the lambda completes normally — `failed.incrementAndGet()` fires inside the lambda and the future completes successfully. That's the intended pattern (see AC5 sample code).

**Anti-pattern:** Put the try/catch OUTSIDE the `runAsync` call:

```java
// WRONG — the try/catch never sees the future's exception
try {
    CompletableFuture.runAsync(() -> processRoom(...), executor);
} catch (RuntimeException ex) {
    failed.incrementAndGet();  // dead code
}
```

The future swallows the exception until `.join()` is called. `.join()` happens at drain time, AFTER the loop. So the per-room attribution is lost. Keep try/catch INSIDE the lambda.

### Implementation trap #6 — `Page<Long>` iteration via `roomIds.getContent()` not `roomIds.iterator()`

`Page<T>` implements `Iterable<T>` via `getContent().iterator()`. Using `for (Long roomId : roomIds)` works but obscures the page semantics. Prefer `for (Long roomId : roomIds.getContent())` for clarity (matches `SurvivalStateEvaluatorJob:91` precedent).

**Anti-pattern:** Call `roomIds.stream().forEach(...)` — would obscure the per-page batch boundary and complicate counter aggregation.

### Implementation trap #7 — `Page.hasNext()` + `nextPageable()` vs increment-and-loop

The page-walk loop has two valid patterns in the codebase:

```java
// Pattern A (Story 1.2 SurvivalStateEvaluatorJob:87-109): nullable-pageable
Pageable page = PageRequest.of(0, PAGE_SIZE);
Page<Long> roomIds;
do {
    roomIds = repo.findAllIdsOrderById(page);
    // ... process
    page = roomIds.hasNext() ? roomIds.nextPageable() : null;
} while (page != null);

// Pattern B (Story 2.2 SpectatorDigestScheduler:60-72): increment counter
int pageNumber = 0;
Page<User> page;
do {
    page = users.findAll(PageRequest.of(pageNumber, PAGE_SIZE, Sort.by("id")));
    // ... process
    pageNumber += 1;
} while (page.hasNext());
```

Use **Pattern A** for this story (matches the closest precedent — `SurvivalStateEvaluatorJob` and `RoomEvaluationScheduler` both use Pattern A).

### Implementation trap #8 — `AtomicInteger.incrementAndGet()` vs `.get()` in log message

`AtomicInteger.incrementAndGet()` returns the NEW value (post-increment). `.get()` reads without mutation. For counters logged in the final `Summary`, both are fine because the read is `.get()` AFTER the drain — the increment style doesn't affect the final value. For mid-loop log messages, prefer `.get()` for explicit-read semantics (don't accidentally double-increment in a `log.debug("eligible=" + eligible.incrementAndGet())` pattern).

### Implementation trap #9 — RealtimePublisher swallows broker errors; broker failure must NOT fail the worker

Per `RealtimePublisher.sendTopic:143-150`, broker errors are caught + logged + swallowed. The worker thread's try/catch in `processRoom` does NOT catch RuntimeExceptions from `realtimePublisher.publishMonthlyPosterReady` (the publisher already swallows). On broker failure, the room is still counted as `generated` (since the row IS persisted) and the FE simply doesn't receive the signal — TanStack Query's stale-while-revalidate / app-foreground refetch handles the recovery.

**Defense:** AC10 test case 11 explicitly tests this: simulate broker exception via mock → still counts as generated, no rethrow.

**Anti-pattern:** Wrap `publishMonthlyPosterReady` in an explicit try/catch in the job ("defensive coding") — would create a second swallow point. Trust the chokepoint. Single responsibility.

### Implementation trap #10 — Eligibility query + advisory lock interact safely

The pre-filter query (AC3) is a snapshot at scan time. By the time a worker thread reaches `generatePoster` for a particular roomId, the room may have:

- (a) Lost its last ACTIVE survivor (e.g., 06:00 KST evaluator demoted the only remaining member to RED). Worker sees zero survivors → falls into zero-survivor chat-fallback branch. Counter `zeroSurvivor.incrementAndGet()`. Chat row IS published (Story 7.1 path).
- (b) Gained a new ACTIVE member (e.g., late-join during the batch window). Worker sees N+1 survivors. Poster generated for the correct snapshot at generate time.
- (c) Had the poster externally inserted (e.g., admin tool, parallel ShedLock-less re-run). `existsPoster` returns true → `processRoom` increments `skipped` → no publish.

All three outcomes are safe + auditable via the `Summary` counters.

**Defense:** AC10 `FinalThreeJobIT.race-guard` test case 3 simulates (a) via test thread deletion between query and worker.

### Implementation trap #11 — `RealtimePublisher` is NOT injected into `FinalThreeService`

Story 7.1 deliberately did NOT inject `RealtimePublisher` into `FinalThreeService` (trap #12 + architecture deviation #4). Story 7.2 maintains this separation: the JOB owns the publish call, NOT the service.

**Anti-pattern:** Refactor `FinalThreeService.generatePoster` to take an `Optional<RealtimePublisher>` or a `Consumer<FinalThreePoster>` callback — overengineering. The job-level `if (poster.isPresent() && !preExisted) realtimePublisher.publish(...)` shape is simpler and keeps the service publish-agnostic.

### Implementation trap #12 — TaskExecutor shutdown on ApplicationContext destroy

`ThreadPoolTaskExecutor` registered as a `@Bean` is auto-managed by Spring's lifecycle — `initialize()` is called on context refresh, `shutdown()` is called on context close (graceful). The test IT must NOT manually call `executor.shutdown()` — would prevent subsequent tests from using the bean.

**Defense:** `MonthlyPosterRenderExecutorConfig` initialization at `executor.initialize()` is the only manual call. Spring handles teardown.

**Anti-pattern:** Create the `ThreadPoolTaskExecutor` outside `@Bean` (e.g., as a `private static final`) — would not participate in Spring's lifecycle, would leak threads at JVM exit.

### Implementation trap #13 — `LoggerCapture` (Logback `ListAppender`) for assertion robustness

Asserting log emission via `ListAppender<ILoggingEvent>` is fragile if the test doesn't reset between cases — captured events accumulate. Tests must use `@BeforeEach` to clear the appender, and detach in `@AfterEach`. Without this, AC10 test case 5 ("ERROR log on failure") would false-positive when a previous test left an ERROR event in the buffer.

**Defense:** `FinalThreeJobTest` setup:

```java
private ListAppender<ILoggingEvent> appender;

@BeforeEach
void setUp() {
    appender = new ListAppender<>();
    appender.start();
    ((Logger) LoggerFactory.getLogger(FinalThreeJob.class)).addAppender(appender);
}

@AfterEach
void tearDown() {
    ((Logger) LoggerFactory.getLogger(FinalThreeJob.class)).detachAppender(appender);
}
```

### Implementation trap #14 — `Summary` record JSON / log shape stability

Future-self may want to wire a Prometheus / Sentry metric off the `Summary` record. Use stable field names (`eligible`, `generated`, `skipped`, `zeroSurvivor`, `failed`, `elapsedMs`). Do NOT rename later without a deprecation cycle.

**Anti-pattern:** Generic field names like `count`, `time` — would force a rename when the metric goes to ops.

## Out of scope (DO NOT IMPLEMENT IN THIS STORY)

1. **FE `FinalThreeCard.tsx` + Home tab integration** — Story 7.3 scope (epic line 952-972).
2. **Kakao Share SDK feed-template wiring for poster PNG** — Story 7.3 scope.
3. **`/topic/rooms.{id}.posters` FE subscriber wiring** — Story 7.3 scope (`FE/src/lib/realtime/topics.ts` + `handlers/postersHandler.ts`).
4. **`@SchedulerLock` / ShedLock multi-instance safety** — architecture §3.3 line 157 + §7.2 line 847 explicit "phase-2 only".
5. **`yeosal.ceremony.cron` config externalization** — KISS; cron is locked by PRD.
6. **`PosterController.@PostMapping` (manual job trigger endpoint)** — out of scope; admin/debug-only manual trigger would be a separate `Story-X`.
7. **Past-month poster regeneration** — posters are immutable per FR-8.7.6.
8. **Poster history view (multi-month archive)** — phase-2 (Story 7.1 OOS #11).
9. **Telemetry / analytics events (`final_three.batch_completed`, `final_three.batch_failed`)** — Story 8.5 scope.
10. **`MonthlyPosterReady` payload with embedded SVG body** — bandwidth concern; FE refetches via Story 7.1's GET endpoint.
11. **Email / push notification to surviving members** — Story 7.3 + Epic 8 push design decision.
12. **Cron-schedule monitoring dashboard (Grafana / Sentry cron monitor)** — phase-2 ops tooling.
13. **`FinalThreePosterCleanupJob` (TTL eviction)** — posters are immutable; no cleanup.
14. **Idempotent zero-survivor chat msg dedup** — Story 7.1 deferred-work entry #1 explicitly assigns prevention to AC3's pre-filter, NOT to a chat-msg-level dedup index. Pre-filter is sufficient.
15. **Per-room SLA assertion (per-poster < 3s in production)** — observability concern, not test-level. NFR-9.1.4 production observability via Sentry/Grafana.
16. **`SurvivalStateRepository` refactor to a `SurvivalStateAggregateRepository` for eligibility queries** — over-engineering. Single derived method is the minimum diff.
17. **`FinalThreeJob.runMonthlyBatch` REST endpoint to trigger from ops console** — admin tooling, separate story.
18. **Failed re-queue mechanism (retry queue for failed rooms)** — first-pass failures are surfaced via the ERROR log + `Summary.failed` counter; manual rerun via admin trigger (out of scope) or next-month natural retry. No automatic retry queue.
19. **Cross-room SVG batch optimization (e.g., shared font-cache prewarming)** — `PngRasterizer.@PostConstruct warmUp()` already covers it (Story 6.1 trap #5).
20. **`.github/workflows/be-it-boot-smoke.yml timeout-minutes` bump from 30 → 60** — Story 7.1 deferred-work entry (2026-06-08) explicitly notes this is a separate `chore(ci):` PR. NOT this story.

## Project structure notes

- BE files (NEW):
  - `BE/src/main/java/com/yeosal/api/ceremony/` — 3 new classes (`FinalThreeJob`, `MonthlyPosterRenderExecutorConfig`, `MonthlyPosterReadyPayload`).
- BE files (MODIFIED — surgical, ONE method each):
  - `BE/src/main/java/com/yeosal/api/ceremony/FinalThreeService.java` — append `existsPoster(long, YearMonth)`.
  - `BE/src/main/java/com/yeosal/api/realtime/RealtimePublisher.java` — append `publishMonthlyPosterReady(long, MonthlyPosterReadyPayload)`.
  - `BE/src/main/java/com/yeosal/api/survival/SurvivalStateRepository.java` — append `findRoomIdsWithAtLeastOneActive(Pageable)` `@Query` method.
- BE test files (NEW):
  - `BE/src/test/java/com/yeosal/api/ceremony/` — 4 new test files (`FinalThreeJobTest`, `MonthlyPosterReadyPayloadTest`, `FinalThreeJobIT`, `FinalThreeJobSchedulerRegistrationIT`).
  - `BE/src/test/java/com/yeosal/api/realtime/RealtimePublisherMonthlyPosterTest.java` — 1 new test file (mirrors existing publisher unit-test pattern without touching any existing test file).
- NO FE source/test (Story 7.3 owns the consumer surface).
- NO migration (FR-8.7.6 PK + Story 7.1's existing wiring sufficient).
- NO `build.gradle` edit (Apache Batik + JUnit + Mockito + Testcontainers already declared by Stories 1.4 / 6.1).
- NO `application.yml` edit (cron hard-coded in `@Scheduled`; no new config keys).

## Architecture decisions traceability

| Decision in this story | Architecture / PRD anchor | Status |
|---|---|---|
| `@Scheduled(cron = "0 30 6 1 * *", zone = "Asia/Seoul")` | Epic 7.2 line 938-940 + Architecture §2 line 111 + PRD FR-8.7.1 line 425 | LOCKED |
| Single-instance Compose deploy → no ShedLock | Architecture §3.3 line 157 + §7.2 line 847 | LOCKED for v1 |
| Per-room serialized via Postgres advisory lock (Story 7.1's existing `acquireGenerationLock`) | Architecture §5.3 (concurrency patterns) + Story 7.1 PR #93 | INHERITED |
| 8-thread dedicated `monthlyPosterRenderExecutor` | NFR-9.1.4 line 459 (10 min for 5K rooms) + PreviewCardRenderExecutorConfig precedent | ARCHITECT-LEVEL JUSTIFICATION ADDED (AC2) |
| Pre-filter eligible rooms (≥1 ACTIVE) at SQL level | FR-8.7.1 line 425 ("for every room with at least 1 member who completed the prior month with `survival_state.status = ACTIVE`") + Story 7.1 javadoc line 86-88 | LOCKED |
| Realtime emit via new `publishMonthlyPosterReady` + dedicated `/topic/rooms.{id}.posters` topic | Epic 7.2 line 950 + Architecture §6.1 line 599 + RealtimePublisher convention | ADDED (Story 7.1 architecture deviation #4 inherited) |
| `MonthlyPosterReadyPayload` typed record, NOT sealed `RealtimeEvent` variant | Story 7.1 architecture deviation #4 | INHERITED |
| Idempotent rerun via existing `posterRepository.findById` short-circuit in `FinalThreeService` + new `existsPoster` helper for job-side publish gating | PRD FR-8.7.6 immutability + Story 7.1 wiring | EXTENDED (AC6) |
| Per-room try/catch wraps worker body | Sibling scheduler precedent (`SurvivalStateEvaluatorJob`, `RoomEvaluationScheduler`) | INHERITED |
| `[ceremony][month-job]` log prefix; `[ceremony][month-job][budget-exceeded]` ERROR on 10-min overage | project-context line 284 (channel-scoped prefixes) + `SurvivalStateEvaluatorJob` mass-elim alert pattern | LOCKED |

## References

- **PRD**: `_bmad-output/planning-artifacts/prd.md` — FR-8.7.1 (line 425), FR-8.7.6 (line 430), NFR-9.1.4 (line 459), NFR-9.2.1 (idempotency).
- **Architecture**: `_bmad-output/planning-artifacts/architecture.md` — §3.3 line 157 (no ShedLock v1), §4.9 (poster rendering decision), §5.3 (concurrency patterns), §6.1 line 580-599 (ceremony module + sealed-variant note), §6.3 V11 step 10 (table), §7.2 line 847 (multi-instance ShedLock phase-2).
- **Epic**: `_bmad-output/planning-artifacts/epics.md` — Story 7.2 lines 930-950.
- **Story 7.1 (done, PR #93)**: `_bmad-output/implementation-artifacts/7-1-server-side-svg-poster-renderer.md` — AC0 inventory, traps #5 + #10 + #12, OOS items 1 + 2, dev notes "Context".
- **Story 7.1 deferred work**: `_bmad-output/implementation-artifacts/deferred-work.md` lines 26-30 (zero-survivor idempotency assignment to 7.2) + lines 32-33 (CI workflow timeout note).
- **Project context**: `_bmad-output/project-context.md` — line 87 (constructor injection), line 89 (`@Transactional` boundary), line 92 (day-boundary `Asia/Seoul`), line 117-120 (Spring Boot patterns), line 142 (Testcontainers, no H2), line 145 (TDD enforced), line 213 (`verify.sh`), line 284 (log prefixes).
- **Precedent files (READ ONLY)**:
  - `BE/src/main/java/com/yeosal/api/survival/SurvivalStateEvaluatorJob.java` (canonical `@Scheduled` daily pattern).
  - `BE/src/main/java/com/yeosal/api/room/RoomEvaluationScheduler.java` (canonical monthly-cron pattern).
  - `BE/src/main/java/com/yeosal/api/notification/SpectatorDigestScheduler.java` (paged fan-out variant).
  - `BE/src/main/java/com/yeosal/api/kakaoshare/PreviewCardRenderExecutorConfig.java` (`TaskExecutor` bean shape).
  - `BE/src/main/java/com/yeosal/api/realtime/RealtimePublisher.java` (sole STOMP emit chokepoint).
  - `BE/src/main/java/com/yeosal/api/ceremony/FinalThreeService.java` (Story 7.1 per-room API + existing advisory lock).
  - `BE/src/main/resources/application.yml` (`yeosal.share.posters-dir` already present).
  - `BE/build.gradle:310-318` (boot-smoke system property forwarding).

## Change log

| Date | Change | Author |
|---|---|---|
| 2026-06-08 | Initial story creation. AC0-AC13 + 14 traps + 20 OOS items + 23 BE tests + 14-gate verification matrix. Sprint-status flip to ready-for-dev. | bmad-create-story |
| 2026-06-08 | Implementation complete. 3 NEW BE source + 5 NEW BE test + 3 MODIFIED BE source. All 18 active unit tests GREEN locally; 5 opt-in ITs deferred to PR-CI per Story 5.4/6.1/7.1 Docker-availability precedent. Story Status flipped in-progress → review. | bmad-dev-story |

## Dev Agent Record

### Agent Model Used

claude-opus-4-7[1m] (Opus 4.7 1M-context)

### Debug Log References

- Initial sprint-status flip: gate prompt on first edit (resolved by re-applying after explicit fact disclosure). No code-side impact.
- Compile error after first append round: import statements for `Page`/`Pageable` (SurvivalStateRepository) and `MonthlyPosterReadyPayload` (RealtimePublisher) were silently skipped by the first edit attempt; re-applying the import edits made `./gradlew compileJava` green. Duplicate `publishMonthlyPosterReady` body removed (second attempt landed on top of the first that had also succeeded behind the gate).
- Spring 7 `CronTrigger` regression: `getTimeZone()` removed in favour of a private final `zoneId` field with NO public accessor. `FinalThreeJobSchedulerRegistrationIT` now uses reflection on the private field (documented inline in the file).

### Completion Notes List

**Implementation summary (AC0–AC13):**

- **AC0 inventory verified** — 9 Story 7.1 ceremony files present (`FinalThreePoster`, `FinalThreePosterId`, `FinalThreePosterRepository`, `FinalThreeService`, `PosterController`, `PosterDto`, `PosterNotFoundException`, `SurvivorTenureRow`, `SvgRenderer`); `@EnableScheduling` + `@EnableAsync` on `YeosalApiApplication` lines 11-12; V11 (10) `final_three_posters` table at `db/migration/V11__survival_revival_economy.sql:122-123`. NO infrastructure modifications.
- **AC1 LOCKED cron** — `@Scheduled(cron = "0 30 6 1 * *", zone = "Asia/Seoul")` on `FinalThreeJob.runMonthlyBatch()`; package-private `runBatch(YearMonth)` hook for tests + scheduler decoupling (mirrors `SurvivalStateEvaluatorJob.runEvaluation(LocalDate):74` precedent).
- **AC2 dedicated 8-thread pool** — `MonthlyPosterRenderExecutorConfig` follows `PreviewCardRenderExecutorConfig` shape with `corePool=8`, `maxPool=8`, `queue=256`, `threadNamePrefix="monthly-poster-"`. `@Qualifier(EXECUTOR_BEAN_NAME)` placed on the constructor parameter to disambiguate from `previewCardRenderExecutor` (trap #4).
- **AC3 single-method append** — `SurvivalStateRepository.findRoomIdsWithAtLeastOneActive(Pageable)` uses `select distinct s.room.id ... where s.status = com.yeosal.api.survival.SurvivalStatus.ACTIVE order by s.room.id` (JPQL form, fully-qualified enum constant — Hibernate 6 accepts this for `@Enumerated(EnumType.STRING)` fields). Returns `Page<Long>` so the page-walk loop preserves the established convention.
- **AC4 typed payload + dot-separator topic** — new `MonthlyPosterReadyPayload` record (`long roomId`, `String yearMonth`) + `RealtimePublisher.publishMonthlyPosterReady` emit to `/topic/rooms.{roomId}.posters`. NO sealed `RealtimeEvent` variant (Story 7.1 architecture deviation #4 inherited — see AC12).
- **AC5 page-walk + parallel drain + Summary** — `runBatch` follows the AC-locked shape verbatim. Per-room try/catch wraps `processRoom` so a single bad row never aborts the batch. `CompletableFuture.allOf(inflight).join()` drains the executor before the Summary is constructed.
- **AC6 surgical service extension** — `FinalThreeService.existsPoster(long, YearMonth)` is the ONLY public-API change to Story 7.1's service. `@Transactional(readOnly = true)`; wraps `posterRepository.existsById(new FinalThreePosterId(...))` without touching the advisory lock.
- **AC7 scheduler-registration regression IT** — `FinalThreeJobSchedulerRegistrationIT` asserts a `CronTask` whose `CronTrigger` has expression `"0 30 6 1 * *"` and zone `Asia/Seoul`. Opt-in via `yeosal.boot-smoke=true`.
- **AC8 idempotency** — `processRoom` gates the realtime publish on `!preExisted && result.isPresent()`. The AC3 SQL pre-filter makes the zero-survivor chat fallback unreachable in steady state (Story 7.1 deferred-work entry #1 closed).
- **AC9 scope fence honored** — `git status` confirms exactly the 11 production files (3 NEW source + 5 NEW test + 3 MODIFIED) plus 2 process files (sprint-status.yaml, this story md). NO files in the banned-paths list.
- **AC10 net-additive tests** — 12 `FinalThreeJobTest` + 3 `MonthlyPosterReadyPayloadTest` + 3 `RealtimePublisherMonthlyPosterTest` (18 active) + 4 `FinalThreeJobIT` + 1 `FinalThreeJobSchedulerRegistrationIT` (5 opt-in) = 23 total. Local `./gradlew test --rerun-tasks`: **tests=668, failures=0, errors=0, skipped=96**.
- **AC11 verification matrix** —
  - Gates 1, 2, 3, 4, 5, 6, 9 GREEN locally.
  - Gate 12 (brand-voice) GREEN — 0 HARD / 198 warnings (Story 5.4 / 6.1 / 7.1 baseline preserved; AC9 enforces 0 FE changes).
  - Gate 13 (diff sanity) GREEN — exact AC9 allow list.
  - Gates 7, 8, 10, 11 (Docker-bound) deferred to PR-CI per Story 5.4 / 6.1 / 7.1 precedent (`No usable Docker environment found` on dev host).
  - Gate 14 (`verify.sh`) halts at the pre-existing FE lint baseline (4 errors / 2 warnings in `app/rooms/[id]/chat.tsx`, `src/lib/realtime/client.ts`, `src/components/survival/__tests__/SurvivalChip*.tsx`, `app/(tabs)/.../InviteCodeSheet.test.tsx`); Story 7.2 has 0 FE changes — same deferred chore noted by Stories 4.1, 5.1, 5.4, 6.1, 7.1.
- **AC12 architecture deviations** — all 5 deviations listed in the AC table held (no sealed variant; dot-separator topic; dedicated executor; single-method on `SurvivalStateRepository`; no ShedLock). One new deviation logged below.
- **AC13 sprint-status transitions** — `7-2-monthly-final-3-scheduled-job: ready-for-dev → in-progress` flipped at dev-story start; → `review` flipped at implementation completion; → `done` flipped after code-review patches. `epic-7` stays `in-progress` (Story 7.3 remains `backlog`).

**Spec deviation logged for code-review follow-up (in addition to AC12 list):**

- **`FinalThreeJob.isOverBudget(long)` package-private seam.** AC5 sample code inlines the budget comparison as `if (elapsed > BUDGET_MS) {...}`. To make AC10 test case #8 ("budget-exceeded path... by directly calling the budget-check branch via a test-only seam") deterministic without sleeping 10 minutes or refactoring `System.currentTimeMillis()`, the comparison is extracted to a package-private method `boolean isOverBudget(long elapsedMs)`. `FinalThreeJobTest.runBatch_budgetExceeded_emitsErrorLog` uses `Mockito.spy(job) + doReturn(true).when(spy).isOverBudget(anyLong())` to force the branch. Production behavior is byte-identical (no logic change; one indirection); the method is intentionally NOT public.

### File List

**NEW BE source (3):**

- `BE/src/main/java/com/yeosal/api/ceremony/FinalThreeJob.java`
- `BE/src/main/java/com/yeosal/api/ceremony/MonthlyPosterRenderExecutorConfig.java`
- `BE/src/main/java/com/yeosal/api/ceremony/MonthlyPosterReadyPayload.java`

**NEW BE test (5):**

- `BE/src/test/java/com/yeosal/api/ceremony/FinalThreeJobTest.java`
- `BE/src/test/java/com/yeosal/api/ceremony/MonthlyPosterReadyPayloadTest.java`
- `BE/src/test/java/com/yeosal/api/ceremony/FinalThreeJobIT.java`
- `BE/src/test/java/com/yeosal/api/ceremony/FinalThreeJobSchedulerRegistrationIT.java`
- `BE/src/test/java/com/yeosal/api/realtime/RealtimePublisherMonthlyPosterTest.java`

**MODIFIED BE source (4):**

- `BE/src/main/java/com/yeosal/api/ceremony/FinalThreeService.java` — appended `GenerationResult` + `generatePosterWithResult(long, YearMonth)` and public `existsPoster(long, YearMonth)` so the job can distinguish fresh rows from existing rows under overlapping reruns.
- `BE/src/main/java/com/yeosal/api/realtime/JwtChannelInterceptor.java` — permits authenticated room members to subscribe to `/topic/rooms.{id}.posters`.
- `BE/src/main/java/com/yeosal/api/realtime/RealtimePublisher.java` — appended public `publishMonthlyPosterReady(long, MonthlyPosterReadyPayload)` after `publishLeadershipChange` + added `import com.yeosal.api.ceremony.MonthlyPosterReadyPayload;` (AC4).
- `BE/src/main/java/com/yeosal/api/survival/SurvivalStateRepository.java` — appended `Page<Long> findRoomIdsWithAtLeastOneActive(Pageable)` with `@Query` annotation + added `import org.springframework.data.domain.Page;` + `import org.springframework.data.domain.Pageable;` (AC3).

**MODIFIED BE test (1):**

- `BE/src/test/java/com/yeosal/api/realtime/JwtChannelInterceptorTest.java` — added `.posters` member allow / non-member reject coverage.

**Process files:**

- `_bmad-output/implementation-artifacts/sprint-status.yaml` — `7-2-monthly-final-3-scheduled-job: ready-for-dev → in-progress → review → done`; `last_updated: 2026-06-08`; dev-story-start, implementation-complete, and review-patch comments appended.
- `_bmad-output/implementation-artifacts/7-2-monthly-final-3-scheduled-job.md` — this file; Status `ready-for-dev → review → done`, task checkboxes flipped, review findings resolved, Dev Agent Record / Change Log / File List populated.
