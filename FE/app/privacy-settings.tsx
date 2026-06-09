// Story 8.5 AC9 — Settings → Privacy → "사용 통계 공유" toggle. This
// page is the REVOCATION surface (Onboarding S5 / Story 8.1 is the
// primary prompt). Mirrors the `notification-settings.tsx` ToggleRow
// shape byte-for-byte (the View + Text + Switch combo at lines 160–164
// of notification-settings.tsx).
//
// Optimistic UI: the visual toggle flips immediately on tap and the
// SecureStore write happens fire-and-forget. `setAnalyticsConsent`
// already swallows write failures (analyticsConsent.ts), so any failure
// is silent in dev mode and the runtime SDK state still reflects user
// intent.

import { useEffect, useState } from "react";
import { ScrollView, StyleSheet, Switch, View } from "react-native";
import { useRequireAuth } from "../src/auth/useRequireAuth";
import { Screen } from "../src/components/Screen";
import { Card } from "../src/components/ui/Card";
import { Text } from "../src/components/ui/Text";
import {
  type AnalyticsConsent,
  getAnalyticsConsent,
  setAnalyticsConsent,
} from "../src/lib/analyticsConsent";
import { space } from "../src/theme/spacing";
import { palette } from "../src/theme/tokens";

const TOGGLE_LABEL = "사용 통계 공유";
const TOGGLE_HELPER = "개인을 직접 식별하지 않는 앱 이용 통계를 수집합니다.";

export default function PrivacySettingsScreen() {
  useRequireAuth();
  const [consent, setConsentState] = useState<AnalyticsConsent | null>(null);
  const [loaded, setLoaded] = useState(false);

  useEffect(() => {
    let cancelled = false;
    getAnalyticsConsent().then((value) => {
      if (!cancelled) {
        setConsentState(value);
        setLoaded(true);
      }
    });
    return () => {
      cancelled = true;
    };
  }, []);

  function handleToggle(next: boolean) {
    const decision: AnalyticsConsent = next ? "opt_in" : "opt_out";
    setConsentState(decision);
    setAnalyticsConsent(decision).catch(() => {
      // SecureStore failure path — runtime SDK state still flipped
      // inside setAnalyticsConsent. UI stays at the user's intent.
    });
  }

  return (
    <Screen title="개인정보 설정">
      <ScrollView contentContainerStyle={styles.content}>
        <Card tone="raised" size="lg">
          <Text variant="h2">개인정보</Text>
          <Text variant="bodySmall" color={palette.inkMute} style={{ marginTop: space[1] }}>
            앱이 어떤 정보를 어떻게 다루는지 설정해요.
          </Text>
          <View style={styles.toggleRow}>
            <View style={{ flex: 1 }}>
              <Text variant="bodyStrong">{TOGGLE_LABEL}</Text>
              <Text variant="caption" color={palette.inkMute} style={{ marginTop: space[1] }}>
                {TOGGLE_HELPER}
              </Text>
            </View>
            <Switch
              value={consent === "opt_in"}
              disabled={!loaded}
              onValueChange={handleToggle}
              accessibilityLabel={TOGGLE_LABEL}
              thumbColor={palette.paper}
              trackColor={{ false: palette.surfaceContrast, true: palette.coral }}
            />
          </View>
        </Card>
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  content: { gap: space[3], paddingBottom: space[8] },
  toggleRow: {
    marginTop: space[3],
    flexDirection: "row",
    alignItems: "center",
    gap: space[3],
  },
});
