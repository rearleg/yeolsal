import { type PropsWithChildren } from "react";
import { type ViewStyle } from "react-native";
import { Card, type CardTone } from "./ui/Card";
import { type RoomAccent } from "../theme/tokens";

// Legacy compatibility shim. New code should import `Card` from `./ui/Card`
// directly. This shim maps the legacy `tone` prop onto the new tone+accent
// shape so existing screens render with the Bento Modular Warm look without
// touching each call site.

export type LegacyCardTone = "paper" | "green" | "pink" | "dark" | "acid" | "white" | "surface";

interface Props {
  style?: ViewStyle;
  tone?: LegacyCardTone;
}

interface Mapping {
  tone: CardTone;
  accent?: RoomAccent;
}

function mapTone(legacy: LegacyCardTone): Mapping {
  switch (legacy) {
    case "green":
      return { tone: "accent", accent: "sage" };
    case "pink":
      return { tone: "accent", accent: "salmon" };
    case "acid":
      return { tone: "accent", accent: "ochre" };
    case "surface":
      return { tone: "sunken" };
    case "dark":
      return { tone: "raised" };
    case "white":
    case "paper":
    default:
      return { tone: "default" };
  }
}

export function NeoCard({ children, style, tone = "paper" }: PropsWithChildren<Props>) {
  const { tone: cardTone, accent } = mapTone(tone);
  return (
    <Card tone={cardTone} accent={accent} size="md" style={style}>
      {children}
    </Card>
  );
}
