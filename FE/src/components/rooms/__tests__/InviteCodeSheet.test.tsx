/* eslint-disable @typescript-eslint/no-require-imports */
// Behaviour contract for the room-detail invite modal:
// - hidden when visible=false
// - shows the create-button only until the parent supplies an invite
// - mutating helpers (onCreate / onShare / onClose) bubble up via props
// - once an invite is supplied, the code, expiry, and share button render
// - long-pressing the rendered code copies just the code to the clipboard

const mockSetStringAsync = jest.fn<Promise<void>, [string]>();
jest.mock("expo-clipboard", () => ({
  setStringAsync: (s: string) => mockSetStringAsync(s),
}));

const mockToastSuccess = jest.fn<void, [string]>();
jest.mock("../../../lib/toast", () => ({
  toast: {
    info: jest.fn(),
    success: (m: string) => mockToastSuccess(m),
    warning: jest.fn(),
    error: jest.fn(),
  },
}));

const mockHaptics = jest.fn<Promise<void>, [unknown]>();
jest.mock("expo-haptics", () => ({
  notificationAsync: (t: unknown) => mockHaptics(t),
  NotificationFeedbackType: { Success: "success" },
}));

import { fireEvent, render } from "@testing-library/react-native";
import React from "react";
import type { RoomInvite } from "../../../api/rooms";
import { InviteCodeSheet } from "../InviteCodeSheet";

const sampleInvite: RoomInvite = {
  id: 99,
  roomId: 42,
  code: "A7K9PXMQ",
  expiresAt: "2026-05-09T00:00:00.000Z",
  // Story 6.2 AC8 — non-nullable wire fields the Story 6.1 BE always emits.
  kakaoShareUrl: "https://yeolsal.app/join?code=A7K9PXMQ",
  previewCardImageUrl:
    "https://api.rearleg.com/yeolsal/api/v1/rooms/42/invites/preview-card",
};

function setup(overrides: Partial<React.ComponentProps<typeof InviteCodeSheet>> = {}) {
  const onCreate = jest.fn();
  const onShareKakao = jest.fn();
  const onShareGeneric = jest.fn();
  const onClose = jest.fn();
  const utils = render(
    <InviteCodeSheet
      visible={true}
      invite={null}
      isCreating={false}
      onCreate={onCreate}
      onShareKakao={onShareKakao}
      onShareGeneric={onShareGeneric}
      onClose={onClose}
      {...overrides}
    />,
  );
  return { ...utils, onCreate, onShareKakao, onShareGeneric, onClose };
}

describe("InviteCodeSheet", () => {
  it("renders nothing user-visible when visible=false", () => {
    const { queryByText } = setup({ visible: false });

    expect(queryByText("초대 코드 만들기")).toBeNull();
    expect(queryByText("KakaoTalk으로 공유")).toBeNull();
    expect(queryByText("다른 앱으로 공유")).toBeNull();
  });

  it("offers only the create button when no invite has been issued yet", () => {
    const { queryByText, getByText } = setup({ invite: null });

    getByText("초대 코드 만들기");
    expect(queryByText("KakaoTalk으로 공유")).toBeNull();
    expect(queryByText("다른 앱으로 공유")).toBeNull();
    expect(queryByText("A7K9PXMQ")).toBeNull();
  });

  it("invokes onCreate when the create button is pressed", () => {
    const { getByText, onCreate } = setup({ invite: null });

    fireEvent.press(getByText("초대 코드 만들기"));

    expect(onCreate).toHaveBeenCalledTimes(1);
  });

  it("renders the code, expiry, and BOTH share buttons when an invite is supplied (Story 6.2 AC4)", () => {
    const { getByText, queryByText } = setup({ invite: sampleInvite });

    getByText("A7K9PXMQ");
    getByText(/유효기간/);
    getByText("KakaoTalk으로 공유");
    getByText("다른 앱으로 공유");
    expect(queryByText("초대 코드 만들기")).toBeNull();
  });

  it("invokes onShareKakao when the primary KakaoTalk share button is pressed (Story 6.2 AC1)", () => {
    const { getByText, onShareKakao, onShareGeneric } = setup({ invite: sampleInvite });

    fireEvent.press(getByText("KakaoTalk으로 공유"));

    expect(onShareKakao).toHaveBeenCalledTimes(1);
    expect(onShareGeneric).not.toHaveBeenCalled();
  });

  it("invokes onShareGeneric when the secondary share button is pressed (Story 6.2 AC4)", () => {
    const { getByText, onShareKakao, onShareGeneric } = setup({ invite: sampleInvite });

    fireEvent.press(getByText("다른 앱으로 공유"));

    expect(onShareGeneric).toHaveBeenCalledTimes(1);
    expect(onShareKakao).not.toHaveBeenCalled();
  });

  it("disables the create button while a new invite is being issued", () => {
    const { getByText, onCreate } = setup({ invite: null, isCreating: true });

    const button = getByText("발급 중…");
    fireEvent.press(button);

    // Disabled buttons never fire onPress.
    expect(onCreate).not.toHaveBeenCalled();
  });

  it("offers a close affordance and bubbles onClose", () => {
    const { getByLabelText, onClose } = setup({ invite: null });

    fireEvent.press(getByLabelText("초대 코드 화면 닫기"));

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  describe("long-press copy", () => {
    beforeEach(() => {
      mockSetStringAsync.mockReset();
      mockToastSuccess.mockReset();
      mockHaptics.mockReset();
      mockSetStringAsync.mockResolvedValue(undefined);
      mockHaptics.mockResolvedValue(undefined);
    });

    it("copies just the code (no extra wrapping text) on long-press", async () => {
      const { getByLabelText } = setup({ invite: sampleInvite });

      // Wrapping the code in its own Pressable lets the test target it via
      // a stable accessibility label without depending on text positioning.
      const codeTarget = getByLabelText("초대 코드 길게 눌러 복사");
      fireEvent(codeTarget, "longPress");

      // Allow any awaited setStringAsync promise to flush before assertions.
      await Promise.resolve();
      await Promise.resolve();

      expect(mockSetStringAsync).toHaveBeenCalledTimes(1);
      expect(mockSetStringAsync).toHaveBeenCalledWith("A7K9PXMQ");
      expect(mockToastSuccess).toHaveBeenCalledWith(
        expect.stringMatching(/복사/),
      );
    });

    it("does not copy on short press", () => {
      const { getByLabelText } = setup({ invite: sampleInvite });

      fireEvent.press(getByLabelText("초대 코드 길게 눌러 복사"));

      expect(mockSetStringAsync).not.toHaveBeenCalled();
      expect(mockToastSuccess).not.toHaveBeenCalled();
    });
  });
});
