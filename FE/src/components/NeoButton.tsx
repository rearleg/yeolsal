import { Button, type ButtonTone } from "./ui/Button";

// Legacy compatibility shim. New code should import `Button` from `./ui/Button`
// directly. Maps the legacy `tone` values onto the new design system tones.

export type LegacyButtonTone = "green" | "pink" | "acid" | "white" | "kakao" | "black";

interface Props {
  label: string;
  tone?: LegacyButtonTone;
  onPress?: () => void;
  disabled?: boolean;
}

function mapTone(legacy: LegacyButtonTone): ButtonTone {
  switch (legacy) {
    case "white":
      return "secondary";
    case "kakao":
      return "kakao";
    case "green":
    case "pink":
    case "acid":
    case "black":
    default:
      return "primary";
  }
}

export function NeoButton({ label, tone = "green", onPress, disabled = false }: Props) {
  return <Button label={label} tone={mapTone(tone)} size="md" fullWidth onPress={onPress} disabled={disabled} />;
}
