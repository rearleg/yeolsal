// Story 2.3 FE-4.2 — visibilityPrefs hooks behavioral test.
//
// Pins three contracts:
//   1. Happy-path: useRecordVisibilityPrefsQuery resolves with the rows.
//   2. useRecordVisibilityPref(roomId) derives the cached row by roomId.
//   3. useUpdateRecordVisibilityPref: optimistic update applies before
//      mutationFn resolves; on error, the cache rolls back to the snapshot.

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook, waitFor } from "@testing-library/react-native";
import type { PropsWithChildren } from "react";
import * as survivalApi from "../../../../api/survival";
import { qk } from "../../keys";
import {
  useRecordVisibilityPref,
  useRecordVisibilityPrefsQuery,
  useUpdateRecordVisibilityPref,
} from "../visibilityPrefs";

jest.mock("../../../../api/survival", () => ({
  getRecordVisibilityPrefs: jest.fn(),
  updateRecordVisibilityPref: jest.fn(),
}));

const getPrefsMock =
  survivalApi.getRecordVisibilityPrefs as jest.MockedFunction<
    typeof survivalApi.getRecordVisibilityPrefs
  >;
const updatePrefMock =
  survivalApi.updateRecordVisibilityPref as jest.MockedFunction<
    typeof survivalApi.updateRecordVisibilityPref
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

const row = (
  roomId: number,
  shareOnElimination: boolean
): survivalApi.VisibilityPrefDto => ({
  roomId,
  roomName: `room-${roomId}`,
  shareOnElimination,
  updatedAt: null,
});

describe("useRecordVisibilityPrefsQuery", () => {
  beforeEach(() => jest.clearAllMocks());

  it("resolves with the rows returned by the API", async () => {
    const rows = [row(1, false), row(2, true)];
    getPrefsMock.mockResolvedValue(rows);
    const client = makeClient();

    const { result } = renderHook(() => useRecordVisibilityPrefsQuery(), {
      wrapper: makeWrapper(client),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toEqual(rows);
    expect(client.getQueryData(qk.recordVisibilityPrefs)).toEqual(rows);
  });
});

describe("useRecordVisibilityPref", () => {
  beforeEach(() => jest.clearAllMocks());

  it("derives the cached row for the requested roomId", async () => {
    getPrefsMock.mockResolvedValue([row(10, false), row(11, true)]);
    const client = makeClient();

    const { result } = renderHook(() => useRecordVisibilityPref(11), {
      wrapper: makeWrapper(client),
    });

    await waitFor(() => expect(result.current).not.toBeNull());
    expect(result.current?.shareOnElimination).toBe(true);
  });

  it("returns null when the room is not in the cache", async () => {
    getPrefsMock.mockResolvedValue([row(10, false)]);
    const client = makeClient();

    const { result } = renderHook(() => useRecordVisibilityPref(99), {
      wrapper: makeWrapper(client),
    });

    await waitFor(() => expect(getPrefsMock).toHaveBeenCalled());
    expect(result.current).toBeNull();
  });
});

describe("useUpdateRecordVisibilityPref", () => {
  beforeEach(() => jest.clearAllMocks());

  it("optimistically updates the list cache before mutationFn resolves", async () => {
    const client = makeClient();
    getPrefsMock.mockResolvedValue([row(7, false)]);
    let resolveUpdate: (v: survivalApi.VisibilityPrefDto) => void = () => {};
    updatePrefMock.mockImplementation(
      () =>
        new Promise<survivalApi.VisibilityPrefDto>((resolve) => {
          resolveUpdate = resolve;
        })
    );

    renderHook(() => useRecordVisibilityPrefsQuery(), {
      wrapper: makeWrapper(client),
    });
    await waitFor(() =>
      expect(client.getQueryData(qk.recordVisibilityPrefs)).toBeDefined()
    );

    const { result } = renderHook(() => useUpdateRecordVisibilityPref(), {
      wrapper: makeWrapper(client),
    });

    act(() => {
      result.current.mutate({ roomId: 7, shareOnElimination: true });
    });

    await waitFor(() => {
      const cached = client.getQueryData<survivalApi.VisibilityPrefDto[]>(
        qk.recordVisibilityPrefs
      );
      expect(cached?.find((r) => r.roomId === 7)?.shareOnElimination).toBe(true);
    });

    resolveUpdate({ ...row(7, true), updatedAt: "2026-05-16T01:00:00Z" });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
  });

  it("rolls back the cache when the mutation rejects", async () => {
    const client = makeClient();
    getPrefsMock.mockResolvedValue([row(7, false)]);
    updatePrefMock.mockRejectedValue(new Error("upstream 5xx"));

    renderHook(() => useRecordVisibilityPrefsQuery(), {
      wrapper: makeWrapper(client),
    });
    await waitFor(() =>
      expect(client.getQueryData(qk.recordVisibilityPrefs)).toBeDefined()
    );

    const { result } = renderHook(() => useUpdateRecordVisibilityPref(), {
      wrapper: makeWrapper(client),
    });

    act(() => {
      result.current.mutate({ roomId: 7, shareOnElimination: true });
    });

    await waitFor(() => expect(result.current.isError).toBe(true));
    const cached = client.getQueryData<survivalApi.VisibilityPrefDto[]>(
      qk.recordVisibilityPrefs
    );
    expect(cached?.find((r) => r.roomId === 7)?.shareOnElimination).toBe(false);
  });
});
