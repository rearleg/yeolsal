// LedgerDetailScreen (Story 3.4 AC2 + AC5).
//
// Drill-in view off the Wallet's "Personal points balance" section. Renders
// every personal_points_ledger row for (caller, roomId), DESC by occurredAt.
// Headline = SUM of deltas, sourced from the existing meSurvival cache so
// the FE never recomputes (BE is authoritative — same number the Wallet
// section card displays).
//
// All Korean strings are AC5-locked (brand-voice 0 HARD violations).

import { useMemo } from "react";
import { ActivityIndicator, ScrollView, StyleSheet, View } from "react-native";
import { Screen } from "../Screen";
import { Text } from "../ui/Text";
import { useCurrentRoomSurvivalState } from "../../lib/query/hooks/survival";
import { usePersonalPointsLedger } from "../../lib/query/hooks/wallet";
import type { LedgerEntryDto, LedgerReason } from "../../api/wallet";
import { space } from "../../theme/spacing";
import { palette, surface } from "../../theme/tokens";

interface LedgerDetailScreenProps {
  readonly roomId: number;
}

// Short label per reason (the headline word on the row). AC5 locks the
// caption strings; labels are derived shorter words that don't appear in
// AC5's banned-lexicon list.
const REASON_LABEL: Record<LedgerReason, string> = {
  SURVIVAL: "잔디",
  REVIVAL_SPEND: "회생권",
  FRIEND_GIFT_SPEND: "친구 선물",
  ROOM_LEAVE: "방 이탈",
  ADJUSTMENT: "조정",
};

const REASON_CAPTION: Record<LedgerReason, string> = {
  SURVIVAL: "오늘의 잔디 한 칸",
  REVIVAL_SPEND: "회생권 사용",
  FRIEND_GIFT_SPEND: "친구에게 선물한 회생권",
  ROOM_LEAVE: "방을 떠났어요",
  ADJUSTMENT: "운영자 조정",
};

const EMPTY_COPY = "이 방에서 받은 잔디 흔적이 아직 없어요";
const ERROR_COPY = "잠시 후 다시 시도해주세요";
const BALANCE_LABEL = "잔액";

const KST_TIMESTAMP = new Intl.DateTimeFormat("ko-KR", {
  timeZone: "Asia/Seoul",
  year: "numeric",
  month: "long",
  day: "numeric",
  hour: "2-digit",
  minute: "2-digit",
  hour12: false,
});

const KST_YEAR = new Intl.DateTimeFormat("en-US", {
  timeZone: "Asia/Seoul",
  year: "numeric",
});

function kstYearOf(date: Date): string {
  return KST_YEAR.format(date);
}

// Include the year only when the row falls outside the current KST year
// so a user reading their second-year ledger can distinguish two rows
// 365 days apart (without bloating recent rows with "2026년").
function formatKstTimestamp(isoUtc: string): string {
  const date = new Date(isoUtc);
  const parts = KST_TIMESTAMP.formatToParts(date);
  const lookup = Object.fromEntries(parts.map((p) => [p.type, p.value]));
  const month = lookup.month ?? "";
  const day = lookup.day ?? "";
  const hour = lookup.hour ?? "00";
  const minute = lookup.minute ?? "00";
  const rowYear = lookup.year;
  const nowYear = kstYearOf(new Date());
  const yearPrefix = rowYear && rowYear !== nowYear ? `${rowYear}년 ` : "";
  return `${yearPrefix}${month} ${day}일 ${hour}:${minute}`;
}

export function LedgerDetailScreen({ roomId }: LedgerDetailScreenProps) {
  const survival = useCurrentRoomSurvivalState(roomId);
  const query = usePersonalPointsLedger(roomId);
  const rows: readonly LedgerEntryDto[] = useMemo(
    () => query.data ?? [],
    [query.data],
  );
  const balance = survival?.personalPoints ?? 0;

  return (
    <Screen title="개인 포인트 잔액">
      <ScrollView contentContainerStyle={styles.content}>
        <View style={styles.headlineCard}>
          <Text variant="caption" color={palette.inkMute}>
            {BALANCE_LABEL}
          </Text>
          <Text variant="numericDisplay" color={palette.ink}>
            {balance}
          </Text>
        </View>

        {query.isLoading ? (
          <ActivityIndicator color={palette.coralDeep} />
        ) : null}
        {query.isError ? (
          <Text variant="body" color={palette.inkMute}>
            {ERROR_COPY}
          </Text>
        ) : null}

        {!query.isLoading && !query.isError && rows.length === 0 ? (
          <Text variant="body" color={palette.inkMute} align="center">
            {EMPTY_COPY}
          </Text>
        ) : null}

        {rows.map((row) => {
          const positive = row.delta >= 0;
          const sign = positive ? "+" : "";
          return (
            <View key={row.id} style={styles.row}>
              <View style={styles.rowMain}>
                <Text variant="bodyStrong" color={palette.ink}>
                  {REASON_LABEL[row.reason]}
                </Text>
                <Text variant="caption" color={palette.inkMute}>
                  {formatKstTimestamp(row.occurredAt)}
                </Text>
                <Text variant="bodySmall" color={palette.inkMute}>
                  {REASON_CAPTION[row.reason]}
                </Text>
              </View>
              <Text
                variant="bodyStrong"
                color={positive ? palette.sageDeep : palette.dangerFg}
                accessibilityLabel={`${sign}${row.delta}점`}
              >
                {`${sign}${row.delta}`}
              </Text>
            </View>
          );
        })}
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  content: {
    gap: space[3],
    paddingBottom: space[8],
  },
  headlineCard: {
    backgroundColor: surface.sunken,
    borderRadius: 12,
    padding: space[4],
    gap: space[1],
    alignItems: "flex-start",
  },
  row: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    paddingVertical: space[3],
    paddingHorizontal: space[3],
    borderRadius: 12,
    backgroundColor: surface.sunken,
    gap: space[3],
  },
  rowMain: {
    flex: 1,
    gap: 2,
  },
});
