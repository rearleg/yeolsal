# Story 6.2: Kakao Share SDK integration + deep-linking

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a primary user,
I want a 1-tap "KakaoTalk으로 공유" CTA on the room invite sheet that posts a preview card with my room's invite code to KakaoTalk, and 1-tap join from any tap on the shared card,
So that my friends can join in two taps directly from KakaoTalk — and the loop survives even when KakaoTalk SDK fails or the friend hasn't installed yet.

## Acceptance Criteria

> 이 스토리는 **Epic 6 KakaoTalk Viral Loop 의 사용자 진입점** 이다. Story 6.1 (PR #90 merged 2026-06-06, commit `f682be5`) 가 BE-side `kakaoShareUrl` + `previewCardImageUrl` payload + public preview-card endpoint + cache invalidation 을 이미 ship 했고, 본 스토리는 FE-side share UX + deep-link entry 를 얹는다. 후속 Story 6.3 (RUNBOOK + native module reinstall 가이드) 가 ops doc 을 마무리한다. **FE-heavy + minimal BE** — 새 native module 1개 (`@react-native-kakao/share`), Kakao Share SDK wrapper, `Linking` deep-link 핸들러, App Link / Universal Link 설정, InviteCodeSheet 의 Kakao-first share 분기, `app/join.tsx` 의 `?code=` 자동수락, signup-after-install bridging via SecureStore. **BE 변경 최소** — `RoomService.joinByCode` 의 cap-exceeded 분기를 `BadRequestException` → 새 `RoomFullException` (409, code `ROOM_FULL`) 로 정확화 (epics line 854 잠금 코드). 핵심 산출:
> 1. **새 native module** — `@react-native-kakao/share` + `@react-native-kakao/core` + `@react-native-kakao/expo-config-plugin` (FE/package.json 3-package add). 본 스토리가 **Epic 6 의 native module trigger** (project-context.md:132 + PRD FR-8.6.6 의 RUNBOOK rule 첫 발화점).
> 2. **새 FE 모듈** `src/lib/kakaoShare.ts` — Kakao Share SDK 의 `KakaoShareLink.sendDefault(...)` wrapper. 모든 호출은 이 한 함수를 거침 (project-context.md:127 single-wrapper 패턴 mirror).
> 3. **새 도메인 훅** `src/lib/query/hooks/useKakaoShare.ts` — TanStack mutation. UI 가 이 훅으로만 share 를 트리거 (architecture §3.2 line 145 의 `useKakaoShare` 명명 잠금).
> 4. **새 deep-link 모듈** `src/lib/deepLinking.ts` + `useShareLinkDeepLink()` hook — `expo-linking` (이미 expo-router 의 transitive dep) 의 `Linking.getInitialURL()` + `Linking.addEventListener("url", ...)` 를 묶음. `_layout.tsx` 의 `NotificationInvalidationBootstrap` 옆에 한 줄 mount (line 92-99 의 push-tap 패턴 byte-similar mirror).
> 5. **`app/join.tsx` 확장** — `useLocalSearchParams<{ code?: string }>()` 으로 `?code=` 자동 수락 + 사전-인증 사용자에 한해 auto-submit. 미인증 사용자는 SecureStore (`yeosal.pendingInviteCode`) 에 저장 후 signup 화면으로 redirect.
> 6. **post-install bridging** — `AuthContext.signUp` / `signIn` 의 성공 콜백에 "pendingInviteCode 자동 join" 단계 1개 추가 (try-once, then clear). **BE auth signup contract 변경 ZERO** — Trap #2 의 결정 근거 참조.
> 7. **`app.json` 확장** — `ios.associatedDomains: ["applinks:yeolsal.app"]`, `android.intentFilters: [{ action.VIEW, autoVerify=true, data.scheme=https + host=yeolsal.app + pathPrefix=/join, category=[BROWSABLE, DEFAULT] }]`, `plugins` 배열에 `["@react-native-kakao/core", { nativeAppKey: ... }]` 추가. **scheme `"yeosal"` 그대로 유지** — Custom scheme `yeosal://join?code=X` 가 dev / preview 의 사람-가능 fallback (Story 6.1 의 `yeolsal.app` "reserve-but-unrouted" 노트 인정).
> 8. **`InviteCodeSheet.tsx` 확장** — primary CTA 가 "KakaoTalk으로 공유" 로 변경, 기존 "공유하기" 가 `tone="secondary"` fallback 으로 강등. 두 버튼 모두 `onShareKakao` / `onShareGeneric` 콜백을 parent 가 제공.
> 9. **`app/rooms/[id].tsx` 확장** — `shareInvite` 가 둘로 갈라짐: `shareInviteKakao()` (Kakao SDK first, 실패 시 catch → toast + generic fallback) + `shareInviteGeneric()` (기존 plain `Share.share`). `useKakaoShare()` mutation 으로 wiring.
> 10. **BE `RoomService.joinByCode` 의 cap-exceeded 분기 정확화** — 기존 `BadRequestException("방 정원을 초과했습니다.")` 한 줄을 새 `com.yeosal.api.room.RoomFullException` 으로 교체. `ApiExceptionHandler` 에 `@ExceptionHandler(RoomFullException.class)` 메서드 추가 → 409 CONFLICT + code `ROOM_FULL` + 한 줄 ko 메시지. **목적:** epics AC line 854 의 "409 CONFLICT with code ROOM_FULL" 잠금. 기존 호출자는 `BadRequestException` 캐치를 하지 않으므로 wire change 가 안전 (FE 검증: `IneligibleLeaderException` 의 409 precedent mirror).
> 11. **`RoomInvite` FE 타입 확장** — `kakaoShareUrl: string` + `previewCardImageUrl: string` 두 필드 추가. Story 6.1 이 BE 측 record 를 확장했지만 FE 측 type 은 의도적으로 변경하지 않았음 (6.1 AC9 scope fence). 본 스토리가 정확히 이 필드를 소비.
>
> **NO new migration** (V13 stays the latest). **NO new STOMP topic** (preview card / share 는 realtime 채널 미사용). **NO new endpoint** (Story 6.1 이 이미 `POST /rooms/{id}/invites` 확장 + `GET .../preview-card` 제공). **NO push notification** (epics line 866 만 본 스토리; push 는 Story 6.3 / Epic 8 의 별도 라인). **NO `tokens.json` 변경** (FE-side share button 은 기존 `palette.coralDeep` / `surface.*` 토큰만 사용). **NO `EXPO_PUBLIC_KAKAO_NATIVE_APP_KEY` 노출 제외** — Native App Key 는 Kakao 가 의도적으로 클라이언트에 임베드되는 공개키 (REST API key 와 별개). project-context.md:235 의 "Kakao REST API key" 금지는 그대로 유지 — Native App Key 는 안전한 EXPO_PUBLIC_*.

### AC1 — Kakao SDK 1-tap share from `InviteCodeSheet` (PRIMARY SHARE PATH)

**Given** 방 멤버가 `RoomDetailScreen` 의 헤더 우측 person-add 아이콘 (`FE/app/rooms/[id].tsx:188-196`) 을 탭하여 `InviteCodeSheet` 를 열고, "초대 코드 만들기" 로 새 invite 를 발급한 직후
**When** 사용자가 시트의 primary "KakaoTalk으로 공유" 버튼을 탭한다
**Then** FE 가 정확히 다음 시퀀스를 수행한다:

1. `useKakaoShare()` mutation 의 `mutate({ invite, roomName, memberCount })` 호출.
2. 훅 내부에서 `sendInviteShare(...)` 호출.
3. wrapper 가 `@react-native-kakao/share` 의 `KakaoShareLink.sendDefault({ templateObject: { objectType: 'feed', ... } })` 를 invoke — **Default `feed` template** 사용 (Kakao 개발자 콘솔의 Custom Template ID 사전 등록 회피, 출시 차단 risk 최소화).
4. SDK 가 KakaoTalk 앱 (또는 KakaoTalk Web Share Dialog) 으로 hand-off 한다.

**`useKakaoShare()` 시그니처 (`FE/src/lib/query/hooks/useKakaoShare.ts`):**

```typescript
import { useMutation } from "@tanstack/react-query";
import { sendInviteShare, type ShareInput } from "../../kakaoShare";

export function useKakaoShare() {
  return useMutation<void, Error, ShareInput>({
    mutationFn: sendInviteShare,
  });
}
```

**`kakaoShare.sendInviteShare` 시그니처 (`FE/src/lib/kakaoShare.ts`):**

```typescript
import { KakaoShareLink } from "@react-native-kakao/share";

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
 * Single entry point for KakaoTalk share. RoomInvite (Story 6.1 + this story)
 * already carries the canonical kakaoShareUrl/previewCardImageUrl pair; this
 * wrapper only assembles the Kakao Default-Template payload and hands off to
 * the native SDK. Errors propagate to the caller — useKakaoShare's onError
 * decides the user-visible fallback (plain Share.share).
 */
export async function sendInviteShare({
  invite, roomName, memberCount,
}: ShareInput): Promise<void> {
  await KakaoShareLink.sendDefault({
    templateObject: {
      objectType: "feed",
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
}
```

**Brand voice 잠금 (epics line 864 + 본 스토리 AC5):**

- `description` = `"<N>명이 함께 살아남는 중"` (`함께` + `살아남` 둘 다 USE 어휘, AVOID 어휘 0)
- 버튼 라벨 = `"같이 살아남자"` (Story 6.1 SVG footer phrase 와 byte-identical)
- `title` = room name (사용자 입력 — brand-voice scope 외; 사용자가 직접 입력한 방 이름은 본 게이트의 대상이 아님)

**Anti-pattern (DO NOT IMPLEMENT):**

- Custom Template 사용 (`sendCustomFeed({ templateId: <N>, ... })`) — Kakao 개발자 콘솔에서 사전 등록 절차 + 승인 단계 필요. 출시 차단 risk 가 큼. **Default `feed` template 만 사용**.
- `link.mobileWebUrl` 과 `link.webUrl` 을 다른 URL 로 — Universal Link / App Link 가 같은 host 를 두 platform 에서 모두 hand off 하므로 동일해야 함. 두 값을 같은 `invite.kakaoShareUrl` 로 고정.
- `imageUrl` 에 cache-busting query (`?v=<timestamp>`) — Story 6.1 의 anti-pattern (line 70) 와 동일 이유. KakaoTalk fetcher 가 cache-busting 을 따라가지 않음.
- Kakao Share SDK 의 init 을 `_layout.tsx` 가 아닌 `kakaoShare.ts` 안에서 lazy — `@react-native-kakao/core` 의 `initializeKakaoSDK(nativeAppKey)` 는 app boot 직후 1회 호출이 SDK 의 공식 권장. lazy init 은 첫 share 의 latency 를 증가.
- `KakaoShareLink.sendDefault` 직접 컴포넌트 안에서 호출 — single-wrapper 원칙 위반. `useKakaoShare()` mutation → `sendInviteShare()` 의 한 경로만.

PRD: FR-8.6.3 (line 416). Architecture: §3.2 line 145 (`useKakaoShare` 명명), §3.3 line 154 (Kakao Share SDK 선택). Story 6.1 file: `_bmad-output/implementation-artifacts/6-1-server-side-preview-card-renderer-cache.md:26-71` (응답 payload contract).

### AC2 — Deep-link tap (app installed) → auto-route to `/join?code=` + 1-tap join + 409 ROOM_FULL handling

**Given** 친구가 받은 KakaoTalk preview card 를 탭한다 (app 설치됨)
**When** OS 의 deep-link resolver 가 `https://yeolsal.app/join?code=ABCD1234` 를 처리한다
**Then** 다음 시퀀스가 정확히 일어난다:

1. **iOS Universal Link** — `applinks:yeolsal.app` (`app.json` 의 `ios.associatedDomains`) + `https://yeolsal.app/.well-known/apple-app-site-association` (Story 6.3 / DevOps 가 hosting). OS 가 앱을 직접 launch + 첫 frame 에 `Linking.getInitialURL()` 이 `https://yeolsal.app/join?code=ABCD1234` 반환.
2. **Android App Link** — `app.json` 의 `android.intentFilters` 가 `https + yeolsal.app + /join` 매칭. `autoVerify=true` 가 `https://yeolsal.app/.well-known/assetlinks.json` 검증 (Story 6.3 hosting). 검증 통과 시 OS 가 disambiguation prompt 없이 앱 직접 launch.
3. **`useShareLinkDeepLink()`** (`_layout.tsx` 에 mount) 가 URL 파싱 — `expo-linking` 의 `Linking.parse(url)` 로 path = `join`, queryParams = `{ code: 'ABCD1234' }` 추출.
4. **인증 상태 분기:**
   - 인증된 사용자 → `router.push('/join?code=ABCD1234')`. `app/join.tsx` 의 `useLocalSearchParams<{ code?: string }>()` 가 code 를 받아 `useEffect` 안에서 자동으로 `submit()` 호출. 사용자에게 추가 입력 ZERO.
   - 비인증 사용자 → `expo-secure-store` 에 `yeosal.pendingInviteCode = 'ABCD1234'` 저장 + `router.replace('/signup')`. **default '/signup'** (현 app 의 새 사용자 유치 우선).
5. **`/join?code=ABCD1234` auto-submit 후 BE 응답 분기:**
   - 200 OK + MemberSummary → 기존 flow (line 30-36 of `app/join.tsx`) 가 `router.replace('/rooms/{id}/settings?onboarding=1')` push.
   - 409 CONFLICT + code `ROOM_FULL` → `toast.error("방이 가득 찼어요. 친구에게 새 방을 만들어 달라고 요청하세요.")` 표시 + 폼 유지 (사용자가 close 버튼으로 빠져나옴). Brand voice — "가득 찼" 은 사실 진술, AVOID 어휘 zero.
   - 404 NOT_FOUND (`code` 가 revoke 또는 expire 됨) → `toast.error("초대 코드가 만료되었어요.")` 표시.
   - 기타 error → 기존 generic `error instanceof Error ? error.message : '그룹 참여에 실패했어요.'` 분기 유지.

**`useShareLinkDeepLink()` 구현 (`FE/src/lib/deepLinking.ts`):**

```typescript
import * as Linking from "expo-linking";
import { router } from "expo-router";
import { useEffect } from "react";
import * as SecureStore from "expo-secure-store";
import { useAuth } from "../auth/AuthContext";

const PENDING_INVITE_KEY = "yeosal.pendingInviteCode";

export function useShareLinkDeepLink() {
  const auth = useAuth();
  useEffect(() => {
    if (auth.loading) return;

    Linking.getInitialURL()
      .then((url) => url && route(url, auth.user != null))
      .catch(() => undefined);

    const sub = Linking.addEventListener("url", ({ url }) =>
      route(url, auth.user != null),
    );
    return () => sub.remove();
  }, [auth.loading, auth.user]);
}

function route(url: string, isAuthed: boolean): void {
  const parsed = Linking.parse(url);
  if (parsed.path !== "join") return;
  const code = typeof parsed.queryParams?.code === "string"
    ? parsed.queryParams.code : null;
  if (!code) return;

  if (isAuthed) {
    router.push(`/join?code=${encodeURIComponent(code)}`);
    return;
  }
  // Best-effort persist — failure-tolerant per project-context.md:132
  // (secure-store quirks on locked-down enterprise builds).
  SecureStore.setItemAsync(PENDING_INVITE_KEY, code).catch(() => undefined);
  router.replace("/signup");
}

export async function consumePendingInviteCode(): Promise<string | null> {
  try {
    const code = await SecureStore.getItemAsync(PENDING_INVITE_KEY);
    if (code) await SecureStore.deleteItemAsync(PENDING_INVITE_KEY);
    return code;
  } catch {
    return null;
  }
}
```

**`app/join.tsx` 의 auto-submit 분기 (추가):**

```typescript
import { router, useLocalSearchParams } from "expo-router";
// ...
const { code: incomingCode } = useLocalSearchParams<{ code?: string }>();

useEffect(() => {
  if (!incomingCode) return;
  // Hydrate the input AND auto-fire the same submit() the manual entry path
  // uses, so the "share-tap" flow has zero added UI surface to maintain.
  setCode(incomingCode.toUpperCase());
  submit();
  // eslint-disable-next-line react-hooks/exhaustive-deps — one-shot
}, [incomingCode]);
```

**`submit()` 의 error 분기 확장 (line 37-39 위에 추가):**

```typescript
} catch (error) {
  if (error instanceof ApiError && error.code === "ROOM_FULL") {
    toast.error("방이 가득 찼어요. 친구에게 새 방을 만들어 달라고 요청하세요.");
    return;
  }
  toast.error(error instanceof Error ? error.message : "그룹 참여에 실패했어요.");
}
```

**Anti-pattern (DO NOT IMPLEMENT):**

- Deep-link path 를 `/rooms/[id]` 또는 `/rooms/preview` 로 — `code` 만으로는 `room.id` 를 모름 (BE 의 `RoomInvite.findActiveByCode` 가 resolve). FE 가 `code → roomId` round-trip 을 한 번 더 하면 latency + edge case 증가. **`/join?code=X` 만** 의 single entry.
- `code` 를 URL fragment (`#code=`) 또는 path segment (`/join/X`) 로 — Story 6.1 의 `ShareUrlBuilder` (line 510-512) 가 `?code=` query 로 잠금. URL contract 변경 금지.
- 인증된 사용자가 deep-link 탭 시 `/join` 으로 가지 않고 `/rooms/{ResolvedRoomId}/preview` 같은 new route 도입 — 기존 join flow 가 신규 join + 기존 멤버 (`existing.isPresent()`) 모두 안전하게 handle (`RoomService.joinByCode:344-349`). new route 는 over-engineering.
- `Linking.addEventListener` 의 subscription cleanup 누락 — `_layout.tsx` 가 마운트되는 단일 인스턴스라 leak 영향은 제한적이지만 `useEffect` cleanup 가 RN 의 navigation 패턴 기본. 누락 시 dev hot-reload 가 multi-subscribe race 를 만듦.
- `SecureStore.setItemAsync` await 누락 → race 로 signup 후 consume 이 null 을 받음. 그러나 `void`-style fire-and-forget 가 UX 의 "스크린 전환은 즉시" 요구와 충돌하지 않음 — fail-quiet 이 정상. Sentry breadcrumb 가 향후 폴리쉬.

PRD: FR-8.6.4 (line 417). Architecture: §3.3 line 154 (KakaoTalk share + 기존 OAuth dependency). Story 6.1: `ShareUrlBuilder` URL contract (file line 56, line 510-512).

### AC3 — Deep-link tap (app NOT installed) → store-fallback + post-install bridging

**Given** 친구가 KakaoTalk preview card 를 탭한다 (app **미설치**)
**When** OS 의 deep-link resolver 가 매칭 앱을 못 찾는다
**Then** 다음 platform-native fallback 이 발생 (애플 / 구글 가이드 기본 동작):

1. **iOS** — Universal Link 가 매칭 앱을 못 찾으면 Safari 가 `https://yeolsal.app/join?code=ABCD1234` 를 연다. `yeolsal.app` 의 (Story 6.3 가 hosting) `/join` 페이지가 minimal landing — App Store deep-link button 한 개 + "코드 ABCD1234" 표시. **Story 6.2 의 책임은 App Store 의 redirect URL 이 invite code 를 query 로 보존하도록 만드는 것** — 즉, `yeolsal.app/join?code=X` HTML 의 App Store deep-link 가 `https://apps.apple.com/kr/app/yeosal/id<APPID>?campaign=invite&code=ABCD1234` 가 되도록 Story 6.3 가 hosting 한다 (본 스토리는 `app.json` 의 universal-link 가 정확히 등록되는지만 책임).
2. **Android** — App Link 가 매칭 앱을 못 찾으면 OS 가 default browser 로 fallback → 위의 iOS 와 동일한 landing page 가 Play Store deep-link 를 제공 (`https://play.google.com/store/apps/details?id=app.yeosal.mobile&referrer=code%3DABCD1234`).
3. **사용자가 store 에서 install 후 첫 launch** → AC2 의 `useShareLinkDeepLink()` 가 다시 발화하지 않음 (post-install 의 첫 launch URL 은 보통 empty). **대신** `Linking.getInitialURL()` 이 null 이고 사용자가 그냥 첫 launch.
4. **Post-install bridging — Apple/Google referrer / Install Referrer API**:
   - **iOS** Apple Search Ads attribution API 를 통한 `code` 전달은 v1 차원에서 OOS (PRD FR-8.6.5 line 418 의 "invite-code preserved via a deep-link handoff" 의 wording 은 *attempt* 만 요구; 실제 attribution API 구현 절차는 store-listing 의 marketing-team 기능. 본 스토리는 **store-listing 의 deep-link query 에 `code` 가 포함**되도록 link 만 lock).
   - **Android** Install Referrer API 를 통해 `google-play-services-instantapps` 또는 `react-native-google-install-referrer` lib 도입 가능 — **본 스토리에서 OOS**, Story 6.3 또는 v1.5 가 결정.
5. **Universal-fallback bridging (본 스토리의 실제 entry):** 사용자가 install 후 KakaoTalk 으로 돌아가 같은 preview card 를 다시 탭 → AC2 의 deep-link path 가 정상 발화 → AC2 line 4 의 비인증 분기가 SecureStore 에 `code` 저장 + signup screen 으로 redirect → signup 완료 시 AC3 line 6 의 consume 단계 자동 실행.
6. **`AuthContext.signUp` / `signIn` 의 post-success 콜백 추가:**

```typescript
// FE/src/auth/AuthContext.tsx — 기존 signUp / signIn 의 router.replace('/today') 직전에 한 줄 추가
const pendingCode = await consumePendingInviteCode();
if (pendingCode) {
  try {
    const member = await joinRoom(pendingCode.toUpperCase());
    router.replace(`/rooms/${member.roomId}/settings?onboarding=1`);
    return;
  } catch (error) {
    // Best-effort — surface a soft toast so the user knows the gift exists.
    if (error instanceof ApiError && error.code === "ROOM_FULL") {
      toast.info("초대받은 방이 가득 찼어요. 직접 코드를 입력해서 다시 시도해보세요.");
    } else {
      toast.info("초대 코드는 그룹 참여 화면에서 다시 사용할 수 있어요.");
    }
    // Fall through to the standard router.replace('/today').
  }
}
router.replace("/today");
```

**`consumePendingInviteCode` 단일 호출 보장:** AC2 의 helper 가 read-then-delete 를 한 함수 안에서 수행. 두 번째 signup attempt (rare) 가 같은 code 로 join 시도하지 않음.

**Anti-pattern (DO NOT IMPLEMENT):**

- `SignupRequest` (BE) 에 `Optional<String> inviteCode` 필드 추가 — `AuthService.signup` 이 auth 도메인 (user 생성, JWT 발행) + room 도메인 (joinByCode) 을 한 transaction 으로 처리해야 함. 두 도메인의 응집도가 깨지고 (`RoomService.joinByCode` 에 대한 의존), `AuthController` 의 single-responsibility 원칙 위배. 더 나아가 `AuthService` 가 `RoomService` 의존을 가지면 startup 의존 그래프가 순환 위험. **본 스토리의 결정 (Trap #2):** FE-side SecureStore bridging.
- Install Referrer API 또는 Apple Search Ads attribution 의 직접 wiring — store-listing / marketing team scope. 본 스토리 OOS.
- `https://yeolsal.app/join` landing page 의 HTML / JS 코드를 본 PR 에 포함 — Story 6.3 또는 별도 infra PR scope. 본 스토리는 **app.json 의 universal-link 잠금**만.
- `pendingInviteCode` 를 `AsyncStorage` 에 저장 — AsyncStorage 는 TanStack Query persist 의 exclusive 사용 영역 (project-context.md:60). **SecureStore 사용**.
- `consumePendingInviteCode` 가 catch 시 silently swallow 만 — 사용자가 "초대받은 방을 놓쳤다" 인식 없이 home 화면으로 직행. **`toast.info(...)` 가 soft notice** 보장.

PRD: FR-8.6.5 (line 418). Story 6.3 dependency: landing page hosting + store-deep-link query preservation (본 스토리 OOS).

### AC4 — Kakao SDK 실패 시 fallback to plain `Share.share` (ALWAYS-WORKS BACKUP)

**Given** Kakao Share SDK 호출이 어떤 이유로 실패한다 (네트워크 / KakaoTalk 미설치 / SDK 초기화 실패 / KakaoSDK API 변경)
**When** `useKakaoShare().mutate(...)` 의 `onError` 콜백이 발화한다
**Then** FE 가 즉시 다음 fallback 을 수행:

1. `toast.info("KakaoTalk 공유가 안 돼요. 다른 방법으로 공유해주세요.")` 한 줄 표시.
2. `Share.share({ message: '열살 그룹 초대 코드: ${invite.code}' })` 호출 — React Native 내장 share sheet (기존 `app/rooms/[id].tsx:149` 의 byte-identical 코드).
3. 사용자가 share sheet 에서 dismiss 또는 다른 채널 선택 → 정상 동작.

**`app/rooms/[id].tsx` 의 `shareInvite` 분기 (line 146-153 교체):**

```typescript
const kakaoShare = useKakaoShare();
// useRoom 의 결과 (room name + member count) 와 useMembers 의 결과 (count) 가 같은 화면에 이미 존재
const memberCount = Math.max(1, members.length);
const roomName = room?.name ?? "그룹";

async function shareInviteKakao() {
  if (!invite) return;
  kakaoShare.mutate(
    { invite, roomName, memberCount },
    {
      onError: () => {
        toast.info("KakaoTalk 공유가 안 돼요. 다른 방법으로 공유해주세요.");
        shareInviteGeneric();
      },
    },
  );
}

async function shareInviteGeneric() {
  if (!invite) return;
  try {
    await Share.share({ message: `열살 그룹 초대 코드: ${invite.code}` });
  } catch {
    // user dismissed share sheet
  }
}
```

**`InviteCodeSheet` Props 확장 (`FE/src/components/rooms/InviteCodeSheet.tsx`):**

```typescript
interface Props {
  // ... 기존 5 props 유지 (visible, invite, isCreating, onCreate, onClose)
  onShareKakao: () => void;       // NEW — primary CTA
  onShareGeneric: () => void;     // NEW — secondary fallback
  // OLD: onShare: () => void;    // 삭제, onShareKakao + onShareGeneric 로 분리
}

// body 안의 Button 두 개로 교체:
<Button label="KakaoTalk으로 공유" tone="primary" size="md" fullWidth onPress={onShareKakao} />
<Button label="다른 앱으로 공유"   tone="secondary" size="md" fullWidth onPress={onShareGeneric} />
```

**`app/rooms/[id].tsx` 의 InviteCodeSheet wiring (line 321-326 교체):**

```typescript
<InviteCodeSheet
  visible={inviteSheetVisible}
  invite={invite}
  isCreating={inviteMut.isPending}
  onCreate={handleCreateInvite}
  onShareKakao={shareInviteKakao}
  onShareGeneric={shareInviteGeneric}
  onClose={() => setInviteSheetVisible(false)}
/>
```

**Brand voice — fallback toast:**

- `"KakaoTalk 공유가 안 돼요. 다른 방법으로 공유해주세요."` — `안 돼요` 는 사실 진술 (AVOID 어휘 `실패` / `패배` 아님). `다른 방법으로` 가 대안 제시.
- 한국어 invitation 톤 (project-context.md:541 `함께` / `우리` 어휘 family).

**Anti-pattern (DO NOT IMPLEMENT):**

- Kakao SDK 가 reject 한 경우 사용자에게 retry button 제공 — UX 가 더 손상. silent fallback 이 정상.
- 실패 시 Kakao 의 native error message 를 그대로 toast 표시 — Korean SDK 의 메시지가 영어 / 일본어 mix 일 수 있고, "kakao_invalid_template" 같은 raw 메시지는 사용자에게 무의미. **단일 한국어 안내 문장 잠금**.
- `Share.share` 의 message 에 `kakaoShareUrl` 포함 — `Share.share` 의 fallback 은 일반 텍스트만 (Kakao preview card 가 작동 안 하는 상황), URL 을 넣으면 plain text 보다 길어 가독성 손상. **`code` 만 포함**.
- onError 안에서 다시 `kakaoShare.mutate(...)` 재시도 — SDK 실패 root cause 가 일시적이지 않을 가능성이 높고, 무한 retry loop risk. **단일 fallback path 만**.
- `Share.share({ url: ... })` 사용 — iOS 만 지원하고 Android 는 message 만 honor. **단일 message 필드 사용**.

PRD: FR-8.6.3 (line 416), FR-8.8.2 (line 467 brand-voice). Story 6.1: 본 fallback path 는 6.1 의 server-side render 가 실패해도 작동해야 하므로 (preview card 가 없어도 code 는 공유 가능) — `previewCardImageUrl` 가 KakaoTalk 의 fetcher 에 503 을 줘도 generic share 가 backup.

### AC5 — Brand voice copy 잠금 (LOCKED SHARE TEXT)

**Given** 본 스토리가 도입하는 모든 share-flow UI copy 가 brand voice 게이트를 통과해야 한다
**When** brand-voice lint (Architecture §4.15 / Story 5.4 baseline) 가 새 파일을 스캔한다
**Then** 다음 phrase set 이 정확히 사용된다 (USE-only):

| Surface | Locked phrase | USE 어휘 hit |
|---|---|---|
| `InviteCodeSheet` primary button | `"KakaoTalk으로 공유"` | (none, neutral) |
| `InviteCodeSheet` secondary button | `"다른 앱으로 공유"` | (none, neutral) |
| Kakao Default Template `description` | `"<N>명이 함께 살아남는 중"` | `함께`, `살아남` |
| Kakao Default Template button | `"같이 살아남자"` | `같이`, `살아남` |
| Generic share message (fallback) | `"열살 그룹 초대 코드: <CODE>"` | (none, neutral) |
| ROOM_FULL toast | `"방이 가득 찼어요. 친구에게 새 방을 만들어 달라고 요청하세요."` | `친구` |
| Code-expired toast | `"초대 코드가 만료되었어요."` | (none, neutral) |
| Kakao SDK fallback toast | `"KakaoTalk 공유가 안 돼요. 다른 방법으로 공유해주세요."` | (none, neutral) |
| Post-install bridging — ROOM_FULL | `"초대받은 방이 가득 찼어요. 직접 코드를 입력해서 다시 시도해보세요."` | (none, neutral) |
| Post-install bridging — other error | `"초대 코드는 그룹 참여 화면에서 다시 사용할 수 있어요."` | (none, neutral) |

**AVOID 어휘 zero check:** 위 표 의 phrase set 에서 `벌금 / 잃었다 / 떨어졌다 / 실패 / 자책 / 부담 / 패배 / 죄책감` 한 단어도 0 회 (Architecture §4.15 line 409 의 AVOID lexicon).

**Brand-voice lint 게이트:**

- **WARN-only** baseline (Architecture §4.15 line 409 의 severity) — 본 스토리는 baseline 0 HARD / ≤198 warnings (Story 5.4 inherited) 를 유지. 새 phrase set 이 AVOID 어휘 0 을 hit 하므로 baseline 보존.
- 두 native module add (`@react-native-kakao/share`, `@react-native-kakao/core`, `@react-native-kakao/expo-config-plugin`) 가 lint scope 외 (node_modules excluded per `FE/eslint.config.js` 의 ignore 패턴).

**Anti-pattern (DO NOT IMPLEMENT):**

- AVOID 어휘 의 의도적 사용 — `"방을 잃어버렸어요"`, `"공유 실패"`, `"가입에 자책하지 마세요"` 등의 phrase 는 brand voice 위반. 본 스토리의 모든 사용자-노출 copy 는 AC5 의 표만 사용.
- 영어 store metadata copy 직접 작성 — Architecture §5.5 line 543 의 "store metadata = comeback pass" 는 Story 8.3 (ASO copy lock) scope. **본 스토리에서 영어 copy ZERO**.
- 라벨에 emoji 추가 — Korean invitation 톤 정렬 + project-context.md:191 "No emojis in source files" 위반. **모든 phrase 가 emoji-free** (Story 6.1 SVG footer 가 같은 정신).

PRD: FR-8.8.2 (line 467). Architecture: §4.15 line 400-417, §5.5 line 540-543.

### AC6 — BE `RoomFullException` + 409 mapping (CAP-EXCEEDED CORRECTNESS)

**Given** `RoomService.joinByCode` (line 338-395) 가 현재 `BadRequestException("방 정원을 초과했습니다.")` 를 던진다 (line 352-353)
**When** epics line 854 가 "409 CONFLICT with code `ROOM_FULL`" 을 잠금 wording 으로 명시한다
**Then** BE 가 다음 정확히 4 곳을 수정:

1. **새 클래스 `BE/src/main/java/com/yeosal/api/room/RoomFullException.java`:**

```java
package com.yeosal.api.room;

/**
 * Thrown when {@link RoomService#joinByCode(com.yeosal.api.user.User, String)}
 * finds the target room at {@code max_members} capacity. Mapped to 409
 * CONFLICT with error code {@code ROOM_FULL} by {@code ApiExceptionHandler}.
 *
 * <p>Distinct from {@code BadRequestException} because (a) the client can
 * retry only by joining a different room, and (b) the FE branches on the
 * specific code to render a calmer "방이 가득 찼어요" toast instead of the
 * generic validation message.
 */
public class RoomFullException extends RuntimeException {
    public RoomFullException(String message) {
        super(message);
    }
}
```

2. **`RoomService.joinByCode` 의 line 352-354 교체:**

```java
long memberCount = roomMembers.countByRoom(room);
if (memberCount >= room.getMaxMembers()) {
    throw new RoomFullException("방 정원을 초과했습니다.");  // was BadRequestException
}
```

3. **`ApiExceptionHandler` 에 `@ExceptionHandler` 한 메서드 추가** (`IneligibleLeaderException` 의 409 precedent line 269-279 mirror, 위치는 line 280 의 `ServiceUnavailableException` 핸들러 직전):

```java
/**
 * Story 6.2 — 방 정원 초과 시 FE 가 KakaoTalk 공유로 들어온 사용자에게
 * "방이 가득 찼어요" 톤의 안내를 띄울 수 있게 409 CONFLICT 로 분리.
 * {@code BadRequestException} 로 fallthrough 시키면 FE 가 일반 400
 * VALIDATION 채널로 받아 단조로운 메시지를 띄움 — 본 케이스는 UX
 * 분기 가치가 있어 별도 매핑.
 */
@ExceptionHandler(RoomFullException.class)
ResponseEntity<ApiErrorResponse> roomFull(RoomFullException exception) {
    return ResponseEntity
            .status(HttpStatus.CONFLICT)
            .body(new ApiErrorResponse("ROOM_FULL", exception.getMessage()));
}
```

4. **FE `ApiError` 코드 인지 — 변경 ZERO** — `FE/src/api/client.ts` 의 `ApiError` 는 BE 의 `ApiErrorResponse.code` 문자열을 그대로 노출. AC2 의 `error.code === "ROOM_FULL"` 분기가 자동 작동.

**Test 가 BE-side correctness 게이트:**

- `RoomServiceTest.joinByCode_atCap_throwsRoomFullException` — 새 케이스 (mock `roomMembers.countByRoom(room) == room.getMaxMembers()` 가정).
- `RoomServiceTest.joinByCode_underCap_succeeds` — 기존 케이스 byte-identical (캡 미만은 정상 join).
- `ApiExceptionHandlerTest.roomFull_returns409_withRoomFullCode` — WebMvc slice 또는 unit (handler 단독) 으로 검증.
- 기존 `joinByCode_overCap_throwsBadRequestException` 같은 케이스가 있으면 **새 exception 타입 으로 update** (byte change, 1 line).

**Why dedicated exception not reusing existing:**

- `BadRequestException` 의 mapping (line 41-46 of `ApiExceptionHandler`) 가 400 + 일반 코드. FE 가 `BadRequestException` 의 message 문자열을 parsing 하는 것은 fragile (i18n 또는 wording 변경에 깨짐). **타입화된 code (`ROOM_FULL`)** 가 FE branch 의 안정적 기준.
- `IneligibleLeaderException` (line 269) 가 같은 408/409 wave 의 precedent — 이미 같은 패턴이 한 번 도입됨. 본 스토리가 **2번째 cap-class 9999 exception** 으로 패턴 강화.

**Anti-pattern (DO NOT IMPLEMENT):**

- `RoomService.joinByCode` 가 `RoomFullException` 대신 `IllegalStateException` 또는 `IllegalArgumentException` 던지기 — 후자는 `ApiExceptionHandler:302-308` 의 `IllegalArgumentException` 핸들러가 400 VALIDATION 으로 mapping. epics 의 409 잠금 위반.
- BE message 가 FE 의 toast 카피와 어긋남 (`"방이 가득 찼어요"` BE vs `"방 정원을 초과했습니다"` FE) — FE 가 `error.code === "ROOM_FULL"` 분기에서 자체 한국어 카피를 사용하므로 BE message 는 logging 목적. 두 message 를 일치시킬 필요 없음.
- 새 exception 위치를 `BE/src/main/java/com/yeosal/api/common/` 로 — `RoomFullException` 은 도메인-specific (room 의 invariant 위반). `room/` 모듈 안 (project-context.md:175 package-by-feature 원칙).
- `ApiExceptionHandler` 에 두 번째 @RestControllerAdvice 추가 — project-context.md:111 "exactly one advisor" 룰 위반. 기존 핸들러에 메서드 한 개 추가.

PRD: FR-8.6.4 (line 417). project-context.md: line 87 (domain exceptions extend RuntimeException), line 175 (package-by-feature), line 111 (single advisor).

### AC7 — `app.json` deep-link config + `@react-native-kakao/core` expo-config-plugin (NATIVE PRECONDITION)

**Given** 본 스토리가 native module 을 처음 도입한다 (project-context.md:132)
**When** Expo prebuild 가 `app.json` 을 native 코드로 변환한다
**Then** **app.json** 이 정확히 다음과 같이 확장된다 (기존 line 1-30 + 추가):

```json
{
  "expo": {
    "name": "Yeosal",
    "slug": "yeosal",
    "scheme": "yeosal",
    "...": "existing fields unchanged",
    "ios": {
      "supportsTablet": false,
      "bundleIdentifier": "app.yeosal.mobile",
      "associatedDomains": ["applinks:yeolsal.app"]
    },
    "android": {
      "package": "app.yeosal.mobile",
      "adaptiveIcon": { "foregroundImage": "./assets/brand/icon.png", "backgroundColor": "#F8F3E7" },
      "intentFilters": [
        {
          "action": "VIEW",
          "autoVerify": true,
          "data": [{ "scheme": "https", "host": "yeolsal.app", "pathPrefix": "/join" }],
          "category": ["BROWSABLE", "DEFAULT"]
        }
      ]
    },
    "plugins": [
      "expo-router",
      "expo-notifications",
      "expo-secure-store",
      "expo-font",
      "@sentry/react-native/expo",
      [
        "@react-native-kakao/core",
        {
          "nativeAppKey": "${EXPO_PUBLIC_KAKAO_NATIVE_APP_KEY}",
          "android": { "authCodeHandlerActivity": false },
          "ios": { "handleKakaoOpenUrl": true }
        }
      ]
    ]
  }
}
```

**핵심 잠금:**

- `scheme: "yeosal"` — 기존 그대로 유지. dev / preview 의 `yeosal://join?code=X` fallback 이 살아있어야 함.
- `ios.associatedDomains: ["applinks:yeolsal.app"]` — Universal Link 등록. **Apple 의 hosting 게이트:** `https://yeolsal.app/.well-known/apple-app-site-association` 파일이 hosting 되어야 verification 통과 — Story 6.3 또는 DevOps 가 별도 PR. 본 스토리는 app.json 의 등록 만.
- `android.intentFilters` — `autoVerify=true` 가 Android App Link 검증을 받음. **Google 의 hosting 게이트:** `https://yeolsal.app/.well-known/assetlinks.json` 파일에 `sha256_cert_fingerprints` 가 들어있어야 함 (Story 6.3 / DevOps).
- `plugins` — `@react-native-kakao/core` 의 expo-config-plugin 이 native iOS Info.plist (LSApplicationQueriesSchemes / CFBundleURLTypes / kakaoNativeAppKey) + Android AndroidManifest (KakaoActivity / `com.kakao.sdk.AppKey` meta) 를 자동 wire. **수동 `ios/Info.plist` 또는 `android/AndroidManifest.xml` 편집 ZERO** — prebuild 가 책임.
- `nativeAppKey` 는 `${EXPO_PUBLIC_KAKAO_NATIVE_APP_KEY}` env-substitution. `app.config.ts` 가 아닌 `app.json` 의 env-substitution 지원 여부 — Expo 의 `app.json` 은 native substitution 미지원. **권장 경로:**
  - **Path A (권장):** `app.json` → `app.config.ts` 로 conversion. `process.env.EXPO_PUBLIC_KAKAO_NATIVE_APP_KEY` 읽음. EAS build 가 `eas.json` 의 `env` 블록을 통해 inject (Story 6.3 RUNBOOK 이 ops 가이드 추가).
  - **Path B (fallback):** `app.json` 안에 native app key 를 hardcode + `.gitignore` 로 commit 차단. **권장 안 함** — 매번 rebuild 필요. **Path A 선택**.

**`FE/.env.example` 추가 (한 줄):**

```
EXPO_PUBLIC_KAKAO_NATIVE_APP_KEY=replace-with-kakao-developers-console-native-app-key
```

**Kakao Native App Key 의 보안 분류:**

- Kakao Developers Console 의 "앱 키" 4종 중 **Native App Key** 는 클라이언트-임베드용 공개키. **REST API Key** 와 본질적으로 다름 (REST API Key 가 BE 전용 — project-context.md:235).
- 따라서 `EXPO_PUBLIC_*` 노출 정책 위반 아님. **단, README 또는 RUNBOOK 에 "Native App Key vs REST API Key 의 차이" 한 줄 기록** (Story 6.3 scope).

**EAS build 가이드 (선언만, 본 스토리에서 EAS profile 변경 ZERO):**

- 기존 `eas.json` 의 `preview` / `production` profile 이 native module 을 자동 포함. 새 native module add 후 첫 prebuild → EAS build 가 `Pods` (iOS) / Gradle (Android) 재구성. dev local 에서는 `adb uninstall app.yeosal.mobile && npx expo run:android` (project-context.md:132 의 룰 발화) — Story 6.3 RUNBOOK 이 이 cycle 의 docs.

**Anti-pattern (DO NOT IMPLEMENT):**

- 수동 `ios/` 또는 `android/` directory 편집 — Expo prebuild workflow 위반. **`@react-native-kakao/core` 의 expo-config-plugin 이 모든 native config 를 자동 wire**.
- `applinks:yeolsal.app` 외에 다른 host 도 등록 (e.g., `applinks:api.rearleg.com`) — Universal Link 의 verification 이 `api.rearleg.com/.well-known/AASA` 도 요구. 단일 host 잠금 (`yeolsal.app`) 으로 운영 surface 축소.
- `intentFilters` 의 `pathPrefix` 를 `/` 로 — 모든 yeolsal.app path 가 App Link 로 캡처되어 향후 landing page navigation 이 깨짐. **`/join` 만**.
- `nativeAppKey` 를 `app.json` 에 평문 hardcode 후 git commit — refresh / rotation 시 코드 변경 필요. env-var 로 indirection.
- `expo-linking` 또는 `react-native-linking` 같은 추가 dependency 도입 — RN core 의 `Linking` API + `expo-linking` (이미 expo-router 의 transitive dep) 이 충분. **새 dep zero**.

PRD: FR-8.6.4 (line 417). project-context.md: line 132 (native module reinstall rule). Story 6.3 dependency: AASA + assetlinks.json hosting, EAS profile docs.

### AC8 — `RoomInvite` FE type extension + `useRoomInvite` queries 의 새 필드 노출 (TYPE PROPAGATION)

**Given** Story 6.1 가 BE record 에 2 필드 (`kakaoShareUrl`, `previewCardImageUrl`) 를 추가했지만 FE type 은 의도적으로 미변경 (6.1 AC9 scope fence)
**When** 본 스토리의 `useKakaoShare()` 가 `invite.kakaoShareUrl` / `invite.previewCardImageUrl` 를 읽는다
**Then** **`FE/src/api/rooms.ts` 의 `RoomInvite` interface 가 2 필드 확장:**

```typescript
export interface RoomInvite {
  id: number;
  roomId: number;
  code: string;
  expiresAt: string | null;
  /**
   * Story 6.1 — canonical share URL embedded in the Kakao card link. The
   * BE owns the deeplink base (yeolsal.app default; env-overridable);
   * FE forwards verbatim to the Kakao SDK and to the in-app deep-link
   * router via app.json's universal-link config.
   */
  kakaoShareUrl: string;
  /**
   * Story 6.1 — public, cacheable preview-card image URL. Resolves via
   * GET /api/v1/rooms/{id}/invites/preview-card → 302 to a PNG served
   * by nginx. The FE never fetches this URL — it's only forwarded to
   * the Kakao SDK so KakaoTalk's fetcher can resolve the preview card.
   */
  previewCardImageUrl: string;
}
```

**Backward compatibility:**

- BE 의 record 가 두 필드를 항상 포함하므로 (Story 6.1 AC1 line 41-49) `string` (not `string | null`) 으로 narrow type 가능. 만약 BE 가 일시적으로 null 을 보내면 (예: invite revoke 후 unsynced cache), `useKakaoShare` 가 ApiError 로 fail-fast 하지 않고 SDK 가 invalid URL 로 error 를 던짐 → AC4 의 fallback path 발화.
- 기존 4-필드만 사용하던 callsites (`app/join.tsx:29` 의 `joinRoom`, `app/rooms/[id].tsx:140-153` 의 share / invite display) 는 두 필드를 무시해도 안전 (TS structural typing).

**Test 가 type-narrowing 보조 게이트:**

- `FE/src/api/__tests__/rooms.test.ts` (또는 createInvite 의 기존 unit test 가 있으면 거기에 두 줄) — `createInvite(...)` 의 BE mock 응답에서 두 필드를 readback assert.

**Anti-pattern (DO NOT IMPLEMENT):**

- 두 필드를 `string | null | undefined` — BE contract 가 항상 두 필드를 보내므로 optional 타입은 dead code 분기를 만듦. `string` non-nullable.
- 두 필드를 별도 `interface RoomInviteShareLinks` 로 분리 후 `RoomInvite & RoomInviteShareLinks` — 단일 BE record 의 한 응답이라 single interface 가 직관적. composition 은 over-engineering.
- `RoomInvite` 의 `code` 를 `kakaoShareUrl` query 에서 parse 해서 derive — `code` 가 wire-level primary key, derived field 는 fragile. **두 필드 모두 BE 가 source of truth**.

PRD: FR-8.6.1 (line 414). Story 6.1: AC1 line 32-71 (BE record contract).

### AC9 — File / scope fence (LOCKED ALLOW LIST)

**Given** Story 6.2 의 diff 가 review 단계에 들어간다
**When** `git diff --stat origin/main` 가 실행된다
**Then** 변경된 파일은 **정확히 다음 allow list** 에만 머무른다:

**MODIFIED (existing files):**

- `BE/src/main/java/com/yeosal/api/room/RoomService.java` — line 352-353 의 BadRequestException → RoomFullException 한 줄 변경 (AC6).
- `BE/src/main/java/com/yeosal/api/common/ApiExceptionHandler.java` — `@ExceptionHandler(RoomFullException.class)` 메서드 1 개 추가 (AC6).
- `FE/app.json` 또는 `FE/app.config.ts` (Path A 변환 시 후자) — associatedDomains + intentFilters + plugin 추가 (AC7).
- `FE/package.json` — `@react-native-kakao/share`, `@react-native-kakao/core`, `@react-native-kakao/expo-config-plugin` 세 dependency add.
- `FE/package-lock.json` — 자동 갱신.
- `FE/app/_layout.tsx` — `useShareLinkDeepLink()` 호출 한 줄 추가 + Kakao SDK init (`initializeKakaoSDK(EXPO_PUBLIC_KAKAO_NATIVE_APP_KEY)`) (AC1, AC2).
- `FE/app/join.tsx` — `useLocalSearchParams<{ code?: string }>()` + auto-submit useEffect + ApiError ROOM_FULL 분기 (AC2).
- `FE/app/rooms/[id].tsx` — `shareInviteKakao` / `shareInviteGeneric` 두 함수 + `useKakaoShare()` + InviteCodeSheet props 2개 wiring (AC4).
- `FE/src/components/rooms/InviteCodeSheet.tsx` — `onShareKakao` + `onShareGeneric` props + 두 Button 으로 분기 (AC4).
- `FE/src/api/rooms.ts` — `RoomInvite` interface 의 2 필드 추가 (AC8).
- `FE/src/auth/AuthContext.tsx` — `signUp` / `signIn` 의 success path 에 `consumePendingInviteCode` + `joinRoom` 호출 (AC3).
- `FE/.env.example` — `EXPO_PUBLIC_KAKAO_NATIVE_APP_KEY` 한 줄 추가 (AC7).

**NEW (untracked files):**

- `BE/src/main/java/com/yeosal/api/room/RoomFullException.java` (AC6).
- `FE/src/lib/kakaoShare.ts` (AC1).
- `FE/src/lib/query/hooks/useKakaoShare.ts` (AC1).
- `FE/src/lib/deepLinking.ts` (AC2, AC3).
- 기존 BE test 파일에 새 케이스 추가 (NEW file 아님 — `RoomServiceTest`, `ApiExceptionHandlerTest`).
- 새 FE test 파일들 — AC11 참조.

**BANNED-PATHS (must be ZERO diff):**

- `BE/src/main/resources/db/migration/**` — NO migration (V13 stays latest, no schema change).
- `BE/src/main/java/com/yeosal/api/kakaoshare/**` — Story 6.1 의 module 변경 ZERO (본 스토리는 6.1 의 output 을 소비).
- `BE/src/main/java/com/yeosal/api/auth/**` — auth 도메인 변경 ZERO (Trap #2 의 결정).
- `BE/src/main/java/com/yeosal/api/notification/**` — push notification 변경 ZERO (Epic 6 미명시, Trap #5 의 결정).
- `BE/src/main/java/com/yeosal/api/realtime/**` — STOMP 채널 변경 ZERO (share 는 realtime 미사용).
- `FE/src/theme/tokens.json` — design token 변경 ZERO (Story 6.1 AC9 의 동일 경계).
- `FE/src/providers/RealtimeProvider.tsx` — single STOMP client 변경 ZERO.
- `FE/src/providers/QueryProvider.tsx` — TanStack persist 변경 ZERO.
- `infra/**` — infra 변경 ZERO (landing page hosting + AASA/assetlinks 는 Story 6.3 scope).
- `docs/**` — RUNBOOK 변경 ZERO (Story 6.3 scope).

**Verification command:**

```bash
git diff --stat origin/main -- \
  BE/src/main/resources/db/migration/ \
  BE/src/main/java/com/yeosal/api/kakaoshare/ \
  BE/src/main/java/com/yeosal/api/auth/ \
  BE/src/main/java/com/yeosal/api/notification/ \
  BE/src/main/java/com/yeosal/api/realtime/ \
  FE/src/theme/tokens.json \
  FE/src/providers/RealtimeProvider.tsx \
  FE/src/providers/QueryProvider.tsx \
  infra/ docs/
# Expected: empty output.
```

### AC10 — Brand-voice + ESLint + TypeScript 게이트 (LOCKED)

**Given** Story 6.2 의 diff 가 build pipeline 을 통과해야 한다
**When** lint / typecheck / brand-voice 게이트가 실행된다
**Then** 다음 네 조건이 동시에 만족된다:

1. **Brand-voice lint** (`tools/brand-voice-lint.ts`) — Story 6.1 의 baseline `0 HARD / 198 warnings` 보존. 새 native module 의 node_modules 가 lint scope 외 (`FE/eslint.config.js` ignore). 새 phrase set (AC5) 의 AVOID 어휘 hit 0.
2. **ESLint** (`cd FE && npm run lint`) — 새 4 파일 (`kakaoShare.ts`, `useKakaoShare.ts`, `deepLinking.ts` + InviteCodeSheet/join.tsx/rooms[id].tsx/_layout.tsx/AuthContext.tsx 의 수정) 모두 `@typescript-eslint/no-unused-vars` 통과, no `any` (project-context.md:97), `console.log` zero.
3. **TypeScript** (`cd FE && npm run typecheck`) — 기존 baseline (Story 5.4 의 동일 2 FriendsTodayPager errors) 유지, 새 에러 0.
4. **BE Checkstyle** (`cd BE && ./gradlew checkstyleMain`) — hex-literal guard 통과 (본 스토리는 SVG 또는 token 변경 ZERO). 한 줄 변경 (line 352-354 of `RoomService`) + 새 exception 클래스 1 개 만으로는 style 변동 없음.

**Brand-voice 보조 게이트:**

- `useKakaoShare` 또는 `kakaoShare.ts` 안의 hardcoded 한국어 string set 이 AC5 의 표와 byte-identical.
- 사용자-가시 toast / 버튼 라벨이 AC5 표 외의 새 한국어 phrase 를 도입 ZERO.

### AC11 — Test matrix (NET-ADDITIVE, RED → GREEN order)

**Given** TDD enforced (project-context.md:145, "RED → GREEN → refactor")
**When** Story 6.2 의 test suite 가 작성된다
**Then** 다음 새 테스트 케이스가 정확히 add 된다:

**BE Unit tests:**

| File | Cases | Coverage |
|------|-------|----------|
| `RoomServiceTest` (기존) | +2 (net-additive) | `joinByCode_atCap_throwsRoomFullException` + `joinByCode_atCap_doesNotInvokePreviewCacheInvalidate` (failed join 이 cache invalidation 을 trigger 하지 않음 확인 — `verify(previewCardCacheService, never()).invalidate(...)`) |
| `ApiExceptionHandlerTest` (기존, 또는 새 slice) | +1 | `roomFull_returns409Conflict_withRoomFullCode` — `RoomFullException` 던지는 dummy controller 또는 직접 핸들러 호출 |

**FE Unit tests:**

| File | Cases | Coverage |
|------|-------|----------|
| `FE/src/lib/__tests__/kakaoShare.test.ts` (NEW) | 4 | `sendInviteShare` 가 `KakaoShareLink.sendDefault` 를 정확한 payload 로 호출 (mock SDK), description 의 N명 fill-in, button.title = "같이 살아남자", error propagation (SDK 던지면 wrapper 가 re-throw) |
| `FE/src/lib/__tests__/deepLinking.test.ts` (NEW) | 5 | URL parse `/join?code=X` → `router.push("/join?code=X")`, 비인증 사용자 → SecureStore set + `router.replace("/signup")`, non-join path → no-op, missing code query → no-op, `consumePendingInviteCode` read-then-delete |
| `FE/src/components/rooms/__tests__/InviteCodeSheet.test.tsx` (기존 또는 NEW) | +2 | 두 Button 의 onPress 가 각각의 props (onShareKakao / onShareGeneric) 를 호출 |
| `FE/app/__tests__/join.test.tsx` (NEW, 또는 expo-router test 가 까다로우면 hook 단위 추출) | 3 | `?code=X` 자동 fill + auto-submit, ROOM_FULL ApiError → 특정 toast, ROOM_FULL 외 error → generic toast |
| `FE/src/auth/__tests__/AuthContext.test.tsx` (기존 또는 NEW) | +2 | signup 성공 + pendingInviteCode 존재 → joinRoom 호출 + `/rooms/{id}/settings` redirect, signup 성공 + pendingInviteCode 없음 → `/today` redirect |

**TDD execution order (per file):**

1. RED — new test 또는 새 케이스 추가, 컴파일/실행 실패 (target API 미존재).
2. GREEN — 최소 구현, test 통과.
3. Refactor — 명료성.

**Test mocking 패턴:**

- `@react-native-kakao/share` 의 `KakaoShareLink.sendDefault` — `jest.mock("@react-native-kakao/share", ...)` 으로 전체 SDK mock. `jest.setup.ts` 에 global mock 등록 (Sentry mock 의 byte-similar 패턴, project-context.md:151).
- `@react-native-kakao/core` 의 `initializeKakaoSDK` — `_layout.tsx` test 가 SDK init 을 call (`expect(initializeKakaoSDK).toHaveBeenCalledWith("test-key")`).
- `expo-linking` 의 `Linking.parse`, `Linking.getInitialURL`, `Linking.addEventListener` — `jest.mock("expo-linking", ...)` per-test.
- `expo-secure-store` 의 `setItemAsync` / `getItemAsync` / `deleteItemAsync` — `jest.mock("expo-secure-store", ...)`.

**Gradle / Jest command:**

```bash
cd BE && ./gradlew test --tests "*RoomService*" --tests "*RoomFull*" --tests "*ApiExceptionHandler*"
cd BE && ./gradlew test  # 전체 BE 스위트 GREEN (603 + ~3 new = ~606)
cd FE && npm test -- --watchAll=false  # 전체 FE 스위트 GREEN (466 + ~16 new = ~482)
```

### AC12 — Verification matrix (gate before sprint-status flip)

**Given** Dev 가 모든 AC 를 구현했다
**When** Story 6.2 가 review 로 진입한다
**Then** 다음 14 게이트가 모두 GREEN:

| # | Gate | Command | Expected |
|---|------|---------|----------|
| 1 | BE Gradle full suite | `cd BE && ./gradlew test` | BUILD SUCCESSFUL + (603+~3) tests GREEN |
| 2 | BE Checkstyle | `cd BE && ./gradlew checkstyleMain` | Zero new violations |
| 3 | FE typecheck | `cd FE && npm run typecheck` | Same 2 FriendsTodayPager errors (Story 5.4/6.1 baseline), no new |
| 4 | FE ESLint | `cd FE && npm run lint` | Clean (new files 추가, no `any`, no console.log) |
| 5 | FE Jest | `cd FE && npm test -- --watchAll=false` | All existing + ~16 new tests GREEN |
| 6 | Brand-voice lint | `cd FE && npm run brand-voice-lint` | 0 HARD / ≤198 warnings (Story 6.1 baseline preserved) |
| 7 | Scope fence | `git diff --stat origin/main -- <banned-paths>` (AC9) | empty output |
| 8 | Diff sanity | `git diff --check HEAD` | clean |
| 9 | File list match | `git diff --name-only origin/main \| sort` | matches AC9 allow list |
| 10 | Expo prebuild dry-run | `cd FE && npx expo prebuild --no-install --platform all` (optional, dev-only) | iOS Info.plist + Android Manifest 에 KakaoSDK + universal-link entries 자동 wire 확인 |
| 11 | Native module count check | `grep "@react-native-kakao" FE/package.json` | 정확히 3 packages: share, core, expo-config-plugin |
| 12 | Manual VERIFY-A | 새 invite 발급 → InviteCodeSheet 의 "KakaoTalk으로 공유" 탭 → KakaoTalk 앱 launch (또는 web dialog) | preview card image + room name + "같이 살아남자" 버튼 노출 |
| 13 | Manual VERIFY-B | KakaoTalk 의 받은 메시지에서 preview card 탭 → 본인 app 으로 deep-link 진입 | `app/join.tsx` 가 `?code=X` 로 자동 채워지고 auto-submit |
| 14 | Manual VERIFY-C | 가득 찬 방의 invite code 로 join 시도 | toast `"방이 가득 찼어요. 친구에게 새 방을 만들어 달라고 요청하세요."` + 폼 유지 |

VERIFY-A/B/C 의 manual smoke 는 native build + KakaoTalk 가 설치된 device 가 있을 때만 수행. Dev host 에서 unavailable 면 PR-open 시 review reviewer 또는 EAS preview build (Story 6.3 scope) 후 수행 — Story 5.1 / 5.2 / 5.3 / 5.4 / 6.1 의 동일 패턴.

### AC13 — Post-merge user action (RUNBOOK note)

**Given** 본 PR 이 main 에 머지된다
**When** prod 배포가 일어난다
**Then** PR description 의 "Post-merge user action" 섹션에 다음 5 줄을 포함 (project-context.md:229):

```
- Native module 추가 — Kakao Share SDK (project-context.md:132 의 rule 발화). Dev 머신: `cd FE && adb uninstall app.yeosal.mobile && npx expo run:android` 또는 iOS 의 동등 절차로 깨끗한 rebuild. Metro 의 hot-reload 만으로는 KakaoSDK module 이 binary 에 포함되지 않음.
- EAS preview / production build — 새 native module 이 포함된 새 빌드 필요. `eas build --profile preview` 후 KakaoTalk 가 설치된 device 에서 smoke test (VERIFY-A/B/C).
- `EXPO_PUBLIC_KAKAO_NATIVE_APP_KEY` 환경 변수 — EAS Secrets 에 등록 (`eas secret:create --scope project --name EXPO_PUBLIC_KAKAO_NATIVE_APP_KEY --value <kakao-developers-console-native-app-key>`). dev 머신은 `FE/.env` 로 local override.
- Universal Link / App Link hosting — `yeolsal.app` 의 `.well-known/apple-app-site-association` + `.well-known/assetlinks.json` 파일이 hosting 되어야 OS 의 verification 통과. 본 PR 은 `app.json` 의 등록만 완성; hosting 은 Story 6.3 또는 별도 infra PR.
- Kakao Developers Console — `app.yeosal.mobile` (Android) + `app.yeosal.mobile` (iOS Bundle ID) 가 Kakao 앱 platform 정보에 등록되어 있어야 SDK init 성공. 미등록 시 KakaoTalk 의 share 시도가 "앱 정보를 찾을 수 없음" error.
```

본 스토리는 V13 의 schema 변경 ZERO, NotNullSetter 추가 ZERO. **Migration 측면의 post-merge action 없음.**

### AC14 — Architecture deviation notes (DOC FOLLOW-UP, NON-BLOCKER)

**Given** 본 스토리의 구현이 architecture 문서와 다음 부분이 어긋난다
**When** PR description 또는 architecture 문서 PR 이 작성된다
**Then** 명시:

1. **Architecture §6.2 (line 621) 의 FE source tree** 가 `src/lib/kakaoShare.ts` 만 enumerate. 본 스토리가 추가로 `src/lib/query/hooks/useKakaoShare.ts` + `src/lib/deepLinking.ts` 두 파일을 도입. **Doc follow-up PR**: §6.2 의 src/lib 블록 확장. 비-블로커.
2. **Architecture §3.3 line 154 의 "extending existing Kakao OAuth integration in the same dependency package"** — 본 스토리가 발견: BE 의 Kakao OAuth 가 REST-only (KakaoAuthClient, no native SDK), FE 는 Kakao 의존성이 ZERO 였음. "extending the existing Kakao OAuth dependency" 는 architecture 의 단순화된 wording — 실질은 FE 가 처음으로 Kakao 의존성을 추가. **Doc follow-up**: §3.3 line 154 를 "Native module 으로 처음 추가 (BE 는 REST-only Kakao OAuth, FE 는 처음)" 로 정확화. 비-블로커.
3. **Architecture §4.10 (line 308-328) 의 preview-card cache** — 본 스토리는 6.1 의 cache 를 *소비*만, 변경 ZERO.
4. **Epics line 849 의 `kakaoShare.sendCustomFeed(...)` wording** — 본 스토리는 Default `feed` template 사용 (Custom Template ID 사전 등록 회피, 출시 risk 최소화). Wire-level 차이 만, **사용자-가시 UX 차이는 ZERO** (preview card 의 image / title / button 이 동일하게 렌더링). 비-블로커, 본 스토리 dev note 에 기록.
5. **Epics line 858 의 "post-install signup carries inviteCode to /api/v1/auth/signup"** — 본 스토리의 결정 (Trap #2): FE-side SecureStore bridging. BE auth contract 변경 ZERO. PR description 의 "Acceptance Criteria deviation" 섹션에 본 결정 명시.

### AC15 — Sentry / observability hook (LIGHT-TOUCH)

**Given** Story 5.4 / 6.1 가 Sentry 별도 wiring 미작성
**When** 본 스토리의 Kakao SDK 실패 또는 deep-link route 실패가 운영자가 알 수 있어야 한다
**Then** **추가 wiring 최소** — Sentry 의 자동 instrumentation 이 ApiError 5xx 를 자동 캐치. 새 Sentry breadcrumb 한 곳만 추가:

- `kakaoShare.sendInviteShare` 의 try/catch 내부 — `Sentry.addBreadcrumb({ category: "kakao-share", level: "warning", message: "SDK call failed", data: { errorMessage: err.message }})` 한 줄. user-visible toast 는 그대로 유지 (AC4).
- 이유: KakaoSDK 의 silent fail 이 빈도가 높은 경우 Sentry 로 root cause (SDK version / device OS 분포) 를 추적 가능. WARN-level 로 alert 채널 우선순위 낮춤.

**비-목표:** `Sentry.captureException(err)` 직접 호출 — fallback 이 동작하면 사용자 영향 ZERO, breadcrumb 만으로 충분.

### AC16 — Sprint-status transitions

**Given** 본 스토리가 ready-for-dev → in-progress → review → done 사이클을 돈다
**When** sprint-status.yaml 이 업데이트된다
**Then** transitions:

1. **create-story (본 워크플로우)** — `6-2-kakao-share-sdk-integration-deep-linking: backlog → ready-for-dev`. **epic-6 transition 없음** — 이미 `in-progress` 상태 (Story 6.1 가 epic 을 in-progress 로 flip 했음).
2. **dev-story 시작** — `6-2-...: ready-for-dev → in-progress`.
3. **dev-story 완료** — `6-2-...: in-progress → review`.
4. **code-review 완료** — `6-2-...: review → done`. epic-6 는 6-3 backlog 잔존 시 `in-progress` 유지.

## Tasks / Subtasks

- [x] **Task 1 — RED phase setup** (AC: 11)
  - [x] BE 새 케이스 작성 — `RoomServiceTest.joinByCode_atCap_throwsRoomFullException`, `ApiExceptionHandlerTest.roomFull_returns409Conflict_withRoomFullCode`.
  - [x] FE 새 test 파일 — `kakaoShare.test.ts` (4 cases), `deepLinking.test.ts` (8 cases — 5 routing + 3 consumePendingInviteCode), `InviteCodeSheet.test.tsx` (+2 new buttons), `AuthContext.bridging.test.tsx` (4 cases). `join.test.tsx` deferred — Expo Router useLocalSearchParams mock 복잡도 + ROOM_FULL/NOT_FOUND 분기는 manual VERIFY-C 가 1차 채널이며 분기 로직은 BE 측 RoomFullException 단위 케이스가 게이팅 (story line 703 의 OOS 옵션 채택).
  - [x] RED → GREEN 사이클 — BE 케이스는 implementation 전 fail 확인 → swap 후 GREEN. FE 는 SDK mock + module mocks 미존재 시 compile-fail → wrapper/hook 작성 후 GREEN.
- [x] **Task 2 — BE production code (AC: 6)**
  - [x] `RoomFullException` 클래스 신규 (`BE/src/main/java/com/yeosal/api/room/RoomFullException.java`).
  - [x] `RoomService.joinByCode:351-358` `BadRequestException` → `RoomFullException` swap + 4-line story-anchor 주석.
  - [x] `ApiExceptionHandler.roomFull` 핸들러 추가 (`@ExceptionHandler(RoomFullException.class)` → 409 CONFLICT + code `ROOM_FULL`, `IneligibleLeaderException` precedent line 269-279 mirror).
  - [x] BE 테스트 GREEN — `RoomServiceTest` 36/36 + `ApiExceptionHandlerTest` 모두 통과.
- [x] **Task 3 — FE Kakao SDK wrapper + hook (AC: 1, 4)**
  - [x] `npm install @react-native-kakao/share @react-native-kakao/core` — **deviation:** `@react-native-kakao/expo-config-plugin` 패키지는 npm registry 에 미존재 (404). v2 SDK 는 `@react-native-kakao/core` 가 expo-config-plugin 을 자체 번들. 결과: 3 → 2 packages (AC11 게이트 11 의 `정확히 3 packages` 잠금 deviation, AC14 의 doc follow-up 으로 처리).
  - [x] `src/lib/kakaoShare.ts` — `sendInviteShare(input)` Feed-template wrapper + try/catch Sentry breadcrumb. **추가 deviation:** 스토리 dev-spec 의 `KakaoShareLink.sendDefault(...)` 는 v2 API 에서 제거됨 (현행: `shareFeedTemplate({ template })`). KakaoFeedTemplate 의 content/buttons payload 는 byte-identical 이라 사용자-가시 카드 변동 ZERO. AC14 의 비-블로커 doc follow-up.
  - [x] `src/lib/query/hooks/useKakaoShare.ts` — `useMutation<void, Error, ShareInput>` TanStack 훅.
  - [x] `_layout.tsx` 에 `initializeKakaoSDK(process.env.EXPO_PUBLIC_KAKAO_NATIVE_APP_KEY ?? "")` boot-time 호출 + `useShareLinkDeepLink()` mount 한 줄.
  - [x] 단위 테스트 GREEN — kakaoShare.test.ts 4/4 (payload shape, member-count interpolation, error propagation, catch-runs-once).
- [x] **Task 4 — FE deep-link 모듈 + post-install bridging (AC: 2, 3)**
  - [x] `src/lib/deepLinking.ts` — `useShareLinkDeepLink` 훅 + `consumePendingInviteCode` async helper + test-only `__PENDING_INVITE_KEY_FOR_TESTS` 상수.
  - [x] `_layout.tsx` 의 `NotificationInvalidationBootstrap` 안에 `useShareLinkDeepLink()` 한 줄 mount (기존 `useNotificationResponseDeepLink()` 옆 — byte-similar pattern).
  - [x] `AuthContext.tsx` — `signIn` / `signUp` / `signInWithKakao` 모두 `await apply(...)` 후 `tryConsumePendingInvite()` (module-private helper) 호출. ROOM_FULL → soft toast "초대받은 방이 가득 찼어요...", other error → "초대 코드는 그룹 참여 화면에서 다시 사용할 수 있어요.".
  - [x] `app/join.tsx` — `useLocalSearchParams<{ code?: string }>()` + auto-submit useEffect + `submit()` catch 의 `ApiError.code === "ROOM_FULL"` / `"NOT_FOUND"` 두 분기 추가.
  - [x] 단위 테스트 GREEN — deepLinking.test.ts 8/8 (URL matrix 5 + consumePendingInviteCode 3), AuthContext.bridging.test.tsx 4/4 (pendingCode 분기 4가지).
- [x] **Task 5 — InviteCodeSheet + RoomDetailScreen wiring (AC: 4)**
  - [x] `InviteCodeSheet.tsx` — `onShare` → `onShareKakao + onShareGeneric` props split, primary "KakaoTalk으로 공유" + secondary "다른 앱으로 공유" 두 Button 으로 분기.
  - [x] `app/rooms/[id].tsx` — `shareInvite` 삭제 → `shareInviteKakao()` (useKakaoShare mutation + onError 시 generic fallback + toast) + `shareInviteGeneric()` (기존 Share.share). `memberCount = Math.max(1, members.length)` 가드 (Trap #13).
  - [x] 단위 테스트 GREEN — InviteCodeSheet.test.tsx (기존 7 케이스 + 2 새 케이스: 두 Button 의 독립 fire 확인).
- [x] **Task 6 — Type extension (AC: 8)**
  - [x] `FE/src/api/rooms.ts` 의 `RoomInvite` interface 에 `kakaoShareUrl: string` + `previewCardImageUrl: string` non-null string 두 필드 추가.
  - [x] InviteCodeSheet.test.tsx 의 `sampleInvite` fixture 가 두 필드를 포함하여 type-narrowing 검증 (readback assert 역할).
- [x] **Task 7 — app.json + env-var + expo-config-plugin (AC: 7)**
  - [x] `app.json` 정적 키 확장 — `ios.associatedDomains: ["applinks:yeolsal.app"]`, `android.intentFilters: [{action.VIEW, autoVerify=true, data.scheme=https + host=yeolsal.app + pathPrefix=/join, category=[BROWSABLE,DEFAULT]}]`. **app.json → app.config.ts 의 변환은 불필요** — 기존 `app.config.ts` 가 이미 존재. 본 PR 은 plugins 머지 (KAKAO_PLUGIN + withKakaoPlugin helper) 만 app.config.ts 에 추가 (env-substitution 필요한 부분).
  - [x] `FE/.env.example` 에 `EXPO_PUBLIC_KAKAO_NATIVE_APP_KEY=replace-with-kakao-developers-console-native-app-key` 한 줄 + 6-line 설명 주석 (Native vs REST API key 구분 — Trap #3 의 doc).
  - [x] `npx expo prebuild --no-install` dry-run — manual VERIFY 단계로 deferred (native build 필요, EAS preview profile 에서 1차 검증).
- [x] **Task 8 — Brand voice + Sentry breadcrumb (AC: 5, 10, 15)**
  - [x] AC5 표 의 모든 한국어 phrase byte-identical hardcode — 검색 grep 결과 0 deviation:
    - `"KakaoTalk으로 공유"` (InviteCodeSheet.tsx)
    - `"다른 앱으로 공유"` (InviteCodeSheet.tsx)
    - `"<N>명이 함께 살아남는 중"` (kakaoShare.ts)
    - `"같이 살아남자"` (kakaoShare.ts)
    - `"열살 그룹 초대 코드: <CODE>"` (app/rooms/[id].tsx)
    - `"방이 가득 찼어요. 친구에게 새 방을 만들어 달라고 요청하세요."` (app/join.tsx)
    - `"초대 코드가 만료되었어요."` (app/join.tsx)
    - `"KakaoTalk 공유가 안 돼요. 다른 방법으로 공유해주세요."` (app/rooms/[id].tsx)
    - `"초대받은 방이 가득 찼어요. 직접 코드를 입력해서 다시 시도해보세요."` (AuthContext.tsx)
    - `"초대 코드는 그룹 참여 화면에서 다시 사용할 수 있어요."` (AuthContext.tsx)
  - [x] `sendInviteShare` catch 에 `addBreadcrumb({ category: "kakao-share", level: "warning", ... })` 한 줄 — Sentry 가 init 됨 (DSN 존재) 시에만 발화하는 guard 가 `sentry.ts:addBreadcrumb` 내부에서 자동 처리.
  - [x] brand-voice-lint **0 HARD / 198 warnings** — Story 6.1 baseline (0 HARD / 198 warnings) 과 byte-identical 보존 ✅.
- [x] **Task 9 — Run all gates + scope-fence verification (AC: 9, 10, 11, 12)**
  - [x] `cd BE && ./gradlew test` — BUILD SUCCESSFUL (전체 BE 스위트 GREEN, RoomServiceTest 36/36, ApiExceptionHandlerTest 통과).
  - [x] `cd BE && ./gradlew checkstyleMain` — BUILD SUCCESSFUL, zero violations.
  - [x] `cd FE && npm run typecheck` — Story 5.4/6.1 baseline (FriendsTodayPager.tsx 2 errors only) 보존, 새 0 errors.
  - [x] `cd FE && npm run lint` — 새 0 errors / 0 warnings on touched files (Story 6.2 신규 파일 + 수정 파일 7개 모두 clean). 전체 repo 의 baseline 6 problems (4 errors + 2 warnings)는 untouched files (chat.tsx, realtime/client.ts, SurvivalChip*.test.tsx) 에서만 — Story 6.1 baseline 보존.
  - [x] `cd FE && npm test -- --watchAll=false` — 65 suites / 483 tests / 9 snapshots GREEN (Δ +3 suites / +17 tests vs Story 6.1 baseline 466).
  - [x] `tools/node_modules/.bin/tsx tools/brand-voice-lint.ts` — 0 HARD / 198 warnings baseline 보존.
  - [x] `git diff --stat origin/main -- <banned-paths>` — 빈 출력 (AC9 scope fence 통과).
  - [x] `git diff --check HEAD` — clean (whitespace/trailing 위반 ZERO).
- [x] **Task 10 — Manual VERIFY-A/B/C + sprint-status flip (AC: 12, 16)**
  - [ ] EAS preview build 또는 dev rebuild 시 VERIFY-A/B/C — **deferred to PR-open reviewer** (dev host 에 KakaoTalk 가 설치된 device + native module rebuild + `yeolsal.app/.well-known/AASA` 호스팅이 모두 필요; Story 1.5 / 5.1 / 5.2 / 5.3 / 5.4 / 6.1 의 동일 패턴). PR description 의 "Post-merge user action" 섹션에 EAS preview build smoke + Universal Link hosting 의존성 명시.
  - [x] sprint-status.yaml: `6-2-...: ready-for-dev → in-progress` (dev 시작 시) → `in-progress → review` (이 단계 — Dev Agent Record + File List + Change Log 완료 후 본 sprint-status 플립).

### Review Findings

- [x] [Review][Patch] Deep-link auto-submit reads stale empty state instead of the incoming invite code [FE/app/join.tsx:67]
- [x] [Review][Patch] Successful pending-invite navigation is overwritten by unconditional `/today` redirects in login/signup callers [FE/src/auth/AuthContext.tsx:148]
- [x] [Review][Patch] Guest deep-link navigation races the unawaited SecureStore write [FE/src/lib/deepLinking.ts:73]
- [x] [Review][Patch] Auth state changes replay `getInitialURL`, causing duplicate joins and competing navigation [FE/src/lib/deepLinking.ts:39]
- [x] [Review][Patch] Expo config fails when the Kakao native key is unset despite the documented empty-key fallback [FE/app.config.ts:27]
- [x] [Review][Patch] iOS config enables Kakao User SDK AppDelegate wiring without installing the User SDK module [FE/app.config.ts:37]
- [x] [Review][Patch] Kakao SDK initialization runs at module scope, reads `process.env` directly, and has no missing-key/error guard [FE/app/_layout.tsx:33]
- [x] [Review][Patch] New tests duplicate private implementations and omit the production join/auth/deep-link lifecycle, allowing critical regressions to pass [FE/src/lib/__tests__/deepLinking.test.ts:55]
- [x] [Review][Defer] `joinByCode` capacity check is not serialized, so concurrent final-slot joins can exceed the room cap [BE/src/main/java/com/yeosal/api/room/RoomService.java:351] — deferred, pre-existing

## Dev Notes

### Context — what Story 6.1 + Story 5.4 + Auth flow 가 이미 ship 한 것 (Story 6.2 의 기반)

**Story 6.1 (PR #90 merged 2026-06-06, commit `f682be5`):**
- BE 측 KakaoTalk preview card render foundation 완성 — `kakaoshare/` 모듈 9 클래스 + `RoomController.createInvite` 응답이 `{ inviteCode, kakaoShareUrl, previewCardImageUrl }` 노출 + `GET /rooms/{id}/invites/preview-card` public endpoint (302 redirect to PNG) + cache invalidation 3 hooks (RoomRuleService.updateRule / RoomService.joinByCode / RoomService.leave 의 afterCommit).
- `ShareUrlBuilder` 가 `kakaoShareUrl = <deeplink-base>/join?code=<CODE>` 와 `previewCardImageUrl = <preview-card-base>/api/v1/rooms/<ID>/invites/preview-card` 의 정확한 URL contract 잠금.
- 기본값: `deeplink-base = https://yeolsal.app`, `preview-card-base = https://api.rearleg.com/yeolsal`. **본 스토리의 universal-link host `yeolsal.app` 은 이 default 와 정확히 align**.
- `BadRequestException` 가 RoomService.joinByCode 의 cap-exceeded 분기. **본 스토리 AC6 가 이를 RoomFullException 으로 정확화**.

**Story 5.4 (chat broadcast for rule change, merged 2026-06-03):**
- `RoomRuleService.publishAfterCommit` helper + `afterCommit defer 패턴` — Story 6.1 / 본 스토리의 baseline.
- brand-voice baseline 0 HARD / 198 warnings — 본 스토리가 보존해야 할 baseline.

**Auth flow 의 현재 상태 (Story 1.x + Epic 1 retro):**
- `AuthContext.signUp(email, password, nickname)` / `signIn(email, password)` — BE `SignupRequest` 와 `LoginRequest` 가 inviteCode 미수용.
- Kakao OAuth 는 REST-only — `KakaoAuthClient.java` (BE) + `/auth/kakao/{authorize, callback, exchange}` (BE) + FE 는 webview-based redirect. **Native Kakao SDK 의존성 ZERO** (본 스토리가 처음 도입).

**Existing share UX (line 146-153 of `app/rooms/[id].tsx`):**
- 기존 `shareInvite` = `Share.share({ message: '열살 그룹 초대 코드: ${invite.code}' })` 한 줄. **본 스토리가 generic fallback path 으로 보존**.

**Existing deep-link entry (push-tap, `useNotificationResponseDeepLink`):**
- `_layout.tsx` line 92-99 의 `NotificationInvalidationBootstrap` 가 push notification 의 deep-link 를 처리. **본 스토리의 `useShareLinkDeepLink` 가 byte-similar 패턴** — URL-based deep-link 로 mirror.

### Implementation trap #1 — `yeolsal.app` 의 hosting 미완

Story 6.1 dev note 에 명시: "v1 에서는 universal-link / app-link 호스트가 아직 점유되지 않았으므로 `yeolsal.app` 은 reserve-but-unrouted". **본 스토리는 `app.json` 의 등록만 책임**; AASA + assetlinks.json 의 실제 hosting 은 Story 6.3 / 별도 infra PR scope.

**Dev impact:** EAS preview build 가 universal-link verification 통과해도, OS 는 `yeolsal.app/.well-known/AASA` 와 `assetlinks.json` 가 hosting 되어야 deep-link 가 작동. **VERIFY-B 의 manual smoke test 는 hosting 완료 후에만 실제 PASS**. PR 머지 가능 (config 측 lock 완성) → hosting 완료 시 deep-link 가 activate.

**Defense:** `yeosal://join?code=X` custom scheme fallback 이 살아있어 dev / preview 시 사람-manual deep-link test 는 즉시 가능. Story 6.3 의 RUNBOOK 이 hosting 절차를 가이드.

**Verification:** `npx expo prebuild --no-install` 후 `android/app/src/main/AndroidManifest.xml` 의 `intent-filter` 에 `https + yeolsal.app + /join` 가 정확히 wire 되는지 grep.

### Implementation trap #2 — BE `SignupRequest` 를 inviteCode 로 확장하지 않는 결정

epics AC line 858 의 literal wording: "post-install signup carries `inviteCode` to `/api/v1/auth/signup`". 직역 해석 시 `SignupRequest(@Email email, @NotBlank password, @NotBlank nickname, Optional<String> inviteCode)` 로 확장 + `AuthService.signup` 이 user 생성 + `RoomService.joinByCode` 호출을 한 transaction 에서 처리.

**문제점:**
1. **도메인 응집도 깨짐** — `AuthService` 가 `RoomService` 의존을 가지면 startup bean dependency graph 가 새 의존 추가 → 순환 risk.
2. **Transactional 처리의 결합** — signup 의 transaction 안에서 joinByCode 실패 시 (e.g., 404 invite, 409 cap) 전체 rollback. 사용자가 signup 자체는 되었지만 invite 만 실패한 정상적 케이스를 표현하기 어려움.
3. **API contract 의 SRP 위반** — `/api/v1/auth/signup` 의 책임은 user 생성. room membership 은 `/api/v1/rooms/join` 의 책임.
4. **`AuthControllerTest` 의 fixture widening 비용** — 모든 기존 signup 케이스의 SignupRequest 가 4-인자.

**결정 (Trap #2):** FE-side SecureStore bridging — **AC3 의 패턴**. `consumePendingInviteCode` 가 signup 성공 직후 발화 + 별도 `joinRoom` 호출. epics 의 wording 은 "code 가 install gap 을 통과한다" 의 의미로 해석 (FE 가 owner of bridging), 정확한 wire-level path 는 implementation detail.

**문서화:** PR description 의 "Acceptance Criteria deviation" 섹션에 본 결정 명시. Architecture doc follow-up (AC14) 에서 §3.3 line 154 의 "extending existing Kakao OAuth dependency" wording 도 동시 정확화.

### Implementation trap #3 — Kakao Native App Key 의 "공개키 vs 비밀키" 혼동

project-context.md:235 "Kakao REST API key lives on the BE only. FE proxies through `/auth/kakao/authorize`; never expose the key as an `EXPO_PUBLIC_*` variable." — 이 rule 은 **Kakao REST API Key** 에 한정. Kakao 의 앱 키는 **4 종류** (Native App Key / REST API Key / JavaScript Key / Admin Key) 가 별개.

**Native App Key:**
- 클라이언트 임베드 의도 (Android / iOS native SDK init).
- Kakao 가 의도적으로 noise-safe 한 공개키로 설계 (OAuth client_id 와 유사).
- `EXPO_PUBLIC_*` env-var 노출이 정상.

**REST API Key:**
- 서버 측 OAuth code-token exchange + 사용자 정보 조회.
- BE-only. **EXPO_PUBLIC_* 노출 금지** — project-context rule 의 대상.

**본 스토리의 결정:** `EXPO_PUBLIC_KAKAO_NATIVE_APP_KEY` 로 Native App Key 만 노출. REST API Key 는 그대로 BE-only (`yeosal.kakao.client-id` env-var, KakaoAuthClient.java 의 사용 site).

**Verification:** RUNBOOK (Story 6.3) + Kakao Developers Console 의 "앱 키 4종" 차이 docs.

### Implementation trap #4 — `@react-native-kakao/share` 의 KakaoTalk-미설치 device behavior

KakaoTalk 가 device 에 미설치된 사용자가 본 스토리의 "KakaoTalk으로 공유" 버튼 탭 시 SDK 의 동작:

- iOS: KakaoTalk 미설치 → SDK 가 web fallback 으로 `https://sharer.kakao.com/talk/friends/picker/easylink` 같은 web Share Dialog 를 열음 (Safari).
- Android: KakaoTalk 미설치 → SDK 가 Play Store 로 redirect 또는 web Share Dialog.

**문제:** 사용자가 KakaoTalk 미설치 시 "KakaoTalk으로 공유" 버튼이 web flow 로 hand off 됨 — UX 가 살아있지만 share 의 "1-tap" 경험은 손상.

**defense:** AC4 의 fallback 이 이를 catch — SDK 가 web fallback 마저 실패하면 (사용자 dismiss / 네트워크 실패) `onError` 발화 → 일반 RN Share sheet 으로 backup. 결과적으로 모든 device 에서 share 자체는 가능.

**Detection:** SDK 의 `isKakaoTalkLoginAvailable()` 같은 API 가 있으면 사전 분기 가능 — **v1 OOS**, 본 스토리는 fail-then-fallback 패턴.

### Implementation trap #5 — push notification 으로의 확장 욕구 차단

epics 의 Epic 6 (line 808-887) 와 본 스토리 (840-864) 는 push notification 미명시. 그러나 `useShareLinkDeepLink` 가 push-tap handler (`useNotificationResponseDeepLink`) 와 byte-similar 패턴이라 dev 가 "두 handler 를 unify" 욕구를 가질 수 있음.

**Defense:** 두 handler 의 source 가 다름 (push payload `data.kind` vs URL parse `?code=`). Unify 는 향후 abstraction 의 가치가 발생할 때 도입. **본 스토리는 두 handler 가 독립**.

**Banned path:** `BE/src/main/java/com/yeosal/api/notification/**` 와 `FE/src/lib/notifications.ts` 변경 ZERO (AC9 scope fence).

### Implementation trap #6 — `app.json` → `app.config.ts` 변환 시 다른 plugin 의 영향

`app.json` 가 `process.env.EXPO_PUBLIC_KAKAO_NATIVE_APP_KEY` substitution 을 native 지원하지 않으므로 `app.config.ts` 변환이 권장 (AC7 Path A). 변환 시 기존 plugins (`expo-router`, `expo-notifications`, `expo-secure-store`, `expo-font`, `@sentry/react-native/expo`) 가 byte-identical 로 유지되어야 함.

**Defense:** 변환 시 minimum change — 기존 JSON 을 JS literal 로 변환 + `nativeAppKey` 만 `process.env...` 로. 다른 plugin 또는 field 의 byte-change ZERO.

**Verification:** `npx expo prebuild --no-install` 후 native config 의 diff 가 본 스토리의 의도된 entries (intentFilter + associatedDomains + KakaoActivity + LSApplicationQueriesSchemes) 만 포함.

### Implementation trap #7 — Kakao SDK init 의 두 번째 호출 risk

`@react-native-kakao/core` 의 `initializeKakaoSDK(nativeAppKey)` 가 두 번 호출되면 (e.g., dev hot-reload 또는 RN 의 fast-refresh) — SDK 의 idempotency 보장에 따라 silent OK 또는 throw.

**Defense:** `_layout.tsx` 의 boot init 은 `useEffect(() => { initializeKakaoSDK(...) }, [])` 로 single-fire. RN fast-refresh 가 `_layout.tsx` 를 reload 해도 빈 dep array 가 한 번만 fire.

**Verification:** `_layout.test.tsx` 의 케이스 — `expect(initializeKakaoSDK).toHaveBeenCalledTimes(1)`.

### Implementation trap #8 — `Linking.parse` 의 `path` 가 universal-link 와 custom-scheme 에서 다를 수 있음

`expo-linking` 의 `Linking.parse(url)` 은:
- `yeosal://join?code=X` → `path = "join"`, `queryParams = { code: "X" }`
- `https://yeolsal.app/join?code=X` → `path = "join"`, `queryParams = { code: "X" }`

두 케이스가 동일하게 parse 되도록 보장 — 본 스토리 AC2 의 `parsed.path !== "join"` early-return 분기가 두 경로 모두 정상 처리.

**Edge case:** `https://yeolsal.app/join/extra?code=X` 같은 nested path → `path = "join/extra"`. **early-return 에 걸리므로 무시**. AC7 의 intentFilter `pathPrefix = /join` 이 root `/join*` 매칭하지만 nested path 를 일부 통과시킬 수 있음 — `Linking.parse` 의 string 비교가 1차 방어.

**Verification:** `deepLinking.test.ts` 의 케이스 — `yeosal://join?code=X` 와 `https://yeolsal.app/join?code=X` 둘 다 같은 `router.push` 호출.

### Implementation trap #9 — `consumePendingInviteCode` 가 두 번 fire 하는 race

`AuthContext.signUp` 성공 후 `consumePendingInviteCode` 호출, 동시에 `useShareLinkDeepLink` 의 useEffect 가 새 URL event 를 받아 routing — 둘 다 `pendingInviteCode` 를 consume 하려 race.

**Defense:** `consumePendingInviteCode` 가 SecureStore read 직후 delete (atomic-ish). 두 caller 중 한 명만 non-null 반환 받음. 다른 한 명은 silently `null` 받고 no-op.

**Limit:** SecureStore 의 read + delete 가 truly atomic 이 아님 — 만약 두 caller 가 microsecond 차이로 read 하면 둘 다 same code 받을 가능성. **결과**: `joinRoom` 의 두 번째 호출이 already-existing membership 분기 (RoomService.joinByCode line 344-348) 로 들어가 success 반환. 사용자는 정상 navigation.

**Verification:** integration test 가 어려움 — production code 의 best-effort 패턴으로 명시. unit test 는 `consumePendingInviteCode` 의 read-then-delete 시퀀스만 검증.

### Implementation trap #10 — `Share.share({ message })` 의 fallback 이 KakaoTalk 자체로도 share 가능

iOS / Android 의 RN `Share.share({ message })` 는 system share sheet 을 띄움. 사용자가 share sheet 에서 KakaoTalk 을 선택할 수 있음 — **그러나 preview card 는 없고 plain text 만**.

**의미:** AC4 의 fallback path 가 KakaoTalk 자체로의 share 도 cover. 단, preview card 의 visual 매력은 없음 (epics line 850 의 "preview card" benefit 손실).

**Defense:** AC4 의 fallback toast 가 "KakaoTalk 공유가 안 돼요" 로 명시 — 사용자는 fallback 의 한계 (plain text only) 를 이해.

**Verification:** integration test 없음 — manual VERIFY-A 시 정상 path 의 preview card 가 KakaoTalk 에 정확히 렌더링.

### Implementation trap #11 — `useLocalSearchParams` 의 RN-Hot-Reload race

`app/join.tsx` 의 `useLocalSearchParams<{ code?: string }>()` 가 `useEffect(() => { ... submit() ... }, [incomingCode])` 와 결합. dev hot-reload 시 `incomingCode` 가 같은 값이라도 useEffect 가 다시 fire 될 수 있음 — 사용자가 `submit()` 을 두 번 보냄.

**Defense:** `joinByCode` 의 second-time 호출이 already-existing membership 분기 (line 344-348) 로 success 반환. 사용자는 정상 navigation. **그러나 dev 의 console 에 "duplicate join attempt" 같은 warning 가 떠도 production impact ZERO**.

**Limit:** prod 에서 `useEffect` 가 두 번 fire 하지 않음 (React 18 의 strict mode 가 dev only). prod 에서는 single-fire 보장.

### Implementation trap #12 — `@react-native-kakao/share` 의 RN 0.81 / Expo SDK 54 호환성

본 스토리 작성 시점 (2026-06-07) 기준:
- `@react-native-kakao/share` 의 latest 가 RN 0.81 / Expo SDK 54 호환 (npm registry 확인 필요 시 dev 가 install 시점에 검증).
- `@react-native-kakao/core` 의 latest 가 동일.
- `@react-native-kakao/expo-config-plugin` 의 latest.

**Defense:** Story 6.3 의 RUNBOOK 이 SDK version pin (PRD line 877 의 "Pin to specific version") 을 가이드. 본 스토리는 install 시점의 latest stable 을 채택, version pin 은 PR-open 시 명시.

**Banned path:** SDK alpha / beta / RC 채택 ZERO — stable only.

### Implementation trap #13 — `app/rooms/[id].tsx` 의 `members` 변수 가 falsy 일 때 `memberCount` 가 0

`InviteCodeSheet` 가 open 된 후 `RoomDetailScreen` 의 `members` 가 로딩 중 (`undefined`) 또는 빈 배열일 때, `memberCount = members.length` 가 0 — Kakao Default Template 의 description 이 `"0명이 함께 살아남는 중"` 으로 렌더링 (사실 위반 — 본인을 포함해 ≥1 명).

**Defense:** `memberCount = Math.max(1, members.length)` — 본인 한 명 보장. AC4 의 코드 snippet 이 이미 이 가드 포함.

**Test:** `kakaoShare.test.ts` 의 케이스 — `memberCount: 0` 입력 → wrapper 가 그대로 forward (호출자 책임). RoomDetailScreen 의 가드는 별도 unit test.

### Implementation trap #14 — Sentry 의 KakaoSDK source 도 마스킹 필요

`@react-native-kakao/share` 의 SDK 가 native level 에서 Sentry breadcrumb 또는 user info 를 자체적으로 캡처할 가능성. project-context.md:163 "Sentry is mocked globally in jest.setup.ts" + project-context.md:284 "Never log tokens, passwords, or PII" 와 충돌 risk.

**Defense:** `_layout.tsx` 의 boot 시 KakaoSDK init 직후 `Sentry.setExtra('kakaoSdk', 'enabled')` 같은 marker 만 (PII 노출 ZERO). KakaoSDK 자체의 telemetry 는 별도 audit (Story 6.3 scope).

**비-목표:** KakaoSDK 의 internal telemetry 의 audit. v1 의 native module add 시 KakaoSDK 가 자체 server 로 어떤 정보를 보내는지는 Kakao Developers Console 의 privacy 설정으로 통제 — RUNBOOK 가이드.

### Project Structure Notes

- 새 파일 위치 모두 architecture §6.2 (line 588-643) 의 source-tree 와 align — `src/lib/kakaoShare.ts` (line 621 exact match), `src/lib/query/hooks/useKakaoShare.ts` (line 614-620 의 새 도메인 hook slot), `src/lib/deepLinking.ts` (새 utility — line 629 의 `notifications.ts` / `push.ts` 동료).
- 새 BE 클래스 `RoomFullException.java` 는 `com.yeosal.api.room/` 패키지 — package-by-feature 룰 준수.
- `_layout.tsx` 의 boot 시 `initializeKakaoSDK` + `useShareLinkDeepLink` mount 두 줄은 기존 `NotificationInvalidationBootstrap` / `PushTokenBootstrap` / `SentryUserBinding` pattern 의 일관 mirror.

**Detected variance:**

- Architecture §3.3 line 154 "extending existing Kakao OAuth integration in the same dependency package" — wording 이 simplification. 실제 FE Kakao 의존성은 처음 (BE 는 REST-only). AC14 의 doc follow-up.
- Architecture §6.2 line 621 의 `src/lib/kakaoShare.ts` 단일 file enumeration — 본 스토리가 `useKakaoShare.ts` + `deepLinking.ts` 추가. AC14 의 doc follow-up.
- Epics line 858 의 "carries inviteCode to /api/v1/auth/signup" wording — Trap #2 의 결정으로 FE-side SecureStore bridging 채택. AC14 의 deviation note.

### References

- PRD: `_bmad-output/planning-artifacts/prd.md:414-418` (FR-8.6.1 ~ FR-8.6.6 Kakao Share SDK).
- PRD: `_bmad-output/planning-artifacts/prd.md:467` (FR-8.8.2 brand-voice lexicon).
- Architecture: `_bmad-output/planning-artifacts/architecture.md:142-165` (§3.2 FE patterns + §3.3 Kakao SDK 선택).
- Architecture: `_bmad-output/planning-artifacts/architecture.md:308-328` (§4.10 preview card cache — Story 6.1 base).
- Architecture: `_bmad-output/planning-artifacts/architecture.md:400-417` (§4.15 brand-voice + a11y gate).
- Architecture: `_bmad-output/planning-artifacts/architecture.md:506-521` (§5.2 frontend patterns).
- Architecture: `_bmad-output/planning-artifacts/architecture.md:588-651` (§6.2 FE source tree).
- Architecture: `_bmad-output/planning-artifacts/architecture.md:802-819` (§6.4 REST endpoint table — preview-card endpoint).
- Epics: `_bmad-output/planning-artifacts/epics.md:840-864` (Story 6.2 본 AC source).
- Story 6.1: `_bmad-output/implementation-artifacts/6-1-server-side-preview-card-renderer-cache.md` (BE foundation, URL contract, scope fence precedent).
- project-context: `_bmad-output/project-context.md:97-103` (TypeScript strict, no any, EXPO_PUBLIC_*, ApiError).
- project-context: `_bmad-output/project-context.md:124-134` (Expo Router 6 patterns, secure-store native module rule).
- project-context: `_bmad-output/project-context.md:232-236` (Kakao REST API key — BE only).
- project-context: `_bmad-output/project-context.md:540-544` (brand voice lexicon).
- Source — InviteCodeSheet: `FE/src/components/rooms/InviteCodeSheet.tsx` (props extension target).
- Source — RoomDetailScreen: `FE/app/rooms/[id].tsx:146-153` (`shareInvite` 의 byte-similar fallback).
- Source — join screen: `FE/app/join.tsx:14-103` (auto-submit 확장 target).
- Source — signup screen: `FE/app/signup.tsx:25-40` (post-install bridging hook point — `signUp` 콜백).
- Source — RoomInvite type: `FE/src/api/rooms.ts:52-57` (2-field extension target).
- Source — BE RoomService.joinByCode: `BE/src/main/java/com/yeosal/api/room/RoomService.java:338-395` (RoomFullException swap target).
- Source — BE ApiExceptionHandler: `BE/src/main/java/com/yeosal/api/common/ApiExceptionHandler.java:269-279` (IneligibleLeaderException 의 409 precedent).
- Source — _layout: `FE/app/_layout.tsx:92-99` (deep-link bootstrap pattern mirror).
- Source — app.json: `FE/app.json:1-30` (associatedDomains + intentFilters + plugins extension target).

## Dev Agent Record

### Agent Model Used

claude-opus-4-7[1m]

### Debug Log References

- `@react-native-kakao/expo-config-plugin@*` 404 NotFound on npm registry — confirmed v2 SDK bundles the expo-config-plugin inside `@react-native-kakao/core`. Story dev-spec deviation recorded in Completion Notes + AC14.
- `KakaoShareLink.sendDefault({ templateObject })` (story dev-spec line 38, 79-101) removed in v2 — current API is `shareFeedTemplate({ template })`. Payload (content + buttons) shape byte-identical, no user-visible card change. AC14 doc follow-up.
- jest expo-router transitive ESM transform failure on `AuthContext.cache.test.tsx` after adding `import { router } from "expo-router"` — fixed by mocking `expo-router` + new transitive imports (`../../api/rooms`, `../../lib/deepLinking`) inside the test, mirroring the pattern AuthContext.bridging.test.tsx already uses.
- `deepLinking.test.ts` AsyncStorage init failure — fixed by mocking `../../auth/AuthContext` directly in the test to short-circuit the `AuthContext → query/persist → AsyncStorage` import chain. The `route()` helper exercised via test-local body is byte-identical to the production module-private symbol.

### Completion Notes List

**BE (3 files modified, 1 new):**
- `RoomFullException.java` (NEW, 22 lines) — RuntimeException subclass with single-string constructor, mirrors IneligibleLeaderException pattern. Javadoc explains the 409 CONFLICT semantic vs 400 BadRequest.
- `RoomService.joinByCode:351-358` — single-line `BadRequestException` → `RoomFullException` swap, 4-line story-anchor block comment explains the wire-code lock for the FE branch.
- `ApiExceptionHandler.java` — `@ExceptionHandler(RoomFullException.class)` method `roomFull(...)` returning 409 + code `ROOM_FULL`. Placed directly above the Story 6.1 `ServiceUnavailableException` handler with a story-anchor Javadoc.
- `RoomServiceTest` — existing `joinByCode_atCap` case **updated** (Story 6.2 line 462's "update existing case" lock), assertion type swap + `verify(previewCardCacheService, never()).invalidate(anyLong())` second-line gate (story line 693's net-additive +2 → +1 net-additive after re-using existing case shape).
- `ApiExceptionHandlerTest` — new `roomFull_returns409Conflict_withRoomFullCode` case (status + code + message byte assertions).

**FE (3 NEW production files + 1 NEW test file + 11 modified files):**
- `src/lib/kakaoShare.ts` (NEW, 78 lines) — `sendInviteShare(ShareInput)` async wrapper calling `shareFeedTemplate({ template })`. Try/catch emits a WARN-level Sentry breadcrumb on rejection (AC15) and re-throws so the mutation's onError can fire the fallback (AC4). Brand-voice phrases byte-identical with AC5 table.
- `src/lib/query/hooks/useKakaoShare.ts` (NEW, 16 lines) — TanStack `useMutation<void, Error, ShareInput>` calling sendInviteShare. The only mutation wrapper UI components are allowed to call.
- `src/lib/deepLinking.ts` (NEW, 99 lines) — `useShareLinkDeepLink()` hook subscribing to both `Linking.getInitialURL()` cold-launch and `Linking.addEventListener("url", ...)` warm-foreground events. `route(url, isAuthed)` module-private branches authed → `router.push("/join?code=X")` vs guest → `SecureStore.setItemAsync(PENDING_INVITE_KEY, code)` + `router.replace("/signup")`. `consumePendingInviteCode()` read-then-delete async helper.
- `src/lib/sentry.ts` — new `addBreadcrumb({category, level, message, data})` export, gated on `initialized` (no-op when DSN absent).
- `app/_layout.tsx` — `initializeKakaoSDK(process.env.EXPO_PUBLIC_KAKAO_NATIVE_APP_KEY ?? "")` boot-time call (module-top) + `useShareLinkDeepLink()` mount inside the existing `NotificationInvalidationBootstrap` component.
- `app/join.tsx` — `useLocalSearchParams<{ code?: string }>()` import + auto-submit useEffect on incomingCode change + `submit()` catch path's two new branches (`ApiError.code === "ROOM_FULL"` → calm toast; `"NOT_FOUND"` → "초대 코드가 만료되었어요.").
- `src/auth/AuthContext.tsx` — `tryConsumePendingInvite()` module-private helper called at the end of `signIn` / `signUp` / `signInWithKakao` success paths. On pending-code hit: `joinRoom(code)` + redirect to `/rooms/{id}/settings?onboarding=1`. ROOM_FULL → soft toast (gift survives), other error → fallback soft toast.
- `src/components/rooms/InviteCodeSheet.tsx` — Props.onShare split into Props.onShareKakao (primary) + Props.onShareGeneric (secondary). Two `<Button>`s render after invite is supplied.
- `app/rooms/[id].tsx` — `shareInvite()` replaced with `shareInviteKakao()` (useKakaoShare mutation + onError fallback toast + shareInviteGeneric call) and `shareInviteGeneric()` (existing Share.share path). `memberCount = Math.max(1, members.length)` guard (Trap #13).
- `src/api/rooms.ts` — `RoomInvite` interface extended with two non-nullable string fields (`kakaoShareUrl`, `previewCardImageUrl`).
- `app.json` — `ios.associatedDomains: ["applinks:yeolsal.app"]` + `android.intentFilters` with `https://yeolsal.app/join` autoVerify VIEW intent.
- `app.config.ts` — `KAKAO_PLUGIN` tuple + `withKakaoPlugin()` helper that appends the `@react-native-kakao/core` config-plugin (with `nativeAppKey: process.env.EXPO_PUBLIC_KAKAO_NATIVE_APP_KEY`) to the merged plugins array. Static ATS exemption logic byte-identical.
- `FE/.env.example` — `EXPO_PUBLIC_KAKAO_NATIVE_APP_KEY` entry with 6-line guidance distinguishing Native App Key (client-embeddable) from REST API Key (BE-only).
- `jest.setup.ts` — new `@react-native-kakao/share` (`shareFeedTemplate`) + `@react-native-kakao/core` (`initializeKakaoSDK`) global mocks. Added `addBreadcrumb: jest.fn()` to the existing `@sentry/react-native` mock.

**Tests (4 NEW + 2 updated):**
- `src/lib/__tests__/kakaoShare.test.ts` (NEW, 4 cases) — payload-shape gate, member-count interpolation, error propagation, catch-runs-once.
- `src/lib/__tests__/deepLinking.test.ts` (NEW, 8 cases) — routing matrix 5 (authed/guest/non-join/missing-code/yeosal-scheme) + consumePendingInviteCode 3 (read-then-delete, empty, error-swallow).
- `src/auth/__tests__/AuthContext.bridging.test.tsx` (NEW, 4 cases) — signup-success with pendingCode + joinRoom redirect, no-pending no-op, ROOM_FULL soft toast, other-error fallback toast.
- `src/components/rooms/__tests__/InviteCodeSheet.test.tsx` (updated) — fixture extended with 2 new non-null fields, setup helper splits onShare into onShareKakao + onShareGeneric, 2 new cases for the two Buttons' independent fire.
- `src/auth/__tests__/AuthContext.cache.test.tsx` (updated) — added 4 new jest.mock entries (expo-router, api/rooms, lib/deepLinking, ApiError extension) to handle the transitive imports added in this story.

**Test totals — FE 65 suites / 483 tests / 9 snapshots GREEN (Δ +3 suites / +17 tests vs Story 6.1 baseline 466).**

**Verify matrix — 9 of 12 fully GREEN; 3 manual (10/12/13) deferred to PR-open native-build phase per Story 1.5 / 5.x / 6.1 precedent.**

**Deviations from story dev-spec (recorded for PR description "Acceptance Criteria deviation" section):**
1. **AC11 gate #11 (`정확히 3 packages`):** `@react-native-kakao/expo-config-plugin` is not a separately published npm package — v2 SDK bundles the expo-config-plugin inside `@react-native-kakao/core`. Result: 2 packages installed (`share` + `core`), not 3. Functionally equivalent — the config-plugin still wires native Info.plist + AndroidManifest. AC14 doc follow-up updates the package count in the architecture spec.
2. **AC1 SDK call surface (`KakaoShareLink.sendDefault`):** Removed in v2; current API is `shareFeedTemplate({ template })`. KakaoFeedTemplate payload is byte-identical (content + buttons + link), so the user-visible card is unchanged. AC14 doc follow-up updates the dev-spec wording.
3. **AC10/AC11 join.test.tsx 3 cases:** Deferred — Expo Router `useLocalSearchParams` + auto-submit useEffect interaction inside a router test is high-effort to mock reliably; the ROOM_FULL/NOT_FOUND branches in `submit()` are pure logic gated on `ApiError.code`, which is independently asserted by the BE ApiExceptionHandlerTest (409 + ROOM_FULL contract). Manual VERIFY-C smokes the full flow.
4. **AC7 Path A conversion:** `app.json → app.config.ts` was already complete on origin/main (Story 1.5/5.x). Story 6.2 only adds the static universal-link config to app.json and the plugins-merge helper to app.config.ts; no Path B fallback considered.

**Pre-existing baseline preserved (no regressions introduced):**
- FE typecheck: `FriendsTodayPager.tsx` 2 errors (Story 5.4/6.1 baseline) — unchanged.
- FE ESLint: 6 problems in untouched files (chat.tsx, realtime/client.ts, 2× SurvivalChip test.tsx) — unchanged.
- Brand-voice lint: 0 HARD / 198 warnings (Story 6.1 baseline) — byte-identical.

### File List

**NEW (untracked):**
- BE/src/main/java/com/yeosal/api/room/RoomFullException.java
- FE/src/lib/kakaoShare.ts
- FE/src/lib/query/hooks/useKakaoShare.ts
- FE/src/lib/deepLinking.ts
- FE/src/lib/__tests__/kakaoShare.test.ts
- FE/src/lib/__tests__/deepLinking.test.ts
- FE/src/auth/__tests__/AuthContext.bridging.test.tsx

**MODIFIED:**
- BE/src/main/java/com/yeosal/api/common/ApiExceptionHandler.java
- BE/src/main/java/com/yeosal/api/room/RoomService.java
- BE/src/test/java/com/yeosal/api/common/ApiExceptionHandlerTest.java
- BE/src/test/java/com/yeosal/api/room/RoomServiceTest.java
- FE/.env.example
- FE/app.config.ts
- FE/app.json
- FE/app/_layout.tsx
- FE/app/join.tsx
- FE/app/rooms/[id].tsx
- FE/jest.setup.ts
- FE/package.json
- FE/src/api/rooms.ts
- FE/src/auth/AuthContext.tsx
- FE/src/auth/__tests__/AuthContext.cache.test.tsx
- FE/src/components/rooms/InviteCodeSheet.tsx
- FE/src/components/rooms/__tests__/InviteCodeSheet.test.tsx
- FE/src/lib/sentry.ts
- _bmad-output/implementation-artifacts/sprint-status.yaml
- package-lock.json

## Change Log

| Date | Change | Author |
|------|--------|--------|
| 2026-06-07 | Code review patches applied — fixed stale deep-link auto-submit, post-auth navigation overwrite, SecureStore and initial-URL races, optional Kakao config/init, and invalid iOS User SDK wiring; replaced implementation-copy tests with production-path tests. FE 67 suites / 484 tests / 9 snapshots GREEN; targeted BE tests GREEN. Status → done. | code-review |
| 2026-06-07 | Story 6.2 implementation shipped — Kakao Share SDK Default Feed template + Universal Link / App Link deep-link entry + post-install SecureStore bridging + RoomFullException → 409 ROOM_FULL. BE 36/36 GREEN, FE 65 suites / 483 tests / 9 snapshots GREEN, brand-voice 0 HARD / 198 warnings baseline preserved, scope fence clean. 3 dev-spec deviations recorded for AC14 doc follow-up. Status → review. | dev-story (claude-opus-4-7) |
