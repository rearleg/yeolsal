import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderHook, waitFor } from "@testing-library/react-native";
import type { PropsWithChildren } from "react";
import * as api from "../../../../api/rooms";
import { qk } from "../../keys";
import { useCreateRoom } from "../rooms";

jest.mock("../../../../api/rooms", () => {
  const actual = jest.requireActual("../../../../api/rooms") as Record<string, unknown>;
  return {
    ...actual,
    createRoom: jest.fn(),
  };
});

// Toast emission is a side-effect we don't care about here — silence it.
jest.mock("../../../toast", () => ({ toast: { error: jest.fn(), info: jest.fn() } }));

// Haptics try to call into native modules that aren't loaded in jest-expo.
jest.mock("../../../../hooks/useHaptics", () => ({
  useHaptic: () => jest.fn(),
}));

const createRoomMock = api.createRoom as jest.MockedFunction<typeof api.createRoom>;

function makeClient() {
  return new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false } },
  });
}

function wrapperWith(client: QueryClient) {
  return function Wrapper({ children }: PropsWithChildren) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

describe("useCreateRoom", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("invalidates the rooms list query on a successful create", async () => {
    const client = makeClient();
    const invalidateSpy = jest.spyOn(client, "invalidateQueries");
    createRoomMock.mockResolvedValueOnce({
      id: 99,
      name: "방12",
      ownerId: 1,
      maxMembers: 12,
      minDailyGoalDays: 10,
      createdAt: "2026-05-14T10:00:00Z",
      pendingMaxMembers: null,
      pendingMaxMembersEffectiveFromMonth: null,
    });

    const { result } = renderHook(() => useCreateRoom(), {
      wrapper: wrapperWith(client),
    });

    result.current.mutate({ name: "방12", minDailyGoalDays: 10, maxMembers: 12 });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(createRoomMock).toHaveBeenCalledWith({
      name: "방12",
      minDailyGoalDays: 10,
      maxMembers: 12,
    });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: qk.rooms });
  });

  it("does NOT invalidate when the create call rejects", async () => {
    const client = makeClient();
    const invalidateSpy = jest.spyOn(client, "invalidateQueries");
    createRoomMock.mockRejectedValueOnce(new Error("boom"));

    const { result } = renderHook(() => useCreateRoom(), {
      wrapper: wrapperWith(client),
    });

    result.current.mutate({ name: "방X", minDailyGoalDays: 10, maxMembers: 12 });

    await waitFor(() => expect(result.current.isError).toBe(true));
    // Failure path must not nuke the cache; the user should still see their
    // previous list of rooms.
    expect(invalidateSpy).not.toHaveBeenCalled();
  });
});
