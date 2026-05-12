// Behaviour contract for the room-creation capacity picker.
// - default value renders as-is (parent owns the state)
// - stepper buttons clamp at [2, 30]
// - inline numeric input emits onChange only for in-range integers and
//   surfaces the brand-voice error when the user types something outside
//   the bounds.

import { fireEvent, render } from "@testing-library/react-native";
import React, { useState } from "react";
import { MaxMembersPicker } from "../MaxMembersPicker";

interface HarnessProps {
  initial: number;
  onChange?: (n: number) => void;
}

function Harness({ initial, onChange }: HarnessProps) {
  const [value, setValue] = useState<number>(initial);
  return (
    <MaxMembersPicker
      value={value}
      onChange={(next) => {
        setValue(next);
        onChange?.(next);
      }}
    />
  );
}

describe("MaxMembersPicker", () => {
  it("renders the parent-supplied value in the numeric input", () => {
    const { getByLabelText } = render(<Harness initial={12} />);
    expect(getByLabelText("최대 인원 직접 입력").props.value).toBe("12");
  });

  it("decrements with the − stepper but never below 2", () => {
    const onChange = jest.fn();
    const { getByLabelText, getByText } = render(
      <Harness initial={3} onChange={onChange} />,
    );

    fireEvent.press(getByLabelText("최대 인원 감소"));
    expect(onChange).toHaveBeenLastCalledWith(2);

    // Floor reached — stepper is disabled, so further taps emit nothing.
    fireEvent.press(getByLabelText("최대 인원 감소"));
    expect(getByLabelText("최대 인원 직접 입력").props.value).toBe("2");
    expect(onChange).toHaveBeenCalledTimes(1);

    expect(getByText("−")).toBeTruthy();
  });

  it("increments with the + stepper but never above 30", () => {
    const onChange = jest.fn();
    const { getByLabelText } = render(<Harness initial={29} onChange={onChange} />);

    fireEvent.press(getByLabelText("최대 인원 증가"));
    expect(onChange).toHaveBeenLastCalledWith(30);

    fireEvent.press(getByLabelText("최대 인원 증가"));
    expect(getByLabelText("최대 인원 직접 입력").props.value).toBe("30");
    expect(onChange).toHaveBeenCalledTimes(1);
  });

  it("emits onChange when the numeric input lands on an in-range value", () => {
    const onChange = jest.fn();
    const { getByLabelText } = render(<Harness initial={12} onChange={onChange} />);

    fireEvent.changeText(getByLabelText("최대 인원 직접 입력"), "20");
    expect(onChange).toHaveBeenLastCalledWith(20);
  });

  it("surfaces the brand-voice range error when the typed value is out of range", () => {
    const onChange = jest.fn();
    const { getByLabelText, queryByText } = render(
      <Harness initial={12} onChange={onChange} />,
    );

    fireEvent.changeText(getByLabelText("최대 인원 직접 입력"), "31");
    // Out-of-range typing does NOT emit (parent stays at 12) but the
    // inline error renders so the user knows why their value isn't sticking.
    expect(onChange).not.toHaveBeenCalled();
    expect(queryByText(/2~30 사이여야 해요/)).not.toBeNull();
  });

  it("falls back to 12 (default) when blurred with empty / garbage text", () => {
    const onChange = jest.fn();
    const { getByLabelText } = render(<Harness initial={12} onChange={onChange} />);

    const input = getByLabelText("최대 인원 직접 입력");
    fireEvent.changeText(input, "");
    fireEvent(input, "blur");
    // commit-on-blur with an unparseable raw value falls back to the default.
    expect(onChange).toHaveBeenLastCalledWith(12);
  });
});
