-- V13: Story 5.2 — pending member-cap snapshot for next-month-only application.
-- Lazy promotion at RoomService.requireRoom propagates the pending value into
-- rooms.max_members once Asia/Seoul calendar month reaches effective_from_month.
alter table rooms
    add column if not exists pending_max_members smallint
        check (pending_max_members is null or (pending_max_members between 2 and 30)),
    add column if not exists pending_max_members_effective_from_month varchar(7);

-- A pending value is only valid when paired with its effective month (and
-- vice versa). The DB CHECK guards against a half-written state slipping in
-- via direct SQL or a future bug in the JPA setter pair.
alter table rooms
    drop constraint if exists chk_rooms_pending_cap_consistency,
    add constraint chk_rooms_pending_cap_consistency
        check (
            (pending_max_members is null
             and pending_max_members_effective_from_month is null)
         or (pending_max_members is not null
             and pending_max_members_effective_from_month is not null)
        );

alter table rooms
    drop constraint if exists chk_rooms_pending_cap_month_format,
    add constraint chk_rooms_pending_cap_month_format
        check (
            pending_max_members_effective_from_month is null
         or pending_max_members_effective_from_month ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'
        );
