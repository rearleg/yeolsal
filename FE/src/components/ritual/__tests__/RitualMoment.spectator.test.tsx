// RitualMoment — Story 1.7 AC5 spectator variant.
// Asserts the ember layer swaps key.glow → key.muted when the spectator
// prop is true, without affecting any other token resolution.

import { render, screen } from "@testing-library/react-native";

import { SubModeProvider } from "../../../providers/SubModeProvider";
import {
  getLastFiredKstDate,
  setLastFiredKstDate,
} from "../../../lib/ritualStorage";
import tokens from "../../../theme/tokens.json";
import { RitualMoment } from "../RitualMoment";

jest.mock("@react-native-async-storage/async-storage", () => ({
  __esModule: true,
  default: { getItem: jest.fn(), setItem: jest.fn(), removeItem: jest.fn() },
}));

jest.mock("../../../lib/ritualStorage", () => ({
  RITUAL_LAST_FIRED_KEY: "ritual.lastFiredKstDate",
  getLastFiredKstDate: jest.fn(),
  setLastFiredKstDate: jest.fn(),
}));

const mockedGet = getLastFiredKstDate as jest.MockedFunction<typeof getLastFiredKstDate>;
const mockedSet = setLastFiredKstDate as jest.MockedFunction<typeof setLastFiredKstDate>;

const KST_IN_WINDOW = new Date(Date.UTC(2026, 4, 13, 21, 3, 0));

const KEY_GLOW_HEX = tokens.color.key.glow.hex;
const KEY_MUTED_HEX = tokens.color.key.muted.hex;

function renderInPostcard(ui: React.ReactElement) {
  return render(<SubModeProvider subMode="postcard">{ui}</SubModeProvider>);
}

function emberBackgroundColor(): string | undefined {
  const layer = screen.getByTestId("ritual-ember");
  // RN Animated.View flattens style arrays into the rendered props.style.
  const style = Array.isArray(layer.props.style)
    ? Object.assign({}, ...layer.props.style)
    : layer.props.style;
  return style?.backgroundColor;
}

describe("RitualMoment — spectator variant (AC5)", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockedGet.mockResolvedValue(null);
    mockedSet.mockResolvedValue(undefined);
  });

  it("uses key.glow on the ember when spectator=false (default treatment)", async () => {
    renderInPostcard(<RitualMoment now={KST_IN_WINDOW} spectator={false} />);

    await screen.findByTestId("ritual-root");
    expect(emberBackgroundColor()).toBe(KEY_GLOW_HEX);
  });

  it("uses key.muted on the ember when spectator=true (dim treatment)", async () => {
    renderInPostcard(<RitualMoment now={KST_IN_WINDOW} spectator />);

    await screen.findByTestId("ritual-root");
    expect(emberBackgroundColor()).toBe(KEY_MUTED_HEX);
  });

  it("verifies that the two ember colors are actually distinct tokens (regression guard)", () => {
    expect(KEY_GLOW_HEX).not.toBe(KEY_MUTED_HEX);
  });
});
