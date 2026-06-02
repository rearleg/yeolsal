// Story 5.1 — useRoomRule + useUpdateRoomRule hook contract tests.

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook, waitFor } from "@testing-library/react-native";
import type { PropsWithChildren } from "react";
import { ApiError } from "../../../../api/client";
import * as roomsApi from "../../../../api/rooms";
import { useRoomRule, useUpdateRoomRule } from "../roomRule";

jest.mock("../../../../api/rooms", () => ({
  getRoomRule: jest.fn(),
  updateRoomRule: jest.fn(),
}));

const getRoomRuleMock = roomsApi.getRoomRule as jest.MockedFunction<typeof roomsApi.getRoomRule>;
const updateRoomRuleMock = roomsApi.updateRoomRule as jest.MockedFunction<
  typeof roomsApi.updateRoomRule
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

const ROOM_ID = 42;

const SAMPLE_STATE = {
  current: {
    id: 500,
    preset: "DAILY_UPDATE" as const,
    weekendInclude: true,
    effectiveFromMonth: "2026-04",
    createdByUserId: 7,
    createdAt: "2026-04-15T03:14:00Z",
  },
  pending: null,
};

const SAMPLE_DTO = {
  id: 1001,
  preset: "DAILY_UPDATE" as const,
  weekendInclude: false,
  effectiveFromMonth: "2026-05",
  createdByUserId: 7,
  createdAt: "2026-04-15T03:14:00Z",
};

describe("useRoomRule", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("fetches the rule state and exposes data", async () => {
    getRoomRuleMock.mockResolvedValue(SAMPLE_STATE);
    const { result } = renderHook(() => useRoomRule(ROOM_ID), {
      wrapper: makeWrapper(makeClient()),
    });
    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.data?.current.effectiveFromMonth).toBe("2026-04");
    expect(getRoomRuleMock).toHaveBeenCalledWith(ROOM_ID);
  });
});

describe("useUpdateRoomRule", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("invalidates qk.roomRule(roomId) on success (AC11)", async () => {
    updateRoomRuleMock.mockResolvedValue(SAMPLE_DTO);
    const client = makeClient();
    const invalidateSpy = jest.spyOn(client, "invalidateQueries");
    const { result } = renderHook(() => useUpdateRoomRule(), {
      wrapper: makeWrapper(client),
    });

    await act(async () => {
      await result.current.mutateAsync({
        roomId: ROOM_ID,
        preset: "DAILY_UPDATE",
        weekendInclude: false,
      });
    });

    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: ["roomRule", ROOM_ID] });
  });

  it("does NOT invalidate when the mutation throws a 403 ApiError", async () => {
    updateRoomRuleMock.mockRejectedValue(
      new ApiError(403, "FORBIDDEN", "방장 권한이 필요합니다."),
    );
    const client = makeClient();
    const invalidateSpy = jest.spyOn(client, "invalidateQueries");
    const { result } = renderHook(() => useUpdateRoomRule(), {
      wrapper: makeWrapper(client),
    });

    await act(async () => {
      try {
        await result.current.mutateAsync({
          roomId: ROOM_ID,
          preset: "DAILY_UPDATE",
          weekendInclude: false,
        });
      } catch {
        // swallow — assertion below confirms cache stayed intact
      }
    });

    expect(invalidateSpy).not.toHaveBeenCalledWith({ queryKey: ["roomRule", ROOM_ID] });
  });

  it("narrows the mutation error to ApiError with the original status", async () => {
    updateRoomRuleMock.mockRejectedValue(
      new ApiError(400, "VALIDATION", "잘못된 요청입니다."),
    );
    const { result } = renderHook(() => useUpdateRoomRule(), {
      wrapper: makeWrapper(makeClient()),
    });

    let captured: unknown = null;
    await act(async () => {
      try {
        await result.current.mutateAsync({
          roomId: ROOM_ID,
          preset: "DAILY_UPDATE",
          weekendInclude: false,
        });
      } catch (err) {
        captured = err;
      }
    });

    expect(captured).toBeInstanceOf(ApiError);
    expect((captured as ApiError).status).toBe(400);
    expect((captured as ApiError).code).toBe("VALIDATION");
  });
});
