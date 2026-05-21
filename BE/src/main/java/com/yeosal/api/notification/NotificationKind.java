package com.yeosal.api.notification;

public enum NotificationKind {
    /** 08:00 KST cron — "오늘의 목표를 정해보세요". */
    GOAL_NUDGE,
    /** 21:30 KST cron — "오늘 회고를 남겨주세요". */
    REFLECTION_NUDGE,
    /** Event hook — friend posted a daily goal. Debounced 30 min per friend. */
    FRIEND_GOAL,
    /** Event hook — friend submitted a reflection. Debounced 30 min per friend. */
    FRIEND_REFLECTION,
    /** Event hook — someone sent the user a friend request. Debounced 30 min per requester. */
    FRIEND_REQUEST_RECEIVED,
    /** Event hook — the user's outgoing friend request was accepted. Debounced 30 min per addressee. */
    FRIEND_REQUEST_ACCEPTED,
    /** Daily evaluator idempotency gate — key format {@code "{prior_entry_date}:{user_id}"}. */
    SURVIVAL_STATE,
    /**
     * Story 2.2 — 09:00 KST cron, one push per spectator per day when their room had
     * activity yesterday. Idempotent via notification_log
     * (user_id, kind, key='{prior_date_kst}:{userId}:{roomId}').
     */
    SPECTATOR_DIGEST,
    /**
     * Story 3.5 — invitation-toned push when a friend sends a kudos message.
     * Rides {@code event_hooks_enabled}, debounce {@code Duration.ZERO}
     * (the V12 partial unique index is the dedupe authority — kudos are
     * already at most 1/day/(sender, target) so {@code NotificationLog}
     * provides audit only).
     */
    KUDOS_RECEIVED,
    /**
     * Story 3.2 — invitation-toned push fan-out to eligible givers when a
     * friend transitions to RED (FR-8.3.4). Rides {@code event_hooks_enabled}.
     * Per-giver dedup key:
     * {@code "{roomId}:{receiverUserId}:{eliminatedAtEpochMillis}"} so the
     * same RED elimination never fires two pushes to the same giver
     * (idempotent on listener retry / app restart / multi-instance deploy
     * — {@code notification_log} is the source of truth).
     */
    FRIEND_GIFT_PROMPT,
    /**
     * Story 3.2 — receiver donor-confirmation push when a friend successfully
     * gifts the receiver a revival (FR-8.3.5). Rides
     * {@code event_hooks_enabled}. Dedup key {@code "revival:{revivalEventId}"}
     * — a single revival can only be friend-gifted by one donor at a time
     * (partial-unique-index defence) so the key is naturally unique.
     */
    FRIEND_GIFT_RECEIVED
}
