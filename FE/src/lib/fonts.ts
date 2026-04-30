import { useFonts } from "expo-font";

/**
 * Wanted Sans is the primary KR family declared in {@link ../../theme/typography.ts}.
 *
 * Phase 5 deliverable: the loader is wired so the app gates render on font
 * readiness. The font map is intentionally empty by default — Metro bundles
 * resolve {@code require(...)} paths at build time and would fail if the
 * binaries are missing. To enable Wanted Sans:
 *
 *   1. Drop the .otf files into {@code FE/assets/fonts/} (see the README in
 *      that directory for which files and where to fetch them).
 *   2. Uncomment the four require() lines in {@code FONT_MAP} below.
 *
 * Until then, the hook resolves immediately with {@code loaded === true}, and
 * platform fonts are used as fallback (this matches the existing
 * {@code fontFamily: "WantedSans"} string in typography.ts).
 */
const FONT_MAP: Record<string, number> = {
  // Activate after dropping font files into FE/assets/fonts/:
  // "WantedSans": require("../../assets/fonts/WantedSans-Regular.otf"),
  // "WantedSans-Medium": require("../../assets/fonts/WantedSans-Medium.otf"),
  // "WantedSans-Bold": require("../../assets/fonts/WantedSans-Bold.otf"),
  // "WantedSans-ExtraBold": require("../../assets/fonts/WantedSans-ExtraBold.otf"),
};

export function useWantedSans(): { loaded: boolean; error: Error | null } {
  const [loaded, error] = useFonts(FONT_MAP);
  return { loaded, error: error ?? null };
}
