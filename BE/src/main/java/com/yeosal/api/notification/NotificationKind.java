package com.yeosal.api.notification;

public enum NotificationKind {
    /** 08:00 KST cron — "오늘의 목표를 정해보세요". */
    GOAL_NUDGE,
    /** 21:30 KST cron — "오늘 회고를 남겨주세요". */
    REFLECTION_NUDGE,
    /** Event hook — friend posted a daily goal. Debounced 30 min per friend. */
    FRIEND_GOAL,
    /** Event hook — friend submitted a reflection. Debounced 30 min per friend. */
    FRIEND_REFLECTION
}
