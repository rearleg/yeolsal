import { focusManager } from "@tanstack/react-query";

// jest hoists `jest.mock(...)` to the top of the file; variables referenced
// inside its factory must be prefixed with "mock" to bypass the
// out-of-scope-variable guard.
const mockAddEventListener = jest.fn();
const mockRemove = jest.fn();
const mockHandlerHolder: { current: ((status: string) => void) | null } = { current: null };

jest.mock("react-native", () => ({
  AppState: {
    addEventListener: (event: string, handler: (status: string) => void) => {
      mockAddEventListener(event);
      mockHandlerHolder.current = handler;
      return { remove: mockRemove };
    },
  },
  Platform: { OS: "ios" },
}));

// Imported AFTER the mock so the production module sees the mocked AppState.
import { setupReactQueryFocus } from "../focus";

describe("setupReactQueryFocus", () => {
  beforeEach(() => {
    mockAddEventListener.mockClear();
    mockRemove.mockClear();
    mockHandlerHolder.current = null;
  });

  it("subscribes to AppState 'change' events", () => {
    setupReactQueryFocus();
    expect(mockAddEventListener).toHaveBeenCalledWith("change");
  });

  it("calls focusManager.setFocused(true) when the app becomes active", () => {
    const setFocused = jest.spyOn(focusManager, "setFocused").mockImplementation(() => undefined);
    setupReactQueryFocus();
    mockHandlerHolder.current?.("active");
    expect(setFocused).toHaveBeenCalledWith(true);
  });

  it("calls focusManager.setFocused(false) when the app goes background", () => {
    const setFocused = jest.spyOn(focusManager, "setFocused").mockImplementation(() => undefined);
    setupReactQueryFocus();
    mockHandlerHolder.current?.("background");
    expect(setFocused).toHaveBeenCalledWith(false);
  });

  it("returns an unsubscribe that removes the AppState listener", () => {
    const unsub = setupReactQueryFocus();
    unsub();
    expect(mockRemove).toHaveBeenCalled();
  });
});
