import { act, render } from "@testing-library/react-native";
import { Text } from "react-native";
import { ToastProvider } from "../ToastProvider";
import { toast } from "../../../lib/toast";

describe("ToastProvider", () => {
  beforeEach(() => {
    jest.useFakeTimers();
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  it("renders children", () => {
    const { getByText } = render(
      <ToastProvider>
        <Text>hello world</Text>
      </ToastProvider>,
    );
    expect(getByText("hello world")).toBeOnTheScreen();
  });

  it("displays a success toast when toast.success is called", () => {
    const { getByText } = render(
      <ToastProvider>
        <Text>app</Text>
      </ToastProvider>,
    );

    act(() => {
      toast.success("저장되었습니다");
    });

    expect(getByText("저장되었습니다")).toBeOnTheScreen();
  });

  it("displays error/warning/info variants", () => {
    const { getByText } = render(
      <ToastProvider>
        <Text>app</Text>
      </ToastProvider>,
    );

    act(() => {
      toast.error("실패");
      toast.warning("주의");
      toast.info("안내");
    });

    expect(getByText("실패")).toBeOnTheScreen();
    expect(getByText("주의")).toBeOnTheScreen();
    expect(getByText("안내")).toBeOnTheScreen();
  });

  it("auto-dismisses a toast after 3500ms", () => {
    const { getByText, queryByText } = render(
      <ToastProvider>
        <Text>app</Text>
      </ToastProvider>,
    );

    act(() => {
      toast.success("일시적");
    });
    expect(getByText("일시적")).toBeOnTheScreen();

    act(() => {
      jest.advanceTimersByTime(3500);
    });

    expect(queryByText("일시적")).toBeNull();
  });

  it("keeps at most 3 toasts visible (oldest evicted)", () => {
    const { getByText, queryByText } = render(
      <ToastProvider>
        <Text>app</Text>
      </ToastProvider>,
    );

    act(() => {
      toast.info("첫번째");
      toast.info("두번째");
      toast.info("세번째");
      toast.info("네번째");
    });

    expect(queryByText("첫번째")).toBeNull();
    expect(getByText("두번째")).toBeOnTheScreen();
    expect(getByText("세번째")).toBeOnTheScreen();
    expect(getByText("네번째")).toBeOnTheScreen();
  });
});

describe("toast bridge outside provider", () => {
  it("is a no-op when no provider is mounted", () => {
    expect(() => {
      toast.success("noone hears");
      toast.error("nor this");
    }).not.toThrow();
  });
});
