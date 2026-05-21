package com.yeosal.api.revival;

/**
 * Story 3.2 AC10 — wire-format response for
 * {@code GET /api/v1/me/has-given-friend-gift}. A pure
 * "have-I-ever-sent-a-FRIEND_GIFT" boolean used by the FE as a fallback
 * for the M3.5 lifetime-1 overlay branch when the POST response field
 * doesn't reach the component (e.g. route refresh between mutation
 * completion and modal teardown).
 */
public record HasGivenFriendGiftDto(boolean has) {}
