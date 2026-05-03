import { ActivityIndicator, ScrollView, StyleSheet } from "react-native";
import { useRequireAuth } from "../../src/auth/useRequireAuth";
import { Screen } from "../../src/components/Screen";
import { GoalCard } from "../../src/components/today/GoalCard";
import { ReflectionCard } from "../../src/components/today/ReflectionCard";
import { TodayChips } from "../../src/components/today/TodayChips";
import { TodayHeader } from "../../src/components/today/TodayHeader";
import { TodoList } from "../../src/components/today/TodoList";
import { useAndroidBack } from "../../src/hooks/useAndroidBack";
import { useTodayQuery } from "../../src/lib/query/hooks/today";
import { useFeedQuery } from "../../src/lib/query/hooks/feed";
import { entryDateOf, fromIso } from "../../src/lib/calendar";
import { space } from "../../src/theme/spacing";
import { palette } from "../../src/theme/tokens";

export default function TodayScreen() {
  useRequireAuth();
  useAndroidBack({ confirmExitOnRoot: true });
  const today = useTodayQuery();
  const feed = useFeedQuery(entryDateOf());

  const entry = today.data ?? null;
  // Drive the header off the *server's* entry date when we have one, so the
  // visible label always matches the data the user is editing — including the
  // narrow window between 00:00 and 06:00 local where the entry-date boundary
  // pulls back to "yesterday".
  const headerDate = entry ? fromIso(entry.date) : fromIso(entryDateOf());
  return (
    <Screen>
      <TodayHeader date={headerDate} />
      <ScrollView contentContainerStyle={styles.content} showsVerticalScrollIndicator={false}>
        {today.isLoading ? <ActivityIndicator color={palette.coralDeep} /> : null}
        <TodayChips items={feed.data ?? []} />
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
