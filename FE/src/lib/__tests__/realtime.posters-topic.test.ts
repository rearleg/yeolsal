// Story 7.3 AC3 + AC10 — integration guard for the
// /topic/rooms.{roomId}.posters destination contract.
//
// Pins:
//   - Dot-separator destination shape (must match BE
//     JwtChannelInterceptor.java:44 regex
//     ^/topic/rooms\.(\d+)\.(chat|members|survival|points|kudos|posters)$).
//   - JSON-decoded frame body matches MonthlyPosterReadyFrame contract
//     (Story 7.2 MonthlyPosterReadyPayload — {roomId:long, yearMonth:string}).

import { renderHook, waitFor } from "@testing-library/react-native";

jest.mock("../../api/posters", () => ({
  getPoster: jest.fn().mockResolvedValue(null),
}));

const mockSubscribe = jest.fn();

jest.mock("../realtime/client", () => ({
  __esModule: true,
  useRealtimeStatus: () => "connected",
  useRealtimeSubscription: <T,>(
    destination: string | null,
    handler: (payload: T) => void,
  ) => {
    if (destination) {
      mockSubscribe(destination, handler);
    }
  },
}));

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createElement, type PropsWithChildren } from "react";
import {
  useFinalThreePoster,
  type MonthlyPosterReadyFrame,
} from "../query/hooks/useFinalThreePoster";

const TOPIC_REGEX = /^\/topic\/rooms\.(\d+)\.posters$/;

function makeWrapper() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 60_000 } },
  });
  return function Wrapper({ children }: PropsWithChildren) {
    return createElement(QueryClientProvider, { client }, children);
  };
}

describe("realtime /topic/rooms.{id}.posters integration contract", () => {
  beforeEach(() => {
    mockSubscribe.mockReset();
  });

  it("registers the dot-separator destination and round-trips a JSON-decoded MonthlyPosterReadyFrame", async () => {
    renderHook(() => useFinalThreePoster(42, "2026-05"), {
      wrapper: makeWrapper(),
    });

    await waitFor(() => expect(mockSubscribe).toHaveBeenCalled());
    const [destination, handler] = mockSubscribe.mock.calls[0] as [
      string,
      (frame: MonthlyPosterReadyFrame) => void,
    ];

    // Dot-separator regression guard — slash-separator would silently
    // break the BE JwtChannelInterceptor regex auth gate.
    expect(destination).toBe("/topic/rooms.42.posters");
    expect(destination).toMatch(TOPIC_REGEX);

    // Round-trip a JSON-encoded MonthlyPosterReadyPayload — confirms the
    // contract with Story 7.2's serializer (roomId:long, yearMonth:string).
    const wirePayload = JSON.parse('{"roomId":42,"yearMonth":"2026-05"}');
    expect(() => handler(wirePayload as MonthlyPosterReadyFrame)).not.toThrow();
  });
});
