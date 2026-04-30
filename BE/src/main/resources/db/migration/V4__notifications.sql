-- Phase 4-E: A-2 standard notifications — prefs, push tokens, dedup log.
--
-- Decisions:
--   * Per-user prefs row (1:1 with users). Defaults: standard nudges enabled,
--     quiet hours 22:00-08:00 local time.
--   * Push tokens are unique per (user, token); multiple devices per user
--     allowed. Re-registering an existing token is a no-op (UPSERT-style).
--   * notification_log provides idempotency for both scheduled cron and
--     event-hook debouncing: senders insert (user_id, kind, key) and bail on
--     conflict. The 30-minute event-hook debounce is enforced in the app
--     layer by selecting the most recent sent_at for (user_id, kind).

create table notification_prefs (
    user_id                  bigint primary key references users(id) on delete cascade,
    goal_nudge_enabled       boolean not null default true,
    reflection_nudge_enabled boolean not null default true,
    event_hooks_enabled      boolean not null default true,
    quiet_start_hour         smallint not null default 22 check (quiet_start_hour between 0 and 23),
    quiet_end_hour           smallint not null default 8  check (quiet_end_hour   between 0 and 23),
    updated_at               timestamptz not null default now()
);

create table push_tokens (
    id           bigserial primary key,
    user_id      bigint not null references users(id) on delete cascade,
    token        varchar(255) not null,
    platform     varchar(20) not null,
    created_at   timestamptz not null default now(),
    last_seen_at timestamptz,
    unique (user_id, token)
);

create index idx_push_tokens_user on push_tokens (user_id);

create table notification_log (
    id      bigserial primary key,
    user_id bigint not null references users(id) on delete cascade,
    kind    varchar(40) not null,
    -- dedup key — for cron jobs use the date string ("2026-04-30"); for event
    -- hooks use a slug like "FRIEND_GOAL:42" so the 30-minute window can be
    -- evaluated by selecting on (user_id, kind, sent_at desc).
    key     varchar(120) not null,
    sent_at timestamptz not null default now(),
    unique (user_id, kind, key)
);

create index idx_notification_log_recent
    on notification_log (user_id, kind, sent_at desc);
