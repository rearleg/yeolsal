// Cross-room spectator detection helper (Epic 1 retro T5).
//
// Suggested in Story 1.7 Dev Notes as the building block Story 2.1 needs
// on day 1. The helper is pure — no I/O, no React. The wire shape mirrors
// the BE `MeSurvivalEntryDto` returned by `GET /api/v1/me/survival`
// (T4 — same retro batch).

import type { SurvivalState } from "../components/survival/types";

/**
 * One row from `GET /api/v1/me/survival` (Architecture §6.4). The user is
 * always self for every entry, so `status` is the raw value; the per-room
 * privacy mask does not apply here.
 *
 * Story 2.1 AC7 — `personalPoints` and `roomPointPool` power the spectator
 * `WalletPreview` block on the Today tab. Both default to `0` on the BE
 * when source data is missing.
 */
export interface MeSurvivalEntry {
  readonly roomId: number;
  readonly roomName: string;
  readonly status: SurvivalState;
  readonly personalPoints: number;
  readonly roomPointPool: number;
}

/**
 * Returns true iff the viewer is in at least one room AND every active
 * membership has status `SPECTATOR`. The "at least one" gate means a
 * brand-new user with no memberships is treated as "not playing", not
 * "spectator everywhere" — Story 2.1 uses this distinction to decide
 * between the empty-state CTA and the dim spectator treatment.
 */
export function isSpectatorAcrossAllRooms(
  entries: readonly MeSurvivalEntry[],
): boolean {
  if (entries.length === 0) return false;
  return entries.every((entry) => entry.status === "SPECTATOR");
}
