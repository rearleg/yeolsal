// Story 7.3 — mutation wrapper UI components are allowed to call for the
// Final-3 poster share. Mirrors the useKakaoShare (Story 6.2 AC1) shape so
// the share-flow surface stays consistent across invite-share and
// poster-share entries.

import { useMutation } from "@tanstack/react-query";
import { sendPosterShare, type PosterShareInput } from "../../kakaoShare";
import { captureEvent } from "../../analytics";

export function useFinalThreePosterShare() {
  return useMutation<void, Error, PosterShareInput>({
    mutationFn: sendPosterShare,
    // Analytics — Final-3 share-rate funnel (PRD §2.3 #5 visual-falsification
    // trigger): share_tapped on intent, share_completed once the Kakao share
    // resolves. The gap between the two is the drop-off the KPI measures.
    onMutate: ({ poster }) => {
      captureEvent("final_three.share_tapped", {
        roomId: poster.roomId,
        yearMonth: poster.yearMonth,
      });
    },
    onSuccess: (_data, { poster, survivorCount }) => {
      captureEvent("final_three.share_completed", {
        roomId: poster.roomId,
        yearMonth: poster.yearMonth,
        survivorCount,
      });
    },
  });
}
