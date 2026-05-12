# Data Models — BE

PostgreSQL is the source of truth. Schema evolves only through Flyway migrations under `BE/src/main/resources/db/migration/V<N>__<slug>.sql`. Hibernate runs in `validate` mode, so any code change without a matching migration will fail boot.

## Migration History

| Version | Slug | Purpose |
|---------|------|---------|
| V1 | `init` | Users, refresh tokens, friendships, daily entries, todos, reflections, monthly goals |
| V2 | `entry_date_six_am_boundary` | Backfills `daily_entries.entry_date` under the 06:00 KST day boundary; aborts on user-level collisions |
| V3 | `rooms` | Rooms, room_members, room_invites (active-code partial unique) |
| V4 | `notifications` | Notification prefs, push tokens, notification log |
| V5 | `login_codes` | Login-code table for Kakao mobile exchange / email magic-link |
| V6 | `room_minimums_and_warnings` | Per-room min daily-goal-days + per-member overrides + monthly warnings |
| V7 | `chat_messages` | Per-room chat baseline with cursor-paged index |
| V8 | `chat_milestone_dedup` | Partial unique index on milestone messages keyed by `(room_id, payload->>userId, payload->>month)` |
| V9 | `chat_milestone_per_day` | Tightens dedup to per-day for milestone announcements |
| V10 | `reflection_updated_at` | Adds `updated_at` to `reflections` for FE last-modified tracking |

> **Migration rules** (from CONTRIBUTING.md):
> - Idempotent SQL is preferred (`drop ... if exists`, `insert ... on conflict do nothing`).
> - Partial unique expression indexes must be matched exactly by service-layer `INSERT ... ON CONFLICT ... WHERE pred DO ...` clauses. V8 was created precisely because the prior code path could insert duplicate milestone rows under concurrency.

## Tables

### `users` (V1)

| Column | Type | Constraints |
|--------|------|-------------|
| `id` | `bigserial` | PK |
| `email` | `varchar(255)` | NOT NULL, UNIQUE |
| `nickname` | `varchar(80)` | NOT NULL |
| `password_hash` | `varchar(255)` | NULL (Kakao users have none) |
| `auth_provider` | `varchar(30)` | NOT NULL, default `'EMAIL'` |
| `timezone` | `varchar(80)` | NOT NULL, default `'Asia/Seoul'` |
| `created_at` | `timestamptz` | NOT NULL, default `now()` |

### `refresh_tokens` (V1)

`id` PK; `user_id` FK → users (cascade); `token_hash` UNIQUE; `expires_at`, `revoked_at`, `created_at`. Used by `/auth/refresh`; rotated on every refresh.

### `friendships` (V1)

`(requester_id, addressee_id)` UNIQUE; `status` varchar (`PENDING`, `ACCEPTED`, `BLOCKED`, etc.). FK to users on both sides.

### `daily_entries` (V1, V2)

| Column | Type | Constraints |
|--------|------|-------------|
| `id` | `bigserial` | PK |
| `user_id` | `bigint` | NOT NULL, FK → users |
| `entry_date` | `date` | NOT NULL |
| `goal` | `text` | NOT NULL |
| `created_at` | `timestamptz` | NOT NULL, default `now()` |
| | | UNIQUE `(user_id, entry_date)` |

`entry_date` is computed under the 06:00 KST day boundary: an entry written before 06:00 in the user's `Asia/Seoul` local time belongs to the previous calendar date. V2 backfilled this; the runtime equivalent lives in `DailyService.currentEntryDate()` via `EntryDateResolver`.

### `todo_items` (V1)

`daily_entry_id` FK (cascade); `title`, `completed`, `completed_at`. One-to-many under daily_entries.

### `reflections` (V1, V10)

| Column | Type | Constraints |
|--------|------|-------------|
| `id` | `bigserial` | PK |
| `daily_entry_id` | `bigint` | NOT NULL, UNIQUE, FK → daily_entries (cascade) |
| `body` | `text` | NOT NULL |
| `submitted_at` | `timestamptz` | NOT NULL, default `now()` |
| `updated_at` | `timestamptz` | added in V10 |

A "day counts" exactly when `daily_entries` exists for that date AND a `reflections` row was submitted before the next 06:00 KST.

### `monthly_goals` (V1)

`(user_id, month)` UNIQUE where `month` is `varchar(7)` (`YYYY-MM`). One row per user per month.

### `rooms` (V3, V6)

`id`, `name`, `owner_id` FK → users, `max_members smallint default 8`, `created_at`. V6 added `min_daily_goal_days smallint default 10` with CHECK in `{10, 15, 20, 31}`.

### `room_members` (V3)

`(room_id, user_id)` UNIQUE; `role varchar(20) default 'MEMBER'`; cascade delete with rooms. Indexed on `user_id` for membership lookup.

### `room_invites` (V3)

Active codes are uniquely indexed via partial expression: `create unique index idx_room_invites_active_code on room_invites (code) where revoked_at is null`. Revoked codes can collide with future codes (the redeem flow only considers `revoked_at is null and (expires_at is null or expires_at > now())`).

### `group_member_minimums` (V6)

`(room_id, user_id)` UNIQUE; `min_daily_goal_days smallint` CHECK `{10,15,20,31}`; `warning_count smallint` CHECK `between 0 and 2`. Composite FK `(room_id, user_id)` references `room_members(room_id, user_id)` cascade. Per-member override; must be `>=` the room's minimum (enforced in the service layer).

### `group_warnings` (V6)

Audit trail: `(room_id, user_id, evaluation_month)` UNIQUE; `evaluation_month` is the first day of the evaluated month in KST (CHECK `extract(day from evaluation_month) = 1`). Stores `completed_days`, `required_days`, `warning_count_after`. Indexed on `(user_id, evaluation_month desc)`.

### `notification_prefs` (V4)

PK is `user_id` (1:1 with users). Booleans: `goal_nudge_enabled`, `reflection_nudge_enabled`, `event_hooks_enabled`. `quiet_start_hour`, `quiet_end_hour smallint` CHECK 0–23. Defaults to 22:00–08:00 quiet hours.

### `push_tokens` (V4)

`(user_id, token)` UNIQUE; `platform varchar(20)`; `last_seen_at`. Multiple devices per user.

### `notification_log` (V4)

`(user_id, kind, key)` UNIQUE — idempotency key for cron jobs (date string) and event hooks (slug like `FRIEND_GOAL:42`). Indexed on `(user_id, kind, sent_at desc)` for the 30-min event-hook debounce window.

### `login_codes` (V5)

Short-lived codes for Kakao mobile exchange / email magic-link flow. (See migration for exact columns.)

### `chat_messages` (V7, V8, V9)

| Column | Type | Constraints |
|--------|------|-------------|
| `id` | `bigserial` | PK |
| `room_id` | `bigint` | NOT NULL, FK → rooms (cascade) |
| `sender_user_id` | `bigint` | NULL (system messages have no author), FK → users (set null) |
| `kind` | `varchar(20)` | NOT NULL, CHECK in `{USER, SYSTEM, GOAL, REFLECTION, MILESTONE, AUTO_LEAVE}` |
| `body` | `text` | NOT NULL |
| `payload` | `jsonb` | NOT NULL, default `'{}'` (system metadata; FE treats as opaque) |
| `created_at` | `timestamptz` | NOT NULL, default `now()` |

Indexes:
- `idx_chat_messages_room_id_id_desc` on `(room_id, id desc)` — supports cursor pagination.
- `ux_chat_messages_milestone_room_user_month` (V8) — partial unique on `(room_id, payload->>'userId', payload->>'month') WHERE kind = 'MILESTONE'`.
- V9 narrows the milestone uniqueness predicate further to per-day.

> Service-side INSERT path for milestone messages must use `INSERT ... ON CONFLICT (room_id, ((payload->>'userId')), ((payload->>'month'))) WHERE kind = 'MILESTONE' DO NOTHING` to keep the partial unique constraint honored under concurrency. Mismatched predicates produce `DataIntegrityViolationException` at runtime.

## Cross-Cutting Conventions

- All timestamps are `timestamptz`.
- Cascades follow the parent: deleting a user or cardinal parent removes dependents.
- `users.timezone` is read but no UI yet exists for editing it (defaults to `Asia/Seoul`).
- The 06:00 day-boundary semantics are owned by the BE (V2 + `DailyService.currentEntryDate()`); FE must not derive "today" client-side from UTC midnight.
- JPA entities live alongside their feature package (`daily/Reflection.java`, `room/Room.java`, etc.); repositories use Spring Data JPA naming conventions.
