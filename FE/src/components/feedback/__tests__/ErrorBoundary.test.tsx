import { fireEvent, render } from "@testing-library/react-native";
import { useState } from "react";
import { Pressable, Text } from "react-native";
import { ErrorBoundary } from "../ErrorBoundary";

function Boom({ when }: { when: boolean }) {
  if (when) throw new Error("kaboom");
  return <Text>safe</Text>;
}

function Toggle() {
  const [thrown, setThrown] = useState(true);
  return (
    <ErrorBoundary>
      <Pressable accessibilityLabel="recover" onPress={() => setThrown(false)}>
        <Text>recover</Text>
      </Pressable>
      <Boom when={thrown} />
    </ErrorBoundary>
  );
}

describe("ErrorBoundary", () => {
  const consoleErrorSpy = jest.spyOn(console, "error").mockImplementation(() => undefined);

  afterAll(() => {
    consoleErrorSpy.mockRestore();
  });

  it("renders children when nothing throws", () => {
    const { getByText } = render(
      <ErrorBoundary>
        <Text>healthy</Text>
      </ErrorBoundary>,
    );
    expect(getByText("healthy")).toBeOnTheScreen();
  });

  it("renders fallback UI when a child throws", () => {
    const { getByText } = render(
      <ErrorBoundary>
        <Boom when={true} />
      </ErrorBoundary>,
    );
    expect(getByText("문제가 발생했어요")).toBeOnTheScreen();
  });

  it("exposes a retry control that resets the error state", () => {
    const { getByText, queryByText } = render(<Toggle />);

    expect(getByText("문제가 발생했어요")).toBeOnTheScreen();

    fireEvent.press(getByText("다시 시도"));

    expect(queryByText("문제가 발생했어요")).toBeNull();
  });
});
