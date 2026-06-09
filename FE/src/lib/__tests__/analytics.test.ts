// Story 8.5 FE-3 (AC2 + AC5 + AC15 row 1) — PostHog SDK guard pattern.
// Mirrors `sentry.ts` shape: module-level env guard, one-shot init,
// no-op-when-disabled helpers. Every test re-imports the module so the
// initialized flag and the captured PostHog stub start from scratch.

type PostHogStub = {
  capture: jest.Mock;
  identify: jest.Mock;
  reset: jest.Mock;
  optIn: jest.Mock;
  optOut: jest.Mock;
};

// Jest factory may only reference identifiers whose names start with
// `mock` (case-insensitive). The variable below is closed-over by the
// jest.mock factory below.
let mockLastInstance: PostHogStub | null = null;

jest.mock("posthog-react-native", () => ({
  __esModule: true,
  PostHog: jest.fn(() => mockLastInstance),
}));

function loadAnalyticsWithEnv(env: Record<string, string | undefined>): {
  mod: typeof import("../analytics");
  posthog: PostHogStub;
} {
  jest.resetModules();
  mockLastInstance = {
    capture: jest.fn(),
    identify: jest.fn(),
    reset: jest.fn(),
    optIn: jest.fn().mockResolvedValue(undefined),
    optOut: jest.fn().mockResolvedValue(undefined),
  };
  const PostHogModule = jest.requireMock("posthog-react-native") as { PostHog: jest.Mock };
  PostHogModule.PostHog.mockClear();
  PostHogModule.PostHog.mockImplementation(() => mockLastInstance);
  (globalThis as { process?: { env?: Record<string, string | undefined> } }).process = {
    env: { ...env },
  };
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  const mod = require("../analytics") as typeof import("../analytics");
  return { mod, posthog: mockLastInstance! };
}

describe("FE/src/lib/analytics — PostHog guard pattern (Story 8.5 AC2)", () => {
  afterEach(() => {
    delete (globalThis as { process?: unknown }).process;
    jest.resetModules();
  });

  it("bootstrapAnalytics is a silent no-op when EXPO_PUBLIC_POSTHOG_API_KEY is absent", () => {
    const { mod } = loadAnalyticsWithEnv({});
    const PostHogModule = jest.requireMock("posthog-react-native") as { PostHog: jest.Mock };
    mod.bootstrapAnalytics();
    expect(PostHogModule.PostHog).not.toHaveBeenCalled();
    expect(mod.isAnalyticsEnabled()).toBe(false);
  });

  it("bootstrapAnalytics initializes PostHog exactly once when key is set", () => {
    const { mod } = loadAnalyticsWithEnv({
      EXPO_PUBLIC_POSTHOG_HOST: "https://analytics.example.com",
      EXPO_PUBLIC_POSTHOG_API_KEY: "phc_test",
    });
    const PostHogModule = jest.requireMock("posthog-react-native") as { PostHog: jest.Mock };
    mod.bootstrapAnalytics();
    mod.bootstrapAnalytics();
    expect(PostHogModule.PostHog).toHaveBeenCalledTimes(1);
    expect(PostHogModule.PostHog).toHaveBeenCalledWith("phc_test", {
      host: "https://analytics.example.com",
      captureAppLifecycleEvents: false,
      enableSessionReplay: false,
      defaultOptIn: false,
    });
    expect(mod.isAnalyticsEnabled()).toBe(true);
  });

  it("bootstrapAnalytics stays disabled when a key is set without a valid self-host URL", () => {
    const { mod } = loadAnalyticsWithEnv({
      EXPO_PUBLIC_POSTHOG_API_KEY: "phc_test",
    });
    const PostHogModule = jest.requireMock("posthog-react-native") as { PostHog: jest.Mock };
    mod.bootstrapAnalytics();
    expect(PostHogModule.PostHog).not.toHaveBeenCalled();
    expect(mod.isAnalyticsEnabled()).toBe(false);
  });

  it("captureEvent delegates to PostHog client when initialized", () => {
    const { mod, posthog } = loadAnalyticsWithEnv({
      EXPO_PUBLIC_POSTHOG_API_KEY: "phc_test",
      EXPO_PUBLIC_POSTHOG_HOST: "https://analytics.example.com",
    });
    mod.bootstrapAnalytics();
    mod.captureEvent("signup.completed", { authMethod: "EMAIL" });
    expect(posthog.capture).toHaveBeenCalledTimes(1);
    expect(posthog.capture).toHaveBeenCalledWith("signup.completed", { authMethod: "EMAIL" });
  });

  it("captureEvent silently no-ops when disabled (no key)", () => {
    const { mod, posthog } = loadAnalyticsWithEnv({});
    mod.bootstrapAnalytics();
    mod.captureEvent("signup.completed", { authMethod: "EMAIL" });
    expect(posthog.capture).not.toHaveBeenCalled();
  });

  it("setAnalyticsUser({ ... }) calls identify with the expected non-PII person properties", () => {
    const { mod, posthog } = loadAnalyticsWithEnv({
      EXPO_PUBLIC_POSTHOG_API_KEY: "phc_test",
      EXPO_PUBLIC_POSTHOG_HOST: "https://analytics.example.com",
    });
    mod.bootstrapAnalytics();
    mod.setAnalyticsUser({
      id: 42,
      currentSurvivalState: "ACTIVE",
      isRoomLeader: true,
    });
    expect(posthog.identify).toHaveBeenCalledTimes(1);
    const [distinctId, props] = posthog.identify.mock.calls[0];
    expect(distinctId).toBe("42");
    expect(props).toMatchObject({
      room_count: 1,
      current_survival_state: "ACTIVE",
      is_room_leader: true,
      is_internal: false,
    });
    // PIPA gold: no PII keys allowed on the person record.
    expect(Object.keys(props as object)).not.toContain("email");
    expect(Object.keys(props as object)).not.toContain("nickname");
  });

  it("setAnalyticsUser(null) calls posthog.reset (sign-out)", () => {
    const { mod, posthog } = loadAnalyticsWithEnv({
      EXPO_PUBLIC_POSTHOG_API_KEY: "phc_test",
      EXPO_PUBLIC_POSTHOG_HOST: "https://analytics.example.com",
    });
    mod.bootstrapAnalytics();
    mod.setAnalyticsUser(null);
    expect(posthog.reset).toHaveBeenCalledTimes(1);
    expect(posthog.identify).not.toHaveBeenCalled();
  });

  it("postHogOptIn / postHogOptOut delegate to the SDK when initialized, no-op otherwise", () => {
    const enabled = loadAnalyticsWithEnv({
      EXPO_PUBLIC_POSTHOG_API_KEY: "phc_test",
      EXPO_PUBLIC_POSTHOG_HOST: "https://analytics.example.com",
    });
    enabled.mod.bootstrapAnalytics();
    enabled.mod.postHogOptIn();
    enabled.mod.postHogOptOut();
    expect(enabled.posthog.optIn).toHaveBeenCalledTimes(1);
    expect(enabled.posthog.optOut).toHaveBeenCalledTimes(1);

    const disabled = loadAnalyticsWithEnv({});
    disabled.mod.bootstrapAnalytics();
    disabled.mod.postHogOptIn();
    disabled.mod.postHogOptOut();
    expect(disabled.posthog.optIn).not.toHaveBeenCalled();
    expect(disabled.posthog.optOut).not.toHaveBeenCalled();
  });

  it("captureEvent swallows RuntimeException from the SDK without throwing", () => {
    const { mod, posthog } = loadAnalyticsWithEnv({
      EXPO_PUBLIC_POSTHOG_API_KEY: "phc_test",
      EXPO_PUBLIC_POSTHOG_HOST: "https://analytics.example.com",
    });
    mod.bootstrapAnalytics();
    posthog.capture.mockImplementation(() => {
      throw new Error("kaboom");
    });
    expect(() => mod.captureEvent("signup.completed", { authMethod: "EMAIL" })).not.toThrow();
  });

  it("addAnalyticsBreadcrumb never emits an event outside the locked taxonomy", () => {
    const { mod, posthog } = loadAnalyticsWithEnv({
      EXPO_PUBLIC_POSTHOG_API_KEY: "phc_test",
      EXPO_PUBLIC_POSTHOG_HOST: "https://analytics.example.com",
    });
    mod.bootstrapAnalytics();
    mod.addAnalyticsBreadcrumb({
      category: "test",
      level: "info",
      message: "ignored",
    });
    expect(posthog.capture).not.toHaveBeenCalled();
  });

  it("postHogOptOut swallows asynchronous SDK rejection", async () => {
    const { mod, posthog } = loadAnalyticsWithEnv({
      EXPO_PUBLIC_POSTHOG_API_KEY: "phc_test",
      EXPO_PUBLIC_POSTHOG_HOST: "https://analytics.example.com",
    });
    mod.bootstrapAnalytics();
    posthog.optOut.mockRejectedValueOnce(new Error("persist failed"));
    expect(() => mod.postHogOptOut()).not.toThrow();
    await Promise.resolve();
    expect(posthog.optOut).toHaveBeenCalledTimes(1);
  });

  it("ANALYTICS_EVENTS catalogue contains all 21 locked event names", () => {
    const { mod } = loadAnalyticsWithEnv({});
    expect(mod.ANALYTICS_EVENTS).toEqual([
      "signup.completed",
      "onboarding.screen.dwell_ms",
      "onboarding.completed",
      "first_daily_entry",
      "activation.24h_complete",
      "revival.attempted",
      "revival.succeeded",
      "revival.failed",
      "kudos.sent",
      "kudos.received",
      "friend_gift.push_sent",
      "friend_gift.push_opened",
      "friend_gift.modal_opened",
      "friend_gift.modal_closed",
      "spectator.entered",
      "spectator.app_opened",
      "spectator.wallet_viewed",
      "spectator.revival_succeeded.day_n",
      "final_three.poster_viewed",
      "final_three.share_tapped",
      "final_three.share_completed",
    ]);
  });
});
