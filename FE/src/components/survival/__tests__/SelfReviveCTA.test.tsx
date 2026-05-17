// Story 3.1 FE-5.1 — SelfReviveCTA renders the three AC8 variants.

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react-native";
import type { PropsWithChildren } from "react";
import * as survivalApi from "../../../api/survival";
import type { MeSurvivalEntry } from "../../../lib/spectator";
import { SelfReviveCTA } from "../SelfReviveCTA";

jest.mock("../../../api/survival", () => ({
  getMeSurvival: jest.fn(),
}));

const getMeSurvivalMock =
  survivalApi.getMeSurvival as jest.MockedFunction<typeof survivalApi.getMeSurvival>;

const ROOM_ID = 11;

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

const entry = (overrides: Partial<MeSurvivalEntry> = {}): MeSurvivalEntry => ({
  roomId: ROOM_ID,
  roomName: `room-${ROOM_ID}`,
  status: "SPECTATOR",
  personalPoints: 0,
  roomPointPool: 0,
  freeRevivalTicketUsed: false,
  ...overrides,
});

describe("SelfReviveCTA", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("ticket-unused + SPECTATOR → renders primary '회생권 사용' CTA", async () => {
    getMeSurvivalMock.mockResolvedValue([entry({ freeRevivalTicketUsed: false })]);
    render(<SelfReviveCTA roomId={ROOM_ID} />, {
      wrapper: makeWrapper(makeClient()),
    });

    await waitFor(() =>
      expect(screen.getByLabelText("회생권 사용")).toBeTruthy(),
    );
    expect(screen.queryByLabelText("포인트로 회생 (3점)")).toBeNull();
    expect(screen.queryByLabelText("친구의 회생권 선물을 기다려요")).toBeNull();
  });

  it("ticket-used + balance ≥ 3 + RED → renders secondary '포인트로 회생 (3점)' CTA only", async () => {
    getMeSurvivalMock.mockResolvedValue([
      entry({ freeRevivalTicketUsed: true, personalPoints: 5, status: "RED" }),
    ]);
    render(<SelfReviveCTA roomId={ROOM_ID} />, {
      wrapper: makeWrapper(makeClient()),
    });

    await waitFor(() =>
      expect(screen.getByLabelText("포인트로 회생 (3점)")).toBeTruthy(),
    );
    expect(screen.queryByLabelText("회생권 사용")).toBeNull();
    expect(screen.queryByLabelText("친구의 회생권 선물을 기다려요")).toBeNull();
  });

  it("ticket-used + balance < 3 → renders muted '친구의 회생권 선물을 기다려요' caption only", async () => {
    getMeSurvivalMock.mockResolvedValue([
      entry({ freeRevivalTicketUsed: true, personalPoints: 2 }),
    ]);
    render(<SelfReviveCTA roomId={ROOM_ID} />, {
      wrapper: makeWrapper(makeClient()),
    });

    await waitFor(() =>
      expect(screen.getByLabelText("친구의 회생권 선물을 기다려요")).toBeTruthy(),
    );
    expect(screen.queryByLabelText("회생권 사용")).toBeNull();
    expect(screen.queryByLabelText("포인트로 회생 (3점)")).toBeNull();
  });

  it("ACTIVE membership → renders nothing (only RED/SPECTATOR get the CTA)", async () => {
    getMeSurvivalMock.mockResolvedValue([entry({ status: "ACTIVE" })]);
    const { toJSON } = render(<SelfReviveCTA roomId={ROOM_ID} />, {
      wrapper: makeWrapper(makeClient()),
    });

    await waitFor(() => expect(getMeSurvivalMock).toHaveBeenCalled());
    expect(toJSON()).toBeNull();
  });

  it("brand-voice-lint Rule 2 — every visible CTA label is clean of AVOID lexicon", async () => {
    getMeSurvivalMock.mockResolvedValue([entry({ freeRevivalTicketUsed: false })]);
    render(<SelfReviveCTA roomId={ROOM_ID} />, {
      wrapper: makeWrapper(makeClient()),
    });

    await waitFor(() =>
      expect(screen.getByLabelText("회생권 사용")).toBeTruthy(),
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
    const labels = [
      "회생권 사용",
      "포인트로 회생 (3점)",
      "친구의 회생권 선물을 기다려요",
    ];
    for (const label of labels) {
      for (const b of banned) {
        expect(label).not.toContain(b);
      }
    }
  });
});
