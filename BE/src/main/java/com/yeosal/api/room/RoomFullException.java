package com.yeosal.api.room;

/**
 * Thrown when {@link RoomService#joinByCode(com.yeosal.api.user.User, String)}
 * finds the target room at {@code max_members} capacity. Mapped to 409
 * CONFLICT with error code {@code ROOM_FULL} by
 * {@code com.yeosal.api.common.ApiExceptionHandler}.
 *
 * <p>Distinct from {@code BadRequestException} because (a) the client can
 * retry only by joining a different room — not by changing the request — and
 * (b) the FE branches on the precise wire code to render a calmer
 * "방이 가득 찼어요" toast instead of the generic 400 validation message.
 *
 * <p>Mirrors the typed-exception pattern established by
 * {@link IneligibleLeaderException}: both are member-state preconditions
 * that the caller cannot resolve by re-sending the same request, so a
 * 409 CONFLICT is the most accurate status.
 */
public class RoomFullException extends RuntimeException {
    public RoomFullException(String message) {
        super(message);
    }
}
