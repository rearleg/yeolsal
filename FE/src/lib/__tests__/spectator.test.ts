// Unit tests for the cross-room spectator helper (Epic 1 retro T5).
// The helper is pure, so the suite is small and exhaustive over the
// branch table: empty / non-empty x all-spectator / mixed / no-spectator.

import {
  isSpectatorAcrossAllRooms,
  type MeSurvivalEntry,
} from "../spectator";

const entry = (
  roomId: number,
  status: MeSurvivalEntry["status"],
  roomName = `room-${roomId}`,
): MeSurvivalEntry => ({
  roomId,
  roomName,
  status,
  personalPoints: 0,
  roomPointPool: 0,
  freeRevivalTicketUsed: false,
});

describe("isSpectatorAcrossAllRooms", () => {
  it("returns false for zero memberships (treated as 'not playing', not 'spectator')", () => {
    expect(isSpectatorAcrossAllRooms([])).toBe(false);
  });

  it("returns true when the only membership is SPECTATOR", () => {
    expect(isSpectatorAcrossAllRooms([entry(1, "SPECTATOR")])).toBe(true);
  });

  it("returns true when every membership is SPECTATOR (multi-room)", () => {
    expect(
      isSpectatorAcrossAllRooms([
        entry(1, "SPECTATOR"),
        entry(2, "SPECTATOR"),
        entry(3, "SPECTATOR"),
      ]),
    ).toBe(true);
  });

  it("returns false when at least one membership is ACTIVE", () => {
    expect(
      isSpectatorAcrossAllRooms([
        entry(1, "SPECTATOR"),
        entry(2, "ACTIVE"),
        entry(3, "SPECTATOR"),
      ]),
    ).toBe(false);
  });

  it("returns false when at least one membership is YELLOW (in-grace)", () => {
    expect(
      isSpectatorAcrossAllRooms([
        entry(1, "SPECTATOR"),
        entry(2, "YELLOW"),
      ]),
    ).toBe(false);
  });

  it("returns false when at least one membership is RED (eliminated, not yet spectator)", () => {
    expect(
      isSpectatorAcrossAllRooms([
        entry(1, "SPECTATOR"),
        entry(2, "RED"),
      ]),
    ).toBe(false);
  });

  it("returns false when no membership is SPECTATOR", () => {
    expect(
      isSpectatorAcrossAllRooms([
        entry(1, "ACTIVE"),
        entry(2, "YELLOW"),
        entry(3, "RED"),
      ]),
    ).toBe(false);
  });

  it("does not mutate the input array", () => {
    const input: readonly MeSurvivalEntry[] = [
      entry(1, "SPECTATOR"),
      entry(2, "SPECTATOR"),
    ];
    const snapshot = JSON.stringify(input);
    isSpectatorAcrossAllRooms(input);
    expect(JSON.stringify(input)).toBe(snapshot);
  });
});
