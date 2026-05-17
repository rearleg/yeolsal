package com.yeosal.api.room.chat;

/**
 * Whitelist mirroring the {@code chk_chat_messages_kind} CHECK constraint
 * (V7 plus the V12 widening for KUDOS). USER messages are user-authored;
 * GOAL/REFLECTION/MILESTONE/AUTO_LEAVE/SYSTEM rows are written by the
 * system-speech hooks with a {@code null sender_user_id}.
 *
 * <p>Story 3.5 — KUDOS rows have a non-null {@code sender_user_id} (the
 * only non-USER kind that does) and a {@code payload} of shape
 * {@code {senderUserId, targetUserId, message}} (ids as JSON strings,
 * V8/V9 milestone-dedup convention). Idempotency lives in the V12 partial
 * unique index {@code ux_kudos_one_per_day} on
 * {@code (sender_user_id, payload->>'targetUserId', KST date)}.
 */
public enum ChatMessageKind {
    USER,
    SYSTEM,
    GOAL,
    REFLECTION,
    MILESTONE,
    AUTO_LEAVE,
    KUDOS
}
