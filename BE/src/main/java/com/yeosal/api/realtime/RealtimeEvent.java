package com.yeosal.api.realtime;

/**
 * Envelope for realtime frames the BE pushes to clients over STOMP.
 *
 * <p>The {@code kind} discriminator lets a single subscription multiplex
 * different payload shapes — e.g. the {@code /user/queue/notifications}
 * destination carries {@code FRIEND_REQUEST_RECEIVED},
 * {@code FRIEND_REQUEST_ACCEPTED}, and any future user-scoped event.
 *
 * <p>Kind values mirror the existing {@code NotificationKind} enum names
 * where possible, so the FE's {@code routeInvalidation} switch can stay
 * a single source of truth across both the push-notification and
 * realtime delivery paths.
 *
 * @param kind     stable string identifier (e.g. {@code "FRIEND_REQUEST_RECEIVED"})
 * @param payload  JSON-serialisable payload; may be {@code null} for kind-only frames
 */
public record RealtimeEvent(String kind, Object payload) {}
