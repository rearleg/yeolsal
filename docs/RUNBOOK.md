# Yeolsal Ops Runbook

Operational guide for the Yeolsal Spring Boot API. Append-only; new
sections land at the bottom of the relevant area so existing on-call
muscle memory survives.

---

## Outage Diagnosis Priority

(See `_bmad-output/project-context.md` for the canonical short-form
checklist; replicated here for offline ops access.)

1. **Which commit is running?** `docker compose exec api cat /app/COMMIT`.
2. **Root cause from `ApiExceptionHandler` logs:**
   `docker compose logs api --since 5m | grep -iE "\[chat\]|\[db\]|\[validation\]|\[evaluator\]|exception"`.
3. **Has the migration applied?** Inspect `flyway_schema_history` for the
   expected `V<N>__<slug>` row + `success = true`.
4. **ApplicationContext boot suspect?** `grep "Error creating bean|No default constructor"`
   in the api logs (cf. PR #34 — missing `@Autowired` on `RateLimitFilter`).

---

## Alert Rules

### Mass-Elimination Guard (NFR-9.3.7, Story 1.2)

**Purpose:** A single 06:00 KST evaluator run that produces an
unusually large number of `YELLOW → RED` transitions almost always
indicates a code-level bug (broken rule interpreter, day-boundary
regression, dedup gate breach). The load-bearing assumption is "a bug
just eliminated 1,000 people overnight" — silencing this alert is
forbidden.

**Source:** `SurvivalStateEvaluatorJob.runEvaluation(...)` emits a
structured ERROR log when `totalToRed > yeosal.evaluator.mass-elimination-alert-threshold`
(default 20, override via `YEOSAL_EVALUATOR_MASS_ELIM_THRESHOLD`).

**Sentry alert rule (register at production cutover):**

```text
Project: yeolsal-api
Severity: critical
Filter:
  level: error
  logger: com.yeosal.api.survival.SurvivalStateEvaluatorJob
  message contains: "[evaluator][mass-elimination]"
Action:
  - PagerDuty: oncall-yeolsal
  - Slack:     #yeolsal-alerts
Cooldown: 1 hour (the cron runs once per day; cooldown avoids
          accidental duplicate pages if the dispatcher retries)
```

**Triage:**

1. Pull the offending night's run summary:
   `docker compose logs api --since 24h | grep "\[evaluator\] done"`.
   Confirm `toRed=N` matches the alert payload.
2. Compare against the prior 7 days' nightly numbers
   (`grep "\[evaluator\] done"` over a wider window). A 5x jump is
   almost always a bug.
3. Inspect `room_rule_versions` for a recently inserted row that may
   have flipped `weekendInclude` unexpectedly:
   `SELECT id, room_id, effective_from_month, rule_payload, created_at
    FROM room_rule_versions ORDER BY created_at DESC LIMIT 20;`
4. Inspect `notification_log` for the day's dedup keys to confirm
   per-user idempotency held:
   `SELECT user_id, key, sent_at FROM notification_log
    WHERE kind = 'SURVIVAL_STATE' AND sent_at >= now() - interval '36 hours'
    ORDER BY sent_at DESC LIMIT 50;`
5. If the rule interpreter is suspect, the unit suite
   `SurvivalStateServiceEvaluateRoomTest` is the canonical reference for
   AC2–AC9 algorithm behavior.

**Disable procedure (only with explicit incident-commander approval):**

1. Set `YEOSAL_EVALUATOR_MASS_ELIM_THRESHOLD` to a very high value (e.g.
   `100000`) via the deploy env — the threshold check is greater-than,
   so this silences the alert without disabling the evaluator itself.
2. Open a HIGH-priority issue with the override commit + reason.
3. Restore the default the same day, or earlier once the underlying
   bug is fixed.

**Never do:** comment out the log line, or change the log level — the
log is the alert's primary fact source.

---

## Realtime Dispatcher (Story 1.2 BE-6.3)

The `PendingRealtimeBroadcastDispatcher` drains
`pending_realtime_broadcasts` every minute (overridable via
`YEOSAL_REALTIME_BROADCAST_DISPATCHER_DELAY_MS`). A broker hiccup
leaves the row eligible for the next tick (markEmitted only on
success).

**Symptoms of a stuck dispatcher:**

- `SELECT COUNT(*) FROM pending_realtime_broadcasts
   WHERE scheduled_at <= now() AND emitted_at IS NULL;` keeps climbing.
- ERROR logs `[realtime-dispatcher] payload deserialize failed rowId=...`
  in the api container.

**Manual drain check (one-off, no production-side fix):**

`docker compose logs api --since 10m | grep "\[realtime-dispatcher\]"`
should show a `tick emitted=N failedBroker=0 failedDeserialize=0` line
every minute. If `failedBroker` is non-zero, the STOMP broker (likely
the in-memory simple broker) had a connectivity blip; the next tick
should self-recover. If `failedDeserialize` is non-zero, the row
payload is malformed — page the on-call engineer (do not auto-delete
the row).
