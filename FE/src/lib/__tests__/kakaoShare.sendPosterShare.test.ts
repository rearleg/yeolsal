// Story 7.3 AC5 + AC6 + AC10 — sendPosterShare wrapper.
// Asserts:
//   - Happy path: SDK call payload byte-matches the locked brand-voice copy.
//   - SDK rejection → re-throws + WARN-level breadcrumb (Sentry).
//   - poster.pngUrl == null → throws synchronously, NO SDK call.
//   - description substitution byte-match for survivorCount=5.
//   - buttons[0].title byte-equals Story 6.2's "같이 살아남자".

import { shareFeedTemplate } from "@react-native-kakao/share";

jest.mock("../sentry", () => ({
  addBreadcrumb: jest.fn(),
}));

import { addBreadcrumb } from "../sentry";
import {
  sendPosterShare,
  type PosterShareInput,
} from "../kakaoShare";
import type { FinalThreePosterDto } from "../../api/posters";

const mockShare = shareFeedTemplate as jest.MockedFunction<
  typeof shareFeedTemplate
>;
const mockBreadcrumb = addBreadcrumb as jest.MockedFunction<typeof addBreadcrumb>;

function sampleInput(overrides: Partial<PosterShareInput> = {}): PosterShareInput {
  const poster: FinalThreePosterDto = overrides.poster ?? {
    roomId: 42,
    yearMonth: "2026-05",
    svgText: "<svg viewBox=\"0 0 800 420\"/>",
    pngUrl: "https://cdn.test/posters/42-2026-05.png",
    generatedAt: "2026-06-01T06:30:00Z",
  };
  return {
    poster,
    invite: overrides.invite ?? {
      code: "A7K9PXMQ",
      kakaoShareUrl: "https://yeolsal.app/join?code=A7K9PXMQ",
    },
    roomName: overrides.roomName ?? "기본 방",
    survivorCount: overrides.survivorCount ?? 5,
  };
}

describe("sendPosterShare", () => {
  beforeEach(() => {
    mockShare.mockReset();
    mockShare.mockResolvedValue(undefined);
    mockBreadcrumb.mockReset();
  });

  it("forwards a Feed-template payload with the locked brand-voice phrases (AC5 + AC6)", async () => {
    await sendPosterShare(sampleInput());

    expect(mockShare).toHaveBeenCalledTimes(1);
    const arg = mockShare.mock.calls[0][0];
    expect(arg.template.content.title).toBe("기본 방");
    expect(arg.template.content.description).toBe(
      "이번 달, 우리 살아남았어. 5명이 함께 끝까지 갔어요.",
    );
    expect(arg.template.content.imageUrl).toBe(
      "https://cdn.test/posters/42-2026-05.png",
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

  it("re-throws SDK rejection + records a WARN-level Sentry breadcrumb", async () => {
    const cause = new Error("network down");
    mockShare.mockRejectedValueOnce(cause);

    await expect(sendPosterShare(sampleInput())).rejects.toBe(cause);

    expect(mockBreadcrumb).toHaveBeenCalledTimes(1);
    expect(mockBreadcrumb).toHaveBeenCalledWith(
      expect.objectContaining({
        category: "kakao-share",
        level: "warning",
        message: expect.stringMatching(/poster/i),
        data: { errorMessage: "network down" },
      }),
    );
  });

  it("throws synchronously when poster.pngUrl is null and never calls the SDK", async () => {
    const input = sampleInput({
      poster: {
        roomId: 42,
        yearMonth: "2026-05",
        svgText: "<svg/>",
        pngUrl: null,
        generatedAt: "2026-06-01T06:30:00Z",
      },
    });
    await expect(sendPosterShare(input)).rejects.toThrow();
    expect(mockShare).not.toHaveBeenCalled();
  });

  it("substitutes survivorCount into the description string", async () => {
    await sendPosterShare(sampleInput({ survivorCount: 1 }));
    const arg = mockShare.mock.calls[0][0];
    expect(arg.template.content.description).toBe(
      "이번 달, 우리 살아남았어. 1명이 함께 끝까지 갔어요.",
    );
  });

  it("reuses Story 6.2's locked button title 같이 살아남자 (regression guard)", async () => {
    await sendPosterShare(sampleInput());
    const arg = mockShare.mock.calls[0][0];
    expect(arg.template.buttons?.[0]?.title).toBe("같이 살아남자");
  });
});
