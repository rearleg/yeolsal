// Story 3.3 FE-12 — FriendGiftPickerSheet component tests (4 cases per AC7).

import { fireEvent, render, screen } from "@testing-library/react-native";
import { AccessibilityInfo } from "react-native";
import type {
  EligibleFriendDto,
  FriendGiftTargetSummaryDto,
} from "../../../api/friendGiftTargets";
import { FriendGiftPickerSheet } from "../FriendGiftPickerSheet";

const friend = (
  userId: number,
  nickname: string,
  status: "RED" | "SPECTATOR" = "RED",
): EligibleFriendDto => ({
  userId,
  nickname,
  status,
  eliminatedAt: "2026-05-18T03:14:15Z",
});

const room = (...members: EligibleFriendDto[]): FriendGiftTargetSummaryDto => ({
  roomId: 42,
  roomName: "Room",
  eligibleCount: members.length,
  friends: members,
});

describe("FriendGiftPickerSheet", () => {
  beforeEach(() => {
    jest
      .spyOn(AccessibilityInfo, "isReduceMotionEnabled")
      .mockResolvedValue(false);
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  it("renders one row per eligible friend", () => {
    const r = room(friend(1, "AA"), friend(2, "BB"), friend(3, "CC", "SPECTATOR"));
    render(
      <FriendGiftPickerSheet
        open
        room={r}
        onSelect={jest.fn()}
        onCancel={jest.fn()}
      />,
    );
    expect(screen.getByText("AA")).toBeTruthy();
    expect(screen.getByText("BB")).toBeTruthy();
    expect(screen.getByText("CC")).toBeTruthy();
  });

  it("tap row → onSelect fires with that friend (userId + nickname)", () => {
    const target = friend(2, "BB");
    const r = room(friend(1, "AA"), target);
    const onSelect = jest.fn();
    render(
      <FriendGiftPickerSheet
        open
        room={r}
        onSelect={onSelect}
        onCancel={jest.fn()}
      />,
    );
    fireEvent.press(screen.getByLabelText("BB 선택"));
    expect(onSelect).toHaveBeenCalledWith(target);
  });

  it("cancel tap → onCancel fires; onSelect does not fire", () => {
    const onSelect = jest.fn();
    const onCancel = jest.fn();
    const r = room(friend(1, "AA"));
    render(
      <FriendGiftPickerSheet
        open
        room={r}
        onSelect={onSelect}
        onCancel={onCancel}
      />,
    );
    fireEvent.press(screen.getByLabelText("닫기"));
    expect(onCancel).toHaveBeenCalled();
    expect(onSelect).not.toHaveBeenCalled();
  });

  it("does not render when open=false (no rows, no cancel)", () => {
    const r = room(friend(1, "AA"));
    const { queryByText } = render(
      <FriendGiftPickerSheet
        open={false}
        room={r}
        onSelect={jest.fn()}
        onCancel={jest.fn()}
      />,
    );
    expect(queryByText("AA")).toBeNull();
    expect(queryByText("닫기")).toBeNull();
  });
});
