import type { DailyFeedItem } from "../../api/types";
import { EmptyState } from "../ui/EmptyState";
import { TodayChips } from "./TodayChips";

interface Props {
  items: DailyFeedItem[];
}

/**
 * Friends page of the Today pager. Reuses {@link TodayChips} for the
 * existing chip layout and only owns the empty-state surface so the
 * pager stays free of business logic.
 */
export function FriendsTodayCard({ items }: Props) {
  if (items.length === 0) {
    return (
      <EmptyState
        title="아직 친구가 없어요"
        description="친구 요청을 보내고 함께 기록해봐요."
      />
    );
  }
  return <TodayChips items={items} />;
}
