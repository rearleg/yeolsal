# Story 5.4: Rule-change broadcast in chat

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a room member,
I want a clear, non-shaming chat system message whenever the rule changes for the next month,
So that I know what the room agreed to before it takes effect.

## Acceptance Criteria

> 이 스토리는 **Epic 5의 네 번째이자 마지막 변경**으로, Story 5.1가 이미 ship한 `RoomRuleService.updateRule` chokepoint 위에 **한 줄 broadcast hook**만 얹는다 — leader가 다음 달 rule을 commit할 때마다 `chat_messages` 테이블에 `kind='SYSTEM'` row 하나를 insert하고 기존 `/topic/rooms.{id}.chat` realtime fan-out으로 모든 방 멤버에게 전달. **NO new migration** (V7 `chat_messages` + V12 `KUDOS` enum widening이 SYSTEM kind를 이미 허용), **NO new endpoint** (write site는 Story 5.1의 PATCH `/rules`만; FE는 chat 화면이 그대로 받음), **NO new RealtimeEvent sealed variant** (chat row가 일반 `ChatService.MessageDto`로 흐름 — Architecture §597의 `RuleChange` 변형은 별도 영역 broadcast가 아닌 chat 메시지 형식으로 매핑됨), **NO new STOMP topic regex token** (`chat`은 V7 시절부터 `JwtChannelInterceptor.ROOM_TOPIC`에 permit), **NO new FE component** (epics line 798 "distinct visual treatment (existing SystemMessage component)" — 기존 `<SystemMessage>`가 `SYSTEM` kind를 이미 muted-pill로 렌더). **Replace semantic (epics line 800-802)**: 같은 `effective_from_month` upsert 시 **새 SYSTEM row를 insert** (dedupe하지 않음) — leader가 마음을 바꿔 다시 편집했음을 알리는 것이 product 의도이고, 동일 month re-edit이 chat에 새 한 줄로 표시되는 것이 멤버에게 가장 정직한 시그널.

### AC1 — `ChatService.publishRuleChangeSystemMessage(...)` helper (REQUIRED METHOD)

**Given** Story 5.1의 `RoomRuleService.updateRule`이 `room_rule_versions` upsert를 마치고 saved DTO를 가지고 있다
**When** 새로운 SYSTEM chat row를 broadcast해야 한다
**Then** `BE/src/main/java/com/yeosal/api/room/chat/ChatService.java`에 **새 public 메서드** `publishRuleChangeSystemMessage(...)`를 추가한다. 시그너처와 동작:

```java
/**
 * Story 5.4 — emits a SYSTEM chat row announcing a leader-staged rule
 * change. The body opens with the locked positive-frame prefix
 * "다음 달부터 새 규칙이 적용됩니다: " and is suffixed with a one-line
 * preview of the pending rule (preset label + weekend-include phrase).
 * Payload carries the structured tuple {ruleVersionId, effectiveFromMonth,
 * preview} so a future FE consumer can render a sub-pill or deep-link to
 * the rule editor without re-parsing the body string.
 *
 * <p>Reuses {@link #publishSystem} so:
 * <ul>
 *   <li>the write runs in {@link Propagation#REQUIRES_NEW} — a chat-row
 *       failure cannot roll back the caller's rule upsert, mirroring the
 *       existing GOAL / REFLECTION / MILESTONE / AUTO_LEAVE pattern;
 *   <li>the standard {@code /topic/rooms.{id}.chat} fan-out fires
 *       automatically (no new topic, no new regex token);
 *   <li>broker errors are swallowed in the realtime layer per the project
 *       convention (best-effort fan-out).
 * </ul>
 */
public ChatMessage publishRuleChangeSystemMessage(
        long roomId,
        long ruleVersionId,
        String effectiveFromMonth,
        String preset,
        boolean weekendInclude) {
    String preview = formatRulePreview(preset, weekendInclude);
    String body = "다음 달부터 새 규칙이 적용됩니다: " + preview;
    String payload = String.format(
            "{\"ruleVersionId\":\"%d\",\"effectiveFromMonth\":%s,\"preview\":%s}",
            ruleVersionId,
            JSON.valueToTree(effectiveFromMonth).toString(),
            JSON.valueToTree(preview).toString());
    return publishSystem(roomId, ChatMessageKind.SYSTEM, body, payload);
}

private static String formatRulePreview(String preset, boolean weekendInclude) {
    String presetLabel = "DAILY_UPDATE".equals(preset) ? "매일 업데이트" : preset;
    String weekendPhrase = weekendInclude ? "주말 포함" : "주말 제외";
    return presetLabel + ", " + weekendPhrase;
}
```

**Contract details that MUST hold byte-identically:**

- Body prefix literal: `"다음 달부터 새 규칙이 적용됩니다: "` (전각 콜론 `：` 금지 — ASCII `:` + ASCII space; mirrors PRD FR-8.5.8 wording in epics line 794).
- Preset label map: `DAILY_UPDATE → "매일 업데이트"`. v1 has only one preset (RulePresetEvaluator:14 + Story 5.1 AC1 step 4 whitelist), so the `else` branch keeps the raw enum string as a defensive fallback — a future Story 5.x adding a second preset MUST extend this switch deliberately.
- Weekend phrase map: `true → "주말 포함"`, `false → "주말 제외"`. ASCII comma + ASCII space joiner — `"매일 업데이트, 주말 포함"`.
- Payload shape: `{ ruleVersionId, effectiveFromMonth, preview }`. **All three keys required** per epics line 794. `ruleVersionId` is rendered as a JSON string (V8/V9 milestone-dedup convention — `payload->>` operators return text regardless of writer shape, and string storage keeps future numeric writers from changing the index plan). `effectiveFromMonth` and `preview` use `JSON.valueToTree(...).toString()` (project's existing ChatService payload-builder pattern at `ChatService:163`, `:247`) to escape quotes / control chars safely. No `senderUserId` in payload — system rows have `sender_user_id = null` per V7 (already enforced by `publishSystem` writing `null` literal).

**Anti-pattern (DO NOT IMPLEMENT):**

- A separate dedicated entity / repo method for rule-change rows (e.g., `insertRuleChangeIfAbsent`). The product semantic per epics line 800-802 is **new row per re-edit**, NOT dedupe — borrowing the V8/V9 MILESTONE / V12 KUDOS `ON CONFLICT DO NOTHING` shape would suppress legitimate "leader changed their mind" messages.
- Hand-crafting the payload JSON with naive `String.format` quoting (without `JSON.valueToTree`). The preview string contains a Korean comma + space, which is benign, but a future preset name containing `"` or `\` would silently corrupt JSONB. Always route through Jackson for the string-valued fields.
- Adding the helper to `RoomRuleService` directly. Chat row writes belong on the chat module's chokepoint (`ChatService`) so a single class owns the `publishSystem` REQUIRES_NEW + realtime fan-out contract. Cross-module reuse mirrors `DailyService → ChatService.publishMilestonesForActor` and `RoomService → ChatService.publishMemberJoinedSystemMessage`.

PRD: FR-8.5.8 (line 408). Architecture: §6.3 V7 chat_messages + V12 enum widening (existing, no change). Epics: lines 793-794.

### AC2 — `RoomRuleService.updateRule` wires the helper via `publishAfterCommit` (REQUIRED CALL SITE)

**Given** Story 5.1의 `RoomRuleService.updateRule(User, long roomId, String preset, boolean weekendInclude)` 이 `RoomRuleVersionDto`를 반환하기 직전 (현재 `RoomRuleService.java:78`)
**When** Story 5.4가 hook을 추가한다
**Then** 다음 두 가지 변경을 만든다:

1. **ChatService 주입** — `RoomRuleService`의 생성자에 `ChatService chatService` 파라미터를 추가하고 필드에 저장. 생성자 인자 순서는 기존 6개 (`rooms, roomMembers, ruleVersions, roomService, clock, objectMapper`) 뒤에 7번째로 append — 미들에 넣어 모든 호출자(`RoomRuleService`를 인스턴스화하는 테스트 픽스처)를 재배열 강제하지 말 것.

2. **afterCommit 발행** — `upsertRule(...)` + `findByRoomIdAndEffectiveFromMonth(...)` + `RoomRuleVersionDto.from(saved)` 직후, return 직전에 다음 호출을 삽입:

```java
RoomRuleVersionDto dto = RoomRuleVersionDto.from(saved);
publishAfterCommit(() -> {
    try {
        chatService.publishRuleChangeSystemMessage(
                roomId,
                saved.getId(),
                saved.getEffectiveFromMonth(),
                dto.preset(),
                dto.weekendInclude());
    } catch (RuntimeException ex) {
        log.warn("[chat] rule-change publish failed roomId={} ruleVersionId={}: {}",
                roomId, saved.getId(), ex.toString());
    }
});
return dto;
```

3. **`publishAfterCommit` 헬퍼 추가** — `RoomRuleService`에 새 `private` 메서드를 정의:

```java
private void publishAfterCommit(Runnable task) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
        task.run();
        return;
    }
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
            task.run();
        }
    });
}
```

이 헬퍼는 `DailyService.publishAfterCommit:295-306`을 **byte-identical하게** mirror한다 (single-class scope, 추출하지 않음 — YAGNI: 같은 패턴을 가진 호출자가 셋 이상 생기기 전까지는 cross-module abstraction 금지).

4. **로거 필드 추가** — `RoomRuleService` 클래스 최상단에 `private static final Logger log = LoggerFactory.getLogger(RoomRuleService.class);` 한 줄 추가 (rule-change publish 실패 시 channel-scoped `[chat]` 프리픽스로 warn 로그 — `ChatService:202`, `DailyService:277` 와 동일 형식).

**Why `publishAfterCommit` is non-negotiable (instead of inline `chatService.publishRuleChangeSystemMessage(...)`):**

- Story 5.1의 `updateRule`은 `@Transactional` 메서드이고 그 안에서 `upsertRule(...)` native UPSERT + `findByRoomIdAndEffectiveFromMonth` 재조회가 한 boundary에 들어있다. `publishSystem`은 자체적으로 `Propagation.REQUIRES_NEW` 이지만, REQUIRES_NEW가 commit 한 후에 outer txn이 `OptimisticLockingFailureException`이나 다른 이유로 rollback되면 → **rolled-back rule edit이지만 chat에는 broadcast된** 상태가 된다. `afterCommit` defer가 이 race를 닫는다 (DailyService.publishGoalSystemMessages 패턴과 동일).
- Inner `try/catch RuntimeException`은 broker / DB / JSON 에러가 outer txn에 전혀 영향을 미치지 않도록 하는 2차 방어선 — `publishSystem` 자체가 broker 에러를 swallow하지만, `ChatMessageRepository.save` 실패 (예: FK 위배 — 방이 사이에 삭제됨)는 RuntimeException으로 propagate된다. afterCommit phase에서 던지면 Spring이 ERROR 로그만 남기고 transaction은 이미 commit된 상태이므로 user-visible 영향은 없지만, 명시적 swallow + warn 로그가 운영자 grep 채널을 깨끗하게 유지한다.

**Anti-pattern (DO NOT IMPLEMENT):**

- Inline `chatService.publishRuleChangeSystemMessage(...)` 직접 호출 (afterCommit 없이) — 위의 race가 열린다. `RoomRuleService` 내부에서도 `DailyService` 패턴을 따라야 한다.
- Story 5.3 패턴 (별도 `@TransactionalEventListener(AFTER_COMMIT)` listener + new `RoomRuleChangedEvent` 발행) — 단일 구독자에 대한 over-engineering. Listener는 cross-package 결합을 끊을 때 가치가 있는데, `RoomRuleService → ChatService` 는 이미 한 레이어 위 도메인 서비스가 chat 도메인 서비스를 호출하는 정상 방향이다 (`RoomService → ChatService`, `DailyService → ChatService` 와 동일).
- `chatService.publishSystem(roomId, SYSTEM, body, payload)` 호출 (helper 우회) — body / payload 빌더가 두 곳에 흩어지면 다음 preset 추가 / 다음 i18n 작업이 누락된다. AC1의 helper가 single chokepoint.

### AC3 — Body literal contract (LOCKED COPY)

**Given** any rendered system row from this flow
**When** brand-voice-lint 또는 visual review가 실행된다
**Then**:

1. **Body 시작은 byte-identical** 하게 `"다음 달부터 새 규칙이 적용됩니다: "` 이다 (UTF-8 17글자 + ASCII colon + ASCII space). NO 변형:
   - 전각 콜론 `：` 금지 (ASCII `:` 사용),
   - 양 끝 공백 mutate 금지,
   - `다음 달` 띄어쓰기 형식 변경 금지 — epics line 794가 `다음 달` 띄어쓰는 형식을 잠근다.
2. **Body 끝**은 `formatRulePreview` 결과로 닫힌다 — 마침표 / 줄임표 `…` 없음. epics line 794의 `: …`에서 `…`는 "preview 문자열이 들어간다"는 의미의 placeholder이며 실제 body에 포함하지 않는다.
3. **Preview phrase** 후보 (v1):
   - `"매일 업데이트, 주말 포함"` (weekendInclude=true)
   - `"매일 업데이트, 주말 제외"` (weekendInclude=false)
   결합 예시:
   - `"다음 달부터 새 규칙이 적용됩니다: 매일 업데이트, 주말 포함"`
   - `"다음 달부터 새 규칙이 적용됩니다: 매일 업데이트, 주말 제외"`
4. **AVOID lexicon 제로** — `벌금 / 잃었다 / 떨어졌다 / 실패 / 자책 / 부담 / 패배 / 죄책감` (tools/brand-voice-lint.ts:50-59). 위 body는 정의상 hit하지 않지만, `RoomRuleServiceTest`에 명시적 assert 추가 — `assertThat(captured.body).doesNotContain(banned)` (Story 3.5 KudosService 패턴).
5. **Story 5.1 preview literal과의 비교** — Story 5.1 AC10 / FE Rule Editor의 locked preview는 `"변경된 규칙은 다음 달 1일부터 적용됩니다."` (FE 화면 confirm 직전). Story 5.4의 chat broadcast body는 의도적으로 **다른 wording** (편집 직전 vs. 편집 직후, 액터 vs. 청중) — 의미가 갈라지지 않도록 둘 다 PRD FR-8.5.8 + epics 표현을 출처로 적는다. **DO NOT** Story 5.1 literal을 chat body로 재사용 — `1일부터` 는 deadline-tone, chat broadcast의 `적용됩니다` 는 announcement-tone이고, UX 분리가 의도된 설계다.

PRD: FR-8.5.8 (line 408). Epics: line 794, line 804 ("system message body uses brand-voice lexicon (no `벌금/실패`)"). Brand-voice: `tools/brand-voice-lint.ts:50-59`.

### AC4 — Payload shape contract (LOCKED WIRE)

**Given** payload JSONB 가 chat_messages row에 저장된다
**When** 미래 consumer (FE 또는 분석) 가 payload를 읽는다
**Then** 정확한 shape:

```json
{
  "ruleVersionId": "<saved.getId() — JSON string>",
  "effectiveFromMonth": "<saved.getEffectiveFromMonth() — YYYY-MM>",
  "preview": "<formatRulePreview(preset, weekendInclude)>"
}
```

**Why all three keys:**

- `ruleVersionId`: deep-link 또는 분석에서 rule version과 chat row를 connect (Story 6.x 무한 cache invalidation에서 `room_invite_preview_cache.rule_version_id`와 같은 trace key).
- `effectiveFromMonth`: 향후 chat 검색 / 필터링에서 "특정 달 rule 변경 알림" 쿼리 (`payload->>'effectiveFromMonth' = '2026-07'`). V8/V9 milestone-dedup `payload->>` operator 패턴과 동일.
- `preview`: redundant with body이지만, body가 i18n / wording 마이그레이션 등 future PR에 따라 변할 수 있는 반면 payload의 `preview`는 wire-stable한 분석 source (epics line 794 명시).

**Why string-typed `ruleVersionId` (not number):**

- V8/V9 milestone-dedup + V12 kudos-dedup `payload->>'userId' = :userId` 패턴 (ChatMessageRepository:55, 86, 122 등)이 string-typed id에 맞춰져 있다. 미래에 rule-change chat row 검색 / dedup 인덱스를 추가할 가능성에 대비해 같은 컨벤션을 유지.

**Anti-pattern:**

- `senderUserId` / `senderUserNickname` / `actorUserId` 추가 — leader 액션이긴 하지만 epics line 794 payload shape는 액터 id를 enumerate하지 않는다. SYSTEM kind는 `sender_user_id = NULL`이 V7 시점부터의 invariant이고, payload에 별도 액터 컬럼을 추가하면 future privacy / abuse-review 시 leader identity leak이 된다. NEEDED-by-FE 가 없는 한 필드를 늘리지 말 것.
- `previousRulePayload` (diff) 추가 — UX는 "이번 달부터 → 다음 달부터" 의 변화이고 이전 상태 diff는 epics가 명시하지 않은 scope creep. Story 5.5 / correct-course가 필요해지면 그때.

### AC5 — Replace semantic ⇒ new SYSTEM row each time (LOCKED PRODUCT CALL)

**Given** leader가 이미 `nextMonth = "2026-07"` 에 대해 한 번 편집을 commit 했고, chat에 1개 SYSTEM row 가 fan-out 되어 있다
**When** leader가 토글을 다시 바꿔 같은 `nextMonth` 에 대해 PATCH `/rule` 를 재호출한다 (epics line 800-802: "the existing `room_rule_versions` row is replaced (UNIQUE on `(room_id, effective_from_month)`)")
**Then** Story 5.1의 native `ON CONFLICT DO UPDATE` (RoomRuleVersionRepository.upsertRule) 가 row 를 **replace** 하고, **NEW** SYSTEM chat row 가 한 번 더 fan-out 된다 (epics line 802: "a new system message is sent").

**Implementation:** `RoomRuleService.updateRule` 의 broadcast hook은 upsert가 insert 였는지 update 였는지를 구분하지 않는다 — 매 호출이 새 chat row를 생성. 이는 product 의도이며 **dedupe하지 않는다**:

- V8/V9 MILESTONE / V12 KUDOS의 `ON CONFLICT DO NOTHING` 패턴을 **빌리지 말 것** — 그건 "같은 날 두 번 reflection이 와도 chat은 한 번만" 의도이고, 여기는 "leader가 마음을 바꿨음을 다시 알림"이 의도.
- Test 에서 같은 `nextMonth`로 두 번 호출한 후 `ChatMessageRepository.findByRoomIdAndIdLessThanOrderByIdDesc` 에 SYSTEM row 두 개가 들어있음을 assert (AC11 row 4 case 2).

**Edge case — leader가 같은 button을 0.5초 안에 더블탭:** 두 PATCH가 거의 동시에 들어와 둘 다 commit → 두 개의 SYSTEM row가 거의 같은 timestamp에 들어간다. UX 관점에서는 dead-tab 시그널인데, 이를 막는 것은 FE의 mutation `isPending` 상태 (이미 Story 5.1 AC10이 다룸). BE 에서 dedupe하지 않는다 — Story 5.1 의 wire-level race-free upsert는 row 한 개 보장이지만, 이는 다른 PATCH 호출의 보장이 아니다.

PRD: FR-8.5.8. Epics: lines 800-802.

### AC6 — Realtime fan-out via existing `/topic/rooms.{id}.chat` (NO NEW TOPIC)

**Given** `ChatService.publishSystem` 이 호출된다
**When** chat row가 저장되고 → MessageDto가 만들어진다
**Then** `realtime.publishChatMessage(roomId, MessageDto)` (ChatService:142, `RealtimePublisher:50-51`)가 자동으로 `/topic/rooms.{id}.chat` 토픽으로 frame을 emit한다.

**Topic regex 변경 ZERO** — `JwtChannelInterceptor.ROOM_TOPIC` (V7 / Story 3.5 시점에 잠긴 정규식)이 이미 `chat` 토큰을 permit:
```
^/topic/rooms\.(\d+)\.(chat|members|survival|points|kudos)$
```
`JwtChannelInterceptor.java:43-44` — Story 5.4가 건드릴 일 없다.

**FE consumer:** `useChatRealtime(roomId)` (FE/src/lib/query/hooks/chat.ts:157-192) 가 이미 `/topic/rooms.{id}.chat` 을 구독 중이며 incoming `ChatMessageDto` 를 `qk.roomMessages(roomId)` InfiniteQuery 의 첫 페이지에 dedupe-by-id 로 prepend. **FE source 변경 ZERO** — 새 system row 는 기존 채널을 타고 자연스럽게 화면에 나타난다.

**MILESTONE / GOAL / REFLECTION / AUTO_LEAVE / KUDOS 와 동일한 channel + 동일한 dedupe path** — 이 story 의 BE 단일 chat row 가 모든 채팅 surface (chat tab last-message peek, chat 화면 messages list; push notification 은 별도)에 일관되게 흐른다.

### AC7 — Push notification 미발행 (PRD FENCE)

**Given** rule-change broadcast 가 chat row + realtime frame 을 만든다
**When** Story 5.4 dev 가 push notification 을 추가하고 싶어한다
**Then** **하지 말 것** — PRD FR-8.5.8 (line 408) "broad visibility for all room members" 는 chat 채널의 broad visibility 만 명시하고, FCM/APNs push 발송은 명시하지 않는다. Push 추가 시:

- 모든 member 가 잠금 화면 알림을 받는다 → spam-tier UX (rule edit 이 흔치 않은 액션이지만 매번 push 는 과잉),
- NotificationKind enum 확장 + RoutingInvalidation 케이스 추가 + push template 새로 작성 + 새 NotificationLog row 등 부수 비용 발생,
- `NotificationKind.KUDOS_RECEIVED` / `FRIEND_REFLECTION` 등 기존 push 항목과 사용자 동의 설정 (`notification_prefs`)의 새 컬럼 협의 필요.

향후 "rule-change push 끄기" 가 필요해지면 별도 story (5.5 또는 8.2)에서 다룬다.

### AC8 — `chat_messages.kind = 'SYSTEM'` 유지 (NO new enum value)

**Given** ChatMessageKind enum 가 V7 + V12 시점에 결정된 7개 값을 가진다 (USER, SYSTEM, GOAL, REFLECTION, MILESTONE, AUTO_LEAVE, KUDOS)
**When** Story 5.4 가 rule-change row 를 분류한다
**Then** kind = `SYSTEM` 을 사용 (epics line 794 가 "kind='SYSTEM'" 으로 잠금):

- 새 `RULE_CHANGE` enum 값을 도입하지 말 것 — V7 `chk_chat_messages_kind` CHECK 제약 (BE/src/main/resources/db/migration/V7__chat_messages.sql) 을 widening 하려면 V14 migration 이 필요하고, 그건 epics line 794 에 명시되지 않은 변경.
- FE `ChatMessageKind` union type (FE/src/api/chat.ts:8-15) 에 새 변형 추가 → SystemMessage `visualFor` switch 에 새 case 추가 → KIND_PREFIX (FE/app/(tabs)/chat.tsx:140-149) 에 새 entry 추가 — 모두 zero scope change.
- payload 의 `ruleVersionId` 키 가 rule-change row 를 SYSTEM 풀에서 식별하는 충분한 marker (`payload ? 'ruleVersionId'` 쿼리).

**Visual differentiation between generic SYSTEM and rule-change SYSTEM:**

- 현재 `<SystemMessage>` (FE/src/components/chat/SystemMessage.tsx:64-71)는 SYSTEM kind 를 muted-grey pill 로 렌더 (`pillBg: surface.sunken`, `textColor: palette.inkMute`).
- 이는 epics line 798 "distinct visual treatment (existing SystemMessage component)" 의 요구 (`distinct` = USER 메시지와 구분되는 시스템 톤; KUDOS 처럼 별도 emphasized variant 가 아님).
- 만약 product 가 rule-change 만 emphasized 톤으로 보이고 싶다면 → 별도 story 의 frontend-only patch. Story 5.4 는 BE row + existing FE rendering 만으로 epics AC 를 충족한다.

### AC9 — Scope fence (REGRESSION FENCE)

**Given** Story 5.4 PR diff
**When** reviewer 가 `git diff --stat origin/main` 를 본다
**Then** BE 는 ChatService 한 클래스 확장 + RoomRuleService 한 클래스 확장 + 테스트만, ZERO 의 항목들:

- **NO** new Flyway migration (V14 등) — V7 + V12 가 SYSTEM kind enum 을 이미 permit.
- **NO** new REST endpoint — write site 는 Story 5.1 의 PATCH `/api/v1/rooms/{id}/rule` 만.
- **NO** new STOMP topic regex token — `chat` 이 이미 `JwtChannelInterceptor` 에 permit.
- **NO** new sealed `RealtimeEvent` variant — Architecture §597 의 `RealtimeEvent.RuleChange` 는 chat 채널의 `ChatService.MessageDto` 형식으로 매핑되었음을 architecture-deviation 노트에 기록 (별도 sealed variant 필요 없음).
- **NO** new ChatMessageKind enum 값 — AC8 fence.
- **NO** new ApiExceptionHandler 매핑 — afterCommit publish 실패는 ERROR 로그만 남고 HTTP boundary 영향 없음.
- **NO** new FE component / 라우트 / hook / api 함수 — `useChatRealtime` 가 새 row 를 알아서 처리.
- **NO** `tokens.json` 또는 디자인 토큰 추가.
- **NO** push notification 발송 (AC7).
- **NO** entity / 컬럼 추가.
- **NO** `RoomService` / `RoomRuleVersion` / `RoomRuleVersionRepository` 변경.

**Allowed FILE LIST:**

- `BE/src/main/java/com/yeosal/api/room/chat/ChatService.java` (MODIFIED — append `publishRuleChangeSystemMessage` + `formatRulePreview` helpers; ~50 line addition)
- `BE/src/main/java/com/yeosal/api/survival/RoomRuleService.java` (MODIFIED — add ChatService field + constructor param + Logger field + `publishAfterCommit` private helper + afterCommit registration call in `updateRule`)
- `BE/src/test/java/com/yeosal/api/room/chat/ChatServiceRuleChangeTest.java` (NEW unit, Mockito) — covers AC1/AC3/AC4 body+payload literal contracts.
- `BE/src/test/java/com/yeosal/api/survival/RoomRuleServiceTest.java` (MODIFIED — extend with the broadcast cases for AC2/AC5/AC6 verifying ChatService is called via afterCommit; existing 13 cases stay byte-identical and the ChatService mock is added to the existing constructor fixture).
- `BE/src/test/java/com/yeosal/api/survival/RoomRuleControllerTest.java` (MODIFIED — extend ChatService mock for the constructor fixture only; assertions stay slice-scoped — no chat row assertion at controller layer).
- `BE/src/test/java/com/yeosal/api/survival/RoomRuleChatBroadcastIT.java` (NEW opt-in `@SpringBootTest` via `@EnabledIfSystemProperty(named = "yeosal.boot-smoke", matches = "true")`) — end-to-end PATCH `/rule` → row in `chat_messages` + realtime spy capture on `/topic/rooms.{id}.chat`.
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (status flip + comment header)
- `_bmad-output/implementation-artifacts/5-4-rule-change-broadcast-in-chat.md` (this file)

**ZERO changes to (verify via `git diff --stat`):**

- `BE/src/main/resources/db/migration/V*.sql` (no new migration)
- `BE/src/main/java/com/yeosal/api/room/chat/ChatMessage.java` / `ChatMessageKind.java` / `ChatMessageRepository.java` (entity + enum + repo unchanged)
- `BE/src/main/java/com/yeosal/api/realtime/RealtimePublisher.java` / `JwtChannelInterceptor.java` (regex + publisher unchanged)
- `BE/src/main/java/com/yeosal/api/common/ApiExceptionHandler.java`
- `BE/src/main/java/com/yeosal/api/survival/RoomRuleController.java` / `RoomRuleVersion.java` / `RoomRuleVersionRepository.java` / `RoomRuleVersionDto.java` / `RoomRuleStateDto.java` / `UpdateRoomRuleRequest.java`
- `BE/src/main/java/com/yeosal/api/room/RoomService.java` / `TransferLeadershipService.java` / `RoomMemberCapService.java` / `AutoLeaderPromotionListener.java` (Epic 5 Stories 1-3 chokepoints unchanged)
- `BE/src/main/java/com/yeosal/api/notification/**` (no push)
- `BE/src/main/java/com/yeosal/api/daily/DailyService.java` (existing MILESTONE / GOAL / REFLECTION 패턴 reference 만; 변경 없음)
- `FE/src/api/chat.ts` / `FE/src/components/chat/SystemMessage.tsx` / `FE/app/(tabs)/chat.tsx` (zero FE source change)
- `FE/src/lib/notifications.ts` (no push routing)
- `FE/src/theme/tokens.json` (no new tokens)
- `FE/src/lib/query/hooks/chat.ts` (useChatRealtime 자동 흐름)

### AC10 — Brand-voice + no-emoji rule (SOURCE FILES)

**Given** Story 5.4 source files
**When** literals are laid down
**Then**:

1. **NO emojis** in any BE source file (project-context.md:191).
2. **Body literal** `"다음 달부터 새 규칙이 적용됩니다: 매일 업데이트, 주말 포함"` (또는 `"... 주말 제외"`) 가 AVOID 어휘 (`벌금/잃었다/떨어졌다/실패/자책/부담/패배/죄책감`) 를 포함하지 않음 (정의상 통과; 그러나 `RoomRuleServiceTest` 에 `assertThat(captured.body).doesNotContain(banned)` for each banned word — Story 3.5 `KudosServiceTest.bodyAvoidsBrandLexicon` 패턴).
3. **Preview phrases** `"매일 업데이트"` / `"주말 포함"` / `"주말 제외"` 가 동일한 AVOID 어휘를 hit 하지 않음.
4. **Body prefix 잠금** — `"다음 달부터 새 규칙이 적용됩니다: "` (ASCII colon + ASCII space, no full-width punctuation, no leading / trailing whitespace mutate). Test `assertThat(captured.body).startsWith("다음 달부터 새 규칙이 적용됩니다: ")`.
5. **No `Co-Authored-By` or AI attribution lines** in commits (project-context.md:206).

`tools/brand-voice-lint.ts` 는 FE / FE/src 트리만 스캔하므로 BE Java source 변경은 lint 게이트에서 자동으로 vacuous-pass — 그러나 위 4번의 단위 테스트가 BE 측 lint 역할을 한다.

### AC11 — Test coverage matrix

**Given** the implementation is complete
**When** verify pipeline runs
**Then** the following NET-ADDITIVE test counts MUST hold (delta vs `origin/main`):

| Test file | Cases | Layer | Notes |
|-----------|-------|-------|-------|
| `ChatServiceRuleChangeTest.java` | at least 7 | BE unit (Mockito) | (1) happy path — `publishRuleChangeSystemMessage(roomId, ruleVersionId, "2026-07", "DAILY_UPDATE", true)` → ArgumentCaptor on `ChatMessageRepository.save` captures a `ChatMessage` with `kind=SYSTEM`, `senderUserId=null`, `body="다음 달부터 새 규칙이 적용됩니다: 매일 업데이트, 주말 포함"`, payload contains `{"ruleVersionId":"<id>","effectiveFromMonth":"2026-07","preview":"매일 업데이트, 주말 포함"}`. (2) weekendInclude=false → `"...주말 제외"`. (3) body prefix 잠금 — `startsWith("다음 달부터 새 규칙이 적용됩니다: ")`. (4) payload는 정확히 3개 키 (ruleVersionId, effectiveFromMonth, preview), 추가 키 없음. (5) brand-voice — body 가 AVOID_LEXICON 8개 단어 어느 것도 포함하지 않음 (`@ParameterizedTest`). (6) realtime fan-out — `realtime.publishChatMessage(roomId, MessageDto)` 가 호출됨 (Mockito `verify`). (7) future-preset fallback — unsupported preset `"WEEKLY_UPDATE"` 가 들어와도 helper 가 throw 하지 않고 raw enum string 을 preview 로 사용 (`"WEEKLY_UPDATE, 주말 포함"`). |
| `RoomRuleServiceTest.java` (extended) | +5 new cases (existing 13 → at least 18) | BE unit (Mockito) | (a) happy insert flow → `chatService.publishRuleChangeSystemMessage(roomId, savedRow.getId(), "2026-07", "DAILY_UPDATE", true)` 가 호출됨. (b) replace flow (existing pending row found, AC5) → broadcast hook 이 **다시 한 번** 호출됨 (dedupe 하지 않음). (c) leader-only 403 path → ChatService 호출 ZERO (rolled-back at requireLeader → afterCommit phase 도달 안 함). (d) afterCommit defer 검증 — `TransactionTemplate.execute(...)` 안에서 호출 시 chatService 는 commit 직전이 아니라 commit 후에 호출됨 (mock 호출 순서 검증 via `inOrder`). (e) ChatService publish failure swallowed — `chatService.publishRuleChangeSystemMessage(...)` 가 `RuntimeException("broker down")` throw 해도 `updateRule` 반환값에 영향 없음 + outer txn rollback 없음. |
| `RoomRuleControllerTest.java` (extended) | +0 new cases, constructor fixture extension only | BE WebMvcTest slice | 기존 7 케이스 그대로; `@MockBean ChatService chatService` 추가만 (controller slice 는 service mock 하므로 chat 호출 검증은 service 레이어에서). |
| `RoomRuleChatBroadcastIT.java` | at least 2 | BE Testcontainers `@SpringBootTest` (opt-in via `@EnabledIfSystemProperty(named = "yeosal.boot-smoke", matches = "true")`) | (1) end-to-end — seed room + leader + 1 member, leader가 `PATCH /api/v1/rooms/{id}/rule` 호출 (`weekendInclude=false`) → 트랜잭션 commit 후 `chat_messages` 테이블에 SYSTEM row 1개 (`body` 정확 + `payload->>'ruleVersionId' = saved.id.toString()`) + `@SpyBean SimpMessagingTemplate.convertAndSend` 가 `/topic/rooms.{id}.chat` 토픽으로 호출됨 (Mockito timeout matcher). (2) replace — 같은 nextMonth 로 두 번 PATCH 호출 → `chat_messages` 에 SYSTEM row 두 개 (AC5 verify). |

**Total net-additive delta:** at least 14 BE cases (7 ChatService unit + 5 RoomRuleService extension + 2 IT). **0 FE cases** (zero FE source 변경 — `useChatRealtime` 가 기존 dedupe-by-id 로 자동 처리).

**Existing test suites MUST stay green:**
- All 13 existing `RoomRuleServiceTest` cases (Story 5.1) — constructor 시그너처에 `ChatService chatService` 한 인자 추가됨에 따라 모든 fixture 업데이트, but assertions 변경 없음.
- All 7 existing `RoomRuleControllerTest` cases (Story 5.1) — `@MockBean ChatService` 한 줄 추가만.
- All 2 existing `RoomRuleNextMonthEvaluatorIT` opt-in IT 케이스 (Story 5.1) — chat broadcast 자동 발생하지만 IT 의 assert 영역이 evaluator 결과만 보므로 영향 없음 (단, IT 안에서 직접 chat row count assertion 이 있다면 추가; 현재는 없음).
- All Story 1.4 / Story 1.6 / Story 3.5 의 `ChatService` / `ChatServiceTest` / `KudosServiceTest` / `MessageJoinedSystemMessageTest` 등 — `ChatService` 에 새 메서드 한 개 추가만이므로 기존 케이스에 영향 없음.

**TDD discipline:** project-context.md:146 ("TDD order: RED → GREEN → refactor"). 새 케이스는 implementation 전에 작성. Commit history 는 RED → GREEN 으로 collapse 가능.

### AC12 — Verify pipeline gates

**Given** implementation is complete
**When** dev runs verify
**Then**:

1. **BE Gradle test green** — `cd BE && ./gradlew test` → BUILD SUCCESSFUL.
2. **brand-voice-lint 0 HARD violations** — `npm --prefix tools run lint:brand-voice` (or repo-root equivalent). Story 5.4 의 BE 변경은 FE 트리 밖이므로 scanner 영향 없음 (Rule 1/2/3 모두 FE 대상). 그러나 lint 자체는 통과해야 함.
3. **FE Jest no NEW failures** — `cd FE && npx jest --runInBand --no-watchman` → 62 suites / 466 tests / 9 snapshots PASS (Story 5.3 baseline). **Δ = 0 FE 변경**.
4. **FE typecheck no NEW errors** — `cd FE && npx tsc --noEmit` — same 2 pre-existing `FriendsTodayPager` errors as baseline (Story 5.1 / 5.2 / 5.3 baseline).
5. **Touched FE files ESLint clean** — N/A — no FE files touched.
6. **`git diff --check HEAD` clean** (whitespace / trailing-newline).
7. **Scope-fence grep** — `git diff --stat origin/main` 가 AC9 allowed FILE LIST 정확 match 확인. 특히:
   - `BE/src/main/resources/db/migration/V*.sql` ZERO change,
   - `FE/src/**` / `FE/app/**` ZERO change,
   - `JwtChannelInterceptor.java` / `RealtimePublisher.java` ZERO change.
8. **Opt-in IT smoke** — `cd BE && ./gradlew test -Dyeosal.boot-smoke=true --tests 'com.yeosal.api.survival.RoomRuleChatBroadcastIT'` — Docker provider 가 사용 가능한 호스트에서 PASS. Docker 가 사용 불가하면 Story 5.1 / 5.2 / 5.3 close-out 패턴대로 PR-open CI 에 deferred.
9. **Manual smoke (VERIFY-N)** — deferred to PR-open per Story 5.1 / 5.2 / 5.3 precedent. PR-open 시 iOS sim 에서:
   - leader 로그인 → room 입장 → `/rooms/{id}/settings/rule` 에서 weekendInclude 토글 → Save 탭 → chat 화면으로 돌아가 새 SYSTEM row 가 muted-pill 로 표시되는지 확인,
   - non-leader 로 다시 로그인 → 같은 room 의 chat 화면에 같은 row 표시 확인 (realtime fan-out + InfiniteQuery dedupe 동작),
   - 같은 leader 가 토글 다시 바꿔 재 commit → 새 SYSTEM row 한 개 추가 (AC5).

## Tasks / Subtasks

- [x] **Task 0 — Pre-flight (no code yet)**
  - [x] Confirm Story 5.1 is `done` in `sprint-status.yaml` (memory: 2026-06-02 PR #86 squash `2e397fb`).
  - [x] Re-read `BE/src/main/java/com/yeosal/api/room/chat/ChatService.java:128-167` — `publishSystem` REQUIRES_NEW pattern + `publishMemberJoinedSystemMessage` payload-builder 패턴 (`String.format` + `JSON.valueToTree(...).toString()` 으로 string 필드 escape).
  - [x] Re-read `BE/src/main/java/com/yeosal/api/daily/DailyService.java:244-306` — `publishGoalSystemMessages` / `publishReflectionSystemMessages` afterCommit defer 패턴 + `publishAfterCommit` helper 의 byte-identical shape.
  - [x] Re-read `BE/src/main/java/com/yeosal/api/survival/RoomRuleService.java` 전체 — 7번째 생성자 인자 추가 위치, `updateRule` return 직전의 broadcast hook 위치.
  - [x] Re-read `BE/src/main/java/com/yeosal/api/realtime/JwtChannelInterceptor.java:43-44` — `chat` 토큰이 이미 ROOM_TOPIC regex 에 permit (Story 5.4 변경 ZERO 확인).
  - [x] Re-read `BE/src/test/java/com/yeosal/api/survival/RoomRuleServiceTest.java` — 13 케이스의 픽스처 + 새 7번째 mock 인자 추가 시 어느 변수가 영향받는지 확인.
- [x] **Task 1 — BE `ChatService.publishRuleChangeSystemMessage` 추가 (AC1, AC3, AC4)**
  - [x] `BE/src/main/java/com/yeosal/api/room/chat/ChatService.java` 끝부분 (publishMilestonesForActor 다음 / private helpers 앞)에 `public ChatMessage publishRuleChangeSystemMessage(long roomId, long ruleVersionId, String effectiveFromMonth, String preset, boolean weekendInclude)` 메서드 추가. JavaDoc 은 AC1 의 shape 그대로.
  - [x] `private static String formatRulePreview(String preset, boolean weekendInclude)` helper 추가 — preset 라벨 + 주말 phrase 결합.
  - [x] Payload 빌더는 `String.format` + `JSON.valueToTree(...).toString()` (project precedent: `ChatService:163`, `:247`). `ruleVersionId` 는 JSON string 으로 저장 — AC4 shape.
  - [x] 내부적으로 `publishSystem(roomId, ChatMessageKind.SYSTEM, body, payload)` 호출 — 단일 chokepoint 재사용.
- [x] **Task 2 — BE `RoomRuleService.updateRule` afterCommit broadcast wiring (AC2, AC5, AC6)**
  - [x] `RoomRuleService` 클래스 최상단에 `private static final Logger log = LoggerFactory.getLogger(RoomRuleService.class);` 추가 + 필요한 import.
  - [x] 생성자 시그너처에 `ChatService chatService` 7번째 인자 append; 동일 이름의 `private final` 필드 추가.
  - [x] `updateRule` 메서드의 `return RoomRuleVersionDto.from(saved);` 줄 직전에 AC2 의 afterCommit 호출 블록을 삽입. local var `RoomRuleVersionDto dto = RoomRuleVersionDto.from(saved);` 로 추출하여 dto.preset() / dto.weekendInclude() 를 lambda 안에서 사용 (effective-final 요구).
  - [x] `private void publishAfterCommit(Runnable task)` helper 추가 (DailyService:295-306 byte-identical).
  - [x] `getRule` 메서드는 read-only 이므로 broadcast hook ZERO (변경 없음).
- [x] **Task 3 — BE unit test `ChatServiceRuleChangeTest.java` (AC11 row 1)**
  - [x] `BE/src/test/java/com/yeosal/api/room/chat/ChatServiceRuleChangeTest.java` — Mockito + `@ExtendWith(MockitoExtension.class)`.
  - [x] Mock `ChatMessageRepository messages`, `RoomRepository rooms`, `RoomMemberRepository roomMembers`, `GroupMemberMinimumRepository minimums`, `RealtimePublisher realtime`, `SurvivalStateRepository survivalStates` → `ChatService` 생성.
  - [x] AC3 / AC4 body+payload 7 케이스 (matrix row 1).
  - [x] `ArgumentCaptor<ChatMessage>` 로 `messages.save` 캡쳐, `ArgumentCaptor<MessageDto>` 로 `realtime.publishChatMessage` 캡쳐.
  - [x] `@ParameterizedTest` + `@ValueSource(strings = {"벌금", "잃었다", ...})` 로 AVOID 어휘 검증.
- [x] **Task 4 — BE unit test `RoomRuleServiceTest.java` 확장 (AC11 row 2)**
  - [x] 기존 13 케이스의 `RoomRuleService` 생성자 호출에 `chatService` 7번째 인자 추가 (`@Mock` 필드 + `build(Clock)` helper).
  - [x] 5 새 케이스 추가:
    - `updateRule_emitsRuleChangeBroadcast_onHappyInsert` — `chatService.publishRuleChangeSystemMessage` 가 정확한 인자 시퀀스로 호출.
    - `updateRule_emitsRuleChangeBroadcast_onReplace` — 같은 nextMonth 로 두 번 호출, broadcast hook 두 번 호출 검증 (no dedupe).
    - `updateRule_doesNotEmitBroadcast_whenNonLeader` — `roomService.requireLeader` 가 `ForbiddenException` throw → `Mockito.verifyNoInteractions(chatService)`.
    - `updateRule_registersAfterCommitSynchronization_whenInsideTransaction` — `TransactionSynchronizationManager.initSynchronization()` 안에서 호출, registered synchronization 의 `afterCommit()` 수동 발화 후 chat 호출 검증 (Spring `TransactionSynchronization` 컨트랙트).
    - `updateRule_swallowsChatPublishFailure` — `chatService.publishRuleChangeSystemMessage` 가 `RuntimeException` throw 하도록 stub, `updateRule` 반환값이 정상.
- [x] **Task 5 — BE controller test fixture 확장 (AC9 allowed-file constructor only)**
  - [x] `RoomRuleControllerTest.java` — `@MockBean ChatService chatService` 한 줄 추가만; 기존 7 assert 변경 없음.
- [x] **Task 6 — BE end-to-end IT `RoomRuleChatBroadcastIT.java` (AC11 row 4)**
  - [x] `BE/src/test/java/com/yeosal/api/survival/RoomRuleChatBroadcastIT.java` — opt-in `@EnabledIfSystemProperty(named = "yeosal.boot-smoke", matches = "true")` + `@SpringBootTest` + `@Testcontainers` (Story 5.1 `RoomRuleNextMonthEvaluatorIT` precedent).
  - [x] `@SpyBean SimpMessagingTemplate` 로 `/topic/rooms.{id}.chat` STOMP boundary 캡쳐 (Story 5.3 `AutoLeaderPromotionIT` 패턴).
  - [x] 2 케이스: happy + replace (AC5).
- [x] **Task 7 — Verify pipeline (AC12)**
  - [x] BE Gradle test green — 574/574 tests PASS (delta +14: 7 ChatServiceRuleChangeTest + 5 RoomRuleServiceTest extension + 2 opt-in IT compile-only since boot-smoke is off).
  - [x] brand-voice 0 HARD — 218 files scanned, 0 HARD / 198 warnings (Story 5.3 baseline preserved; BE changes are outside the FE scan tree).
  - [x] FE source Δ = 0 — `git diff --stat origin/main -- FE/` returns no output.
  - [x] FE typecheck baseline — only the 2 pre-existing `FriendsTodayPager` errors (Story 5.1/5.2/5.3 baseline).
  - [x] `git diff --stat origin/main` matches AC9 FILE LIST — 5 modified + 3 untracked, all on the allow list.
  - [x] `git diff --check HEAD` clean (no whitespace / trailing-newline issues).
  - [x] Opt-in IT (`-Dyeosal.boot-smoke=true`) — Docker unavailable on the dev host; defer to PR-CI per the Story 5.1 / 5.2 / 5.3 close-out pattern. IT class compiles cleanly so the boot-smoke fan-out will pick it up.
  - [x] Manual VERIFY-N — deferred to PR-open (no FE change, so the leader-edit → muted-pill SYSTEM row smoke is the same surface Story 5.1 already validated).

### Review Findings

- [x] [Review][Patch] `publishRuleChangeSystemMessage` does not actually inherit `publishSystem`'s `REQUIRES_NEW` transaction contract when it calls it through `this` [BE/src/main/java/com/yeosal/api/room/chat/ChatService.java:199] — fixed by adding `@Transactional(propagation = Propagation.REQUIRES_NEW)` to the public helper itself.

## Dev Notes

### Context — what Story 5.1 + Story 3.5 + V7 + V12 가 이미 ship 한 것 (Story 5.4 의 기반)

**Story 5.1 (PR #86 merged 2026-06-02 squash `2e397fb`):**
- `RoomRuleService.updateRule(User, long roomId, String preset, boolean weekendInclude)` — leader-only PATCH 라이트 사이트. Native `ON CONFLICT DO UPDATE` upsert 가 race-free 하게 row 를 insert-or-replace. **Story 5.4 는 이 메서드에 7번째 인자 + afterCommit hook 만 얹는다.**
- `RoomRuleVersionDto.from(RoomRuleVersion)` — preset 과 weekendInclude 를 typed 필드로 노출. `dto.preset()` / `dto.weekendInclude()` 를 broadcast helper 인자로 forward.
- `RoomRuleVersion` 엔티티 — `getId()` / `getEffectiveFromMonth()` / `getRulePayload()` getter 만 사용 (변경 없음).
- `RoomService.requireLeader(Room, User)` — Story 5.1 이 public 으로 promote 함. `updateRule` 진입 시 첫 가드. Story 5.4 의 broadcast hook 은 이 가드 뒤에 실행되므로 비-리더는 chat row 를 만들 수 없다.

**Story 3.5 (PR #78 merged 2026-05-18 squash `e5fdea7`):**
- `ChatService.publishMilestonesForActor` / `KudosService` — chat 도메인 안의 SYSTEM-kind broadcast precedent. `publishSystem(roomId, kind, body, payload)` 가 chokepoint.
- `ChatMessageRepository.insertMilestoneIfAbsent` / `insertKudosIfAbsent` — V8/V9 / V12 partial unique index 기반 dedupe. **Story 5.4 는 dedupe 하지 않으므로 이 패턴을 빌리지 않는다** (AC5: 매 호출이 새 row).
- `ChatMessageKind.KUDOS` enum 값 추가 + V12 CHECK 제약 widening — Story 5.4 는 **추가 enum 값 없음** (AC8).
- FE `SystemMessage` 컴포넌트의 KUDOS variant — coralSoft pill + 강조 톤. **Story 5.4 의 rule-change SYSTEM row 는 default muted-grey 톤** (SystemMessage:64-71 의 fallback case) 사용.

**V7 (chat_messages, shipped):**
- `chk_chat_messages_kind` CHECK 제약이 `'SYSTEM'` 을 V7 시점부터 허용 (V12 가 `KUDOS` 만 추가 widening).
- `(room_id, id DESC)` index 가 cursor pagination 을 cover — 새 SYSTEM row 들이 chat 화면에 즉시 등장.

**FE consumer (zero change):**
- `useChatRealtime(roomId)` (FE/src/lib/query/hooks/chat.ts:157-192) — `/topic/rooms.{id}.chat` 구독 + dedupe-by-id 로 InfiniteQuery 첫 페이지에 prepend.
- `<SystemMessage>` (FE/src/components/chat/SystemMessage.tsx) — `kind="SYSTEM"` 일 때 muted-grey pill 렌더. epics line 798 "distinct visual treatment" 요구를 자동 충족.
- KIND_PREFIX["SYSTEM"] = "[알림] " (FE/app/(tabs)/chat.tsx:142) — chat tab last-message peek 에 "[알림] 다음 달부터 새 규칙이 적용됩니다: 매일 업데이트, 주말 포함" 으로 표시.

### Architecture deviation — `RealtimeEvent.RuleChange` 가 별도 sealed variant 가 아님 (chat 채널로 매핑)

Architecture §597 (`architecture.md:593-598`) 가 `RealtimeEvent.LeadershipChange` / `.PointPoolChange` / `.FriendGiftPrompt` / `.MonthlyPosterReady` / `.SurvivalStateChange` 다섯 변형을 enumerate 한다. **`RuleChange` 는 명시되지 않음** — 이는 우연이 아니라 의도:

- `LeadershipChange` 등은 별도 broad channel (`/topic/rooms.{id}.survival`) 에 STOMP frame 만 보내는 채널 (chat row 없음).
- `RuleChange` 는 epics line 794 가 명시적으로 `chat_messages` 행을 요구 — 즉 chat 메시지 *형식* 으로만 전달되므로 별도 sealed variant 가 필요 없다.

**Story 5.4 가 등재하는 architecture-deviation 노트:** Architecture 가 향후 §597 표를 업데이트할 때 한 줄 추가 — "`RuleChange` is delivered as a `chat_messages` row with `kind=SYSTEM` + payload `{ ruleVersionId, effectiveFromMonth, preview }` rather than a separate sealed `RealtimeEvent` variant; consumed via the existing `/topic/rooms.{id}.chat` channel." 이 줄을 추가하는 doc PR 은 Story 5.4 블록커가 아님 — 별도 follow-up.

### Architecture deviation — `RoomRuleService` 가 cross-package `ChatService` 를 주입 (acceptable)

`RoomRuleService` 는 `com.yeosal.api.survival` 패키지에 있고 `ChatService` 는 `com.yeosal.api.room.chat` 패키지에 있다. 다른 패키지의 application service 를 주입하는 것은 정상 — `RoomService` (room/) 가 `ChatService` (room.chat/) 를 주입하고, `DailyService` (daily/) 가 `ChatService` (room.chat/) 를 주입하는 기존 precedent 가 있다. Package-by-feature 의 원칙 (project-context.md:176) 은 동일 도메인이 한 패키지에 응집되도록 하라는 것이지 cross-package 호출을 금지하는 것이 아니다.

다만 Story 5.4 는 `RoomRuleService` (write site) → `ChatService.publishRuleChangeSystemMessage` (broadcast) 의 단방향 의존을 추가한다. ChatService 가 RoomRuleService 를 거꾸로 호출해서는 안 된다 (현재 그런 코드 없음, 그리고 추가하지 말 것).

### Implementation trap #1 — `publishAfterCommit` defer 가 필수 (inline 호출 금지)

`RoomRuleService.updateRule` 이 `@Transactional`. Inline 으로 `chatService.publishRuleChangeSystemMessage(...)` 를 호출하면:

- `publishSystem` 의 `Propagation.REQUIRES_NEW` 가 inner txn 을 새로 commit 함 → chat row 가 DB 에 저장됨,
- 그런데 outer `updateRule` 의 `@Transactional` 이 (예) `OptimisticLockingFailureException` 으로 rollback → rule row 는 없는데 chat 에는 "rule changed" 알림이 남는다.

**afterCommit defer 가 닫는 race:** outer txn 이 commit 되어야만 lambda 가 실행됨. 만약 outer 가 rollback 되면 lambda 는 절대 실행되지 않으므로 chat row 도 없다. DailyService:281 / 247 / 253 패턴과 동일.

**Verification:** Test case `updateRule_emitsBroadcastAfterCommit_notInline` — `TransactionTemplate.execute()` 또는 `@Transactional` 테스트 컨텍스트 안에서 호출하고 `InOrder` 로 outer commit 후 chat 호출 순서 검증.

### Implementation trap #2 — afterCommit 안의 `try/catch` 가 outer 영향 차단

afterCommit phase 에서 던지는 RuntimeException 은 Spring 의 `TransactionSynchronization.afterCommit` 컨트랙트상 outer txn 에 영향을 미칠 수 없다 (이미 commit 된 상태). 그러나:

1. Spring 의 default behavior 는 ERROR 레벨로 stack trace 를 통째로 출력 — 운영 grep 채널이 더러워짐.
2. 만약 afterCommit phase 가 inline 으로 (트랜잭션 동기화 비활성 시) 실행되면 — `publishAfterCommit` 헬퍼의 `!TransactionSynchronizationManager.isSynchronizationActive()` 분기 — RuntimeException 이 caller 쪽으로 propagate 될 위험. `chatService.publishRuleChangeSystemMessage` 가 던지는 RuntimeException 이 `updateRule` 의 caller (Controller) 까지 가서 500 IL 을 만들 수 있다.

**Defense:** lambda 본체에 명시적 `try { ... } catch (RuntimeException ex) { log.warn("[chat] rule-change publish failed roomId={} ruleVersionId={}: {}", roomId, saved.getId(), ex.toString()); }` 를 추가. DailyService:283-287 의 milestone fan-out 패턴과 동일.

**Verification:** Test case `updateRule_swallowsChatPublishFailure`.

### Implementation trap #3 — Effective-final 람다 캡쳐

Lambda 안에서 사용하는 `roomId`, `saved`, `dto` 변수는 effective-final 이어야 한다. AC2 의 예제 코드처럼 `RoomRuleVersionDto dto = RoomRuleVersionDto.from(saved);` 로 한 번 추출하면 lambda 가 안전하게 캡쳐. `dto.preset()` / `dto.weekendInclude()` / `saved.getId()` / `saved.getEffectiveFromMonth()` 모두 lambda 안에서 호출 가능.

**Anti-pattern:** Lambda 안에서 `RoomRuleVersionDto.from(saved)` 를 재호출하지 말 것 — 같은 결과를 두 번 계산 (사소하지만 caller-side `dto` 변수가 이미 있으니 재사용이 명확).

### Implementation trap #4 — `RoomRuleService` 생성자 시그너처 변경의 영향 반경

생성자가 6 → 7 인자로 바뀌면 영향받는 호출자:

1. **Spring DI 컨테이너** — 자동 처리 (`ChatService` Bean 이 존재하므로 inject 됨).
2. **`RoomRuleServiceTest`** — `@BeforeEach` 의 `new RoomRuleService(...)` 호출에 mock 추가 필요. 13 케이스가 같은 fixture 를 공유하므로 한 군데만 수정.
3. **`RoomRuleControllerTest`** — `@WebMvcTest` slice 라서 `RoomRuleService` 가 `@MockBean` 으로 주입됨; 직접 `new` 하지 않음 → 변경 없음. 그러나 `ChatService` 가 Spring context 에 필요하므로 `@MockBean ChatService chatService` 한 줄 추가.
4. **`RoomRuleNextMonthEvaluatorIT`** — `@SpringBootTest` full context 라서 자동 wiring; 변경 없음.

**Anti-pattern:** 7번째 인자를 생성자 중간 (예: 4번째) 에 끼워넣지 말 것 — 모든 호출자가 손상되고 future-PR 이 머지 충돌난다. **마지막에 append.**

### Implementation trap #5 — `JsonNode.valueToTree(...).toString()` vs raw quoting

Payload 빌더 패턴은 ChatService:163, 247 에서 이미 확정:

```java
String payload = String.format(
    "{\"ruleVersionId\":\"%d\",\"effectiveFromMonth\":%s,\"preview\":%s}",
    ruleVersionId,
    JSON.valueToTree(effectiveFromMonth).toString(),  // 자동 escape + 따옴표
    JSON.valueToTree(preview).toString());            // 자동 escape + 따옴표
```

- `effectiveFromMonth = "2026-07"` → `JSON.valueToTree("2026-07").toString()` → `"\"2026-07\""` (literal string with quotes embedded).
- `preview = "매일 업데이트, 주말 포함"` → `"\"매일 업데이트, 주말 포함\""`. Korean 안에 `"` 가 없으므로 안전하지만, future 에 preview 가 `"매일 업데이트 (\"daily-update\"), 주말 포함"` 같은 형태가 되어도 자동 escape.

**Why `ruleVersionId` 는 `"%d"` 만 (수동 따옴표):** Long 은 numeric 처럼 보이지만 AC4 contract 가 "JSON string 으로 저장" 이므로 명시적으로 `"\"%d\""` 패턴이 들어가도록 `String.format` 의 첫 placeholder 를 `\"%d\"` 로 작성. Test 는 `mapper.readTree(payload).path("ruleVersionId").asText()` 로 파싱한 뒤 `.equals(String.valueOf(ruleVersionId))` 로 검증.

### Implementation trap #6 — Brand-voice WARN 도 0 으로 유지하라

`tools/brand-voice-lint.ts` 의 Rule 1 (HARD GATE) 은 FE TSX 의 survival color packed-type 위반이고 Story 5.4 는 BE only 라서 자동 vacuous-pass. Rule 2 (WARN — AVOID 어휘) 는 BE/Java 도 스캔하지 않지만, **AC10.2** 의 BE 단위 테스트가 brand-voice 게이트 역할을 한다.

**WARN 도 0 으로 유지하라는 의미:** Story 5.3 baseline 이 198 WARN 이다 (Story 5.1 baseline 188 + Story 5.2 / 5.3 의 새 inherited). Story 5.4 가 새 WARN 을 증가시키지 않아야 한다. 즉:

- `formatRulePreview` 의 `"DAILY_UPDATE".equals(preset) ? "매일 업데이트" : preset` — `else` 분기에서 preset 의 원시 enum string 을 직접 출력하면 미래에 `"FORCED_HARDCORE"` 같은 preset 가 chat body 에 leak 될 수 있음. v1 에서는 preset whitelist 에 `DAILY_UPDATE` 만 있으므로 (Story 5.1 AC1 step 4), `else` 분기는 dead code 처럼 동작하지만 defensive 하게 유지.
- Brand-voice WARN 의 어휘에 `실패` 가 있으므로 — `"실패에 따른 페널티"` 같은 미래 wording 이 들어오지 않도록 helper 의 wording 책임을 한 클래스에 집중.

### Implementation trap #7 — chat row 가 chat 화면 InfiniteQuery 의 첫 페이지에 prepend 됨

FE `useChatRealtime` (FE/src/lib/query/hooks/chat.ts:162-192) 은 incoming STOMP frame 을 첫 페이지 messages 배열의 **끝에** push (line 184: `messages: [...first.messages, incoming]`). 첫 페이지의 의미는 desc-by-id 순서로 자른 후 ascending 으로 reverse 된 가장 최신 페이지 → array 끝 = 가장 최신 메시지. 새 SYSTEM row 의 id 는 시퀀스의 최댓값이므로 자연스럽게 끝에 append.

**Dedupe-by-id** — `useChatRealtime` 가 모든 페이지를 순회하며 `m.id === incoming.id` 면 prev 반환 (line 176-180). Story 5.4 의 SYSTEM row 도 동일하게 dedupe — 만약 user 가 chat 화면 진입 시 REST 로 받은 새 SYSTEM row 가 이미 캐시에 있고 STOMP echo 가 또 와도 한 번만 렌더.

### Implementation trap #8 — Same-timestamp 두 SYSTEM row 의 chat 화면 표시 순서

AC5 의 같은 nextMonth 재편집 → 두 SYSTEM row. 만약 Database 의 `id IDENTITY` sequence 가 정상이면 두 row 의 id 가 서로 다르고 `findByRoomIdAndIdLessThanOrderByIdDesc` 의 정렬이 안정적. **timestamp** 가 같더라도 id 는 항상 다르므로 chat 화면 ordering 에는 모호함이 없음.

**Edge case (defensive):** 두 PATCH 가 동일 `System.currentTimeMillis()` 안에 도착해도, Postgres `IDENTITY` sequence 가 atomic 하게 다른 id 를 발급. JPA `@PrePersist` 의 `createdAt = Instant.now()` 가 같은 ms 를 받더라도 id 가 tiebreak.

### Implementation trap #9 — Push notification 미발행을 어떻게 강제하는가

AC7 fence 가 "push 안 함" 이라고 명시했지만, 누군가 따라가다 `NotificationService.sendEvent(...)` 호출을 추가할 수 있다. **방지 메커니즘:**

- AC9 의 allowed FILE LIST 에 `BE/src/main/java/com/yeosal/api/notification/**` 이 없음 → diff 가 그쪽을 건드리면 scope fence 위반.
- 코드 리뷰 시 grep `git diff origin/main -- BE/src/main/java/com/yeosal/api/notification/` 가 빈 결과를 반환해야 함.
- Test 측면 — `RoomRuleServiceTest` 에 `NotificationService` mock 을 주입하지 않음. 만약 dev 가 무의식적으로 추가하려 하면 컴파일 에러로 잡힘.

### Implementation trap #10 — Spectator 멤버 에게도 row 가 보임 (의도)

`requireNotSpectator` (ChatService:295) 는 USER-authored 메시지에만 적용. `publishSystem` 은 우회하므로 SPECTATOR 도 새 SYSTEM row 를 받음. 이는 **의도** — SPECTATOR 도 방의 rule 변화를 알 권리가 있고, epics line 798 "any member" 는 SPECTATOR 를 명시적 제외하지 않음. Story 2.1 (Spectator Mode) 가 SPECTATOR 의 chat *읽기* 권한을 명시적으로 허용함 — chat read path 의 `requireMembership` 만 통과하면 됨.

**Verification:** IT 케이스에 SPECTATOR 멤버를 1명 포함하고 chat tab 의 last-message peek API 호출 → 같은 SYSTEM row 가 응답에 포함되는지 검증 (선택적 — opt-in IT 의 scope).

### Implementation trap #11 — `effective_from_month` 가 `pending` 이 아닌 `current` 와 같으면 안 됨 (Story 5.1 에서 이미 보장)

Story 5.1 의 `nextMonthKST()` 가 항상 `currentMonth + 1` 을 반환. 따라서 `effective_from_month` 가 chat broadcast 시점의 `currentMonth` 와 같을 일이 없다. Story 5.4 는 `saved.getEffectiveFromMonth()` 를 그대로 forward — Story 5.1 의 invariant 가 깨지지 않는 한 chat body 의 "다음 달" 표현이 의미적으로 맞다.

**Edge case (defensive):** 미래에 Story 5.x 가 `effective_from_month = currentMonth` 를 허용하면 (예: 긴급 룰 변경), chat body 의 "다음 달부터" 표현이 거짓이 된다. 그런 변경이 들어올 때 broadcast helper 의 wording 도 함께 수정해야 함을 코드 주석에 명시. Story 5.4 는 v1 invariant 에 의존한다.

### Implementation trap #12 — null preset / null month 방어선

`formatRulePreview` 가 `null` preset 을 받을 일은 없지만 (Story 5.1 AC7 의 `@NotNull @Pattern` 가 controller 입력에서 거부, AC1 step 4 의 service-level whitelist 가 보조 가드), defensive 하게 `effective_from_month` 또는 `preset` 이 `null` 이면 chat broadcast 자체를 skip 하지 말 것 — service contract 상 항상 non-null 이고, 만약 null 이라면 위쪽 어딘가에 다른 버그가 있다 (silent failure 보다 NPE 가 더 빨리 잡힘). 그러나 `JSON.valueToTree(null).toString()` 은 `"null"` 문자열을 반환 → payload 가 `"effectiveFromMonth":null` 처럼 들어가서 schema integrity 가 깨짐. **단위 테스트에 null-preset / null-month 케이스를 추가하지 말 것** — 정상 흐름 가정.

### Implementation trap #13 — `ChatService.publishSystem` 의 `Propagation.REQUIRES_NEW` 와 outer afterCommit 의 상호작용

`publishAfterCommit` 의 lambda 가 실행될 때 outer txn 은 이미 commit 됨. lambda 안에서 `chatService.publishRuleChangeSystemMessage(...)` → 내부적으로 `publishSystem(...)` → `@Transactional(propagation = Propagation.REQUIRES_NEW)`. 이미 트랜잭션 동기화가 떨어진 상태에서 REQUIRES_NEW 는 새 트랜잭션을 시작하므로 정상 동작. **새 트랜잭션은 chat row 를 commit 한 후, 그 안의 `realtime.publishChatMessage` 가 STOMP frame 을 emit.**

순서 요약:
1. Outer txn 시작 (Controller → `@Transactional` updateRule).
2. `upsertRule` UPSERT (rule_versions 에 row insert/update).
3. `findByRoomIdAndEffectiveFromMonth` re-read.
4. `publishAfterCommit(() -> chatService.publishRuleChangeSystemMessage(...))` — synchronization 등록만.
5. Outer txn commit.
6. afterCommit phase: lambda 실행.
7. `publishSystem` REQUIRES_NEW txn 시작.
8. `messages.save(new ChatMessage(...))` → chat_messages row insert.
9. REQUIRES_NEW txn commit.
10. `realtime.publishChatMessage(roomId, MessageDto)` → STOMP frame 출하.

이 시퀀스는 DailyService → ChatService.publishSystem 패턴과 byte-identical. 새로운 race 가 추가되지 않음.

### Implementation trap #14 — 향후 `effective_from_month` payload key 의 RENAME 금지

epics line 794 가 payload 의 키 이름을 enumerate: `ruleVersionId`, `effectiveFromMonth`, `preview`. 향후 PR 이 이를 `rule_version_id` / `effective_from_month` (snake_case) 로 바꾸려는 충동을 거부할 것 — V8/V9 milestone-dedup 의 `payload->>'userId'` / `'date'` 도 camelCase 이고, 데이터베이스 인덱스가 이미 camelCase 표현식에 묶여 있다. SnakeCase 마이그레이션은 별도 backfill PR 이 필요한 wire-breaking 변경.

### Out of scope (DO NOT IMPLEMENT IN THIS STORY)

1. **Push notification 발행** (FCM/APNs) — AC7 fence. PRD FR-8.5.8 가 push 를 명시하지 않음. 추가 시 NotificationKind 확장 + RoutingInvalidation 케이스 + 사용자 동의 설정 새 컬럼 등 부수 작업이 광범위.
2. **새 `ChatMessageKind.RULE_CHANGE` enum 값** — AC8 fence. V7 `chk_chat_messages_kind` widening 이 필요해지고 그건 V14 migration. epics line 794 가 명시적으로 `kind='SYSTEM'` 잠금.
3. **별도 sealed `RealtimeEvent.RuleChange` variant** — chat 채널 매핑으로 충분, architecture-deviation 노트만 추가.
4. **새 STOMP topic** `/topic/rooms.{id}.rule` — `chat` 채널 재사용, JwtChannelInterceptor regex 변경 ZERO.
5. **FE 측 새 컴포넌트 / hook / 라우트** — `useChatRealtime` + `<SystemMessage>` + KIND_PREFIX 가 자동으로 처리.
6. **Dedupe (V8/V9 / V12 패턴 같은 partial unique index)** — AC5 fence. 같은 nextMonth 재편집이 새 SYSTEM row 를 만든다는 게 product 의도.
7. **Push title / body i18n** — v1 한국어 only.
8. **챗 메시지 deep-link 로 rule editor 화면 이동** — UX 가 명시하지 않음. payload 의 `ruleVersionId` 는 미래 deep-link 가 필요해지면 사용 가능.
9. **Rule revert / undo 액션** — 별도 story.
10. **Previous rule snapshot diff** — payload 에 `previousPayload` 추가 안 함.
11. **변경 액터 (leader) 의 nickname / userId 노출** — `senderUserId = NULL` 유지, payload 에 actor 정보 ZERO. 미래 추적 필요 시 `room_rule_versions.created_by_user_id` 가 source of truth.
12. **Brand-voice positive-frame 강화** — preview phrase 가 `"매일 업데이트, 주말 포함"` 의 사실 진술. 추가 emotive copy ("함께 더 깊이 들여다봐요" 등) 추가하지 말 것 — Story 8.2 brand-voice copy pass scope.
13. **Spectator 멤버에게 SYSTEM row 숨기기** — Implementation trap #10 참조; SPECTATOR 도 받음이 의도.
14. **GeneratedTokens.java / tokens.json 변경** — 디자인 토큰 변경 없음.
15. **새 ApiExceptionHandler 매핑** — afterCommit phase 의 RuntimeException 은 HTTP boundary 밖, log only.
16. **Architecture §6.4 endpoint 테이블 업데이트** — 새 endpoint 추가 없음, 변경 불요. 단, §597 sealed variant 표에 architecture-deviation 노트 추가는 별도 doc PR (블록커 아님).
17. **`ChatService.publishMemberJoinedSystemMessage` 패턴 변경** — 그 메서드는 Story 1.6 SYSTEM row 발행자; Story 5.4 의 새 헬퍼와 독립. 시그너처 변경 금지.
18. **`MILESTONE` dedupe 패턴을 rule-change 에 적용** — AC5 fence; dedupe 안 함.
19. **새 BMad story (5.5, 5.6) 의 prep work** — 5.4 가 epic 5 의 마지막 backlog story. Epic 5 retrospective 가 optional 로 sprint-status 에 있고, 5-5 / 5-6 는 backlog 자체에 없음 (epics 가 5.1-5.4 까지만 enumerate).

### Project structure notes

- BE files:
  - `BE/src/main/java/com/yeosal/api/room/chat/ChatService.java` (MODIFIED) — chat 도메인 서비스 확장. 새 helper 가 같은 클래스에 머무는 것이 cohesion 보존.
  - `BE/src/main/java/com/yeosal/api/survival/RoomRuleService.java` (MODIFIED) — write site 의 afterCommit hook 만 추가. 패키지 위치 (survival/) 은 Story 5.1 의 결정 유지.
- BE tests mirror source layout:
  - `BE/src/test/java/com/yeosal/api/room/chat/ChatServiceRuleChangeTest.java` (NEW) — ChatService 의 새 메서드 단위 테스트.
  - `BE/src/test/java/com/yeosal/api/survival/RoomRuleServiceTest.java` (MODIFIED) — 5 새 케이스 + 13 기존 케이스의 생성자 픽스처 확장.
  - `BE/src/test/java/com/yeosal/api/survival/RoomRuleControllerTest.java` (MODIFIED) — `@MockBean ChatService` 추가만.
  - `BE/src/test/java/com/yeosal/api/survival/RoomRuleChatBroadcastIT.java` (NEW opt-in IT) — end-to-end PATCH → chat row + STOMP frame 검증.
- FE 변경 ZERO — `useChatRealtime` + `<SystemMessage>` + chat tab last-message peek 자동 동작.

### Architecture decisions traceability

| FR / decision | AC | File |
|----|----|------|
| FR-8.5.8 (rule-version change broadcast in chat) | AC1, AC2, AC5, AC6 | `ChatService.publishRuleChangeSystemMessage` (new) + `RoomRuleService.updateRule` (extended afterCommit hook) |
| Epics line 793-794 (kind='SYSTEM' + payload shape) | AC1, AC4, AC8 | `ChatService.publishRuleChangeSystemMessage` payload builder |
| Epics line 798 (existing SystemMessage 컴포넌트 distinct visual) | AC6, AC8 | FE `<SystemMessage>` 자동 렌더 (zero change) |
| Epics line 800-802 (replace semantic ⇒ 새 메시지) | AC5 | `RoomRuleService.updateRule` afterCommit hook 이 매 호출마다 실행 |
| Epics line 804 (brand-voice lexicon, no `벌금/실패`) | AC3, AC10 | `formatRulePreview` + `RoomRuleServiceTest.bodyAvoidsBrandLexicon` |
| project-context.md:191 (no emojis) | AC10 | no source-file emojis |
| project-context.md:280 (channel-scoped log prefix) | AC2 | `[chat]` prefix in failure log |
| project-context.md:146 (TDD RED→GREEN) | AC11, AC12 | RED → GREEN per file |
| project-context.md:88 (constructor injection only) | AC2 | `ChatService chatService` 7번째 생성자 인자 |
| Architecture §597 (RealtimeEvent.RuleChange) | architecture deviation | chat 채널 매핑으로 변형, sealed variant 미신설 |

### References

- Epics: `_bmad-output/planning-artifacts/epics.md:784-804` (Epic 5 + Story 5.4 ACs), `epics.md:1171` (FR Coverage Map "Story 5.4 (rule-change broadcast message)")
- PRD: `_bmad-output/planning-artifacts/prd.md:408` (FR-8.5.8 broadcast requirement)
- Architecture:
  - `_bmad-output/planning-artifacts/architecture.md:593-598` (sealed `RealtimeEvent` variants; Story 5.4 architecture-deviation 노트)
  - `_bmad-output/planning-artifacts/architecture.md:802-817` (REST endpoint table; PATCH /rules 만 — Story 5.4 는 새 endpoint 추가 없음)
- project-context: `_bmad-output/project-context.md:88` (constructor injection), `:144-146` (TDD), `:176` (package-by-feature), `:191` (no emojis), `:280` (channel-scoped log prefix), `:117` (RealtimePublisher chokepoint — services 가 SimpMessagingTemplate 직접 주입 금지)
- Story 5.1: `_bmad-output/implementation-artifacts/5-1-rule-edit-with-next-month-only-application.md` (Epic 5 first wiring — RoomRuleService.updateRule 의 happy path + UPSERT semantic)
- Story 3.5: `_bmad-output/implementation-artifacts/3-5-kudos-message-endpoint-chat-messages-kind-extension.md` (chat broadcast precedent — KUDOS row + payload-builder pattern + brand-voice 테스트 패턴)
- Existing BE code:
  - `BE/src/main/java/com/yeosal/api/room/chat/ChatService.java:128-167` (`publishSystem` REQUIRES_NEW + `publishMemberJoinedSystemMessage` payload-builder precedent)
  - `BE/src/main/java/com/yeosal/api/room/chat/ChatService.java:142` (`realtime.publishChatMessage` fan-out — Story 5.4 가 자동 활용)
  - `BE/src/main/java/com/yeosal/api/room/chat/ChatMessage.java:42-47` (`@Enumerated(EnumType.STRING)` SYSTEM kind storage)
  - `BE/src/main/java/com/yeosal/api/room/chat/ChatMessageKind.java` (SYSTEM 값 V7 시점부터 존재 — 변경 없음)
  - `BE/src/main/java/com/yeosal/api/daily/DailyService.java:244-306` (`publishGoalSystemMessages` afterCommit 패턴 — Story 5.4 가 byte-identical 적용)
  - `BE/src/main/java/com/yeosal/api/survival/RoomRuleService.java` (Story 5.1 의 chokepoint — 7번째 인자 추가 site)
  - `BE/src/main/java/com/yeosal/api/realtime/RealtimePublisher.java:50-51` (`publishChatMessage` 메서드 — chat 토픽 emit)
  - `BE/src/main/java/com/yeosal/api/realtime/JwtChannelInterceptor.java:43-44` (`chat` 토큰 ROOM_TOPIC permit — 변경 없음)
- Existing FE code:
  - `FE/src/api/chat.ts:8-15` (`ChatMessageKind` union — SYSTEM 이미 있음, 변경 없음)
  - `FE/src/components/chat/SystemMessage.tsx:64-71` (`SYSTEM` muted-pill 변형 — 자동 활용)
  - `FE/src/lib/query/hooks/chat.ts:157-192` (`useChatRealtime` STOMP 구독 + dedupe-by-id — 자동 활용)
  - `FE/app/(tabs)/chat.tsx:140-149` (`KIND_PREFIX["SYSTEM"] = "[알림] "` — chat tab last-message peek 자동 활용)
- Brand-voice lint: `tools/brand-voice-lint.ts:50-59` (AVOID 어휘 8개)

### Change log

| Date | Author | Change |
|------|--------|--------|
| 2026-06-03 | Maya (context engineer) | Initial context-engineered story file. Story 5.4 — Epic 5 의 마지막이자 가장 가벼운 변경: ChatService 에 `publishRuleChangeSystemMessage` 한 메서드 + RoomRuleService.updateRule 의 afterCommit hook 한 줄. NO new migration / endpoint / FE source / sealed variant / topic / enum 값 / ApiExceptionHandler 매핑. Body literal `"다음 달부터 새 규칙이 적용됩니다: "` + preview phrase 매핑 (`매일 업데이트, 주말 포함/제외`) + payload shape `{ruleVersionId, effectiveFromMonth, preview}` 모두 epics line 793-794 잠금. Replace semantic (epics line 800-802) = 매 호출 새 SYSTEM row (NOT dedupe). PRD FR-8.5.8 가 push 미명시 → AC7 fence. Architecture §597 의 `RealtimeEvent.RuleChange` 는 별도 sealed variant 가 아닌 chat 채널 매핑으로 매핑 (architecture-deviation 노트). 14 implementation traps 카탈로그 (가장 중요: Trap #1 afterCommit defer 필수, Trap #2 lambda 안의 try/catch, Trap #5 JSON.valueToTree payload escape, Trap #11 effective_from_month invariant). 19-item out-of-scope list 로 push notification + RULE_CHANGE enum 값 + 별도 sealed variant + FE 컴포넌트 추가 + dedupe pattern + previous-rule diff 등 모두 차단. AC11 test matrix net-additive 14 BE cases (7 ChatService unit + 5 RoomRuleService extension + 2 opt-in IT) + 0 FE 변경. Story 5.4 가 done 으로 진입하면 epic-5 의 4개 stories 모두 완료되어 epic-5 in-progress → done flip 가능 (epic-5-retrospective 는 optional). |
| 2026-06-03 | Dev agent (claude-opus-4-7) | Implementation complete; status flipped ready-for-dev → in-progress → review. Net-additive delta exactly matches AC11: 7 `ChatServiceRuleChangeTest` cases + 5 `RoomRuleServiceTest` cases + 2 `RoomRuleChatBroadcastIT` opt-in cases + `@MockBean ChatService` fixture line on `RoomRuleControllerTest`. BE Gradle full suite 574 tests GREEN; brand-voice 0 HARD / 198 warnings (baseline preserved — BE-only diff is outside the FE scan); FE source Δ = 0 (only 2 pre-existing FriendsTodayPager typecheck errors, no change); `git diff --check HEAD` clean. AC9 scope fence honored — banned-paths grep returns 0 lines; 8 changed files (5 modified + 3 untracked) all on the allow list. Opt-in IT (`RoomRuleChatBroadcastIT`) compiles cleanly; execution deferred to PR-CI per Story 5.1 / 5.2 / 5.3 close-out (Docker unavailable on dev host). One small deviation from the story plan: case 4 of the RoomRuleService extension is named `updateRule_registersAfterCommitSynchronization_whenInsideTransaction` and drives the synchronization manually via `TransactionSynchronizationManager.initSynchronization()` + `s.afterCommit()` (instead of the planned `TransactionTemplate.execute(...)` shape) — equivalent semantic, less ceremony, no extra Spring context. |
| 2026-06-03 | Code review patch | Added `@Transactional(propagation = Propagation.REQUIRES_NEW)` directly to `ChatService.publishRuleChangeSystemMessage`. The inner `publishSystem` delegation already carries REQUIRES_NEW, but Spring AOP **self-invocation does not pass through the proxy** — without an annotation on the outer method, any in-class call path that invokes it from inside another transaction would have the new method silently join the outer txn instead of opening a fresh one, defeating the "chat-row failure must not roll back the caller" guarantee. Adding the annotation makes the new method safer-by-default regardless of caller path (mirrors `publishMilestonesForActor`'s explicit annotation pattern). AC9 file list unchanged (single-file modification, ChatService.java only, +1 line). BE Gradle test re-run after the patch: 574/574 GREEN; banned-paths grep still 0 lines. |

## Dev Agent Record

### Agent Model Used

claude-opus-4-7 (1M context) — bmad-dev-story workflow, 2026-06-03 execution.

### Debug Log References

- BE Gradle full suite: 574 tests / BUILD SUCCESSFUL in 9s (after Story 5.4 delta).
- `ChatServiceRuleChangeTest`: 7 cases — first executed RED with 7 `cannot find symbol publishRuleChangeSystemMessage` compile errors (expected); after AC1 implementation, GREEN on rerun.
- `RoomRuleServiceTest`: 13 → 18 cases — RED with `actual and formal argument lists differ in length` (constructor arity 6→7) before `RoomRuleService` got the `ChatService` field; GREEN after.
- `RoomRuleControllerTest`: 7 cases unchanged; needed only `@MockBean ChatService` to satisfy the web slice's Spring context after the service ctor widened.
- `RoomRuleChatBroadcastIT`: 2 cases, opt-in via `@EnabledIfSystemProperty("yeosal.boot-smoke","true")`. Compile clean. Docker not available on dev host (`docker ps` failed) → IT execution deferred to PR-CI, mirroring the Story 5.1 / 5.2 / 5.3 close-out pattern.
- brand-voice-lint: 218 files scanned, 0 HARD / 198 warnings — exactly the Story 5.3 baseline (BE-only diff is outside the FE scan tree).
- FE typecheck baseline preserved: only the 2 pre-existing `FriendsTodayPager.tsx` errors (no FE source change in this story).
- `git diff --check HEAD`: clean.
- AC9 banned-path grep (`git diff --stat origin/main -- BE/src/main/resources/db/migration/ FE/src/ FE/app/ BE/src/main/java/com/yeosal/api/notification/ BE/src/main/java/com/yeosal/api/realtime/ BE/src/main/java/com/yeosal/api/common/ApiExceptionHandler.java`) returns zero lines.

### Completion Notes List

- ChatService gained a single new public helper `publishRuleChangeSystemMessage(roomId, ruleVersionId, effectiveFromMonth, preset, weekendInclude)` plus the private `formatRulePreview(preset, weekendInclude)` map. Body literal is locked byte-identically to `"다음 달부터 새 규칙이 적용됩니다: <preset-label>, <weekend-phrase>"` (ASCII colon + ASCII space, no full-width punctuation). Payload is the three-key contract from epics line 794: `{ruleVersionId, effectiveFromMonth, preview}` with `ruleVersionId` rendered as a JSON string (V8/V9 milestone-dedup convention) and the two string fields routed through `JSON.valueToTree(...).toString()` to escape safely. The helper delegates to `publishSystem(...)` so the REQUIRES_NEW + `/topic/rooms.{id}.chat` fan-out comes for free.
- RoomRuleService gained `ChatService chatService` as the 7th constructor argument (append, never insert mid-list), an `org.slf4j.Logger`, a `private void publishAfterCommit(Runnable)` helper byte-identical to `DailyService:295-306`, and a single afterCommit registration just before the existing `return RoomRuleVersionDto.from(saved)`. The lambda body wraps the chat call in an inner `try/catch RuntimeException` and emits a `[chat]` channel-scoped `log.warn` on failure — that closes both the outer-rollback-but-chat-published race and the afterCommit-phase RuntimeException leak (Implementation traps #1, #2, #13).
- AC5 replace semantic is preserved by NOT borrowing the V8/V9 MILESTONE / V12 KUDOS `ON CONFLICT DO NOTHING` shape — each `updateRule` call appends a fresh SYSTEM row. The IT case `leaderRuleEditTwice_appendsTwoSystemRows` asserts both rows land and the bodies reflect the second edit's `weekendInclude` flip.
- AC7 push fence honored: `BE/src/main/java/com/yeosal/api/notification/**` ZERO diff. AC8 enum fence honored: `ChatMessageKind` / `chk_chat_messages_kind` ZERO diff. AC9 banned-paths grep returns nothing.
- FE source ZERO change — `useChatRealtime` dedupe-by-id and the existing `<SystemMessage>` muted-pill rendering handle the new row through the same channel as MILESTONE / GOAL / REFLECTION / AUTO_LEAVE / KUDOS rows.
- Architecture deviation noted in AC9 dev notes: `RealtimeEvent.RuleChange` is intentionally delivered as a `chat_messages` SYSTEM row instead of a new sealed variant. Follow-up doc PR (non-blocker) is the only action item.
- Opt-in IT execution will run automatically inside PR-open CI; on the dev host Docker is unavailable so it stayed compile-only this session.

### File List

- `BE/src/main/java/com/yeosal/api/room/chat/ChatService.java` (modified) — added `publishRuleChangeSystemMessage` public helper and `formatRulePreview` private static helper. Existing methods untouched.
- `BE/src/main/java/com/yeosal/api/survival/RoomRuleService.java` (modified) — added `Logger log`, 7th `ChatService chatService` constructor argument + field, `publishAfterCommit` private helper, and the afterCommit chat broadcast registration inside `updateRule`. `getRule` and helpers untouched.
- `BE/src/test/java/com/yeosal/api/room/chat/ChatServiceRuleChangeTest.java` (new) — 7 Mockito unit cases covering AC1/AC3/AC4/AC6/AC10 + the Trap #6 future-preset fallback.
- `BE/src/test/java/com/yeosal/api/survival/RoomRuleServiceTest.java` (modified) — added `@Mock ChatService chatService` field, widened the `build(Clock)` fixture to pass it as the 7th arg, added an `@AfterEach` to clear any leaked `TransactionSynchronizationManager` state, and appended 5 new cases under a new `// ---------- Story 5.4 broadcast hook ----------` divider. Existing 13 cases stay byte-identical.
- `BE/src/test/java/com/yeosal/api/survival/RoomRuleControllerTest.java` (modified) — single `@MockBean ChatService chatService` line plus the import. Existing 7 cases stay byte-identical.
- `BE/src/test/java/com/yeosal/api/survival/RoomRuleChatBroadcastIT.java` (new, opt-in) — 2 `@SpringBootTest @Testcontainers` end-to-end cases for AC11 row 4. `@SpyBean SimpMessagingTemplate` asserts STOMP fan-out on `/topic/rooms.{id}.chat`; JDBC asserts a SYSTEM row with the locked body and `{ruleVersionId, effectiveFromMonth, preview}` payload landed in `chat_messages`.
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (modified) — flipped `5-4-rule-change-broadcast-in-chat` from `ready-for-dev` to `in-progress` at workflow start; this commit also flips it to `review` at finalization (single net-result line: `review`). `last_updated` bumped to 2026-06-03.
- `_bmad-output/implementation-artifacts/5-4-rule-change-broadcast-in-chat.md` (new — context-engineered then dev-recorded this session) — story file flipped Status: ready-for-dev → review, every Task / Subtask checkbox marked done, Dev Agent Record / File List / Change Log filled.
