// WelcomeWindow — Story 1.6 AC1, AC2, AC3, AC4, AC8.
//
// Covers the pure 3-state machine (solo / growing / full) derived from
// (memberCount, graceEndsAt), the headline + 2-CTA composition, the A10
// anti-pattern guard (no progress bar / "X명 더 들어와야 시작"), and the
// accessibility composite. Mirrors the SurvivalChip test-file shape from
// Story 1.5.

import { render, screen } from "@testing-library/react-native";
import { SubModeProvider } from "../../../providers/SubModeProvider";
import { WelcomeWindow } from "../WelcomeWindow";

function renderInPostcard(ui: React.ReactElement) {
  return render(<SubModeProvider subMode="postcard">{ui}</SubModeProvider>);
}

const NOW = new Date("2026-05-14T10:00:00Z");
const GRACE_FUTURE = new Date("2026-05-25T10:00:00Z"); // still inside grace
const GRACE_PAST = new Date("2026-04-01T10:00:00Z"); // grace ended

describe("WelcomeWindow — 3-state machine + headline + 2 CTAs (AC1/AC2/AC4)", () => {
  it("solo: renders headline, both CTAs, and welcome-period descriptive copy", () => {
    renderInPostcard(
      <WelcomeWindow
        roomName="첫 그룹"
        memberCount={1}
        graceEndsAt={GRACE_FUTURE}
        now={NOW}
        onTapStartToday={() => undefined}
      />,
    );
    expect(screen.getByTestId("welcome-window")).toBeTruthy();
    expect(screen.getByTestId("welcome-window-headline")).toHaveTextContent(
      "친구를 초대하면 같이 살아남을 수 있어요",
    );
    expect(screen.getByTestId("welcome-window-cta-kakao")).toBeTruthy();
    expect(screen.getByTestId("welcome-window-cta-start-today")).toBeTruthy();
    expect(screen.getByTestId("welcome-window-period-copy")).toHaveTextContent(
      "환영 기간 14일",
    );
  });

  it("growing: keeps CTAs visible, replaces period copy with positive {N}명 함께 line", () => {
    renderInPostcard(
      <WelcomeWindow
        roomName="첫 그룹"
        memberCount={3}
        graceEndsAt={GRACE_FUTURE}
        now={NOW}
        onTapStartToday={() => undefined}
      />,
    );
    expect(screen.getByTestId("welcome-window")).toBeTruthy();
    expect(screen.getByTestId("welcome-window-cta-kakao")).toBeTruthy();
    expect(screen.getByTestId("welcome-window-cta-start-today")).toBeTruthy();
    const periodCopy = screen.getByTestId("welcome-window-period-copy");
    expect(periodCopy).toHaveTextContent(/3명 함께/);
    expect(periodCopy).toHaveTextContent(/환영 기간 끝까지/);
    expect(screen.queryByText("환영 기간 14일")).toBeNull();
  });

  it("full: returns null (component dismisses itself when memberCount>=2 and grace ended)", () => {
    renderInPostcard(
      <WelcomeWindow
        roomName="첫 그룹"
        memberCount={5}
        graceEndsAt={GRACE_PAST}
        now={NOW}
        onTapStartToday={() => undefined}
      />,
    );
    expect(screen.queryByTestId("welcome-window")).toBeNull();
    expect(screen.queryByTestId("welcome-window-headline")).toBeNull();
  });
});

describe("WelcomeWindow — A10 anti-pattern guard (AC3)", () => {
  const probes: ReadonlyArray<{
    readonly state: string;
    readonly memberCount: number;
    readonly grace: Date;
  }> = [
    { state: "solo", memberCount: 1, grace: GRACE_FUTURE },
    { state: "growing", memberCount: 4, grace: GRACE_FUTURE },
  ];

  for (const probe of probes) {
    it(`${probe.state}: never renders progress bar / countdown / "X명 더 들어와야 시작" strings`, () => {
      renderInPostcard(
        <WelcomeWindow
          roomName="첫 그룹"
          memberCount={probe.memberCount}
          graceEndsAt={probe.grace}
          now={NOW}
          onTapStartToday={() => undefined}
        />,
      );
      expect(screen.queryByText(/명 더 들어와야 시작/)).toBeNull();
      expect(screen.queryByText(/명 더 필요/)).toBeNull();
      expect(screen.queryByText(/\/\s?\d+\s?(명|일)/)).toBeNull();
      const allByRole = screen.queryAllByRole?.("progressbar") ?? [];
      expect(allByRole).toHaveLength(0);
    });
  }
});

describe("WelcomeWindow — accessibility composite (AC8)", () => {
  it("wrapper exposes a composite role and CTAs are accessible by role 'button'", () => {
    renderInPostcard(
      <WelcomeWindow
        roomName="첫 그룹"
        memberCount={1}
        graceEndsAt={GRACE_FUTURE}
        now={NOW}
        onTapStartToday={() => undefined}
      />,
    );
    const wrapper = screen.getByTestId("welcome-window");
    expect(["summary", "none", "text"]).toContain(wrapper.props.accessibilityRole);
    const kakao = screen.getByTestId("welcome-window-cta-kakao");
    expect(kakao.props.accessibilityRole).toBe("button");
    expect(kakao.props.accessibilityState?.disabled).toBe(true);
    const startToday = screen.getByTestId("welcome-window-cta-start-today");
    expect(startToday.props.accessibilityRole).toBe("button");
  });

  it("headline <Text> caps maxFontSizeMultiplier at 1.3 (Story 1.5 / NFR-9.6.3)", () => {
    renderInPostcard(
      <WelcomeWindow
        roomName="첫 그룹"
        memberCount={1}
        graceEndsAt={GRACE_FUTURE}
        now={NOW}
        onTapStartToday={() => undefined}
      />,
    );
    const headline = screen.getByTestId("welcome-window-headline");
    expect(headline.props.maxFontSizeMultiplier).toBeLessThanOrEqual(1.3);
  });
});

describe("WelcomeWindow — pure component (AC4 state derivation)", () => {
  it("derives state purely from props: same (memberCount, graceEndsAt) always renders same state", () => {
    const props = {
      roomName: "첫 그룹",
      memberCount: 1,
      graceEndsAt: GRACE_FUTURE,
      now: NOW,
      onTapStartToday: () => undefined,
    };
    const first = renderInPostcard(<WelcomeWindow {...props} />);
    const firstStr = JSON.stringify(first.toJSON());
    first.unmount();
    const second = renderInPostcard(<WelcomeWindow {...props} />);
    // Compare serialized form so AnimatedValue refs (different objects across
    // mounts but identical serialized shape) don't trip object-identity equality.
    expect(JSON.stringify(second.toJSON())).toEqual(firstStr);
  });

  it("boundary: memberCount=2 with grace still future stays in growing (CTAs visible)", () => {
    renderInPostcard(
      <WelcomeWindow
        roomName="첫 그룹"
        memberCount={2}
        graceEndsAt={GRACE_FUTURE}
        now={NOW}
        onTapStartToday={() => undefined}
      />,
    );
    expect(screen.getByTestId("welcome-window-cta-kakao")).toBeTruthy();
    expect(screen.getByTestId("welcome-window-cta-start-today")).toBeTruthy();
  });

  it("boundary: memberCount=2 with grace ended dismisses (full state, returns null)", () => {
    renderInPostcard(
      <WelcomeWindow
        roomName="첫 그룹"
        memberCount={2}
        graceEndsAt={GRACE_PAST}
        now={NOW}
        onTapStartToday={() => undefined}
      />,
    );
    expect(screen.queryByTestId("welcome-window")).toBeNull();
  });
});
