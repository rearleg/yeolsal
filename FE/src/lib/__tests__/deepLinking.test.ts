// Story 6.2 AC2/AC3/AC11 — KakaoTalk share-tap deep-link handler.
// Verifies the URL parser routing matrix:
//   /join?code=X        + authed → router.push("/join?code=X")
//   /join?code=X        + guest  → SecureStore set + router.replace("/signup")
//   /join (no code)                → no-op (early return)
//   /other path                    → no-op (early return)
//
// Plus consumePendingInviteCode: read-then-delete idempotency + null-on-error.

// Short-circuit the AuthContext → query/persist → AsyncStorage import
// chain. The route() helper under test never invokes useAuth at runtime
// — only the hook does — so a no-op mock is safe and avoids the
// native-AsyncStorage initialisation gate in Jest.
jest.mock("../../auth/AuthContext", () => ({
  useAuth: jest.fn(() => ({ user: null, loading: false })),
}));

jest.mock("expo-linking", () => ({
  parse: jest.fn(),
  getInitialURL: jest.fn(() => Promise.resolve(null)),
  addEventListener: jest.fn(() => ({ remove: jest.fn() })),
}));

jest.mock("expo-secure-store", () => ({
  getItemAsync: jest.fn(),
  setItemAsync: jest.fn(),
  deleteItemAsync: jest.fn(),
}));

jest.mock("expo-router", () => ({
  router: { push: jest.fn(), replace: jest.fn() },
}));

import * as Linking from "expo-linking";
import { router } from "expo-router";
import * as SecureStore from "expo-secure-store";
import { act, renderHook, waitFor } from "@testing-library/react-native";
import { useAuth } from "../../auth/AuthContext";
import {
  __PENDING_INVITE_KEY_FOR_TESTS,
  consumePendingInviteCode,
  routeShareLink,
  useShareLinkDeepLink,
} from "../deepLinking";

const mockParse = Linking.parse as jest.MockedFunction<typeof Linking.parse>;
const mockPush = router.push as jest.MockedFunction<typeof router.push>;
const mockReplace = router.replace as jest.MockedFunction<typeof router.replace>;
const mockSetItem = SecureStore.setItemAsync as jest.MockedFunction<
  typeof SecureStore.setItemAsync
>;
const mockGetItem = SecureStore.getItemAsync as jest.MockedFunction<
  typeof SecureStore.getItemAsync
>;
const mockDeleteItem = SecureStore.deleteItemAsync as jest.MockedFunction<
  typeof SecureStore.deleteItemAsync
>;

describe("deep-link routing matrix (Story 6.2 AC2)", () => {
  beforeEach(() => {
    mockParse.mockReset();
    mockPush.mockReset();
    mockReplace.mockReset();
    mockSetItem.mockReset();
    mockGetItem.mockReset();
    mockDeleteItem.mockReset();
  });

  it("authenticated /join?code=X → router.push(/join?code=X)", async () => {
    mockParse.mockReturnValueOnce({
      path: "join",
      queryParams: { code: "ABCD1234" },
      scheme: null,
      hostname: null,
    } as unknown as ReturnType<typeof Linking.parse>);

    await routeShareLink("https://yeolsal.app/join?code=ABCD1234", true);

    expect(mockPush).toHaveBeenCalledWith("/join?code=ABCD1234");
    expect(mockSetItem).not.toHaveBeenCalled();
    expect(mockReplace).not.toHaveBeenCalled();
  });

  it("unauthenticated /join?code=X persists before router.replace(/signup)", async () => {
    mockParse.mockReturnValueOnce({
      path: "join",
      queryParams: { code: "ABCD1234" },
      scheme: null,
      hostname: null,
    } as unknown as ReturnType<typeof Linking.parse>);

    let resolveWrite: (() => void) | undefined;
    mockSetItem.mockImplementationOnce(
      () => new Promise<void>((resolve) => {
        resolveWrite = resolve;
      }),
    );
    const routing = routeShareLink(
      "https://yeolsal.app/join?code=ABCD1234",
      false,
    );

    expect(mockSetItem).toHaveBeenCalledWith(
      __PENDING_INVITE_KEY_FOR_TESTS,
      "ABCD1234",
    );
    expect(mockReplace).not.toHaveBeenCalled();
    resolveWrite?.();
    await routing;
    expect(mockReplace).toHaveBeenCalledWith("/signup");
    expect(mockPush).not.toHaveBeenCalled();
  });

  it("non-join path → no-op (e.g. /rooms/42)", async () => {
    mockParse.mockReturnValueOnce({
      path: "rooms/42",
      queryParams: {},
      scheme: null,
      hostname: null,
    } as unknown as ReturnType<typeof Linking.parse>);

    await routeShareLink("https://yeolsal.app/rooms/42", true);

    expect(mockPush).not.toHaveBeenCalled();
    expect(mockReplace).not.toHaveBeenCalled();
    expect(mockSetItem).not.toHaveBeenCalled();
  });

  it("missing ?code query → no-op", async () => {
    mockParse.mockReturnValueOnce({
      path: "join",
      queryParams: {},
      scheme: null,
      hostname: null,
    } as unknown as ReturnType<typeof Linking.parse>);

    await routeShareLink("https://yeolsal.app/join", true);

    expect(mockPush).not.toHaveBeenCalled();
    expect(mockSetItem).not.toHaveBeenCalled();
  });

  it("yeosal://join?code=X (custom scheme fallback) parses identically", async () => {
    mockParse.mockReturnValueOnce({
      path: "join",
      queryParams: { code: "WXYZ4321" },
      scheme: "yeosal",
      hostname: null,
    } as unknown as ReturnType<typeof Linking.parse>);

    await routeShareLink("yeosal://join?code=WXYZ4321", true);

    expect(mockPush).toHaveBeenCalledWith("/join?code=WXYZ4321");
  });
});

describe("useShareLinkDeepLink lifecycle", () => {
  const mockUseAuth = useAuth as jest.MockedFunction<typeof useAuth>;
  const mockGetInitialUrl = Linking.getInitialURL as jest.MockedFunction<
    typeof Linking.getInitialURL
  >;

  beforeEach(() => {
    mockGetInitialUrl.mockReset();
    mockGetInitialUrl.mockResolvedValue(
      "https://yeolsal.app/join?code=ABCD1234",
    );
    mockUseAuth.mockReturnValue({
      user: null,
      loading: false,
      signIn: jest.fn(),
      signUp: jest.fn(),
      signInWithKakao: jest.fn(),
      signOut: jest.fn(),
    });
    mockParse.mockReturnValue({
      path: "join",
      queryParams: { code: "ABCD1234" },
      scheme: "https",
      hostname: "yeolsal.app",
    } as unknown as ReturnType<typeof Linking.parse>);
    mockSetItem.mockResolvedValue(undefined);
  });

  it("reads the cold-launch URL only once across auth-state changes", async () => {
    const { rerender } = renderHook(() => useShareLinkDeepLink());
    await waitFor(() => expect(mockGetInitialUrl).toHaveBeenCalledTimes(1));

    mockUseAuth.mockReturnValue({
      ...mockUseAuth(),
      user: {
        id: 7,
        email: "a@example.com",
        nickname: "alice",
        timezone: "Asia/Seoul",
      },
    });
    act(() => rerender(undefined));

    expect(mockGetInitialUrl).toHaveBeenCalledTimes(1);
  });
});

describe("consumePendingInviteCode (Story 6.2 AC3)", () => {
  beforeEach(() => {
    mockGetItem.mockReset();
    mockDeleteItem.mockReset();
  });

  it("read-then-delete returns the code and clears the slot", async () => {
    mockGetItem.mockResolvedValueOnce("ABCD1234");
    mockDeleteItem.mockResolvedValueOnce(undefined);

    const code = await consumePendingInviteCode();

    expect(code).toBe("ABCD1234");
    expect(mockGetItem).toHaveBeenCalledWith(__PENDING_INVITE_KEY_FOR_TESTS);
    expect(mockDeleteItem).toHaveBeenCalledWith(__PENDING_INVITE_KEY_FOR_TESTS);
  });

  it("returns null and skips delete when the slot is empty", async () => {
    mockGetItem.mockResolvedValueOnce(null);

    const code = await consumePendingInviteCode();

    expect(code).toBeNull();
    expect(mockDeleteItem).not.toHaveBeenCalled();
  });

  it("returns null and swallows SecureStore errors (locked-down enterprise build defence)", async () => {
    mockGetItem.mockRejectedValueOnce(new Error("locked-down build"));

    const code = await consumePendingInviteCode();

    expect(code).toBeNull();
  });
});
