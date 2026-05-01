import { fireEvent, render } from "@testing-library/react-native";
import { useState } from "react";
import { Pressable, Text } from "react-native";
import { ErrorBoundary } from "../ErrorBoundary";

function Boom({ when }: { when: boolean }) {
  if (when) throw new Error("kaboom");
  return <Text>recovered</Text>;
}

function Wrapper() {
  const [throws, setThrows] = useState(true);
  return (
    <>
      <Pressable accessibilityLabel="fix" onPress={() => setThrows(false)}>
        <Text>fix</Text>
      </Pressable>
      <ErrorBoundary>
        <Boom when={throws} />
      </ErrorBoundary>
    </>
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

  it("exposes a retry control that resets the error state once root cause is fixed", () => {
    const { getByText, getByLabelText, queryByText } = render(<Wrapper />);

    // Initial render: Boom throws → boundary fallback shows.
    expect(getByText("문제가 발생했어요")).toBeOnTheScreen();

    // External fix: flip Wrapper state so Boom no longer throws.
    fireEvent.press(getByLabelText("fix"));

    // Boundary still in error state until reset.
    expect(getByText("문제가 발생했어요")).toBeOnTheScreen();

    // Press retry → boundary clears, re-renders children with the fix.
    fireEvent.press(getByText("다시 시도"));

    expect(queryByText("문제가 발생했어요")).toBeNull();
    expect(getByText("recovered")).toBeOnTheScreen();
  });
});
