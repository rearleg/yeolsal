// Story 8.1 AC2/AC3/AC4/AC6/AC10/AC11 — 5-screen onboarding carousel.
//
// Single page with internal carousel state (UX line 1731: multi-step form
// is banned; this is a carousel). Horizontal ScrollView pagingEnabled —
// no carousel dependency. S1 alone wraps in the D4 Postcard sub-mode
// (UX line 1171); S2–S5 stay on the base theme resolved by the app-root
// SubModeProvider. Dwell telemetry fires on forward advance only —
// backward navigation is an exploration signal, not screen completion.
// Screen-5 dwell is measured here but emitted by the parent AFTER the
// consent decision lands, so the SDK's runtime opt-state matches user
// intent before any downstream event fires.

import { useCallback, useEffect, useRef, useState } from "react";
import {
  Animated,
  Linking,
  ScrollView,
  StyleSheet,
  Switch,
  View,
  useWindowDimensions,
  type NativeScrollEvent,
  type NativeSyntheticEvent,
} from "react-native";
import { MIN_DAYS_LABELS, type Room } from "../../api/rooms";
import { captureEvent } from "../../lib/analytics";
import type { AnalyticsConsent } from "../../lib/analyticsConsent";
import {
  useRoomMembersQuery,
  useRoomsQuery,
} from "../../lib/query/hooks/rooms";
import { SubModeProvider } from "../../providers/SubModeProvider";
import { useReducedMotion } from "../../theme/motion";
import { space } from "../../theme/spacing";
import { useTheme } from "../../theme/useTheme";
import { Button } from "../ui/Button";
import { Text } from "../ui/Text";
import { OnboardingDotIndicator } from "./OnboardingDotIndicator";

const S1_BODY = "열살방은 친구와 함께 살아남는 방입니다.";
const S2_BODY = "매일 약속을 지키면 살아남습니다. 빠지면 친구가 살릴 수 있어요.";
const S3_BODY = "v1에서는 돈을 받지 않습니다 — 살아남는 것 자체가 자산입니다.";
const S4_BODY = "친구를 살리는 건 옵션이지 의무가 아닙니다.";
const GRACE_BANNER = "처음 14일은 환영 기간이에요";
const WALLET_TITLE = "Wallet";
const WALLET_BODY = "처음 합류한 그룹에서 무료 회생권 1장이 자동으로 발급돼요.";
const WALLET_ROW = "🎟️ 무료 회생권 ×1";
const ROOM_TITLE = "Room";
const ROOM_EMPTY_BODY = "다음 단계에서 그룹을 만들거나 친구의 초대 코드를 입력하세요.";
const PIPA_TITLE = "사용 통계 공유 (선택)";
const PIPA_BODY = "개인을 직접 식별하지 않는 앱 이용 통계를 수집해 서비스 개선에 사용해요.";
const PIPA_CHECKBOX_LABEL = "사용 통계 공유에 동의합니다";
const PIPA_LINK_LABEL = "자세히 보기";
const PRIVACY_POLICY_URL = "https://yeolsal.app/privacy";
const NEXT_LABEL = "다음";
const BACK_LABEL = "이전";
const SUBMIT_LABEL = "시작하기";
const SCREEN_COUNT = 5;

type ScreenIndex = 1 | 2 | 3 | 4 | 5;

export interface OnboardingCompletionResult {
  readonly consent: AnalyticsConsent;
  readonly screen5DwellMs: number;
}

interface OnboardingCarouselProps {
  readonly deferredDestination: string | null;
  readonly onComplete: (result: OnboardingCompletionResult) => void;
}

export function OnboardingCarousel({
  deferredDestination,
  onComplete,
}: OnboardingCarouselProps) {
  const reducedMotion = useReducedMotion();
  const [consentChecked, setConsentChecked] = useState(false);
  const { width } = useWindowDimensions();
  const navigation = useCarouselNavigation(width, reducedMotion);

  function handleSubmit() {
    onComplete({
      consent: consentChecked ? "opt_in" : "opt_out",
      screen5DwellMs: navigation.currentDwellMs(),
    });
  }

  return (
    <View style={styles.root}>
      <CarouselPages
        width={width}
        navigation={navigation}
        deferredDestination={deferredDestination}
        consentChecked={consentChecked}
        onToggleConsent={setConsentChecked}
      />
      <CarouselFooter navigation={navigation} onSubmit={handleSubmit} />
    </View>
  );
}

type CarouselNavigation = ReturnType<typeof useCarouselNavigation>;

function CarouselPages({
  width,
  navigation,
  deferredDestination,
  consentChecked,
  onToggleConsent,
}: {
  width: number;
  navigation: CarouselNavigation;
  deferredDestination: string | null;
  consentChecked: boolean;
  onToggleConsent: (next: boolean) => void;
}) {
  return (
    <ScrollView ref={navigation.scrollRef} horizontal pagingEnabled disableIntervalMomentum
      showsHorizontalScrollIndicator={false}
      onMomentumScrollEnd={navigation.handleMomentumEnd} testID="onboarding-carousel">
      <ScreenOne width={width} />
      <BodyScreen width={width} body={S2_BODY} testID="onboarding-s2" />
      <BodyScreen width={width} body={S3_BODY} testID="onboarding-s3" />
      <BodyScreen width={width} body={S4_BODY} testID="onboarding-s4" />
      <ScreenFive width={width} deferredDestination={deferredDestination}
        consentChecked={consentChecked} onToggleConsent={onToggleConsent} />
    </ScrollView>
  );
}

function CarouselFooter({
  navigation,
  onSubmit,
}: {
  navigation: CarouselNavigation;
  onSubmit: () => void;
}) {
  return (
    <View style={styles.footer}>
      <View style={styles.footerSide}>
        {navigation.current > 1 ? (
          <Button label={BACK_LABEL} tone="ghost" size="md" onPress={navigation.goBack} />
        ) : null}
      </View>
      <OnboardingDotIndicator total={SCREEN_COUNT} current={navigation.current} />
      <View style={[styles.footerSide, styles.footerEnd]}>
        <Button label={navigation.current < SCREEN_COUNT ? NEXT_LABEL : SUBMIT_LABEL}
          tone="primary" size="md"
          onPress={navigation.current < SCREEN_COUNT ? navigation.advance : onSubmit} />
      </View>
    </View>
  );
}

function useCarouselNavigation(width: number, reducedMotion: boolean) {
  const [current, setCurrent] = useState<ScreenIndex>(1);
  const scrollRef = useRef<ScrollView>(null);
  const enteredAtRef = useRef(Date.now());
  const scrollTo = useCallback((screen: ScreenIndex, animated = !reducedMotion) => {
    scrollRef.current?.scrollTo({ x: width * (screen - 1), animated });
  }, [reducedMotion, width]);
  const moveTo = useCallback((screen: ScreenIndex, emit: boolean) => {
    if (emit) emitDwell(current, enteredAtRef.current);
    enteredAtRef.current = Date.now();
    setCurrent(screen);
    scrollTo(screen);
  }, [current, scrollTo]);
  useEffect(() => {
    scrollTo(current, false);
  }, [current, scrollTo]);
  const advance = () => {
    if (current < SCREEN_COUNT) moveTo((current + 1) as ScreenIndex, true);
  };
  const goBack = () => {
    if (current > 1) moveTo((current - 1) as ScreenIndex, false);
  };
  const handleMomentumEnd = (event: NativeSyntheticEvent<NativeScrollEvent>) => {
    const raw = Math.round(event.nativeEvent.contentOffset.x / width) + 1;
    const landed = Math.min(SCREEN_COUNT, Math.max(1, raw), current + 1) as ScreenIndex;
    if (landed !== current) moveTo(landed, landed > current);
    if (landed !== raw) scrollTo(landed);
  };
  return {
    current,
    scrollRef,
    advance,
    goBack,
    handleMomentumEnd,
    currentDwellMs: () => Date.now() - enteredAtRef.current,
  };
}

function emitDwell(screen: ScreenIndex, enteredAt: number) {
  captureEvent("onboarding.screen.dwell_ms", {
    screen,
    dwellMs: Date.now() - enteredAt,
  });
}

/**
 * S1 D4 hint (UX line 1171). The sub-mode wrap lives HERE, on the screen
 * boundary — the leaf content reads useTheme() only and never branches on
 * the sub-mode string (UX cross-cutting rule #9).
 */
function ScreenOne({ width }: { width: number }) {
  return (
    <SubModeProvider subMode="postcard">
      <ScreenOneContent width={width} />
    </SubModeProvider>
  );
}

function ScreenOneContent({ width }: { width: number }) {
  const theme = useTheme();
  const reducedMotion = useReducedMotion();
  const opacity = useRef(new Animated.Value(reducedMotion ? 1 : 0)).current;

  useEffect(() => {
    if (reducedMotion) {
      opacity.stopAnimation(() => opacity.setValue(1));
      return;
    }
    const duration = (theme.motion.entry as { duration: number }).duration;
    Animated.timing(opacity, {
      toValue: 1,
      duration,
      useNativeDriver: true,
    }).start();
  }, [opacity, reducedMotion, theme.motion.entry]);

  const display = theme.typography["display.serif"] as {
    readonly size: number;
    readonly lineHeight: number;
    readonly weight: number;
    readonly family: string;
  };
  const padding = (theme.space.layout as { padding: number }).padding;

  return (
    <View style={[styles.screen, { width, padding }]}>
      <Animated.View
        testID="onboarding-s1-card"
        style={{
          backgroundColor: theme.color.bg.surface.hex,
          borderRadius: theme.radius.pronounced,
          padding,
          opacity,
        }}
      >
        <Text
          style={{
            fontFamily: display.family,
            fontSize: display.size,
            lineHeight: display.lineHeight,
            fontWeight: String(display.weight) as "700",
            color: theme.color.text.primary.hex,
          }}
        >
          {S1_BODY}
        </Text>
      </Animated.View>
    </View>
  );
}

function BodyScreen({
  width,
  body,
  testID,
}: {
  width: number;
  body: string;
  testID: string;
}) {
  return (
    <View style={[styles.screen, { width }]} testID={testID}>
      <Text variant="h2">{body}</Text>
    </View>
  );
}

interface ScreenFiveProps {
  readonly width: number;
  readonly deferredDestination: string | null;
  readonly consentChecked: boolean;
  readonly onToggleConsent: (next: boolean) => void;
}

/**
 * S5 composite (AC4): grace banner → Wallet preview → Room preview → PIPA
 * consent. The Room preview reads the TanStack cache directly (the deeplink
 * join already happened in AuthContext before onboarding started — Trap #9),
 * falling through to the default copy when the lookup misses.
 */
function ScreenFive({
  width,
  deferredDestination,
  consentChecked,
  onToggleConsent,
}: ScreenFiveProps) {
  const theme = useTheme();
  const roomsQuery = useRoomsQuery();
  const rooms = roomsQuery.data ?? [];
  const previewRoomId = parseRoomIdFromDestination(deferredDestination);
  const previewRoom =
    previewRoomId != null
      ? rooms.find((room) => room.id === previewRoomId) ?? null
      : rooms[0] ?? null;
  const membersQuery = useRoomMembersQuery(previewRoom?.id ?? Number.NaN);
  const memberCount = membersQuery.data?.length ?? 0;

  return (
    <View style={{ width }} testID="onboarding-s5">
      <ScrollView contentContainerStyle={styles.s5Content}>
        <GraceCard theme={theme} />
        <WalletCard theme={theme} />
        <RoomPreviewCard theme={theme} room={previewRoom} memberCount={memberCount} />
        <ConsentCard
          theme={theme}
          checked={consentChecked}
          onToggle={onToggleConsent}
        />
      </ScrollView>
    </View>
  );
}

type Theme = ReturnType<typeof useTheme>;

function GraceCard({ theme }: { theme: Theme }) {
  return (
    <View style={cardStyle(theme, theme.color.ember.subtle.hex)}>
      <Text variant="bodyStrong" color={theme.color.text.primary.hex} numberOfLines={1}>
        {GRACE_BANNER}
      </Text>
    </View>
  );
}

function WalletCard({ theme }: { theme: Theme }) {
  const secondary = theme.color.text.secondary.hex;
  return (
    <View style={cardStyle(theme)}>
      <Text variant="bodyStrong" color={theme.color.text.primary.hex}>{WALLET_TITLE}</Text>
      <Text variant="bodySmall" color={secondary}>{WALLET_BODY}</Text>
      <Text variant="bodySmall" color={secondary}>{WALLET_ROW}</Text>
    </View>
  );
}

function RoomPreviewCard({
  theme,
  room,
  memberCount,
}: {
  theme: Theme;
  room: Room | null;
  memberCount: number;
}) {
  const secondary = theme.color.text.secondary.hex;
  return (
    <View style={cardStyle(theme)}>
      <Text variant="bodyStrong" color={theme.color.text.primary.hex} numberOfLines={1}>
        {room?.name ?? ROOM_TITLE}
      </Text>
      <Text variant="bodySmall" color={secondary}>
        {room ? `목표 ${MIN_DAYS_LABELS[room.minDailyGoalDays]}` : ROOM_EMPTY_BODY}
      </Text>
      {room && memberCount > 0 ? (
        <Text variant="bodySmall" color={secondary}>{`${memberCount}명 함께 살아남는 중`}</Text>
      ) : null}
    </View>
  );
}

function ConsentCard({
  theme,
  checked,
  onToggle,
}: {
  theme: Theme;
  checked: boolean;
  onToggle: (next: boolean) => void;
}) {
  const secondary = theme.color.text.secondary.hex;
  return (
    <View style={cardStyle(theme)}>
      <Text variant="bodyStrong" color={theme.color.text.primary.hex}>{PIPA_TITLE}</Text>
      <Text variant="bodySmall" color={secondary}>{PIPA_BODY}</Text>
      <Text variant="caption" color={secondary} accessibilityRole="link" onPress={openPrivacyPolicy}>
        {PIPA_LINK_LABEL}
      </Text>
      <View style={styles.consentRow}>
        <Text variant="bodySmall" color={theme.color.text.primary.hex} style={styles.consentLabel}>
          {PIPA_CHECKBOX_LABEL}
        </Text>
        <Switch value={checked} onValueChange={onToggle} accessibilityLabel={PIPA_CHECKBOX_LABEL} testID="onboarding-pipa-consent" />
      </View>
    </View>
  );
}

function cardStyle(theme: Theme, backgroundColor = theme.color.bg.surface.hex) {
  return [
    styles.s5Card,
    { backgroundColor, borderRadius: theme.radius.pronounced },
  ];
}

function openPrivacyPolicy() {
  Linking.openURL(PRIVACY_POLICY_URL).catch(() => undefined);
}

function parseRoomIdFromDestination(destination: string | null): number | null {
  if (!destination) return null;
  const match = /^\/rooms\/(\d+)(?:[/?#]|$)/.exec(destination);
  if (!match) return null;
  const id = Number(match[1]);
  return Number.isSafeInteger(id) && id > 0 ? id : null;
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
  },
  screen: {
    flex: 1,
    justifyContent: "center",
    paddingHorizontal: space[5],
  },
  s5Content: {
    flexGrow: 1,
    justifyContent: "center",
    gap: space[3],
    padding: space[5],
    paddingBottom: space[8],
  },
  s5Card: {
    padding: space[4],
    gap: space[2],
  },
  consentRow: {
    flexDirection: "row",
    alignItems: "center",
    gap: space[3],
    marginTop: space[2],
  },
  consentLabel: {
    flex: 1,
  },
  footer: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    padding: space[5],
    gap: space[3],
  },
  footerSide: {
    minWidth: 96,
  },
  footerEnd: {
    alignItems: "flex-end",
  },
});
