// Story 2.3 FE-4.3 — settings-screen integration smoke for the per-room
// visibility toggle.
//
// The story spec lists this file under app/rooms/[id]/__tests__/, but Jest's
// testMatch (FE/package.json) is rooted at <rootDir>/src/**/__tests__/ and
// would not discover a test placed under app/. Co-locating with the toggle
// component keeps the contract verified while staying inside the Jest test
// surface.
//
// Asserts the AC4 copy strings + a11y label survive the render, plus that
// the off-state default kicks in when the server materializes
// shareOnElimination=false (AC6 default-private) — including the empty-list
// case where no row exists for the room yet.

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react-native";
import type { PropsWithChildren } from "react";
import * as survivalApi from "../../../api/survival";
import { RecordVisibilityToggle } from "../RecordVisibilityToggle";

jest.mock("../../../api/survival", () => ({
  getRecordVisibilityPrefs: jest.fn(),
  updateRecordVisibilityPref: jest.fn(),
}));

const getPrefsMock =
  survivalApi.getRecordVisibilityPrefs as jest.MockedFunction<
    typeof survivalApi.getRecordVisibilityPrefs
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

describe("settings-screen visibility toggle integration", () => {
  beforeEach(() => jest.clearAllMocks());

  it("renders the toggle with brand-voice copy at AC4 + AC6 default-off state", async () => {
    getPrefsMock.mockResolvedValue([
      { roomId: 42, roomName: "기록 모임", shareOnElimination: false, updatedAt: null },
    ]);

    render(<RecordVisibilityToggle roomId={42} />, {
      wrapper: makeWrapper(makeClient()),
    });

    await waitFor(() =>
      expect(screen.getByText("이 그룹에서 내 기록 공유")).toBeTruthy()
    );
    expect(
      screen.getByText("꺼져 있어요 — 멤버에게 내 기록은 보이지 않아요.")
    ).toBeTruthy();
    expect(screen.getByLabelText("이 그룹에서 내 기록 공유 토글")).toBeTruthy();
  });

  it("default-private survives an empty server materialization too", async () => {
    getPrefsMock.mockResolvedValue([]);

    render(<RecordVisibilityToggle roomId={42} />, {
      wrapper: makeWrapper(makeClient()),
    });

    await waitFor(() =>
      expect(
        screen.getByText("꺼져 있어요 — 멤버에게 내 기록은 보이지 않아요.")
      ).toBeTruthy()
    );
  });
});
