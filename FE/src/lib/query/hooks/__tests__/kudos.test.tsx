// Story 3.5 FE-5.2 — useSendKudos mutation hook contract (AC9, AC10).

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook, waitFor } from "@testing-library/react-native";
import type { PropsWithChildren } from "react";
import { ApiError } from "../../../../api/client";
import * as kudosApi from "../../../../api/kudos";
import { qk } from "../../keys";
import { useSendKudos } from "../kudos";

jest.mock("../../../../api/kudos", () => ({
  postKudos: jest.fn(),
}));

const postKudosMock =
  kudosApi.postKudos as jest.MockedFunction<typeof kudosApi.postKudos>;

const ROOM_ID = 42;
const TARGET_ID = 7;
const KUDOS_DTO: kudosApi.KudosDto = {
  kudosId: 9001,
  roomId: ROOM_ID,
  senderUserId: 11,
  targetUserId: TARGET_ID,
  message: "",
  occurredAt: "2026-05-17T03:14:15Z",
};

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

describe("useSendKudos", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("success → returns KudosDto and invalidates roomMessages + roomLastMessage", async () => {
    postKudosMock.mockResolvedValue(KUDOS_DTO);
    const client = makeClient();
    const spy = jest.spyOn(client, "invalidateQueries");

    const { result } = renderHook(() => useSendKudos(ROOM_ID), {
      wrapper: makeWrapper(client),
    });

    await act(async () => {
      const dto = await result.current.mutateAsync({ targetUserId: TARGET_ID });
      expect(dto).toEqual(KUDOS_DTO);
    });

    expect(postKudosMock).toHaveBeenCalledWith(ROOM_ID, { targetUserId: TARGET_ID });
    expect(spy).toHaveBeenCalledWith({ queryKey: qk.roomMessages(ROOM_ID) });
    expect(spy).toHaveBeenCalledWith({ queryKey: qk.roomLastMessage(ROOM_ID) });
  });

  it("409 KUDOS_ALREADY_SENT_TODAY → caller receives ApiError; no invalidation", async () => {
    postKudosMock.mockRejectedValue(
      new ApiError(409, "KUDOS_ALREADY_SENT_TODAY", "오늘은 이미 응원을 보냈어요."),
    );
    const client = makeClient();
    const spy = jest.spyOn(client, "invalidateQueries");

    const { result } = renderHook(() => useSendKudos(ROOM_ID), {
      wrapper: makeWrapper(client),
    });

    await act(async () => {
      await expect(
        result.current.mutateAsync({ targetUserId: TARGET_ID }),
      ).rejects.toMatchObject({ code: "KUDOS_ALREADY_SENT_TODAY" });
    });

    await waitFor(() => expect(spy).not.toHaveBeenCalled());
  });

  it("400 KUDOS_TARGET_NOT_ELIGIBLE → caller receives ApiError; no invalidation", async () => {
    postKudosMock.mockRejectedValue(
      new ApiError(400, "KUDOS_TARGET_NOT_ELIGIBLE", "응원 대상이 아니에요."),
    );
    const client = makeClient();
    const spy = jest.spyOn(client, "invalidateQueries");

    const { result } = renderHook(() => useSendKudos(ROOM_ID), {
      wrapper: makeWrapper(client),
    });

    await act(async () => {
      await expect(
        result.current.mutateAsync({ targetUserId: TARGET_ID }),
      ).rejects.toMatchObject({ code: "KUDOS_TARGET_NOT_ELIGIBLE" });
    });

    expect(spy).not.toHaveBeenCalled();
  });

  it("network error → caller receives the error; no invalidation", async () => {
    postKudosMock.mockRejectedValue(new Error("network unreachable"));
    const client = makeClient();
    const spy = jest.spyOn(client, "invalidateQueries");

    const { result } = renderHook(() => useSendKudos(ROOM_ID), {
      wrapper: makeWrapper(client),
    });

    await act(async () => {
      await expect(
        result.current.mutateAsync({ targetUserId: TARGET_ID }),
      ).rejects.toThrow("network unreachable");
    });

    expect(spy).not.toHaveBeenCalled();
  });

  it("forwards optional message field to postKudos", async () => {
    postKudosMock.mockResolvedValue({ ...KUDOS_DTO, message: "우리 같이 가자" });
    const client = makeClient();

    const { result } = renderHook(() => useSendKudos(ROOM_ID), {
      wrapper: makeWrapper(client),
    });

    await act(async () => {
      await result.current.mutateAsync({
        targetUserId: TARGET_ID,
        message: "우리 같이 가자",
      });
    });

    expect(postKudosMock).toHaveBeenCalledWith(ROOM_ID, {
      targetUserId: TARGET_ID,
      message: "우리 같이 가자",
    });
  });
});
