package com.yeosal.api.revival;

import java.time.Instant;

/**
 * Story 4.1 AC5 — wire shape for {@code GET /api/v1/rooms/{id}/points}.
 *
 * <p>Architecture §6.4 promised this resource: "room member → returns
 * {@code RoomPointPoolDto}". The DTO is intentionally narrow — just the
 * three fields a client needs to render the current pool number plus the
 * timestamp of the last mutation:
 *
 * <ul>
 *   <li>{@code roomId} — echoes the path-parameter so clients can stash
 *       the response in a per-room cache entry without re-parsing.</li>
 *   <li>{@code total} — never {@code null}; legacy rooms missing the V11
 *       backfill row coalesce to {@code 0} at the controller layer
 *       (defensive — AC5 "controller MUST defensively coalesce").</li>
 *   <li>{@code lastEventAt} — {@code null} when the pool has never been
 *       mutated. FE may surface it as a "마지막 회생: HH:mm" caption.</li>
 * </ul>
 *
 * <p>The DTO is NOT the same record as {@link PointPoolChangePayload}
 * (the STOMP frame) — the realtime payload also carries {@code delta},
 * {@code newTotal}, and {@code sourceRevivalEventId} which a REST snapshot
 * has no use for. Keeping the two records distinct preserves the option
 * to evolve either side independently.
 */
public record RoomPointPoolDto(long roomId, int total, Instant lastEventAt) {}
