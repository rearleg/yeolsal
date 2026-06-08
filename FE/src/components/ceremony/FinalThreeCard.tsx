// Story 7.3 FE-7 / AC1 + AC4 + AC5 + AC6 + AC7 — Home tab Final-3 card.
//
// Surfaces the BE-rendered Editorial poster (Story 7.1) for each room the
// viewer is currently ACTIVE in, with two share entries:
//   1. "KakaoTalk으로 공유" — uses sendPosterShare (Story 7.3) + the room's
//      active invite (Story 6.1) to ship a Kakao Default Feed card.
//   2. "다른 앱으로 공유" — OS-native share sheet via Share.share with a
//      brand-voice-locked Korean fallback string.
//
// Privacy stance (AC4 + Trap #4): the card is gated FE-only by the viewer's
// current ACTIVE status. The poster body is already member-gated by the
// Story 7.1 REST endpoint; the FE gate is a UX dignity guard, not a
// privacy boundary.

import { useCallback } from "react";
import { Share, StyleSheet, View } from "react-native";
import { SvgXml } from "react-native-svg";
import { Button } from "../ui/Button";
import { Text } from "../ui/Text";
import { palette } from "../../theme/tokens";
import { radius, space } from "../../theme/spacing";
import { useFinalThreePoster } from "../../lib/query/hooks/useFinalThreePoster";
import { useFinalThreePosterShare } from "../../lib/query/hooks/useFinalThreePosterShare";
import { useCurrentRoomSurvivalState } from "../../lib/query/hooks/survival";
import {
  useCreateInvite,
  useRoomMembersQuery,
  useRoomsQuery,
} from "../../lib/query/hooks/rooms";
import { toast } from "../../lib/toast";

interface FinalThreeCardProps {
  roomId: number;
  yearMonth: string;
}

// 800×420 matches Story 7.1 SvgRenderer + Story 6.1 PngRasterizer lock so
// the card never has dead space or clipping.
const POSTER_ASPECT_RATIO = 800 / 420;

const KAKAO_FALLBACK_TOAST =
  "KakaoTalk 공유가 안 돼요. 다른 방법으로 공유해주세요.";

export function FinalThreeCard({ roomId, yearMonth }: FinalThreeCardProps) {
  const poster = useFinalThreePoster(roomId, yearMonth);
  const myStatus = useCurrentRoomSurvivalState(roomId);
  const rooms = useRoomsQuery();
  const members = useRoomMembersQuery(roomId);
  const createInvite = useCreateInvite();
  const posterShare = useFinalThreePosterShare();

  const roomName = rooms.data?.find((r) => r.id === roomId)?.name ?? "";
  const survivorCount =
    (members.data ?? []).filter((m) => m.survivalStatus === "ACTIVE").length;

  const handleShareGeneric = useCallback(async () => {
    try {
      await Share.share({
        message: `이번 달, 우리 ${survivorCount}명이 함께 살아남았어요. (열살)`,
      });
    } catch {
      // user dismissed; nothing to do.
    }
  }, [survivorCount]);

  const handleShareKakao = useCallback(async () => {
    if (poster.data == null) return;
    if (poster.data.pngUrl == null) {
      await handleShareGeneric();
      return;
    }
    try {
      const invite = await createInvite.mutateAsync(roomId);
      posterShare.mutate(
        {
          poster: poster.data,
          invite: { code: invite.code, kakaoShareUrl: invite.kakaoShareUrl },
          roomName,
          survivorCount,
        },
        {
          onError: () => {
            toast.info(KAKAO_FALLBACK_TOAST);
            void handleShareGeneric();
          },
        },
      );
    } catch {
      toast.info(KAKAO_FALLBACK_TOAST);
      await handleShareGeneric();
    }
  }, [
    createInvite,
    handleShareGeneric,
    poster.data,
    posterShare,
    roomId,
    roomName,
    survivorCount,
  ]);

  // AC4 — FE-only privacy gate. Hide unless viewer is currently ACTIVE in
  // this room. A user who transitioned to RED since month-end never sees
  // the marketing asset.
  if (myStatus?.status !== "ACTIVE") return null;
  // Trap #11 — no skeleton during initial fetch.
  if (poster.isLoading) return null;
  // AC2 — 404 self-hide (data === null).
  if (poster.data == null) return null;

  return (
    <View
      accessible
      accessibilityRole="image"
      accessibilityLabel="이번 달 살아남은 멤버 포스터"
      testID={`final-three-card-${roomId}`}
      style={styles.card}
    >
      <Text variant="h3" style={styles.header}>
        이번 달, 우리 살아남았어
      </Text>
      <View style={[styles.posterFrame, { aspectRatio: POSTER_ASPECT_RATIO }]}>
        <SvgXml
          xml={poster.data.svgText}
          width="100%"
          height="100%"
          testID={`final-three-svg-${roomId}`}
        />
      </View>
      <View style={styles.actions}>
        <Button
          label="KakaoTalk으로 공유"
          tone="primary"
          size="md"
          fullWidth
          // The handler defensively falls back to Share.share when
          // poster.pngUrl is null (Kakao card thumbnails are PNG-only);
          // leaving the button enabled keeps the AC10-7 share-completion
          // path measurable instead of dead-end disabled state.
          onPress={handleShareKakao}
          accessibilityLabel="포스터를 KakaoTalk으로 공유하기"
          testID={`final-three-share-kakao-${roomId}`}
        />
        <Button
          label="다른 앱으로 공유"
          tone="secondary"
          size="md"
          fullWidth
          onPress={handleShareGeneric}
          testID={`final-three-share-generic-${roomId}`}
        />
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  card: {
    backgroundColor: palette.surface,
    borderRadius: radius.lg,
    padding: space[4],
    gap: space[3],
  },
  header: {
    color: palette.ink,
  },
  posterFrame: {
    width: "100%",
    overflow: "hidden",
    borderRadius: radius.md,
    backgroundColor: palette.surfaceSunken,
  },
  actions: {
    gap: space[2],
  },
});
