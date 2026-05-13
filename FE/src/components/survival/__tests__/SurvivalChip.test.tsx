// Mock @expo/vector-icons up front so the icon glyph renders as a plain
// view in tests. The real component would require the bundled font asset
// which Jest doesn't load in unit-test mode.
jest.mock("@expo/vector-icons", () => {
  const { View } = require("react-native");
  return {
    MaterialIcons: ({ name, testID, ...rest }: { name: string; testID?: string }) => (
      <View testID={testID} accessibilityLabel={`icon:${name}`} {...rest} />
    ),
  };
});

import { render, screen } from "@testing-library/react-native";
import { SurvivalChip } from "../SurvivalChip";
import type { SurvivalState } from "../types";
import { SURVIVAL_ICON_GLYPH } from "../iconMap";

interface ExpectedRow {
  readonly state: SurvivalState;
  readonly hex: string;
  readonly label: string;
}

const EXPECTED: readonly ExpectedRow[] = [
  { state: "ACTIVE", hex: "#6B9A6E", label: "활동 중" },
  { state: "YELLOW", hex: "#B89C4F", label: "노란 카드" },
  { state: "RED", hex: "#7C4640", label: "빨간 카드" },
  { state: "SPECTATOR", hex: "#6E737E", label: "관전 중" },
];

describe("SurvivalChip — all 4 states render packed-type fields correctly", () => {
  for (const row of EXPECTED) {
    it(`${row.state}: renders dot color ${row.hex}, label "${row.label}", icon, and accessibilityLabel`, () => {
      render(<SurvivalChip state={row.state} />);
      const wrapper = screen.getByTestId(`survival-chip-${row.state}`);
      expect(wrapper).toBeTruthy();
      expect(wrapper.props.accessibilityLabel).toBe(row.label);

      const dot = screen.getByTestId(`survival-chip-${row.state}-dot`);
      const dotStyle = Array.isArray(dot.props.style)
        ? Object.assign({}, ...dot.props.style)
        : dot.props.style;
      expect(dotStyle.backgroundColor).toBe(row.hex);

      const icon = screen.getByTestId(`survival-chip-${row.state}-icon`);
      expect(icon.props.accessibilityLabel).toBe(
        `icon:${SURVIVAL_ICON_GLYPH[row.state]}`,
      );

      expect(
        screen.getByTestId(`survival-chip-${row.state}-label`),
      ).toHaveTextContent(row.label);
    });
  }

  it("renders as a non-splittable composite: single View root, role 'text'", () => {
    render(<SurvivalChip state="ACTIVE" />);
    const wrapper = screen.getByTestId("survival-chip-ACTIVE");
    expect(wrapper.props.accessibilityRole).toBe("text");
    expect(screen.queryByText("활동 중")).toBeTruthy();
  });
});
