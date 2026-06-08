// Story 7.3 — mutation wrapper UI components are allowed to call for the
// Final-3 poster share. Mirrors the useKakaoShare (Story 6.2 AC1) shape so
// the share-flow surface stays consistent across invite-share and
// poster-share entries.

import { useMutation } from "@tanstack/react-query";
import { sendPosterShare, type PosterShareInput } from "../../kakaoShare";

export function useFinalThreePosterShare() {
  return useMutation<void, Error, PosterShareInput>({
    mutationFn: sendPosterShare,
  });
}
