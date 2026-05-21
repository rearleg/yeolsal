// Story 3.2 AC7 — daily-entry footer caption that surfaces the 7-day
// "your friend revived you N days ago" echo. Caption-toned, muted ink,
// disappears on day 7 (BE query enforces the window).

import { useFriendGiftReceipts } from "../../lib/query/hooks/friendGift";
import { palette } from "../../theme/tokens";
import { Text } from "../ui/Text";

interface SevenDayFootnoteProps {
  roomId: number;
}

export function SevenDayFootnote({ roomId }: SevenDayFootnoteProps) {
  const { data } = useFriendGiftReceipts();
  const receipt = data?.find((entry) => entry.roomId === roomId);
  if (receipt == null) return null;

  const copy = `${receipt.donorNickname}가 너를 살린 지 ${receipt.daysSinceRevival}일째`;
  return (
    <Text
      variant="caption"
      color={palette.inkMute}
      accessibilityLabel={copy}
    >
      {copy}
    </Text>
  );
}
