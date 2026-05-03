-- Phase 4-F: per-room chat baseline.
--
-- Decisions:
--   * One row per message, regardless of who sent it. USER messages set
--     sender_user_id; SYSTEM/GOAL/REFLECTION/MILESTONE/AUTO_LEAVE messages
--     written by PR G hooks leave it NULL because the actor isn't always
--     the message author (e.g. a milestone for member X is "system" speech).
--   * Hard-deleting a user nulls their authorship (ON DELETE SET NULL); the
--     room cascade still applies.
--   * payload is a free-form JSONB blob: PR G uses {actorUserId, dailyEntryId,
--     date} for system messages; the FE always treats it as opaque metadata
--     and never as a message-rendering source of truth.
--   * The (room_id, id desc) index supports cursor-based pagination — given
--     a room and a "before this id" anchor, Postgres scans backwards through
--     the index without sorting.

create table chat_messages (
    id             bigserial primary key,
    room_id        bigint not null references rooms(id) on delete cascade,
    sender_user_id bigint references users(id) on delete set null,
    kind           varchar(20) not null,
    body           text not null,
    payload        jsonb not null default '{}'::jsonb,
    created_at     timestamptz not null default now(),
    constraint chk_chat_messages_kind
        check (kind in ('USER', 'SYSTEM', 'GOAL', 'REFLECTION', 'MILESTONE', 'AUTO_LEAVE'))
);

create index idx_chat_messages_room_id_id_desc
    on chat_messages (room_id, id desc);
