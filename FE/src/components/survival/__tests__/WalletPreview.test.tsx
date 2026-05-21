// Story 2.1 FE-5.5 — WalletPreview renders only when SPECTATOR data is present.

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react-native";
import type { PropsWithChildren } from "react";
import * as survivalApi from "../../../api/survival";
import type { MeSurvivalEntry } from "../../../lib/spectator";
import { WalletPreview } from "../WalletPreview";

jest.mock("../../../api/survival", () => ({
  getMeSurvival: jest.fn(),
}));
jest.mock("../../../api/friendGiftTargets", () => ({
  getFriendGiftTargets: jest.fn(),
}));

const getMeSurvivalMock =
  survivalApi.getMeSurvival as jest.MockedFunction<typeof survivalApi.getMeSurvival>;
// Lazy require so the import order stays clean while letting the jest.mock
// above hoist correctly.
// eslint-disable-next-line @typescript-eslint/no-require-imports
const targetsApi = require("../../../api/friendGiftTargets") as {
  getFriendGiftTargets: jest.MockedFunction<() => Promise<unknown>>;
};
const getTargetsMock = targetsApi.getFriendGiftTargets;

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
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

const entry = (
  roomId: number,
  personalPoints: number,
  roomPointPool: number,
  freeRevivalTicketUsed: boolean = false,
): MeSurvivalEntry => ({
  roomId,
  roomName: `room-${roomId}`,
  status: "SPECTATOR",
  personalPoints,
  roomPointPool,
  freeRevivalTicketUsed,
});

describe("WalletPreview", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    // Default — no eligible badge targets. Each Story 3.3 case overrides.
    getTargetsMock.mockResolvedValue([]);
  });

  it("renders all three lines with the first room's data when entries exist", async () => {
    getMeSurvivalMock.mockResolvedValue([entry(11, 6, 18)]);
    render(<WalletPreview />, { wrapper: makeWrapper(makeClient()) });

    await waitFor(() =>
      expect(screen.getByLabelText("개인 포인트 6점")).toBeTruthy(),
    );
    expect(screen.getByLabelText("무료 회생권 1매")).toBeTruthy();
    expect(screen.getByLabelText("그룹 포인트 18")).toBeTruthy();
  });

  it("returns null when the survival query has no entries (defensive)", () => {
    getMeSurvivalMock.mockImplementation(() => new Promise(() => undefined));
    const { toJSON } = render(<WalletPreview />, {
      wrapper: makeWrapper(makeClient()),
    });
    expect(toJSON()).toBeNull();
  });

  it("Story 3.1 AC7 — when freeRevivalTicketUsed=true, the 🎟 line is NOT rendered", async () => {
    getMeSurvivalMock.mockResolvedValue([entry(13, 3, 9, true)]);
    render(<WalletPreview />, { wrapper: makeWrapper(makeClient()) });

    await waitFor(() =>
      expect(screen.getByLabelText("개인 포인트 3점")).toBeTruthy(),
    );
    expect(screen.queryByLabelText("무료 회생권 1매")).toBeNull();
    expect(screen.getByLabelText("그룹 포인트 9")).toBeTruthy();
  });

  it("brand-voice-lint Rule 2 — accessibility labels do NOT contain any banned word", async () => {
    getMeSurvivalMock.mockResolvedValue([entry(11, 3, 9)]);
    render(<WalletPreview />, { wrapper: makeWrapper(makeClient()) });

    await waitFor(() =>
      expect(screen.getByLabelText("개인 포인트 3점")).toBeTruthy(),
    );

    const banned = [
      "벌금",
      "잃었다",
      "떨어졌다",
      "실패",
      "자책",
      "부담",
      "패배",
      "죄책감",
    ];
    const labels = ["무료 회생권 1매", "개인 포인트 3점", "그룹 포인트 9"];
    for (const label of labels) {
      for (const b of banned) {
        expect(label).not.toContain(b);
      }
    }
  });

  it("Story 3.3 — when useFriendGiftTargets has one eligible target, the badge renders", async () => {
    getMeSurvivalMock.mockResolvedValue([entry(11, 10, 18)]);
    getTargetsMock.mockResolvedValue([
      {
        roomId: 11,
        roomName: "room-11",
        eligibleCount: 1,
        friends: [
          {
            userId: 99,
            nickname: "FFF",
            status: "RED",
            eliminatedAt: "2026-05-18T03:14:15Z",
          },
        ],
      },
    ]);
    render(<WalletPreview />, { wrapper: makeWrapper(makeClient()) });

    await waitFor(() =>
      expect(screen.getByText("친구 회생 대기 (1)")).toBeTruthy(),
    );
  });

  it("Story 3.3 — when useFriendGiftTargets is empty, the badge does NOT render", async () => {
    getMeSurvivalMock.mockResolvedValue([entry(11, 10, 18)]);
    getTargetsMock.mockResolvedValue([]);
    render(<WalletPreview />, { wrapper: makeWrapper(makeClient()) });

    await waitFor(() =>
      expect(screen.getByLabelText("개인 포인트 10점")).toBeTruthy(),
    );
    expect(screen.queryByText(/친구 회생 대기/)).toBeNull();
  });
});
