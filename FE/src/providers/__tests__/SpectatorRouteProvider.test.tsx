// Story 2.1 FE-5.2 — SpectatorRouteProvider context exposure + empty-cache safety.

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react-native";
import type { PropsWithChildren } from "react";
import { Text } from "react-native";
import * as survivalApi from "../../api/survival";
import type { MeSurvivalEntry } from "../../lib/spectator";
import {
  SpectatorRouteProvider,
  useSpectatorRoute,
} from "../SpectatorRouteProvider";

jest.mock("../../api/survival", () => ({
  getMeSurvival: jest.fn(),
}));

const getMeSurvivalMock =
  survivalApi.getMeSurvival as jest.MockedFunction<typeof survivalApi.getMeSurvival>;

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
      <QueryClientProvider client={client}>
        <SpectatorRouteProvider>{children}</SpectatorRouteProvider>
      </QueryClientProvider>
    );
  };
}

const entry = (
  roomId: number,
  status: MeSurvivalEntry["status"],
): MeSurvivalEntry => ({
  roomId,
  roomName: `room-${roomId}`,
  status,
  personalPoints: 0,
  roomPointPool: 0,
});

function Probe() {
  const v = useSpectatorRoute();
  return (
    <>
      <Text testID="everywhere">{String(v.isSpectatorEverywhere)}</Text>
      <Text testID="spectator-ids">
        {[...v.spectatorRoomIds].sort((a, b) => a - b).join(",")}
      </Text>
      <Text testID="active-ids">
        {[...v.activeRoomIds].sort((a, b) => a - b).join(",")}
      </Text>
    </>
  );
}

describe("SpectatorRouteProvider", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("partitions room ids into spectator vs active sets", async () => {
    getMeSurvivalMock.mockResolvedValue([
      entry(11, "ACTIVE"),
      entry(12, "SPECTATOR"),
      entry(13, "YELLOW"),
    ]);
    const client = makeClient();
    render(<Probe />, { wrapper: makeWrapper(client) });

    await waitFor(() =>
      expect(screen.getByTestId("spectator-ids")).toHaveTextContent("12"),
    );
    expect(screen.getByTestId("active-ids")).toHaveTextContent("11,13");
    expect(screen.getByTestId("everywhere")).toHaveTextContent("false");
  });

  it("flips isSpectatorEverywhere when every membership is SPECTATOR", async () => {
    getMeSurvivalMock.mockResolvedValue([
      entry(21, "SPECTATOR"),
      entry(22, "SPECTATOR"),
    ]);
    const client = makeClient();
    render(<Probe />, { wrapper: makeWrapper(client) });

    await waitFor(() =>
      expect(screen.getByTestId("everywhere")).toHaveTextContent("true"),
    );
    expect(screen.getByTestId("spectator-ids")).toHaveTextContent("21,22");
    expect(screen.getByTestId("active-ids")).toHaveTextContent("");
  });

  it("does not crash with an empty cache — exposes the EMPTY_VALUE defaults", () => {
    // No mock resolve: getMeSurvival never finishes, so the query stays in
    // pending state. The provider must still expose the empty defaults
    // rather than throwing on `data ?? []`.
    getMeSurvivalMock.mockImplementation(() => new Promise(() => undefined));
    const client = makeClient();
    render(<Probe />, { wrapper: makeWrapper(client) });

    expect(screen.getByTestId("everywhere")).toHaveTextContent("false");
    expect(screen.getByTestId("spectator-ids")).toHaveTextContent("");
    expect(screen.getByTestId("active-ids")).toHaveTextContent("");
  });
});
