// Story 5.2 — TanStack invalidation contract for the two leader-action hooks.

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { renderHook, waitFor } from "@testing-library/react-native";
import type { PropsWithChildren } from "react";
import * as api from "../../../../api/rooms";
import { qk } from "../../keys";
import { useTransferLeadership, useUpdateMemberCap } from "../rooms";

jest.mock("../../../../api/rooms", () => {
  const actual = jest.requireActual("../../../../api/rooms") as Record<string, unknown>;
  return {
    ...actual,
    updateMemberCap: jest.fn(),
    transferLeadership: jest.fn(),
  };
});

jest.mock("../../../toast", () => ({
  toast: { error: jest.fn(), info: jest.fn(), success: jest.fn() },
}));

jest.mock("../../../../hooks/useHaptics", () => ({
  useHaptic: () => jest.fn(),
}));

const updateMemberCapMock = api.updateMemberCap as jest.MockedFunction<
  typeof api.updateMemberCap
>;
const transferLeadershipMock = api.transferLeadership as jest.MockedFunction<
  typeof api.transferLeadership
>;

function makeClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0 },
      mutations: { retry: false },
    },
  });
}

function wrapperWith(client: QueryClient) {
  return function Wrapper({ children }: PropsWithChildren) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

const ROOM = {
  id: 42,
  name: "방",
  ownerId: 7,
  maxMembers: 12,
  minDailyGoalDays: 10 as const,
  createdAt: "2026-04-15T03:14:00Z",
  pendingMaxMembers: null,
  pendingMaxMembersEffectiveFromMonth: null,
};

describe("useUpdateMemberCap", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("invalidates the rooms list query on a successful cap update", async () => {
    const client = makeClient();
    const invalidateSpy = jest.spyOn(client, "invalidateQueries");
    updateMemberCapMock.mockResolvedValueOnce({
      ...ROOM,
      pendingMaxMembers: 20,
      pendingMaxMembersEffectiveFromMonth: "2026-05",
    });

    const { result } = renderHook(() => useUpdateMemberCap(), {
      wrapper: wrapperWith(client),
    });

    result.current.mutate({ roomId: 42, maxMembers: 20 });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(updateMemberCapMock).toHaveBeenCalledWith(42, { maxMembers: 20 });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: qk.rooms });
  });

  it("does not invalidate when the mutation fails", async () => {
    const client = makeClient();
    const invalidateSpy = jest.spyOn(client, "invalidateQueries");
    updateMemberCapMock.mockRejectedValueOnce(new Error("bad request"));

    const { result } = renderHook(() => useUpdateMemberCap(), {
      wrapper: wrapperWith(client),
    });

    result.current.mutate({ roomId: 42, maxMembers: 31 });

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(invalidateSpy).not.toHaveBeenCalled();
  });
});

describe("useTransferLeadership", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("invalidates both qk.rooms and qk.roomMembers(roomId) on success", async () => {
    const client = makeClient();
    const invalidateSpy = jest.spyOn(client, "invalidateQueries");
    transferLeadershipMock.mockResolvedValueOnce({ ...ROOM, ownerId: 11 });

    const { result } = renderHook(() => useTransferLeadership(), {
      wrapper: wrapperWith(client),
    });

    result.current.mutate({ roomId: 42, targetUserId: 11 });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(transferLeadershipMock).toHaveBeenCalledWith(42, {
      targetUserId: 11,
    });
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: qk.rooms });
    expect(invalidateSpy).toHaveBeenCalledWith({
      queryKey: qk.roomMembers(42),
    });
  });

  it("does not invalidate when the mutation fails", async () => {
    const client = makeClient();
    const invalidateSpy = jest.spyOn(client, "invalidateQueries");
    transferLeadershipMock.mockRejectedValueOnce(new Error("conflict"));

    const { result } = renderHook(() => useTransferLeadership(), {
      wrapper: wrapperWith(client),
    });

    result.current.mutate({ roomId: 42, targetUserId: 11 });

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(invalidateSpy).not.toHaveBeenCalled();
  });
});
