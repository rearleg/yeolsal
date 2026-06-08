// Story 7.3 AC2 + AC10 — wire contract for GET /api/v1/rooms/{roomId}/posters/
// {yearMonth}. Pins URL shape, response unwrap, 404/403 → null fallthrough,
// and ApiError propagation on 5xx so a future BE error-envelope change can't
// silently regress the FinalThreeCard's self-hide path.

import * as SecureStore from "expo-secure-store";

jest.mock("../config", () => ({ API_BASE_URL: "https://api.test" }));

jest.mock("expo-secure-store", () => ({
  getItemAsync: jest.fn().mockResolvedValue("dev-token"),
  setItemAsync: jest.fn(),
  deleteItemAsync: jest.fn(),
}));

import { getPoster, type FinalThreePosterDto } from "../posters";

const getItemAsyncMock = SecureStore.getItemAsync as jest.MockedFunction<
  typeof SecureStore.getItemAsync
>;

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

describe("getPoster wire contract", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    getItemAsyncMock.mockResolvedValue("dev-token");
  });

  it("GETs /rooms/{roomId}/posters/{yearMonth} and unwraps envelope.data", async () => {
    const dto: FinalThreePosterDto = {
      roomId: 42,
      yearMonth: "2026-05",
      svgText: "<svg viewBox=\"0 0 800 420\"/>",
      pngUrl: "https://cdn.test/posters/42-2026-05.png",
      generatedAt: "2026-06-01T06:30:00Z",
    };
    const fetchMock = jest.fn().mockResolvedValueOnce(
      jsonResponse(200, { data: dto }),
    );
    global.fetch = fetchMock as unknown as typeof fetch;

    const result = await getPoster(42, "2026-05");

    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("https://api.test/rooms/42/posters/2026-05");
    expect(init.method ?? "GET").toBe("GET");
    expect(result).toEqual(dto);
  });

  it("returns null on 404 POSTER_NOT_FOUND so the consumer can self-hide", async () => {
    const fetchMock = jest.fn().mockResolvedValueOnce(
      jsonResponse(404, {
        error: { code: "POSTER_NOT_FOUND", message: "no poster" },
      }),
    );
    global.fetch = fetchMock as unknown as typeof fetch;

    await expect(getPoster(42, "2026-05")).resolves.toBeNull();
  });

  it("returns null on 403 FORBIDDEN (membership stripped between cache hit and refresh)", async () => {
    const fetchMock = jest.fn().mockResolvedValueOnce(
      jsonResponse(403, {
        error: { code: "FORBIDDEN", message: "not a member" },
      }),
    );
    global.fetch = fetchMock as unknown as typeof fetch;

    await expect(getPoster(42, "2026-05")).resolves.toBeNull();
  });

  it("propagates non-404/403 errors as ApiError", async () => {
    const fetchMock = jest.fn().mockResolvedValueOnce(
      jsonResponse(500, {
        error: { code: "INTERNAL", message: "boom" },
      }),
    );
    global.fetch = fetchMock as unknown as typeof fetch;

    await expect(getPoster(42, "2026-05")).rejects.toMatchObject({
      status: 500,
      code: "INTERNAL",
    });
  });
});
