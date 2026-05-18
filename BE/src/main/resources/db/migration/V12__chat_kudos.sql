-- V12 — chat_messages.kind extension + Kudos 1/(KST day, sender, target) dedupe.
-- Story 3.5. PRD FR-8.3.9. UX U3 disposition ACCEPT.
--
-- Two deviations from the PRD/epics text are intentional and locked here:
--
--   (a) V11 already shipped to production (PR #55 commit c2b9e7d, 2026-05-13)
--       WITHOUT the KUDOS kind or this index. The epics line 573 "the V11
--       migration includes the chat_messages.kind enum extension" assumption
--       is stale because Flyway runs each migration exactly once. Story 3.5
--       therefore adds this NEW V12 migration (smallest free integer per
--       project-context "V<N>__<slug>.sql" rule).
--
--   (b) The PRD's `date_part('day', created_at at time zone 'Asia/Seoul')`
--       formula is wrong — `date_part('day', ...)` returns ONLY the
--       day-of-month integer (1..31), so kudos sent on different KST days
--       with the same day-of-month (e.g. Jan 1 and Feb 1) would collide,
--       while same-month different-days would not. The correct expression
--       is `((created_at at time zone 'Asia/Seoul')::date)` — see the
--       IMMUTABLE rationale below.

-- (1) Extend the chk_chat_messages_kind CHECK to permit 'KUDOS'.
-- V7 created the constraint with the original 6 kinds; drop+recreate is
-- the only Postgres-supported way to widen a named CHECK constraint.
alter table chat_messages
    drop constraint if exists chk_chat_messages_kind;
alter table chat_messages
    add constraint chk_chat_messages_kind
        check (kind in ('USER', 'SYSTEM', 'GOAL', 'REFLECTION',
                        'MILESTONE', 'AUTO_LEAVE', 'KUDOS'));

-- (2) Partial unique index — at most one KUDOS per (sender, target, KST day).
-- Idempotency follows V8/V9 milestone-dedup precedent. The KST-day key uses
-- '((created_at at time zone ''Asia/Seoul'')::date)', NOT 'date_part('day',...)':
--   - 'at time zone ''Asia/Seoul''' on a timestamptz yields a timezone-less
--     timestamp (IMMUTABLE because the timezone is a literal constant).
--   - The subsequent '::date' cast on a plain timestamp is IMMUTABLE.
-- Direct 'timestamptz::date' is STABLE — rejected by Postgres inside a
-- partial unique index expression with SQLSTATE 42P17 (cf. PR #57 commit
-- 4f741ff, which fixed the V11 ((eliminated_at)::date) trap).
create unique index if not exists ux_kudos_one_per_day
    on chat_messages (
        sender_user_id,
        ((payload ->> 'targetUserId')),
        (((created_at at time zone 'Asia/Seoul')::date))
    )
    where kind = 'KUDOS';
