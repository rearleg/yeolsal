// Story 8.5 FE-1 (AC8 + AC15 row 3) — guard pins for the new PostHog
// env-var exports. Each test isolates `globalThis.process.env` before
// re-importing the config module so the module-init reads the patched
// value (mirrors the same pattern other env-driven config tests use).

describe("api/config — PostHog env vars (Story 8.5 AC8)", () => {
  const originalProcess = (globalThis as { process?: unknown }).process;

  afterEach(() => {
    if (originalProcess === undefined) {
      delete (globalThis as { process?: unknown }).process;
    } else {
      (globalThis as { process?: unknown }).process = originalProcess;
    }
    jest.resetModules();
  });

  function loadConfigWithEnv(env: Record<string, string | undefined>): typeof import("../config") {
    jest.resetModules();
    (globalThis as { process?: { env?: Record<string, string | undefined> } }).process = {
      env: { ...env },
    };
    // eslint-disable-next-line @typescript-eslint/no-require-imports
    return require("../config") as typeof import("../config");
  }

  it("POSTHOG_HOST defaults to empty string when EXPO_PUBLIC_POSTHOG_HOST is absent", () => {
    const cfg = loadConfigWithEnv({});
    expect(cfg.POSTHOG_HOST).toBe("");
  });

  it("POSTHOG_API_KEY defaults to empty string when EXPO_PUBLIC_POSTHOG_API_KEY is absent", () => {
    const cfg = loadConfigWithEnv({});
    expect(cfg.POSTHOG_API_KEY).toBe("");
  });

  it("ANALYTICS_INTERNAL_BUILD is false unless EXPO_PUBLIC_INTERNAL_BUILD is the literal string 'true'", () => {
    expect(loadConfigWithEnv({}).ANALYTICS_INTERNAL_BUILD).toBe(false);
    expect(loadConfigWithEnv({ EXPO_PUBLIC_INTERNAL_BUILD: "false" }).ANALYTICS_INTERNAL_BUILD).toBe(
      false,
    );
    expect(loadConfigWithEnv({ EXPO_PUBLIC_INTERNAL_BUILD: "1" }).ANALYTICS_INTERNAL_BUILD).toBe(
      false,
    );
    expect(loadConfigWithEnv({ EXPO_PUBLIC_INTERNAL_BUILD: "true" }).ANALYTICS_INTERNAL_BUILD).toBe(
      true,
    );
  });

  it("POSTHOG_HOST + POSTHOG_API_KEY read EXPO_PUBLIC_* vars verbatim when present", () => {
    const cfg = loadConfigWithEnv({
      EXPO_PUBLIC_POSTHOG_HOST: "https://analytics.example.com",
      EXPO_PUBLIC_POSTHOG_API_KEY: "phc_test_project_api_key",
    });
    expect(cfg.POSTHOG_HOST).toBe("https://analytics.example.com");
    expect(cfg.POSTHOG_API_KEY).toBe("phc_test_project_api_key");
  });
});
