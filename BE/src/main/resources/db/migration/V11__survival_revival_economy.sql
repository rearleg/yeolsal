-- V11 — Survival State, Revival Economy, Group Pool, Rule Versions, Ceremony.
--
-- Implements Architecture §6.3 in a single migration. Story 1.1 only consumes
-- the subset (1)+(3)+(13) at runtime; the remaining tables ship here so that
-- subsequent stories (1.4, 3.x, 4.x, 5.x, 6.x, 7.x) can wire entities without
-- introducing additional Flyway files. All statements are idempotent
-- (drop ... if exists, create ... if not exists, on conflict do nothing).

-- (1) widen rooms.max_members range. V3 ships no prior CHECK constraint, so
-- the drop here is documentary; the new constraint accepts every legacy row
-- (current default 8) plus the widened range required by FR-8.1.1 (2..30).
alter table rooms
    drop constraint if exists chk_rooms_max_members;
alter table rooms
    alter column max_members set default 12;
alter table rooms
    add constraint chk_rooms_max_members check (max_members between 2 and 30);

-- (2) free revival ticket flag on users (lifetime-one self revival).
alter table users
    add column if not exists free_revival_ticket_used boolean not null default false;

-- (3) survival_state — materialized source of truth for membership lifecycle.
-- grace_ends_at is nullable: legacy backfilled rows (step 13) leave it NULL
-- because the legacy joined_at semantics aren't worth retro-fitting; only
-- fresh joins via Story 1.1 set grace_ends_at = joinedAt + 14 days.
create table if not exists survival_state (
    id                       bigserial primary key,
    room_id                  bigint not null references rooms(id) on delete cascade,
    user_id                  bigint not null references users(id) on delete cascade,
    status                   varchar(16) not null check (status in ('ACTIVE','YELLOW','RED','SPECTATOR')),
    last_state_change_at     timestamptz not null default now(),
    eliminated_at            timestamptz,
    broad_visibility_at      timestamptz,
    grace_ends_at            timestamptz,
    unique (room_id, user_id)
);
create index if not exists idx_survival_state_room on survival_state (room_id);
create index if not exists idx_survival_state_status on survival_state (status, last_state_change_at);

-- (4) streak_freezes — one freeze per (user, month).
create table if not exists streak_freezes (
    id            bigserial primary key,
    user_id       bigint not null references users(id) on delete cascade,
    room_id       bigint not null references rooms(id) on delete cascade,
    applied_date  date not null,
    month         varchar(7) not null,
    created_at    timestamptz not null default now()
);
create unique index if not exists ux_streak_freezes_user_month on streak_freezes (user_id, month);

-- (5) revival_events — append-only audit with V8/V9-style partial unique
-- dedupe so concurrent revival attempts collapse to exactly one success
-- per (room, user, elimination date).
create table if not exists revival_events (
    id              bigserial primary key,
    room_id         bigint not null references rooms(id) on delete cascade,
    user_id         bigint not null references users(id) on delete cascade,
    giver_user_id   bigint references users(id),
    source          varchar(20) not null check (source in ('FREE_TICKET','PERSONAL_POINTS','FRIEND_GIFT')),
    source_subtype  varchar(20),
    points_spent    smallint not null,
    pool_after      integer not null,
    eliminated_at   timestamptz not null,
    succeeded       boolean not null default true,
    occurred_at     timestamptz not null default now()
);
create unique index if not exists ux_revival_events_one_per_elimination
    on revival_events (room_id, user_id, ((eliminated_at)::date))
    where succeeded = true;
create index if not exists idx_revival_events_giver
    on revival_events (giver_user_id) where giver_user_id is not null;

-- (6) personal_points_ledger — append-only point movements per (user, room).
create table if not exists personal_points_ledger (
    id               bigserial primary key,
    user_id          bigint not null references users(id) on delete cascade,
    room_id          bigint not null references rooms(id) on delete cascade,
    delta            smallint not null,
    reason           varchar(24) not null check (reason in ('SURVIVAL','REVIVAL_SPEND','FRIEND_GIFT_SPEND','ROOM_LEAVE','ADJUSTMENT')),
    occurred_at      timestamptz not null default now(),
    revival_event_id bigint references revival_events(id)
);
create index if not exists idx_ppl_user_room on personal_points_ledger (user_id, room_id, occurred_at);

-- (7) room_point_pool — counter cache; updates inside the same tx as ledger writes.
create table if not exists room_point_pool (
    room_id        bigint primary key references rooms(id) on delete cascade,
    total          integer not null default 0 check (total >= 0),
    last_event_at  timestamptz
);

-- (8) room_rule_versions — month-keyed rule history (preset + weekend toggle).
create table if not exists room_rule_versions (
    id                    bigserial primary key,
    room_id               bigint not null references rooms(id) on delete cascade,
    effective_from_month  varchar(7) not null,
    rule_payload          jsonb not null,
    created_by_user_id    bigint not null references users(id),
    created_at            timestamptz not null default now(),
    unique (room_id, effective_from_month)
);

-- (9) record_visibility_prefs — per-(user, room) opt-in for spectator-mode
-- record sharing.
create table if not exists record_visibility_prefs (
    user_id                bigint not null references users(id) on delete cascade,
    room_id                bigint not null references rooms(id) on delete cascade,
    share_on_elimination   boolean not null default false,
    updated_at             timestamptz not null default now(),
    primary key (user_id, room_id)
);

-- (10) final_three_posters — monthly ceremony posters per (room, month).
create table if not exists final_three_posters (
    room_id      bigint not null references rooms(id) on delete cascade,
    year_month   varchar(7) not null,
    svg_text     text not null,
    png_url      varchar(512),
    generated_at timestamptz not null default now(),
    primary key (room_id, year_month)
);

-- (11) room_invite_preview_cache — KakaoTalk share preview cache (per room).
create table if not exists room_invite_preview_cache (
    room_id                  bigint primary key references rooms(id) on delete cascade,
    png_url                  varchar(512) not null,
    rendered_at              timestamptz not null default now(),
    rule_version_id          bigint references room_rule_versions(id),
    member_count_at_render   smallint not null
);

-- (12) pending_realtime_broadcasts — delayed-emit support (§4.14).
create table if not exists pending_realtime_broadcasts (
    id            bigserial primary key,
    scheduled_at  timestamptz not null,
    payload       jsonb not null,
    emitted_at    timestamptz
);
create index if not exists idx_pending_realtime_due
    on pending_realtime_broadcasts (scheduled_at) where emitted_at is null;

-- (13) backfill: every existing room_member gets ACTIVE survival_state.
-- grace_ends_at is intentionally left NULL for legacy rows — the original
-- joined_at semantics aren't worth retro-fitting and would land legacy
-- members in an unintended grace window. Only fresh joins via Story 1.1
-- (RoomService.create / joinByCode) set grace_ends_at = joinedAt + 14d.
insert into survival_state (room_id, user_id, status)
select rm.room_id, rm.user_id, 'ACTIVE'
from room_members rm
on conflict (room_id, user_id) do nothing;

-- (14) backfill: every existing room gets a default rule_payload effective
-- this month (Asia/Seoul). Future rule edits append new rows; the current
-- effective row is the one with the largest effective_from_month for the room.
insert into room_rule_versions (room_id, effective_from_month, rule_payload, created_by_user_id)
select r.id,
       to_char(now() at time zone 'Asia/Seoul', 'YYYY-MM'),
       jsonb_build_object('preset', 'DAILY_UPDATE', 'weekendInclude', true),
       r.owner_id
from rooms r
on conflict (room_id, effective_from_month) do nothing;

-- (15) backfill: room_point_pool row per room (starts at 0 points).
insert into room_point_pool (room_id, total)
select id, 0 from rooms
on conflict (room_id) do nothing;
