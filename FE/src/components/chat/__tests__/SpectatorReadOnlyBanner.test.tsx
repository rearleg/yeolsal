// Story 2.1 FE-5.3 — SpectatorReadOnlyBanner copy + a11y contract.
//
// Brand-voice-lint Rule 2 (PRD FR-8.8.2): none of the 8 banned dignity-
// damaging words may appear in the banner copy. This test mirrors the
// `tools/brand-voice-lint.ts` HARD-gate set verbatim so a regression that
// changes the copy without re-running the lint is caught here.

import { render, screen } from "@testing-library/react-native";
import { SpectatorReadOnlyBanner } from "../SpectatorReadOnlyBanner";

const BANNED_WORDS = [
  "벌금",
  "잃었다",
  "떨어졌다",
  "실패",
  "자책",
  "부담",
  "패배",
  "죄책감",
] as const;

const COPY = "관전 중 — 메시지는 회생 후에 다시 보낼 수 있어요";

describe("SpectatorReadOnlyBanner", () => {
  it("renders the locked dignity-tone copy", () => {
    render(<SpectatorReadOnlyBanner />);
    expect(screen.getByText(COPY)).toBeTruthy();
  });

  it("exposes accessibilityRole=text (non-interactive — not a button)", () => {
    render(<SpectatorReadOnlyBanner />);
    // RN test-renderer maps accessibilityRole to a role string the
    // `getByRole` matcher reads off the host props.
    const labeledNode = screen.getByLabelText(COPY);
    expect(labeledNode.props.accessibilityRole).toBe("text");
  });

  it("copy passes brand-voice-lint Rule 2 — none of the 8 banned words appear", () => {
    for (const banned of BANNED_WORDS) {
      expect(COPY).not.toContain(banned);
    }
  });
});
