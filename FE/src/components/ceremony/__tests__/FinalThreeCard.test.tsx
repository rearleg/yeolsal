// Story 7.3 AC1 + AC4 + AC5 + AC6 + AC7 + AC10 — FinalThreeCard component tests.
//
// Eight cases per spec:
//   1. AC4 gate — viewer not ACTIVE in this room → renders nothing.
//   2. AC2 — poster.data === null (404 self-hide) → renders nothing.
//   3. Trap #11 — isLoading=true → renders nothing (no skeleton).
//   4. Success — renders header, SVG container, and the two share buttons.
//   5. AC7 — accessibilityLabel byte-matches the locked phrase.
//   6. Kakao tap — createInvite resolves THEN posterShare.mutate fires with
//      the exact payload (poster, invite, roomName, survivorCount).
//   7. Kakao tap when poster.pngUrl is null → straight to OS Share.share,
//      no SDK call.
//   8. Generic share tap → Share.share with the locked Korean fallback string.

import { fireEvent, render, screen, waitFor } from "@testing-library/react-native";
import { Share } from "react-native";

jest.mock("../../../lib/query/hooks/useFinalThreePoster", () => ({
  useFinalThreePoster: jest.fn(),
}));
jest.mock("../../../lib/query/hooks/useFinalThreePosterShare", () => ({
  useFinalThreePosterShare: jest.fn(),
}));
jest.mock("../../../lib/query/hooks/survival", () => ({
  useCurrentRoomSurvivalState: jest.fn(),
}));
jest.mock("../../../lib/query/hooks/rooms", () => ({
  useRoomsQuery: jest.fn(),
  useRoomMembersQuery: jest.fn(),
  useCreateInvite: jest.fn(),
}));
jest.mock("react-native-svg", () => {
  const { View } = jest.requireActual("react-native");
  return {
    __esModule: true,
    SvgXml: (props: { xml: string; testID?: string }) => (
      <View testID={props.testID ?? "mock-svg-xml"} />
    ),
  };
});

import { FinalThreeCard } from "../FinalThreeCard";
import { useFinalThreePoster } from "../../../lib/query/hooks/useFinalThreePoster";
import { useFinalThreePosterShare } from "../../../lib/query/hooks/useFinalThreePosterShare";
import { useCurrentRoomSurvivalState } from "../../../lib/query/hooks/survival";
import {
  useCreateInvite,
  useRoomMembersQuery,
  useRoomsQuery,
} from "../../../lib/query/hooks/rooms";
import type { FinalThreePosterDto } from "../../../api/posters";

const mockUseFinalThreePoster =
  useFinalThreePoster as jest.MockedFunction<typeof useFinalThreePoster>;
const mockUseFinalThreePosterShare =
  useFinalThreePosterShare as jest.MockedFunction<typeof useFinalThreePosterShare>;
const mockUseCurrentRoomSurvivalState =
  useCurrentRoomSurvivalState as jest.MockedFunction<typeof useCurrentRoomSurvivalState>;
const mockUseRoomsQuery = useRoomsQuery as jest.MockedFunction<typeof useRoomsQuery>;
const mockUseRoomMembersQuery =
  useRoomMembersQuery as jest.MockedFunction<typeof useRoomMembersQuery>;
const mockUseCreateInvite =
  useCreateInvite as jest.MockedFunction<typeof useCreateInvite>;

const ROOM_ID = 42;
const YEAR_MONTH = "2026-05";

const SAMPLE_POSTER: FinalThreePosterDto = {
  roomId: ROOM_ID,
  yearMonth: YEAR_MONTH,
  svgText: "<svg viewBox=\"0 0 800 420\"><text>survivors</text></svg>",
  pngUrl: "https://cdn.test/posters/42-2026-05.png",
  generatedAt: "2026-06-01T06:30:00Z",
};

const SAMPLE_INVITE = {
  id: 1,
  roomId: ROOM_ID,
  code: "A7K9PXMQ",
  expiresAt: null,
  kakaoShareUrl: "https://yeolsal.app/join?code=A7K9PXMQ",
  previewCardImageUrl: "https://api.test/preview-card",
};

function activeMember(userId: number, nickname: string) {
  return {
    userId,
    nickname,
    role: "MEMBER" as const,
    currentMinimum: 5 as const,
    warningCount: 0,
    survivalStatus: "ACTIVE" as const,
  };
}

function setupHappyPath(overrides?: { pngUrl?: string | null }) {
  mockUseCurrentRoomSurvivalState.mockReturnValue({
    roomId: ROOM_ID,
    status: "ACTIVE",
  } as ReturnType<typeof useCurrentRoomSurvivalState>);

  // `pngUrl: null` is a deliberate setup; ?? would coalesce null back to
  // SAMPLE_POSTER.pngUrl. Use `in` to distinguish "missing key" from
  // "explicit null".
  const overrodePngUrl =
    overrides && "pngUrl" in overrides ? overrides.pngUrl ?? null : SAMPLE_POSTER.pngUrl;
  mockUseFinalThreePoster.mockReturnValue({
    data: { ...SAMPLE_POSTER, pngUrl: overrodePngUrl },
    isLoading: false,
    isError: false,
  });

  mockUseRoomsQuery.mockReturnValue({
    data: [
      {
        id: ROOM_ID,
        name: "기본 방",
        ownerId: 1,
        maxMembers: 12,
        minDailyGoalDays: 10,
        createdAt: "2026-04-15T03:14:00Z",
        pendingMaxMembers: null,
        pendingMaxMembersEffectiveFromMonth: null,
      },
    ],
  } as ReturnType<typeof useRoomsQuery>);

  mockUseRoomMembersQuery.mockReturnValue({
    data: [
      activeMember(1, "규민"),
      activeMember(2, "지수"),
      activeMember(3, "민호"),
      activeMember(4, "수아"),
      activeMember(5, "혜진"),
    ],
  } as unknown as ReturnType<typeof useRoomMembersQuery>);

  const mutateAsyncInvite = jest.fn().mockResolvedValue(SAMPLE_INVITE);
  mockUseCreateInvite.mockReturnValue({
    mutateAsync: mutateAsyncInvite,
  } as unknown as ReturnType<typeof useCreateInvite>);

  const mutateShare = jest.fn();
  mockUseFinalThreePosterShare.mockReturnValue({
    mutate: mutateShare,
    isPending: false,
  } as unknown as ReturnType<typeof useFinalThreePosterShare>);

  return { mutateAsyncInvite, mutateShare };
}

describe("FinalThreeCard", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    jest.spyOn(Share, "share").mockResolvedValue({ action: "sharedAction" });
  });

  it("renders nothing when viewer is not ACTIVE in this room (AC4)", () => {
    setupHappyPath();
    mockUseCurrentRoomSurvivalState.mockReturnValueOnce({
      roomId: ROOM_ID,
      status: "RED",
    } as ReturnType<typeof useCurrentRoomSurvivalState>);

    const { toJSON } = render(
      <FinalThreeCard roomId={ROOM_ID} yearMonth={YEAR_MONTH} />,
    );
    expect(toJSON()).toBeNull();
  });

  it("renders nothing when the REST hook returns data === null (404 self-hide)", () => {
    setupHappyPath();
    mockUseFinalThreePoster.mockReturnValueOnce({
      data: null,
      isLoading: false,
      isError: false,
    });

    const { toJSON } = render(
      <FinalThreeCard roomId={ROOM_ID} yearMonth={YEAR_MONTH} />,
    );
    expect(toJSON()).toBeNull();
  });

  it("renders nothing while isLoading is true (no skeleton — Trap #11)", () => {
    setupHappyPath();
    mockUseFinalThreePoster.mockReturnValueOnce({
      data: null,
      isLoading: true,
      isError: false,
    });

    const { toJSON } = render(
      <FinalThreeCard roomId={ROOM_ID} yearMonth={YEAR_MONTH} />,
    );
    expect(toJSON()).toBeNull();
  });

  it("renders header, inline SVG, and both share buttons on success", () => {
    setupHappyPath();
    render(<FinalThreeCard roomId={ROOM_ID} yearMonth={YEAR_MONTH} />);

    expect(screen.getByTestId(`final-three-card-${ROOM_ID}`)).toBeTruthy();
    expect(screen.getByText("이번 달, 우리 살아남았어")).toBeTruthy();
    expect(screen.getByTestId(`final-three-svg-${ROOM_ID}`)).toBeTruthy();
    expect(screen.getByTestId(`final-three-share-kakao-${ROOM_ID}`)).toBeTruthy();
    expect(screen.getByTestId(`final-three-share-generic-${ROOM_ID}`)).toBeTruthy();
  });

  it("uses the locked accessibility label '이번 달 살아남은 멤버 포스터' (AC6/AC7)", () => {
    setupHappyPath();
    render(<FinalThreeCard roomId={ROOM_ID} yearMonth={YEAR_MONTH} />);

    const card = screen.getByTestId(`final-three-card-${ROOM_ID}`);
    expect(card.props.accessibilityLabel).toBe("이번 달 살아남은 멤버 포스터");
  });

  it("KakaoTalk button calls createInvite then posterShare.mutate with exact payload", async () => {
    const { mutateAsyncInvite, mutateShare } = setupHappyPath();
    render(<FinalThreeCard roomId={ROOM_ID} yearMonth={YEAR_MONTH} />);

    fireEvent.press(screen.getByTestId(`final-three-share-kakao-${ROOM_ID}`));

    await waitFor(() => expect(mutateAsyncInvite).toHaveBeenCalledWith(ROOM_ID));
    await waitFor(() => expect(mutateShare).toHaveBeenCalledTimes(1));
    const [payload] = mutateShare.mock.calls[0];
    expect(payload).toMatchObject({
      poster: { yearMonth: YEAR_MONTH, pngUrl: SAMPLE_POSTER.pngUrl },
      invite: { code: "A7K9PXMQ", kakaoShareUrl: SAMPLE_INVITE.kakaoShareUrl },
      roomName: "기본 방",
      survivorCount: 5,
    });
  });

  it("KakaoTalk button falls back straight to Share.share when poster.pngUrl is null", async () => {
    const { mutateShare } = setupHappyPath({ pngUrl: null });
    render(<FinalThreeCard roomId={ROOM_ID} yearMonth={YEAR_MONTH} />);

    fireEvent.press(screen.getByTestId(`final-three-share-kakao-${ROOM_ID}`));

    await waitFor(() => expect(Share.share).toHaveBeenCalledTimes(1));
    expect(mutateShare).not.toHaveBeenCalled();
  });

  it("Generic share button forwards locked Korean fallback text to Share.share", async () => {
    setupHappyPath();
    render(<FinalThreeCard roomId={ROOM_ID} yearMonth={YEAR_MONTH} />);

    fireEvent.press(screen.getByTestId(`final-three-share-generic-${ROOM_ID}`));

    await waitFor(() => expect(Share.share).toHaveBeenCalledTimes(1));
    expect(Share.share).toHaveBeenCalledWith({
      message: "이번 달, 우리 5명이 함께 살아남았어요. (열살)",
    });
  });
});
