-- Phase 7-G follow-up #2: redefine MILESTONE as a daily progress
-- announcement instead of a once-a-month threshold-crossing event.
--
-- Product change driven by user feedback: every time a member files
-- both today's goal AND today's reflection, the group chat should
-- echo a progress line such as "alice님 15일 중 7일 완료!". That
-- means the dedup tuple becomes (room, user, date) rather than
-- (room, user, month) — V8's partial unique index goes, and the
-- per-day index takes its place.
--
-- payload->>'date' is text ("YYYY-MM-DD") whether the writer chose
-- a string or coerced from a JSON value, matching the same
-- text-equality behaviour as the previous index. The partial
-- predicate keeps the index small (only MILESTONE rows participate)
-- and lets the existing (room_id, id desc) cursor scan stay
-- untouched.
drop index if exists ux_chat_messages_milestone_room_user_month;

create unique index ux_chat_messages_milestone_room_user_date
    on chat_messages (
        room_id,
        ((payload ->> 'userId')),
        ((payload ->> 'date'))
    )
    where kind = 'MILESTONE';
