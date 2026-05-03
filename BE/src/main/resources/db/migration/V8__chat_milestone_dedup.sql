-- Phase 7-G follow-up: atomic once-per-(room, user, month) MILESTONE
-- announcement.
--
-- The reflection-time MILESTONE hook (ChatService.publishMilestonesForActor)
-- runs in REQUIRES_NEW per actor and fans out to every room the actor is
-- a member of. Two concurrent reflections — or a retried afterCommit
-- callback — could otherwise both observe "no existing milestone" and
-- both insert a row. JSONB payload values aren't covered by any other
-- index, so we add a partial unique expression index keyed exactly on
-- the dedup tuple.
--
-- payload->>'userId' is text-for-text whether the writer chose a JSON
-- string or number, which keeps the constraint stable if a later writer
-- normalizes userId to a number. The partial predicate keeps the index
-- small (only MILESTONE rows participate) and lets the existing
-- (room_id, id desc) cursor scan stay untouched.
create unique index ux_chat_messages_milestone_room_user_month
    on chat_messages (
        room_id,
        ((payload ->> 'userId')),
        ((payload ->> 'month'))
    )
    where kind = 'MILESTONE';
