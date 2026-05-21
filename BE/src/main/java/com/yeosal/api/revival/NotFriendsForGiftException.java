package com.yeosal.api.revival;

import com.yeosal.api.common.ForbiddenException;

/**
 * Story 3.2 — friend-gift attempted between two users without an
 * {@link com.yeosal.api.friend.FriendshipStatus#ACCEPTED} friendship
 * row in either direction (FR-8.3.3, epics line 493).
 *
 * <p>Distinct exception class from
 * {@code com.yeosal.api.room.chat.NotFriendsException} (Story 3.5's
 * kudos-only friendship gate) because the wire codes MUST differ —
 * {@code NOT_FRIENDS} is locked to the kudos path, {@code NOT_FRIENDS_FOR_GIFT}
 * surfaces the friend-gift path. The FE Friend Gift Modal branches on
 * both codes (the kudos secondary CTA may emit {@code NOT_FRIENDS}; the
 * primary friend-gift CTA emits this code) so the two contracts MUST
 * stay separable.
 *
 * <p>Extends {@link ForbiddenException} so the parent's 403 status flows
 * through if a future refactor re-routes the handler, while the explicit
 * handler at {@code ApiExceptionHandler} still emits the precise 403 /
 * {@link #CODE} pair. Mirrors the
 * {@link com.yeosal.api.common.SpectatorWriteForbiddenException}
 * typed-subclass pattern.
 */
public class NotFriendsForGiftException extends ForbiddenException {

    /** Stable wire code surfaced via {@code ApiErrorResponse.error.code}. */
    public static final String CODE = "NOT_FRIENDS_FOR_GIFT";

    public NotFriendsForGiftException(String message) {
        super(message);
    }
}
