package com.yeosal.api.room.chat;

import com.yeosal.api.common.ForbiddenException;

/**
 * Story 3.5 — kudos send attempted between two users without an
 * {@link com.yeosal.api.friend.FriendshipStatus#ACCEPTED} friendship
 * row in either direction. Mirrors the
 * {@link com.yeosal.api.common.SpectatorWriteForbiddenException}
 * typed-subclass pattern: extends {@link ForbiddenException} so the
 * parent's 403 status flows through if a future refactor re-routes the
 * handler, while the explicit handler at {@code ApiExceptionHandler}
 * still emits the precise 403 / {@link #CODE} pair.
 *
 * <p>The {@link #CODE} string is the FE-facing branch key; it MUST stay
 * {@code "NOT_FRIENDS"} so the Friend Gift Modal can surface
 * "친구가 된 멤버에게만 보낼 수 있어요" without parsing the message.
 */
public class NotFriendsException extends ForbiddenException {

    /** Stable wire code surfaced via {@code ApiErrorResponse.error.code}. */
    public static final String CODE = "NOT_FRIENDS";

    public NotFriendsException(String message) {
        super(message);
    }
}
