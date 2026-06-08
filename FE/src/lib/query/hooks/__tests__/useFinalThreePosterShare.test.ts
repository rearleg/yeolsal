// Story 7.3 AC5 + AC10 — useFinalThreePosterShare mutation wrapper.
// Asserts:
//   1. `mutate(...)` calls sendPosterShare once with the forwarded input.
//   2. Mutation flips isError when sendPosterShare throws.

import { createElement, type PropsWithChildren } from "react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderHook, waitFor, act } from "@testing-library/react-native";

jest.mock("../../../kakaoShare", () => ({
  sendPosterShare: jest.fn(),
}));

import { sendPosterShare, type PosterShareInput } from "../../../kakaoShare";
import { useFinalThreePosterShare } from "../useFinalThreePosterShare";

const mockSend = sendPosterShare as jest.MockedFunction<typeof sendPosterShare>;

function makeWrapper() {
  const client = new QueryClient({
    defaultOptions: { mutations: { retry: false } },
  });
  return function Wrapper({ children }: PropsWithChildren) {
    return createElement(QueryClientProvider, { client }, children);
  };
}

const SAMPLE_INPUT: PosterShareInput = {
  poster: {
    roomId: 42,
    yearMonth: "2026-05",
    svgText: "<svg/>",
    pngUrl: "https://cdn.test/p.png",
    generatedAt: "2026-06-01T06:30:00Z",
  },
  invite: {
    code: "A7K9PXMQ",
    kakaoShareUrl: "https://yeolsal.app/join?code=A7K9PXMQ",
  },
  roomName: "기본 방",
  survivorCount: 3,
};

describe("useFinalThreePosterShare", () => {
  beforeEach(() => {
    mockSend.mockReset();
  });

  it("forwards input verbatim to sendPosterShare", async () => {
    mockSend.mockResolvedValue(undefined);
    const { result } = renderHook(() => useFinalThreePosterShare(), {
      wrapper: makeWrapper(),
    });

    act(() => {
      result.current.mutate(SAMPLE_INPUT);
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(mockSend).toHaveBeenCalledTimes(1);
    // TanStack v5 passes (variables, mutationCtx) — assert only the
    // forwarded variables shape.
    expect(mockSend.mock.calls[0][0]).toEqual(SAMPLE_INPUT);
  });

  it("flips isError when sendPosterShare rejects", async () => {
    mockSend.mockRejectedValue(new Error("sdk down"));
    const { result } = renderHook(() => useFinalThreePosterShare(), {
      wrapper: makeWrapper(),
    });

    act(() => {
      result.current.mutate(SAMPLE_INPUT);
    });

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(result.current.error?.message).toBe("sdk down");
  });
});
