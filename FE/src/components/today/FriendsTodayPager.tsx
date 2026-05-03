import AsyncStorage from "@react-native-async-storage/async-storage";
import { useEffect, useRef, useState } from "react";
import { Pressable, StyleSheet, View } from "react-native";
import PagerView from "react-native-pager-view";
import type { DailyFeedItem } from "../../api/types";
import { toast } from "../../lib/toast";
import { palette, surface } from "../../theme/tokens";
import { space } from "../../theme/spacing";
import { FriendsTodayCard } from "./FriendsTodayCard";
import { GroupTodayCard } from "./GroupTodayCard";

// Bumped if/when the page order or the gesture changes — old keys keep
// returning users out of the toast loop.
const ONBOARDING_KEY = "today_pager_onboarding_seen_v1";
const ONBOARDING_DELAY_MS = 800;

interface Props {
  /** Friend daily feed items already resolved by the parent. */
  friends: DailyFeedItem[];
  /** Entry-date the parent is rendering (yyyy-MM-dd) — handed down to GroupTodayCard. */
  date: string;
}

const PAGES = [
  { key: "group", label: "그룹" },
  { key: "friends", label: "친구" },
] as const;

export function FriendsTodayPager({ friends, date }: Props) {
  const pagerRef = useRef<PagerView>(null);
  const [page, setPage] = useState<number>(0);

  // Onboarding hint: explain the swipe gesture exactly once per device.
  // The default page is the group view; first-time users would otherwise
  // not realise the friends feed still exists one swipe over.
  useEffect(() => {
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    AsyncStorage.getItem(ONBOARDING_KEY)
      .then((seen) => {
        if (cancelled || seen === "1") return;
        timer = setTimeout(() => {
          if (cancelled) return;
          toast.info("← → 슬라이드해서 친구들의 오늘로 전환할 수 있어요");
          AsyncStorage.setItem(ONBOARDING_KEY, "1").catch(() => {
            // Silent: failing to persist the flag just means the toast
            // shows again next launch, which is acceptable.
          });
        }, ONBOARDING_DELAY_MS);
      })
      .catch(() => {
        // Storage error: skip the toast rather than annoy on every cold start.
      });
    return () => {
      cancelled = true;
      if (timer) clearTimeout(timer);
    };
  }, []);

  return (
    <View>
      <PagerView
        ref={pagerRef}
        style={styles.pager}
        initialPage={0}
        onPageSelected={(e) => setPage(e.nativeEvent.position)}
      >
        <View key="group" collapsable={false}>
          <GroupTodayCard date={date} />
        </View>
        <View key="friends" collapsable={false}>
          <FriendsTodayCard items={friends} />
        </View>
      </PagerView>
      <View style={styles.indicator} accessibilityRole="tablist">
        {PAGES.map((p, i) => {
          const active = page === i;
          return (
            <Pressable
              key={p.key}
              accessibilityRole="tab"
              accessibilityLabel={`${p.label} 페이지`}
              accessibilityState={{ selected: active }}
              onPress={() => {
                pagerRef.current?.setPage(i);
                setPage(i);
              }}
              hitSlop={8}
              style={[styles.dot, active && styles.dotActive]}
            />
          );
        })}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  // PagerView measures children once, so a fixed height is required. The
  // tallest variant is GroupTodayCard's empty-state (title + description +
  // two stacked Buttons ≈ 240px). Anything below that clips the action
  // buttons for users with zero rooms — so we hold a 260 floor here and
  // revisit if either page grows again.
  pager: { height: 260 },
  indicator: {
    flexDirection: "row",
    justifyContent: "center",
    gap: space[1],
    marginTop: space[2],
  },
  dot: {
    width: 8,
    height: 8,
    borderRadius: 4,
    backgroundColor: surface.border,
  },
  dotActive: {
    backgroundColor: palette.coralDeep,
    width: 18,
  },
});
