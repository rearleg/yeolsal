// Story 5.1 — wire contract for the per-room rule HTTP calls. Pins URL
// shape, HTTP verb, request body, and response envelope unwrap so an
// accidental field rename can't silently regress the BE handshake.

import * as SecureStore from "expo-secure-store";

jest.mock("../config", () => ({ API_BASE_URL: "https://api.test" }));

jest.mock("expo-secure-store", () => ({
  getItemAsync: jest.fn().mockResolvedValue("dev-token"),
  setItemAsync: jest.fn(),
  deleteItemAsync: jest.fn(),
}));

import { getRoomRule, updateRoomRule } from "../rooms";

const getItemAsyncMock = SecureStore.getItemAsync as jest.MockedFunction<
  typeof SecureStore.getItemAsync
>;

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

describe("rooms rule wire contract", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    getItemAsyncMock.mockResolvedValue("dev-token");
  });

  it("getRoomRule GETs /rooms/{id}/rule and unwraps the envelope", async () => {
    const fetchMock = jest.fn().mockResolvedValueOnce(
      jsonResponse(200, {
        data: {
          current: {
            id: 500,
            preset: "DAILY_UPDATE",
            weekendInclude: true,
            effectiveFromMonth: "2026-04",
            createdByUserId: 7,
            createdAt: "2026-04-15T03:14:00Z",
          },
          pending: {
            id: 700,
            preset: "DAILY_UPDATE",
            weekendInclude: false,
            effectiveFromMonth: "2026-05",
            createdByUserId: 7,
            createdAt: "2026-04-16T10:00:00Z",
          },
        },
      }),
    );
    global.fetch = fetchMock as unknown as typeof fetch;

    const state = await getRoomRule(42);

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("https://api.test/rooms/42/rule");
    expect(init.method ?? "GET").toBe("GET");
    expect(state.current.effectiveFromMonth).toBe("2026-04");
    expect(state.pending?.weekendInclude).toBe(false);
  });

  it("updateRoomRule PATCHes /rooms/{id}/rule with {preset, weekendInclude} and returns the row DTO", async () => {
    const fetchMock = jest.fn().mockResolvedValueOnce(
      jsonResponse(200, {
        data: {
          id: 1001,
          preset: "DAILY_UPDATE",
          weekendInclude: false,
          effectiveFromMonth: "2026-05",
          createdByUserId: 7,
          createdAt: "2026-04-15T03:14:00Z",
        },
      }),
    );
    global.fetch = fetchMock as unknown as typeof fetch;

    const dto = await updateRoomRule(42, { preset: "DAILY_UPDATE", weekendInclude: false });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("https://api.test/rooms/42/rule");
    expect(init.method).toBe("PATCH");
    const body = JSON.parse(init.body as string) as Record<string, unknown>;
    expect(body).toEqual({ preset: "DAILY_UPDATE", weekendInclude: false });
    expect(dto.effectiveFromMonth).toBe("2026-05");
    expect(dto.weekendInclude).toBe(false);
  });
});
