# 분석 이벤트 분류 (Analytics SDK + Taxonomy)

> Story 8.5 산출물. PostHog self-hosted 결정과 이벤트 분류표의 single-source-of-truth.

---

## 1. Decision summary

- **SDK:** PostHog self-hosted (FE: [`posthog-react-native`](https://posthog.com/docs/libraries/react-native) v4.x, BE: [`posthog-java`](https://posthog.com/docs/libraries/java) v1.x).
- **Self-host deployment:** `infra/posthog.sh` 가 공식 PostHog hobby installer를 40자 commit SHA로 고정해 실행합니다.
- **Data residency:** 운영 VPS (KR region) 와 같은 곳. 국외 전송 없음 → PIPA gold.
- **Rationale source-of-truth:** [`_bmad-output/planning-artifacts/analytics-sdk-decision-2026-06-09.md`](../_bmad-output/planning-artifacts/analytics-sdk-decision-2026-06-09.md) (PR #101 / commit 5c02f6e). 본 문서는 결정 근거를 중복 서술하지 않습니다 — 의사결정 맥락이 필요하면 그 doc 을 참조하세요.

---

## 2. Env vars

| Env var | Side | Required? | Source |
|---|---|---|---|
| `EXPO_PUBLIC_POSTHOG_HOST` | FE | optional (dev) / required (prod EAS) | EAS secret |
| `EXPO_PUBLIC_POSTHOG_API_KEY` | FE | optional (dev) / required (prod EAS) | PostHog Project API Key (NOT Personal API Key) |
| `EXPO_PUBLIC_INTERNAL_BUILD` | FE | optional | `"true"` for internal/staff EAS profiles |
| `POSTHOG_HOST` | BE | optional (dev) / required (prod) | Spring env |
| `POSTHOG_PROJECT_API_KEY` | BE | optional (dev) / required (prod) | Spring env |
| `POSTHOG_PERSONAL_API_KEY` | BE | optional | only for admin lint-helper Phase-2 (NOT used by Story 8.5 lint helper — that is source-only AST walk) |

- FE 의 `EXPO_PUBLIC_POSTHOG_API_KEY` 는 **Project API Key** (client-embeddable, public). Personal API Key 는 EAS 번들에 절대 들어가지 않습니다 (Story 6.2 의 Kakao REST API Key 정책과 같은 라인).
- BE 의 `yeosal.analytics.enabled` 가 `false` 면 `NoOpAnalyticsService` bean 이 wired 됩니다 — 환경변수 비어있어도 부팅 정상.

---

## 3. Event taxonomy

5 funnels / 21 events. 이름은 snake-case 의 dot 구분자.

### Funnel 1 — Activation (FR-8.8.1)

| Event | Side | Properties | Emission point |
|---|---|---|---|
| `signup.completed` | FE | `{authMethod: "EMAIL" \| "KAKAO"}` | `AuthContext.signUp` / `signInWithKakao` 성공 |
| `onboarding.screen.dwell_ms` | FE | `{screen: 1\|2\|3\|4\|5, dwellMs: number}` | per-screen — emission site 는 Story 8.1 |
| `onboarding.completed` | FE | `{}` | onboarding finish — Story 8.1 |
| `first_daily_entry` | FE | `{roomId: number}` | 첫 `useDailyEntryMutation` 성공 — Story 8.1 backfill 범위, 본 스토리에서는 deferred |
| `activation.24h_complete` | BE | `{firstEntryAt: ISO8601, deltaSinceSignupMs: number}` | NotificationLog idempotency-keyed 06:00 KST 평가자 — emission deferred (OOS 14·15) |

### Funnel 2 — Revival flows (FR-8.3.*)

| Event | Side | Properties | Emission point |
|---|---|---|---|
| `revival.attempted` | FE | `{source: "FREE_TICKET" \| "PERSONAL_POINTS" \| "FRIEND_GIFT" \| "KUDOS", roomId: number}` | `useSelfRevival` / `useSendFriendGift` / `useSendKudos` mutation 시작 — emission deferred (backfill OOS) |
| `revival.succeeded` | BE | `{source, roomId, revivalEventId: number, poolAfter: number}` | `RevivalService` commit |
| `revival.failed` | FE | `{source, reason: "INSUFFICIENT_POINTS" \| "ALREADY_REVIVED" \| "FORBIDDEN" \| "NETWORK"}` | mutation `onError` — `ApiError.code` 분기 |
| `kudos.sent` | BE | `{roomId, targetUserId}` | `KudosService` commit |
| `kudos.received` | BE | `{roomId, fromUserId}` | KudosRealtimeListener push fan-out |

### Funnel 3 — Friend-gift conversion (FR-8.3.3 / 8.3.4)

| Event | Side | Properties | Emission point |
|---|---|---|---|
| `friend_gift.push_sent` | BE | `{roomId, receiverUserId, giverUserId}` | `EligibleGiverPushListener.sendEvent` 성공 (per giver, per RED elimination) |
| `friend_gift.push_opened` | FE | `{roomId}` | push-tap deep-link — `useNotificationResponseDeepLink` FRIEND_GIFT_PROMPT 분기 |
| `friend_gift.modal_opened` | FE | `{source: "PUSH_INITIATED" \| "WALLET_INITIATED", roomId}` | FriendGiftModal mount |
| `friend_gift.modal_closed` | FE | `{outcome: "revival_sent" \| "kudos_sent" \| "cancelled", roomId}` | modal close |

### Funnel 4 — Spectator → revival cohort (PRD §13.1)

| Event | Side | Properties | Emission point |
|---|---|---|---|
| `spectator.entered` | BE | `{roomId, eliminatedAt: ISO8601}` | `SurvivalStateService` ACTIVE/YELLOW → RED transition (기존 `SurvivalStateTransitionEvent` AFTER_COMMIT 리스너 라이딩) |
| `spectator.app_opened` | FE | `{}` | Today tab focus 시 `useIsSpectatorEverywhere() === true` |
| `spectator.wallet_viewed` | FE | `{roomId}` | Wallet route focus 시 spectator |
| `spectator.revival_succeeded.day_n` | BE | `{day: 1..30, roomId}` | revival commit — 사용자가 SPECTATOR 였던 일수 ≥ 1 일 (BE 만 derivation) |

### Funnel 5 — Final-3 share-rate (PRD §2.3 #5)

| Event | Side | Properties | Emission point |
|---|---|---|---|
| `final_three.poster_viewed` | FE | `{roomId, yearMonth}` | `FinalThreeCard` mount + visible (≥1 frame) |
| `final_three.share_tapped` | FE | `{roomId, yearMonth, channel: "KAKAO" \| "GENERIC"}` | Kakao/generic share 버튼 press |
| `final_three.share_completed` | FE | `{roomId, yearMonth, channel}` | Kakao SDK `.then()` / generic share completion callback |

### Locked event catalogue (machine-readable — lint helper source-of-truth)

`tools/analytics-taxonomy-lint.ts` 가 아래 fenced block 을 파싱합니다. 새 이벤트는 (a) 위 표 + (b) 아래 block + (c) `FE/src/lib/analytics.ts` 의 `ANALYTICS_EVENTS` 세 곳에 동시에 추가해야 합니다.

```analytics-events
signup.completed
onboarding.screen.dwell_ms
onboarding.completed
first_daily_entry
activation.24h_complete
revival.attempted
revival.succeeded
revival.failed
kudos.sent
kudos.received
friend_gift.push_sent
friend_gift.push_opened
friend_gift.modal_opened
friend_gift.modal_closed
spectator.entered
spectator.app_opened
spectator.wallet_viewed
spectator.revival_succeeded.day_n
final_three.poster_viewed
final_three.share_tapped
final_three.share_completed
```

---

## 4. User properties

PostHog `identify` 가 set 하는 person properties. PII 없음.

| Property | Type | Emission point | Privacy classification |
|---|---|---|---|
| `user_id` | string (BE PK as string) | `identify(distinct_id, ...)` 의 distinct_id (Sentry 와 동일 매핑) | non-PII (internal opaque id) |
| `account_age_days` | number | 가입 시각을 제공하는 신뢰 가능한 API가 추가된 뒤 전송. Story 8.5에서는 거짓 `0` 대신 생략 | non-PII |
| `room_count` | number | v1: 하드코딩 `1` (mandatory single room). v1.5 revisit 코멘트 표기 | non-PII |
| `current_survival_state` | `"ACTIVE" \| "YELLOW" \| "RED" \| "SPECTATOR"` | `useIsSpectatorEverywhere` 또는 `useMeSurvivalQuery` snapshot — leaf component 가 선택 | non-PII |
| `is_room_leader` | boolean | `useRoomsQuery` snapshot | non-PII |
| `is_internal` | boolean | guarded `runtime.process?.env?.EXPO_PUBLIC_INTERNAL_BUILD === "true"` | non-PII (KPI 필터 용) |

**No email, no nickname, no phone.** Sentry 는 PII (`email`) 를 set 하지만 PostHog 는 PIPA gold 정책에 따라 **절대** PII 를 set 하지 않습니다. 두 시스템의 user shape 가 의도적으로 다릅니다.

---

## 5. PIPA 옵트인/아웃 흐름

### High-level flow

```
앱 첫 부팅
  → bootstrapAnalytics() 동기 실행 (모듈 top-level, app/_layout.tsx)
  → SDK init with opt_out_capturing_by_default: true (fail-closed)
  → AnalyticsUserBinding effect → await getAnalyticsConsent()
       null    → 동의 결정 아직 없음 — opt-out 유지 (Story 8.1 prompt 가 처리)
       "opt_in"  → posthog.optIn() — capture 시작
       "opt_out" → posthog.optOut() — capture 차단 유지

Story 8.1 Onboarding S5
  → 사용자에게 prompt
  → setAnalyticsConsent("opt_in" | "opt_out") 호출
  → SDK runtime state flip

Settings → Privacy → "사용 통계 공유" 토글 (revocation surface — Story 8.5)
  → 사용자가 언제든 setAnalyticsConsent(...) 재호출
  → SDK runtime state flip — 즉시 효력
```

### Helper API surface (`FE/src/lib/analytics.ts` + `FE/src/lib/analyticsConsent.ts`)

```typescript
// analytics.ts
export function bootstrapAnalytics(): void;
export function isAnalyticsEnabled(): boolean;
export function captureEvent(name: AnalyticsEventName, properties?: Record<string, unknown>): void;
export function identifyUser(props: AnalyticsUserProperties | null): void;
export function setAnalyticsUser(props: AnalyticsUserProperties | null): void;
export function addAnalyticsBreadcrumb(input: {
  category: string;
  level: "info" | "warning" | "error";
  message: string;
}): void;
export type AnalyticsEventName = (typeof ANALYTICS_EVENTS)[number];
export interface AnalyticsUserProperties {
  id: number;
  currentSurvivalState: "ACTIVE" | "YELLOW" | "RED" | "SPECTATOR";
  isRoomLeader: boolean;
}

// analyticsConsent.ts
export async function getAnalyticsConsent(): Promise<"opt_in" | "opt_out" | null>;
export async function setAnalyticsConsent(next: "opt_in" | "opt_out"): Promise<void>;
export async function clearAnalyticsConsent(): Promise<void>;
```

**Default contract (Trap #7):** `bootstrapAnalytics()` 는 동기 — `app/_layout.tsx:25` 의 `bootstrapSentry()` 와 같은 shape. 모듈 top-level 에서 `await` 불가능. 따라서 SDK 는 `opt_out_capturing_by_default: true` 로 init 하고, `AnalyticsUserBinding` 의 첫 effect 에서 `getAnalyticsConsent()` 를 await 후 `posthog.optIn()` 을 호출합니다. 사용자가 명시적 동의 없으면 capture 안 함 — PIPA-strict reading.

**Consent surface:** Onboarding S5 (Story 8.1) 가 **primary**. Settings 토글 (Story 8.5 AC9) 이 **revocation** surface — 언제든 끌 수 있음.

---

## 6. Sentry boundary

`docs/analytics.md` 와 `analytics-sdk-decision-2026-06-09.md` §3.5 양쪽에 lock:

- **Sentry** — BE error / FE render-crash / query-error 전용 (`captureRenderError`, `captureQueryError`). NFR-9.3.7 mass-elimination alert 가 Sentry 로 갑니다.
- **PostHog** — product analytics / funnel / cohort 전용.

**No cross-emission.** Sentry 가 `revival.failed` 를 받지 않고, PostHog 가 render crash 를 받지 않습니다. 두 시스템은 각자 독립 lib 로 coexist. 미래의 "왜 revival 실패를 Sentry 에도 안 보내?" 라는 질문은 — Sentry 의 역할이 exception/crash 이고, revival 분석은 PostHog 의 funnel-4 에 이미 있기 때문 — 으로 답합니다.

---

## 7. Retention & backup

- **Event retention:** 365 일 (ClickHouse). PostHog admin → Project Settings 에서 정책 확인.
- **Backup:** ClickHouse `clickhouse-backup` 일일 스냅샷. Postgres metadata 는 기존 backup 패턴.
- **운영 절차:** [`RUNBOOK.md §18`](../RUNBOOK.md#18-posthog-분석-sdk-운영) 참고.

---

## 8. Out-of-scope (for v1 / Story 8.5)

Story 8.5 는 SDK 기초 + 분류표 + 옵트인/아웃 helper + lint helper 까지. 아래는 **본 스토리에서 의도적으로 제외**:

1. Stories 4.x / 6.x / 7.x emit-points backfill — 결정 doc §6 OOS #2. v1 launch gap acceptance 또는 별도 backfill PR.
2. Onboarding S5 PIPA prompt UI — Story 8.1 의 것.
3. Brand-voice copy pass (AVOID lexicon 검사) — Story 8.2.
4. ASO copy lock — Story 8.3.
5. Release-gate brand-voice review — Story 8.4.
6. Subdomain DNS + cert (`analytics.yeolsal.app`) — 운영 owner (rearleg) per decision doc §6 OOS #1.
7. Self-hosted PostHog 운영 배포 — docker-compose + RUNBOOK 만 ship.
8. ClickHouse backup 자동화 스크립트 — RUNBOOK 절차만, 자동화는 post-launch SRE.
9. PostHog session recording — Trap #6 (`disableSessionRecording: true` lock).
10. PostHog feature flags — SDK 지원하지만 v1 범위 아님.
11. PostHog A/B testing — 같음.
12. PostHog cohort / funnel dashboard 세팅 — 시각화는 W8+ SRE 활동.
13. Custom event ingestion endpoint (`POST /api/v1/internal/analytics-events`) — 결정 doc §3.2: posthog-java 가 있어 BE 직접 ingest, fallback 미발동.
14. `spectator.revival_succeeded.day_n` 의 BE-side derivation — 분류표에 이름은 lock; emission deferred.
15. `activation.24h_complete` 의 BE-side derivation — 같음.
16. 영/일/중 analytics docs — 한국어 only v1.
17. PostHog admin user/team 관리 — SRE.
18. Analytics event PII redaction lint — 본 스토리의 lint helper 는 name-only. PII lint 는 Phase-2 hardening.
19. Sentry → PostHog cross-emission (reverse 도) — §6 lock.
20. Multi-room (`room_count > 1`) 처리 — v1 mandatory single room. `room_count: 1` 하드코딩 + `// v1.5 revisit` 코멘트.
21. Per-room PIPA opt-in (방마다 다른 결정) — out of scope. 동의는 per-device.
22. EAS Environment Variable 의 `EXPO_PUBLIC_POSTHOG_*` 키 설정 — RUNBOOK §18 운영 항목.

---

## References

- [`_bmad-output/planning-artifacts/analytics-sdk-decision-2026-06-09.md`](../_bmad-output/planning-artifacts/analytics-sdk-decision-2026-06-09.md) — SDK 결정 (PostHog self-hosted), PIPA flow, Sentry boundary, retention
- [`_bmad-output/planning-artifacts/prd.md`](../_bmad-output/planning-artifacts/prd.md) §3.1 Activation & Retention KPIs (lines 92–105), §3.2 Phase-2 Trigger Gates (lines 107–114), §13 (PRD-line: NFR-9.4.1–4 lines 482–485)
- [`_bmad-output/planning-artifacts/epics.md`](../_bmad-output/planning-artifacts/epics.md) §Story 8.5 (lines 1085–1126)
- [`RUNBOOK.md §18`](../RUNBOOK.md#18-posthog-분석-sdk-운영) — PostHog 운영 절차
- `tools/analytics-taxonomy-lint.ts` — 위 fenced `analytics-events` block 을 파싱하는 lint helper
