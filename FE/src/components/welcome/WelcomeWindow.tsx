// WelcomeWindow — Story 1.6 J0 surface (D4 Postcard Mythic).
//
// The leader's first 30 seconds. Pure presentational component: state is
// derived from (memberCount, graceEndsAt) — solo / growing / full — and
// returns null in `full`. The component reads tokens via useTheme() and
// MUST NOT read the sub-mode string directly (UX cross-cutting rule #9,
// Story 1.5 AC7). Wrap the parent site in <SubModeProvider subMode="postcard">.
//
// AC3 anti-pattern guard: no progress bar, no "X명 더 들어와야 시작",
// no countdown / quota visualization tied to member count or grace days.

import { useEffect, useRef } from "react";
import { Animated, Pressable, StyleSheet, View } from "react-native";
import { toast } from "../../lib/toast";
import { useTheme } from "../../theme/useTheme";
import { Text } from "../ui/Text";

const KAKAO_LOCKED_COPY = "곧 카카오 초대가 가능해질 거예요";
const HEADLINE_COPY = "친구를 초대하면 같이 살아남을 수 있어요";
const SOLO_PERIOD_COPY = "환영 기간 14일";
const CTA_A_LABEL = "🥥 카카오로 초대";
const CTA_B_LABEL = "🌿 먼저 오늘 기록하기";
const TODAY_TAGLINE = "첫 잔디 — 곧 함께 채워질 거예요";
const GRACE_DAYS = 14;

export interface WelcomeWindowProps {
  readonly roomName: string;
  readonly memberCount: number;
  readonly graceEndsAt: Date | string;
  readonly kakaoEnabled?: boolean;
  readonly onTapKakao?: () => void;
  readonly onTapStartToday: () => void;
  /** Override for unit tests (defaults to {@code new Date()}). */
  readonly now?: Date;
}

export type WelcomeWindowState = "solo" | "growing" | "full";

export interface ShouldShowWelcomeWindowArgs {
  readonly currentUserId: number | null | undefined;
  readonly ownerId: number | null | undefined;
  readonly memberCount: number;
  readonly roomCreatedAt: Date | string | null | undefined;
  readonly now?: Date;
}

/**
 * Gating predicate used by the room detail screen (FE/app/rooms/[id].tsx) to
 * decide whether to mount the WelcomeWindow. Single seat of the rendering rule
 * so the screen and any future surface stay in agreement. Returns false on any
 * missing input rather than throwing — defensive at the integration boundary.
 */
export function shouldShowWelcomeWindow(args: ShouldShowWelcomeWindowArgs): boolean {
  if (args.currentUserId == null || args.ownerId == null) return false;
  if (args.currentUserId !== args.ownerId) return false;
  if (args.memberCount < 1) return false;
  if (args.roomCreatedAt == null) return false;
  const createdAt =
    args.roomCreatedAt instanceof Date ? args.roomCreatedAt : new Date(args.roomCreatedAt);
  if (Number.isNaN(createdAt.getTime())) return false;
  const now = args.now ?? new Date();
  const graceEnds = new Date(createdAt.getTime() + GRACE_DAYS * 24 * 60 * 60 * 1000);
  return now.getTime() < graceEnds.getTime();
}

export function deriveWelcomeWindowState(
  memberCount: number,
  graceEndsAt: Date,
  now: Date,
): WelcomeWindowState {
  if (memberCount <= 1) return "solo";
  return now.getTime() < graceEndsAt.getTime() ? "growing" : "full";
}

export function WelcomeWindow({
  roomName: _roomName,
  memberCount,
  graceEndsAt,
  kakaoEnabled = false,
  onTapKakao,
  onTapStartToday,
  now,
}: WelcomeWindowProps) {
  void _roomName;
  const theme = useTheme();
  const graceDate = graceEndsAt instanceof Date ? graceEndsAt : new Date(graceEndsAt);
  const nowDate = now ?? new Date();
  const state = deriveWelcomeWindowState(memberCount, graceDate, nowDate);

  // Postcard sub-mode resolves motion.entry.duration → 1500ms. The fade
  // makes the postcard tone tangible from first frame. Native driver keeps
  // it on the compositor.
  const opacity = useRef(new Animated.Value(0)).current;
  useEffect(() => {
    if (state === "full") return;
    const duration = (theme.motion.entry as { duration: number }).duration;
    Animated.timing(opacity, {
      toValue: 1,
      duration,
      useNativeDriver: true,
    }).start();
  }, [opacity, state, theme.motion.entry]);

  if (state === "full") return null;

  const display = theme.typography["display.serif"] as {
    readonly size: number;
    readonly lineHeight: number;
    readonly weight: number;
    readonly family: string;
  };
  const surface = theme.color.bg.surface.hex;
  const primaryText = theme.color.text.primary.hex;
  const kakaoBg = theme.color.key.default.hex;
  const emberBg = theme.color.ember.subtle.hex;
  const radius = theme.radius.pronounced;
  const padding = (theme.space.layout as { padding: number }).padding;

  function handleKakaoPress() {
    if (kakaoEnabled) {
      onTapKakao?.();
      return;
    }
    toast.info(KAKAO_LOCKED_COPY);
  }

  return (
    <Animated.View
      testID="welcome-window"
      accessibilityRole="summary"
      style={[
        styles.wrapper,
        {
          backgroundColor: surface,
          borderRadius: radius,
          padding,
          opacity,
        },
      ]}
    >
      <Text
        testID="welcome-window-headline"
        style={{
          fontFamily: display.family,
          fontSize: display.size,
          lineHeight: display.lineHeight,
          fontWeight: String(display.weight) as "700",
          color: primaryText,
        }}
        numberOfLines={1}
      >
        {HEADLINE_COPY}
      </Text>

      <View style={styles.ctaStack}>
        <Pressable
          testID="welcome-window-cta-kakao"
          accessibilityRole="button"
          accessibilityLabel={CTA_A_LABEL}
          accessibilityHint={kakaoEnabled ? undefined : KAKAO_LOCKED_COPY}
          accessibilityState={{ disabled: !kakaoEnabled }}
          onPress={handleKakaoPress}
          style={[
            styles.cta,
            {
              backgroundColor: kakaoBg,
              borderRadius: radius,
              opacity: kakaoEnabled ? 1 : 0.6,
            },
          ]}
        >
          <Text
            testID="welcome-window-cta-kakao-label"
            style={{ color: primaryText, fontWeight: "700" }}
          >
            {CTA_A_LABEL}
          </Text>
        </Pressable>

        <Pressable
          testID="welcome-window-cta-start-today"
          accessibilityRole="button"
          accessibilityLabel={CTA_B_LABEL}
          onPress={onTapStartToday}
          style={[
            styles.cta,
            {
              backgroundColor: emberBg,
              borderRadius: radius,
            },
          ]}
        >
          <Text
            testID="welcome-window-cta-start-today-label"
            style={{ color: primaryText, fontWeight: "700" }}
          >
            {CTA_B_LABEL}
          </Text>
        </Pressable>
      </View>

      <Text
        testID="welcome-window-period-copy"
        accessibilityRole="text"
        style={{ color: theme.color.text.secondary.hex }}
      >
        {state === "solo"
          ? SOLO_PERIOD_COPY
          : `${memberCount}명 함께 — 환영 기간 끝까지 자유롭게 초대해요`}
      </Text>
    </Animated.View>
  );
}

export { TODAY_TAGLINE };

const styles = StyleSheet.create({
  wrapper: {
    gap: 16,
  },
  ctaStack: {
    gap: 12,
  },
  cta: {
    paddingVertical: 14,
    paddingHorizontal: 16,
    alignItems: "center",
    justifyContent: "center",
    minHeight: 48,
  },
});
