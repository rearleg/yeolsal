package com.yeosal.api.room.chat;

import java.time.Instant;

/**
 * Story 3.5 — realtime payload emitted to {@code /topic/rooms.{roomId}.kudos}
 * after a kudos write commits. Architecture §4.14 server-side privacy
 * (no client filtering): the {@code JwtChannelInterceptor} regex
 * permits SUBSCRIBE only to authenticated room members, so this payload
 * is safe to broadcast to the room topic.
 *
 * <p>{@code messagePreview} is the first 40 characters of the rendered
 * chat-row body — protects the unencrypted broker channel from any
 * future longer-body change while keeping the room-awareness frame
 * informative.
 */
public record KudosSentPayload(
        long senderId,
        long targetId,
        String messagePreview,
        Instant occurredAt) {}
