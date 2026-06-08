// Story 7.3 — REST client for the per-(room, yearMonth) Final-3 poster.
// Powers useFinalThreePoster (AC2); never called directly by components per
// the project-context domain-hook rule (FE/src/lib/query/hooks/*).

import { apiRequest, ApiError, type ApiEnvelope } from "./client";

/**
 * Wire shape mirrors BE PosterDto (Story 7.1
 * BE/src/main/java/com/yeosal/api/ceremony/PosterDto.java) byte-for-byte.
 * generatedAt is ISO-8601 UTC (Java Instant#toString); pngUrl may be null
 * when PNG transcode failed at generation time — FinalThreeCard falls back
 * to inline SVG only and disables Kakao share.
 */
export interface FinalThreePosterDto {
  readonly roomId: number;
  readonly yearMonth: string;
  readonly svgText: string;
  readonly pngUrl: string | null;
  readonly generatedAt: string;
}

/**
 * GET /api/v1/rooms/{roomId}/posters/{yearMonth}
 *
 * <p>404 (POSTER_NOT_FOUND) → returns null (no poster exists OR the room
 * had zero ACTIVE survivors that month). 403 (FORBIDDEN, membership
 * stripped) → also returns null so the card simply self-hides instead
 * of polluting Sentry. Any other non-2xx surfaces as ApiError.
 */
export async function getPoster(
  roomId: number,
  yearMonth: string,
): Promise<FinalThreePosterDto | null> {
  try {
    const envelope = await apiRequest<ApiEnvelope<FinalThreePosterDto>>(
      `/rooms/${roomId}/posters/${yearMonth}`,
    );
    return envelope.data;
  } catch (err) {
    if (err instanceof ApiError && (err.status === 404 || err.status === 403)) {
      return null;
    }
    throw err;
  }
}
