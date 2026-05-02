// Behaviour contract for the room minimum-days settings panel:
// - shows an onboarding banner only when the parent says it's the first
//   visit (right after join), otherwise it's a plain edit panel
// - mounts MinDaysSegmented at the parent-supplied value, with the room
//   floor enforced
// - save button is disabled while the picker matches the persisted value
//   (no-op submits) and while the parent is still pending a save
// - bubbles the chosen value back via onSubmit when pressed

import { fireEvent, render } from "@testing-library/react-native";
import React, { useState } from "react";
import type { MinDays } from "../../../api/rooms";
import { RoomMinimumSettings } from "../RoomMinimumSettings";

interface HarnessProps {
  initial: MinDays;
  roomFloor: MinDays;
  saving?: boolean;
  onboarding?: boolean;
  onSubmit: (min: MinDays) => void;
}

function Harness({ initial, roomFloor, saving, onboarding, onSubmit }: HarnessProps) {
  const [value, setValue] = useState<MinDays>(initial);
  return (
    <RoomMinimumSettings
      value={value}
      onChange={setValue}
      currentMinimum={initial}
      roomFloor={roomFloor}
      saving={saving ?? false}
      onboarding={onboarding ?? false}
      onSubmit={onSubmit}
    />
  );
}

describe("RoomMinimumSettings", () => {
  it("shows the onboarding banner only when onboarding=true", () => {
    const onSubmit = jest.fn();
    const { queryByText, rerender } = render(
      <Harness initial={10} roomFloor={10} onSubmit={onSubmit} onboarding={false} />,
    );

    expect(queryByText(/처음 설정/)).toBeNull();

    rerender(
      <Harness initial={10} roomFloor={10} onSubmit={onSubmit} onboarding={true} />,
    );
    expect(queryByText(/처음 설정/)).not.toBeNull();
  });

  it("disables save when the picker still matches the persisted value", () => {
    const onSubmit = jest.fn();
    const { getByText } = render(
      <Harness initial={15} roomFloor={10} onSubmit={onSubmit} />,
    );

    fireEvent.press(getByText(/저장/));

    expect(onSubmit).not.toHaveBeenCalled();
  });

  it("calls onSubmit with the picker value once a different option is chosen", () => {
    const onSubmit = jest.fn();
    const { getByLabelText, getByText } = render(
      <Harness initial={10} roomFloor={10} onSubmit={onSubmit} />,
    );

    // The segmented control labels each option; tap the 20-day chip.
    fireEvent.press(getByLabelText("20일"));
    fireEvent.press(getByText(/저장/));

    expect(onSubmit).toHaveBeenCalledTimes(1);
    expect(onSubmit).toHaveBeenCalledWith(20);
  });

  it("disables save while saving=true even when value differs", () => {
    const onSubmit = jest.fn();
    const { getByLabelText, getByText } = render(
      <Harness initial={10} roomFloor={10} saving={true} onSubmit={onSubmit} />,
    );

    fireEvent.press(getByLabelText("20일"));
    fireEvent.press(getByText(/저장/));

    expect(onSubmit).not.toHaveBeenCalled();
  });
});
