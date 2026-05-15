import { ActivityIndicator, ScrollView, StyleSheet } from "react-native";
import { useQueryClient } from "@tanstack/react-query";
import { useRequireAuth } from "../../src/auth/useRequireAuth";
import { Screen } from "../../src/components/Screen";
import { Text } from "../../src/components/ui/Text";
import { GoalCard } from "../../src/components/today/GoalCard";
import { ReflectionCard } from "../../src/components/today/ReflectionCard";
import { TodayChips } from "../../src/components/today/TodayChips";
import { TodayHeader } from "../../src/components/today/TodayHeader";
import { TodoList } from "../../src/components/today/TodoList";
import { WalletPreview } from "../../src/components/survival/WalletPreview";
import { TODAY_TAGLINE } from "../../src/components/welcome";
import { useAndroidBack } from "../../src/hooks/useAndroidBack";
import { useTodayQuery } from "../../src/lib/query/hooks/today";
import { useFeedQuery } from "../../src/lib/query/hooks/feed";
import { useIsSpectatorEverywhere } from "../../src/lib/query/hooks/survival";
import { entryDateOf, fromIso } from "../../src/lib/calendar";
import { space } from "../../src/theme/spacing";
import { palette } from "../../src/theme/tokens";

export default function TodayScreen() {
  useRequireAuth();
  useAndroidBack({ confirmExitOnRoot: true });
  const today = useTodayQuery();
  const feed = useFeedQuery(entryDateOf());
  const isSpectatorEverywhere = useIsSpectatorEverywhere();
  const qc = useQueryClient();

  const entry = today.data ?? null;
  // Drive the header off the *server's* entry date when we have one, so the
  // visible label always matches the data the user is editing — including the
  // narrow window between 00:00 and 06:00 local where the entry-date boundary
  // pulls back to "yesterday".
  const headerDate = entry ? fromIso(entry.date) : fromIso(entryDateOf());

  // Story 1.6 AC6 — render the warm-tone tagline when the J0 leader tapped
  // CTA-B on the WelcomeWindow AND today's entry is still fresh (no goal yet).
  // The flag is keyed per-room but any single match flips the surface, since
  // the leader may be in J0 for at least one of their rooms.
  const hasSoloLeaderFlag = qc
    .getQueriesData<boolean>({ predicate: (q) => q.queryKey[0] === "soloLeaderTagline" })
    .some(([, value]) => value === true);
  const isFirstEntryVibe = entry == null || !entry.goal || entry.goal.trim().length === 0;
  const showSoloLeaderTagline = hasSoloLeaderFlag && isFirstEntryVibe;
  return (
    <Screen>
      <TodayHeader date={headerDate} />
      <ScrollView contentContainerStyle={styles.content} showsVerticalScrollIndicator={false}>
        {isSpectatorEverywhere ? <WalletPreview /> : null}
        {today.isLoading ? <ActivityIndicator color={palette.coralDeep} /> : null}
        {showSoloLeaderTagline ? (
          <Text testID="today-solo-leader-tagline" variant="body">
            {TODAY_TAGLINE}
          </Text>
        ) : null}
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
