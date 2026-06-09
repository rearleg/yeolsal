// Story 8.5 FE-2 (AC4 + AC15 row 2) — SecureStore-backed PIPA consent
// store. Mirrors the `playedRevivalEvents.test.ts` shape: jest.mock the
// native module, isolate the in-memory cache between tests via
// `clearAnalyticsConsent`.

jest.mock("expo-secure-store", () => ({
  setItemAsync: jest.fn(),
  getItemAsync: jest.fn(),
  deleteItemAsync: jest.fn(),
}));

jest.mock("../analytics", () => ({
  __esModule: true,
  postHogOptIn: jest.fn(),
  postHogOptOut: jest.fn(),
}));

import {
  clearAnalyticsConsent,
  getAnalyticsConsent,
  setAnalyticsConsent,
} from "../analyticsConsent";
import { postHogOptIn, postHogOptOut } from "../analytics";

const SecureStoreMock = jest.requireMock("expo-secure-store") as {
  setItemAsync: jest.Mock;
  getItemAsync: jest.Mock;
  deleteItemAsync: jest.Mock;
};

const STORAGE_KEY = "yeosal.analyticsConsent";

describe("analyticsConsent helper (Story 8.5 AC4)", () => {
  beforeEach(async () => {
    jest.clearAllMocks();
    SecureStoreMock.getItemAsync.mockResolvedValue(null);
    SecureStoreMock.setItemAsync.mockResolvedValue(undefined);
    SecureStoreMock.deleteItemAsync.mockResolvedValue(undefined);
    await clearAnalyticsConsent();
    jest.clearAllMocks();
  });

  it("getAnalyticsConsent returns null on first read when SecureStore is empty", async () => {
    SecureStoreMock.getItemAsync.mockResolvedValue(null);
    expect(await getAnalyticsConsent()).toBeNull();
    expect(SecureStoreMock.getItemAsync).toHaveBeenCalledWith(STORAGE_KEY);
  });

  it("setAnalyticsConsent('opt_in') writes SecureStore and calls posthog.optIn", async () => {
    await setAnalyticsConsent("opt_in");
    expect(SecureStoreMock.setItemAsync).toHaveBeenCalledTimes(1);
    const call = SecureStoreMock.setItemAsync.mock.calls[0];
    expect(call[0]).toBe(STORAGE_KEY);
    const parsed = JSON.parse(call[1] as string) as { value: string; at: string };
    expect(parsed.value).toBe("opt_in");
    expect(parsed.at).toMatch(/^\d{4}-\d{2}-\d{2}T/);
    expect(postHogOptIn).toHaveBeenCalledTimes(1);
    expect(postHogOptOut).not.toHaveBeenCalled();
  });

  it("setAnalyticsConsent('opt_out') writes SecureStore and calls posthog.optOut", async () => {
    await setAnalyticsConsent("opt_out");
    expect(SecureStoreMock.setItemAsync).toHaveBeenCalledTimes(1);
    const raw = SecureStoreMock.setItemAsync.mock.calls[0][1] as string;
    const parsed = JSON.parse(raw) as { value: string; at: string };
    expect(parsed.value).toBe("opt_out");
    expect(postHogOptOut).toHaveBeenCalledTimes(1);
    expect(postHogOptIn).not.toHaveBeenCalled();
  });

  it("applies opt-out before a slow SecureStore write completes", async () => {
    let releaseWrite: (() => void) | undefined;
    SecureStoreMock.setItemAsync.mockImplementationOnce(
      () => new Promise<void>((resolve) => {
        releaseWrite = resolve;
      }),
    );
    const pending = setAnalyticsConsent("opt_out");
    expect(postHogOptOut).toHaveBeenCalledTimes(1);
    await Promise.resolve();
    expect(SecureStoreMock.setItemAsync).toHaveBeenCalledTimes(1);
    releaseWrite?.();
    await pending;
  });

  it("keeps the newest consent when an older SecureStore read resolves late", async () => {
    let releaseRead: ((value: string | null) => void) | undefined;
    SecureStoreMock.getItemAsync.mockImplementationOnce(
      () => new Promise<string | null>((resolve) => {
        releaseRead = resolve;
      }),
    );
    const pendingRead = getAnalyticsConsent();
    await setAnalyticsConsent("opt_out");
    releaseRead?.(JSON.stringify({ value: "opt_in", at: "2026-06-09T00:00:00.000Z" }));
    expect(await pendingRead).toBe("opt_out");
    expect(await getAnalyticsConsent()).toBe("opt_out");
  });

  it("getAnalyticsConsent hits in-memory cache on second read (no second SecureStore call)", async () => {
    SecureStoreMock.getItemAsync.mockResolvedValue(
      JSON.stringify({ value: "opt_in", at: "2026-06-09T00:00:00.000Z" }),
    );
    expect(await getAnalyticsConsent()).toBe("opt_in");
    expect(await getAnalyticsConsent()).toBe("opt_in");
    expect(SecureStoreMock.getItemAsync).toHaveBeenCalledTimes(1);
  });

  it("setAnalyticsConsent swallows SecureStore failures without throwing", async () => {
    SecureStoreMock.setItemAsync.mockRejectedValueOnce(new Error("kaboom"));
    await expect(setAnalyticsConsent("opt_in")).resolves.toBeUndefined();
    // SDK side still invoked so runtime state matches user intent even if
    // the durable write failed; next boot reverts to fail-closed if the
    // write was truly lost.
    expect(postHogOptIn).toHaveBeenCalledTimes(1);
  });

  it("clearAnalyticsConsent clears the in-memory cache and deletes SecureStore key", async () => {
    SecureStoreMock.getItemAsync.mockResolvedValue(
      JSON.stringify({ value: "opt_in", at: "2026-06-09T00:00:00.000Z" }),
    );
    expect(await getAnalyticsConsent()).toBe("opt_in");
    await clearAnalyticsConsent();
    SecureStoreMock.getItemAsync.mockResolvedValue(null);
    expect(await getAnalyticsConsent()).toBeNull();
    expect(SecureStoreMock.deleteItemAsync).toHaveBeenCalledWith(STORAGE_KEY);
  });
});
