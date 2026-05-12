package com.yeosal.api.survival;

/**
 * Membership lifecycle for a (room, user) pair (Architecture §4.1).
 *
 * <p>Mapped with {@code @Enumerated(EnumType.STRING)} everywhere — never
 * {@code ORDINAL} — so future reordering or insertion of values stays
 * forward-compatible with stored rows. Matches the
 * {@code survival_state.status} CHECK constraint in V11.
 */
public enum SurvivalStatus {
    ACTIVE,
    YELLOW,
    RED,
    SPECTATOR
}
