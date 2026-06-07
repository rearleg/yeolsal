// Jest setup for yeosal-fe — runs before each test file.
// Keep mocks minimal. Per-test mocks belong in the test file itself.

jest.mock("react-native/Libraries/Animated/NativeAnimatedHelper", () => ({}), {
  virtual: true,
});

// @sentry/react-native starts an AsyncExpiringMap setInterval at module
// import time (via reactnavigation → timeToDisplayIntegration). That timer
// keeps the Jest worker alive past test completion. We don't exercise
// Sentry in unit tests, so stub it out.
jest.mock("@sentry/react-native", () => ({
  init: jest.fn(),
  setUser: jest.fn(),
  captureException: jest.fn(),
  captureMessage: jest.fn(),
  addBreadcrumb: jest.fn(),
  withScope: (cb: (scope: { setContext: jest.Mock }) => void) => cb({ setContext: jest.fn() }),
  ReactNativeTracing: jest.fn(),
}));

// Story 6.2 — KakaoTalk Share SDK + Core. The native modules are not loadable
// in Jest so we expose only the surface the FE wrapper / boot path uses:
// shareFeedTemplate + initializeKakaoSDK. Tests assert call shape against
// these jest.fn()s.
jest.mock("@react-native-kakao/share", () => ({
  shareFeedTemplate: jest.fn(),
}));

jest.mock("@react-native-kakao/core", () => ({
  initializeKakaoSDK: jest.fn(),
}));
