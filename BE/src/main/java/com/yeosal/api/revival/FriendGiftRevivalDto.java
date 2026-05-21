package com.yeosal.api.revival;

import java.time.Instant;

/**
 * Wire-format response payload for {@code POST /api/v1/rooms/{id}/revivals/gifts}
 * (Story 3.2 AC1).
 *
 * <p>Distinct from {@link RevivalEventDto} because the friend-gift response
 * carries two extra fields the FE needs:
 *
 * <ul>
 *   <li>{@code isFirstEverFriendGiftSend} — drives the M3.5 lifetime-1
 *       overlay branch on the giver's modal (UX line 1552-1554; Story 3.2
 *       AC6). The BE computes this inside the @Transactional boundary as
 *       an EXCLUDING-self exists check against {@code revival_events}; the
 *       FE just reads the boolean.</li>
 *   <li>{@code receiverNickname} — feeds the donor-side success toast
 *       "{nickname}에게 회생권을 선물했어요" without an extra round-trip
 *       (Story 3.2 AC4).</li>
 * </ul>
 *
 * <p>{@code source} is serialized as a String (the enum's
 * {@link RevivalSource#name()}) so the wire contract stays stable across
 * future enum additions — mirrors {@link RevivalEventDto}.
 */
public record FriendGiftRevivalDto(
        long revivalEventId,
        String source,
        int pointsSpent,
        int roomPointPoolAfter,
        Instant occurredAt,
        boolean isFirstEverFriendGiftSend,
        String receiverNickname) {}
