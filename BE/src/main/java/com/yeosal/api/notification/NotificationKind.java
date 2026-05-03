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
    FRIEND_REQUEST_ACCEPTED
}
