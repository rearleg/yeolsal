import type { QueryClient } from "@tanstack/react-query";
import { routeInvalidation } from "../notifications";

function makeQcSpy(): {
  qc: QueryClient;
  invalidate: jest.Mock;
} {
  const invalidate = jest.fn();
  // Cast minimal stub — routeInvalidation only touches invalidateQueries.
  const qc = { invalidateQueries: invalidate } as unknown as QueryClient;
  return { qc, invalidate };
}

describe("routeInvalidation", () => {
  it("FRIEND_GOAL invalidates feed only", () => {
    const { qc, invalidate } = makeQcSpy();
    routeInvalidation(qc, "FRIEND_GOAL");
    expect(invalidate).toHaveBeenCalledTimes(1);
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["feed"] });
  });

  it("FRIEND_REFLECTION invalidates feed only", () => {
    const { qc, invalidate } = makeQcSpy();
    routeInvalidation(qc, "FRIEND_REFLECTION");
    expect(invalidate).toHaveBeenCalledTimes(1);
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["feed"] });
  });

  it("FRIEND_REQUEST_RECEIVED invalidates friendRequests + feed", () => {
    const { qc, invalidate } = makeQcSpy();
    routeInvalidation(qc, "FRIEND_REQUEST_RECEIVED");
    expect(invalidate).toHaveBeenCalledTimes(2);
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["friendRequests"] });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["feed"] });
  });

  it("FRIEND_REQUEST_ACCEPTED invalidates friendRequests + feed", () => {
    const { qc, invalidate } = makeQcSpy();
    routeInvalidation(qc, "FRIEND_REQUEST_ACCEPTED");
    expect(invalidate).toHaveBeenCalledTimes(2);
  });

  it("MILESTONE invalidates room messages by predicate", () => {
    const { qc, invalidate } = makeQcSpy();
    routeInvalidation(qc, "MILESTONE");
    expect(invalidate).toHaveBeenCalledTimes(1);
    const arg = invalidate.mock.calls[0][0];
    expect(arg.predicate({ queryKey: ["rooms", 42, "messages"] })).toBe(true);
    expect(arg.predicate({ queryKey: ["feed", "2026-05-03"] })).toBe(false);
    expect(arg.predicate({ queryKey: ["rooms", 42, "members"] })).toBe(false);
  });

  // Story 3.5 — KUDOS_RECEIVED uses the same predicate as MILESTONE so the
  // receiver's chat list refreshes when the kudos push arrives.
  it("KUDOS_RECEIVED invalidates room messages by predicate", () => {
    const { qc, invalidate } = makeQcSpy();
    routeInvalidation(qc, "KUDOS_RECEIVED");
    expect(invalidate).toHaveBeenCalledTimes(1);
    const arg = invalidate.mock.calls[0][0];
    expect(arg.predicate({ queryKey: ["rooms", 42, "messages"] })).toBe(true);
    expect(arg.predicate({ queryKey: ["feed", "2026-05-03"] })).toBe(false);
    expect(arg.predicate({ queryKey: ["rooms", 42, "members"] })).toBe(false);
  });

  it("GOAL_NUDGE / REFLECTION_NUDGE are self-nudges and invalidate nothing", () => {
    for (const kind of ["GOAL_NUDGE", "REFLECTION_NUDGE"] as const) {
      const { qc, invalidate } = makeQcSpy();
      routeInvalidation(qc, kind);
      expect(invalidate).not.toHaveBeenCalled();
    }
  });

  it("SPECTATOR_DIGEST is a passive room summary and invalidates nothing", () => {
    const { qc, invalidate } = makeQcSpy();
    routeInvalidation(qc, "SPECTATOR_DIGEST");
    expect(invalidate).not.toHaveBeenCalled();
  });

  it("missing kind falls back to broad invalidation (feed + requests + rooms)", () => {
    const { qc, invalidate } = makeQcSpy();
    routeInvalidation(qc, undefined);
    expect(invalidate).toHaveBeenCalledTimes(3);
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["feed"] });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ["friendRequests"] });
  });

  it("unknown kind also falls back to broad invalidation", () => {
    const { qc, invalidate } = makeQcSpy();
    routeInvalidation(qc, "WHATEVER_NEW_KIND");
    expect(invalidate).toHaveBeenCalledTimes(3);
  });
});
