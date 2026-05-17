package com.yeosal.api.revival;

/**
 * Source kinds admitted by the {@code revival_events.source} CHECK constraint
 * (V11 step 5). Mapped {@code @Enumerated(EnumType.STRING)} everywhere so
 * future reordering or insertion of values stays forward-compatible with
 * stored rows.
 *
 * <p>Story 3.1 only writes {@link #FREE_TICKET} and {@link #PERSONAL_POINTS}
 * (self-revival). Story 3.2 will introduce {@link #FRIEND_GIFT}; the enum
 * defines all three values upfront so the wire+persistence contracts stay
 * aligned across the epic.
 */
public enum RevivalSource {
    FREE_TICKET,
    PERSONAL_POINTS,
    FRIEND_GIFT
}
