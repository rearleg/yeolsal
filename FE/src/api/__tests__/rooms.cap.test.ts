// Story 5.2 — wire contract for the per-room member-cap HTTP call. Pins
// URL shape, HTTP verb, request body, and response envelope unwrap so an
// accidental field rename can't silently regress the BE handshake.

import * as SecureStore from "expo-secure-store";

jest.mock("../config", () => ({ API_BASE_URL: "https://api.test" }));

jest.mock("expo-secure-store", () => ({
  getItemAsync: jest.fn().mockResolvedValue("dev-token"),
  setItemAsync: jest.fn(),
  deleteItemAsync: jest.fn(),
}));

import { updateMemberCap } from "../rooms";

const getItemAsyncMock = SecureStore.getItemAsync as jest.MockedFunction<
  typeof SecureStore.getItemAsync
>;

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

describe("rooms cap wire contract", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    getItemAsyncMock.mockResolvedValue("dev-token");
  });

  it("updateMemberCap PATCHes /rooms/{id}/members/cap with {maxMembers} and returns Room", async () => {
    const fetchMock = jest.fn().mockResolvedValueOnce(
      jsonResponse(200, {
        data: {
          id: 42,
          name: "방",
          ownerId: 7,
          maxMembers: 12,
          minDailyGoalDays: 10,
          createdAt: "2026-04-15T03:14:00Z",
          pendingMaxMembers: 20,
          pendingMaxMembersEffectiveFromMonth: "2026-05",
        },
      }),
    );
    global.fetch = fetchMock as unknown as typeof fetch;

    const room = await updateMemberCap(42, { maxMembers: 20 });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("https://api.test/rooms/42/members/cap");
    expect(init.method).toBe("PATCH");
    const body = JSON.parse(init.body as string) as Record<string, unknown>;
    expect(body).toEqual({ maxMembers: 20 });
    expect(room.pendingMaxMembers).toBe(20);
    expect(room.pendingMaxMembersEffectiveFromMonth).toBe("2026-05");
  });

  it("updateMemberCap propagates 400 VALIDATION as ApiError", async () => {
    const fetchMock = jest.fn().mockResolvedValueOnce(
      jsonResponse(400, {
        error: { code: "VALIDATION", message: "정원은 2에서 30 사이여야 합니다." },
      }),
    );
    global.fetch = fetchMock as unknown as typeof fetch;

    await expect(updateMemberCap(42, { maxMembers: 31 })).rejects.toMatchObject({
      status: 400,
      code: "VALIDATION",
    });
  });
});
