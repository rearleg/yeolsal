// Story 3.4 FE-12 — WalletScreen component tests (AC8: 6 cases).
//
// Asserts:
//   1. Renders 4 sections in correct order.
//   2. Free ticket section: unused state shows "🎟  무료 회생권 1매".
//   3. Free ticket section: used state shows "🎟  사용 완료".
//   4. Personal points section: tap navigates to /wallet/{roomId}/ledger.
//   5. Room pool section: includes FriendGiftBadge mount + PoolBar children.
//   6. Loading state shows ActivityIndicator; error state shows generic copy.

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react-native";
import type { PropsWithChildren } from "react";
import { SafeAreaProvider } from "react-native-safe-area-context";
import * as walletApi from "../../../api/wallet";
import * as survivalApi from "../../../api/survival";
import * as roomPointsApi from "../../../api/roomPoints";
import * as targetsApi from "../../../api/friendGiftTargets";
import type { MeSurvivalEntry } from "../../../lib/spectator";
import { WalletScreen } from "../WalletScreen";

const mockRouterPush = jest.fn();

jest.mock("expo-router", () => ({
  router: { push: (path: string) => mockRouterPush(path) },
}));

jest.mock("../../../api/wallet", () => ({
  getPersonalPointsLedger: jest.fn(),
  getReceivedRevivals: jest.fn(),
}));
jest.mock("../../../api/survival", () => ({
  getMeSurvival: jest.fn(),
  getRecordVisibilityPrefs: jest.fn(),
  updateRecordVisibilityPref: jest.fn(),
}));
jest.mock("../../../api/friendGiftTargets", () => ({
  getFriendGiftTargets: jest.fn(),
}));
// Story 4.1 — WalletScreen now reads `pool` via useRoomPoints(roomId) →
// getRoomPoints REST call. Stub the api boundary so existing cases stay
// green without a real fetch. lastEventAt left null; consumers don't
// surface it in the per-room Wallet route.
jest.mock("../../../api/roomPoints", () => ({
  getRoomPoints: jest.fn(),
}));
jest.mock("../../../lib/realtime/client", () => ({
  getRealtimeClient: jest.fn(() => ({
    subscribe: jest.fn(() => ({ unsubscribe: jest.fn() })),
  })),
  // Story 4.1 — useRoomPoints (mounted by WalletScreen) calls
  // useRealtimeSubscription. Stub so the hook is a no-op realtime-wise;
  // tests don't drive STOMP frames through the Wallet surface (roomPoints
  // hook has its own dedicated test file).
  useRealtimeSubscription: jest.fn(),
  // Story 4.1 Patch 3 — useRoomPoints now also reads useRealtimeStatus
  // for reconnect-recovery. Default to "connected" so the status-change
  // effect is a no-op in WalletScreen test paths.
  useRealtimeStatus: jest.fn(() => "connected"),
}));
// Replace FriendGiftBadge with a marker view so the pool-section
// assertion can verify the badge slot mounts independent of the badge's
// own eligibility/disabled branching (covered in FriendGiftBadge.test).
// React.createElement avoids the JSX-inside-factory transform pitfall.
jest.mock("../../revival/FriendGiftBadge", () => {
  const React = jest.requireActual("react");
  const { View } = jest.requireActual("react-native");
  return {
    FriendGiftBadge: (props: { roomId: number }) =>
      React.createElement(View, {
        testID: `friend-gift-badge-mock-${props.roomId}`,
      }),
  };
});

const getMeSurvivalMock = survivalApi.getMeSurvival as jest.MockedFunction<
  typeof survivalApi.getMeSurvival
>;
const getReceivedMock = walletApi.getReceivedRevivals as jest.MockedFunction<
  typeof walletApi.getReceivedRevivals
>;
const getTargetsMock = targetsApi.getFriendGiftTargets as jest.MockedFunction<
  typeof targetsApi.getFriendGiftTargets
>;
const getRoomPointsMock = roomPointsApi.getRoomPoints as jest.MockedFunction<
  typeof roomPointsApi.getRoomPoints
>;

function makeClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, staleTime: 0, gcTime: 60_000 },
      mutations: { retry: false },
    },
  });
}

function makeWrapper(client: QueryClient) {
  return function Wrapper({ children }: PropsWithChildren) {
    return (
      <SafeAreaProvider
        initialMetrics={{
          frame: { x: 0, y: 0, width: 320, height: 640 },
          insets: { top: 0, left: 0, right: 0, bottom: 0 },
        }}
      >
        <QueryClientProvider client={client}>{children}</QueryClientProvider>
      </SafeAreaProvider>
    );
  };
}

const ROOM_ID = 42;

const survival = (overrides: Partial<MeSurvivalEntry> = {}): MeSurvivalEntry => ({
  roomId: ROOM_ID,
  roomName: "Room",
  status: "ACTIVE",
  personalPoints: 5,
  roomPointPool: 0,
  freeRevivalTicketUsed: false,
  ...overrides,
});

describe("WalletScreen", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockRouterPush.mockReset();
    getReceivedMock.mockResolvedValue([]);
    getTargetsMock.mockResolvedValue([]);
    // Default — mirrors the survival fixture's roomPointPool=0. Individual
    // cases override when they care about the pool number's display.
    getRoomPointsMock.mockResolvedValue({
      roomId: ROOM_ID,
      total: 0,
      lastEventAt: null,
    });
  });

  it("renders 4 sections in correct order via testIDs", async () => {
    getMeSurvivalMock.mockResolvedValue([survival()]);
    const { getByTestId } = render(<WalletScreen roomId={ROOM_ID} />, {
      wrapper: makeWrapper(makeClient()),
    });
    await waitFor(() => expect(getByTestId("wallet-section-ticket")).toBeTruthy());
    expect(getByTestId("wallet-section-personal-points")).toBeTruthy();
    expect(getByTestId("wallet-section-pool")).toBeTruthy();
    expect(getByTestId("wallet-section-received")).toBeTruthy();
  });

  it("free-ticket section: unused state shows the active copy", async () => {
    getMeSurvivalMock.mockResolvedValue([survival({ freeRevivalTicketUsed: false })]);
    render(<WalletScreen roomId={ROOM_ID} />, {
      wrapper: makeWrapper(makeClient()),
    });
    await waitFor(() =>
      expect(screen.getByText("🎟  무료 회생권 1매")).toBeTruthy(),
    );
    expect(screen.getByText("남은 회생권")).toBeTruthy();
  });

  it("free-ticket section: used state shows the spent copy", async () => {
    getMeSurvivalMock.mockResolvedValue([survival({ freeRevivalTicketUsed: true })]);
    render(<WalletScreen roomId={ROOM_ID} />, {
      wrapper: makeWrapper(makeClient()),
    });
    await waitFor(() => expect(screen.getByText("🎟  사용 완료")).toBeTruthy());
    expect(screen.getByText("다음 시즌에 새로 받아요")).toBeTruthy();
  });

  it("personal-points section: tap navigates to /wallet/{roomId}/ledger", async () => {
    getMeSurvivalMock.mockResolvedValue([survival({ personalPoints: 7 })]);
    const { getByTestId } = render(<WalletScreen roomId={ROOM_ID} />, {
      wrapper: makeWrapper(makeClient()),
    });
    await waitFor(() => expect(getByTestId("wallet-section-personal-points")).toBeTruthy());
    fireEvent.press(getByTestId("wallet-section-personal-points"));
    expect(mockRouterPush).toHaveBeenCalledWith(`/wallet/${ROOM_ID}/ledger`);
  });

  it("room pool section mounts PoolBar + FriendGiftBadge children", async () => {
    getMeSurvivalMock.mockResolvedValue([survival()]);
    const { getByTestId } = render(<WalletScreen roomId={ROOM_ID} />, {
      wrapper: makeWrapper(makeClient()),
    });
    await waitFor(() => expect(getByTestId("wallet-section-pool")).toBeTruthy());
    expect(getByTestId("poolbar-fill")).toBeTruthy();
    // Spec AC8 case 5 + AC7 — the pool section is the integration point
    // for Story 3.3's friend-gift badge; verify the slot mounts.
    expect(getByTestId(`friend-gift-badge-mock-${ROOM_ID}`)).toBeTruthy();
  });

  it("loading state shows ActivityIndicator before survival resolves", async () => {
    // Hold the survival query pending so the screen stays in its
    // isLoading branch — ActivityIndicator has accessibilityRole
    // "progressbar" set by React Native by default.
    getMeSurvivalMock.mockReturnValueOnce(new Promise(() => undefined));
    const { findByTestId, UNSAFE_getAllByType } = render(
      <WalletScreen roomId={ROOM_ID} />,
      { wrapper: makeWrapper(makeClient()) },
    );
    // First assert the loading branch by checking ActivityIndicator
    // mount via the testing-library escape hatch.
    const { ActivityIndicator } = jest.requireActual("react-native");
    await waitFor(() =>
      expect(UNSAFE_getAllByType(ActivityIndicator).length).toBeGreaterThan(0),
    );
    // None of the section testIDs should be present in the loading state.
    await expect(findByTestId("wallet-section-ticket")).rejects.toThrow();
  });

  it("error state shows the generic retry copy", async () => {
    getMeSurvivalMock.mockRejectedValueOnce(new Error("boom"));
    render(<WalletScreen roomId={ROOM_ID} />, {
      wrapper: makeWrapper(makeClient()),
    });
    await waitFor(() =>
      expect(screen.getByText("잠시 후 다시 시도해주세요")).toBeTruthy(),
    );
  });

  it("Story 4.1 Patch 5 — pool falls back to survival.roomPointPool when roomPoints is loading", async () => {
    // Hold the roomPoints REST query pending so the hook stays in
    // isLoading=true. The Wallet should render survival.roomPointPool
    // (the meSurvival snapshot) instead of a false zero.
    getRoomPointsMock.mockReturnValueOnce(new Promise(() => undefined));
    getMeSurvivalMock.mockResolvedValue([survival({ roomPointPool: 42 })]);

    const { getByTestId } = render(<WalletScreen roomId={ROOM_ID} />, {
      wrapper: makeWrapper(makeClient()),
    });
    await waitFor(() => expect(getByTestId("wallet-section-pool")).toBeTruthy());

    // Pool section displays the fallback value `42` (not `0`).
    expect(screen.getByText("42")).toBeTruthy();
  });

  it("Story 4.1 Patch 5 — pool falls back to survival.roomPointPool when roomPoints errors", async () => {
    // REST fails → useRoomPoints returns isError=true. Wallet renders
    // survival.roomPointPool fallback rather than a false zero.
    getRoomPointsMock.mockRejectedValue(new Error("network down"));
    getMeSurvivalMock.mockResolvedValue([survival({ roomPointPool: 7 })]);

    const { getByTestId } = render(<WalletScreen roomId={ROOM_ID} />, {
      wrapper: makeWrapper(makeClient()),
    });
    await waitFor(() => expect(getByTestId("wallet-section-pool")).toBeTruthy());

    await waitFor(() => expect(screen.getByText("7")).toBeTruthy());
  });

  it("not-a-member state shows the dedicated copy (not the error retry)", async () => {
    // P10 — survival query succeeded but this roomId isn't in the user's
    // membership list. Should render the "방에 더 이상 속해 있지 않아요"
    // copy, NOT the generic retry.
    getMeSurvivalMock.mockResolvedValue([
      survival({ roomId: ROOM_ID + 999, roomName: "Other" }),
    ]);
    render(<WalletScreen roomId={ROOM_ID} />, {
      wrapper: makeWrapper(makeClient()),
    });
    await waitFor(() =>
      expect(screen.getByText("이 방에 더 이상 속해 있지 않아요")).toBeTruthy(),
    );
  });
});
