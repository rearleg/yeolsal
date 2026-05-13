import { render, screen } from "@testing-library/react-native";
import { Text } from "react-native";
import { resolveTheme, useTheme } from "../useTheme";
import { SubModeProvider } from "../../providers/SubModeProvider";

function Probe() {
  const t = useTheme();
  return (
    <>
      <Text testID="bg-canvas">{t.color.bg.canvas.hex}</Text>
      <Text testID="bg-surface">{t.color.bg.surface.hex}</Text>
      <Text testID="text-primary">{t.color.text.primary.hex}</Text>
    </>
  );
}

describe("useTheme — pure resolver (no provider)", () => {
  it("returns the base tokens (no overrides) when subMode is null", () => {
    const base = resolveTheme(null);
    expect(base.color.bg.canvas.hex).toBe("#1B1715");
    expect(base.color.bg.surface.hex).toBe("#241F1C");
    expect(base.color.text.primary.hex).toBe("#F4F0EB");
    expect(base.radius.default).toBe(10);
    expect(base.motion.entry.duration).toBe(250);
  });

  it("strips the subMode block from ResolvedTheme", () => {
    const base = resolveTheme(null) as Record<string, unknown>;
    expect("subMode" in base).toBe(false);
  });

  it("returns a fresh clone each call — caller-side mutation does not leak", () => {
    const a = resolveTheme(null);
    (a.color.bg.canvas as { hex: string }).hex = "#000000";
    const b = resolveTheme(null);
    expect(b.color.bg.canvas.hex).toBe("#1B1715");
  });

  it("quiet sub-mode overrides bg + text colors on the resolved theme", () => {
    const quiet = resolveTheme("quiet");
    expect(quiet.color.bg.canvas.hex).toBe("#15110F");
    expect(quiet.color.bg.surface.hex).toBe("#1E1916");
    expect(quiet.color.text.primary.hex).toBe("#D8D1C7");
    expect(quiet.color.text.secondary.hex).toBe("#928B81");
  });

  it("bento sub-mode overrides bg.elevated and radius.default", () => {
    const bento = resolveTheme("bento");
    expect(bento.color.bg.elevated.hex).toBe("#27221F");
    expect(bento.radius.default).toBe(12);
  });

  it("editorial sub-mode overrides motion.entry.duration and radius.default", () => {
    const editorial = resolveTheme("editorial");
    expect(editorial.motion.entry.duration).toBe(600);
    expect(editorial.radius.default).toBe(8);
  });

  it("postcard sub-mode overrides bg.surface and motion.entry.duration", () => {
    const postcard = resolveTheme("postcard");
    expect(postcard.color.bg.surface.hex).toBe("#28221F");
    expect(postcard.motion.entry.duration).toBe(1500);
  });

  it("plate sub-mode overrides radius.default", () => {
    const plate = resolveTheme("plate");
    expect(plate.radius.default).toBe(6);
  });
});

describe("useTheme — under <SubModeProvider>", () => {
  it("without a provider, returns base tokens", () => {
    render(<Probe />);
    expect(screen.getByTestId("bg-canvas")).toHaveTextContent("#1B1715");
    expect(screen.getByTestId("text-primary")).toHaveTextContent("#F4F0EB");
  });

  it("inside <SubModeProvider subMode='quiet'>, returns the merged theme", () => {
    render(
      <SubModeProvider subMode="quiet">
        <Probe />
      </SubModeProvider>,
    );
    expect(screen.getByTestId("bg-canvas")).toHaveTextContent("#15110F");
    expect(screen.getByTestId("bg-surface")).toHaveTextContent("#1E1916");
    expect(screen.getByTestId("text-primary")).toHaveTextContent("#D8D1C7");
  });
});
