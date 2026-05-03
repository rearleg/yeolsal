package com.yeosal.api.room.chat;

/**
 * Whitelist mirroring the V7 {@code chk_chat_messages_kind} CHECK constraint.
 * USER messages are user-authored; the rest are written by the PR G system-
 * message hooks and render with the system-message visual treatment on the FE.
 */
public enum ChatMessageKind {
    USER,
    SYSTEM,
    GOAL,
    REFLECTION,
    MILESTONE,
    AUTO_LEAVE
}
