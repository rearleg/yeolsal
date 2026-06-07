import { shareFeedTemplate } from "@react-native-kakao/share";
import { addBreadcrumb } from "./sentry";

export interface ShareInput {
  invite: {
    code: string;
    kakaoShareUrl: string;
    previewCardImageUrl: string;
  };
  roomName: string;
  memberCount: number;
}

/**
 * Single entry point for the KakaoTalk share flow. {@link useKakaoShare}
 * is the only intended caller — UI components must not invoke the
 * Kakao SDK directly so the Default Feed template payload, the
 * brand-voice phrases, and the Sentry breadcrumb stay locked in one
 * place (Story 6.2 AC1 + AC5 + AC15).
 *
 * <p>Implementation note: epics:849 named the SDK call
 * {@code kakaoShare.sendCustomFeed(...)} and the story dev-spec named
 * it {@code KakaoShareLink.sendDefault(...)}; the {@code @react-native-kakao/share}
 * v2 API moved to {@code shareFeedTemplate({ template })}. Wire-level
 * rename only — the {@link KakaoFeedTemplate} payload (content, buttons)
 * is byte-identical, so the user-visible card is unchanged. Recorded
 * as a non-blocker doc follow-up in AC14.
 *
 * Errors are propagated to the mutation so {@code useKakaoShare}'s
 * onError can decide the user-visible fallback (plain {@code Share.share},
 * see Story 6.2 AC4). Per the architecture's lexicon discipline (§4.15)
 * the {@code description} and {@code buttons[0].title} phrases are
 * USE-only — `함께`, `살아남`, `같이` — and never contain AVOID terms.
 */
export async function sendInviteShare({
  invite,
  roomName,
  memberCount,
}: ShareInput): Promise<void> {
  try {
    await shareFeedTemplate({
      template: {
        content: {
          title: roomName,
          description: `${memberCount}명이 함께 살아남는 중`,
          imageUrl: invite.previewCardImageUrl,
          link: {
            mobileWebUrl: invite.kakaoShareUrl,
            webUrl: invite.kakaoShareUrl,
          },
        },
        buttons: [
          {
            title: "같이 살아남자",
            link: {
              mobileWebUrl: invite.kakaoShareUrl,
              webUrl: invite.kakaoShareUrl,
            },
          },
        ],
      },
    });
  } catch (err) {
    // Story 6.2 AC15 — light-touch observability. WARN-level breadcrumb only
    // (the user-visible fallback in useKakaoShare.onError keeps share working,
    // so a full captureException would over-alert ops). PII-free: only the
    // error message string is forwarded.
    addBreadcrumb({
      category: "kakao-share",
      level: "warning",
      message: "SDK call failed",
      data: {
        errorMessage: err instanceof Error ? err.message : String(err),
      },
    });
    throw err;
  }
}
