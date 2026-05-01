// Jest setup for yeosal-fe — runs before each test file.
// Keep mocks minimal. Per-test mocks belong in the test file itself.

jest.mock("react-native/Libraries/Animated/NativeAnimatedHelper", () => ({}), {
  virtual: true,
});
