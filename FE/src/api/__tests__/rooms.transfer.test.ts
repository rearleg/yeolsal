// Story 5.2 — wire contract for leader transfer HTTP call. Pins URL shape,
// HTTP verb, request body, and response envelope unwrap so an accidental
// field rename can't silently regress the BE handshake.

import * as SecureStore from "expo-secure-store";

jest.mock("../config", () => ({ API_BASE_URL: "https://api.test" }));

jest.mock("expo-secure-store", () => ({
  getItemAsync: jest.fn().mockResolvedValue("dev-token"),
  setItemAsync: jest.fn(),
  deleteItemAsync: jest.fn(),
}));

import { transferLeadership } from "../rooms";

const getItemAsyncMock = SecureStore.getItemAsync as jest.MockedFunction<
  typeof SecureStore.getItemAsync
>;

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

describe("rooms transfer-leadership wire contract", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    getItemAsyncMock.mockResolvedValue("dev-token");
  });

  it("transferLeadership POSTs /rooms/{id}/transfer-leadership with {targetUserId}", async () => {
    const fetchMock = jest.fn().mockResolvedValueOnce(
      jsonResponse(200, {
        data: {
          id: 42,
          name: "방",
          ownerId: 11,
          maxMembers: 12,
          minDailyGoalDays: 10,
          createdAt: "2026-04-15T03:14:00Z",
          pendingMaxMembers: null,
          pendingMaxMembersEffectiveFromMonth: null,
        },
      }),
    );
    global.fetch = fetchMock as unknown as typeof fetch;

    const room = await transferLeadership(42, { targetUserId: 11 });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("https://api.test/rooms/42/transfer-leadership");
    expect(init.method).toBe("POST");
    const body = JSON.parse(init.body as string) as Record<string, unknown>;
    expect(body).toEqual({ targetUserId: 11 });
    expect(room.ownerId).toBe(11);
  });

  it("transferLeadership propagates 409 INELIGIBLE_LEADER as ApiError", async () => {
    const fetchMock = jest.fn().mockResolvedValueOnce(
      jsonResponse(409, {
        error: { code: "INELIGIBLE_LEADER", message: "대상의 상태를 확인할 수 없어요." },
      }),
    );
    global.fetch = fetchMock as unknown as typeof fetch;

    await expect(
      transferLeadership(42, { targetUserId: 11 }),
    ).rejects.toMatchObject({ status: 409, code: "INELIGIBLE_LEADER" });
  });
});
