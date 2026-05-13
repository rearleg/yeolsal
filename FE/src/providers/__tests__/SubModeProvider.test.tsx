import { render, screen } from "@testing-library/react-native";
import { Text } from "react-native";
import { SubModeProvider, type SubMode } from "../SubModeProvider";
import { useTheme } from "../../theme/useTheme";

function ThemeProbe() {
  const t = useTheme();
  const motion = t.motion as { entry: { duration: number } };
  const space = t.space as { layout: { padding: number } };
  return (
    <>
      <Text testID="bg-canvas">{t.color.bg.canvas.hex}</Text>
      <Text testID="bg-surface">{t.color.bg.surface.hex}</Text>
      <Text testID="bg-elevated">{t.color.bg.elevated.hex}</Text>
      <Text testID="text-primary">{t.color.text.primary.hex}</Text>
      <Text testID="text-secondary">{t.color.text.secondary.hex}</Text>
      <Text testID="radius-default">{String(t.radius.default)}</Text>
      <Text testID="motion-entry-duration">{String(motion.entry.duration)}</Text>
      <Text testID="space-layout-padding">{String(space.layout.padding)}</Text>
    </>
  );
}

interface Expected {
  readonly "bg-canvas"?: string;
  readonly "bg-surface"?: string;
  readonly "bg-elevated"?: string;
  readonly "text-primary"?: string;
  readonly "text-secondary"?: string;
  readonly "radius-default"?: string;
  readonly "motion-entry-duration"?: string;
  readonly "space-layout-padding"?: string;
}

function mountAndAssert(subMode: SubMode, expected: Expected): void {
  render(
    <SubModeProvider subMode={subMode}>
      <ThemeProbe />
    </SubModeProvider>,
  );
  for (const [testId, value] of Object.entries(expected)) {
    if (value === undefined) continue;
    expect(screen.getByTestId(testId)).toHaveTextContent(value);
  }
}

describe("SubModeProvider — resolves all 5 sub-modes + null base", () => {
  it("null (base) — no overrides applied", () => {
    mountAndAssert(null, {
      "bg-canvas": "#1B1715",
      "bg-surface": "#241F1C",
      "bg-elevated": "#2D2724",
      "text-primary": "#F4F0EB",
      "radius-default": "10",
      "motion-entry-duration": "250",
      "space-layout-padding": "20",
    });
  });

  it("editorial — radius 8, padding 24, entry duration 600", () => {
    mountAndAssert("editorial", {
      "radius-default": "8",
      "motion-entry-duration": "600",
      "space-layout-padding": "24",
      "bg-canvas": "#1B1715",
    });
  });

  it("bento — bg.elevated override, radius 12, padding 16", () => {
    mountAndAssert("bento", {
      "bg-elevated": "#27221F",
      "radius-default": "12",
      "space-layout-padding": "16",
    });
  });

  it("quiet — bg + text overrides; entry duration 400", () => {
    mountAndAssert("quiet", {
      "bg-canvas": "#15110F",
      "bg-surface": "#1E1916",
      "text-primary": "#D8D1C7",
      "text-secondary": "#928B81",
      "motion-entry-duration": "400",
    });
  });

  it("postcard — bg.surface swap, entry duration 1500", () => {
    mountAndAssert("postcard", {
      "bg-surface": "#28221F",
      "motion-entry-duration": "1500",
    });
  });

  it("plate — radius 6, padding 16", () => {
    mountAndAssert("plate", {
      "radius-default": "6",
      "space-layout-padding": "16",
    });
  });
});
