// Story 8.1 AC1 + AC13 row 1 — onboardingState SecureStore record.
// Mirrors the playedRevivalEvents / analyticsConsent mock shape: the
// expo-secure-store native module is replaced wholesale and the helper's
// observable contract (return values + stored JSON) is asserted.

jest.mock("expo-secure-store", () => ({
  getItemAsync: jest.fn(),
  setItemAsync: jest.fn(),
  deleteItemAsync: jest.fn(),
}));

import * as SecureStore from "expo-secure-store";
import {
  clearOnboardingState,
  getOnboardingState,
  markOnboardingCompleted,
  setDeferredDestination,
} from "../onboardingState";

const getItem = SecureStore.getItemAsync as jest.MockedFunction<
  typeof SecureStore.getItemAsync
>;
const setItem = SecureStore.setItemAsync as jest.MockedFunction<
  typeof SecureStore.setItemAsync
>;
const deleteItem = SecureStore.deleteItemAsync as jest.MockedFunction<
  typeof SecureStore.deleteItemAsync
>;

function primeMocks() {
  getItem.mockResolvedValue(null);
  setItem.mockResolvedValue(undefined);
  deleteItem.mockResolvedValue(undefined);
}

describe("onboardingState (Story 8.1 AC1)", () => {
  beforeEach(async () => {
    jest.clearAllMocks();
    primeMocks();
    await clearOnboardingState();
    jest.clearAllMocks();
    primeMocks();
  });

  it("returns null before any onboarding decision is recorded", async () => {
    await expect(getOnboardingState()).resolves.toBeNull();
    expect(getItem).toHaveBeenCalledWith("yeosal.onboardingState");
  });

  it("markOnboardingCompleted(null) persists completedAt with a null deferredDestination", async () => {
    await markOnboardingCompleted(null);

    expect(setItem).toHaveBeenCalledTimes(1);
    const [key, raw] = setItem.mock.calls[0] as [string, string];
    expect(key).toBe("yeosal.onboardingState");
    const stored = JSON.parse(raw) as Record<string, unknown>;
    expect(stored.version).toBe(1);
    expect(typeof stored.completedAt).toBe("string");
    expect(stored.deferredDestination).toBeNull();

    await expect(getOnboardingState()).resolves.toMatchObject({
      version: 1,
      deferredDestination: null,
    });
  });

  it("markOnboardingCompleted(destination) persists completedAt and the destination", async () => {
    await markOnboardingCompleted("/rooms/42/settings?onboarding=1");

    const [, raw] = setItem.mock.calls[0] as [string, string];
    const stored = JSON.parse(raw) as Record<string, unknown>;
    expect(typeof stored.completedAt).toBe("string");
    expect(stored.deferredDestination).toBe("/rooms/42/settings?onboarding=1");
  });

  it("setDeferredDestination stashes the destination without completedAt", async () => {
    await setDeferredDestination("/rooms/42/settings?onboarding=1");

    const [, raw] = setItem.mock.calls[0] as [string, string];
    const stored = JSON.parse(raw) as Record<string, unknown>;
    expect(stored.deferredDestination).toBe("/rooms/42/settings?onboarding=1");
    expect("completedAt" in stored).toBe(false);

    await expect(getOnboardingState()).resolves.toMatchObject({
      completedAt: null,
      deferredDestination: "/rooms/42/settings?onboarding=1",
    });
  });

  it("returns null when the SecureStore read fails", async () => {
    getItem.mockRejectedValue(new Error("keychain unavailable"));
    await expect(getOnboardingState()).resolves.toBeNull();
  });

  it("swallows SecureStore write failures with a dev warning", async () => {
    const warn = jest.spyOn(console, "warn").mockImplementation(() => undefined);
    setItem.mockRejectedValue(new Error("quota exceeded"));

    await expect(markOnboardingCompleted(null)).resolves.toBeUndefined();
    expect(warn).toHaveBeenCalled();

    warn.mockRestore();
  });
});
