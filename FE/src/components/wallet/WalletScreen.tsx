// WalletScreen (Story 3.4 AC1, AC5, AC6, AC7).
//
// Per-room wallet surface with four sections in fixed top-to-bottom order:
//   1. Free revival ticket (user-scoped flag; identical across rooms)
//   2. Personal points balance (tappable → /wallet/{roomId}/ledger)
//   3. Room point pool (<PoolBar> + Story 3.3's <FriendGiftBadge>)
//   4. 받은 회생권 history (tappable → /wallet/{roomId}/received-revivals)
//
// All sections are Bento Surface cards under the D2.bento sub-mode (the
// route file `app/wallet/[roomId].tsx` wraps this screen in
// <SubModeProvider subMode="bento">). Leaf tokens come from useTheme();
// stable palette colors stay as palette.* per the transitional precedent
// set by other recent screens (Story 3.3 FriendGiftBadge).

import { router } from "expo-router";
import { useMemo } from "react";
import {
  ActivityIndicator,
  Pressable,
  ScrollView,
  StyleSheet,
  View,
} from "react-native";
import { Screen } from "../Screen";
import { Text } from "../ui/Text";
import { useCurrentRoomSurvivalState, useMeSurvivalQuery } from "../../lib/query/hooks/survival";
import { useRoomPoints } from "../../lib/query/hooks/roomPoints";
import { useReceivedRevivals } from "../../lib/query/hooks/wallet";
import { FriendGiftBadge } from "../revival/FriendGiftBadge";
import { PoolBar } from "../revival/PoolBar";
import { space } from "../../theme/spacing";
import { palette, surface } from "../../theme/tokens";
import { useTheme } from "../../theme/useTheme";

// Story 4.3 wires the real per-room threshold table; v1 placeholder caps
// the PoolBar fill ratio at this constant so the bar renders sensibly
// for the v1 low-magnitude pool numbers.
// TODO(Story 4.3): replace with the BE-shipped poolMax per room.
const POOL_MAX_V1 = 100;

const COPY = {
  freeTicketEnabled: "🎟  무료 회생권 1매",
  freeTicketEnabledCaption: "남은 회생권",
  freeTicketUsed: "🎟  사용 완료",
  freeTicketUsedCaption: "다음 시즌에 새로 받아요",
  personalPointsLabel: "개인 포인트",
  poolLabel: "그룹 포인트",
  poolPromise: "다음 시즌, 그룹 포인트는 함께 마실 커피로 교환됩니다.",
  receivedLabel: "받은 회생권",
  receivedEmpty: "아직 없어요",
  errorRetry: "잠시 후 다시 시도해주세요",
  notAMember: "이 방에 더 이상 속해 있지 않아요",
} as const;

const KST_MD = new Intl.DateTimeFormat("ko-KR", {
  timeZone: "Asia/Seoul",
  year: "numeric",
  month: "long",
  day: "numeric",
});

const KST_YEAR = new Intl.DateTimeFormat("en-US", {
  timeZone: "Asia/Seoul",
  year: "numeric",
});

function formatRecentDate(isoUtc: string | null): string {
  if (isoUtc == null) return COPY.receivedEmpty;
  const date = new Date(isoUtc);
  const parts = KST_MD.formatToParts(date);
  const lookup = Object.fromEntries(parts.map((p) => [p.type, p.value]));
  const month = lookup.month ?? "";
  const day = lookup.day ?? "";
  const rowYear = lookup.year;
  const nowYear = KST_YEAR.format(new Date());
  const yearPrefix = rowYear && rowYear !== nowYear ? `${rowYear}년 ` : "";
  return `${yearPrefix}${month} ${day}일`;
}

interface WalletScreenProps {
  readonly roomId: number;
}

export function WalletScreen({ roomId }: WalletScreenProps) {
  const meSurvivalQuery = useMeSurvivalQuery();
  const survival = useCurrentRoomSurvivalState(roomId);
  const receivedQuery = useReceivedRevivals(roomId);
  // Story 4.1 AC9 — live per-room pool total via dedicated REST + STOMP hook.
  // `survival.roomPointPool` (the cross-room aggregation from /me/survival)
  // is preserved on MeSurvivalEntry for spectator surfaces; the per-room
  // Wallet route reads from the hook so the value updates from STOMP frames
  // without re-fetching the cross-room aggregation per pool event.
  const roomPoints = useRoomPoints(roomId);
  const theme = useTheme();

  const headerTitle = useMemo(() => {
    const name = survival?.roomName ?? "";
    return name ? `${name} Wallet` : "Wallet";
  }, [survival?.roomName]);

  const sectionGap = typeof theme.space.layout.padding === "number"
    ? theme.space.layout.padding
    : 16;
  const surfaceRadius = typeof theme.radius.default === "number"
    ? theme.radius.default
    : 12;

  const loading = meSurvivalQuery.isLoading;
  const error = meSurvivalQuery.isError;

  if (loading) {
    return (
      <Screen title={headerTitle}>
        <View style={styles.centered}>
          <ActivityIndicator color={palette.coralDeep} />
        </View>
      </Screen>
    );
  }

  if (error) {
    return (
      <Screen title={headerTitle}>
        <View style={styles.centered}>
          <Text variant="body" color={palette.inkMute}>
            {COPY.errorRetry}
          </Text>
        </View>
      </Screen>
    );
  }

  // Query succeeded but this roomId is not in the user's membership list
  // (left the room mid-session, or stale deep link). Distinguish from the
  // transient-error branch so the user gets a meaningful "you're not a
  // member" message instead of a retry prompt.
  if (survival == null) {
    return (
      <Screen title={headerTitle}>
        <View style={styles.centered}>
          <Text variant="body" color={palette.inkMute}>
            {COPY.notAMember}
          </Text>
        </View>
      </Screen>
    );
  }

  const ticketUsed = survival.freeRevivalTicketUsed;
  const personalPoints = survival.personalPoints;
  // Story 4.1 Patch 5 — fall back to the cross-room aggregation when the
  // dedicated per-room query is still loading or has errored. Otherwise
  // the user sees a false `0` for the seconds between mount and the
  // first REST response (or indefinitely on network failure), even
  // though `survival.roomPointPool` already carries a usable snapshot
  // from the meSurvival cache.
  const pool = roomPoints.isLoading || roomPoints.isError
    ? survival.roomPointPool
    : roomPoints.total;
  const received = receivedQuery.data ?? [];
  const receivedCount = received.length;
  const mostRecentReceivedAt = receivedCount > 0
    ? received[0].occurredAt
    : null;

  const cardStyle = [
    styles.card,
    { borderRadius: surfaceRadius, padding: sectionGap },
  ];

  return (
    <Screen title={headerTitle}>
      <ScrollView
        contentContainerStyle={[styles.content, { gap: sectionGap }]}
      >
        <View style={cardStyle} testID="wallet-section-ticket">
          <Text variant="bodyStrong" color={palette.ink}>
            {ticketUsed ? COPY.freeTicketUsed : COPY.freeTicketEnabled}
          </Text>
          <Text variant="caption" color={palette.inkMute}>
            {ticketUsed
              ? COPY.freeTicketUsedCaption
              : COPY.freeTicketEnabledCaption}
          </Text>
        </View>

        <Pressable
          accessibilityRole="button"
          accessibilityLabel={`${COPY.personalPointsLabel} ${personalPoints}점, 탭하면 상세 보기`}
          onPress={() => router.push(`/wallet/${roomId}/ledger`)}
          style={cardStyle}
          testID="wallet-section-personal-points"
        >
          <Text variant="caption" color={palette.inkMute}>
            {COPY.personalPointsLabel}
          </Text>
          <Text variant="numericDisplay" color={palette.ink}>
            {personalPoints}
          </Text>
        </Pressable>

        <View style={cardStyle} testID="wallet-section-pool">
          <Text variant="caption" color={palette.inkMute}>
            {COPY.poolLabel}
          </Text>
          <Text variant="numericDisplay" color={palette.ink}>
            {pool}
          </Text>
          <View style={styles.poolBarSpacer}>
            <PoolBar total={pool} max={POOL_MAX_V1} />
          </View>
          <Text variant="caption" color={palette.inkMute}>
            {COPY.poolPromise}
          </Text>
          <FriendGiftBadge
            roomId={roomId}
            onTap={() => {
              // Wallet AC7 — N=1 case opens the modal. The Modal/Picker
              // state machine lives one level up in WalletPreview-style
              // wiring; on the standalone Wallet route we delegate the
              // user back to the room screen which already owns the
              // FriendGiftModal mount surface (FriendGiftSurfaces).
              router.push(`/rooms/${roomId}`);
            }}
            onTapMulti={() => {
              router.push(`/rooms/${roomId}`);
            }}
          />
        </View>

        <Pressable
          accessibilityRole="button"
          accessibilityLabel={`${COPY.receivedLabel} ${receivedCount}건, 탭하면 상세 보기`}
          onPress={() => router.push(`/wallet/${roomId}/received-revivals`)}
          style={cardStyle}
          testID="wallet-section-received"
        >
          <Text variant="caption" color={palette.inkMute}>
            {COPY.receivedLabel}
          </Text>
          <Text variant="numericDisplay" color={palette.ink}>
            {receivedCount}
          </Text>
          <Text variant="caption" color={palette.inkMute}>
            {formatRecentDate(mostRecentReceivedAt)}
          </Text>
        </Pressable>
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  content: {
    paddingBottom: space[8],
  },
  card: {
    backgroundColor: surface.sunken,
    gap: space[1],
  },
  centered: {
    flex: 1,
    alignItems: "center",
    justifyContent: "center",
  },
  poolBarSpacer: {
    marginTop: space[2],
    marginBottom: space[2],
  },
});
