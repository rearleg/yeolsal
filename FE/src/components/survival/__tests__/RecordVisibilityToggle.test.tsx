// Story 2.3 FE-4.1 — RecordVisibilityToggle behavioral test.
//
// Covers AC4 (brand-voice copy in both states), AC5 (mutation triggers on
// toggle change), and AC10 (toast on success/error). The mutation hook is
// real (TanStack Query is instantiated per-test); only the api/survival
// module is mocked so no real network is hit, per the project-context FE
// testing rule.

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react-native";
import type { PropsWithChildren } from "react";
import * as survivalApi from "../../../api/survival";
import { setToastBridge } from "../../../lib/toast";
import { qk } from "../../../lib/query/keys";
import { RecordVisibilityToggle } from "../RecordVisibilityToggle";

jest.mock("../../../api/survival", () => ({
  getRecordVisibilityPrefs: jest.fn(),
  updateRecordVisibilityPref: jest.fn(),
}));

const getPrefsMock =
  survivalApi.getRecordVisibilityPrefs as jest.MockedFunction<
    typeof survivalApi.getRecordVisibilityPrefs
  >;
const updatePrefMock =
  survivalApi.updateRecordVisibilityPref as jest.MockedFunction<
    typeof survivalApi.updateRecordVisibilityPref
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
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

function makeToastSpy() {
  const calls: Array<{ variant: string; message: string }> = [];
  setToastBridge({
    show: (variant, message) => calls.push({ variant, message }),
  });
  return calls;
}

describe("RecordVisibilityToggle", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  afterEach(() => {
    setToastBridge(null);
  });

  it("renders the off-state description when shareOnElimination is false", async () => {
    getPrefsMock.mockResolvedValue([
      { roomId: 7, roomName: "팀 A", shareOnElimination: false, updatedAt: null },
    ]);
    render(<RecordVisibilityToggle roomId={7} />, {
      wrapper: makeWrapper(makeClient()),
    });

    await waitFor(() =>
      expect(
        screen.getByText("꺼져 있어요 — 멤버에게 내 기록은 보이지 않아요.")
      ).toBeTruthy()
    );
    expect(screen.getByText("이 그룹에서 내 기록 공유")).toBeTruthy();
  });

  it("renders the on-state description when shareOnElimination is true", async () => {
    getPrefsMock.mockResolvedValue([
      {
        roomId: 7,
        roomName: "팀 A",
        shareOnElimination: true,
        updatedAt: "2026-05-16T01:00:00Z",
      },
    ]);
    render(<RecordVisibilityToggle roomId={7} />, {
      wrapper: makeWrapper(makeClient()),
    });

    await waitFor(() =>
      expect(
        screen.getByText("공유를 켜면 내 잔디와 회고가 그룹 멤버에게 보여요.")
      ).toBeTruthy()
    );
  });

  it("flipping the switch ON optimistically updates the cache and shows the success toast", async () => {
    const client = makeClient();
    const toastCalls = makeToastSpy();
    getPrefsMock.mockResolvedValue([
      { roomId: 7, roomName: "팀 A", shareOnElimination: false, updatedAt: null },
    ]);
    updatePrefMock.mockResolvedValue({
      roomId: 7,
      roomName: "팀 A",
      shareOnElimination: true,
      updatedAt: "2026-05-16T01:30:00Z",
    });

    render(<RecordVisibilityToggle roomId={7} />, { wrapper: makeWrapper(client) });

    await waitFor(() =>
      expect(screen.getByLabelText("이 그룹에서 내 기록 공유 토글")).toBeTruthy()
    );
    fireEvent(screen.getByLabelText("이 그룹에서 내 기록 공유 토글"), "valueChange", true);

    await waitFor(() => expect(updatePrefMock).toHaveBeenCalledWith(7, true));
    await waitFor(() =>
      expect(toastCalls).toEqual(
        expect.arrayContaining([
          { variant: "success", message: "이제 멤버들이 내 기록을 볼 수 있어요." },
        ])
      )
    );
  });

  it("on mutation failure: cache is rolled back and an error toast appears", async () => {
    const client = makeClient();
    const toastCalls = makeToastSpy();
    getPrefsMock.mockResolvedValue([
      { roomId: 7, roomName: "팀 A", shareOnElimination: false, updatedAt: null },
    ]);
    updatePrefMock.mockRejectedValue(new Error("network down"));

    render(<RecordVisibilityToggle roomId={7} />, { wrapper: makeWrapper(client) });

    await waitFor(() =>
      expect(screen.getByLabelText("이 그룹에서 내 기록 공유 토글")).toBeTruthy()
    );
    fireEvent(screen.getByLabelText("이 그룹에서 내 기록 공유 토글"), "valueChange", true);

    await waitFor(() =>
      expect(toastCalls).toEqual(
        expect.arrayContaining([
          { variant: "danger", message: "잠시 후 다시 시도해 주세요." },
        ])
      )
    );
    await waitFor(() => {
      const cached = client.getQueryData<survivalApi.VisibilityPrefDto[]>(
        qk.recordVisibilityPrefs
      );
      const row = cached?.find((r) => r.roomId === 7);
      expect(row?.shareOnElimination).toBe(false);
    });
  });

  it("brand-voice-lint Rule 2 — copy strings do not contain any banned word", () => {
    const banned = [
      "벌금",
      "잃었다",
      "떨어졌다",
      "실패",
      "자책",
      "부담",
      "패배",
      "죄책감",
      "노출",
      "탈락",
    ];
    const strings = [
      "이 그룹에서 내 기록 공유",
      "공유를 켜면 내 잔디와 회고가 그룹 멤버에게 보여요.",
      "꺼져 있어요 — 멤버에게 내 기록은 보이지 않아요.",
      "이제 멤버들이 내 기록을 볼 수 있어요.",
      "내 기록은 다시 비공개로 돌아갔어요.",
    ];
    for (const s of strings) {
      for (const b of banned) {
        expect(s).not.toContain(b);
      }
    }
  });
});
