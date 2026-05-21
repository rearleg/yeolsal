// Story 3.3 FE-12 — useFriendGiftTargets hook tests (4 cases per AC7).

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook, waitFor } from "@testing-library/react-native";
import type { PropsWithChildren } from "react";
import * as targetsApi from "../../../../api/friendGiftTargets";
import { qk } from "../../keys";
import {
  useFriendGiftTargets,
  useFriendGiftTargetsForRoom,
} from "../friendGiftTargets";

jest.mock("../../../../api/friendGiftTargets", () => ({
  getFriendGiftTargets: jest.fn(),
}));

const getTargetsMock = targetsApi.getFriendGiftTargets as jest.MockedFunction<
  typeof targetsApi.getFriendGiftTargets
>;

function makeClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, staleTime: 0, gcTime: 60_000 },
      mutations: { retry: false },
    },
  });
}

function makeWrapper(client: QueryClient) {
  return function Wrapper({ children }: PropsWithChildren) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

describe("useFriendGiftTargets / useFriendGiftTargetsForRoom", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("empty data → returns []", async () => {
    getTargetsMock.mockResolvedValue([]);
    const { result } = renderHook(() => useFriendGiftTargets(), {
      wrapper: makeWrapper(makeClient()),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toEqual([]);
  });

  it("returns parsed entry shape verbatim", async () => {
    const data = [
      {
        roomId: 42,
        roomName: "Room",
        eligibleCount: 1,
        friends: [
          {
            userId: 11,
            nickname: "BBB",
            status: "RED" as const,
            eliminatedAt: "2026-05-18T03:14:15Z",
          },
        ],
      },
    ];
    getTargetsMock.mockResolvedValue(data);
    const { result } = renderHook(() => useFriendGiftTargets(), {
      wrapper: makeWrapper(makeClient()),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toEqual(data);
  });

  it("useFriendGiftTargetsForRoom — filters by roomId", async () => {
    getTargetsMock.mockResolvedValue([
      { roomId: 1, roomName: "A", eligibleCount: 1, friends: [] },
      { roomId: 2, roomName: "B", eligibleCount: 2, friends: [] },
    ]);
    const client = makeClient();
    const { result } = renderHook(() => useFriendGiftTargetsForRoom(2), {
      wrapper: makeWrapper(client),
    });
    await waitFor(() =>
      expect(client.getQueryData(qk.friendGiftTargets)).toBeDefined(),
    );
    expect(result.current?.roomId).toBe(2);
    expect(result.current?.eligibleCount).toBe(2);
  });

  it("queryKey is qk.friendGiftTargets — invalidation flows through", async () => {
    getTargetsMock.mockResolvedValue([]);
    const client = makeClient();
    const { result } = renderHook(() => useFriendGiftTargets(), {
      wrapper: makeWrapper(client),
    });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    const firstCalls = getTargetsMock.mock.calls.length;

    await act(async () => {
      await client.invalidateQueries({ queryKey: qk.friendGiftTargets });
    });
    await waitFor(() =>
      expect(getTargetsMock.mock.calls.length).toBeGreaterThan(firstCalls),
    );
  });
});
