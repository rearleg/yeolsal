// Wire contract for the room-creation HTTP call. The picker UI is tested
// separately; this test pins the JSON body so an accidental rename of a
// CreateRoomInput field doesn't silently regress the BE handshake.

import * as SecureStore from "expo-secure-store";

jest.mock("../config", () => ({ API_BASE_URL: "https://api.test" }));

jest.mock("expo-secure-store", () => ({
  getItemAsync: jest.fn().mockResolvedValue("dev-token"),
  setItemAsync: jest.fn(),
  deleteItemAsync: jest.fn(),
}));

import { createRoom, type CreateRoomInput } from "../rooms";

const getItemAsyncMock = SecureStore.getItemAsync as jest.MockedFunction<
  typeof SecureStore.getItemAsync
>;

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}

describe("createRoom", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    getItemAsyncMock.mockResolvedValue("dev-token");
  });

  it("POSTs to /rooms with name, minDailyGoalDays, AND maxMembers in the body", async () => {
    const fetchMock = jest.fn().mockResolvedValueOnce(
      jsonResponse(200, {
        data: {
          id: 42,
          name: "방12",
          ownerId: 1,
          maxMembers: 12,
          minDailyGoalDays: 10,
        },
      }),
    );
    global.fetch = fetchMock as unknown as typeof fetch;

    const input: CreateRoomInput = {
      name: "방12",
      minDailyGoalDays: 10,
      maxMembers: 12,
    };
    const room = await createRoom(input);

    expect(room.maxMembers).toBe(12);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("https://api.test/rooms");
    expect(init.method).toBe("POST");
    const body = JSON.parse(init.body as string) as Record<string, unknown>;
    expect(body).toEqual({
      name: "방12",
      minDailyGoalDays: 10,
      maxMembers: 12,
    });
  });

  it("propagates the caller's maxMembers verbatim (no client-side clamping in the API layer)", async () => {
    const fetchMock = jest.fn().mockResolvedValueOnce(
      jsonResponse(200, {
        data: {
          id: 43,
          name: "방30",
          ownerId: 1,
          maxMembers: 30,
          minDailyGoalDays: 20,
        },
      }),
    );
    global.fetch = fetchMock as unknown as typeof fetch;

    await createRoom({ name: "방30", minDailyGoalDays: 20, maxMembers: 30 });

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    const body = JSON.parse(init.body as string) as Record<string, unknown>;
    expect(body.maxMembers).toBe(30);
  });
});
