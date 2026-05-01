import { useFocusEffect } from "expo-router";
import { useCallback, useState } from "react";
import { ActivityIndicator, Alert, ScrollView, StyleSheet } from "react-native";
import { type ApiEnvelope, apiRequest } from "../src/api/client";
import type { DailyFeedItem } from "../src/api/types";
import { useRequireAuth } from "../src/auth/useRequireAuth";
import { Screen } from "../src/components/Screen";
import { GoalCard } from "../src/components/today/GoalCard";
import { ReflectionCard } from "../src/components/today/ReflectionCard";
import { TodayChips } from "../src/components/today/TodayChips";
import { TodoList } from "../src/components/today/TodoList";
import { useTodayQuery } from "../src/lib/query/hooks/today";
import { entryDateOf } from "../src/lib/calendar";
import { space } from "../src/theme/spacing";
import { palette } from "../src/theme/tokens";

export default function TodayScreen() {
  const auth = useRequireAuth();
  const today = useTodayQuery();
  const [feed, setFeed] = useState<DailyFeedItem[]>([]);

  useFocusEffect(
    useCallback(() => {
      if (auth.loading || !auth.user) return;
      const date = entryDateOf();
      apiRequest<ApiEnvelope<DailyFeedItem[]>>(`/feed/daily?date=${date}`)
        .then((r) => setFeed(r.data))
        .catch((error: unknown) => {
          Alert.alert(
            "친구 피드",
            error instanceof Error ? error.message : "피드를 불러오지 못했습니다.",
          );
        });
    }, [auth.loading, auth.user]),
  );

  const entry = today.data ?? null;
  return (
    <Screen title="오늘">
      <ScrollView contentContainerStyle={styles.content} showsVerticalScrollIndicator={false}>
        {today.isLoading ? <ActivityIndicator color={palette.coralDeep} /> : null}
        <TodayChips items={feed} />
        <GoalCard entry={entry} />
        <TodoList entry={entry} />
        <ReflectionCard entry={entry} />
      </ScrollView>
    </Screen>
  );
}

const styles = StyleSheet.create({
  content: { gap: space[3], paddingBottom: space[8] },
});
