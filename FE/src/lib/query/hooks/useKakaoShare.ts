import { useMutation } from "@tanstack/react-query";
import { sendInviteShare, type ShareInput } from "../../kakaoShare";

/**
 * Story 6.2 AC1 — the only mutation wrapper UI components are allowed to
 * call for KakaoTalk share. The TanStack mutation gives callers
 * {@code onSuccess} / {@code onError} hooks without each component having
 * to manage its own loading state, and centralises the fallback decision
 * (Story 6.2 AC4) at the call site rather than inside the SDK wrapper.
 */
export function useKakaoShare() {
  return useMutation<void, Error, ShareInput>({
    mutationFn: sendInviteShare,
  });
}
