// ReceivedRevivalsDetailScreen (Story 3.4 AC3 + AC5).
//
// Drill-in off the Wallet's "받은 회생권" section. Renders the lifetime
// received-revival history for (caller, roomId) covering all 3 sources
// (FREE_TICKET / PERSONAL_POINTS / FRIEND_GIFT). Donor nickname surfaces
// only on FRIEND_GIFT rows per FR-8.3.5 (receiver-only visibility — the
// BE response is already filtered; this FE just renders what arrives).

import { useMemo } from "react";
import { ActivityIndicator, ScrollView, StyleSheet, View } from "react-native";
import { Screen } from "../Screen";
import { Text } from "../ui/Text";
import { useReceivedRevivals } from "../../lib/query/hooks/wallet";
import type { ReceivedRevivalDto, RevivalSource } from "../../api/wallet";
import { space } from "../../theme/spacing";
import { palette, surface } from "../../theme/tokens";

interface ReceivedRevivalsDetailScreenProps {
  readonly roomId: number;
}

const SOURCE_ICON: Record<RevivalSource, string> = {
  FREE_TICKET: "🎟",
  PERSONAL_POINTS: "🌿",
  FRIEND_GIFT: "💗",
};

const SOURCE_LABEL: Record<RevivalSource, string> = {
  FREE_TICKET: "무료 회생권",
  PERSONAL_POINTS: "포인트로 회생",
  FRIEND_GIFT: "친구의 선물",
};

const SOURCE_CAPTION: Record<RevivalSource, string> = {
  FREE_TICKET: "스스로 회생",
  PERSONAL_POINTS: "내 포인트 3점 사용",
  FRIEND_GIFT: "친구의 선물",
};

const EMPTY_COPY = "이 방에서 받은 회생권이 아직 없어요";
const ERROR_COPY = "잠시 후 다시 시도해주세요";

const KST_DATE = new Intl.DateTimeFormat("ko-KR", {
  timeZone: "Asia/Seoul",
  year: "numeric",
  month: "long",
  day: "numeric",
});

const KST_YEAR = new Intl.DateTimeFormat("en-US", {
  timeZone: "Asia/Seoul",
  year: "numeric",
});

// Include the year only when the row falls outside the current KST year
// — two rows 365 days apart used to render identically without a year.
function formatKstDate(isoUtc: string): string {
  const date = new Date(isoUtc);
  const parts = KST_DATE.formatToParts(date);
  const lookup = Object.fromEntries(parts.map((p) => [p.type, p.value]));
  const month = lookup.month ?? "";
  const day = lookup.day ?? "";
  const rowYear = lookup.year;
  const nowYear = KST_YEAR.format(new Date());
  const yearPrefix = rowYear && rowYear !== nowYear ? `${rowYear}년 ` : "";
  return `${yearPrefix}${month} ${day}일`;
}

export function ReceivedRevivalsDetailScreen({
  roomId,
}: ReceivedRevivalsDetailScreenProps) {
  const query = useReceivedRevivals(roomId);
  const rows: readonly ReceivedRevivalDto[] = useMemo(
    () => query.data ?? [],
    [query.data],
  );

  return (
    <Screen title="받은 회생권">
      <ScrollView contentContainerStyle={styles.content}>
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

        {rows.map((row) => (
          <View key={row.revivalEventId} style={styles.row}>
            <Text variant="h3" color={palette.coralDeep}>
              {SOURCE_ICON[row.source]}
            </Text>
            <View style={styles.rowMain}>
              <Text variant="bodyStrong" color={palette.ink}>
                {SOURCE_LABEL[row.source]}
              </Text>
              <Text variant="caption" color={palette.inkMute}>
                {formatKstDate(row.occurredAt)}
              </Text>
              {row.source === "FRIEND_GIFT" && row.donorNickname != null ? (
                <Text variant="bodySmall" color={palette.inkSoft}>
                  {`${row.donorNickname}님이 보낸 회생권`}
                </Text>
              ) : (
                <Text variant="bodySmall" color={palette.inkMute}>
                  {SOURCE_CAPTION[row.source]}
                </Text>
              )}
            </View>
          </View>
        ))}
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  content: {
    gap: space[3],
    paddingBottom: space[8],
  },
  row: {
    flexDirection: "row",
    alignItems: "center",
    gap: space[3],
    paddingVertical: space[3],
    paddingHorizontal: space[3],
    borderRadius: 12,
    backgroundColor: surface.sunken,
  },
  rowMain: {
    flex: 1,
    gap: 2,
  },
});
