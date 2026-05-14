// WelcomeWindow.disabled-cta — Story 1.6 AC2 (Option B lock on CTA-A in W1-W2).
//
// CTA-A is disabled until Story 6.2 wires KakaoShareLink live. While
// disabled, pressing it must NOT invoke any Share / Kakao SDK and must
// surface the locked Option B copy via toast.info + accessibilityHint.

const mockToastInfo = jest.fn<void, [string]>();

jest.mock("../../../lib/toast", () => ({
  toast: {
    info: (m: string) => mockToastInfo(m),
    success: jest.fn(),
    warning: jest.fn(),
    error: jest.fn(),
  },
}));

import { fireEvent, render, screen } from "@testing-library/react-native";
import { Share } from "react-native";
import { SubModeProvider } from "../../../providers/SubModeProvider";
import { WelcomeWindow } from "../WelcomeWindow";

// Defensive spy: if anyone ever does `import {Share} from "react-native"` and
// calls `Share.share(...)`, the test fails loudly. The disabled CTA must never
// trip the share sheet.
const shareSpy = jest
  .spyOn(Share, "share")
  .mockImplementation(() => Promise.resolve({ action: "dismissedAction" }) as never);

const NOW = new Date("2026-05-14T10:00:00Z");
const GRACE_FUTURE = new Date("2026-05-25T10:00:00Z");
const LOCKED_COPY = "곧 카카오 초대가 가능해질 거예요";

function renderInPostcard(ui: React.ReactElement) {
  return render(<SubModeProvider subMode="postcard">{ui}</SubModeProvider>);
}

beforeEach(() => {
  mockToastInfo.mockClear();
  shareSpy.mockClear();
});

describe("WelcomeWindow disabled CTA-A (Option B 2026-05-11 lock)", () => {
  it("CTA-A renders disabled by default (kakaoEnabled omitted)", () => {
    const onTapKakao = jest.fn();
    renderInPostcard(
      <WelcomeWindow
        roomName="첫 그룹"
        memberCount={1}
        graceEndsAt={GRACE_FUTURE}
        now={NOW}
        onTapKakao={onTapKakao}
        onTapStartToday={() => undefined}
      />,
    );
    const kakao = screen.getByTestId("welcome-window-cta-kakao");
    expect(kakao.props.accessibilityState?.disabled).toBe(true);
    expect(kakao.props.accessibilityHint).toBe(LOCKED_COPY);
  });

  it("CTA-A press while disabled fires toast.info with the Option B locked copy", () => {
    const onTapKakao = jest.fn();
    renderInPostcard(
      <WelcomeWindow
        roomName="첫 그룹"
        memberCount={1}
        graceEndsAt={GRACE_FUTURE}
        now={NOW}
        onTapKakao={onTapKakao}
        onTapStartToday={() => undefined}
      />,
    );
    fireEvent.press(screen.getByTestId("welcome-window-cta-kakao"));
    expect(mockToastInfo).toHaveBeenCalledTimes(1);
    expect(mockToastInfo).toHaveBeenCalledWith(LOCKED_COPY);
    expect(onTapKakao).not.toHaveBeenCalled();
    expect(shareSpy).not.toHaveBeenCalled();
  });

  it("CTA-A press never opens the OS share sheet (no Kakao SDK invocation)", () => {
    renderInPostcard(
      <WelcomeWindow
        roomName="첫 그룹"
        memberCount={1}
        graceEndsAt={GRACE_FUTURE}
        now={NOW}
        onTapStartToday={() => undefined}
      />,
    );
    fireEvent.press(screen.getByTestId("welcome-window-cta-kakao"));
    expect(shareSpy).not.toHaveBeenCalled();
  });

  it("CTA-A renders enabled only when kakaoEnabled=true (Story 6.2 forward-compat)", () => {
    const onTapKakao = jest.fn();
    renderInPostcard(
      <WelcomeWindow
        roomName="첫 그룹"
        memberCount={1}
        graceEndsAt={GRACE_FUTURE}
        now={NOW}
        kakaoEnabled
        onTapKakao={onTapKakao}
        onTapStartToday={() => undefined}
      />,
    );
    const kakao = screen.getByTestId("welcome-window-cta-kakao");
    expect(kakao.props.accessibilityState?.disabled).toBe(false);
    fireEvent.press(kakao);
    expect(onTapKakao).toHaveBeenCalledTimes(1);
    expect(mockToastInfo).not.toHaveBeenCalled();
  });

  it("CTA-B 'start today' invokes its handler exactly once when pressed", () => {
    const onTapStartToday = jest.fn();
    renderInPostcard(
      <WelcomeWindow
        roomName="첫 그룹"
        memberCount={1}
        graceEndsAt={GRACE_FUTURE}
        now={NOW}
        onTapStartToday={onTapStartToday}
      />,
    );
    fireEvent.press(screen.getByTestId("welcome-window-cta-start-today"));
    expect(onTapStartToday).toHaveBeenCalledTimes(1);
    expect(mockToastInfo).not.toHaveBeenCalled();
  });
});
