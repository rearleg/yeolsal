import { MaterialIcons } from "@expo/vector-icons";
import { Pressable, StyleSheet, View } from "react-native";
import type { MemberTodayDto, RoomMember } from "../../api/rooms";
import { space } from "../../theme/spacing";
import { palette, semantic, surface } from "../../theme/tokens";
import { Card } from "../ui/Card";
import { Text } from "../ui/Text";

interface Hue {
  base: string;
  deep: string;
  soft: string;
}

interface RoomMemberCardProps {
  member: RoomMember;
  hue: Hue;
  /** Today's snapshot for this member, or null while the room-today query is loading. */
  today: MemberTodayDto | null;
  /**
   * Days this calendar month where the member satisfied the daily mission
   * rule (bucket > 0). {@code null} while the per-member grass query is in
   * flight — the card renders a "—/N" placeholder until it resolves so the
   * row never silently shows "0/N" mid-fetch.
   */
  monthSuccess: number | null;
  /** Effective denominator: {@code min(member.currentMinimum, monthDayCount)}. */
  monthDenominator: number;
  onPress?: () => void;
}

/**
 * Per-member dashboard card on the group detail screen. Surfaces today's
 * progress (goal / todos / reflection) and this month's achievement against
 * the member's individual minimum so the owner can see at a glance who is
 * on track and who is at risk of accruing a warning.
 */
export function RoomMemberCard({
  member,
  hue,
  today,
  monthSuccess,
  monthDenominator,
  onPress,
}: RoomMemberCardProps) {
  const goalSet = today?.goalSet ?? false;
  const todoCount = today?.completedTodoCount ?? 0;
  const reflectionSubmitted = today?.reflectionSubmitted ?? false;
  const isOwner = member.role === "OWNER";

  const successCount = monthSuccess ?? 0;
  const progressPct = monthDenominator === 0
    ? 0
    : Math.min(100, Math.round((successCount / monthDenominator) * 100));
  const monthLabel = monthSuccess == null
    ? `—/${monthDenominator}`
    : `${successCount}/${monthDenominator}`;

  const a11yMonth = monthSuccess == null
    ? `이번 달 달성 정보 불러오는 중`
    : `이번 달 ${monthDenominator}일 중 ${successCount}일 달성`;
  const a11yToday = today
    ? `오늘 ${goalSet ? "목표 작성" : "목표 미작성"}, 할 일 ${todoCount}개, ${reflectionSubmitted ? "회고 완료" : "회고 미작성"}`
    : `오늘 정보 없음`;

  return (
    <Pressable
      accessibilityRole="button"
      accessibilityLabel={`${member.nickname} 프로필 열기 — ${a11yToday}, ${a11yMonth}`}
      onPress={onPress}
      style={({ pressed }) => [pressed && { opacity: 0.85 }]}
    >
      <Card tone="default" size="md">
        <View style={styles.headerRow}>
          <View style={[styles.avatar, { backgroundColor: hue.soft }]}>
            <Text variant="bodyStrong" color={hue.deep}>
              {member.nickname.slice(0, 1).toUpperCase()}
            </Text>
          </View>
          <View style={styles.identity}>
            <View style={styles.nameRow}>
              <Text variant="bodyStrong" numberOfLines={1} style={{ flex: 1 }}>
                {member.nickname}
              </Text>
              {isOwner ? (
                <View style={styles.ownerBadge}>
                  <Text variant="caption" color={palette.coralDeep}>그룹장</Text>
                </View>
              ) : null}
            </View>
            <Text variant="caption" color={palette.inkMute}>
              월 {monthDenominator}일 목표
            </Text>
          </View>
          <View style={styles.scorePill}>
            <Text variant="bodyStrong" color={palette.ink}>{monthLabel}</Text>
          </View>
        </View>

        <View style={styles.todayRow}>
          <DailyChip
            label="목표"
            iconName="flag"
            ok={goalSet}
            valueText={goalSet ? "작성" : "미작성"}
          />
          <DailyChip
            label="할 일"
            iconName="check-circle-outline"
            ok={todoCount > 0}
            valueText={`${todoCount}개`}
          />
          <DailyChip
            label="회고"
            iconName="auto-stories"
            ok={reflectionSubmitted}
            valueText={reflectionSubmitted ? "완료" : "미작성"}
          />
        </View>

        <View style={styles.progressTrack} accessibilityElementsHidden>
          <View
            style={[
              styles.progressFill,
              { width: `${progressPct}%`, backgroundColor: hue.deep },
            ]}
          />
        </View>
      </Card>
    </Pressable>
  );
}

interface DailyChipProps {
  label: string;
  iconName: keyof typeof MaterialIcons.glyphMap;
  ok: boolean;
  valueText: string;
}

function DailyChip({ label, iconName, ok, valueText }: DailyChipProps) {
  const fg = ok ? semantic.success.fg : palette.inkMute;
  const bg = ok ? semantic.success.bg : surface.sunken;
  return (
    <View style={[styles.chip, { backgroundColor: bg }]}>
      <MaterialIcons name={iconName} size={14} color={fg} />
      <View style={{ flexShrink: 1 }}>
        <Text variant="caption" color={palette.inkMute}>{label}</Text>
        <Text variant="bodySmall" color={fg} numberOfLines={1}>
          {valueText}
        </Text>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  headerRow: {
    flexDirection: "row",
    alignItems: "center",
    gap: space[3],
  },
  avatar: {
    width: 40,
    height: 40,
    borderRadius: 20,
    alignItems: "center",
    justifyContent: "center",
  },
  identity: { flex: 1, gap: 2 },
  nameRow: { flexDirection: "row", alignItems: "center", gap: space[2] },
  ownerBadge: {
    paddingHorizontal: space[2],
    paddingVertical: 2,
    borderRadius: 999,
    backgroundColor: palette.coralSoft,
  },
  scorePill: {
    paddingHorizontal: space[3],
    paddingVertical: space[1],
    borderRadius: 999,
    backgroundColor: surface.sunken,
  },
  todayRow: {
    flexDirection: "row",
    gap: space[2],
    marginTop: space[3],
  },
  chip: {
    flex: 1,
    flexDirection: "row",
    alignItems: "center",
    gap: space[1],
    paddingHorizontal: space[2],
    paddingVertical: space[2],
    borderRadius: 12,
  },
  progressTrack: {
    marginTop: space[3],
    height: 8,
    borderRadius: 4,
    backgroundColor: surface.sunken,
    overflow: "hidden",
  },
  progressFill: { height: "100%", borderRadius: 4 },
});
