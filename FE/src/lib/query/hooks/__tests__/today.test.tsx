import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook, waitFor } from "@testing-library/react-native";
import type { PropsWithChildren } from "react";
import {
  useAddTodo,
  useDeleteTodo,
  useSubmitReflection,
  useTodayQuery,
  useToggleTodo,
  useUpdateGoal,
} from "../today";
import * as api from "../../api";
import { qk } from "../../keys";
import * as toastMod from "../../../toast";
import * as sentryMod from "../../../sentry";
import * as hapticsMod from "../../../../hooks/useHaptics";
import { ApiError } from "../../../../api/client";
import type { DailyEntryDto } from "../../../../api/types";

jest.mock("../../api", () => ({
  fetchToday: jest.fn(),
  updateGoal: jest.fn(),
  addTodo: jest.fn(),
  patchTodo: jest.fn(),
  deleteTodo: jest.fn(),
  submitReflection: jest.fn(),
}));

jest.mock("../../../sentry", () => ({
  captureQueryError: jest.fn(),
}));

const fetchTodayMock = api.fetchToday as jest.MockedFunction<typeof api.fetchToday>;
const updateGoalMock = api.updateGoal as jest.MockedFunction<typeof api.updateGoal>;
const addTodoMock = api.addTodo as jest.MockedFunction<typeof api.addTodo>;
const patchTodoMock = api.patchTodo as jest.MockedFunction<typeof api.patchTodo>;
const deleteTodoMock = api.deleteTodo as jest.MockedFunction<typeof api.deleteTodo>;
const submitReflectionMock = api.submitReflection as jest.MockedFunction<
  typeof api.submitReflection
>;

const SAMPLE: DailyEntryDto = {
  id: 1,
  date: "2026-04-30",
  goal: "테스트 목표",
  todos: [
    { id: 10, title: "할 일 1", completed: false },
    { id: 11, title: "할 일 2", completed: false },
  ],
  reflection: null,
};

function makeClient() {
  // gcTime > 0 so observer-less cache entries seeded via setQueryData
  // (used by the optimistic-update tests) survive until the test ends.
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

describe("useTodayQuery", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("returns server data once fetchToday resolves", async () => {
    fetchTodayMock.mockResolvedValue(SAMPLE);
    const client = makeClient();
    const { result } = renderHook(() => useTodayQuery(), { wrapper: makeWrapper(client) });
    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toEqual(SAMPLE);
    expect(fetchTodayMock).toHaveBeenCalledTimes(1);
  });

  it("uses queryKey qk.today so the cache is shared with mutations", async () => {
    fetchTodayMock.mockResolvedValue(SAMPLE);
    const client = makeClient();
    renderHook(() => useTodayQuery(), { wrapper: makeWrapper(client) });
    await waitFor(() => {
      expect(client.getQueryData(qk.today)).toEqual(SAMPLE);
    });
  });
});

describe("useToggleTodo", () => {
  let client: QueryClient;
  let hapticTrigger: jest.Mock;

  beforeEach(() => {
    jest.clearAllMocks();
    client = makeClient();
    client.setQueryData(qk.today, SAMPLE);
    hapticTrigger = jest.fn();
    jest.spyOn(hapticsMod, "useHaptic").mockReturnValue(hapticTrigger);
  });

  afterEach(() => {
    // Cancel scheduled GC timers so jest workers can exit cleanly.
    client.clear();
  });

  it("applies optimistic update to cached entry on mutate", async () => {
    patchTodoMock.mockImplementation(() => new Promise(() => undefined));
    const { result } = renderHook(() => useToggleTodo(), { wrapper: makeWrapper(client) });

    act(() => {
      result.current.mutate({ id: 10, completed: true });
    });

    await waitFor(() => {
      const cached = client.getQueryData<DailyEntryDto | null>(qk.today);
      expect(cached?.todos.find((t) => t.id === 10)?.completed).toBe(true);
    });
  });

  it("triggers light haptic on optimistic mutate", async () => {
    patchTodoMock.mockImplementation(() => new Promise(() => undefined));
    const { result } = renderHook(() => useToggleTodo(), { wrapper: makeWrapper(client) });

    act(() => {
      result.current.mutate({ id: 10, completed: true });
    });

    await waitFor(() => expect(hapticTrigger).toHaveBeenCalledWith("light"));
  });

  it("rolls back optimistic update when patchTodo rejects", async () => {
    const errorSpy = jest.spyOn(toastMod.toast, "error").mockImplementation(() => undefined);
    patchTodoMock.mockRejectedValue(new Error("network down"));
    const { result } = renderHook(() => useToggleTodo(), { wrapper: makeWrapper(client) });

    act(() => {
      result.current.mutate({ id: 10, completed: true });
    });

    await waitFor(() => expect(result.current.isError).toBe(true));
    const cached = client.getQueryData<DailyEntryDto | null>(qk.today);
    expect(cached?.todos.find((t) => t.id === 10)?.completed).toBe(false);
    expect(errorSpy).toHaveBeenCalledWith("network down");
  });

  it("calls patchTodo with the new completed value", async () => {
    patchTodoMock.mockResolvedValue(undefined);
    const { result } = renderHook(() => useToggleTodo(), { wrapper: makeWrapper(client) });

    act(() => {
      result.current.mutate({ id: 11, completed: true });
    });

    await waitFor(() => expect(patchTodoMock).toHaveBeenCalledWith(11, { completed: true }));
  });

  it("invalidates qk.today on settle", async () => {
    patchTodoMock.mockResolvedValue(undefined);
    const invalidateSpy = jest.spyOn(client, "invalidateQueries");
    const { result } = renderHook(() => useToggleTodo(), { wrapper: makeWrapper(client) });

    act(() => {
      result.current.mutate({ id: 10, completed: true });
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: qk.today });
  });
});

describe("useUpdateGoal", () => {
  let client: QueryClient;

  beforeEach(() => {
    jest.clearAllMocks();
    client = makeClient();
    client.setQueryData(qk.today, SAMPLE);
  });

  afterEach(() => {
    // Cancel scheduled GC timers so jest workers can exit cleanly.
    client.clear();
  });

  it("optimistically updates the cached goal", async () => {
    updateGoalMock.mockImplementation(() => new Promise(() => undefined));
    const { result } = renderHook(() => useUpdateGoal(), { wrapper: makeWrapper(client) });

    act(() => {
      result.current.mutate("새로운 목표");
    });

    await waitFor(() => {
      const cached = client.getQueryData<DailyEntryDto | null>(qk.today);
      expect(cached?.goal).toBe("새로운 목표");
    });
  });

  it("rolls back and toasts on error", async () => {
    const errorSpy = jest.spyOn(toastMod.toast, "error").mockImplementation(() => undefined);
    updateGoalMock.mockRejectedValue(new Error("save failed"));
    const { result } = renderHook(() => useUpdateGoal(), { wrapper: makeWrapper(client) });

    act(() => {
      result.current.mutate("실패할 목표");
    });

    await waitFor(() => expect(result.current.isError).toBe(true));
    const cached = client.getQueryData<DailyEntryDto | null>(qk.today);
    expect(cached?.goal).toBe(SAMPLE.goal);
    expect(errorSpy).toHaveBeenCalledWith("save failed");
  });

  it("writes server response to cache on success", async () => {
    const updated: DailyEntryDto = { ...SAMPLE, goal: "서버 확정 목표" };
    updateGoalMock.mockResolvedValue(updated);
    const { result } = renderHook(() => useUpdateGoal(), { wrapper: makeWrapper(client) });

    act(() => {
      result.current.mutate("서버 확정 목표");
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(client.getQueryData<DailyEntryDto | null>(qk.today)).toEqual(updated);
  });
});

describe("useAddTodo", () => {
  let client: QueryClient;

  beforeEach(() => {
    jest.clearAllMocks();
    client = makeClient();
    client.setQueryData(qk.today, SAMPLE);
  });

  afterEach(() => {
    // Cancel scheduled GC timers so jest workers can exit cleanly.
    client.clear();
  });

  it("calls addTodo with the trimmed title", async () => {
    addTodoMock.mockResolvedValue(undefined);
    const { result } = renderHook(() => useAddTodo(), { wrapper: makeWrapper(client) });

    act(() => {
      result.current.mutate("새 할 일");
    });

    await waitFor(() => expect(addTodoMock).toHaveBeenCalledWith("새 할 일"));
  });

  it("invalidates qk.today on success so the new todo is fetched", async () => {
    addTodoMock.mockResolvedValue(undefined);
    const invalidateSpy = jest.spyOn(client, "invalidateQueries");
    const { result } = renderHook(() => useAddTodo(), { wrapper: makeWrapper(client) });

    act(() => {
      result.current.mutate("동기화 대상");
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: qk.today });
  });

  it("toasts on error", async () => {
    const errorSpy = jest.spyOn(toastMod.toast, "error").mockImplementation(() => undefined);
    addTodoMock.mockRejectedValue(new Error("add failed"));
    const { result } = renderHook(() => useAddTodo(), { wrapper: makeWrapper(client) });

    act(() => {
      result.current.mutate("실패 할 일");
    });

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(errorSpy).toHaveBeenCalledWith("add failed");
  });
});

describe("useDeleteTodo", () => {
  let client: QueryClient;

  beforeEach(() => {
    jest.clearAllMocks();
    client = makeClient();
    client.setQueryData(qk.today, SAMPLE);
  });

  afterEach(() => {
    // Cancel scheduled GC timers so jest workers can exit cleanly.
    client.clear();
  });

  it("optimistically removes the todo from cache", async () => {
    deleteTodoMock.mockImplementation(() => new Promise(() => undefined));
    const { result } = renderHook(() => useDeleteTodo(), { wrapper: makeWrapper(client) });

    act(() => {
      result.current.mutate(10);
    });

    await waitFor(() => {
      const cached = client.getQueryData<DailyEntryDto | null>(qk.today);
      expect(cached?.todos.some((t) => t.id === 10)).toBe(false);
    });
  });

  it("rolls back when delete rejects", async () => {
    jest.spyOn(toastMod.toast, "error").mockImplementation(() => undefined);
    deleteTodoMock.mockRejectedValue(new Error("nope"));
    const { result } = renderHook(() => useDeleteTodo(), { wrapper: makeWrapper(client) });

    act(() => {
      result.current.mutate(10);
    });

    await waitFor(() => expect(result.current.isError).toBe(true));
    const cached = client.getQueryData<DailyEntryDto | null>(qk.today);
    expect(cached?.todos.some((t) => t.id === 10)).toBe(true);
  });
});

describe("useSubmitReflection", () => {
  let client: QueryClient;
  let hapticTrigger: jest.Mock;

  beforeEach(() => {
    jest.clearAllMocks();
    client = makeClient();
    client.setQueryData(qk.today, SAMPLE);
    hapticTrigger = jest.fn();
    jest.spyOn(hapticsMod, "useHaptic").mockReturnValue(hapticTrigger);
  });

  afterEach(() => {
    // Cancel scheduled GC timers so jest workers can exit cleanly.
    client.clear();
  });

  it("calls submitReflection with dailyEntryId and body", async () => {
    submitReflectionMock.mockResolvedValue(undefined);
    const { result } = renderHook(() => useSubmitReflection(), { wrapper: makeWrapper(client) });

    act(() => {
      result.current.mutate({ dailyEntryId: SAMPLE.id, body: "회고 본문" });
    });

    await waitFor(() =>
      expect(submitReflectionMock).toHaveBeenCalledWith(SAMPLE.id, "회고 본문"),
    );
  });

  it("fires success haptic and invalidates qk.today on success", async () => {
    submitReflectionMock.mockResolvedValue(undefined);
    const invalidateSpy = jest.spyOn(client, "invalidateQueries");
    const { result } = renderHook(() => useSubmitReflection(), { wrapper: makeWrapper(client) });

    act(() => {
      result.current.mutate({ dailyEntryId: SAMPLE.id, body: "회고 본문" });
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(hapticTrigger).toHaveBeenCalledWith("success");
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: qk.today });
  });

  it("toasts and skips haptic on error", async () => {
    const errorSpy = jest.spyOn(toastMod.toast, "error").mockImplementation(() => undefined);
    submitReflectionMock.mockRejectedValue(new Error("reflection failed"));
    const { result } = renderHook(() => useSubmitReflection(), { wrapper: makeWrapper(client) });

    act(() => {
      result.current.mutate({ dailyEntryId: SAMPLE.id, body: "실패 회고" });
    });

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(errorSpy).toHaveBeenCalledWith("reflection failed");
    expect(hapticTrigger).not.toHaveBeenCalledWith("success");
  });

  it("treats BAD_REQUEST 'already submitted' as info + cache invalidation + success haptic", async () => {
    const infoSpy = jest.spyOn(toastMod.toast, "info").mockImplementation(() => undefined);
    const errorSpy = jest.spyOn(toastMod.toast, "error").mockImplementation(() => undefined);
    const invalidateSpy = jest.spyOn(client, "invalidateQueries");
    submitReflectionMock.mockRejectedValue(new ApiError(400, "BAD_REQUEST", "이미 회고를 제출했습니다."));

    const { result } = renderHook(() => useSubmitReflection(), { wrapper: makeWrapper(client) });

    act(() => {
      result.current.mutate({ dailyEntryId: SAMPLE.id, body: "재시도 회고" });
    });

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(infoSpy).toHaveBeenCalledWith("이미 회고를 제출했습니다.");
    expect(errorSpy).not.toHaveBeenCalled();
    expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: qk.today });
    expect(hapticTrigger).toHaveBeenCalledWith("success");
  });

  it("INTERNAL_ERROR shows generic toast and reports to Sentry (no server-message leak)", async () => {
    const errorSpy = jest.spyOn(toastMod.toast, "error").mockImplementation(() => undefined);
    const captureMock = sentryMod.captureQueryError as jest.MockedFunction<
      typeof sentryMod.captureQueryError
    >;
    captureMock.mockClear();
    const apiError = new ApiError(500, "INTERNAL_ERROR", "내부 오류가 발생했습니다.");
    submitReflectionMock.mockRejectedValue(apiError);

    const { result } = renderHook(() => useSubmitReflection(), { wrapper: makeWrapper(client) });

    act(() => {
      result.current.mutate({ dailyEntryId: SAMPLE.id, body: "본문" });
    });

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(errorSpy).toHaveBeenCalledWith(
      "회고 저장에 실패했어요. 잠시 뒤 다시 시도해 주세요.",
    );
    expect(captureMock).toHaveBeenCalledWith(apiError, { kind: "mutation", key: qk.today });
  });

  it("falls through to message toast for other ApiError codes", async () => {
    const errorSpy = jest.spyOn(toastMod.toast, "error").mockImplementation(() => undefined);
    submitReflectionMock.mockRejectedValue(new ApiError(403, "FORBIDDEN", "권한이 없습니다."));

    const { result } = renderHook(() => useSubmitReflection(), { wrapper: makeWrapper(client) });

    act(() => {
      result.current.mutate({ dailyEntryId: SAMPLE.id, body: "본문" });
    });

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(errorSpy).toHaveBeenCalledWith("권한이 없습니다.");
  });
});
