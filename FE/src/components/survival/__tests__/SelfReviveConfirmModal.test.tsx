// Story 3.1 FE-5.2 — SelfReviveConfirmModal flow + toast assertions.

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react-native";
import type { PropsWithChildren } from "react";
import { ApiError } from "../../../api/client";
import * as revivalApi from "../../../api/revival";
import { setToastBridge } from "../../../lib/toast";
import { SelfReviveConfirmModal } from "../SelfReviveConfirmModal";

jest.mock("../../../api/revival", () => ({
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

describe("SelfReviveConfirmModal", () => {
  let toastCalls: Array<{ variant: string; message: string }>;

  beforeEach(() => {
    jest.useFakeTimers();
    jest.clearAllMocks();
    toastCalls = [];
    setToastBridge({
      show: (variant, message) => {
        toastCalls.push({ variant, message });
      },
    });
  });

  afterEach(() => {
    jest.useRealTimers();
    setToastBridge(null);
  });

  it("FREE_TICKET body renders '무료 회생권 1매가 사용돼요.'", () => {
    render(
      <SelfReviveConfirmModal
        open
        roomId={ROOM_ID}
        source="FREE_TICKET"
        onClose={() => {}}
      />,
      { wrapper: makeWrapper(makeClient()) },
    );
    expect(screen.getByText("방으로 돌아갈까요?")).toBeTruthy();
    expect(screen.getByText("무료 회생권 1매가 사용돼요.")).toBeTruthy();
  });

  it("PERSONAL_POINTS body renders '개인 포인트 3점이 사용돼요.'", () => {
    render(
      <SelfReviveConfirmModal
        open
        roomId={ROOM_ID}
        source="PERSONAL_POINTS"
        onClose={() => {}}
      />,
      { wrapper: makeWrapper(makeClient()) },
    );
    expect(screen.getByText("개인 포인트 3점이 사용돼요.")).toBeTruthy();
  });

  it("primary CTA tap calls postSelfRevival with the right source, shows success toast, closes", async () => {
    postSelfRevivalMock.mockResolvedValue({
      revivalEventId: 1,
      source: "FREE_TICKET",
      pointsSpent: 0,
      roomPointPoolAfter: 5,
      occurredAt: "2026-05-16T03:14:15Z",
    });
    const onClose = jest.fn();
    render(
      <SelfReviveConfirmModal
        open
        roomId={ROOM_ID}
        source="FREE_TICKET"
        onClose={onClose}
      />,
      { wrapper: makeWrapper(makeClient()) },
    );

    fireEvent.press(screen.getByLabelText("돌아가기"));

    await waitFor(() => expect(postSelfRevivalMock).toHaveBeenCalledWith(ROOM_ID, "FREE_TICKET"));
    await waitFor(() => expect(onClose).toHaveBeenCalled());
    expect(toastCalls).toContainEqual({ variant: "success", message: "방으로 돌아왔어요" });
  });

  it("INSUFFICIENT_POINTS error → '포인트가 모자라요' toast and auto-close after 1.5s", async () => {
    postSelfRevivalMock.mockRejectedValue(
      new ApiError(400, "INSUFFICIENT_POINTS", "개인 포인트가 부족합니다."),
    );
    const onClose = jest.fn();
    render(
      <SelfReviveConfirmModal
        open
        roomId={ROOM_ID}
        source="PERSONAL_POINTS"
        onClose={onClose}
      />,
      { wrapper: makeWrapper(makeClient()) },
    );

    fireEvent.press(screen.getByLabelText("돌아가기"));

    await waitFor(() => expect(postSelfRevivalMock).toHaveBeenCalled());
    await waitFor(() =>
      expect(toastCalls).toContainEqual({ variant: "danger", message: "포인트가 모자라요" }),
    );
    expect(onClose).not.toHaveBeenCalled();
    jest.advanceTimersByTime(1500);
    expect(onClose).toHaveBeenCalled();
  });

  it("ALREADY_REVIVED error → body swaps to '이미 회생됐어요' and auto-closes", async () => {
    postSelfRevivalMock.mockRejectedValue(
      new ApiError(409, "ALREADY_REVIVED", "이미 회생되었습니다."),
    );
    const onClose = jest.fn();
    render(
      <SelfReviveConfirmModal
        open
        roomId={ROOM_ID}
        source="FREE_TICKET"
        onClose={onClose}
      />,
      { wrapper: makeWrapper(makeClient()) },
    );

    fireEvent.press(screen.getByLabelText("돌아가기"));

    await waitFor(() => expect(screen.queryByText("이미 회생됐어요")).toBeTruthy());
    jest.advanceTimersByTime(1500);
    expect(onClose).toHaveBeenCalled();
  });

  it("FREE_TICKET_ALREADY_USED error → '이미 회생권을 썼어요' toast and auto-close", async () => {
    postSelfRevivalMock.mockRejectedValue(
      new ApiError(400, "FREE_TICKET_ALREADY_USED", "이미 회생권을 사용했어요."),
    );
    const onClose = jest.fn();
    render(
      <SelfReviveConfirmModal
        open
        roomId={ROOM_ID}
        source="FREE_TICKET"
        onClose={onClose}
      />,
      { wrapper: makeWrapper(makeClient()) },
    );

    fireEvent.press(screen.getByLabelText("돌아가기"));

    await waitFor(() =>
      expect(toastCalls).toContainEqual({ variant: "danger", message: "이미 회생권을 썼어요" }),
    );
    jest.advanceTimersByTime(1500);
    expect(onClose).toHaveBeenCalled();
  });

  it("ghost CTA tap closes without calling postSelfRevival", () => {
    const onClose = jest.fn();
    render(
      <SelfReviveConfirmModal
        open
        roomId={ROOM_ID}
        source="FREE_TICKET"
        onClose={onClose}
      />,
      { wrapper: makeWrapper(makeClient()) },
    );

    fireEvent.press(screen.getByLabelText("닫기"));
    expect(onClose).toHaveBeenCalled();
    expect(postSelfRevivalMock).not.toHaveBeenCalled();
  });
});
