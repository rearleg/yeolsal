import { shareFeedTemplate } from "@react-native-kakao/share";
import { addBreadcrumb } from "./sentry";
import type { FinalThreePosterDto } from "../api/posters";

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

/**
 * Story 7.3 — second Kakao share template alongside {@link sendInviteShare}
 * (Story 6.2). Same SDK call, same template type ("feed"), different
 * {@code imageUrl} (poster.pngUrl instead of invite.previewCardImageUrl)
 * and different description / button copy. Single-wrapper rule preserved
 * (project-context.md "all data fetching / single STOMP / single Kakao
 * wrapper"). UI components must invoke via {@code useFinalThreePosterShare},
 * never call {@code shareFeedTemplate} directly.
 *
 * <p>PNG-only {@code imageUrl} — the inline SVG body ({@code poster.svgText})
 * is never sent to KakaoTalk's fetcher (Kakao card thumbnails are PNG/JPEG
 * only per the SDK docs). When {@code poster.pngUrl} is null (transcode
 * failed), this throws synchronously so the caller can suppress the Kakao
 * button and fall back to the OS generic share (Story 7.3 AC7).
 *
 * <p>Brand-voice phrase set (locked per AC6): description uses
 * {@code "이번 달, 우리 살아남았어"} + per-room survivor count; button reuses
 * Story 6.2's locked {@code "같이 살아남자"} so the Korean invitation-tone
 * lexicon ({@code 함께}, {@code 같이}, {@code 우리}, {@code 살아남})
 * accumulates across surfaces.
 */
export interface PosterShareInput {
  poster: FinalThreePosterDto;
  invite: {
    code: string;
    kakaoShareUrl: string;
  };
  roomName: string;
  survivorCount: number;
}

export async function sendPosterShare({
  poster,
  invite,
  roomName,
  survivorCount,
}: PosterShareInput): Promise<void> {
  if (poster.pngUrl == null) {
    throw new Error("Poster PNG unavailable — Kakao share suppressed.");
  }
  try {
    await shareFeedTemplate({
      template: {
        content: {
          title: roomName,
          description: `이번 달, 우리 살아남았어. ${survivorCount}명이 함께 끝까지 갔어요.`,
          imageUrl: poster.pngUrl,
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
    addBreadcrumb({
      category: "kakao-share",
      level: "warning",
      message: "Poster share SDK call failed",
      data: {
        errorMessage: err instanceof Error ? err.message : String(err),
      },
    });
    throw err;
  }
}
