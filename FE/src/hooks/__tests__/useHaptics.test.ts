import { renderHook, act } from "@testing-library/react-native";
import * as Haptics from "expo-haptics";
import { useHaptic } from "../useHaptics";
import * as motion from "../../theme/motion";

jest.mock("expo-haptics", () => ({
  impactAsync: jest.fn(() => Promise.resolve()),
  notificationAsync: jest.fn(() => Promise.resolve()),
  ImpactFeedbackStyle: { Light: "light", Medium: "medium", Heavy: "heavy" },
  NotificationFeedbackType: { Success: "success", Warning: "warning", Error: "error" },
}));

describe("useHaptic", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    jest.spyOn(motion, "useReducedMotion").mockReturnValue(false);
  });

  it("triggers light impact for 'light'", () => {
    const { result } = renderHook(() => useHaptic());
    act(() => result.current("light"));
    expect(Haptics.impactAsync).toHaveBeenCalledWith(Haptics.ImpactFeedbackStyle.Light);
  });

  it("triggers medium impact for 'medium'", () => {
    const { result } = renderHook(() => useHaptic());
    act(() => result.current("medium"));
    expect(Haptics.impactAsync).toHaveBeenCalledWith(Haptics.ImpactFeedbackStyle.Medium);
  });

  it("triggers success notification for 'success'", () => {
    const { result } = renderHook(() => useHaptic());
    act(() => result.current("success"));
    expect(Haptics.notificationAsync).toHaveBeenCalledWith(
      Haptics.NotificationFeedbackType.Success,
    );
  });

  it("triggers warning notification for 'warning'", () => {
    const { result } = renderHook(() => useHaptic());
    act(() => result.current("warning"));
    expect(Haptics.notificationAsync).toHaveBeenCalledWith(
      Haptics.NotificationFeedbackType.Warning,
    );
  });

  it("triggers error notification for 'error'", () => {
    const { result } = renderHook(() => useHaptic());
    act(() => result.current("error"));
    expect(Haptics.notificationAsync).toHaveBeenCalledWith(
      Haptics.NotificationFeedbackType.Error,
    );
  });

  it("does nothing when reduced motion is enabled", () => {
    jest.spyOn(motion, "useReducedMotion").mockReturnValue(true);
    const { result } = renderHook(() => useHaptic());
    act(() => result.current("light"));
    act(() => result.current("success"));
    expect(Haptics.impactAsync).not.toHaveBeenCalled();
    expect(Haptics.notificationAsync).not.toHaveBeenCalled();
  });

  it("returns a stable callback across renders", () => {
    const { result, rerender } = renderHook(() => useHaptic());
    const first = result.current;
    rerender({});
    expect(result.current).toBe(first);
  });
});
