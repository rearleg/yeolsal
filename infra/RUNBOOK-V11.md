# V11 Production Cutover Runbook

Operations playbook for the first deploy that ships
`V11__survival_revival_economy.sql` to a live yeolsal Postgres. Owned
by Story 1.4. Reuse this runbook for any future replay scenario
(disaster recovery, fresh staging cutover, etc.).

> The migration itself is in
> `BE/src/main/resources/db/migration/V11__survival_revival_economy.sql`
> and was authored alongside Story 1.1. Story 1.4 owns the
> verification + operational layer (this runbook + `verify-v11.sh` +
> `V11MigrationIT`).

---

## TL;DR

```bash
# 1. Pre-flight: snapshot the DB
docker compose exec -T postgres pg_dump -U yeosal yeosal \
  > "infra/backups/pre-v11-$(date +%F-%H%M).sql"

# 2. Deploy: Spring Boot runs Flyway on boot
docker compose pull api
docker compose up -d api

# 3. Post-flight: sanity-check the 5 invariants
infra/verify-v11.sh
```

If any step fails, see [Rollback](#rollback) below — but for non-emergency
defects prefer a **forward-fix V12** over rolling back.

---

## Pre-flight

### DB backup (mandatory)

```bash
mkdir -p infra/backups
docker compose exec -T postgres pg_dump -U yeosal yeosal \
  > "infra/backups/pre-v11-$(date +%F-%H%M).sql"
```

The backup is the only recovery path that preserves data written
*between* V11 deploy and rollback (revival events, pool deposits, new
survival-state transitions). The `DROP TABLE ... CASCADE` rollback path
discards those rows permanently.

### Downtime estimate

V11 adds 10 new tables, 11 new indexes, 1 new column on `users`, 1
constraint replacement on `rooms`, and 3 backfill INSERTs. Expected
wall-clock on Postgres 16-alpine in the Compose stack:

| Member-count scale | Wall-clock budget |
|--------------------|-------------------|
| 10s of rooms / 100s of members (current prod) | < 5 s |
| 50k members (Architecture §4.13 NFR-9.1.1 target) | < 30 s |

The deploy itself takes longer than the migration (container restart +
JVM warmup ~20 s). Plan for a ~60 s no-traffic window total.

### Rehearsal (recommended)

Mirror current prod into a staging Compose stack and replay the
sequence end-to-end before the real cutover:

```bash
docker compose -f infra/docker-compose.yml up -d postgres
docker compose exec -T postgres psql -U yeosal yeosal \
  < infra/backups/pre-v11-<latest>.sql
docker compose pull api
docker compose up -d api
infra/verify-v11.sh --verbose
docker compose down -v   # discard the rehearsal stack
```

All 5 checks in `verify-v11.sh` must report `PASS`. If any reports
`FAIL`, do not cut over to prod — diagnose against the audit findings
in [V11 audit](#v11-audit-finding-be-3) below.

---

## Deploy

```bash
docker compose pull api
docker compose up -d api
```

Spring Boot 3.3.5 runs Flyway on boot via the auto-configuration
(`spring.flyway.enabled=true` by default, `ddl-auto: validate` keeps
JPA from racing the migration). If Flyway fails mid-V11, the container
exits non-zero and Compose surfaces the failure via
`docker compose ps`.

**Watch the boot logs:**

```bash
docker compose logs api --since 2m | grep -iE "flyway|migration|exception"
```

The success line is `Successfully applied 1 migration to schema "public", now at version v11` (or similar). The failure path emits
`Migration of schema "public" to version "11 - ..."` followed by the
underlying SQL exception.

---

## Post-flight

### Automated check

```bash
infra/verify-v11.sh
```

Exit code `0` = all 5 invariants hold. Exit code `1` = at least one
invariant failed; **do not declare the deploy healthy**. Re-run with
`--verbose` to see the actual counts. See
[`infra/verify-v11.sh`](./verify-v11.sh) for the exact queries.

### Manual SQL fallback

If `verify-v11.sh` is unavailable (e.g. unfamiliar host), run these
queries directly via `docker compose exec -T postgres psql -U yeosal
yeosal`:

```sql
-- Flyway applied V11 successfully?
SELECT version, success, installed_on
  FROM flyway_schema_history
 WHERE version IN ('1','2','3','4','5','6','7','8','9','10','11')
 ORDER BY installed_rank;

-- Backfill counts match (every member has a survival_state row,
-- every room has a rule_version + point_pool row).
SELECT (SELECT count(*) FROM room_members)        AS members,
       (SELECT count(*) FROM survival_state)      AS survival_state,
       (SELECT count(*) FROM rooms)               AS rooms,
       (SELECT count(*) FROM room_rule_versions)  AS rule_versions,
       (SELECT count(*) FROM room_point_pool)     AS point_pool;

-- Free revival ticket backfill (NOT NULL DEFAULT false applied to
-- every legacy row).
SELECT count(*) AS users_with_null_free_revival
  FROM users
 WHERE free_revival_ticket_used IS NULL;
-- Expected: 0
```

The count comparisons in the second query must satisfy:
`survival_state = members`, `rule_versions = rooms`, `point_pool =
rooms`. Any mismatch indicates the corresponding backfill
(`V11__survival_revival_economy.sql` steps 13/14/15) did not run, and
is a deploy abort.

---

## V11 audit finding (BE-3)

### Hotfix 2026-05-13: `ux_revival_events_one_per_elimination` index expression

The original Story 1.4 audit missed a Postgres-level defect on V11
line 68. The partial unique index was authored as:

```sql
create unique index if not exists ux_revival_events_one_per_elimination
    on revival_events (room_id, user_id, ((eliminated_at)::date))
    where succeeded = true;
```

`eliminated_at` is `timestamptz` and `timestamptz::date` is **STABLE**
(its result depends on the session `timezone` GUC). Postgres only
accepts **IMMUTABLE** functions inside index expressions and fails
the migration with:

```text
SQL State : 42P17
Message   : ERROR: functions in index expression must be marked IMMUTABLE
```

The bug was caught on the first real Postgres-16-alpine cutover
attempt (server pull + `docker compose up -d --build`, 2026-05-13).
The Story 1.4 audit had marked V11 safe because the AC1/AC5 IT
(`V11MigrationIT`) had not been exercised against a live Docker —
deferred per Story 1.3 precedent. Lesson: never sign off the audit
on a V11-class migration without a green opt-in IT run.

**Fix shipped in the same Story 1.3 + 1.4 PR:** the index drops the
`::date` cast and dedupes on the exact elimination timestamp:

```sql
create unique index if not exists ux_revival_events_one_per_elimination
    on revival_events (room_id, user_id, eliminated_at)
    where succeeded = true;
```

Semantic impact: timestamp-level uniqueness is strictly tighter than
day-level. Per elimination, exactly one `revival_events` row is
INSERT'd by `SurvivalStateService` at the elimination moment, so the
new index still catches the bug class the original guard was meant
to defend against (duplicate successful revivals for the same
elimination event). Any future writer must mirror the new key:
`ON CONFLICT (room_id, user_id, eliminated_at) WHERE succeeded = true
DO NOTHING`.

V11 is replay-safe under this change: the `CREATE UNIQUE INDEX IF
NOT EXISTS` guard short-circuits on the second run.

### Original audit (still valid for the rest of V11)

Per Story 1.4 task BE-3, V11 was re-audited line-by-line. All
statements fall into the additive / idempotent categories:

| Category | V11 occurrences |
|---|---|
| `CREATE TABLE IF NOT EXISTS` | 10 (survival_state, streak_freezes, revival_events, personal_points_ledger, room_point_pool, room_rule_versions, record_visibility_prefs, final_three_posters, room_invite_preview_cache, pending_realtime_broadcasts) |
| `CREATE [UNIQUE] INDEX IF NOT EXISTS` | 7 |
| `ADD COLUMN IF NOT EXISTS` | 1 (`users.free_revival_ticket_used`) |
| `ALTER COLUMN ... SET DEFAULT` | 1 (`rooms.max_members` default 8 → 12) |
| `DROP CONSTRAINT IF EXISTS` + `ADD CONSTRAINT` | 1 (`chk_rooms_max_members`, widened to BETWEEN 2 AND 30) |
| `INSERT ... ON CONFLICT DO NOTHING` | 3 (backfill steps 13/14/15) |

**Destructive operations checked and absent:**

- No `DROP TABLE` against pre-V11 tables.
- No `DROP COLUMN` against pre-V11 columns.
- No `ALTER COLUMN ... TYPE` (no table rewrites).
- No `TRUNCATE`.
- No `UPDATE` against pre-V11 data outside column-default expansion.

**`chk_rooms_max_members` DROP is documentary.** `V3__rooms.sql`
creates `rooms.max_members smallint not null default 8` with **no
CHECK constraint** (V3 line 18). The `DROP CONSTRAINT IF EXISTS` in
V11 step (1) is therefore a no-op on every existing production DB —
it exists only so the V11 SQL is replay-safe (AC5).

**`max_members` default change (8 → 12) does not rewrite rows.**
Postgres `ALTER TABLE ... ALTER COLUMN ... SET DEFAULT` only changes
the column metadata. Existing rows retain their pre-V11
`max_members` value (8 by V3 default, or whatever the explicit insert
specified). New rows after V11 take the widened default of 12. The
matching widened CHECK constraint (`BETWEEN 2 AND 30`) accepts every
legacy value (8 is in [2, 30]).

**`users.free_revival_ticket_used` backfill via column default.**
`ADD COLUMN IF NOT EXISTS ... boolean not null default false` triggers
Postgres' fast-path column add — the storage receives `false` for
every existing row at the time the column is added. No separate
`UPDATE` statement is needed. Story 1.4 AC4 verifies this via
`SELECT count(*) FROM users WHERE free_revival_ticket_used IS NULL`
(must return 0).

V11 contains only additive DDL plus one idempotent constraint
replacement; no rollback fallout from the schema-level changes.
**Data-level rollback fallout is unavoidable** if the V11-introduced
tables already have rows (see [Rollback](#rollback)).

---

## Rollback

V11 is **additive-only**. Forward-fix is strongly preferred — write a
new V12 migration that undoes the specific defect. Full rollback is
documented for completeness but **discards every revival event, pool
deposit, and survival-state transition that happened between deploy
and rollback**.

### Rollback procedure (emergency only)

```bash
# 1. Restore the pre-V11 backup. THIS IS THE PREFERRED PATH.
docker compose exec -T postgres psql -U yeosal yeosal \
  < infra/backups/pre-v11-<timestamp>.sql
```

```sql
-- 2. If a clean restore is not possible, execute the inverse DDL
--    manually. DATA LOSS WARNING applies — see above.
DROP TABLE IF EXISTS
    survival_state,
    streak_freezes,
    revival_events,
    personal_points_ledger,
    room_point_pool,
    room_rule_versions,
    record_visibility_prefs,
    final_three_posters,
    room_invite_preview_cache,
    pending_realtime_broadcasts
  CASCADE;

ALTER TABLE users DROP COLUMN IF EXISTS free_revival_ticket_used;

ALTER TABLE rooms ALTER COLUMN max_members SET DEFAULT 8;
ALTER TABLE rooms DROP CONSTRAINT IF EXISTS chk_rooms_max_members;

-- 3. Remove the V11 row so Flyway does not block future migrations.
DELETE FROM flyway_schema_history WHERE version = '11';
```

After rollback, deploy the previous API image so Flyway does not
attempt V11 again on container restart.

### Data-loss audit

Rollback drops every row in these tables:

- `survival_state` — every membership lifecycle state (RED/YELLOW/SPECTATOR transitions, broad_visibility timestamps).
- `streak_freezes` — every monthly streak freeze consumption.
- `revival_events` — every revival audit row.
- `personal_points_ledger` — every per-(user, room) point movement.
- `room_point_pool` — every group pool total.
- `room_rule_versions` — every per-month rule history row.
- `record_visibility_prefs` — every spectator-mode opt-in.
- `final_three_posters` — every monthly ceremony poster.
- `room_invite_preview_cache` — every cached KakaoTalk share preview.
- `pending_realtime_broadcasts` — every queued delayed broadcast.

This is intentional — there is no "rollback while preserving these
rows" path because the schema that hosts them is gone. The pre-V11
backup is the only recovery mechanism that keeps the database
consistent at the previous schema version.

---

## Idempotency contract

V11 SQL is **idempotent**. The reference patterns:

- `CREATE TABLE IF NOT EXISTS` / `CREATE [UNIQUE] INDEX IF NOT EXISTS` / `ADD COLUMN IF NOT EXISTS` short-circuit if the object already exists.
- `DROP CONSTRAINT IF EXISTS chk_rooms_max_members` + `ADD CONSTRAINT chk_rooms_max_members` — Postgres treats this as a remove-then-add. On the second run the DROP succeeds (the previous ADD left the constraint), then the ADD re-creates it. Semantically equivalent to a no-op.
- `INSERT ... ON CONFLICT (...) DO NOTHING` (V11 steps 13/14/15) — the matching `unique` constraints (`survival_state(room_id, user_id)`, `room_rule_versions(room_id, effective_from_month)`, `room_point_pool` PK on `room_id`) guarantee duplicates collapse silently.

`V11MigrationIT.v11_replay_via_jdbc_isIdempotent` and
`V11MigrationIT.v11_replay_preservesBackfillRows` are the contract:
both replay the V11 SQL via raw `JdbcTemplate.execute` after Flyway
has already applied it, and assert every row count is unchanged.

### Failure-mid-migration recovery

If V11 fails mid-way (e.g. container OOM-killed between step 7 and
step 8), `flyway_schema_history` will hold a row with
`version='11', success=false`. Flyway refuses to retry until that
marker is cleared. The operator workflow:

```bash
# 1. Inspect the failure row.
docker compose exec -T postgres psql -U yeosal yeosal \
  -c "SELECT version, success, installed_on, execution_time
        FROM flyway_schema_history WHERE version='11';"

# 2. Clear the failed marker (Flyway 10 supports `flyway repair`
#    but the API container does not ship the CLI; use direct SQL).
docker compose exec -T postgres psql -U yeosal yeosal \
  -c "DELETE FROM flyway_schema_history WHERE version='11' AND success=false;"

# 3. Re-deploy — the next boot replays V11 from the top. Idempotency
#    guarantees (above) protect every step that partially ran.
docker compose up -d api
```

After the retry, run `verify-v11.sh` as the gate.

---

## Stack-PR merge note

Per project-context "Stack PR Merge Procedure" (incident-driven rule
from PR #36, where V7/V8 missed prod): the Story 1.4 PR's
`baseRefName` **must be `main`** before merging. Verify with:

```bash
gh pr view <N> --json baseRefName,mergeStateStatus
```

If `baseRefName != main`, either merge the base PR first or retarget
this PR's base to `main`. Do not merge stack-PRs out of order — that is
the exact failure mode that lost V7/V8 in the past.
