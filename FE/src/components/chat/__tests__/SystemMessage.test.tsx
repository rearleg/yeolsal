// Story 3.5 FE-5.1 — <SystemMessage> KUDOS visual variant + a11y prefix +
// brand-voice contract on the rendered body.

import { render, screen } from "@testing-library/react-native";
import type { ChatMessageDto } from "../../../api/chat";
import { palette } from "../../../theme/tokens";
import { SystemMessage } from "../SystemMessage";

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

function kudosMessage(overrides: Partial<ChatMessageDto> = {}): ChatMessageDto {
  return {
    id: 1,
    roomId: 42,
    senderUserId: 7,
    kind: "KUDOS",
    body: "alice이 응원을 보냈어요",
    payload: { senderUserId: "7", targetUserId: "11", message: "" },
    createdAt: "2026-05-17T03:14:15Z",
    ...overrides,
  };
}

function systemMessage(overrides: Partial<ChatMessageDto> = {}): ChatMessageDto {
  return {
    id: 2,
    roomId: 42,
    senderUserId: null,
    kind: "SYSTEM",
    body: "alice 함께합니다 🌿",
    payload: {},
    createdAt: "2026-05-17T03:14:15Z",
    ...overrides,
  };
}

describe("<SystemMessage> KUDOS variant", () => {
  it("renders the body verbatim with the 응원 a11y prefix", () => {
    render(<SystemMessage message={kudosMessage()} />);
    expect(screen.getByText("alice이 응원을 보냈어요")).toBeTruthy();
    // KIND_LABEL["KUDOS"] = "응원" — the accessibility label prefix.
    expect(screen.getByLabelText(/^응원:/)).toBeTruthy();
  });

  it("uses postcard-warm tokens — coralSoft pill background", () => {
    const { UNSAFE_root } = render(<SystemMessage message={kudosMessage()} />);
    // The pill background lives on the inner View; flatten styles and look
    // for the postcard tone. We probe the prop tree instead of relying on
    // RN's host snapshot which collapses style arrays differently per
    // platform.
    const found = UNSAFE_root.findAll((node: { props?: { style?: unknown } }) => {
      const style = node.props?.style;
      if (!style) return false;
      const flat = Array.isArray(style) ? style.flat() : [style];
      return flat.some(
        (s) =>
          s &&
          typeof s === "object" &&
          (s as { backgroundColor?: string }).backgroundColor === palette.coralSoft,
      );
    });
    expect(found.length).toBeGreaterThan(0);
  });

  it("SYSTEM rendering remains a muted variant (no KUDOS regression)", () => {
    const { UNSAFE_root } = render(<SystemMessage message={systemMessage()} />);
    expect(screen.getByLabelText(/^시스템 알림:/)).toBeTruthy();
    // SYSTEM uses the muted pill — NOT the coralSoft KUDOS pill.
    const muted = UNSAFE_root.findAll((node: { props?: { style?: unknown } }) => {
      const style = node.props?.style;
      if (!style) return false;
      const flat = Array.isArray(style) ? style.flat() : [style];
      return flat.some(
        (s) =>
          s &&
          typeof s === "object" &&
          (s as { backgroundColor?: string }).backgroundColor === palette.coralSoft,
      );
    });
    expect(muted.length).toBe(0);
  });

  it("the rendered body passes brand-voice Rule 2 — no AVOID lexicon words", () => {
    const msg = kudosMessage();
    for (const banned of BANNED_WORDS) {
      expect(msg.body).not.toContain(banned);
    }
  });
});
