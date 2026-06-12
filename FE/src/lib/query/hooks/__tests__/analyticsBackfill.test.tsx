// L2 analytics backfill — emit-site coverage for the revival / kudos /
// friend-gift / Final-3 share funnels. Each mutation hook routes its terminal
// (and key intermediate) events through the locked ANALYTICS_EVENTS taxonomy
// via captureEvent; these tests pin the event name + properties.

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook } from "@testing-library/react-native";
import type { PropsWithChildren } from "react";

import * as revivalApi from "../../../../api/revival";
import * as kudosApi from "../../../../api/kudos";
import * as friendGiftsApi from "../../../../api/friendGifts";
import * as kakaoShare from "../../../kakaoShare";
import { captureEvent } from "../../../analytics";
import { useSelfRevival } from "../revival";
import { useSendKudos } from "../kudos";
import { useSendFriendGift } from "../friendGift";
import { useFinalThreePosterShare } from "../useFinalThreePosterShare";

jest.mock("../../../../api/revival", () => ({ postSelfRevival: jest.fn() }));
jest.mock("../../../../api/kudos", () => ({ postKudos: jest.fn() }));
jest.mock("../../../../api/friendGifts", () => ({
  postFriendGift: jest.fn(),
  getFriendGiftReceipts: jest.fn(),
  getHasGivenFriendGift: jest.fn(),
}));
jest.mock("../../../kakaoShare", () => ({ sendPosterShare: jest.fn() }));
jest.mock("../../../analytics", () => ({ captureEvent: jest.fn() }));

const capture = captureEvent as jest.MockedFunction<typeof captureEvent>;
const ROOM_ID = 42;

function makeWrapper() {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false, staleTime: 0, gcTime: 60_000 },
      mutations: { retry: false },
    },
  });
  return function Wrapper({ children }: PropsWithChildren) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

beforeEach(() => {
  jest.clearAllMocks();
});

describe("analytics backfill — revival funnel", () => {
  it("emits revival.attempted then revival.succeeded on success", async () => {
    (revivalApi.postSelfRevival as jest.Mock).mockResolvedValue({});
    const { result } = renderHook(() => useSelfRevival(ROOM_ID), {
      wrapper: makeWrapper(),
    });
    await act(async () => {
      await result.current.mutateAsync("FREE_TICKET");
    });
    expect(capture).toHaveBeenCalledWith("revival.attempted", {
      source: "FREE_TICKET",
      roomId: ROOM_ID,
    });
    expect(capture).toHaveBeenCalledWith("revival.succeeded", {
      source: "FREE_TICKET",
      roomId: ROOM_ID,
    });
  });

  it("emits revival.failed with a reason on error", async () => {
    (revivalApi.postSelfRevival as jest.Mock).mockRejectedValue(new Error("boom"));
    const { result } = renderHook(() => useSelfRevival(ROOM_ID), {
      wrapper: makeWrapper(),
    });
    await act(async () => {
      await expect(result.current.mutateAsync("PERSONAL_POINTS")).rejects.toBeDefined();
    });
    expect(capture).toHaveBeenCalledWith("revival.failed", {
      reason: "NETWORK",
      source: "PERSONAL_POINTS",
      roomId: ROOM_ID,
    });
  });
});

describe("analytics backfill — kudos", () => {
  it("emits kudos.sent on success", async () => {
    (kudosApi.postKudos as jest.Mock).mockResolvedValue({});
    const { result } = renderHook(() => useSendKudos(ROOM_ID), {
      wrapper: makeWrapper(),
    });
    await act(async () => {
      await result.current.mutateAsync({ targetUserId: 11 });
    });
    expect(capture).toHaveBeenCalledWith("kudos.sent", { roomId: ROOM_ID });
  });
});

describe("analytics backfill — friend-gift conversion", () => {
  it("emits friend_gift.modal_closed with outcome on success", async () => {
    (friendGiftsApi.postFriendGift as jest.Mock).mockResolvedValue({});
    const { result } = renderHook(() => useSendFriendGift(ROOM_ID), {
      wrapper: makeWrapper(),
    });
    await act(async () => {
      await result.current.mutateAsync({
        targetUserId: 11,
        sourceSubtype: "WALLET_INITIATED",
      });
    });
    expect(capture).toHaveBeenCalledWith("friend_gift.modal_closed", {
      outcome: "revival_sent",
      sourceSubtype: "WALLET_INITIATED",
      roomId: ROOM_ID,
    });
  });
});

describe("analytics backfill — Final-3 share-rate funnel", () => {
  it("emits share_tapped then share_completed", async () => {
    (kakaoShare.sendPosterShare as jest.Mock).mockResolvedValue(undefined);
    const input = {
      poster: {
        roomId: ROOM_ID,
        yearMonth: "2026-06",
        svgText: "<svg/>",
        pngUrl: "https://cdn.example/p.png",
        generatedAt: "2026-06-01T00:00:00Z",
      },
      invite: { code: "ABC123", kakaoShareUrl: "https://k.example/s" },
      roomName: "우리 방",
      survivorCount: 3,
    };
    const { result } = renderHook(() => useFinalThreePosterShare(), {
      wrapper: makeWrapper(),
    });
    await act(async () => {
      await result.current.mutateAsync(input);
    });
    expect(capture).toHaveBeenCalledWith("final_three.share_tapped", {
      roomId: ROOM_ID,
      yearMonth: "2026-06",
    });
    expect(capture).toHaveBeenCalledWith("final_three.share_completed", {
      roomId: ROOM_ID,
      yearMonth: "2026-06",
      survivorCount: 3,
    });
  });
});
