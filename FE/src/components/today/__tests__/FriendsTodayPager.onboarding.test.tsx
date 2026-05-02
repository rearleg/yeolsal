/* eslint-disable @typescript-eslint/no-require-imports */
// Onboarding-toast contract for FriendsTodayPager. The page order is
// already group-first (default page index 0); without a hint, first-time
// users have no way to discover that the friends feed lives one swipe
// over. Show a single toast.info on first mount per device, gated by
// AsyncStorage so it never re-fires.

const mockGetItem = jest.fn<Promise<string | null>, [string]>();
const mockSetItem = jest.fn<Promise<void>, [string, string]>();

jest.mock("@react-native-async-storage/async-storage", () => ({
  __esModule: true,
  default: {
    getItem: (key: string) => mockGetItem(key),
    setItem: (key: string, value: string) => mockSetItem(key, value),
  },
}));

const mockToastInfo = jest.fn<void, [string]>();
jest.mock("../../../lib/toast", () => ({
  toast: {
    info: (m: string) => mockToastInfo(m),
    success: jest.fn(),
    warning: jest.fn(),
    error: jest.fn(),
  },
}));

// PagerView is a native module — render its children inline so the
// component tree mounts in jsdom without bridging native code.
jest.mock("react-native-pager-view", () => {
  const React = require("react") as typeof import("react");
  const { View } = require("react-native") as typeof import("react-native");
  const Pager = React.forwardRef<unknown, { children?: React.ReactNode }>(
    ({ children }, ref) => {
      React.useImperativeHandle(ref, () => ({ setPage: jest.fn() }));
      return <View>{children}</View>;
    },
  );
  Pager.displayName = "MockPagerView";
  return { __esModule: true, default: Pager };
});

// FriendsTodayCard / GroupTodayCard pull in expo-router → react-navigation,
// which jest-expo's transformIgnorePatterns don't transpile in this repo.
// The onboarding contract under test is independent of either page's
// content, so render simple stubs here.
jest.mock("../FriendsTodayCard", () => {
  const { View } = require("react-native") as typeof import("react-native");
  return { FriendsTodayCard: () => <View testID="friends-card-stub" /> };
});

jest.mock("../GroupTodayCard", () => {
  const { View } = require("react-native") as typeof import("react-native");
  return { GroupTodayCard: () => <View testID="group-card-stub" /> };
});

import { act, render, waitFor } from "@testing-library/react-native";
import React from "react";
import { FriendsTodayPager } from "../FriendsTodayPager";

const ONBOARDING_KEY = "today_pager_onboarding_seen_v1";

beforeEach(() => {
  jest.useFakeTimers();
  mockGetItem.mockReset();
  mockSetItem.mockReset();
  mockToastInfo.mockReset();
  mockSetItem.mockResolvedValue(undefined);
});

afterEach(() => {
  jest.useRealTimers();
});

describe("FriendsTodayPager onboarding toast", () => {
  it("fires toast.info exactly once on a fresh device and persists the seen flag", async () => {
    mockGetItem.mockResolvedValueOnce(null);

    render(<FriendsTodayPager friends={[]} date="2026-05-02" />);

    // Storage probe happens on mount; advance the toast delay timer.
    await waitFor(() => expect(mockGetItem).toHaveBeenCalledWith(ONBOARDING_KEY));
    await act(async () => {
      jest.advanceTimersByTime(2000);
    });

    expect(mockToastInfo).toHaveBeenCalledTimes(1);
    expect(mockToastInfo.mock.calls[0][0]).toMatch(/슬라이드/);
    expect(mockSetItem).toHaveBeenCalledWith(ONBOARDING_KEY, "1");
  });

  it("does not fire toast when the seen flag is already set", async () => {
    mockGetItem.mockResolvedValueOnce("1");

    render(<FriendsTodayPager friends={[]} date="2026-05-02" />);

    await waitFor(() => expect(mockGetItem).toHaveBeenCalledWith(ONBOARDING_KEY));
    await act(async () => {
      jest.advanceTimersByTime(2000);
    });

    expect(mockToastInfo).not.toHaveBeenCalled();
    expect(mockSetItem).not.toHaveBeenCalled();
  });

  it("swallows AsyncStorage read errors without throwing or showing the toast", async () => {
    mockGetItem.mockRejectedValueOnce(new Error("storage offline"));

    render(<FriendsTodayPager friends={[]} date="2026-05-02" />);

    await waitFor(() => expect(mockGetItem).toHaveBeenCalled());
    await act(async () => {
      jest.advanceTimersByTime(2000);
    });

    expect(mockToastInfo).not.toHaveBeenCalled();
  });
});
