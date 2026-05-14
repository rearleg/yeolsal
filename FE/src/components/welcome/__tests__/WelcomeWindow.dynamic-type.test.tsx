// WelcomeWindow.dynamic-type — Story 1.6 AC8 (NFR-9.6.3 Dynamic Type cap).
//
// Mirrors SurvivalChip.dynamic-type.test.tsx (Story 1.5 AC9). The shared
// <Text> caps maxFontSizeMultiplier at 1.3 — this snapshot guards that the
// headline + CTA labels inherit the cap, so a regression that unbounds the
// cap shows up as a snapshot diff at PR review.

import { render, screen } from "@testing-library/react-native";
import { SubModeProvider } from "../../../providers/SubModeProvider";
import { WelcomeWindow } from "../WelcomeWindow";

const NOW = new Date("2026-05-14T10:00:00Z");
const GRACE_FUTURE = new Date("2026-05-25T10:00:00Z");

function renderInPostcard(ui: React.ReactElement) {
  return render(<SubModeProvider subMode="postcard">{ui}</SubModeProvider>);
}

describe("WelcomeWindow — Dynamic Type accessibility guarantees", () => {
  it("headline + CTA labels inherit the 1.3 cap from shared <Text>", () => {
    renderInPostcard(
      <WelcomeWindow
        roomName="첫 그룹"
        memberCount={1}
        graceEndsAt={GRACE_FUTURE}
        now={NOW}
        onTapStartToday={() => undefined}
      />,
    );
    expect(screen.getByTestId("welcome-window-headline").props.maxFontSizeMultiplier)
      .toBeLessThanOrEqual(1.3);
    expect(screen.getByTestId("welcome-window-cta-kakao-label").props.maxFontSizeMultiplier)
      .toBeLessThanOrEqual(1.3);
    expect(screen.getByTestId("welcome-window-cta-start-today-label").props.maxFontSizeMultiplier)
      .toBeLessThanOrEqual(1.3);
  });

  it("solo: rendered tree stays stable", () => {
    const tree = renderInPostcard(
      <WelcomeWindow
        roomName="첫 그룹"
        memberCount={1}
        graceEndsAt={GRACE_FUTURE}
        now={NOW}
        onTapStartToday={() => undefined}
      />,
    ).toJSON();
    expect(tree).toMatchSnapshot();
  });

  it("growing: rendered tree stays stable", () => {
    const tree = renderInPostcard(
      <WelcomeWindow
        roomName="첫 그룹"
        memberCount={3}
        graceEndsAt={GRACE_FUTURE}
        now={NOW}
        onTapStartToday={() => undefined}
      />,
    ).toJSON();
    expect(tree).toMatchSnapshot();
  });
});
