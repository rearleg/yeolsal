-- Phase 4-D: per-room minimum daily-goal-days + per-member overrides + monthly warnings.
--
-- Decisions:
--   * Allowed values are a fixed whitelist {10, 15, 20, 31}. 31 means "every
--     day of that calendar month"; the evaluation cap-by-month-length lives
--     in the application layer so February doesn't auto-warn members.
--   * Each member has their own minimum that must be >= the room's minimum.
--     Backfill sets every existing member's row to mirror the room.
--   * Monthly warning state lives in (group_member_minimums.warning_count)
--     for fast read; group_warnings is the audit trail with one row per
--     (room, user, month) — enforces evaluator idempotency.

alter table rooms
    add column min_daily_goal_days smallint not null default 10;

alter table rooms
    add constraint chk_rooms_min_daily_goal_days
        check (min_daily_goal_days in (10, 15, 20, 31));

create table group_member_minimums (
    id                  bigserial primary key,
    room_id             bigint not null,
    user_id             bigint not null,
    min_daily_goal_days smallint not null,
    warning_count       smallint not null default 0,
    updated_at          timestamptz not null default now(),
    unique (room_id, user_id),
    constraint fk_group_member_minimums_member
        foreign key (room_id, user_id)
        references room_members(room_id, user_id)
        on delete cascade,
    constraint chk_group_member_minimum_days
        check (min_daily_goal_days in (10, 15, 20, 31)),
    constraint chk_group_member_warning_count
        check (warning_count between 0 and 2)
);

create index idx_group_member_minimums_room on group_member_minimums (room_id);

-- Backfill: every existing membership inherits the room's minimum (default 10).
-- Idempotent thanks to the unique (room_id, user_id) constraint.
insert into group_member_minimums (room_id, user_id, min_daily_goal_days)
select rm.room_id, rm.user_id, r.min_daily_goal_days
from room_members rm
join rooms r on r.id = rm.room_id
on conflict (room_id, user_id) do nothing;

create table group_warnings (
    id                  bigserial primary key,
    room_id             bigint not null references rooms(id) on delete cascade,
    user_id             bigint not null references users(id) on delete cascade,
    -- First day of the evaluated month in KST. A real DATE lets the scheduler
    -- compare with arithmetic (range queries, ordering) without string-format
    -- drift, and the CHECK below traps any caller that would otherwise insert
    -- a mid-month timestamp.
    evaluation_month    date not null,
    completed_days      smallint not null,
    required_days       smallint not null,
    warning_count_after smallint not null,
    created_at          timestamptz not null default now(),
    unique (room_id, user_id, evaluation_month),
    constraint chk_group_warnings_month_start
        check (extract(day from evaluation_month) = 1)
);

create index idx_group_warnings_user_month
    on group_warnings (user_id, evaluation_month desc);
