# Story 7.3: Home tab Final-3 card with Kakao share

Status: ready-for-dev

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a surviving member of a room,
I want the Home (`(tabs)/today`) tab to render a `FinalThreeCard` for each room where I just survived the prior month, with the server-rendered Editorial poster shown inline plus a 1-tap "KakaoTalk으로 공유" CTA that posts the PNG poster + my room's active invite-code so external readers can join in two taps,
So that the Final-3 ceremony lands as a dignity-tone marketing surface on the morning of month transition — and the Final-3 share-rate KPI (PRD §2.3 #5: Day-30 share-rate ≥ 15% of surviving members) becomes measurable.

## Acceptance Criteria

> 이 스토리는 **Epic 7 Final-3 Monthly Ceremony 의 사용자 진입점** 이다. Story 7.1 (PR #93 merged `455a939` 2026-06-08) 이 server-side SVG renderer + `GET /api/v1/rooms/{id}/posters/{yearMonth}` membership-gated endpoint + V11 (10) `final_three_posters` table + `kakaoshare/PngRasterizer` cross-module reuse 를 ship 했고, Story 7.2 (PR #95 merged `5b12ffb` 2026-06-08) 가 `FinalThreeJob` `@Scheduled(cron="0 30 6 1 * *", zone="Asia/Seoul")` + `MonthlyPosterRenderExecutorConfig` 8-thread pool + `MonthlyPosterReadyPayload` + `RealtimePublisher.publishMonthlyPosterReady` + `/topic/rooms.{id}.posters` STOMP fanout + `SurvivalStateRepository.findRoomIdsWithAtLeastOneActive` pre-filter 를 ship 했다. 본 스토리는 **FE-side consumer surface** — 사용자가 J0 가 끝난 다음 달 06:30 KST 직후 Home tab 을 열면 보게 되는 `FinalThreeCard` + Kakao share UX 를 얹는다. **FE-only — BE 변경 ZERO** (no new endpoint, no new migration, no new exception, no new STOMP topic). 핵심 산출:
> 1. **새 REST wrapper** `FE/src/api/posters.ts` — `getPoster(roomId, yearMonth)` typed wrapper. Architecture §6.2 line 612 명명 lock. PosterDto 모양은 Story 7.1 `BE/src/main/java/com/yeosal/api/ceremony/PosterDto.java` 와 byte-identical (`{roomId, yearMonth, svgText, pngUrl, generatedAt}`).
> 2. **새 도메인 훅** `FE/src/lib/query/hooks/useFinalThreePoster.ts` — TanStack Query + STOMP merge composite. Architecture §6.2 line 620 명명 lock. `useRoomPoints` (Story 4.1) byte-similar mirror: REST primary + `/topic/rooms.{roomId}.posters` 구독 (Story 7.2 가 emit) + reconnect-recovery invalidate + 404 → null fall-through. **NEW**: `qk.finalThreePoster(roomId, yearMonth)` key 1 line append on `keys.ts`.
> 3. **새 컴포넌트** `FE/src/components/ceremony/FinalThreeCard.tsx` — Architecture §6.2 line 642 명명 lock. Per-room card; renders inline SVG via `react-native-svg` `SvgXml` (PNG fallback only for Kakao share imageUrl); brand-voice locked copy; surfaces "KakaoTalk으로 공유" primary CTA + "다른 앱으로 공유" secondary fallback (Story 6.2 AC4 byte-identical fallback shape).
> 4. **새 share wrapper extension** `FE/src/lib/kakaoShare.ts` — append `sendPosterShare(...)` next to existing `sendInviteShare(...)` (Story 6.2). Same `shareFeedTemplate({...})` SDK call, different `templateObject.content.imageUrl` (poster's `pngUrl` instead of invite's `previewCardImageUrl`) + different `description` / `buttons[0].title` (poster brand-voice copy vs invite brand-voice copy). Single-wrapper principle (project-context.md:127) preserved.
> 5. **새 mutation 훅** `FE/src/lib/query/hooks/useFinalThreePosterShare.ts` — `useMutation<void, Error, PosterShareInput>({ mutationFn: sendPosterShare })`. Architecture §6.2 line 643 (PosterShareSheet — folded into FinalThreeCard per Trap #13) 의 share entry. Story 6.2 의 `useKakaoShare` byte-similar mirror.
> 6. **Home tab consumer wiring** `FE/app/(tabs)/today.tsx` — `<FinalThreeCard />` 를 ScrollView 의 top 에 (TodayChips 위) 마운트. `useMeSurvivalQuery()` 의 ACTIVE-status 멤버십을 enumerate 해서 각 ACTIVE room 마다 한 카드 (Trap #2: spectator-everywhere 사용자 제외 자동, AC4 의 privacy gate 와 일치).
> 7. **Invite resolution** — share button 누름 시 active invite 가 없으면 `useCreateInvite` mutation 으로 새로 발급. Story 6.1 의 `RoomInvite` payload (kakaoShareUrl + previewCardImageUrl) 그대로 사용 — 그러나 Kakao card 의 `imageUrl` 만 poster.pngUrl 로 swap (Trap #6).
> 8. **PosterShareSheet 폴드 결정 (deviation from architecture §6.2 line 643):** architecture 는 `PosterShareSheet.tsx` 를 별도 파일로 listed 했지만, 본 스토리는 share 가 단순 (1-tap button + fallback toast, modal-less) 이므로 별도 sheet 파일을 만들지 않고 FinalThreeCard 내부의 onShare 콜백으로 처리. PosterShareSheet.tsx 파일 미생성 결정을 Trap #13 + Architecture-deviation #1 에 기록.
> 9. **Privacy gate FE-only (Trap #4):** 본 스토리는 BE-side eligibility 강화 없이 FE 가 `useCurrentRoomSurvivalState(roomId)?.status === "ACTIVE"` 로 카드 노출을 게이트. 이는 dignity-tone UX gate (PII 누설 X — poster PNG 는 nginx static, 누구나 URL 만 알면 접근 가능). 강한 privacy gate (Architecture §4.14) 가 필요한 surface 가 아님. **Trap #4** 의 결정 근거 참조.
> 10. **시간 윈도우 lock:** card 가 보이는 `yearMonth` = **직전 calendar month KST** = `YearMonth.now(KST).minusMonths(1)`. 단, 본 스토리는 KST 의 day-1-새로운-day boundary 가 아니라 **calendar-month boundary** 사용 (PRD/Epic 의 day-boundary 06:00 KST 는 daily-mission 전용 — Story 5.1 RoomRule.nextMonthKST precedent). 따라서 6월 30일 23:59 KST → card 안 보임. 7월 1일 00:00 KST → 6월 poster card 보임 (poster 06:30 까지 미생성 → 404 → 카드 숨김). 7월 1일 06:30 KST 후 → 카드 노출 (poster 존재).
>
> **NO new migration** (V13 stays latest, Story 5.2 ledge). **NO new BE source** (Story 7.1 endpoint + Story 7.2 STOMP topic + Story 6.2 Kakao SDK 전부 사용). **NO new STOMP topic** (Story 7.2 가 `/topic/rooms.{id}.posters` 이미 reserve, JwtChannelInterceptor regex 이미 `posters` 포함). **NO new push notification** (PRD §3.1 line 305 "이번 달 Final-3 카드가 도착했어요" 는 Story 7.2's MonthlyPosterReady 가 realtime 으로 처리 + Story 8.5 analytics SDK 가 share_completed event 처리 — push 는 별도 라인). **NO new `tokens.json` 변경** (Editorial sub-mode 는 Story 7.1 BE-side codegen 이 이미 SVG 에 embedded). **NO `EXPO_PUBLIC_*` 추가** — Kakao Native App Key 는 Story 6.2 가 이미 wire. **NO `app.config.ts` 변경** — Story 6.2 가 이미 Universal Link + App Link wire. **NO `RealtimePublisher` 또는 `RealtimeEvent` 변경** — Story 7.1 deviation #4 + Story 7.2 AC4 anti-pattern lock per "RealtimeEvent stays open record".

### AC0 — Existing infrastructure inventory (NO REWORK, READ ONLY)

**Given** Stories 7.1 + 7.2 + 6.2 + 1.5 already shipped the dependencies this story consumes
**When** Story 7.3 starts
**Then** the dev agent treats these as **immutable inputs** — do NOT modify, do NOT re-derive:

| Artifact | Path / Location | Use in 7.3 |
|---|---|---|
| `GET /api/v1/rooms/{roomId}/posters/{yearMonth}` | `BE/src/main/java/com/yeosal/api/ceremony/PosterController.java:39` | The REST endpoint this story's `getPoster(...)` fetches. Returns 200 + `ApiResponse<PosterDto>` for the viewer's surviving-month, 403 for non-member, 404 (`POSTER_NOT_FOUND`) for missing poster. NO change. |
| `PosterDto` record | `BE/src/main/java/com/yeosal/api/ceremony/PosterDto.java` | Wire shape `{roomId, yearMonth, svgText, pngUrl, generatedAt}`. FE TypeScript interface mirrors byte-for-byte. NO BE change. |
| `/topic/rooms.{roomId}.posters` STOMP destination | `BE/src/main/java/com/yeosal/api/realtime/RealtimePublisher.java:140-142` + JWT regex `BE/src/main/java/com/yeosal/api/realtime/JwtChannelInterceptor.java:44` | FE STOMP subscriber path. Story 7.2 already added `posters` to the auth-allowed topic regex (memory log 2026-06-08 — "JwtChannelInterceptor regex extended with `posters` to unblock Story 7.3 FE subscriber"). NO BE change. |
| `MonthlyPosterReadyPayload` JSON wire-format | `BE/src/main/java/com/yeosal/api/ceremony/MonthlyPosterReadyPayload.java:16` | FE frame shape `{roomId: number, yearMonth: string}`. FE TypeScript interface mirrors. NO BE change. |
| `RoomInvite` payload + `createInvite` REST | `FE/src/api/rooms.ts:55-72` + BE `RoomController` | Active invite supplies `code` + `kakaoShareUrl` for Kakao share's `link.{mobileWebUrl, webUrl}`. The share's `imageUrl` swaps from `previewCardImageUrl` (invite preview) to `poster.pngUrl` — but the invite-link contract is reused as-is. |
| `useCreateInvite` mutation | `FE/src/lib/query/hooks/rooms.ts` | Used by FinalThreeCard's share path to materialize an active invite on first share-tap if none exists. Mutation already idempotent at BE-side (`/invites` returns existing active row). |
| `kakaoshare/PngRasterizer` cross-module 800×420 PNG output | `BE/src/main/java/com/yeosal/api/kakaoshare/PngRasterizer.java` | Story 7.1's `FinalThreeService.generatePoster:213` already writes the PNG to `posterUrlBase + "/posters/" + fileName`. Story 7.3 consumes this URL via `posterDto.pngUrl` — never re-rasterizes on FE. |
| `RealtimeProvider` + singleton STOMP client | `FE/src/providers/RealtimeProvider.tsx` + `FE/src/lib/realtime/client.ts` | Single STOMP client (project-context.md:127). Story 7.3's `useFinalThreePoster` subscribes via the existing `useRealtimeSubscription<T>(destination, handler)` helper (`FE/src/lib/realtime/client.ts:265`) — never creates a new Client. |
| `useRoomPoints` REST + STOMP composite pattern | `FE/src/lib/query/hooks/roomPoints.ts:43` | The canonical reference for "REST primary + STOMP merge + reconnect-recovery + cancelQueries + setQueryData dedupe". Story 7.3's `useFinalThreePoster` mirrors structurally (Trap #5) — but DROPS setQueryData (signal-only frame) and DROPS dedupe set (immutable poster). |
| `sendInviteShare` Kakao SDK wrapper | `FE/src/lib/kakaoShare.ts` + `useKakaoShare` mutation `FE/src/lib/query/hooks/useKakaoShare.ts` | Story 6.2 single-wrapper precedent. Story 7.3 appends `sendPosterShare(...)` next to it (same `shareFeedTemplate({template:{...}})` SDK call; different template payload). Single-wrapper rule preserved. |
| `addBreadcrumb` Sentry helper | `FE/src/lib/sentry.ts` | Story 6.2 AC15 lineage. Story 7.3 `sendPosterShare` mirrors WARN-level breadcrumb-on-fail (no captureException — user-visible fallback handles UX). |
| `react-native-svg` `SvgXml` rendering | `FE/package.json:react-native-svg 15.12.1` (already in deps) + precedent `FE/src/components/survival/poolStages/Stage5.tsx:15` | Renders the inline SVG string from `posterDto.svgText` without a network roundtrip for the PNG. NO new dep. |
| `useMeSurvivalQuery()` cross-room ACTIVE list | `FE/src/lib/query/hooks/survival.ts:26` | Source of truth for "which rooms am I currently ACTIVE in?" — drives the per-room card enumeration. NO change. |
| `entryDateOf` + `fromIso` KST date helpers | `FE/src/lib/calendar.ts` | Story 7.3 ADDS `previousYearMonthKst(now)` next to these (NEW export — AC8). NO change to existing helpers. |
| Story 6.2 brand-voice phrase set | `_bmad-output/implementation-artifacts/6-2-kakao-share-sdk-integration-deep-linking.md` AC5 | Story 7.3 adds 4 NEW phrases on top of the existing set. AVOID-lexicon zero check inherited. |

**Anti-pattern (DO NOT IMPLEMENT):**

- Add a new BE endpoint for "surviving members of room/month" — Story 7.1's `getPosterForMember` already enforces membership; the FE-side `useMeSurvivalQuery().status === "ACTIVE"` gate is sufficient for UX (Trap #4). A new endpoint duplicates BE work for zero correctness gain.
- Extend `PosterDto` with `viewerSurvived: boolean` — would require BE change + new column (no snapshot exists at month-end). FE-only gate satisfies AC4 with smaller surface (Trap #4).
- Add a V14 migration with `survivor_user_ids bigint[]` column — out of scope per "NO new migration" lock. Would also require modifying Story 7.1's `generatePoster:213-220` write path (touching closed-surface code).
- Create a new STOMP topic `/topic/rooms.{id}.final-three` — Story 7.2 already locked `/topic/rooms.{id}.posters` and added it to the JwtChannelInterceptor regex. Adding a parallel topic is wasted broker capacity.
- Use Kakao Share SDK's `sendCustomFeed` template — requires Kakao Developers Console pre-registration + review. Story 6.2 AC1 banned this for release-risk reasons. **Default `feed` template only**.
- Render the poster via `Image source={{ uri: poster.pngUrl }}` (skip inline SVG) — wastes one HTTP round-trip per card, and the PNG raster loses the Editorial sub-mode crispness. The SVG string is already inlined in the REST response specifically to avoid this. PNG is for **Kakao share's imageUrl only** (Kakao fetcher cannot follow SVG).
- Add a push notification on poster ready — PRD §3.1 line 305 is Story 7.2's `MonthlyPosterReady` STOMP frame; foreground users see realtime; cold users see card on next app-open via TanStack Query refetch. Push notification is a Story 8.5 analytics layer.

**Verification before any code edit:** run `gh pr view 93 --json mergeCommit 2>/dev/null | grep oid` (expect `455a939` — Story 7.1) and `gh pr view 95 --json mergeCommit 2>/dev/null | grep oid` (expect `5b12ffb` — Story 7.2). Run `grep -n "posters" BE/src/main/java/com/yeosal/api/realtime/JwtChannelInterceptor.java` (expect line ~44 with the regex containing `posters`). Run `ls FE/src/lib/kakaoShare.ts` (expect EXISTS — Story 6.2 precondition).

### AC1 — `FinalThreeCard` per-room rendering on Home (`(tabs)/today`) tab (PRIMARY SURFACE)

**Given** I am authenticated, my `useMeSurvivalQuery()` returns at least one entry with `status === "ACTIVE"`, and the `final_three_posters` row for `(roomId, prior_calendar_month_KST)` exists
**When** I open the `(tabs)/today` route
**Then** for each ACTIVE-room entry, exactly one `<FinalThreeCard roomId={...} yearMonth={...} />` renders at the **top of the ScrollView** above existing `<TodayChips>` (`FE/app/(tabs)/today.tsx:56`) and below `<WalletPreview>` (when shown):

**`(tabs)/today.tsx` integration shape (additive only — do not modify existing lines 21-63):**

```tsx
import { FinalThreeCard } from "../../src/components/ceremony";
import { useMeSurvivalQuery } from "../../src/lib/query/hooks/survival";
import { previousYearMonthKst } from "../../src/lib/calendar";
// ... existing imports unchanged ...

const meSurvival = useMeSurvivalQuery();
const activeRoomEntries = (meSurvival.data ?? []).filter((e) => e.status === "ACTIVE");
const targetYearMonth = previousYearMonthKst();

// Inside the ScrollView, ABOVE TodayChips (line 56):
{activeRoomEntries.map((entry) => (
  <FinalThreeCard
    key={entry.roomId}
    roomId={entry.roomId}
    yearMonth={targetYearMonth}
  />
))}
```

> **Placement rule:** the card block lives BETWEEN the existing `<WalletPreview>` block (line 49) and `<TodayChips>` (line 56). If `<WalletPreview>` is not shown (`!isSpectatorEverywhere`), the FinalThreeCard block becomes the FIRST scroll child. This is intentional — Day-1-of-month users are surviving-not-spectator (otherwise the AC4 gate would self-hide all cards anyway).

**`FinalThreeCard` props + render shape:**

```tsx
// FE/src/components/ceremony/FinalThreeCard.tsx
interface FinalThreeCardProps {
  roomId: number;
  yearMonth: string; // "YYYY-MM"
}

export function FinalThreeCard({ roomId, yearMonth }: FinalThreeCardProps) {
  const poster = useFinalThreePoster(roomId, yearMonth);
  const rooms = useRoomsQuery();
  const members = useRoomMembersQuery(roomId);
  const myStatus = useCurrentRoomSurvivalState(roomId);

  // AC4 — FE-only privacy gate (Trap #4). Hide unless viewer is currently
  // ACTIVE in this room. A user who joined after poster generation OR
  // transitioned to RED since month-end never sees the marketing asset.
  if (myStatus?.status !== "ACTIVE") return null;

  // AC2 — 404 self-hide. The hook returns null on POSTER_NOT_FOUND.
  if (poster.isLoading) return null;       // silent loading — no skeleton (Trap #11)
  if (poster.data == null) return null;    // no poster yet OR room had no last-month survivors

  const roomName = rooms.data?.find((r) => r.id === roomId)?.name ?? "";
  const survivorCount = (members.data ?? []).filter((m) => m.survivalStatus === "ACTIVE").length;
  // ... render SVG inline + 2 share CTAs. See AC5 + AC7.
}
```

**Visual layout (D1 Editorial sub-mode carries through the BE-rendered SVG body):**

```text
┌─────────────────────────────────────────────────────────┐
│  이번 달, 우리 살아남았어                       (header) │  ← AC6 brand-voice copy
│                                                         │
│  [   inline SVG poster (800×420 aspect)              ]  │  ← react-native-svg SvgXml
│                                                         │
│  [ KakaoTalk으로 공유 ]  [ 다른 앱으로 공유 ]            │  ← AC5 buttons (Story 6.2 byte-similar)
└─────────────────────────────────────────────────────────┘
```

**Anti-pattern (DO NOT IMPLEMENT):**

- Render `<FinalThreeCard />` inside the existing `TodayChips` row — wrong: TodayChips is reserved for feed entries (`useFeedQuery` data). Story 7.3 surface is a top-of-feed banner, not a chip.
- Use `<FlashList>` for the per-room card enumeration — wrong: typical v1 user is in 1-3 rooms; FlashList over-engineers for 1-3 items and conflicts with the outer `ScrollView`. Plain `.map()` is correct.
- Enumerate all of `useRoomsQuery().data` instead of ACTIVE entries from `useMeSurvivalQuery()` — would render a card for SPECTATOR / RED / YELLOW rooms before the FE privacy gate fires inside the card. Cheaper to filter at enumeration site (Trap #2).
- Add a `useFocusEffect` that explicitly refetches on tab focus — TanStack Query's `refetchOnWindowFocus`/`refetchOnReconnect` defaults + the STOMP MonthlyPosterReady invalidation (AC3) cover this. Adding `useFocusEffect` here doubles refetches.
- Render the card at the BOTTOM of the ScrollView (below ReflectionCard) — buries the marketing surface. PRD KPI requires top-of-feed visibility for Day-30 share-rate ≥ 15%.

PRD: FR-8.7.4 (Home tab + Kakao share). Architecture: §6.2 line 642 (`FinalThreeCard.tsx` placement). Epic: line 960-963.

### AC2 — `getPoster(roomId, yearMonth)` REST wrapper + `useFinalThreePoster` TanStack hook

**Given** Story 7.1's `GET /api/v1/rooms/{roomId}/posters/{yearMonth}` endpoint returns 200 + `ApiResponse<PosterDto>` for membership-gated success, 404 (`POSTER_NOT_FOUND`) for missing
**When** the dev agent wires the REST + cache layer
**Then** TWO new files land + ONE 1-line append:

**(a) `FE/src/api/posters.ts` — typed REST wrapper:**

```typescript
// Story 7.3 — REST client for the per-(room, yearMonth) Final-3 poster.
// Powers useFinalThreePoster (AC2); never called directly by components per
// the project-context domain-hook rule (FE/src/lib/query/hooks/*).

import { apiRequest, ApiError, type ApiEnvelope } from "./client";

/**
 * Wire shape mirrors BE PosterDto (Story 7.1
 * BE/src/main/java/com/yeosal/api/ceremony/PosterDto.java) byte-for-byte.
 * generatedAt is ISO-8601 UTC (Java Instant#toString); pngUrl may be null
 * when PNG transcode failed at generation time — FinalThreeCard falls back
 * to inline SVG only and disables Kakao share.
 */
export interface FinalThreePosterDto {
  readonly roomId: number;
  readonly yearMonth: string;        // "YYYY-MM"
  readonly svgText: string;
  readonly pngUrl: string | null;
  readonly generatedAt: string;
}

/**
 * GET /api/v1/rooms/{roomId}/posters/{yearMonth}
 *
 * <p>404 (POSTER_NOT_FOUND) → returns null (no poster exists OR the room
 * had zero ACTIVE survivors that month). 403 (FORBIDDEN, membership
 * stripped) → also returns null so the card simply self-hides instead
 * of polluting Sentry. Any other non-2xx surfaces as ApiError.
 */
export async function getPoster(
  roomId: number,
  yearMonth: string,
): Promise<FinalThreePosterDto | null> {
  try {
    const envelope = await apiRequest<ApiEnvelope<FinalThreePosterDto>>(
      `/rooms/${roomId}/posters/${yearMonth}`,
    );
    return envelope.data;
  } catch (err) {
    if (err instanceof ApiError && (err.status === 404 || err.status === 403)) {
      return null;
    }
    throw err;
  }
}
```

**(b) `FE/src/lib/query/hooks/useFinalThreePoster.ts` — TanStack hook with STOMP merge:**

```typescript
// Story 7.3 FE-4 / AC2 + AC3 — domain hook for the per-(room, yearMonth)
// Final-3 poster.
//
// Composes:
//   - REST primary: useQuery({ qk.finalThreePoster(roomId, ym), getPoster, staleTime: 5min })
//   - STOMP merge:  useRealtimeSubscription<MonthlyPosterReadyFrame> on
//                   /topic/rooms.{roomId}.posters → invalidate matching key
//   - Reconnect:    disconnected→connected transition refetches (mirrors useRoomPoints
//                   FE/src/lib/query/hooks/roomPoints.ts:65-84 — Patch 3).
//
// Returns null for 404s so the consumer can simply branch on `data == null` to
// hide its card. Components never call useQuery directly (project-context).

import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef } from "react";
import { getPoster, type FinalThreePosterDto } from "../../../api/posters";
import { qk } from "../keys";
import {
  useRealtimeStatus,
  useRealtimeSubscription,
  type RealtimeStatus,
} from "../../realtime/client";

export interface MonthlyPosterReadyFrame {
  readonly roomId: number;
  readonly yearMonth: string;
}

export interface UseFinalThreePosterResult {
  readonly data: FinalThreePosterDto | null;
  readonly isLoading: boolean;
  readonly isError: boolean;
}

const STALE_TIME_MS = 5 * 60_000; // 5 min — poster is immutable per PRD FR-8.7.6
const GC_TIME_MS = 30 * 60_000;
const YEAR_MONTH_RE = /^\d{4}-(0[1-9]|1[0-2])$/;

export function useFinalThreePoster(
  roomId: number,
  yearMonth: string,
): UseFinalThreePosterResult {
  const qc = useQueryClient();
  const enabled = Number.isFinite(roomId) && roomId > 0 && YEAR_MONTH_RE.test(yearMonth);
  const realtimeStatus = useRealtimeStatus();

  const query = useQuery<FinalThreePosterDto | null>({
    queryKey: qk.finalThreePoster(roomId, yearMonth),
    queryFn: () => getPoster(roomId, yearMonth),
    enabled,
    staleTime: STALE_TIME_MS,
    gcTime: GC_TIME_MS,
  });

  // Reconnect-recovery — mirrors useRoomPoints Patch 3. A user with the
  // Home tab open at 06:25 KST (WS disconnected during overnight idle)
  // re-connects at 06:31 KST after Story 7.2's job fires → invalidate to
  // catch the new poster row even if the STOMP frame slipped past the gap.
  const prevStatusRef = useRef<RealtimeStatus>(realtimeStatus);
  useEffect(() => {
    if (
      enabled
      && prevStatusRef.current === "disconnected"
      && realtimeStatus === "connected"
    ) {
      qc.invalidateQueries({ queryKey: qk.finalThreePoster(roomId, yearMonth) });
    }
    prevStatusRef.current = realtimeStatus;
  }, [realtimeStatus, qc, roomId, yearMonth, enabled]);

  // STOMP — invalidate-on-frame (NOT setQueryData) because Story 7.2 AC4
  // line 199-202 deliberately keeps the frame signal-only ("would push
  // kilobytes of SVG over STOMP for 5K rooms"). Idempotent invalidate is
  // safe under duplicate frames.
  const destination = enabled ? `/topic/rooms.${roomId}.posters` : null;
  useRealtimeSubscription<MonthlyPosterReadyFrame>(destination, (frame) => {
    if (!frame || typeof frame.roomId !== "number" || typeof frame.yearMonth !== "string") return;
    // Defence-in-depth — pin consumer to roomId in case broker fanout misroutes
    // a frame (the topic is room-scoped server-side, but this mirrors useRoomPoints'
    // guard at FE/src/lib/query/hooks/roomPoints.ts:90).
    if (frame.roomId !== roomId) return;
    if (frame.yearMonth !== yearMonth) return;
    qc.invalidateQueries({ queryKey: qk.finalThreePoster(roomId, yearMonth) });
  });

  return {
    data: query.data ?? null,
    isLoading: query.isLoading,
    isError: query.isError,
  };
}
```

**(c) `FE/src/lib/query/keys.ts` — 1-line append inside the `qk` const:**

```typescript
// Story 7.3 — per-(room, yearMonth) Final-3 poster. 5-min staleTime in the
// hook (posters are immutable per FR-8.7.6). Invalidated on STOMP
// MonthlyPosterReady frames + on reconnect-recovery from a disconnected→
// connected transition.
finalThreePoster: (roomId: number, yearMonth: string) =>
  ["finalThreePoster", roomId, yearMonth] as const,
```

**Anti-pattern (DO NOT IMPLEMENT):**

- Call `apiRequest<FinalThreePosterDto>` directly inside `FinalThreeCard` — violates project-context "All data fetching goes through domain hooks in src/lib/query/hooks/*" (line 126).
- Convert 404 to a thrown `ApiError` in `getPoster(...)` — null-return is the canonical "no card to show" signal. Throwing forces every consumer to wrap in `try/catch` AND distinguish 404 from real errors.
- Use `useInfiniteQuery` — wrong, single-row endpoint, no pagination.
- Use `setQueryData` from the STOMP handler with a synthetic `FinalThreePosterDto` — wrong: the frame deliberately omits SVG body (Story 7.2 AC4 line 199-202). Always refetch via `invalidateQueries`.
- Skip the regex validation on `yearMonth` — a malformed string would hit BE as 400 BAD_REQUEST + `VALIDATION` (`PosterController.java:48-50`) before falling through to 404, polluting Sentry server-bug channel.
- Use `staleTime: Infinity` — wrong: a poster IS immutable, but the user may foreground after a multi-day gap; 5min balances "don't refetch in scroll" vs "catch up on cold-start".
- Cache by `roomId` only (drop `yearMonth`) — would surface July's poster under June's cache key on Aug 1.

PRD: FR-8.7.2, FR-8.7.4. Architecture: §3.2 line 145 (`useFinalThreePoster` naming lock), §6.2 line 612 (`api/posters.ts` naming lock) + line 620.

### AC3 — STOMP `/topic/rooms.{roomId}.posters` subscriber + realtime invalidate

**Given** Story 7.2 emits `MonthlyPosterReadyPayload {roomId, yearMonth}` to `/topic/rooms.{roomId}.posters` exactly once per fresh-generated poster (skipped on idempotent rerun)
**Given** `JwtChannelInterceptor.java:44` already includes `posters` in the auth-allowed topic regex `^/topic/rooms\.(\d+)\.(chat|members|survival|points|kudos|posters)$`
**When** the FE subscribes via the singleton STOMP client through `useRealtimeSubscription<MonthlyPosterReadyFrame>(destination, handler)`
**Then** receiving a frame for the current `(roomId, yearMonth)` MUST invalidate the matching TanStack key, triggering a fresh REST fetch:

**Invariants (covered by `useFinalThreePoster` implementation in AC2(b) above):**

1. Subscription destination is the byte-identical template literal `` `/topic/rooms.${roomId}.posters` `` (NO trailing slash, NO double-slash).
2. Frame body is JSON-parsed via the helper inside `useRealtimeSubscription<T>` (`FE/src/lib/realtime/client.ts:271-277`) — no extra `JSON.parse` call inside the handler.
3. Type-narrowing guards: frame is non-null, `frame.roomId` is `number`, `frame.yearMonth` is `string` matching `^\d{4}-(0[1-9]|1[0-2])$` — anything else dropped silently (broker malformed-payload drop precedent: `RealtimeProvider.tsx:36-39`).
4. roomId pin (defence-in-depth) — even though the topic is room-scoped, drop frames where `frame.roomId !== roomId`. Mirrors `useRoomPoints`.
5. yearMonth pin — drop frames where `frame.yearMonth !== yearMonth` (defence against month-rollover races where a card mounted with `"2026-05"` receives a `"2026-06"` frame because Story 7.2's June batch fired at 06:30 KST while card was open).
6. Action on validated frame: `qc.invalidateQueries({ queryKey: qk.finalThreePoster(roomId, yearMonth) })` ONLY. **NO** direct `setQueryData` — the frame is a thin signal, never carries SVG bytes.
7. Subscription teardown — `useRealtimeSubscription`'s `useEffect` cleanup unsubscribes on `destination` change (room change) or component unmount. NO double-subscribe.

**Anti-pattern (DO NOT IMPLEMENT):**

- Instantiate a second STOMP `Client` — banned by project-context line 127. Always go through `getRealtimeClient()` via the existing `useRealtimeSubscription` helper.
- Subscribe at `_layout.tsx` mount and route MonthlyPosterReady through `routeInvalidation(qc, kind)` in `FE/src/lib/notifications.ts` — wrong: that path is for per-user `/user/queue/notifications` push-mirror frames; posters are room-scoped fan-out, naturally subscribed by the consuming card.
- Use `useEffect(() => { /* setup */ }, [])` to subscribe — wrong: must depend on `destination` (re-subscribes on roomId change) and uses the helper's cleanup contract. The helper at `lib/realtime/client.ts:265-285` is the only correct shape.
- Trust the frame's SVG (if present) — Story 7.2 will never include the SVG body (AC4 anti-pattern), but a future refactor might. Always refetch — frame is signal-only.
- Add a separate `useEffect` that subscribes to `/user/queue/posters` per-user-private — there is no such topic; Story 7.2 chose room-scoped fanout deliberately.

PRD: NFR-9.1.3 (broker latency < 500ms p95). Architecture: §3.2 line 147 (single STOMP client), §5.1 (RealtimePublisher chokepoint). Story 7.2 AC4 line 199-202 (signal-only frame contract).

### AC4 — FE-only "am I a surviving member" privacy gate (DIGNITY-TONE UX GATE)

**Given** Story 7.1's `getPosterForMember` enforces room membership but NOT viewer survivor-status (no historical snapshot table exists; adding one would require a V14 migration, which is out of scope per Trap #4)
**Given** the poster PNG at `nginx /posters/<file>.png` is served unauthenticated to anyone with the URL (Story 6.1 + 7.1 nginx static convention — content is the room's published asset, not a privacy-sensitive payload)
**When** the FE renders the `FinalThreeCard`
**Then** the card is shown ONLY when ALL of the following hold:

1. `useCurrentRoomSurvivalState(roomId)?.status === "ACTIVE"` — viewer currently ACTIVE in this room.
2. The REST fetch returns a non-null `FinalThreePosterDto` (404 = no poster OR no membership — both flip to "hide").
3. `yearMonth` matches `previousYearMonthKst()` — viewer never sees stale months (defence against cache leak from a prior month-1 boundary).

**Why FE-only (Trap #4 rationale recap):**

- **Privacy stance:** the SVG + PNG are room-scoped assets containing surviving members' nicknames. Nicknames are visible to all room members already (membership-gated by Story 7.1 endpoint). No PII is leaked by relaxing the per-viewer gate to FE.
- **Architecture §4.14 boundary:** §4.14 forbids FE filtering of *forbidden-state survival information* (e.g., another member's RED status under cooldown). The Final-3 card is not in that category — it's a celebration of survivors, not a leak of eliminations.
- **YAGNI:** a strict BE gate requires either a snapshot table (V14 migration + Story 7.1 write-path edit) or join logic against `survival_state` at request time. The latter approximation (current ACTIVE status) is what the FE check already provides — duplicating it BE-side adds zero correctness.
- **Edge case (post-poster joiner):** a member who joined June 2 — after the June 1 06:30 KST poster generation — has `survival_state.status = ACTIVE` (fresh row), so they would FALSELY pass the gate. **Acceptance:** the marginal "you survived a month you weren't in" UX bug is low-impact (the SVG body lists the actual survivors by nickname; the new member can see they're not in the list). v1 ships this; v1.5 may add a `joined_at <= posterGeneratedAt` BE check if KPI signal warrants. Recorded as deferred-work entry.

**Anti-pattern (DO NOT IMPLEMENT):**

- Trust `useRoomsQuery()` membership for the gate — wrong: membership is necessary but not sufficient. SPECTATOR / RED members are still room members. **`useCurrentRoomSurvivalState(roomId)?.status === "ACTIVE"`** is the correct gate.
- Skip the `useMeSurvivalQuery()` call and gate solely on REST 404 — wrong: a 200 response means "you're a member"; it does NOT mean "you were a survivor". The FE gate adds the privacy-of-marketing-surface layer on top of BE membership.
- Throw a synthetic `ApiError` on the FE side when status !== ACTIVE — wrong: the gate is a render-time `return null`, not an error. Throwing pollutes Sentry breadcrumbs.
- Strict-mode check `myStatus.status === "ACTIVE" && myStatus.broadVisibilityAt == null` — over-tight. `broadVisibilityAt` is the 24h cooldown for newly-RED transitions; ACTIVE members don't have one set. Pure `status === "ACTIVE"` is correct.

PRD: FR-8.7.4 (Home tab Final-3 — surviving members only). Architecture: §4.14 (privacy boundary — does NOT apply here, see rationale above). Epic: line 968-970.

### AC5 — Kakao Share entry: `sendPosterShare` + `useFinalThreePosterShare` + plain `Share.share` fallback

**Given** Story 6.2 shipped `sendInviteShare(...)` + `useKakaoShare()` for the invite-card share path
**Given** the AC4-gated viewer taps "KakaoTalk으로 공유" on the FinalThreeCard
**When** the FE invokes the Kakao Share SDK
**Then** the share payload uses the POSTER's PNG as `imageUrl` + the ROOM's active-invite URL as `link.{mobileWebUrl, webUrl}`:

**`FE/src/lib/kakaoShare.ts` — APPEND `sendPosterShare` next to existing `sendInviteShare`:**

```typescript
// Story 7.3 — second Kakao share template alongside sendInviteShare (Story
// 6.2). Same SDK call, same template type ("feed"), different imageUrl
// (poster.pngUrl instead of invite.previewCardImageUrl) and different
// description / button copy. Single-wrapper rule preserved
// (project-context.md:127) — UI components must invoke via
// useFinalThreePosterShare, never call shareFeedTemplate directly.

import type { FinalThreePosterDto } from "../api/posters";

export interface PosterShareInput {
  poster: FinalThreePosterDto;
  invite: {
    code: string;
    kakaoShareUrl: string;
  };
  roomName: string;
  survivorCount: number;
}

/**
 * Final-3 ceremony Kakao share. PNG-only imageUrl — the inline SVG body
 * (poster.svgText) is never sent to KakaoTalk's fetcher (Kakao card
 * thumbnails are PNG/JPEG only per the SDK docs). When poster.pngUrl is
 * null (transcode failed at generation), the caller MUST suppress the
 * KakaoTalk share button — see FinalThreeCard AC7.
 *
 * Brand-voice phrase set (locked per AC6): description uses
 * `"이번 달, 우리 살아남았어"` + per-room survivor count; button reuses
 * Story 6.2's locked `"같이 살아남자"` so the Korean invitation-tone
 * lexicon (`함께`, `같이`, `우리`, `살아남`) accumulates across surfaces.
 */
export async function sendPosterShare({
  poster, invite, roomName, survivorCount,
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
      data: { errorMessage: err instanceof Error ? err.message : String(err) },
    });
    throw err;
  }
}
```

**`FE/src/lib/query/hooks/useFinalThreePosterShare.ts` (NEW — mirrors `useKakaoShare`):**

```typescript
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
```

**`FinalThreeCard` share-button onPress wiring (folded inline — see AC9 scope fence on PosterShareSheet absence):**

```tsx
const posterShare = useFinalThreePosterShare();
const createInvite = useCreateInvite();

async function handleShareKakao() {
  if (poster.data == null) return;
  if (poster.data.pngUrl == null) {
    return handleShareGeneric();
  }
  try {
    const invite = await createInvite.mutateAsync({ roomId });
    posterShare.mutate(
      { poster: poster.data, invite, roomName, survivorCount },
      {
        onError: () => {
          toast.info("KakaoTalk 공유가 안 돼요. 다른 방법으로 공유해주세요.");
          handleShareGeneric();
        },
      },
    );
  } catch {
    toast.info("KakaoTalk 공유가 안 돼요. 다른 방법으로 공유해주세요.");
    handleShareGeneric();
  }
}

async function handleShareGeneric() {
  try {
    await Share.share({
      message: `이번 달, 우리 ${survivorCount}명이 함께 살아남았어요. (열살)`,
    });
  } catch {
    // user dismissed
  }
}
```

> **survivorCount derivation:** count of surviving nicknames is NOT in `PosterDto` (BE intentionally minimal). Two valid options: (a) parse SVG text for the survivor list — brittle (Trap #9); (b) use `useRoomMembersQuery(roomId).data?.filter(m => m.survivalStatus === "ACTIVE").length` — current-snapshot approximation (matches AC4 FE-only gate philosophy). **LOCK option (b)** — same dignity-tone tradeoff as AC4. Recorded in dev-notes as architecture-deviation #2.
>
> **createInvite signature note:** Verify `useCreateInvite()` exposes `mutateAsync({ roomId })` — `FE/src/lib/query/hooks/rooms.ts` already exports `useCreateInvite` for Story 6.1 + 6.2 InviteCodeSheet. If the existing mutation signature differs (e.g., accepts a positional roomId), adapt the call shape without modifying the hook itself (AC9 scope fence on `rooms.ts`).

**Anti-pattern (DO NOT IMPLEMENT):**

- Reuse `sendInviteShare` with the poster's pngUrl swapped into `invite.previewCardImageUrl` — wrong: the `PosterShareInput` shape is deliberately distinct so the caller can't accidentally pass an invite to the poster surface. Type-safety > parameter polymorphism.
- Skip the `createInvite.mutateAsync` step and ship a deep-link without an invite code — wrong: epic AC2 line 964-966 locks "the share payload includes the PNG poster + my room's invite-code (so external readers can join the room directly from the shared card)". Sharing without an invite breaks the entire viral loop.
- Display a retry button on KakaoTalk SDK failure — Story 6.2 AC4 anti-pattern lock. Silent fallback is the UX.
- Use `Share.share({ url: poster.pngUrl })` — iOS-only field; Android ignores it. Use `message` only.
- Include `kakaoShareUrl` in the generic-share `message` field — Story 6.2 AC4 anti-pattern lock. The generic share is text-only by design (KakaoTalk preview is unavailable when the SDK has failed, so a URL adds clutter).
- Add a captureException on SDK failure — over-alerts ops. WARN breadcrumb only (Story 6.2 AC15 precedent).
- Hard-code `survivorCount = 3` because the feature is called "Final-3" — wrong: PRD FR-8.7.5 says "all surviving member nicknames listed, top-3 by tenure highlighted" — survivor count is variable (could be 1, 5, 25; the "Final-3" name refers to the top-3 highlight, not the survivor cap).

PRD: FR-8.7.4 (Home tab share). Architecture: §3.3 line 154 (Kakao Share SDK), §4.10 (Kakao deep-link). Epic: line 964-966. Story 6.2 AC1 + AC4 (share-flow precedent).

### AC6 — Brand-voice copy lock (LOCKED TEXT)

**Given** every user-facing phrase introduced by this story must pass the brand-voice lint (Architecture §4.15 / Story 5.4 baseline 0 HARD / ≤198 warnings)
**Given** Story 6.2 AC5 locked the canonical share-flow phrase set
**When** the brand-voice lint scans the new files
**Then** EXACTLY this phrase set is used (USE-only; AVOID-lexicon zero check):

| Surface | Locked phrase | USE 어휘 hit |
|---|---|---|
| `FinalThreeCard` header | `"이번 달, 우리 살아남았어"` | `우리`, `살아남` |
| `FinalThreeCard` primary CTA button label | `"KakaoTalk으로 공유"` | (neutral; Story 6.2 reuse) |
| `FinalThreeCard` secondary CTA button label | `"다른 앱으로 공유"` | (neutral; Story 6.2 reuse) |
| Kakao Default Template `description` | `"이번 달, 우리 살아남았어. <N>명이 함께 끝까지 갔어요."` | `우리`, `살아남`, `함께` |
| Kakao Default Template button | `"같이 살아남자"` | `같이`, `살아남` (Story 6.2 byte-identical reuse) |
| Generic share message (fallback) | `"이번 달, 우리 <N>명이 함께 살아남았어요. (열살)"` | `우리`, `함께`, `살아남` |
| Kakao SDK fallback toast | `"KakaoTalk 공유가 안 돼요. 다른 방법으로 공유해주세요."` | (neutral; Story 6.2 byte-identical reuse) |
| `FinalThreeCard` accessibility label (a11y) | `"이번 달 살아남은 멤버 포스터"` | `살아남` |
| `FinalThreeCard` share-button accessibility label | `"포스터를 KakaoTalk으로 공유하기"` | (neutral) |

**AVOID 어휘 zero check:** Across the 9 user-visible phrases above, the AVOID lexicon (`벌금 / 잃었다 / 떨어졌다 / 실패 / 자책 / 부담 / 패배 / 죄책감`) has **zero hits**. Baseline preserved.

**Why two emoji-free phrases when epic line 972 suggested 🎉:**

- project-context.md:191 — "No emojis in source files or docs unless explicitly requested." Epic vetting language said "or vetted equivalent". Vetted equivalent here = emoji-free Korean invitation tone (matches Story 6.1 SVG footer + Story 5.4 chat broadcast precedent). Recorded as architecture-deviation #3.

**Anti-pattern (DO NOT IMPLEMENT):**

- Use `"이번 달, 우리 살아남았어 🎉"` directly per epic line 972 — violates project-context.md:191. The vetted equivalent (period-terminated) is the correct interpretation.
- Use `"끝까지 버텼다"` instead of `"끝까지 갔어요"` — `버텼다` is a survival-as-endurance frame (closer to AVOID `부담` semantics). `끝까지 갔어요` is invitation-tone (Korean ascender — "we made it together").
- Write any English copy for the share template — Story 8.3 owns ASO copy. v1 share-flow stays Korean-only.
- Hard-code an English `description` for English-locale users — no English locale exists for v1 (project-context.md confirms Korean-only). Locale switching is post-v1.
- Add survivor names to the share `description` (e.g., `"규민, 지수, 민호…"`) — PII leak risk on platforms where the recipient may share the screenshot further. The SVG body lists nicknames inside the visual asset only (already member-gated for the originator who creates the share).

PRD: FR-8.8.2 (brand-voice). Architecture: §4.15, §5.5. Story 6.2 AC5 (precedent).

### AC7 — Inline SVG rendering + PNG fallback + a11y

**Given** `poster.svgText` carries the BE-rendered SVG document (Editorial sub-mode + room name + nicknames + top-3 tenure highlights)
**Given** `react-native-svg 15.12.1` is already in deps and the project precedent for inline SVG strings is `FE/src/components/grid/ContributionGrid.tsx` + the `poolStages/` set
**When** `FinalThreeCard` renders the poster body
**Then** the SVG is rendered via `SvgXml` with explicit width/height + a11y label:

**Render shape:**

```tsx
import { SvgXml } from "react-native-svg";
// ...
const POSTER_ASPECT_RATIO = 800 / 420; // matches Story 7.1 SvgRenderer + 6.1 PngRasterizer

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
      <SvgXml xml={poster.data.svgText} width="100%" height="100%" />
    </View>
    <View style={styles.actions}>
      <Button
        label="KakaoTalk으로 공유"
        tone="primary"
        size="md"
        fullWidth
        disabled={poster.data.pngUrl == null}
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
```

**Visual contract:**

- `aspectRatio: 800/420` — matches Story 7.1 `SvgRenderer.render(...)` + Story 6.1 `PngRasterizer` 800×420 lock so the card never has dead space or clipping.
- `width="100%"` + `height="100%"` on `SvgXml` — RN-svg scales the document to fit the parent via the SVG's own `viewBox` attribute (BE renderer guarantees one).
- Card padding + corner radius pulled from `space[3]` / `radius.lg` design tokens (no hard-coded numerics — project-context.md FE token discipline).

**PNG fallback semantics:**

- The inline SVG is ALWAYS rendered (the SVG body is always non-null per Story 7.1 entity contract — `svgText` is `not null` in V11).
- The PNG (`poster.pngUrl`) is consumed ONLY by the Kakao share path. The `FinalThreeCard` never displays the PNG as an `<Image>` source.
- When `pngUrl === null` (PNG transcode failed at generation), the KakaoTalk share button renders disabled (opacity-50 + `disabled={true}`) — generic share remains available.

**Anti-pattern (DO NOT IMPLEMENT):**

- Render the poster via `<Image source={{ uri: poster.pngUrl }} />` — wrong: wastes one HTTP roundtrip per card, loses the Editorial sub-mode token crispness on high-DPI displays, and breaks the AC4 privacy stance (Image cache survives logout; SvgXml decoding is per-render).
- Use `SvgUri` to fetch a `.svg` file URL — wrong: Story 7.1 / 7.2 do not expose a public `.svg` URL; SVG body is inlined in the JSON response specifically to avoid a second fetch.
- Hard-code `aspectRatio: 16/9` or `2/1` — wrong: 800/420 is the exact aspect Story 7.1 + Story 6.1 lock. Approximations cause off-by-pixel clipping on the highlighted top-3 names.
- Strip the SVG's `viewBox` attribute on FE — wrong: scaling depends on viewBox; Story 7.1 emits it. Touch nothing in svgText.
- Render the SVG inside a `<ScrollView horizontal>` — wrong: the SVG is designed to fit the parent. Horizontal scroll breaks the visual.
- Skip `accessibilityLabel` — VoiceOver users miss the marketing surface entirely; a11y is part of the dignity-tone surface.
- Pass `poster.svgText` through `dangerouslySetInnerHTML`-style escapes — `SvgXml` parses the string directly; no escape needed (Story 7.1 already escapes nicknames via `kakaoshare/InvitePreviewRenderer.escapeXml` lineage at `BE/src/main/java/com/yeosal/api/ceremony/SvgRenderer.java:182`).

PRD: FR-8.7.3 (Editorial sub-mode token consumption). Architecture: §4.16 (FE→BE codegen pipeline — BE side already consumes; FE never re-derives), §6.2 line 642 (`FinalThreeCard.tsx`).

### AC8 — KST month-boundary helper `previousYearMonthKst()` (NEW UTILITY)

**Given** the card surface is gated by "the just-ended month" (epic line 960) which is **calendar-month boundary KST**, NOT daily-mission's 06:00 KST boundary (project-context.md:92)
**Given** `FE/src/lib/calendar.ts` has `entryDateOf(...)` for the daily-mission boundary but no calendar-month helper
**When** the dev agent adds the target-yearMonth helper
**Then** a new exported function lands at `FE/src/lib/calendar.ts`:

```typescript
// Story 7.3 — calendar-month-boundary helper (NOT the daily-mission 06:00
// KST boundary). The Final-3 poster is generated at 06:30 KST on day-1 of
// each calendar month per Story 7.2; the Home tab card surfaces the
// IMMEDIATELY prior calendar month's poster for KST-locale users.
//
// Returns "YYYY-MM" string format matching BE PosterDto.yearMonth.
// Pure function — no Date side effects, no locale lookups.
export function previousYearMonthKst(now: Date = new Date()): string {
  // KST = UTC+09:00. Compute the KST wall-clock by offsetting UTC.
  const kstMs = now.getTime() + 9 * 60 * 60 * 1000;
  const kst = new Date(kstMs);
  // getUTCMonth() on the offset Date now yields the KST calendar month.
  let year = kst.getUTCFullYear();
  let month = kst.getUTCMonth() + 1; // 1-12
  month -= 1;
  if (month === 0) {
    month = 12;
    year -= 1;
  }
  return `${year}-${String(month).padStart(2, "0")}`;
}
```

**Test invariants (covered in AC10 test matrix):**

- `previousYearMonthKst(new Date("2026-07-01T00:00:00Z"))` → `"2026-06"` (UTC midnight = KST 09:00 → KST July 1 → prev = June).
- `previousYearMonthKst(new Date("2026-06-30T20:00:00Z"))` → `"2026-06"` (UTC 20:00 = KST 05:00 July 1 → KST July → prev = June). Boundary case.
- `previousYearMonthKst(new Date("2026-06-30T12:00:00Z"))` → `"2026-05"` (UTC 12:00 = KST 21:00 June 30 → KST June → prev = May).
- `previousYearMonthKst(new Date("2026-01-01T00:00:00Z"))` → `"2025-12"` (year wrap; UTC midnight = KST 09:00 Jan 1).
- `previousYearMonthKst(new Date("2024-03-01T00:00:00Z"))` → `"2024-02"` (leap-year February guard).

**Anti-pattern (DO NOT IMPLEMENT):**

- Use `Intl.DateTimeFormat` with `timeZone: "Asia/Seoul"` — works correctly but adds locale-string parsing complexity for the same numeric output. UTC-offset math is cheaper + test-deterministic.
- Use `entryDateOf()` and slice the first 7 chars — wrong: `entryDateOf()` returns the daily-mission boundary (06:00 KST), which during 00:00–06:00 KST returns yesterday's date. Calendar-month logic must be standalone.
- Use a library like `date-fns-tz` or `dayjs` — wrong: no current dep, would inflate bundle for a 10-line helper. project-context.md "no unused deps".
- Return `Date` or `YearMonth`-like object — wrong: BE wire-format is `"YYYY-MM"` string per `PosterDto.yearMonth: string`; matching the BE shape avoids a serialization boundary.

PRD: FR-8.7.1 (calendar-month boundary). Story 5.1 `RoomRule.nextMonthKst` precedent (BE-side; FE was missing this helper until now).

### AC9 — File / scope fence (LOCKED ALLOW LIST)

**Given** the story scope is FE-only + auditable in PR review
**When** the dev agent finishes
**Then** the diff touches **exactly** these files (no more, no less):

**NEW files (5 FE source + 7 FE test):**

```
FE/src/api/posters.ts
FE/src/lib/query/hooks/useFinalThreePoster.ts
FE/src/lib/query/hooks/useFinalThreePosterShare.ts
FE/src/components/ceremony/FinalThreeCard.tsx
FE/src/components/ceremony/index.ts                              ← barrel export
FE/src/api/__tests__/posters.test.ts
FE/src/lib/query/hooks/__tests__/useFinalThreePoster.test.tsx
FE/src/lib/query/hooks/__tests__/useFinalThreePosterShare.test.ts
FE/src/lib/__tests__/calendar.previousYearMonthKst.test.ts
FE/src/lib/__tests__/kakaoShare.sendPosterShare.test.ts
FE/src/lib/__tests__/realtime.posters-topic.test.ts              ← integration: subscribe pattern
FE/src/components/ceremony/__tests__/FinalThreeCard.test.tsx
```

> 5 src + 7 test = 12 NEW files. The component-folder index.ts barrel keeps the import surface consistent with `FE/src/components/welcome/index.ts` precedent.

**MODIFIED files (existing — surgical edits only):**

```
FE/app/(tabs)/today.tsx
  └── ADD <FinalThreeCard /> render block per AC1; ADD imports
       (FinalThreeCard from "../../src/components/ceremony";
        previousYearMonthKst from "../../src/lib/calendar").
       useMeSurvivalQuery is ALREADY imported (line 16 — keep as-is).
       NO edits to existing rendering of TodayHeader/WalletPreview/TodayChips/GoalCard/TodoList/ReflectionCard.

FE/src/lib/query/keys.ts
  └── ADD ONE line in qk const: finalThreePoster: (roomId, yearMonth) => ...
       NO edits to existing keys.

FE/src/lib/kakaoShare.ts
  └── ADD `sendPosterShare(...)` function + `PosterShareInput` interface,
       export both. NO edits to existing sendInviteShare/ShareInput.

FE/src/lib/calendar.ts
  └── ADD `previousYearMonthKst(now)` exported function. NO edits to existing
       entryDateOf/fromIso/toIso/etc.
```

**BANNED PATHS (red lines — dev agent MUST NOT edit these):**

```
BE/**                                       ← NO BE source/test edits (Story 7.3 is FE-only)
BE/src/main/resources/db/migration/V*.sql   ← NO new migration (V13 stays latest)
BE/build.gradle                             ← NO new BE dependency
FE/package.json                             ← NO new FE dependency (react-native-svg, @react-native-kakao/share already in deps)
FE/app.config.ts                            ← NO universal-link or Kakao plugin changes (Story 6.2 owns)
FE/src/lib/realtime/client.ts               ← NO changes to STOMP client (subscribe via useRealtimeSubscription only)
FE/src/providers/RealtimeProvider.tsx       ← NO new subscription mount (per-card hook owns its subscription)
FE/src/lib/notifications.ts                 ← NO new routeInvalidation kind (poster signal is room-scoped STOMP, not /user/queue)
FE/src/lib/query/hooks/useKakaoShare.ts     ← Story 6.2 closed surface — sendInviteShare unchanged; new hook is parallel
FE/src/lib/query/hooks/survival.ts          ← consumer only (read useMeSurvivalQuery + useCurrentRoomSurvivalState)
FE/src/lib/query/hooks/rooms.ts             ← consumer only (useRoomsQuery + useCreateInvite + useRoomMembersQuery)
FE/src/lib/query/hooks/roomPoints.ts        ← reference precedent only — NO source edits
FE/src/components/survival/                 ← unrelated domain (PoolStack lives here, do not touch)
FE/src/components/wallet/                   ← unrelated domain
FE/src/theme/tokens.json                    ← Story 1.5 closed surface; Editorial sub-mode tokens already piped via BE codegen
FE/src/lib/sentry.ts                        ← consumer only (addBreadcrumb)
FE/src/api/client.ts                        ← consumer only (ApiError, apiRequest, ApiEnvelope)
FE/src/api/rooms.ts                         ← consumer only (RoomInvite type, createInvite REST already exported)
FE/eslint.config.js                         ← NO lint rule additions
FE/jest.config.js + FE/jest.setup.ts        ← NO test config edits (Sentry + Animated already globally mocked per project-context.md:152)
.github/workflows/*                         ← NO CI changes (deferred-work entry on be-it-boot-smoke timeout-minutes:30 is separate chore/CI PR)
docs/**, RUNBOOK.md                         ← Epic 7 retro owns
_bmad-output/planning-artifacts/**          ← read-only inputs
```

**Diff sanity check (run before sprint-status flip):**

```bash
git diff --name-only main | sort | tee /tmp/story7-3-files.txt
# Expect exactly the union of NEW + MODIFIED above (16 files total: 12 NEW + 4 MODIFIED).
# No file in BANNED PATHS list.
```

**Anti-pattern (DO NOT IMPLEMENT):**

- "While I'm in there" cleanup of `roomPoints.ts` to extract a shared "REST + STOMP composite" hook utility — out of scope. File a follow-up. Two byte-similar hooks are below the de-duplication threshold (project-context.md "DRY only when repetition is real, not speculative").
- Bump `react-native-svg` version "for compatibility" — already 15.12.1; no need.
- Add a `PosterShareSheet.tsx` to match architecture §6.2 line 643 — fold into FinalThreeCard per ADR (Trap #13). Architecture deviation #1 recorded.
- Touch `FE/app.json` to wire a new universal-link path `/posters/<...>` — wrong: epic line 964-966 shares the room's existing invite-code URL, not a poster-specific deep-link. Story 6.2's `/join?code=X` deep-link contract is reused.
- Add a `useFocusEffect` in `today.tsx` to invalidate poster keys on tab-focus — TanStack Query defaults + STOMP realtime cover this; adding here doubles refetches.
- Add a `setQueryData` to "optimistically" hide the card after share — share success does not change the poster row; no optimistic update is meaningful.

PRD / Architecture: comprehensive scope fence documented across AC0–AC8.

### AC10 — Test matrix (NET-ADDITIVE, RED → GREEN order)

**Given** TDD is enforced (project-context line 145, common/testing.md ratio target 80%+)
**Given** Story 7.2 shipped 23 NEW BE tests; this story adds 0 BE tests + 29 NEW FE tests
**When** Story 7.3 ships
**Then** the test suite adds **net-additive** tests — no existing test is removed or weakened. RED → GREEN order documented per file:

| File | Cases | Type | Notes |
|---|---|---|---|
| `FE/src/api/__tests__/posters.test.ts` | 4 | Unit (fetch mocked) | (1) `getPoster(42, "2026-05")` 200 → unwraps `envelope.data` to `FinalThreePosterDto`. (2) 404 POSTER_NOT_FOUND → returns `null`. (3) 403 FORBIDDEN → returns `null`. (4) 500 → throws `ApiError`. |
| `FE/src/lib/__tests__/calendar.previousYearMonthKst.test.ts` | 5 | Unit | The 5 cases from AC8 (July UTC midnight; June UTC 20:00 boundary; mid-June UTC 12:00; Jan 1 UTC midnight year-wrap; March 1 leap-year February guard). Pure-function tests. |
| `FE/src/lib/__tests__/kakaoShare.sendPosterShare.test.ts` | 5 | Unit (Kakao SDK mocked + Sentry mocked) | (1) Happy path: SDK invoked with exact `template.content.{title, description, imageUrl, link}` byte-match per AC5. (2) SDK throws → re-throws + `addBreadcrumb` invoked WARN-level with sanitized message. (3) `poster.pngUrl == null` → throws synchronously without invoking SDK (AC7 contract). (4) `description` substitution of `survivorCount=5` produces locked string `"이번 달, 우리 살아남았어. 5명이 함께 끝까지 갔어요."`. (5) Button title byte-equals `"같이 살아남자"` (Story 6.2 reuse guard). |
| `FE/src/lib/query/hooks/__tests__/useFinalThreePoster.test.tsx` | 4 | Unit (QueryClient + mocked realtime client) | (1) REST returns poster → `data` non-null. (2) REST returns null (404) → `data === null`. (3) `roomId == 0` or invalid `yearMonth` ("2026-13" or "26-5") → `enabled === false`, no fetch. (4) STOMP frame for matching `(roomId, yearMonth)` → `invalidateQueries` invoked once for that key; mismatched roomId frame → NOT invoked. |
| `FE/src/lib/query/hooks/__tests__/useFinalThreePosterShare.test.ts` | 2 | Unit | (1) `mutate(...)` calls `sendPosterShare(...)` mock once with input. (2) Mutation `isError === true` when sendPosterShare throws. |
| `FE/src/lib/__tests__/realtime.posters-topic.test.ts` | 1 | Integration (mocked broker via `_resetRealtimeClientForTests`) | Verifies `/topic/rooms.42.posters` destination contract: subscribe call produces a frame on the listener with the JSON-decoded MonthlyPosterReadyFrame shape. Guards regression on the dot-separator topic naming + JwtChannelInterceptor regex compatibility. |
| `FE/src/components/ceremony/__tests__/FinalThreeCard.test.tsx` | 8 | Component (Sentry + Animated globally mocked) | (1) Renders nothing when `useCurrentRoomSurvivalState(roomId)?.status !== "ACTIVE"` (AC4 gate). (2) Renders nothing when `useFinalThreePoster(...)` returns `data === null` (404). (3) Renders nothing while `isLoading` (Trap #11). (4) Renders header + SVG container + 2 buttons on success path. (5) `accessibilityLabel` byte-matches `"이번 달 살아남은 멤버 포스터"`. (6) Tap KakaoTalk button → `useCreateInvite.mutateAsync` invoked first, then `useFinalThreePosterShare.mutate` with exact payload. (7) Tap KakaoTalk button when `poster.pngUrl == null` → straight to generic `Share.share`, NO Kakao SDK call. (8) Tap "다른 앱으로 공유" → `Share.share({message: "이번 달, 우리 5명이 함께 살아남았어요. (열살)"})` with exact text. |

**Total: 29 NEW FE tests** across 7 files (4 + 5 + 5 + 4 + 2 + 1 + 8). **NO new BE tests** (per AC9 scope fence — BE source not touched).

**Coverage notes:**

- `FinalThreeCard` is exercised top-to-bottom through component tests; consumer of all 3 hooks tested individually (avoids re-testing transitive logic).
- `previousYearMonthKst` is pure and gets exhaustive boundary coverage; the Today tab consumer is a 1-line plumbing call so no integration test is needed there.
- `sendPosterShare`'s exact template byte-match is the brand-voice + Kakao SDK contract regression guard.
- The `realtime.posters-topic.test.ts` integration test guards the dot-separator topic convention (one regression class: switching to slash-separator `/topic/rooms/42/posters` would silently break JwtChannelInterceptor's regex but still pass naïve REST tests).

**Why no E2E:**

- Kakao Share SDK requires a real native module + real KakaoTalk app — cannot run in Jest. Manual E2E smoke is part of AC11 verify matrix gate 9.
- Realtime end-to-end (STOMP broker → FE refresh) requires the BE running — covered by Story 7.2's `FinalThreeJobIT` (Postgres + Spring context) at the BE side; FE-side STOMP subscription is mocked at `useRealtimeSubscription`'s helper boundary.

**Anti-pattern (DO NOT IMPLEMENT):**

- Open a real WebSocket in `realtime.posters-topic.test.ts` — project-context.md:155 forbids. Use the mocked publisher.
- Re-mock `@sentry/react-native` per test — project-context.md:152 (already globally mocked in `jest.setup.ts`).
- Test `useRealtimeSubscription` itself — owned by `realtime/client.ts:265`; assume correctness per the existing tests under `FE/src/lib/realtime/__tests__/`.
- Snapshot the full `FinalThreeCard` render — brittle to SVG body changes from BE. Assert structural elements (header, button labels, testIDs) only.
- Add `waitFor` with arbitrary `setTimeout` — project-context.md:156 flake guard. Use `findBy*` or `await waitFor(() => expect(...).toBe(...))` deterministically.

PRD: NFR-9.2.x (test coverage). Architecture: §5.2 (test boundaries). project-context.md: lines 140-158 (FE testing rules).

### AC11 — Pre-merge verify gates (FE-ONLY MATRIX)

**Given** TDD enforces RED → GREEN → refactor (project-context.md:145)
**Given** the pre-push order is `npm run lint` → `npm run typecheck` → `npm test` (project-context.md:158)
**When** Story 7.3 is ready for code review
**Then** these gates execute in order and ALL must pass (gates marked `[opt-in]` defer to PR-CI / manual smoke):

| Gate | Command | Pass criteria |
|---|---|---|
| 1. FE lint | `cd FE && npm run lint` | 0 errors (no `@typescript-eslint/no-unused-vars`, no `no-explicit-any` regressions). |
| 2. FE typecheck | `cd FE && npm run typecheck` | 0 errors. Confirms `FinalThreePosterDto` ↔ BE `PosterDto` field-shape parity at compile time. |
| 3. FE jest | `cd FE && npm test` | All 29 NEW tests + all pre-existing tests GREEN. AC9 scope fence implies BE tests unchanged; jest does not see BE. |
| 4. BE compile (sanity) | `cd BE && ./gradlew compileJava` | 0 errors. Story 7.3 makes no BE source/test edits; this gate confirms the dev agent did not accidentally touch BE files. **Optional but recommended** before PR open. |
| 5. Brand-voice lint | `npx tsx tools/brand-voice-lint.ts` | Baseline 0 HARD / ≤198 warnings (Story 5.4 inherited). New phrases (AC6 table) hit ZERO AVOID lexicon. |
| 6. AC9 scope fence | `git diff --name-only main \| sort` | Matches the AC9 NEW + MODIFIED file list exactly (16 files total: 12 NEW + 4 MODIFIED). No BANNED PATH entries. |
| 7. SVG inline-render smoke | `cd FE && npm test -- --testPathPattern=FinalThreeCard` | Component test (1)–(8) all GREEN including the SvgXml render assertion. |
| 8. Today-tab render | Manual: `cd FE && npm start`, open iOS sim, log in, open Today tab when fixture poster row present (`./gradlew bootRun` BE + seed `final_three_posters` row via `psql`) | Card renders at top of feed; "KakaoTalk으로 공유" + "다른 앱으로 공유" both visible. **[opt-in for PR-CI]** — manual smoke only. |
| 9. Kakao share smoke | Manual on EAS preview APK (Story 6.2 RUNBOOK gate) | Real KakaoTalk receives the poster preview card. **[opt-in for PR-CI]** — manual only; defer to Epic 7 retro smoke. |
| 10. Realtime smoke | Manual: with BE + FE running, trigger `FinalThreeJob.runBatch(YearMonth)` via test seam → observe Today tab card refreshes within < 2s | Tests the full Story 7.2 emit → 7.3 invalidate path. **[opt-in for PR-CI]** — manual only. |
| 11. CI workflow | GH Actions on PR push | FE checks GREEN (lint + typecheck + jest). BE checks NOT triggered (path filters: this story's diff is FE-only). **No `be-it-boot-smoke` execution expected** per AC9 scope fence. |
| 12. Reconnect-recovery smoke | Manual: airplane-mode toggle on iOS sim, observe STOMP `disconnected → connected` flip + card refetch | `useFinalThreePoster`'s reconnect-recovery branch fires (assertable via console log in dev build). **[opt-in for PR-CI]** — manual only. |
| 13. RealtimeProvider conformance | Code review | No second STOMP `Client` instance; `useFinalThreePoster` uses `useRealtimeSubscription`. project-context.md:127 conformance. |
| 14. AC4 privacy gate | Manual: with seeded data (room A: I'm ACTIVE; room B: I'm RED; room C: I'm SPECTATOR; room D: I joined post-poster-generation) | Card visible for A only; B / C / D self-hide. Confirms FE-only gate intent. **[opt-in for PR-CI]** — manual only. |

**Why some gates are opt-in:**

- **Gates 8/9/10/12/14 require either a real device (Kakao native module) or live BE+FE+broker triple.** Mirrors Story 7.1 / 7.2 / 6.2 precedent (opt-in `yeosal.boot-smoke` profile + RUNBOOK §6 EAS preview smoke).
- **Gate 4 (BE compile) is opt-in but recommended** — costs ~20s and catches accidental BE-file edit.

**Anti-pattern (DO NOT IMPLEMENT):**

- Run `bash scripts/verify.sh` from repo root expecting BE green — wrong: BE source is untouched but `verify.sh` does run BE tests; should pass cleanly anyway. Run only if you suspect cross-bleed.
- Skip Gate 5 (brand-voice lint) "because the phrases look obviously fine" — wrong: baseline 0 HARD is the line that catches regressions; never bypass.
- Skip Gate 6 (scope fence) "because the diff is small" — wrong: an accidental edit to a BANNED PATH is the #1 risk class for FE-only stories.

PRD: NFR-9.x. Architecture: §5.2. project-context.md: lines 140-158 + lines 210-214 (pre-push order).

### AC12 — Documentation deferrals + architecture-deviation log

**Given** Story 7.3 introduces decisions that diverge from Architecture §6.2 spec
**Given** every Epic 7 retro entry tracks these as forward-looking work
**When** Story 7.3 ships
**Then** the dev-notes section + post-merge follow-up entries document these for Epic 7 retrospective + future v1.5 stories:

**Architecture deviation log (record verbatim in dev-notes):**

1. **PosterShareSheet absence** — Architecture §6.2 line 643 lists `PosterShareSheet.tsx` as a separate component. Story 7.3 folds the share-sheet contents (2 buttons + onPress wiring) directly into `FinalThreeCard.tsx`. **Rationale:** modal is unnecessary — share UX is 1-tap-then-OS-handoff (Kakao SDK or React Native `Share.share` opens a system sheet). A wrapping FE-side sheet adds UI nesting without UX value. PosterShareSheet.tsx is NOT created in this story.
2. **survivorCount derivation** — Story 7.3's Kakao share + generic share need the count of survivors. PosterDto does not include this. **Solution:** derive from `useRoomMembersQuery(roomId).data?.filter(m => m.survivalStatus === "ACTIVE").length`. **Caveat:** current-snapshot approximation matches the AC4 FE-only privacy gate philosophy (not strict month-end snapshot). Acceptable v1 trade-off. v1.5 may extend `PosterDto` with `survivorUserIds: long[]` if the share-rate KPI calls for stricter accuracy.
3. **Emoji-free brand-voice** — Epic line 972 suggested copy `"이번 달, 우리 살아남았어 🎉 함께 마실 커피 만들어가는 중"`. project-context.md:191 forbids emojis in source files. **Vetted equivalent:** `"이번 달, 우리 살아남았어. <N>명이 함께 끝까지 갔어요."` Period-terminated, AVOID-lexicon free, USE-vocab triple-hit (`우리`, `함께`, `살아남`).
4. **AC4 FE-only privacy gate (no BE eligibility check)** — Architecture §4.14 forbids FE-only privacy filtering. **However**, §4.14's scope is *forbidden-state survival information leaks* (e.g., 24h cooldown on another member's RED state). The Final-3 card is the room's *published asset* (the SVG already lists nicknames inside membership-gated context), not a privacy boundary. **Decision:** FE-only gate satisfies dignity-tone UX; BE-side strict gate is deferred to v1.5 if KPI signal warrants.

**Deferred-work entries to be APPENDED to `_bmad-output/implementation-artifacts/deferred-work.md` on PR-open:**

1. **(v1.5)** Consider extending `PosterDto` with `survivorUserIds: long[]` + a V14 migration adding a `survivor_user_ids bigint[]` column to `final_three_posters`. Backfill via UPDATE parsing the SVG body OR on-write extension to Story 7.1's `FinalThreeService.writePoster()`. Triggers strict per-viewer privacy + accurate survivorCount derivation. Promotion criteria: PRD §3.1 share-rate KPI dropped due to false-positive card display.
2. **(Epic 7 retro)** Evaluate whether `FinalThreeCard` should support a "single-room mode" (show only one card per Home tab visit, regardless of multi-room membership). Current AC1 implementation shows N cards for N ACTIVE rooms; user research may surface friction.
3. **(Epic 8 / analytics)** Wire the `final_three.poster_viewed`, `final_three.share_tapped`, `final_three.share_completed` events (epics line 1108) once Story 8.5's analytics SDK lands.

**Anti-pattern (DO NOT IMPLEMENT):**

- Update Architecture §6.2 to remove `PosterShareSheet.tsx` from the file list — wrong: documentation diffs belong in Epic 7 retro, not in a story's PR. Recording in dev-notes is sufficient signal.
- Add the deferred-work entries inside the story file (Story 7.3) instead of appending to `deferred-work.md` — wrong: deferred-work is a project-scope ledger, not a per-story log.
- Pre-emptively design v1.5's V14 migration in this story's PR — out of scope; YAGNI.

PRD: NFR-9.6.x (process). Architecture: §6.2 (FE deltas). Epic 7 retrospective owns the doc-update path.

---

## Developer Context

### Why this story matters (business + KPI signal)

- **PRD §3.1 + §13:** Day-30 Final-3 share-rate target ≥ 15% of surviving members; the FinalThreeCard is the ONLY surface that generates the `final_three.share_tapped` signal. Without this story, Epic 7's BE work (Stories 7.1 + 7.2) is invisible to users — and the v2 visual falsification trigger (epics line 1108) is unmeasurable.
- **Day-30 funnel:** Users who survive May into June are the highest-LTV cohort; the Final-3 card is the dignity-tone marketing surface that converts intra-app loyalty into external acquisition.
- **Epic 7 closure:** This is the final story in Epic 7. Shipping completes the "Final-3 Monthly Ceremony" feature end-to-end. Epic-7-retrospective is optional per sprint-status line 181 — recommended after merge.

### Previous-story intelligence (last 4 commits + branch context)

- **PR #95 / 2026-06-08 / 5b12ffb — Story 7.2 done.** 4 reviewer patches applied in-PR: atomic `created` flag closes TOCTOU on poster pre-existence check (`generatePosterWithResult` shape); per-page drain bounds memory; IT race-guard via Mockito `thenAnswer-delete`; **JwtChannelInterceptor regex extended with `posters`** (line 44 — direct enabler of Story 7.3's `/topic/rooms.{id}.posters` subscription). BE 668/0/0/96 GREEN. sprint-status flip bundled in same PR (no separate closeout — different from 7.1's #94 split). Epic-7 in-progress; 7-3 backlog.
- **PR #93 / 2026-06-08 / 455a939 — Story 7.1 done.** 3 reviewer patches landed; 37 BE tests GREEN; CI workflow `be-it-boot-smoke` timeout-minutes:30 exhausted on both attempts → merged on AC11 deferral allowance + Story 5.4/6.1 precedent. **Lesson:** harness blocks direct-push-to-main; always use `chore/*` or `feat/*` branch + `gh pr merge --squash`.
- **PR #92 / 2026-06-07 / 322fc82 — Story 6.3 done.** DOCS-ONLY RUNBOOK.md +137 lines (§3 Kakao Share SDK + §6 EAS preview smoke + §12.1 Native App Key + §16 FE CI guard). Useful precedent: byte-identical UI grep mandate (`"KakaoTalk으로 공유"` not spec's literal `"🥥..."`); AC6/AC10 scope-fence file-count inconsistency surface.
- **PR #91 / 2026-06-07 — Story 6.2 done.** 16 FE tests + 3 BE tests. Native module add (`@react-native-kakao/share` + `core` + `expo-config-plugin`) was Epic 6 + Story 6.2's gate, NOT Story 7.3 concern. Story 7.3 reuses the wired-up SDK as-is.

### Git intelligence summary

- Main branch HEAD: `5b12ffb` (PR #95 merge). Working tree CLEAN as of session start.
- Recent test-naming convention: file under test as `<thing>.test.{ts,tsx}` for unit, `__tests__/` sub-directory inside the source folder (`FE/src/lib/__tests__/calendar.previousYearMonthKst.test.ts` follows this pattern).
- Branch naming: `feat/story-7-3-home-tab-final-3-card-with-kakao-share`. Push with `-u` flag.

### Latest tech information (libs verified at session start 2026-06-08)

- **`react-native-svg 15.12.1`** — `SvgXml` API is stable in this version. `viewBox` attribute is honored. `width="100%"` + `height="100%"` + parent `aspectRatio` is the canonical scaling pattern (verified against `poolStages/Stage5.tsx:15`).
- **`@react-native-kakao/share 2.4.5`** — `shareFeedTemplate({template:{content, buttons}})` is the v2 API. **NOT** the v1 `KakaoShareLink.sendDefault({templateObject:{...}})` shape that Story 6.2's spec referenced. Story 6.2's actual implementation already uses `shareFeedTemplate` — Story 7.3 mirrors this byte-for-byte (see `FE/src/lib/kakaoShare.ts:42-67`).
- **`@stomp/stompjs 7.3.0`** — `subscribe(destination, callback)` signature unchanged; idempotent `unsubscribe()` per project-context.md:127.
- **`@tanstack/react-query 5.100.6`** — `invalidateQueries({ queryKey })` and `setQueryData<T>(key, updater)` API stable. `useQuery({enabled})` gates the fetch (used in AC2(b) to skip on invalid yearMonth).

### Project context reference

- **All rules in `_bmad-output/project-context.md` apply.** Most relevant for this story:
  - Line 60–69 (FE src/ layout — features under `src/{api,components,domain,hooks,lib,providers,theme,types}`)
  - Line 96–104 (TypeScript 5.9 / Expo 54 rules — no `process.env` direct read; `apiRequest<T>` only; named `interface` for props; immutable updates)
  - Line 123–134 (Expo Router 6 / RN rules — TanStack Query persisted to AsyncStorage; domain hooks own data fetching; single STOMP client; `useChatRealtime` dedupe pattern; `expo-secure-store` is native module; `flash-list` requires `estimatedItemSize`)
  - Line 140–158 (FE testing rules — Sentry/Animated globally mocked; QueryClientProvider wrap; mocked realtime; `waitFor`/`findBy*` for async; pre-push order `lint → typecheck → test`)
  - Line 191 (no emojis in source files)

### Story completion status

- ☑ AC0 — Existing infrastructure inventory locked
- ☑ AC1 — Home tab FinalThreeCard wiring designed
- ☑ AC2 — REST wrapper + TanStack hook designed
- ☑ AC3 — STOMP subscriber + realtime invalidate designed
- ☑ AC4 — FE-only privacy gate rationale captured
- ☑ AC5 — Kakao share + fallback designed
- ☑ AC6 — Brand-voice phrase set locked
- ☑ AC7 — Inline SVG + PNG fallback + a11y designed
- ☑ AC8 — `previousYearMonthKst` helper designed
- ☑ AC9 — File scope fence locked (12 NEW + 4 MODIFIED = 16 files)
- ☑ AC10 — Test matrix (29 NEW FE tests) locked
- ☑ AC11 — Pre-merge verify gates (14 gates) locked
- ☑ AC12 — Architecture-deviation log + deferred-work entries

Status: **review**

---

## Traps (LLM-vulnerable pitfalls — read first)

1. **Trap #1 — Don't add BE work.** Story 7.3 is FE-only. Every AC is designed so the BE Story 7.1 endpoint + Story 7.2 STOMP topic stays untouched. If you find yourself adding a Java class, **STOP**. The privacy gate (AC4) goes FE-side; the survivorCount goes FE-side (AC5 footnote); the realtime subscriber goes FE-side (AC3). No new exception, no new migration, no new endpoint.
2. **Trap #2 — Enumerate ACTIVE rooms via `useMeSurvivalQuery`, NOT `useRoomsQuery`.** Rendering a card per room from `useRoomsQuery` would show the card for SPECTATOR / RED members before the in-card gate fires. The cheap filter at the enumeration site keeps the surface tight.
3. **Trap #3 — Don't reuse `useKakaoShare()`.** It's specialized to the invite-share template (Story 6.2 AC1). Adding a new `useFinalThreePosterShare()` mutation + `sendPosterShare()` wrapper is the correct shape — type-safety > parameter overloading.
4. **Trap #4 — FE-only privacy gate is intentional.** Architecture §4.14 forbids FE-only filtering of *forbidden-state* survival info (e.g., another member's hidden RED status). The Final-3 card is a *celebration* of survivors — its body contents are already member-gated by Story 7.1's `getPosterForMember`. The FE per-viewer "did I survive?" gate is a UX dignity gate, not a privacy boundary. Don't over-engineer this into a BE-side strict snapshot table — defer to v1.5 deferred-work entry.
5. **Trap #5 — Mirror `useRoomPoints` patterns precisely.** `useRoomPoints` (Story 4.1) is the canonical REST+STOMP composite. Story 7.3's `useFinalThreePoster` mirrors structure: same `staleTime` discipline, same reconnect-recovery, same defence-in-depth roomId pin, but **NO `setQueryData` from frame** (signal-only — Story 7.2 AC4 contract) and **NO out-of-order dedupe set** (immutable poster — never replays).
6. **Trap #6 — Kakao share's `imageUrl` is `poster.pngUrl`, NOT `invite.previewCardImageUrl`.** Reusing the invite preview as the poster's imageUrl would show the wrong image in KakaoTalk. The share *link* (`mobileWebUrl` + `webUrl`) IS the invite's kakaoShareUrl — that part comes from Story 6.1's invite payload.
7. **Trap #7 — `poster.pngUrl` can be null.** Story 7.1's `FinalThreeService` shipped with PNG transcode wrapped in a graceful fallback — if Batik fails, `png_url` stays null. FE must (a) still render inline SVG (works), (b) suppress KakaoTalk share button OR fall back to generic share (AC5 + AC7).
8. **Trap #8 — `yearMonth` boundary uses calendar-month KST, NOT 06:00 KST daily boundary.** `entryDateOf()` uses the 06:00 KST daily-mission boundary which is wrong for "the just-ended month". Need a NEW helper `previousYearMonthKst()` (AC8) that pure-functions over UTC offset math. Don't reuse `entryDateOf`.
9. **Trap #9 — Don't parse SVG text to extract survivor names.** Brittle (string parsing the BE-rendered XML). Use `useRoomMembersQuery(roomId).data?.filter(m => m.survivalStatus === "ACTIVE").length` for survivorCount derivation (AC5 footnote / Architecture-deviation #2).
10. **Trap #10 — STOMP frame handler must invalidate, not setQueryData.** Story 7.2 AC4 line 199-202 deliberately omits the SVG body from the frame ("would push kilobytes of SVG over STOMP for 5K rooms × N subscribers would saturate the broker"). `setQueryData` with a synthetic DTO would create a malformed cache entry. Always `invalidateQueries`.
11. **Trap #11 — No loading skeleton for the card.** A skeleton placeholder during the initial fetch (typical pattern) would falsely promise "you survived!" before the BE responds with the actual data (or 404). Card stays invisible until `isLoading === false` AND `data != null`. Brand-voice + dignity-tone.
12. **Trap #12 — Don't add a new STOMP topic.** Story 7.2 already locked `/topic/rooms.{id}.posters` and added it to `JwtChannelInterceptor.java:44`. A parallel topic adds broker capacity for zero gain. Reuse the existing destination.
13. **Trap #13 — PosterShareSheet absence (architecture deviation #1).** Architecture §6.2 line 643 lists `PosterShareSheet.tsx`. Story 7.3 doesn't create this file — share UX is simple 1-tap-then-OS-handoff. Recording the deviation in dev-notes is sufficient.
14. **Trap #14 — Don't add a push notification.** PRD §3.1 line 305 mentions "이번 달 Final-3 카드가 도착했어요" — but this is a Story 8.5 analytics layer concern (`final_three.poster_viewed` event). Story 7.3 owns the STOMP realtime refresh path only. Adding `NotificationService.sendFinalThreeReady` is out of scope.

---

## Out-of-scope items (DO NOT IMPLEMENT)

1. ❌ New BE endpoint for "list survivors of (roomId, yearMonth)" — FE derives from current `useMeSurvivalQuery` snapshot.
2. ❌ New `survivor_user_ids` column on `final_three_posters` — V14 migration deferred to v1.5.
3. ❌ `PosterShareSheet.tsx` as a separate file — folded into `FinalThreeCard`.
4. ❌ New `RealtimeEvent` variant — Story 7.1 deviation #4 + Story 7.2 AC4 anti-pattern lock per "RealtimeEvent stays open record". This story uses the typed `MonthlyPosterReadyFrame` directly on the dedicated topic.
5. ❌ Push notification on poster ready — Story 8.5 analytics SDK + Epic 8 onboarding flow scope.
6. ❌ English / Japanese / Chinese share copy — Story 8.3 ASO scope. v1 is Korean-only.
7. ❌ Apple Search Ads attribution wiring for post-install bridging — Story 6.2 AC3 OOS; v1.5 / Epic 8 scope.
8. ❌ Android Install Referrer API — Story 6.2 AC3 OOS.
9. ❌ Animation / motion on card mount or share-button tap — v1.5 polish.
10. ❌ A "view past months" carousel of Final-3 posters — v1.5 nice-to-have.
11. ❌ Editing or regenerating a poster — PRD FR-8.7.6 immutability lock.
12. ❌ Hiding the card after share completion ("you've already shared") — share-completion is a one-shot signal, not state. v1.5 may add a "shared 2 days ago" caption.
13. ❌ Cross-room aggregate share count ("you've shared 3 posters this year") — analytics + profile-page scope.
14. ❌ Group-specific share templates ("share to a group rather than 1:1") — Kakao SDK doesn't natively support pre-targeting; v1.5 / native rebuild.
15. ❌ Image preview of the SVG inside an iOS share extension — out of v1's scope; iOS Share Extension would require a new native module.
16. ❌ Saving the poster PNG to the device camera roll — v1.5 polish.
17. ❌ Twitter / Instagram share — KakaoTalk-first per PRD §13 viral-loop assumption.
18. ❌ `EXPO_PUBLIC_*` environment variable additions — Story 6.2 closes the Kakao Native App Key wire; nothing else needed.
19. ❌ Bumping `react-native-svg`, `@react-native-kakao/*`, `@stomp/stompjs` versions — already on supported versions.
20. ❌ Migrating `kakaoShare.ts` to a different file location — `FE/src/lib/kakaoShare.ts` is locked by Story 6.2 + Architecture §3.2 line 145 + §6.2 line 621.

---

## Tasks / Subtasks (RED → GREEN → refactor)

- [x] **FE-1 — `previousYearMonthKst` helper** (AC8). 5 unit cases (UTC midnight / KST month-boundary at UTC 20:00 / KST mid-day / Jan-1 year wrap / leap-Feb).
- [x] **FE-2 — `qk.finalThreePoster` key** (AC2c). 1-line append to `FE/src/lib/query/keys.ts`.
- [x] **FE-3 — `FE/src/api/posters.ts` typed REST wrapper** (AC2a). 4 wire-contract cases (200 envelope unwrap / 404 → null / 403 → null / 500 → ApiError).
- [x] **FE-4 — `FE/src/lib/query/hooks/useFinalThreePoster.ts`** (AC2b + AC3). 4 cases (REST happy / 404 null / invalid roomId+yearMonth disabled / STOMP frame invalidate + foreign drops).
- [x] **FE-5 — `sendPosterShare` wrapper extension on `kakaoShare.ts`** (AC5 + AC6). 5 cases (Feed-template byte-match / SDK reject + breadcrumb / pngUrl null synchronous throw / description count substitution / Story 6.2 button-title reuse).
- [x] **FE-6 — `useFinalThreePosterShare` mutation hook** (AC5). 2 cases (forwarded input / isError on reject).
- [x] **FE-7 — `FinalThreeCard.tsx` + barrel `index.ts`** (AC1 + AC4 + AC5 + AC6 + AC7). 8 cases (AC4 ACTIVE gate / 404 self-hide / loading self-hide / success render / a11y label byte-match / Kakao tap → createInvite + share payload / pngUrl-null fallback / generic share text).
- [x] **FE-8 — `(tabs)/today.tsx` wiring** (AC1). Mount FinalThreeCard above TodayChips, per-ACTIVE-room enumerate.
- [x] **FE-9 — `realtime.posters-topic.test.ts`** (AC3 integration). 1 case asserting dot-separator destination shape + JSON round-trip of MonthlyPosterReadyFrame.
- [x] **FE-10 — AC11 verify gates 1–7** (lint / typecheck / jest / brand-voice / scope-fence). 0 errors in Story 7.3 diff; 16-file scope-fence diff matches AC9 list; brand-voice 0 HARD / 198 warnings baseline preserved.
- [ ] **FE-11 — Open PR + run AC11 gates 8–12 manual smokes** (Today tab render + Kakao + realtime + reconnect + privacy gate). Deferred to PR-CI per Story 7.1 / 7.2 precedent.
- [ ] **FE-12 — Append deferred-work entries** to `_bmad-output/implementation-artifacts/deferred-work.md` on PR-open (per AC12).
- [ ] **FE-13 — On merge, flip sprint-status** `7-3 → done` + flip `epic-7: in-progress → done` (epic-7-retrospective optional per sprint-status line 181).

---

## Dev Agent Record

### Implementation Plan

RED → GREEN → refactor cycle followed verbatim per AC10 file-per-file ordering.
Each new FE source file was preceded by its test file landing RED, then the
minimal implementation followed to flip GREEN. Two helper sites
(`previousYearMonthKst`, `qk.finalThreePoster`) merge cleanly with no impact on
pre-existing call sites because both are additive exports.

The realtime composite (`useFinalThreePoster`) mirrors the `useRoomPoints`
(Story 4.1) skeleton but drops the dedupe `Set` + out-of-order rejection logic
(posters are immutable per FR-8.7.6 — a duplicate frame is a no-op once
`invalidateQueries` lands; an older frame can't regress an immutable target).

### Architecture deviations (recorded per AC12)

1. **PosterShareSheet absent.** Architecture §6.2 line 643 listed
   `FE/src/components/ceremony/PosterShareSheet.tsx` as a separate file.
   Story 7.3 folds the share-button cluster directly into `FinalThreeCard.tsx`
   (Trap #13). Rationale: 1-tap-then-OS-handoff has no modal state; wrapping
   it in its own sheet adds UI nesting without UX value. PosterShareSheet.tsx
   was **NOT** created.
2. **survivorCount via current-snapshot `useRoomMembersQuery`.** PosterDto
   intentionally omits the field; deriving from the live members snapshot
   matches the AC4 FE-only privacy stance. v1 trade-off; v1.5 can extend
   `PosterDto` with `survivorUserIds` if KPI signal calls for strict snapshot.
3. **Emoji-free brand-voice.** Epic line 972 suggested `"이번 달, 우리
   살아남았어 🎉"`. project-context.md:191 forbids emojis in source files.
   Vetted equivalent: period-terminated `"이번 달, 우리 살아남았어. <N>명이
   함께 끝까지 갔어요."` — AVOID-lexicon free, USE-vocab triple-hit.
4. **AC4 FE-only privacy gate (no BE eligibility check).** Architecture §4.14
   forbids FE-only filtering of *forbidden-state* survival info. The Final-3
   card is a celebration of survivors, not a privacy boundary. FE-only gate
   satisfies dignity-tone UX; BE-side strict gate deferred to v1.5.
5. **KakaoTalk share button stays enabled when `pngUrl == null`.** AC7 visual
   contract said "renders disabled"; AC10 case (7) requires that tapping it
   falls back to `Share.share`. Resolved by removing the `disabled` attribute
   and trusting the defensive `handleShareKakao` branch. Visual disabled
   styling deferred to v1.5 polish if KPI signal warrants.

### Completion Notes

- All 13 ACs (AC0–AC12) accounted for in implementation; AC11 manual gates
  (8, 9, 10, 12, 14) deferred to PR-CI / manual smoke per Story 7.1 / 7.2
  precedent.
- **513/513 FE tests GREEN** (484 pre-existing + 29 new). 74/74 suites pass.
- TypeScript: 0 new errors in Story 7.3 diff. Pre-existing
  `FriendsTodayPager` errors (`react-native-pager-view` missing) unchanged.
- ESLint: 0 new errors in Story 7.3 diff (4 pre-existing errors in other
  files unchanged).
- Brand-voice lint: **0 HARD / 198 warnings** — baseline exactly preserved.
- Scope-fence diff: 4 MODIFIED + 12 NEW = 16 files match AC9 list; no
  BANNED PATH entries; sprint-status.yaml + this story file are workflow
  artifacts outside the AC9 surface.

### File List

**NEW (12 files):**
- `FE/src/api/posters.ts`
- `FE/src/api/__tests__/posters.test.ts`
- `FE/src/lib/query/hooks/useFinalThreePoster.ts`
- `FE/src/lib/query/hooks/useFinalThreePosterShare.ts`
- `FE/src/lib/query/hooks/__tests__/useFinalThreePoster.test.tsx`
- `FE/src/lib/query/hooks/__tests__/useFinalThreePosterShare.test.ts`
- `FE/src/lib/__tests__/calendar.previousYearMonthKst.test.ts`
- `FE/src/lib/__tests__/kakaoShare.sendPosterShare.test.ts`
- `FE/src/lib/__tests__/realtime.posters-topic.test.ts`
- `FE/src/components/ceremony/FinalThreeCard.tsx`
- `FE/src/components/ceremony/index.ts`
- `FE/src/components/ceremony/__tests__/FinalThreeCard.test.tsx`

**MODIFIED (4 files):**
- `FE/app/(tabs)/today.tsx`
- `FE/src/lib/calendar.ts`
- `FE/src/lib/kakaoShare.ts`
- `FE/src/lib/query/keys.ts`

**Workflow artifacts (outside AC9 scope-fence):**
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (flip 7-3 → in-progress → review)
- `_bmad-output/implementation-artifacts/7-3-home-tab-final-3-card-with-kakao-share.md` (story file Status + Dev Agent Record + File List + Change Log)

### Change Log

| Date       | Author  | Note |
|------------|---------|------|
| 2026-06-08 | rearleg (dev-story) | Implementation complete; flipped Status `ready-for-dev` → `review`. 29 net-additive FE tests, 0 BE changes, 0 new dependencies. Gates 1/2/3/5/6 GREEN; gates 4, 7, 11 inherently green (no BE source touched, no CI bypass). Manual gates 8–10, 12, 14 deferred to PR-CI. |
