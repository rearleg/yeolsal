// Story 3.2 FE-13 — useSendFriendGift cache-invalidation policy tests.

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook } from "@testing-library/react-native";
import type { PropsWithChildren } from "react";
import { ApiError } from "../../../../api/client";
import * as friendGiftsApi from "../../../../api/friendGifts";
import { qk } from "../../keys";
import { useSendFriendGift } from "../friendGift";

jest.mock("../../../../api/friendGifts", () => ({
  postFriendGift: jest.fn(),
  getFriendGiftReceipts: jest.fn(),
  getHasGivenFriendGift: jest.fn(),
}));

const postFriendGiftMock =
  friendGiftsApi.postFriendGift as jest.MockedFunction<typeof friendGiftsApi.postFriendGift>;

const ROOM_ID = 42;
const TARGET_USER_ID = 11;

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

describe("useSendFriendGift", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("success → invalidates meSurvival + roomMessages + friendGiftReceipts + hasGivenFriendGift", async () => {
    postFriendGiftMock.mockResolvedValue({
      revivalEventId: 9002,
      source: "FRIEND_GIFT",
      pointsSpent: 5,
      roomPointPoolAfter: 10,
      occurredAt: "2026-05-18T03:14:15Z",
      isFirstEverFriendGiftSend: true,
      receiverNickname: "Receiver",
    });
    const client = makeClient();
    const spy = jest.spyOn(client, "invalidateQueries");

    const { result } = renderHook(() => useSendFriendGift(ROOM_ID), {
      wrapper: makeWrapper(client),
    });
    await act(async () => {
      await result.current.mutateAsync({ targetUserId: TARGET_USER_ID });
    });

    expect(postFriendGiftMock).toHaveBeenCalledWith(
      ROOM_ID,
      TARGET_USER_ID,
      undefined,
    );
    const keysSeen = spy.mock.calls.map((c) => JSON.stringify(c[0]?.queryKey));
    expect(keysSeen).toEqual(
      expect.arrayContaining([
        JSON.stringify(qk.meSurvival),
        JSON.stringify(qk.roomMessages(ROOM_ID)),
        JSON.stringify(qk.friendGiftReceipts),
        JSON.stringify(qk.hasGivenFriendGift),
      ]),
    );
  });

  it("409 ALREADY_REVIVED → still invalidates meSurvival (defensive)", async () => {
    postFriendGiftMock.mockRejectedValue(
      new ApiError(409, "ALREADY_REVIVED", "이미 회생되었습니다."),
    );
    const client = makeClient();
    const spy = jest.spyOn(client, "invalidateQueries");

    const { result } = renderHook(() => useSendFriendGift(ROOM_ID), {
      wrapper: makeWrapper(client),
    });
    await act(async () => {
      try {
        await result.current.mutateAsync({ targetUserId: TARGET_USER_ID });
      } catch {
        /* expected */
      }
    });

    const keysSeen = spy.mock.calls.map((c) => JSON.stringify(c[0]?.queryKey));
    expect(keysSeen).toEqual(expect.arrayContaining([JSON.stringify(qk.meSurvival)]));
  });

  it("400 INSUFFICIENT_GIFT_POINTS → invalidates meSurvival (defensive)", async () => {
    postFriendGiftMock.mockRejectedValue(
      new ApiError(400, "INSUFFICIENT_GIFT_POINTS", "포인트가 모자라요."),
    );
    const client = makeClient();
    const spy = jest.spyOn(client, "invalidateQueries");

    const { result } = renderHook(() => useSendFriendGift(ROOM_ID), {
      wrapper: makeWrapper(client),
    });
    await act(async () => {
      try {
        await result.current.mutateAsync({ targetUserId: TARGET_USER_ID });
      } catch {
        /* expected */
      }
    });

    const keysSeen = spy.mock.calls.map((c) => JSON.stringify(c[0]?.queryKey));
    expect(keysSeen).toEqual(expect.arrayContaining([JSON.stringify(qk.meSurvival)]));
  });

  it("5xx → no cache mutation", async () => {
    postFriendGiftMock.mockRejectedValue(
      new ApiError(500, "INTERNAL_ERROR", "내부 오류가 발생했습니다."),
    );
    const client = makeClient();
    const spy = jest.spyOn(client, "invalidateQueries");

    const { result } = renderHook(() => useSendFriendGift(ROOM_ID), {
      wrapper: makeWrapper(client),
    });
    await act(async () => {
      try {
        await result.current.mutateAsync({ targetUserId: TARGET_USER_ID });
      } catch {
        /* expected */
      }
    });

    expect(spy).not.toHaveBeenCalled();
  });

  it("Story 3.3 — mutate with sourceSubtype=WALLET_INITIATED → postFriendGift receives the field", async () => {
    postFriendGiftMock.mockResolvedValue({
      revivalEventId: 9003,
      source: "FRIEND_GIFT",
      pointsSpent: 5,
      roomPointPoolAfter: 5,
      occurredAt: "2026-05-18T03:14:15Z",
      isFirstEverFriendGiftSend: false,
      receiverNickname: "Receiver",
    });
    const { result } = renderHook(() => useSendFriendGift(ROOM_ID), {
      wrapper: makeWrapper(makeClient()),
    });
    await act(async () => {
      await result.current.mutateAsync({
        targetUserId: TARGET_USER_ID,
        sourceSubtype: "WALLET_INITIATED",
      });
    });
    expect(postFriendGiftMock).toHaveBeenCalledWith(
      ROOM_ID,
      TARGET_USER_ID,
      "WALLET_INITIATED",
    );
  });

  it("Story 3.3 — mutate without sourceSubtype → postFriendGift receives undefined (back-compat)", async () => {
    postFriendGiftMock.mockResolvedValue({
      revivalEventId: 9004,
      source: "FRIEND_GIFT",
      pointsSpent: 5,
      roomPointPoolAfter: 5,
      occurredAt: "2026-05-18T03:14:15Z",
      isFirstEverFriendGiftSend: true,
      receiverNickname: "Receiver",
    });
    const { result } = renderHook(() => useSendFriendGift(ROOM_ID), {
      wrapper: makeWrapper(makeClient()),
    });
    await act(async () => {
      await result.current.mutateAsync({ targetUserId: TARGET_USER_ID });
    });
    expect(postFriendGiftMock).toHaveBeenCalledWith(
      ROOM_ID,
      TARGET_USER_ID,
      undefined,
    );
  });
});
