// Story 8.5 FE-6 (AC9 + AC15 row 4) — Privacy Settings page RTL.
//
// Test path note: AC15 row 4 lists `FE/app/__tests__/privacy-settings.test.tsx`,
// but `FE/package.json` jest config restricts testMatch to
// `<rootDir>/src/**/__tests__/**/*.test.{ts,tsx}`. Tests outside FE/src
// are not discovered. We place this here so it actually runs.

import { act, fireEvent, render, waitFor } from "@testing-library/react-native";
import React from "react";

jest.mock("../auth/useRequireAuth", () => ({
  __esModule: true,
  useRequireAuth: jest.fn(),
}));

jest.mock("../lib/analyticsConsent", () => ({
  __esModule: true,
  getAnalyticsConsent: jest.fn().mockResolvedValue(null),
  setAnalyticsConsent: jest.fn().mockResolvedValue(undefined),
}));

import PrivacySettingsScreen from "../../app/privacy-settings";
import {
  getAnalyticsConsent,
  setAnalyticsConsent,
} from "../lib/analyticsConsent";

const getMock = getAnalyticsConsent as jest.MockedFunction<typeof getAnalyticsConsent>;
const setMock = setAnalyticsConsent as jest.MockedFunction<typeof setAnalyticsConsent>;

describe("PrivacySettingsScreen (Story 8.5 AC9)", () => {
  beforeEach(() => {
    getMock.mockReset();
    setMock.mockReset();
    setMock.mockResolvedValue(undefined);
  });

  it("renders the locked AC14 label + helper sub-text byte-identically", async () => {
    getMock.mockResolvedValue(null);
    const { getByText } = render(<PrivacySettingsScreen />);
    await waitFor(() => {
      expect(getByText("사용 통계 공유")).toBeTruthy();
      expect(getByText("개인을 직접 식별하지 않는 앱 이용 통계를 수집합니다.")).toBeTruthy();
    });
  });

  it("Switch initial value reflects getAnalyticsConsent('opt_in')", async () => {
    getMock.mockResolvedValue("opt_in");
    const { getByLabelText } = render(<PrivacySettingsScreen />);
    const sw = await waitFor(() => getByLabelText("사용 통계 공유"));
    expect(sw.props.value).toBe(true);
    expect(sw.props.disabled).toBe(false);
  });

  it("disables the Switch while the initial consent read is pending", () => {
    getMock.mockReturnValue(new Promise(() => undefined));
    const { getByLabelText } = render(<PrivacySettingsScreen />);
    expect(getByLabelText("사용 통계 공유").props.disabled).toBe(true);
  });

  it("toggling on calls setAnalyticsConsent('opt_in')", async () => {
    getMock.mockResolvedValue("opt_out");
    const { getByLabelText } = render(<PrivacySettingsScreen />);
    const sw = await waitFor(() => getByLabelText("사용 통계 공유"));
    expect(sw.props.value).toBe(false);
    await act(async () => {
      fireEvent(sw, "valueChange", true);
    });
    expect(setMock).toHaveBeenCalledWith("opt_in");
  });

  it("toggling off calls setAnalyticsConsent('opt_out')", async () => {
    getMock.mockResolvedValue("opt_in");
    const { getByLabelText } = render(<PrivacySettingsScreen />);
    const sw = await waitFor(() => getByLabelText("사용 통계 공유"));
    expect(sw.props.value).toBe(true);
    await act(async () => {
      fireEvent(sw, "valueChange", false);
    });
    expect(setMock).toHaveBeenCalledWith("opt_out");
  });
});
