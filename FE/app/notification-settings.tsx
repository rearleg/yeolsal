import { useEffect, useState } from "react";
import { Alert, Pressable, ScrollView, StyleSheet, Switch, View } from "react-native";
import {
  getNotificationPrefs,
  updateNotificationPrefs,
  type NotificationPrefs,
} from "../src/api/notifications";
import { useRequireAuth } from "../src/auth/useRequireAuth";
import { Screen } from "../src/components/Screen";
import { Button } from "../src/components/ui/Button";
import { Card } from "../src/components/ui/Card";
import { Skeleton } from "../src/components/ui/Skeleton";
import { Text } from "../src/components/ui/Text";
import { space } from "../src/theme/spacing";
import { palette, surface } from "../src/theme/tokens";

const HOUR_OPTIONS = Array.from({ length: 24 }, (_, i) => i);

export default function NotificationSettingsScreen() {
  useRequireAuth();
  const [prefs, setPrefs] = useState<NotificationPrefs | null>(null);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    void load();
  }, []);

  async function load() {
    try {
      setPrefs(await getNotificationPrefs());
    } catch (error) {
      Alert.alert("알림", error instanceof Error ? error.message : "설정을 불러오지 못했습니다.");
    }
  }

  function update<K extends keyof NotificationPrefs>(key: K, value: NotificationPrefs[K]) {
    setPrefs((p) => (p ? { ...p, [key]: value } : p));
  }

  async function save() {
    if (!prefs) return;
    setSaving(true);
    try {
      const next = await updateNotificationPrefs(prefs);
      setPrefs(next);
      Alert.alert("알림", "저장했어요.");
    } catch (error) {
      Alert.alert("저장 실패", error instanceof Error ? error.message : "다시 시도하세요.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <Screen title="알림 설정">
      <ScrollView contentContainerStyle={styles.content}>
        <Card tone="raised" size="lg">
          <Text variant="h2">알림</Text>
          <Text variant="bodySmall" color={palette.inkMute} style={{ marginTop: space[1] }}>
            방해 받지 않을 시간을 지정할 수 있어요.
          </Text>

          {!prefs ? (
            <View style={{ marginTop: space[3], gap: space[2] }}>
              <Skeleton height={32} />
              <Skeleton height={32} />
              <Skeleton height={32} />
            </View>
          ) : (
            <View style={{ marginTop: space[3], gap: space[3] }}>
              <ToggleRow
                label="아침 8시 목표 알림"
                value={prefs.goalNudgeEnabled}
                onChange={(v) => update("goalNudgeEnabled", v)}
              />
              <ToggleRow
                label="밤 9시 30분 회고 알림"
                value={prefs.reflectionNudgeEnabled}
                onChange={(v) => update("reflectionNudgeEnabled", v)}
              />
              <ToggleRow
                label="친구 활동 알림"
                value={prefs.eventHooksEnabled}
                onChange={(v) => update("eventHooksEnabled", v)}
              />

              <View>
                <Text variant="bodyStrong">방해 금지 시간</Text>
                <Text variant="caption" color={palette.inkMute}>
                  {formatHour(prefs.quietStartHour)} – {formatHour(prefs.quietEndHour)}
                </Text>
              </View>
              <HourSelector
                label="시작"
                value={prefs.quietStartHour}
                onChange={(v) => update("quietStartHour", v)}
              />
              <HourSelector
                label="종료"
                value={prefs.quietEndHour}
                onChange={(v) => update("quietEndHour", v)}
              />

              <Button
                label={saving ? "저장 중…" : "저장"}
                tone="primary"
                size="md"
                fullWidth
                disabled={saving}
                onPress={save}
              />
            </View>
          )}
        </Card>
      </ScrollView>
    </Screen>
  );
}

function ToggleRow({
  label,
  value,
  onChange,
}: {
  label: string;
  value: boolean;
  onChange: (v: boolean) => void;
}) {
  return (
    <View style={styles.toggleRow}>
      <Text variant="bodyStrong" style={{ flex: 1 }}>{label}</Text>
      <Switch
        value={value}
        onValueChange={onChange}
        accessibilityLabel={label}
        thumbColor={palette.paper}
        trackColor={{ false: palette.surfaceContrast, true: palette.coral }}
      />
    </View>
  );
}

function HourSelector({
  label,
  value,
  onChange,
}: {
  label: string;
  value: number;
  onChange: (v: number) => void;
}) {
  return (
    <View>
      <Text variant="caption" color={palette.inkMute}>{label}</Text>
      <ScrollView
        horizontal
        showsHorizontalScrollIndicator={false}
        contentContainerStyle={styles.hourRow}
      >
        {HOUR_OPTIONS.map((h) => {
          const selected = h === value;
          return (
            <Pressable
              key={h}
              accessibilityRole="button"
              accessibilityLabel={`${label} ${formatHour(h)}`}
              onPress={() => onChange(h)}
              style={[styles.hourPill, selected && styles.hourPillSelected]}
            >
              <Text variant="bodyStrong" color={selected ? palette.paper : palette.ink}>
                {formatHour(h)}
              </Text>
            </Pressable>
          );
        })}
      </ScrollView>
    </View>
  );
}

function formatHour(h: number): string {
  return `${String(h).padStart(2, "0")}:00`;
}

const styles = StyleSheet.create({
  content: { gap: space[3], paddingBottom: space[8] },
  toggleRow: { flexDirection: "row", alignItems: "center", gap: space[2] },
  hourRow: { gap: space[1], paddingVertical: space[1] },
  hourPill: {
    paddingHorizontal: space[3],
    paddingVertical: space[1],
    borderRadius: 999,
    backgroundColor: surface.sunken,
    borderWidth: 1,
    borderColor: surface.border,
  },
  hourPillSelected: {
    backgroundColor: palette.coral,
    borderColor: palette.coralDeep,
  },
});
