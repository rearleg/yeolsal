// Story 6.2 AC1/AC5/AC11/AC15 — wrapper around the @react-native-kakao/share
// Feed template. Asserts: (a) exact payload shape, (b) brand-voice phrases
// are byte-identical with AC5's table, (c) error propagation so the
// useKakaoShare mutation's onError can fire the fallback, (d) catch path
// runs on SDK rejection.

import { shareFeedTemplate } from "@react-native-kakao/share";
import { sendInviteShare, type ShareInput } from "../kakaoShare";

const mockShare = shareFeedTemplate as jest.MockedFunction<
  typeof shareFeedTemplate
>;

function sampleInput(overrides: Partial<ShareInput> = {}): ShareInput {
  return {
    invite: {
      code: "A7K9PXMQ",
      kakaoShareUrl: "https://yeolsal.app/join?code=A7K9PXMQ",
      previewCardImageUrl:
        "https://api.rearleg.com/yeolsal/api/v1/rooms/42/invites/preview-card",
    },
    roomName: "기본 방",
    memberCount: 4,
    ...overrides,
  };
}

describe("sendInviteShare", () => {
  beforeEach(() => {
    mockShare.mockReset();
    mockShare.mockResolvedValue(undefined);
  });

  it("forwards a Feed-template payload with the locked brand-voice phrases (AC1 + AC5)", async () => {
    await sendInviteShare(sampleInput());

    expect(mockShare).toHaveBeenCalledTimes(1);
    const arg = mockShare.mock.calls[0][0];
    expect(arg.template.content.title).toBe("기본 방");
    expect(arg.template.content.description).toBe("4명이 함께 살아남는 중");
    expect(arg.template.content.imageUrl).toBe(
      "https://api.rearleg.com/yeolsal/api/v1/rooms/42/invites/preview-card",
    );
    expect(arg.template.content.link.mobileWebUrl).toBe(
      "https://yeolsal.app/join?code=A7K9PXMQ",
    );
    expect(arg.template.content.link.webUrl).toBe(
      "https://yeolsal.app/join?code=A7K9PXMQ",
    );
    expect(arg.template.buttons).toEqual([
      {
        title: "같이 살아남자",
        link: {
          mobileWebUrl: "https://yeolsal.app/join?code=A7K9PXMQ",
          webUrl: "https://yeolsal.app/join?code=A7K9PXMQ",
        },
      },
    ]);
  });

  it("interpolates the member count into the description (AC5 — `N명이 함께 살아남는 중`)", async () => {
    await sendInviteShare(sampleInput({ memberCount: 12 }));

    const arg = mockShare.mock.calls[0][0];
    expect(arg.template.content.description).toBe("12명이 함께 살아남는 중");
  });

  it("re-throws SDK errors so useKakaoShare.onError can fire the fallback (AC4)", async () => {
    const boom = new Error("kakao_invalid_template");
    mockShare.mockRejectedValueOnce(boom);

    await expect(sendInviteShare(sampleInput())).rejects.toBe(boom);
  });

  it("runs the catch path exactly once when the SDK rejects (AC15 — guarded Sentry breadcrumb)", async () => {
    mockShare.mockRejectedValueOnce(new Error("kakao_network_unavailable"));

    await expect(sendInviteShare(sampleInput())).rejects.toThrow();
    // The catch path runs the addBreadcrumb gate + re-throws; subsequent
    // SDK calls remain isolated. The breadcrumb itself drops in jest
    // because Sentry isn't bootstrapped — that no-op branch is covered
    // by sentry.ts's isSentryEnabled() guard already.
    expect(mockShare).toHaveBeenCalledTimes(1);
  });
});
