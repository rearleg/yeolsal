/* eslint-disable @typescript-eslint/no-require-imports */
// Behaviour contract for the room-detail invite modal:
// - hidden when visible=false
// - shows the create-button only until the parent supplies an invite
// - mutating helpers (onCreate / onShare / onClose) bubble up via props
// - once an invite is supplied, the code, expiry, and share button render

import { fireEvent, render } from "@testing-library/react-native";
import React from "react";
import type { RoomInvite } from "../../../api/rooms";
import { InviteCodeSheet } from "../InviteCodeSheet";

const sampleInvite: RoomInvite = {
  id: 99,
  roomId: 42,
  code: "A7K9PXMQ",
  expiresAt: "2026-05-09T00:00:00.000Z",
};

function setup(overrides: Partial<React.ComponentProps<typeof InviteCodeSheet>> = {}) {
  const onCreate = jest.fn();
  const onShare = jest.fn();
  const onClose = jest.fn();
  const utils = render(
    <InviteCodeSheet
      visible={true}
      invite={null}
      isCreating={false}
      onCreate={onCreate}
      onShare={onShare}
      onClose={onClose}
      {...overrides}
    />,
  );
  return { ...utils, onCreate, onShare, onClose };
}

describe("InviteCodeSheet", () => {
  it("renders nothing user-visible when visible=false", () => {
    const { queryByText } = setup({ visible: false });

    expect(queryByText("초대 코드 만들기")).toBeNull();
    expect(queryByText("공유하기")).toBeNull();
  });

  it("offers only the create button when no invite has been issued yet", () => {
    const { queryByText, getByText } = setup({ invite: null });

    getByText("초대 코드 만들기");
    expect(queryByText("공유하기")).toBeNull();
    expect(queryByText("A7K9PXMQ")).toBeNull();
  });

  it("invokes onCreate when the create button is pressed", () => {
    const { getByText, onCreate } = setup({ invite: null });

    fireEvent.press(getByText("초대 코드 만들기"));

    expect(onCreate).toHaveBeenCalledTimes(1);
  });

  it("renders the code, expiry, and share button when an invite is supplied", () => {
    const { getByText, queryByText } = setup({ invite: sampleInvite });

    getByText("A7K9PXMQ");
    getByText(/유효기간/);
    getByText("공유하기");
    expect(queryByText("초대 코드 만들기")).toBeNull();
  });

  it("invokes onShare when the share button is pressed", () => {
    const { getByText, onShare } = setup({ invite: sampleInvite });

    fireEvent.press(getByText("공유하기"));

    expect(onShare).toHaveBeenCalledTimes(1);
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
});
