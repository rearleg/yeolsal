-- Phase 4-A: rooms (small invite-coded groups, max 8 members).
--
-- Decisions:
--   * Multiple memberships per user allowed (multi-room).
--   * Invite codes are short and URL-safe (8-char alphabet); active codes
--     must be globally unique while not revoked.
--   * Membership uniqueness: at most one row per (room_id, user_id).
--
-- Visibility rule (Phase 4-A.5): two users can view each other's profile
-- and grass when they share at least one room (in addition to the legacy
-- friendship rule). Auto-default rooms (Phase 4-A.6) seed memberships from
-- existing accepted-friendship connected components on first boot.

create table rooms (
    id          bigserial primary key,
    name        varchar(80) not null,
    owner_id    bigint not null references users(id),
    max_members smallint not null default 8,
    created_at  timestamptz not null default now()
);

create index idx_rooms_owner on rooms (owner_id);

create table room_members (
    id        bigserial primary key,
    room_id   bigint not null references rooms(id) on delete cascade,
    user_id   bigint not null references users(id),
    role      varchar(20) not null default 'MEMBER',
    joined_at timestamptz not null default now(),
    unique (room_id, user_id)
);

create index idx_room_members_user on room_members (user_id);

create table room_invites (
    id           bigserial primary key,
    room_id      bigint not null references rooms(id) on delete cascade,
    code         varchar(32) not null,
    created_by   bigint not null references users(id),
    expires_at   timestamptz,
    revoked_at   timestamptz,
    created_at   timestamptz not null default now()
);

-- Each non-revoked code must be globally unique while active. Revoked codes
-- can collide with future codes; this matches the redeem flow which only
-- considers rows where revoked_at is null and expires_at is null/future.
create unique index idx_room_invites_active_code
    on room_invites (code)
    where revoked_at is null;

create index idx_room_invites_room on room_invites (room_id);
