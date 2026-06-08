/* eslint-disable @typescript-eslint/no-require-imports -- Jest hoists `jest.mock` factories above ES imports, so the factory must use `require()` to reference modules. Canonical Jest mock pattern; mirrors `InviteCodeSheet.test.tsx`. */
// Dynamic Type smoke test for <SurvivalChip /> (Story 1.5 AC9, NFR-9.6.3).
//
// The chip wraps the label in our shared <Text> which caps
// maxFontSizeMultiplier at 1.3 — the static prop is recorded in this
// snapshot, so any regression that removes the cap (or unbounds it) shows
// up immediately as a snapshot diff at PR-review time. The snapshot also
// guards the overall rendered tree shape against accidental overflow
// (extra wrapping Views, split label, etc.).

jest.mock("@expo/vector-icons", () => {
  const { View } = require("react-native");
  return {
    MaterialIcons: ({ name, testID }: { name: string; testID?: string }) => (
      <View testID={testID} accessibilityLabel={`icon:${name}`} />
    ),
  };
});

import { render, screen } from "@testing-library/react-native";
import { SurvivalChip } from "../SurvivalChip";

describe("SurvivalChip — Dynamic Type accessibility guarantees", () => {
  it("ACTIVE label uses a capped maxFontSizeMultiplier (Dynamic Type bound)", () => {
    render(<SurvivalChip state="ACTIVE" />);
    const label = screen.getByTestId("survival-chip-ACTIVE-label");
    expect(label.props.maxFontSizeMultiplier).toBeLessThanOrEqual(1.3);
  });

  it("ACTIVE chip rendered tree stays stable", () => {
    const tree = render(<SurvivalChip state="ACTIVE" />).toJSON();
    expect(tree).toMatchSnapshot();
  });

  it("RED chip rendered tree stays stable", () => {
    const tree = render(<SurvivalChip state="RED" />).toJSON();
    expect(tree).toMatchSnapshot();
  });
});
