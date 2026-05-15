// Story 2.1 FE-5.1 — survival query + derived hooks.
//
// Mocks the api/survival client so no real network is hit. Wraps each hook
// in a QueryClientProvider per the project-context FE testing rule.

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook, waitFor } from "@testing-library/react-native";
import type { PropsWithChildren } from "react";
import * as survivalApi from "../../../../api/survival";
import type { MeSurvivalEntry } from "../../../spectator";
import { qk } from "../../keys";
import {
  useCurrentRoomSurvivalState,
  useIsSpectatorEverywhere,
  useMeSurvivalQuery,
} from "../survival";

jest.mock("../../../../api/survival", () => ({
  getMeSurvival: jest.fn(),
}));

const getMeSurvivalMock =
  survivalApi.getMeSurvival as jest.MockedFunction<typeof survivalApi.getMeSurvival>;

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
});

describe("useMeSurvivalQuery", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("resolves with the rows returned by getMeSurvival()", async () => {
    const rows = [entry(1, "ACTIVE"), entry(2, "SPECTATOR")];
    getMeSurvivalMock.mockResolvedValue(rows);
    const client = makeClient();

    const { result } = renderHook(() => useMeSurvivalQuery(), {
      wrapper: makeWrapper(client),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toEqual(rows);
    expect(getMeSurvivalMock).toHaveBeenCalledTimes(1);
  });

  it("uses queryKey qk.meSurvival so invalidation flows through the singleton cache", async () => {
    getMeSurvivalMock.mockResolvedValue([entry(1, "ACTIVE")]);
    const client = makeClient();

    renderHook(() => useMeSurvivalQuery(), { wrapper: makeWrapper(client) });

    await waitFor(() => {
      expect(client.getQueryData(qk.meSurvival)).toBeDefined();
    });
  });
});

describe("useCurrentRoomSurvivalState", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("returns the entry whose roomId matches", async () => {
    getMeSurvivalMock.mockResolvedValue([
      entry(11, "ACTIVE"),
      entry(12, "SPECTATOR"),
    ]);
    const client = makeClient();

    const { result } = renderHook(() => useCurrentRoomSurvivalState(12), {
      wrapper: makeWrapper(client),
    });

    await waitFor(() => expect(result.current).not.toBeNull());
    expect(result.current?.status).toBe("SPECTATOR");
  });

  it("returns null when the user is not a member of the room", async () => {
    getMeSurvivalMock.mockResolvedValue([entry(11, "ACTIVE")]);
    const client = makeClient();

    const { result } = renderHook(() => useCurrentRoomSurvivalState(99), {
      wrapper: makeWrapper(client),
    });

    await waitFor(() => expect(getMeSurvivalMock).toHaveBeenCalled());
    expect(result.current).toBeNull();
  });

  it("returns null when roomId is null (caller has no room in context)", () => {
    const client = makeClient();
    const { result } = renderHook(() => useCurrentRoomSurvivalState(null), {
      wrapper: makeWrapper(client),
    });
    expect(result.current).toBeNull();
  });

  it("flips when the cache is updated with a SurvivalStateChange (re-resolves to ACTIVE)", async () => {
    getMeSurvivalMock.mockResolvedValue([entry(11, "SPECTATOR")]);
    const client = makeClient();

    const { result } = renderHook(() => useCurrentRoomSurvivalState(11), {
      wrapper: makeWrapper(client),
    });
    await waitFor(() => expect(result.current?.status).toBe("SPECTATOR"));

    // Simulate the STOMP frame handler's invalidation path (AC6).
    act(() => {
      client.setQueryData(qk.meSurvival, [entry(11, "ACTIVE")]);
    });

    await waitFor(() => expect(result.current?.status).toBe("ACTIVE"));
  });
});

describe("useIsSpectatorEverywhere", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("returns true only when every membership is SPECTATOR (multi-room)", async () => {
    getMeSurvivalMock.mockResolvedValue([
      entry(1, "SPECTATOR"),
      entry(2, "SPECTATOR"),
    ]);
    const client = makeClient();

    const { result } = renderHook(() => useIsSpectatorEverywhere(), {
      wrapper: makeWrapper(client),
    });

    await waitFor(() => expect(result.current).toBe(true));
  });

  it("returns false when at least one membership is ACTIVE", async () => {
    getMeSurvivalMock.mockResolvedValue([
      entry(1, "SPECTATOR"),
      entry(2, "ACTIVE"),
    ]);
    const client = makeClient();

    const { result } = renderHook(() => useIsSpectatorEverywhere(), {
      wrapper: makeWrapper(client),
    });

    await waitFor(() => expect(getMeSurvivalMock).toHaveBeenCalled());
    expect(result.current).toBe(false);
  });

  it("returns false when the user has zero memberships (empty-state, not spectator)", async () => {
    getMeSurvivalMock.mockResolvedValue([]);
    const client = makeClient();

    const { result } = renderHook(() => useIsSpectatorEverywhere(), {
      wrapper: makeWrapper(client),
    });

    await waitFor(() => expect(getMeSurvivalMock).toHaveBeenCalled());
    expect(result.current).toBe(false);
  });
});
