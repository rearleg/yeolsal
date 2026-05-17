// Story 3.1 FE-5.3 — useSelfRevival mutation hook invalidation behavior.

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook, waitFor } from "@testing-library/react-native";
import type { PropsWithChildren } from "react";
import { ApiError } from "../../../../api/client";
import * as revivalApi from "../../../../api/revival";
import { qk } from "../../keys";
import { useSelfRevival } from "../revival";

jest.mock("../../../../api/revival", () => ({
  postSelfRevival: jest.fn(),
}));

const postSelfRevivalMock =
  revivalApi.postSelfRevival as jest.MockedFunction<typeof revivalApi.postSelfRevival>;

const ROOM_ID = 11;

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

describe("useSelfRevival", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("success → invalidates qk.meSurvival and resolves with the DTO", async () => {
    postSelfRevivalMock.mockResolvedValue({
      revivalEventId: 1,
      source: "FREE_TICKET",
      pointsSpent: 0,
      roomPointPoolAfter: 5,
      occurredAt: "2026-05-16T03:14:15Z",
    });
    const client = makeClient();
    const spy = jest.spyOn(client, "invalidateQueries");

    const { result } = renderHook(() => useSelfRevival(ROOM_ID), {
      wrapper: makeWrapper(client),
    });

    await act(async () => {
      await result.current.mutateAsync("FREE_TICKET");
    });

    expect(postSelfRevivalMock).toHaveBeenCalledWith(ROOM_ID, "FREE_TICKET");
    expect(spy).toHaveBeenCalledWith({ queryKey: qk.meSurvival });
  });

  it("409 ALREADY_REVIVED → still invalidates qk.meSurvival (state must be re-read)", async () => {
    postSelfRevivalMock.mockRejectedValue(
      new ApiError(409, "ALREADY_REVIVED", "이미 회생되었습니다."),
    );
    const client = makeClient();
    const spy = jest.spyOn(client, "invalidateQueries");

    const { result } = renderHook(() => useSelfRevival(ROOM_ID), {
      wrapper: makeWrapper(client),
    });

    await act(async () => {
      try {
        await result.current.mutateAsync("FREE_TICKET");
      } catch {
        /* expected error */
      }
    });

    await waitFor(() =>
      expect(spy).toHaveBeenCalledWith({ queryKey: qk.meSurvival }),
    );
  });

  it("400 FREE_TICKET_ALREADY_USED → still invalidates qk.meSurvival", async () => {
    postSelfRevivalMock.mockRejectedValue(
      new ApiError(400, "FREE_TICKET_ALREADY_USED", "이미 회생권을 사용했어요."),
    );
    const client = makeClient();
    const spy = jest.spyOn(client, "invalidateQueries");

    const { result } = renderHook(() => useSelfRevival(ROOM_ID), {
      wrapper: makeWrapper(client),
    });

    await act(async () => {
      try {
        await result.current.mutateAsync("FREE_TICKET");
      } catch {
        /* expected error */
      }
    });

    await waitFor(() =>
      expect(spy).toHaveBeenCalledWith({ queryKey: qk.meSurvival }),
    );
  });

  it("400 INSUFFICIENT_POINTS → does NOT invalidate (the cache is still authoritative)", async () => {
    postSelfRevivalMock.mockRejectedValue(
      new ApiError(400, "INSUFFICIENT_POINTS", "개인 포인트가 부족합니다."),
    );
    const client = makeClient();
    const spy = jest.spyOn(client, "invalidateQueries");

    const { result } = renderHook(() => useSelfRevival(ROOM_ID), {
      wrapper: makeWrapper(client),
    });

    await act(async () => {
      try {
        await result.current.mutateAsync("PERSONAL_POINTS");
      } catch {
        /* expected error */
      }
    });

    expect(spy).not.toHaveBeenCalled();
  });
});
