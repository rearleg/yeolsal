# Story 3.5: Kudos message endpoint + chat_messages.kind extension

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As **a room member who wants to support a friend in `RED`/`SPECTATOR` without spending points**,
I want **a 0-cost endpoint that posts an invitation-toned `kind='KUDOS'` chat row, fires exactly one per-receiver push, and is dedup-keyed to 1 / KST day / (sender, target) pair**,
so that **"응원만 보내기" is a real first-class supportive action that closes UX U3 — not vaporware UI — and unblocks Story 3.2's Friend Gift Modal third CTA**.

PRD authority: **FR-8.3.9** (root authority for kudos endpoint + 1-per-KST-day dedupe + invitation-tone push + `chat_messages.kind='KUDOS'` payload shape + brand-voice lint warn on AVOID lexicon).
Architecture authority: **§4.11** (single batched migration discipline — sprint-change deviation noted below), **§4.14** (Realtime topic privacy — server-side filtering only), **§4.15** (Brand-voice lint Rule 2 WARN), **§5.1** (BE patterns — single `ApiExceptionHandler`, `@Valid`, constructor injection, partial unique index idempotency per V8/V9 reference), **§5.4** (Privacy patterns — quiet hours respected).
Epics ref: `_bmad-output/planning-artifacts/epics.md` lines 563–606 (Story 3.5 ACs verbatim) and line 1192 (execution order lock: **3.1 → 3.5 → 3.2 → 3.3 → 3.4**).
UX ref: J3 Friend-revives-friend ⭐ flow (`_bmad-output/planning-artifacts/ux-design-specification.md` lines 1310–1338, especially line 1322 — "Kudos message 송신 / chat에 `KIND='KUDOS'` row / Story 3.5"); pattern inspiration I2 Strava Kudos lines 446–448, 486; Friend Gift Modal 3 CTA equal weight line 722, 772; `<SystemMessage subMode='postcard'>` rendering via D4 surface assignment lines 1115–1135 + 1165–1166.
Execution-order lock (epics line 1192): **3.1 → 3.5 → 3.2 → 3.3 → 3.4**. Story 3.5 lands SECOND in Epic 3 — Story 3.1 (the revival foundation) is done (PR #75, merged 2026-05-17). Downstream Story 3.2 (Friend Gift Modal) consumes the `POST /api/v1/rooms/{id}/kudos` endpoint this story ships AND depends on the `chat_messages.kind = 'KUDOS'` enum + partial unique index.

> **Foundation note.** The `chat_messages` table exists since V7 (`BE/src/main/resources/db/migration/V7__chat_messages.sql`); the existing `CHECK` constraint `chk_chat_messages_kind` permits `'USER', 'SYSTEM', 'GOAL', 'REFLECTION', 'MILESTONE', 'AUTO_LEAVE'`. The Java entity layer (`com.yeosal.api.room.chat.{ChatMessage, ChatMessageKind, ChatMessageRepository, ChatService, ChatController}`) is in place. Friendships (`com.yeosal.api.friend.{Friendship, FriendshipStatus, FriendshipRepository}`), notifications (`com.yeosal.api.notification.{NotificationKind, NotificationService.sendEvent}`), realtime (`com.yeosal.api.realtime.{RealtimePublisher, JwtChannelInterceptor}`), and survival-state read access (`com.yeosal.api.survival.SurvivalStatus` + `SurvivalStateRepository.findByRoomIdAndUserId`) are all in place. Story 3.5 ships: (a) a **NEW V12 Flyway migration** that extends `chk_chat_messages_kind` with `'KUDOS'` and adds the partial unique dedupe index (b) a `ChatMessageKind.KUDOS` enum value (c) a NEW `kudos/` BE module (`KudosController`, `KudosService`, `KudosRequest`, `KudosDto`, `KudosAlreadySentTodayException`, `KudosTargetNotEligibleException`, `NotFriendsException`) with `@ExceptionHandler` mappings in `ApiExceptionHandler` (d) a `NotificationKind.KUDOS_RECEIVED` enum value (e) `NotificationService.sendEvent` reuse for the receiver push (f) `RealtimePublisher.publishKudos` + `JwtChannelInterceptor.ROOM_TOPIC` regex extension to permit `/topic/rooms.{id}.kudos` (g) the FE `api/kudos.ts` typed client, `useSendKudos` mutation, `<SystemMessage>` `KUDOS` visual variant (postcard / ember-tone), notification-invalidation router branch, brand-voice lint warn-only check on the optional message body.

> **CRITICAL — V11 / V12 migration deviation (read first).** The epics text at line 573 states "the V11 migration includes the `chat_messages.kind` enum extension (decision locked 2026-05-11: single migration, batched with all v1 schema deltas per brownfield Flyway convention — see Architecture §4.11)". That decision was authored **before V11 shipped to production**. V11 was merged via PR #55 (commit `c2b9e7d`, 2026-05-13) + PR #57 (the IMMUTABLE hotfix) and **does NOT include the `'KUDOS'` kind or the `ux_kudos_one_per_day` partial unique index** — confirmed by reading `BE/src/main/resources/db/migration/V11__survival_revival_economy.sql` end-to-end. Flyway runs each migration exactly once; we cannot edit V11. **Story 3.5 therefore adds a NEW migration `V12__chat_kudos.sql`** (smallest free integer, project-context Migrations rule). The epics text is documentation drift; the architecture §4.11 "single batched migration" discipline still holds for the remaining V11 backfill scope — Story 3.5 is a clean schema delta after V11 landed.

> **CRITICAL — PRD partial unique index expression is incorrect (read second).** PRD FR-8.3.9 and epics line 575 write the dedupe index expression as `date_part('day', created_at at time zone 'Asia/Seoul')`. **This is wrong.** `date_part('day', …)` returns only the **day-of-month integer (1..31)** — so a kudos sent Jan 1 and another sent Feb 1 (different KST days, same day-of-month) would collide on the unique index, and a kudos sent Jan 1 + Jan 31 would not collide. The correct expression is **`((created_at at time zone 'Asia/Seoul')::date)`** — `at time zone 'Asia/Seoul'` converts the `timestamptz` to a timezone-less `timestamp` in Asia/Seoul wall-clock (IMMUTABLE because the timezone is a literal), and the subsequent `::timestamp::date` cast is IMMUTABLE (different from `timestamptz::date`, which is STABLE and triggers SQLSTATE 42P17 in a partial unique index expression — the trap that bit Story 1.4 PR #57 commit `4f741ff`). **V12 MUST use `((created_at at time zone 'Asia/Seoul')::date)` and the service-layer `ON CONFLICT` predicate MUST match it exactly.** Mismatched predicate ↔ index defeats the idempotency contract and surfaces as `DataIntegrityViolationException` instead of `409 KUDOS_ALREADY_SENT_TODAY`.

## Acceptance Criteria

1. **AC1 — V12 migration extends `chat_messages.kind` + adds `ux_kudos_one_per_day` (epics 573–575; PRD FR-8.3.9; Architecture §4.11, §5.1 idempotency rule).**
   - **Given** the production schema is at V11 (`flyway_schema_history` last entry `version='11'`),
   - **When** the application boots after deploying this story,
   - **Then** Flyway applies `V12__chat_kudos.sql` exactly once with this content (all statements idempotent — `drop ... if exists`, `create ... if not exists`):
     ```sql
     -- V12 — chat_messages.kind extension + Kudos 1/(KST day, sender, target) dedupe.
     -- Story 3.5. PRD FR-8.3.9. UX U3 disposition ACCEPT.

     -- (1) Extend the chk_chat_messages_kind CHECK to permit 'KUDOS'.
     -- V7 created the constraint with the original 6 kinds; drop+recreate is
     -- the only Postgres-supported way to widen a named CHECK constraint.
     alter table chat_messages
         drop constraint if exists chk_chat_messages_kind;
     alter table chat_messages
         add constraint chk_chat_messages_kind
             check (kind in ('USER', 'SYSTEM', 'GOAL', 'REFLECTION',
                             'MILESTONE', 'AUTO_LEAVE', 'KUDOS'));

     -- (2) Partial unique index — at most one KUDOS per (sender, target, KST day).
     -- Idempotency follows V8/V9 milestone-dedup precedent. The KST-day key uses
     -- '((created_at at time zone ''Asia/Seoul'')::date)', NOT 'date_part('day',...)':
     --   - 'at time zone ''Asia/Seoul''' on a timestamptz yields a timezone-less
     --     timestamp (IMMUTABLE because the timezone is a literal constant).
     --   - The subsequent '::date' cast on a plain timestamp is IMMUTABLE.
     -- (Direct 'timestamptz::date' is STABLE — rejected by Postgres inside a
     -- partial unique index expression with SQLSTATE 42P17, cf. PR #57.)
     create unique index if not exists ux_kudos_one_per_day
         on chat_messages (
             sender_user_id,
             ((payload ->> 'targetUserId')),
             (((created_at at time zone 'Asia/Seoul')::date))
         )
         where kind = 'KUDOS';
     ```
   - **Why `payload->>'targetUserId'` and not a dedicated column:** the V7 `chat_messages` row carries `payload jsonb` for system-speech metadata (V8/V9 milestone dedup uses `payload->>'userId'` and `payload->>'date'`/`payload->>'month'` exactly this way at `BE/src/main/resources/db/migration/V9__chat_milestone_per_day.sql:17-25`). Adding a typed column for a single new kind would churn the schema for every future feature. Kudos rows write `payload = {"senderUserId":"<id>", "targetUserId":"<id>", "message":"<text-or-empty>"}` with both ids as **strings** (matching V8/V9's text-cast convention so a future numeric writer doesn't break the index).
   - **AC1 verification:** new IT `KudosMigrationIT` asserts (a) the constraint now accepts a `kind='KUDOS'` insert, (b) the partial unique index exists with the documented expression (read from `pg_indexes.indexdef`), (c) Postgres rejects a duplicate `(sender, target, KST day)` insert with SQLSTATE `23505` whose constraint-name is `ux_kudos_one_per_day`.
   - [Architecture §4.11 brownfield Flyway convention; project-context "smallest free integer V<N>__<slug>.sql"; V8/V9 partial unique index precedent]

2. **AC2 — `ChatMessageKind` enum + ChatService whitelist (epics 573–575; project-context "DTOs are records, enum-string whitelist").**
   - **Given** Java enum binding mirrors the DB CHECK,
   - **When** the application boots after deploying this story,
   - **Then** `BE/src/main/java/com/yeosal/api/room/chat/ChatMessageKind.java` adds the `KUDOS` enum value **at the end** (preserves ordinal stability for the existing 6 values).
   - **Mechanism:** the enum already uses `@Enumerated(EnumType.STRING)` at `ChatMessage.java:42-44` so adding a new constant is wire-stable. The class-level docstring is updated to mention KUDOS as the seventh kind. The internal `ChatService.publishSystem(...)` guard at `ChatService.java:133-135` already throws `IllegalArgumentException` if `kind == USER` — leave it unchanged; the parallel kudos write path lives in the **NEW** `KudosService` (AC4) and does NOT call `ChatService.publishSystem` (kudos rows have a non-null `sender_user_id`, unlike SYSTEM rows). The existing FE `ChatMessageKind` union type (`FE/src/api/chat.ts:4-10`) gets `"KUDOS"` appended (AC9).
   - [project-context Java rule "DTOs are records, enum-string whitelist", `ChatMessageKind.java` is the canonical Java mirror of the DB CHECK]

3. **AC3 — `POST /api/v1/rooms/{id}/kudos` endpoint contract (epics 577–584; PRD FR-8.3.9).**
   - **Given** I am authenticated, member of room R, and the request body is `{"targetUserId": <long>, "message": <string-or-null, max 60 chars>}`,
   - **When** I call `POST /api/v1/rooms/{id}/kudos` with `Content-Type: application/json`,
   - **Then** within a single `@Transactional` boundary, **in this exact order**:
     1. Resolve `me` via `CurrentUser.require(auth)` (existing pattern, mirrors `ChatController.send` at `ChatController.java:46-49`).
     2. Cheap precheck — `roomMembers.existsByRoomIdAndUserId(roomId, me.getId())` MUST be true; otherwise throw `ForbiddenException("방 멤버만 응원을 보낼 수 있어요.")` → `403 FORBIDDEN`.
     3. Cheap precheck — `me.getId().equals(targetUserId)` MUST be false; otherwise throw `BadRequestException("자기 자신에게는 응원을 보낼 수 없어요.")` → `400 BAD_REQUEST`.
     4. Cheap precheck — `roomMembers.existsByRoomIdAndUserId(roomId, targetUserId)` MUST be true; otherwise throw `NotFoundException("대상 멤버를 찾을 수 없어요.")` → `404 NOT_FOUND`.
     5. Sender spectator gate — load `survivalStates.findByRoomIdAndUserId(roomId, me.getId())` and if `status == SPECTATOR`, throw the existing `SpectatorWriteForbiddenException` → `403 FORBIDDEN` code `SPECTATOR_WRITE_FORBIDDEN`. Reuse the existing exception class at `com.yeosal.api.common.SpectatorWriteForbiddenException` (do NOT create a kudos-specific variant — the wire code is shared with chat-message blocking; the FE branches on the same code).
     6. Target eligibility gate — load `survivalStates.findByRoomIdAndUserId(roomId, targetUserId)` and if `status ∉ {RED, SPECTATOR}`, throw `KudosTargetNotEligibleException("응원은 회생을 기다리는 멤버에게만 보낼 수 있어요.")` → `400 BAD_REQUEST` code `KUDOS_TARGET_NOT_ELIGIBLE`.
     7. Friendship gate — load the bidirectional friendship via `friendships.findBetween(me, target).filter(f -> f.getStatus() == FriendshipStatus.ACCEPTED)`. If the optional is empty, throw `NotFriendsException("친구가 된 멤버에게만 응원을 보낼 수 있어요.")` → `403 FORBIDDEN` code `NOT_FRIENDS`. Reuses the canonical lookup pattern at `FriendService.canView` line 181–183.
     8. Message normalisation — trim the `message` string; treat empty/whitespace-only as `null`. If the trimmed length > 60 (UTF-16 code-unit count, mirroring `@Size(max = 60)`), throw `BadRequestException("응원 메시지는 60자까지 보낼 수 있어요.")` → `400 BAD_REQUEST` code `BAD_REQUEST`. The `@Valid @Size(max = 60)` on the DTO is the primary gate; this normalisation guards the storage path.
     9. INSERT `chat_messages` row via a NEW native `@Modifying @Query` `insertKudosIfAbsent(roomId, senderUserId, body, payload)` on `ChatMessageRepository`. SQL shape:
         ```sql
         insert into chat_messages (room_id, sender_user_id, kind, body, payload)
         values (:roomId, :senderUserId, 'KUDOS', :body, cast(:payload as jsonb))
         on conflict (
             sender_user_id,
             ((payload ->> 'targetUserId')),
             (((created_at at time zone 'Asia/Seoul')::date))
         ) where kind = 'KUDOS'
         do nothing
         returning id
         ```
         Caller reads the returned row count (0 = same-day duplicate, 1 = inserted). Body MUST be the rendered Korean string `"<senderNickname>이 응원을 보냈어요"` (no AVOID-lexicon words; line-length safe for chat list rendering); payload MUST be the literal JSON string `{"senderUserId":"<id>","targetUserId":"<id>","message":"<text>"}` (both ids as strings — matches V8/V9 precedent; `message` field present even when null → use empty string `""` so consumers can read a stable shape).
     10. On zero rows inserted, throw `KudosAlreadySentTodayException("오늘은 이미 응원을 보냈어요.")` → `409 CONFLICT` code `KUDOS_ALREADY_SENT_TODAY`. Defence-in-depth: also wrap the call in a try/catch for `DataIntegrityViolationException` and inspect the root constraint name via `org.springframework.core.NestedExceptionUtils.getMostSpecificCause(...)` — if the constraint name equals `ux_kudos_one_per_day`, rethrow as `KudosAlreadySentTodayException` (the Story 3.1 service-layer translation pattern at `RevivalService` lines 245-258).
     11. After the INSERT succeeds, fire a `KudosSentEvent { roomId, senderUserId, targetUserId, messagePreview, occurredAt }` via `ApplicationEventPublisher` (NEW record — see AC7). The downstream listener (NEW `KudosRealtimeListener`) emits the realtime frame and queues the push **post-commit**, mirroring the `SurvivalStateRealtimeListener` precedent at `BE/src/main/java/com/yeosal/api/survival/SurvivalStateRealtimeListener.java:56-105`.
   - **Response:** `201 CREATED` (Spring annotation `@ResponseStatus(HttpStatus.CREATED)` on the controller method — required by epics line 582 "return `201 Created`") with `ApiResponse.of(new KudosDto(chatMessageId, roomId, senderUserId, targetUserId, message, createdAt))` (record fields: `kudosId: long` — the new chat_messages id, `roomId: long`, `senderUserId: long`, `targetUserId: long`, `message: String` (the trimmed body, possibly empty), `occurredAt: Instant`). The envelope wrap `{ "data": { ... } }` is automatic.
   - [Epics 577–584; PRD FR-8.3.9; project-context "BE controller path `/api/v1/...`, `ApiResponse.of(dto)` wrap, `@Valid` on DTO, single `ApiExceptionHandler`"]

4. **AC4 — `KudosService` is a NEW dedicated module (NOT extension of `ChatService`).**
   - **Given** kudos has its own dedupe / friendship / eligibility / push / realtime semantics distinct from chat-message authoring,
   - **When** the BE module shape is decided,
   - **Then** the implementation lives at **NEW** package `com.yeosal.api.room.chat` (KudosController + KudosService + KudosRequest + KudosDto + KudosSentEvent + the three new exceptions). **Rationale:** (a) `ChatService.sendUserMessage` is the user-text authoring path and has the `MAX_BODY_LENGTH = 2000` ceiling, no friendship check, no per-day dedupe — kudos differs on all three axes; (b) `ChatService.publishSystem` is the system-speech path with a null `sender_user_id` — kudos has a non-null sender. A separate service keeps `ChatService` lean and avoids cross-cutting branches. **Co-located in the same package** so the `ChatMessageKind`, `ChatMessageRepository`, and `ChatMessage` reads/writes stay package-private where appropriate (the existing `ChatMessageRepository` is `public interface` — no visibility change required). NEW files only — `ChatService.java`, `ChatController.java`, `ChatMessage.java`, `ChatMessageKind.java` are read but **not modified** beyond AC2's enum addition and AC9's repository method append.
   - **Why not extend `ChatController` with a second `@PostMapping`:** `ChatController` is currently scoped to message CRUD on `/{id}/messages`; the kudos endpoint is on `/{id}/kudos`. Two POSTs on the same controller is acceptable but the second adds a heavyweight set of dependencies (friendships, survival state, notification, realtime listener wiring) — separating the controller keeps the dependency graph small and matches Story 3.1's `RevivalController` precedent (also separated from `RoomController`).
   - [Story 3.1 `RevivalController` separation precedent; project-context "package-by-feature, high cohesion / low coupling"]

5. **AC5 — Domain exceptions + `ApiExceptionHandler` mappings (epics 581, 586–596; project-context Java rule).**
   - **Given** four new error codes are introduced: `KUDOS_ALREADY_SENT_TODAY` (409), `KUDOS_TARGET_NOT_ELIGIBLE` (400), `NOT_FRIENDS` (403), and the reused `SPECTATOR_WRITE_FORBIDDEN` (403),
   - **When** the response envelope is rendered,
   - **Then** each error path returns the exact wire code documented above. Implementation:
     - **NEW** `BE/src/main/java/com/yeosal/api/room/chat/KudosAlreadySentTodayException.java` — `public class KudosAlreadySentTodayException extends RuntimeException { public static final String CODE = "KUDOS_ALREADY_SENT_TODAY"; public KudosAlreadySentTodayException(String msg) { super(msg); } }`.
     - **NEW** `BE/src/main/java/com/yeosal/api/room/chat/KudosTargetNotEligibleException.java` — same shape, CODE = `"KUDOS_TARGET_NOT_ELIGIBLE"`.
     - **NEW** `BE/src/main/java/com/yeosal/api/room/chat/NotFriendsException.java` — same shape, CODE = `"NOT_FRIENDS"`, extends `ForbiddenException` so the parent's 403 status flows through if a future refactor re-routes the handler. Follow the `SpectatorWriteForbiddenException` typed-subclass + CODE-constant precedent at `BE/src/main/java/com/yeosal/api/common/SpectatorWriteForbiddenException.java:16-24`.
     - **UPDATE** `BE/src/main/java/com/yeosal/api/common/ApiExceptionHandler.java`: add **three new** `@ExceptionHandler` methods returning the exact (status, code) tuples above. Place them after the existing Story 3.1 mappings (around line 175). The `SpectatorWriteForbiddenException` handler at lines 60–65 already exists — DO NOT duplicate. Pattern: the methods are package-private (no access modifier), mirror the Story 3.1 `alreadyRevived` / `insufficientPoints` shape at lines 138–155, and wire the CODE constant directly (`ApiErrorResponse.of(KudosAlreadySentTodayException.CODE, exception.getMessage())`).
   - **Rationale (project-context):** "Adding a new domain exception without a matching `@ExceptionHandler` in `ApiExceptionHandler` results in a generic 5xx that pollutes the Sentry server-bug channel." Three new exceptions → three new handlers, all in the same advice class. **Do NOT introduce a second `@RestControllerAdvice`.**
   - [Epics 581, 586–596; project-context "Single `@RestControllerAdvice`, typed domain exceptions"; Story 3.1 precedent]

6. **AC6 — Receiver push notification via `NotificationService.sendEvent` (epics 583; PRD FR-8.3.9 line "One push to receiver").**
   - **Given** the kudos write committed successfully and a `KudosSentEvent` was published,
   - **When** the `KudosRealtimeListener` fires post-commit (AFTER_COMMIT phase),
   - **Then** the listener calls **`notificationService.sendEvent(target, NotificationKind.KUDOS_RECEIVED, key, title, body, Duration.ZERO)`** where:
     - `key = "<chatMessageId>"` (unique per kudos row — guarantees no `notification_log` debounce collision across rapid same-pair kudos on different days).
     - `title = "응원이 도착했어요 🌿"` (locked copy; brand-voice "응원" + "도착" — invitation tone per FR-8.3.4 / FR-8.8.2; emoji 🌿 mirrors the existing toast convention).
     - `body = "<senderNickname>이 응원을 보냈어요"` (epics 583 verbatim — substitutes the sender's `users.nickname`; line MUST NOT contain any AVOID-lexicon word).
     - `Duration.ZERO` debounce — the partial unique index already enforces 1/day/(sender, target), so the BE service-layer dedupe is the authority; the `NotificationLog` row exists for audit but not for further suppression.
   - **NEW** `NotificationKind.KUDOS_RECEIVED` enum value (project-context Java rule "DTOs/enums match wire shape"). Update `NotificationService.isCronEnabled(...)` switch at lines 137-154 with a new branch `case KUDOS_RECEIVED -> pref.isEventHooksEnabled();` — kudos rides the same `event_hooks_enabled` toggle as friend pings / spectator digest (one switch silences all relational pings without disabling goal/reflection nudges).
   - **Quiet hours apply** (existing `QuietHoursPolicy` is checked inside `sendEvent`). Cross-check: kudos at 23:30 KST when the receiver's `quiet_start_hour=22` → push is suppressed. **The `chat_messages` row is still inserted** — the daily-digest job (Story 2.2) and the receiver opening the room next morning will surface the kudos via the chat list re-render (AC10 FE branch). **Do not** introduce a "delayed push" queue; that's out of scope.
   - **Event hooks toggle off:** the BE returns `201 Created` regardless; only the push is suppressed. The donor's toast (AC10) still fires because the FE acts on the HTTP response, not on the push.
   - [Epics 583; PRD FR-8.3.9; `NotificationService.sendEvent` at `NotificationService.java:90-113` for the REQUIRES_NEW pattern; project-context "quiet hours respected for all push notifications"]

7. **AC7 — Realtime emit + topic auth (epics 584; Architecture §4.14 server-side privacy).**
   - **Given** the kudos write committed successfully,
   - **When** the `KudosRealtimeListener` fires post-commit,
   - **Then** it ALSO calls `realtime.publishKudos(roomId, payload)` (NEW method on `RealtimePublisher`) which emits to **`/topic/rooms.{roomId}.kudos`** (dot-separator, matches the existing chat/members/survival/points convention at `RealtimePublisher.java:43-49` — **NOT** the slash-separator the architecture §4.14 prose uses, which is documentation drift). The payload `KudosSentPayload` record fields: `senderId: long, targetId: long, messagePreview: String, occurredAt: Instant`. Where `messagePreview = body.substring(0, Math.min(body.length(), 40))` (first 40 chars of the rendered chat-row body — protects the unencrypted broker channel from any future longer-body change while keeping the room-awareness frame informative).
   - **CRITICAL — STOMP auth interceptor MUST be updated.** `BE/src/main/java/com/yeosal/api/realtime/JwtChannelInterceptor.java` line 43-44 defines the room-topic regex as `^/topic/rooms\\.(\\d+)\\.(chat|members|survival|points)$`. **Add `|kudos`** so authenticated members can SUBSCRIBE to the new topic. The Story 3.1 review patch had exactly this issue (memory: "JwtChannelInterceptor.ROOM_TOPIC permits /topic/rooms.{id}.points" — extending here to `(chat|members|survival|points|kudos)`). **Without this update, the FE STOMP subscription to `/topic/rooms.{id}.kudos` will be silently dropped by `preSend` returning null** (deny-by-default at `JwtChannelInterceptor.java:107-114`).
   - **Failure semantics:** `RealtimePublisher.publishKudos` MUST swallow broker errors with a warn-and-continue log (mirror the existing `sendTopic(...)` helper at `RealtimePublisher.java:111-118`) — a STOMP hiccup must NEVER roll back the surrounding kudos transaction. The chat row + push are independently delivered.
   - [Epics 584; Architecture §4.14 server-side privacy enforcement; `RealtimePublisher` failure-swallow pattern; Story 3.1 review-patch precedent on `JwtChannelInterceptor`]

8. **AC8 — Receiver-side render: `<SystemMessage>` KUDOS variant in chat list (epics 598-600; UX D4 postcard sub-mode lines 1115-1135, 1165-1166).**
   - **Given** the receiver opens the kudos push (or opens the room normally),
   - **When** the chat list renders the persisted KUDOS row,
   - **Then** the `<SystemMessage>` component at `FE/src/components/chat/SystemMessage.tsx` adds a **NEW** case to the `visualFor(kind)` switch at lines 20-60:
     ```ts
     case "KUDOS":
       return {
         icon: "favorite",                 // MaterialIcons heart filled — matches "응원" semantic
         iconColor: palette.coralDeep,     // ember tone per UX line 772 "ember-tone outline + amber"
         pillBg: palette.coralSoft,        // postcard-warm pill background
         textColor: palette.inkDeep,       // distinct from grey SYSTEM text — "응원" stands out
         emphasized: true,                 // bodyStrong variant — kudos is a first-class moment, not a muted system row
       };
     ```
   - **NEW** `KIND_LABEL` entry at lines 62-69: `KUDOS: "응원"` (used as the accessibility row prefix — screen reader announces "응원: <senderNickname>이 응원을 보냈어요").
   - **MessageBubble routing:** `FE/src/components/chat/MessageBubble.tsx` lines 19-22 already delegate every non-USER kind to `<SystemMessage>`, so KUDOS automatically flows through this path. No change to `MessageBubble.tsx`.
   - **Distinct from SYSTEM:** the `KIND_LABEL[KUDOS] = "응원"` value + the heart icon + the coral-warm pill background visually separate KUDOS rows from the muted grey SYSTEM (`info-outline` icon, `palette.inkMute` text) so the chat scroll looks invitation-toned, not pure noise. **Do NOT introduce a fully separate `<KudosMessage>` component** — the existing `<SystemMessage>` visual mechanism is the surface assignment mechanism per UX line 600 ("KUDOS row with sender name + message in a distinct visual variant `<SystemMessage subMode='postcard'>` or v2 equivalent"). The `subMode='postcard'` prop does not yet exist as a coded surface (it's a token strategy reference); the v2-equivalent in current code is the heart-icon + coral-soft pill — match the visual intent of D4 Postcard Mythic via existing tokens.
   - **Body text:** the BE-rendered `body` field is `"<senderNickname>이 응원을 보냈어요"`. FE renders `body` verbatim; no client-side concatenation of payload fields (preserves brand-voice review at the BE-author seam).
   - [Epics 598-600; UX lines 1115-1135 D4 Postcard Mythic; UX lines 1165-1166 surface assignment; existing `<SystemMessage>` extension precedent]

9. **AC9 — FE API client + `useSendKudos` mutation hook (project-context FE rules).**
   - **Given** the FE needs to call the new endpoint and update caches,
   - **When** the Story 3.2 Friend Gift Modal (downstream) renders the "응원만 보내기" CTA,
   - **Then** the typed client + mutation exist:
     - **NEW** `FE/src/api/kudos.ts`:
       ```ts
       import { apiRequest, type ApiEnvelope } from "./client";
       export interface KudosDto {
         readonly kudosId: number;
         readonly roomId: number;
         readonly senderUserId: number;
         readonly targetUserId: number;
         readonly message: string;
         readonly occurredAt: string;
       }
       export interface SendKudosRequest {
         readonly targetUserId: number;
         readonly message?: string;
       }
       export async function postKudos(roomId: number, body: SendKudosRequest): Promise<KudosDto> {
         const envelope = await apiRequest<ApiEnvelope<KudosDto>>(
           `/rooms/${roomId}/kudos`,
           { method: "POST", body: JSON.stringify(body) },
         );
         return envelope.data;
       }
       ```
       Direct `fetch` is forbidden (project-context FE rule); all calls go through `apiRequest<T>` for 401-refresh + `ApiError` mapping.
     - **NEW** `FE/src/lib/query/hooks/kudos.ts`:
       ```ts
       export function useSendKudos(roomId: number) {
         const qc = useQueryClient();
         return useMutation<KudosDto, ApiError, SendKudosRequest>({
           mutationFn: (body) => postKudos(roomId, body),
           onSuccess: () => {
             // Invalidate room messages so the KUDOS row appears immediately
             // (the WS frame from AC7 will also arrive, but invalidation
             // covers the "receiver was already in the room when the push
             // fired" race).
             qc.invalidateQueries({ queryKey: qk.roomMessages(roomId) });
             qc.invalidateQueries({ queryKey: qk.roomLastMessage(roomId) });
           },
           // No onError cache mutation — the Story 3.2 Friend Gift Modal
           // surfaces the error via its own toast (`KUDOS_ALREADY_SENT_TODAY`
           // → "오늘은 이미 응원을 보냈어요" / `KUDOS_TARGET_NOT_ELIGIBLE` →
           // "이 친구는 지금 응원 대상이 아니에요" / `NOT_FRIENDS` →
           // "친구가 된 멤버에게만 보낼 수 있어요"). Story 3.5 does NOT
           // ship UI — it ships the hook contract Story 3.2 will consume.
         });
       }
       ```
     - **UPDATE** `FE/src/api/chat.ts` line 4-10: append `"KUDOS"` to the `ChatMessageKind` union. **Do NOT remove** any existing kind. Order: `KUDOS` last for human-readability symmetry with the Java enum.
     - **UPDATE** `FE/src/lib/notifications.ts` `routeInvalidation` switch (lines 55-92): add a new case `case "KUDOS_RECEIVED": qc.invalidateQueries({ predicate: (q) => Array.isArray(q.queryKey) && q.queryKey[0] === "rooms" && q.queryKey[2] === "messages" }); return;` — same predicate the `MILESTONE` case at lines 66-73 uses, so the receiver sees the KUDOS row immediately when the push lands.
     - **Reuse `qk.roomMessages(roomId)` + `qk.roomLastMessage(roomId)`** — no new query key required. Story 3.5 does NOT add `qk.kudos` (single-purpose key would only be consumed by Story 3.2's Friend Gift Modal, but it would invalidate the chat list anyway). Apply the project-context FE rule: never `queryClient.clear()`; use `invalidateQueries`.
   - [Project-context FE rules — `apiRequest` only, no `clear()`, `qk.*` keys; existing `useChatMessages` hook precedent at `FE/src/lib/query/hooks/chat.ts:28-46`]

10. **AC10 — Donor-side optimistic toast + return contract (epics 487 "donor gets toast 응원이 도착했어요 🌿").**
    - **Given** the Story 3.2 Friend Gift Modal (downstream) calls `useSendKudos(roomId).mutate({ targetUserId, message })`,
    - **When** the mutation succeeds (`onSuccess`),
    - **Then** the consumer (Story 3.2's modal) shows a donor toast `"응원이 도착했어요 🌿"`. **Story 3.5 does NOT ship the toast call** — the toast is a Story 3.2 concern; AC10 here documents the **contract** that the `KudosDto` is returned to the caller so the toast can fire deterministically (no race with the broker frame).
    - **Story 3.5 ships only:** the mutation hook + the `KudosDto` type. The contract test for the donor side is `lib/query/hooks/__tests__/kudos.test.tsx` (AC11.FE-2) which asserts (a) success returns the `KudosDto` (b) on success the query cache is invalidated (c) on `KUDOS_ALREADY_SENT_TODAY` the caller receives an `ApiError` with `code: "KUDOS_ALREADY_SENT_TODAY"` (d) no toast call is fired by the hook itself.
    - **Why split the toast off:** the same `useSendKudos` mutation is also consumed by Story 3.3's Wallet badge `WALLET_INITIATED` kudos path, and possibly future surfaces. Toasting from the hook would couple the visual response to the data layer.
    - [Epics 487 donor-toast contract; project-context "hooks are pure, components own UI side-effects"]

11. **AC11 — Test coverage (TDD, 80%+ on new code).**

    **BE — JUnit 5 + AssertJ + Mockito + Testcontainers (project-context "H2 forbidden — partial unique indexes + jsonb require Postgres"):**
    - `BE/src/test/java/com/yeosal/api/room/chat/KudosServiceTest.java` — Mockito unit (`@ExtendWith(MockitoExtension.class)`, mock all repos + `NotificationService` + `ApplicationEventPublisher`). Cover:
      - **happy path** — `sendKudos(me=alice, roomId=R, targetId=bob, message=null)` with bob `RED` + friends → `chat_messages` row inserted, `KudosSentEvent` published with `messagePreview = body`, returns `KudosDto`.
      - **happy path with message** — message = `"우리 같이 가자"` → row inserted, payload contains `message` field, returns DTO.
      - **same-day duplicate** — `insertKudosIfAbsent` returns 0 (simulated via repo mock) → `KudosAlreadySentTodayException` thrown.
      - **`DataIntegrityViolationException` with the kudos constraint** — repo throws DIVE wrapping a constraint name match → translated to `KudosAlreadySentTodayException` (defence-in-depth path).
      - **`DataIntegrityViolationException` with a DIFFERENT constraint** — repo throws DIVE with FK constraint name → rethrows DIVE (does NOT swallow as kudos conflict; preserves the existing `ApiExceptionHandler.dataIntegrity` 500 path for unrelated violations).
      - **sender is spectator** → `SpectatorWriteForbiddenException`.
      - **target is `ACTIVE`** → `KudosTargetNotEligibleException`.
      - **target is `YELLOW`** → `KudosTargetNotEligibleException` (Yellow is not yet eliminated; epics line 587 — `RED`/`SPECTATOR` only).
      - **non-friend** (no friendship row) → `NotFriendsException`.
      - **friendship status `PENDING`** → `NotFriendsException` (must be `ACCEPTED`).
      - **friendship status `BLOCKED`** → `NotFriendsException`.
      - **sender == target** → `BadRequestException` (self-kudos forbidden).
      - **non-member sender** → `ForbiddenException` (`existsByRoomIdAndUserId(roomId, me) == false`).
      - **non-member target** → `NotFoundException`.
      - **message exactly 60 chars** → succeeds (boundary).
      - **message 61 chars** → `BadRequestException` (server-side guard, in addition to `@Valid @Size` on the DTO).
      - **target's `survival_state` row missing** → treats as `KudosTargetNotEligibleException` (defensive — matches the precedent in `ChatService.requireNotSpectator` at lines 293-302 for missing rows, but inverted: kudos REQUIRES eligibility evidence to send).
    - `BE/src/test/java/com/yeosal/api/room/chat/KudosControllerTest.java` — `@WebMvcTest`:
      - `POST /api/v1/rooms/42/kudos` with `{"targetUserId":7}` (no message) → `201 CREATED` + envelope shape `{"data": {"kudosId":..., "roomId":42, "senderUserId":..., "targetUserId":7, "message":"", "occurredAt":"..."}}`.
      - With `message = ""` (empty string) → `201 CREATED` + envelope `message: ""`.
      - With `message > 60 chars` → `400 VALIDATION` (Jackson + `@Valid` chain).
      - With missing `targetUserId` → `400 VALIDATION`.
      - With non-numeric `targetUserId` → `400 VALIDATION` (`HttpMessageNotReadableException` mapping at `ApiExceptionHandler.java:90-98`).
      - Unauthenticated → `401 UNAUTHORIZED`.
      - Service throws `KudosAlreadySentTodayException` → `409 CONFLICT` + code `KUDOS_ALREADY_SENT_TODAY`.
      - Service throws `KudosTargetNotEligibleException` → `400 BAD_REQUEST` + code `KUDOS_TARGET_NOT_ELIGIBLE`.
      - Service throws `NotFriendsException` → `403 FORBIDDEN` + code `NOT_FRIENDS`.
      - Service throws `SpectatorWriteForbiddenException` → `403 FORBIDDEN` + code `SPECTATOR_WRITE_FORBIDDEN`.
    - `BE/src/test/java/com/yeosal/api/room/chat/KudosMigrationIT.java` — `@SpringBootTest` + `@Testcontainers` PostgreSQL (`postgres:16`, mirror `RevivalConcurrencyIT` setup):
      - Boot the app → V12 has applied → `pg_indexes` query returns a row for `ux_kudos_one_per_day` whose `indexdef` contains `at time zone 'Asia/Seoul'` (proves the IMMUTABLE-expression contract).
      - Insert a row with `kind='KUDOS'` directly (via `EntityManager`) → succeeds (proves CHECK constraint was widened).
      - Insert a duplicate same-day kudos for the same `(sender, target)` → throws `DataIntegrityViolationException` whose root constraint name equals `ux_kudos_one_per_day`.
      - Insert a kudos for the same `(sender, target)` on a different KST day (manipulate `created_at` via the entity) → succeeds (proves the index keys on KST date, not raw timestamp).
      - Insert a kudos for the SAME calendar day but a DIFFERENT pair `(sender, otherTarget)` → succeeds (proves the index keys on the triple, not just the date).
    - `BE/src/test/java/com/yeosal/api/room/chat/KudosConcurrencyIT.java` — `@SpringBootTest` + `@Testcontainers` PostgreSQL:
      - Seed: alice + bob, friends (ACCEPTED), bob `RED` in room R, alice ACTIVE.
      - Spawn **two parallel `CompletableFuture`s** invoking `kudosService.sendKudos(R, alice.id, bob.id, "테스트")` simultaneously (use `CountDownLatch` like `RevivalConcurrencyIT` at `BE/src/test/java/com/yeosal/api/revival/RevivalConcurrencyIT.java:62-130`).
      - **Exactly one** future succeeds; the other throws `KudosAlreadySentTodayException` (or `DataIntegrityViolationException` translated by the service-layer catch).
      - Post-test assertions: `chat_messages WHERE kind='KUDOS' AND sender_user_id=alice` returns exactly 1 row; payload contains `targetUserId="<bob.id>"`.
    - **Coverage target:** 80%+ on `KudosService.java`, `KudosController.java`, `KudosSentEvent.java`, `KudosRealtimeListener.java`, the three new exceptions. Trivial records / DTO constructors are excluded by JaCoCo defaults.

    **FE — Jest + `@testing-library/react-native`:**
    - **FE-1** `FE/src/components/chat/__tests__/SystemMessage.test.tsx` — NEW test file (the chat folder has only `SpectatorReadOnlyBanner.test.tsx` today). Cover:
      - Render with `message.kind === "KUDOS"` → heart icon present, `KIND_LABEL[KUDOS]` accessibility prefix is "응원", pill background uses `palette.coralSoft`, text uses `palette.inkDeep` + `emphasized=true`.
      - Render with `message.kind === "SYSTEM"` → unchanged behaviour (regression guard — adding KUDOS must not change SYSTEM's visual).
      - Brand-voice copy assertion — the rendered body MUST NOT contain any of the 8 AVOID lexicon words (run the body through the local `AVOID_LEXICON` list).
    - **FE-2** `FE/src/lib/query/hooks/__tests__/kudos.test.tsx` — NEW. Cover:
      - `useSendKudos(42).mutate({ targetUserId: 7 })` success → returns a `KudosDto`, invalidates `qk.roomMessages(42)` and `qk.roomLastMessage(42)`.
      - Mutation fails with `ApiError.code === "KUDOS_ALREADY_SENT_TODAY"` → caller receives the error; no cache invalidation fires (the duplicate didn't change room state).
      - Mutation fails with `ApiError.code === "KUDOS_TARGET_NOT_ELIGIBLE"` → caller receives the error; no cache invalidation fires.
      - Mutation fails with network error → caller receives the error; no cache invalidation fires.
      - The hook does NOT call `toast(...)` — toast is a consumer concern (AC10 contract test).
    - **FE-3** `FE/src/lib/__tests__/notifications.test.ts` — UPDATE existing test if present (or create) to add the `KUDOS_RECEIVED` branch. Mock `QueryClient`, call `routeInvalidation(qc, "KUDOS_RECEIVED")`, assert it invalidated only the `rooms/*/messages` predicate.
    - **Coverage target:** 80%+ on `FE/src/api/kudos.ts`, `FE/src/lib/query/hooks/kudos.ts`, the new `SystemMessage` KUDOS branch.

    **Brand-voice lint:** `tools/node_modules/.bin/tsx tools/brand-voice-lint.ts` MUST be clean of HARD violations (Rule 1, NFR-9.6.1 — no new `semantic.survival.*.color` references). Rule 2 (AVOID lexicon) warnings on new files MUST be zero — verify with the same `banned[]` pattern Story 3.1's `SelfReviveCTA.test.tsx` uses for documentation-banned-word assertion contexts (those are acceptable; bare lexicon in production strings is NOT).

12. **AC12 — Out-of-scope (explicit).** Story 3.5 ships the kudos endpoint + DB migration + receiver-render variant + FE client + hook. It does **NOT** ship:
    - The Friend Gift Modal UI itself ("응원만 보내기" CTA on a postcard modal surface) — Story 3.2.
    - The "lifetime-1 first-kudos" sender-side moment — kudos is not a lifetime-1 trigger; only FRIEND_GIFT sends fire M3.5 per UX line 261.
    - Per-room kudos counter / leaderboard — explicitly rejected per UX line 368 anti-pattern ("친구별 기여도 leaderboard" listed under banned).
    - Kudos history "받은 응원 모아보기" surface — would be Story 3.4 (Wallet UI surface), if at all; v1 explicitly does not ship a kudos archive.
    - Push to the donor — only the receiver gets a push (epics line 583). The donor sees the result in the chat list when they next open the room + the optimistic toast on send (AC10 contract).
    - Multi-room "send kudos to friend across rooms" — kudos is room-scoped (the endpoint takes `roomId`); the friend MUST be a same-room member.
    - Brand-voice lint HARD-gate on the kudos `message` body — Architecture §4.15 keeps AVOID-lexicon as WARN-level (human authoritative).
    - WebSocket frame for the donor side ("kudos sent" confirmation) — donor uses the HTTP 201 response, not a WS round-trip.
    - Quiet-hours override / "send despite quiet hours" toggle — existing `QuietHoursPolicy` is authoritative.
    - Story 2.3 record-visibility-prefs interaction — kudos is sender-initiated, not eliminated-user-controlled, so visibility-prefs do not gate it. (Distinct from daily-entry redaction, which Story 2.3 handles.)

    If a file under `BE/src/main/java/com/yeosal/api/{auth, profile, daily, survival/, revival/, room/chat/ChatService.java, room/chat/ChatController.java}` is modified beyond what's listed in AC2/AC9 + the controller-wiring touchpoints in AC5, scope has drifted — stop and re-scope. The existing `ChatService.publishSystem(...)` is NOT extended (kudos is a separate write path per AC4).

## Tasks / Subtasks

### Backend (BE/) — V12 migration + kudos module + enum + push wiring

- [x] **Task BE-1 — Flyway V12 migration (AC1)**
  - [x] BE-1.1 — NEW `BE/src/main/resources/db/migration/V12__chat_kudos.sql`. Body per AC1 SQL block. `drop constraint if exists ...` + `add constraint ...` for the widened CHECK; `create unique index if not exists ux_kudos_one_per_day on chat_messages (sender_user_id, ((payload->>'targetUserId')), (((created_at at time zone 'Asia/Seoul')::date))) where kind = 'KUDOS';`. Header comment cites Story 3.5 + PRD FR-8.3.9 + UX U3 disposition + the IMMUTABLE expression rationale (PR #57 reference).

- [x] **Task BE-2 — `ChatMessageKind` enum + JS-Java mirror (AC2)**
  - [x] BE-2.1 — UPDATE `BE/src/main/java/com/yeosal/api/room/chat/ChatMessageKind.java`. Append `KUDOS` enum value at the end. Update the class-level javadoc with a one-line note: "Story 3.5 — KUDOS rows have non-null `sender_user_id` and a `payload` of shape `{senderUserId, targetUserId, message}`."
  - [x] BE-2.2 — Verify the existing `@Enumerated(EnumType.STRING)` mapping at `ChatMessage.java:42-44` continues to round-trip the new value (covered by `KudosMigrationIT` AC11.BE-2 first assertion).

- [x] **Task BE-3 — Domain exceptions + `ApiExceptionHandler` mappings (AC5)**
  - [x] BE-3.1 — NEW `BE/src/main/java/com/yeosal/api/room/chat/KudosAlreadySentTodayException.java` extends `RuntimeException`, `public static final String CODE = "KUDOS_ALREADY_SENT_TODAY"`, single-message constructor.
  - [x] BE-3.2 — NEW `BE/src/main/java/com/yeosal/api/room/chat/KudosTargetNotEligibleException.java` extends `RuntimeException`, `CODE = "KUDOS_TARGET_NOT_ELIGIBLE"`.
  - [x] BE-3.3 — NEW `BE/src/main/java/com/yeosal/api/room/chat/NotFriendsException.java` extends `ForbiddenException`, `CODE = "NOT_FRIENDS"`. Reuses parent's 403 fallthrough; the explicit handler returns the same 403 with the precise wire code.
  - [x] BE-3.4 — UPDATE `BE/src/main/java/com/yeosal/api/common/ApiExceptionHandler.java`. Add three `@ExceptionHandler` methods after the Story 3.1 mappings (around line 180): `kudosAlreadySentToday → 409 CONFLICT + KUDOS_ALREADY_SENT_TODAY`; `kudosTargetNotEligible → 400 BAD_REQUEST + KUDOS_TARGET_NOT_ELIGIBLE`; `notFriends → 403 FORBIDDEN + NOT_FRIENDS`. Use `ApiErrorResponse.of(<Type>.CODE, exception.getMessage())`. Preserve all existing handlers.

- [x] **Task BE-4 — `KudosRequest` + `KudosDto` + `KudosSentEvent` records (AC3, AC7)**
  - [x] BE-4.1 — NEW `BE/src/main/java/com/yeosal/api/room/chat/KudosRequest.java`. Record `(@NotNull Long targetUserId, @Size(max = 60) String message)`. The `message` field MAY be null or empty (Story 3.5 makes it optional per epics 578 — `message?: string`).
  - [x] BE-4.2 — NEW `BE/src/main/java/com/yeosal/api/room/chat/KudosDto.java`. Record `(long kudosId, long roomId, long senderUserId, long targetUserId, String message, java.time.Instant occurredAt)`. JSON serialisation is automatic via Spring's default ObjectMapper.
  - [x] BE-4.3 — NEW `BE/src/main/java/com/yeosal/api/room/chat/KudosSentEvent.java`. Record `(long roomId, long senderUserId, long targetUserId, String messagePreview, java.time.Instant occurredAt)`. Distinct from `KudosDto` because the event may carry orchestration fields in future stories.
  - [x] BE-4.4 — NEW `BE/src/main/java/com/yeosal/api/room/chat/KudosSentPayload.java`. Record `(long senderId, long targetId, String messagePreview, java.time.Instant occurredAt)`. Wire shape for `/topic/rooms.{id}.kudos`.

- [x] **Task BE-5 — `KudosService` with transactional flow (AC3, AC4, AC5, AC6, AC7)**
  - [x] BE-5.1 — NEW `BE/src/main/java/com/yeosal/api/room/chat/KudosService.java`. Constructor injection of `ChatMessageRepository`, `RoomMemberRepository`, `SurvivalStateRepository`, `FriendshipRepository`, `UserRepository`, `ApplicationEventPublisher`, `com.fasterxml.jackson.databind.ObjectMapper`, `java.time.Clock`. NO field injection.
  - [x] BE-5.2 — Public method `@Transactional public KudosDto sendKudos(long roomId, User me, long targetUserId, String message)`. Implement the AC3 sequence in the exact order documented. The advisory lock and `SELECT ... FOR UPDATE` are **NOT used** here — kudos uses only the partial unique index for dedupe (no point pool / no atomic balance check — kudos is 0-cost; the single race is the same-day duplicate, which the partial unique index resolves).
  - [x] BE-5.3 — Private helper `buildPayloadJson(long senderId, long targetId, String message)` returns the literal JSON string `{"senderUserId":"<id>","targetUserId":"<id>","message":"<text>"}` via `ObjectMapper.writeValueAsString(Map.of(...))`. Both ids as STRINGS — matches V8/V9 + Story 3.1 payload convention. `message` is the trimmed input or empty string (never null in the persisted JSON — keeps the partial unique index expression stable; `payload->>'targetUserId'` will always resolve to a non-null text value).
  - [x] BE-5.4 — Private helper `renderBody(User sender)` returns the locked Korean string `sender.getNickname() + "이 응원을 보냈어요"`. Brand-voice contract: zero AVOID-lexicon words.
  - [x] BE-5.5 — Catch `DataIntegrityViolationException` at the INSERT call site: inspect `NestedExceptionUtils.getMostSpecificCause(ex)` for a root cause whose message contains the constraint name `ux_kudos_one_per_day`; rethrow as `KudosAlreadySentTodayException`. Otherwise rethrow the original DIVE (lets `ApiExceptionHandler.dataIntegrity` handle unrelated violations as 500). Mirror the Story 3.1 pattern in `RevivalService` (string-match form chosen over `PSQLException.getServerErrorMessage().getConstraint()` to avoid coupling the service to the postgres JDBC driver type — same approach Story 3.1's `isRevivalDedupConflict` takes).

- [x] **Task BE-6 — `ChatMessageRepository.insertKudosIfAbsent` native query (AC3)**
  - [x] BE-6.1 — UPDATE `BE/src/main/java/com/yeosal/api/room/chat/ChatMessageRepository.java`. Append a new `@Modifying @Query(nativeQuery = true)` method `insertKudosIfAbsent(@Param("roomId") Long roomId, @Param("senderUserId") Long senderUserId, @Param("body") String body, @Param("payload") String payload)` returning `int` (rows affected — 0 or 1). SQL body per AC3 step 9. **Critically:** the `on conflict` predicate `where kind = 'KUDOS'` MUST be present and MUST match the index predicate exactly (else the conflict path is not invoked).
  - [x] BE-6.2 — Append a sibling read method `findKudosId(senderUserId, targetUserId, kstDate)` returning `Optional<Long>` (the just-inserted `chat_messages.id`). The service calls `LocalDate.ofInstant(occurredAt, "Asia/Seoul")` to compute the KST key and pass it through to this method — typed-stable with the V12 index expression. Two-query approach mirrors Story 3.1's `RevivalEventRepository.insertOnConflictDoNothing(...)` + `findByRoomIdAndUserIdAndEliminatedAt(...)` precedent.

- [x] **Task BE-7 — `KudosController` (AC3, AC11)**
  - [x] BE-7.1 — NEW `BE/src/main/java/com/yeosal/api/room/chat/KudosController.java`. `@RestController @RequestMapping("/api/v1/rooms")`. Single endpoint `@PostMapping("/{id}/kudos") @ResponseStatus(HttpStatus.CREATED)`. Method signature: `public ApiResponse<KudosDto> send(Authentication auth, @PathVariable long id, @Valid @RequestBody KudosRequest body)`. Constructor injects `KudosService` + `CurrentUser`. Body: `User me = currentUser.require(auth); return ApiResponse.of(kudosService.sendKudos(id, me, body.targetUserId(), body.message()));`.

- [x] **Task BE-8 — `NotificationKind.KUDOS_RECEIVED` + `NotificationService` switch (AC6)**
  - [x] BE-8.1 — UPDATE `BE/src/main/java/com/yeosal/api/notification/NotificationKind.java`. Appended `KUDOS_RECEIVED` enum value at the end with javadoc.
  - [x] BE-8.2 — UPDATE `BE/src/main/java/com/yeosal/api/notification/NotificationService.java` `isCronEnabled` switch. Added a branch `case KUDOS_RECEIVED -> pref.isEventHooksEnabled();`. No other change.

- [x] **Task BE-9 — `RealtimePublisher.publishKudos` + `JwtChannelInterceptor` regex (AC7)**
  - [x] BE-9.1 — UPDATE `BE/src/main/java/com/yeosal/api/realtime/RealtimePublisher.java`. Added `public void publishKudos(long roomId, KudosSentPayload payload)` that calls `sendTopic("/topic/rooms." + roomId + ".kudos", payload);`. Updated the class-level javadoc destination scheme list with the new bullet.
  - [x] BE-9.2 — UPDATE `BE/src/main/java/com/yeosal/api/realtime/JwtChannelInterceptor.java`. Changed the `ROOM_TOPIC` regex to `^/topic/rooms\\.(\\d+)\\.(chat|members|survival|points|kudos)$`. Updated the class-level javadoc bullet to mention `kudos`.

- [x] **Task BE-10 — `KudosRealtimeListener` (AC6, AC7)**
  - [x] BE-10.1 — NEW `BE/src/main/java/com/yeosal/api/room/chat/KudosRealtimeListener.java`. `@Component` with `@Transactional(propagation = REQUIRES_NEW)` + `@TransactionalEventListener(phase = AFTER_COMMIT)`. Loads sender + target via `UserRepository.findById`; calls `publisher.publishKudos(roomId, payload)`; calls `notificationService.sendEvent(target, NotificationKind.KUDOS_RECEIVED, key, "응원이 도착했어요 🌿", senderNickname + "이 응원을 보냈어요", Duration.ZERO)`. Each external call is wrapped in its own try/catch with warn-and-continue logging — mirrors `SurvivalStateRealtimeListener`. The `key` used is `Long.toString(event.occurredAt().toEpochMilli())` so a future retry hits the same dedup row.

- [x] **Task BE-11 — Tests (AC11)**
  - [x] BE-11.1 — `KudosServiceTest.java` (Mockito unit, 17 cases — happy null/message, dedup row count 0, DIVE constraint-match translation, DIVE non-match rethrow, sender SPECTATOR, target ACTIVE/YELLOW/missing state, no friendship, friendship PENDING/BLOCKED, self-kudos, non-member sender/target, message 60 boundary, message 61 reject).
  - [x] BE-11.2 — `KudosControllerTest.java` (`@WebMvcTest`, 10 cases — 201 null-message, 201 empty-message, 400 message-too-long, 400 missing target, 400 non-numeric target, unauthenticated 4xx, 409 KUDOS_ALREADY_SENT_TODAY, 400 KUDOS_TARGET_NOT_ELIGIBLE, 403 NOT_FRIENDS, 403 SPECTATOR_WRITE_FORBIDDEN).
  - [x] BE-11.3 — `KudosMigrationIT.java` (`@SpringBootTest` + Testcontainers PostgreSQL, 5 cases — IMMUTABLE Asia/Seoul indexdef, CHECK widened, same-day duplicate rejected with constraint name, different KST day succeeds, different pair same day succeeds).
  - [x] BE-11.4 — `KudosConcurrencyIT.java` (CountDownLatch + 2 parallel `CompletableFuture`s — exactly one row lands; loser surfaces `KudosAlreadySentTodayException` or `DataIntegrityViolationException`).

### Frontend (FE/) — API client + hook + chat-list KUDOS variant + notification routing

- [x] **Task FE-1 — API client + types (AC9)**
  - [x] FE-1.1 — NEW `FE/src/api/kudos.ts`. Exports `KudosDto` interface (readonly fields per AC9), `SendKudosRequest` interface, `postKudos(roomId, body)` via `apiRequest<ApiEnvelope<KudosDto>>`.
  - [x] FE-1.2 — UPDATE `FE/src/api/chat.ts`. Appended `"KUDOS"` to the `ChatMessageKind` union (KUDOS last, matches Java enum order). Class-level docstring updated to call out KUDOS as the only non-USER kind with a non-null `senderUserId`.

- [x] **Task FE-2 — `useSendKudos` mutation hook (AC9, AC10)**
  - [x] FE-2.1 — NEW `FE/src/lib/query/hooks/kudos.ts`. Exports `useSendKudos(roomId)`. On success invalidates `qk.roomMessages(roomId)` and `qk.roomLastMessage(roomId)`. On error: no cache mutation. The hook does NOT call `toast()` — toast is a consumer concern.
  - [x] FE-2.2 — No update to `FE/src/lib/query/keys.ts` required.

- [x] **Task FE-3 — `<SystemMessage>` KUDOS variant (AC8)**
  - [x] FE-3.1 — UPDATE `FE/src/components/chat/SystemMessage.tsx`. Added the `case "KUDOS":` block (heart icon, `palette.coralDeep` icon, `palette.coralSoft` pill, `palette.inkDeep` text, `emphasized: true`). Added `KUDOS: "응원"` to `KIND_LABEL`. Preserved existing styles + `accessibilityLabel`s + the default fallback to SYSTEM.
  - [x] FE-3.bonus — UPDATE `FE/app/(tabs)/chat.tsx`'s `KIND_PREFIX` (`Record<ChatMessageKind, string>`) to include `KUDOS: "🌿 "` — the `Record<ChatMessageKind, ...>` exhaustiveness check required this and the 🌿 prefix matches the kudos push title brand convention. Discovered via `tsc --noEmit`.

- [x] **Task FE-4 — Notification routing (AC9)**
  - [x] FE-4.1 — UPDATE `FE/src/lib/notifications.ts` `routeInvalidation` switch. Added `case "KUDOS_RECEIVED":` between MILESTONE and GOAL_NUDGE; mirrors the MILESTONE predicate (invalidates `["rooms", *, "messages"]`).

- [x] **Task FE-5 — Tests (AC11)**
  - [x] FE-5.1 — NEW `FE/src/components/chat/__tests__/SystemMessage.test.tsx` — 4 cases: KUDOS renders body + 응원 a11y prefix, KUDOS uses coralSoft pill, SYSTEM remains muted (regression guard), KUDOS body passes brand-voice Rule 2 (no AVOID lexicon).
  - [x] FE-5.2 — NEW `FE/src/lib/query/hooks/__tests__/kudos.test.tsx` — 5 cases: success → KudosDto + invalidate roomMessages + roomLastMessage; KUDOS_ALREADY_SENT_TODAY → ApiError no invalidation; KUDOS_TARGET_NOT_ELIGIBLE → ApiError no invalidation; network error → no invalidation; optional `message` field forwarded.
  - [x] FE-5.3 — UPDATE `FE/src/lib/__tests__/notifications.test.ts` — added `KUDOS_RECEIVED` test mirroring the MILESTONE predicate assertion.

### Scripts / verification / sprint-status

- [x] **Task X-1 — Verification gate**
  - [~] X-1.1 — `cd BE && ./gradlew test` — DEFERRED to CI (local JDK 21 unavailable; matches Story 3.1 precedent).
  - [~] X-1.2 — `cd BE && ./gradlew check` — DEFERRED to CI (same reason).
  - [x] X-1.3 — `cd FE && npm test` — 42 suites / 266 tests PASS (includes the 3 new Story 3.5 test files: SystemMessage / useSendKudos / notifications KUDOS_RECEIVED).
  - [x] X-1.4 — `cd FE && npx tsc --noEmit` — clean for Story 3.5 sources (pre-existing `FriendsTodayPager.tsx` errors are on main, not from this story). `npm run lint` — pre-existing failures only (4 errors all in files this story did not touch: `app/rooms/[id]/chat.tsx`, `survival/__tests__/SurvivalChip*.test.tsx`, `realtime/client.ts`).
  - [x] X-1.5 — `tools/node_modules/.bin/tsx tools/brand-voice-lint.ts` — **0 HARD violations**. 152 warnings, all in pre-existing files; zero warnings on Story 3.5 sources (KudosService strings, push title/body, SystemMessage KUDOS label all clean).
  - [~] X-1.6 — `bash scripts/verify.sh` — pending CI run (BE gates required for full verify).

- [x] **Task X-2 — Sprint-status flips**
  - [x] X-2.1 — Flipped `3-5-...` `ready-for-dev → in-progress` in `_bmad-output/implementation-artifacts/sprint-status.yaml` at start.
  - [x] X-2.2 — Flipped `in-progress → review` after FE green + brand-voice clean. Next: run `/code-review`.
  - [ ] X-2.3 — After review approval + PR merge to main: flip `review → done`.

- [x] **Task X-3 — Branch hygiene + post-merge action**
  - [x] X-3.1 — Cut branch `feat/story-3-5-kudos-message-endpoint-and-chat-kind-extension` from latest `main` (e15d375). Targets `main` directly.
  - [ ] X-3.2 — PR description (drafted at submit time) MUST include a "Post-merge user action" section: container restart applies V12 automatically (Flyway is wired into Spring Boot startup); ops verifies `flyway_schema_history` last entry is `version=12` after deploy.

### Out-of-scope explicit list

- [ ] **Task X-OOS — Documented deferrals (call out in PR description):**
  - Friend Gift Modal D4 Postcard surface that consumes the kudos hook — Story 3.2.
  - Wallet "받은 응원" history surface — not in v1.
  - Kudos archive / leaderboard — explicitly banned per UX line 368 anti-pattern.
  - WebSocket frame for the donor side — donor reads HTTP 201 + optimistic toast.
  - HARD CI gate on AVOID-lexicon for kudos `message` body — Architecture §4.15 keeps it WARN-only.
  - Multi-room kudos — kudos is room-scoped per AC3 step 4.

### Review Findings

- [ ] [Review][Patch] Post-insert kudos row recovery can 500 when app clock and DB `created_at` fall on different KST dates [`BE/src/main/java/com/yeosal/api/room/chat/KudosService.java:175`] — `insertKudosIfAbsent` writes `created_at` using the database default, but `sendKudos` then computes `kstToday` from the injected application `Clock` and searches by that date. If the app clock is fixed in tests, skewed from DB time, or the request crosses the KST date boundary between the DB insert and `clock.instant()`, the insert can commit and then `findKudosId(...)` returns empty, surfacing a 500 after the row already exists. AC3 expects the response `kudosId` / `occurredAt` to describe the inserted `chat_messages` row. Return `id` and `created_at` from the INSERT path, or otherwise read the inserted row by a key that cannot disagree with DB `created_at`.
- [ ] [Review][Patch] Receiver push dedup/audit key uses event timestamp instead of `chatMessageId` [`BE/src/main/java/com/yeosal/api/room/chat/KudosRealtimeListener.java:96`] — AC6 explicitly requires `key = "<chatMessageId>"`, but `KudosSentEvent` does not carry the inserted `kudosId`, so the listener sends `Long.toString(event.occurredAt().toEpochMilli())`. This makes the notification log key describe listener/application time rather than the committed row and breaks the documented audit/dedup contract. Carry `kudosId` in `KudosSentEvent` and pass it to `sendEvent`.
- [ ] [Review][Patch] `/topic/rooms.{id}.kudos` realtime frame is not consumed by the FE chat screen [`FE/app/rooms/[id]/chat.tsx:48`] — the BE emits `KudosSentPayload` on the new `kudos` topic, but the chat screen only calls `useChatRealtime(roomId)`, which subscribes to `/topic/rooms.{id}.chat`. No hook subscribes to `/topic/rooms.{id}.kudos`, so the AC7 realtime path does not make a KUDOS row appear for an already-open receiver chat; it falls back to push invalidation or the 30s polling interval. Add a FE kudos realtime subscription that invalidates `qk.roomMessages(roomId)` and `qk.roomLastMessage(roomId)`, or publish a full `ChatMessageDto` through the existing chat topic.

## Dev Notes

### Architecture patterns (load-bearing — must follow)

- **Partial unique index is the single dedupe authority.** Kudos has no advisory lock (0-cost; the only race is same-day duplicate, which the unique index handles atomically inside the INSERT). The service-layer `DataIntegrityViolationException → KudosAlreadySentTodayException` translation is the defence-in-depth path that converts the DB exception to the typed wire code. Architecture §5.1 "idempotency via partial unique indexes per V8/V9 reference".
- **IMMUTABLE expression contract.** The partial unique index expression `((created_at at time zone 'Asia/Seoul')::date)` is IMMUTABLE because the timezone is a literal. The naive `((created_at)::date)` (on a `timestamptz`) is STABLE and Postgres rejects it inside a partial unique index expression with SQLSTATE 42P17 (the V11 trap PR #57 fixed). **The PRD's `date_part('day', ...)` formula is also wrong** — it returns only day-of-month integer, allowing month-spanning collisions. The story spec at AC1 supersedes the PRD formula.
- **Single `@RestControllerAdvice`** — `ApiExceptionHandler` only. This story ADDS three new `@ExceptionHandler` methods to the existing class. Do NOT introduce a second advice.
- **Constructor injection only** (project-context Java rule). No `@Autowired` fields.
- **`open-in-view: false`** — every read of `User.nickname`, `Friendship.status`, etc. must happen inside `@Transactional`. `KudosService.sendKudos` is `@Transactional`; the controller is not. The `KudosRealtimeListener` runs in `REQUIRES_NEW`, so its `users.findById(...)` reads are inside that new transaction.
- **STOMP destination convention is dot-separated** (`/topic/rooms.{id}.kudos`, `/user/{id}/queue/...`). The architecture §4.14 prose uses slash separators — that's drift; the runtime contract is dots. The Story 3.1 review patch already corrected this in `JwtChannelInterceptor.ROOM_TOPIC`; Story 3.5 extends the regex with `|kudos`.
- **System messages have null `sender_user_id`; KUDOS does NOT.** `ChatService.publishSystem` enforces `kind != USER` and writes null sender. Kudos rows have a non-null `sender_user_id` (the donor); KUDOS is the only non-USER kind with a real sender. Document this in the `ChatMessage.java` class-level javadoc (already mentioned at line 18-22 — append a note about KUDOS).
- **`payload->>'targetUserId'` is the canonical addressing pattern** for any non-USER row that needs a per-target dedupe. V8/V9 milestone used `payload->>'userId'`; V12 kudos uses `payload->>'targetUserId'`. Both follow the text-cast convention (store ids as JSON strings so a future numeric writer doesn't break the index — the `text->>` operator returns `text`, period).
- **Brand-voice copy is the contract.** All new Korean strings (push title/body, error messages, the rendered chat-row body) pass `tools/brand-voice-lint.ts` Rule 2. The user-typed `message` field is **NOT lint-gated** at storage time (Architecture §4.15 — human-authoritative WARN); but the test suite asserts Story 3.5's own static strings are clean.
- **Quiet hours apply** — `NotificationService.sendEvent` already calls `quietHours.isQuiet(...)` at line 102. Kudos at 23:30 KST when the receiver's `quiet_start_hour=22` → push suppressed; the chat row is still inserted, so morning open surfaces it.
- **Immutable updates on FE** — TanStack Query cache updates via `setQueryData(key, (prev) => ...)`. No mutation. The kudos hook only invalidates (not setQueryData) — simpler and matches the "kudos arrives via WS or next refetch" UX.
- **No second STOMP client** — the FE `RealtimeProvider` is the sole client. The Story 3.2 modal (downstream) will use `useRealtimeSubscription("/topic/rooms.{id}.kudos", ...)` to surface a future "your kudos was sent" confirmation if it wants — but Story 3.5 does NOT add an FE subscription (the chat list re-renders via cache invalidation; the WS frame is for downstream consumers).

### Reuse vs. new (read each UPDATE file fully before editing)

**NEW files (BE):**

- `BE/src/main/resources/db/migration/V12__chat_kudos.sql`
- `BE/src/main/java/com/yeosal/api/room/chat/KudosController.java`
- `BE/src/main/java/com/yeosal/api/room/chat/KudosService.java`
- `BE/src/main/java/com/yeosal/api/room/chat/KudosRequest.java`
- `BE/src/main/java/com/yeosal/api/room/chat/KudosDto.java`
- `BE/src/main/java/com/yeosal/api/room/chat/KudosSentEvent.java`
- `BE/src/main/java/com/yeosal/api/room/chat/KudosSentPayload.java`
- `BE/src/main/java/com/yeosal/api/room/chat/KudosRealtimeListener.java`
- `BE/src/main/java/com/yeosal/api/room/chat/KudosAlreadySentTodayException.java`
- `BE/src/main/java/com/yeosal/api/room/chat/KudosTargetNotEligibleException.java`
- `BE/src/main/java/com/yeosal/api/room/chat/NotFriendsException.java`
- `BE/src/test/java/com/yeosal/api/room/chat/KudosServiceTest.java`
- `BE/src/test/java/com/yeosal/api/room/chat/KudosControllerTest.java`
- `BE/src/test/java/com/yeosal/api/room/chat/KudosMigrationIT.java`
- `BE/src/test/java/com/yeosal/api/room/chat/KudosConcurrencyIT.java`

**NEW files (FE):**

- `FE/src/api/kudos.ts`
- `FE/src/lib/query/hooks/kudos.ts`
- `FE/src/components/chat/__tests__/SystemMessage.test.tsx`
- `FE/src/lib/query/hooks/__tests__/kudos.test.tsx`
- `FE/src/lib/__tests__/notifications.test.ts` (if absent; otherwise update the existing test)

**UPDATE files (read FULLY before editing — these are the load-bearing seams):**

- `BE/src/main/java/com/yeosal/api/room/chat/ChatMessageKind.java` (append `KUDOS` enum value at the end; update class-level javadoc).
- `BE/src/main/java/com/yeosal/api/room/chat/ChatMessageRepository.java` (append `insertKudosIfAbsent` native query; preserve all existing methods, javadoc style).
- `BE/src/main/java/com/yeosal/api/common/ApiExceptionHandler.java` (three new `@ExceptionHandler` methods after the Story 3.1 mappings; preserve every existing handler).
- `BE/src/main/java/com/yeosal/api/notification/NotificationKind.java` (append `KUDOS_RECEIVED` value).
- `BE/src/main/java/com/yeosal/api/notification/NotificationService.java` (single `case KUDOS_RECEIVED` branch in `isCronEnabled` switch — no other changes).
- `BE/src/main/java/com/yeosal/api/realtime/RealtimePublisher.java` (add `publishKudos(...)` method; update class-level destination scheme javadoc bullet).
- `BE/src/main/java/com/yeosal/api/realtime/JwtChannelInterceptor.java` (extend `ROOM_TOPIC` regex with `|kudos`; update class-level javadoc bullet).
- `FE/src/api/chat.ts` (append `"KUDOS"` to the `ChatMessageKind` union).
- `FE/src/components/chat/SystemMessage.tsx` (add KUDOS case to `visualFor` + `KIND_LABEL`; preserve every other case).
- `FE/src/lib/notifications.ts` (add `case "KUDOS_RECEIVED"` branch to `routeInvalidation`).
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (status transitions).
- `_bmad-output/implementation-artifacts/3-5-kudos-message-endpoint-chat-messages-kind-extension.md` (this file's checkboxes, Dev Agent Record, Status).

**Files explicitly NOT touched:**

- `BE/src/main/resources/db/migration/V1..V11__*.sql` — only V12 is added; existing migrations are append-only (Flyway invariant).
- `BE/src/main/java/com/yeosal/api/room/chat/ChatService.java` — kudos has its own service. The existing `publishSystem` path is preserved; `sendUserMessage` is preserved.
- `BE/src/main/java/com/yeosal/api/room/chat/ChatController.java` — kudos has its own controller per AC4 rationale.
- `BE/src/main/java/com/yeosal/api/room/chat/ChatMessage.java` — entity layer unchanged (the existing `@Enumerated(EnumType.STRING) ChatMessageKind` round-trips KUDOS automatically).
- `BE/src/main/java/com/yeosal/api/revival/*` — kudos and revival are independent; no cross-module imports.
- `BE/src/main/java/com/yeosal/api/survival/*` — read-only consumer (`SurvivalStateRepository.findByRoomIdAndUserId` for eligibility checks).
- `BE/src/main/java/com/yeosal/api/friend/*` — read-only consumer (`FriendshipRepository.findBetween` for the friendship check).
- FE chat list (`FE/src/components/chat/ChatList.tsx`) — KUDOS rows flow through `MessageBubble → SystemMessage` automatically because the bubble's non-USER routing at `MessageBubble.tsx:19-22` already delegates every non-USER kind to `<SystemMessage>`.
- FE `useChatMessages` hook (`FE/src/lib/query/hooks/chat.ts`) — already consumes whatever `ChatMessageDto[]` the BE returns; the new KUDOS kind flows through unchanged.

### Testing standards summary

- **BE:** JUnit 5 + AssertJ + Mockito for unit; `@SpringBootTest` + `@Testcontainers` PostgreSQL (NO H2 — project-context "Testcontainers required for partial unique indexes, advisory locks, jsonb") for IT. JWT auth via the existing test helper. Tests live at `BE/src/test/java/com/yeosal/api/room/chat/...` mirroring the main package layout (project-context "Tests live ... mirroring the main package layout"). Naming convention: `methodName_scenario_expectedBehavior()` or `@DisplayName`.
- **FE:** Jest + `@testing-library/react-native`. TanStack Query mutation tests stub `apiRequest` (project-context "TanStack hook tests must wrap in a `QueryClientProvider` and stub `fetch` — no real network"). Realtime hook tests are NOT required for Story 3.5 (the FE does not subscribe to `/topic/rooms.{id}.kudos`; Story 3.2 will).
- **Concurrency test is non-optional.** Architecture §5.1 explicitly calls out idempotency via partial unique indexes per V8/V9 reference. A test that asserts "exactly one of two parallel kudos sends succeeds" is the contract; `KudosConcurrencyIT` is the implementation.
- **Migration test is non-optional.** Architecture §4.11 + project-context "Postgres-specific features (partial unique indexes, jsonb) — Testcontainers required". `KudosMigrationIT` proves (a) the constraint widened to permit KUDOS, (b) the index exists with the IMMUTABLE expression, (c) the dedupe predicate fires correctly.
- **Coverage target:** 80%+ on new BE service / controller / repository code; 80%+ on new FE hook / API client / component-branch code.

### Previous-story intelligence

- **Story 1.1 — Room creation precedent for the modular service pattern** (`BE/src/main/java/com/yeosal/api/room/RoomService.java`). Story 3.1's `RevivalService` followed it; Story 3.5's `KudosService` follows it. Constructor injection, `@Transactional` boundary, single public method.
- **Story 1.2 — `SurvivalStateRealtimeListener`** (`BE/src/main/java/com/yeosal/api/survival/SurvivalStateRealtimeListener.java`) is the canonical example of the `@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Transactional(REQUIRES_NEW)` listener pattern. Story 3.5's `KudosRealtimeListener` follows the same structure. The `objectMapper.valueToTree(payload)` JSON serialisation precedent at line 87 applies if a future listener needs to queue the kudos frame for delayed emit (not needed in v1).
- **Story 1.4 — V11 IMMUTABLE expression hotfix (PR #57, commit `4f741ff`)** — **THE single most important precedent for this story's V12 migration.** PR #57's commit body explains why `((eliminated_at)::date)` (a STABLE cast) is rejected from a partial unique index expression with SQLSTATE 42P17. V12 uses `((created_at at time zone 'Asia/Seoul')::date)` (IMMUTABLE via the literal-timezone shift) to avoid the same trap. **Read the PR #57 commit message before authoring V12.**
- **Story 1.5 — Brand-voice lint** (`tools/brand-voice-lint.ts`) — AVOID lexicon Rule 2 applies to all new copy. Run before commit. Story 3.5's static strings (push title/body, exception messages, rendered chat body) are clean; only the user-typed `message` body is exempt (Architecture §4.15 keeps it human-authoritative WARN).
- **Story 1.6 — `ChatService.publishMemberJoinedSystemMessage`** (`BE/src/main/java/com/yeosal/api/room/chat/ChatService.java:157-167`) — the precedent for writing a non-USER chat_messages row with structured payload. The Story 3.5 service-layer INSERT mirrors the same `(roomId, senderUserId, kind, body, payload)` constructor pattern.
- **Story 2.1 — `SpectatorWriteForbiddenException` typed-subclass + CODE-constant precedent** (`BE/src/main/java/com/yeosal/api/common/SpectatorWriteForbiddenException.java`). Story 3.5's `NotFriendsException` follows the same pattern (extends `ForbiddenException`, has a `public static final String CODE`).
- **Story 2.2 — `notification_log` debounce pattern** (`BE/src/main/java/com/yeosal/api/notification/NotificationService.sendEvent` at lines 90-113). Kudos uses the same `sendEvent` with `Duration.ZERO` (the partial unique index is the dedupe authority, not `NotificationLog`).
- **Story 2.3 — Read-side redaction precedent** — does NOT apply to kudos. Kudos is sender-initiated and the receiver always sees the row + push (subject to quiet hours); record-visibility-prefs do not gate it.
- **Story 3.1 — `RevivalService` + `RevivalController` + `RevivalConcurrencyIT`** — the closest precedent for this story's module shape. Story 3.5 mirrors:
  - Service-layer `DataIntegrityViolationException → typed exception` translation (Story 3.1 catches the partial-unique-index conflict; Story 3.5 does the same for `ux_kudos_one_per_day`).
  - `@WebMvcTest` controller test pattern (`RevivalControllerTest.java`) — 5 cases there; Story 3.5 needs 10.
  - `@SpringBootTest` + Testcontainers concurrency test pattern (`RevivalConcurrencyIT.java` lines 62-130) — `CountDownLatch` + two parallel `CompletableFuture`s; Story 3.5 mirrors the structure for `KudosConcurrencyIT`.
  - The Story 3.1 review-patch addition of `|points` to `JwtChannelInterceptor.ROOM_TOPIC` regex is the exact precedent for Story 3.5's `|kudos` addition.
  - `ApiExceptionHandler` mapping pattern (lines 138-181) — three more handlers added in the same shape.
- **Story 3.1 review followups** documented two open issues at `3-1-...md` lines 462-469. **Two of them are still relevant to Story 3.5's safe operation:**
  - `DefaultRoomMigrationRunner` creates rooms bypassing `RoomService.create` — does NOT affect kudos (kudos doesn't need `room_point_pool`). Out of scope for this story.
  - `FE persist cache version bump` — kudos changes the FE wire shape (`ChatMessageKind` union adds `"KUDOS"`). **If Story 3.1's reviewer-flagged persist-cache-version issue lands during Story 3.5's window** the dev agent should coordinate the bump. Otherwise Story 3.5's union addition is backward-compatible (old persisted messages don't have KUDOS rows; new messages flow naturally) — no version bump strictly required.

### Git intelligence (recent commits informing this story)

- `d83e130` (PR #75, 2026-05-17) — **Story 3.1 free revival ticket + self-revival.** Establishes the `revival/` module pattern. Defines `JwtChannelInterceptor.ROOM_TOPIC` regex with `|points`. Story 3.5 reads this commit's `RevivalService.java` for the `NestedExceptionUtils.getMostSpecificCause(...)` + constraint-name discrimination pattern.
- `e15d375` (PR #77, 2026-05-17) — Docker build context fix (`infra/include FE/src/theme/tokens.json`). Not directly relevant; confirms the FE→BE codegen pipeline is operational.
- `191911e` (PR #69, 2026-05-16) — Story 2.2 spectator daily digest. Establishes `NotificationKind.SPECTATOR_DIGEST` + half-open window. Story 3.5's `NotificationKind.KUDOS_RECEIVED` follows the same enum-extension pattern.
- `387a955` (PR #71, 2026-05-16) — Story 2.3 record-visibility opt-in. Does NOT gate kudos (sender-initiated, not eliminated-user-controlled).
- `2182ca9` (PR #62, 2026-05-13) — Story 1.4 V11 + IMMUTABLE hotfix follow-up. Confirms V11 + the partial-unique-index expression contract.
- `4f741ff` (PR #57, 2026-05-13) — **THE single most important precedent for V12's IMMUTABLE expression contract.** Read the commit body for SQLSTATE 42P17 reasoning.

### Latest technical specifics

- **PostgreSQL 16 partial unique indexes** — `create unique index ... ON ... WHERE ...` is a partial index with a `WHERE` predicate. The predicate MUST be IMMUTABLE; STABLE/VOLATILE expressions are rejected with `42P17 "functions in index predicate must be marked IMMUTABLE"`. Our predicate `where kind = 'KUDOS'` is a simple equality on an enum-text column — trivially IMMUTABLE.
- **Postgres expression-index timezone rules** — `timestamptz at time zone '<literal>'` produces a `timestamp` (without tz) in the named zone's wall-clock; this is IMMUTABLE because the timezone is a literal constant. `timestamp::date` (without tz) is IMMUTABLE. Chaining both gives the KST-day key as an IMMUTABLE expression. Direct `timestamptz::date` is STABLE because the conversion uses `SHOW timezone`. **Use the `at time zone` form; do not use the bare `::date` cast.**
- **JJWT 0.12.x** — not in this story's path. Auth is handled by the existing `JwtAuthenticationFilter`.
- **Spring `@TransactionalEventListener(phase = AFTER_COMMIT)`** — Spring Framework 6.x / Boot 3.3.x. The listener runs in a NEW transaction (`REQUIRES_NEW`). The existing pattern in `SurvivalStateRealtimeListener` is the reference.
- **TanStack Query 5.x** — `invalidateQueries({ queryKey })` and `invalidateQueries({ predicate })` are both used in this codebase. The `routeInvalidation` MILESTONE branch (`FE/src/lib/notifications.ts:66-73`) uses the predicate form for room-messages — Story 3.5's `KUDOS_RECEIVED` branch mirrors it.
- **Expo SDK 54 / RN 0.81.5** — `<MaterialIcons>` icon set already used in `SystemMessage.tsx` line 1. `favorite` is a valid icon name (heart filled — see Material Symbols catalog). No new packages required.
- **Jackson ObjectMapper** — already auto-wired in Spring Boot via the default `JacksonAutoConfiguration`. The kudos JSON payload writer in `KudosService.buildPayloadJson` injects the same `ObjectMapper` via constructor — no special config required.
- **`@Size(max = 60)` on a nullable field** — Bean Validation treats null as valid (i.e., max-60 is only enforced when the value is present). Combined with `@NotNull` it would fail; we deliberately do NOT use `@NotNull` on `message` so null/missing is acceptable per epics 578 (`message?: string`).

### Project context reference

Mandatory pre-read: `_bmad-output/project-context.md`. Load-bearing rules for this story:

- BE controller paths use `/api/v1/...` only — context-path `/yeolsal` is auto-prefixed.
- All controller responses wrapped in `ApiResponse.of(...)`.
- Single `@RestControllerAdvice` — extend `ApiExceptionHandler`, do not introduce a second.
- TanStack Query persisted to AsyncStorage — `invalidateQueries`, never `clear()`.
- Hibernate `validate` mode — schema changes require Flyway migrations. **Story 3.5 adds V12.**
- JPA `open-in-view: false` — service-layer `@Transactional` reads only.
- `@Valid` on controller DTOs — `MethodArgumentNotValidException` maps to `400 VALIDATION`.
- DTOs are `record`s.
- All API calls FE-side via `apiRequest<T>` — direct `fetch` forbidden.
- Realtime via the single `RealtimeProvider` STOMP client — no second client.
- Postgres-specific features (partial unique indexes, jsonb) — **Testcontainers required**, H2 forbidden.
- Stack PR Merge Procedure — Story 3.5 is a clean PR (no stack); not applicable but bookmark for Story 3.2.
- Migration check pre-pre-merge: any change with significant operational impact (migrations) MUST include "Post-merge user action" in PR body — V12 qualifies.
- Brand-voice "USE" lexicon (함께, 선물, 응원, 컴백, 회생, 그룹, 동료, 우리, 살리다) — Story 3.5 uses 응원 across all surfaces.
- No emojis in source files or docs unless explicitly requested — the push title `"응원이 도착했어요 🌿"` IS an explicit requirement (epics 487 — donor toast convention).

### References

- Epics: `_bmad-output/planning-artifacts/epics.md` lines 563–606 + line 1192 (execution order lock) + line 1163 (FR Coverage Map).
- PRD: `_bmad-output/planning-artifacts/prd.md` FR-8.3.9 (line 385 — root authority), FR-8.3.4 (push tone), FR-8.3.7 (donor-protection), FR-8.8.2 (AVOID lexicon).
- Architecture: `_bmad-output/planning-artifacts/architecture.md` §4.11 (Flyway brownfield convention), §4.14 (realtime privacy), §4.15 (brand-voice lint), §5.1 (BE patterns + idempotency), §5.4 (privacy patterns), §5.5 (brand voice patterns), §6.4 (REST endpoint table — note kudos endpoint is NOT explicitly listed because architecture predates the sprint-change; PRD FR-8.3.9 is the authority).
- UX: `_bmad-output/planning-artifacts/ux-design-specification.md` lines 446-448, 486 (Strava Kudos inspiration), 535 (Friend Gift Modal 3-CTA), 600-622 (sub-mode catalog including D4 Postcard), 722, 772 ("응원만 보내기 (0점)" CTA tone), 1115-1135 (D4 Postcard Mythic spec), 1165-1166 (Friend Gift Modal surface assignment), 1310-1338 (J3 friend-revives-friend flow with kudos branch at line 1322).
- V7 schema (in-prod): `BE/src/main/resources/db/migration/V7__chat_messages.sql` (chat_messages base + CHECK constraint).
- V8/V9 precedent: `BE/src/main/resources/db/migration/V8__chat_milestone_dedup.sql` + `V9__chat_milestone_per_day.sql` (partial unique index pattern Story 3.5 mirrors).
- V11 IMMUTABLE-cast lesson: `BE/src/main/resources/db/migration/V11__survival_revival_economy.sql:54-62` (PR #57 commit `4f741ff` body).
- Project context: `_bmad-output/project-context.md` (all of Critical Implementation Rules section).
- Realtime publisher: `BE/src/main/java/com/yeosal/api/realtime/RealtimePublisher.java` (failure-swallow pattern at lines 111-128).
- STOMP auth interceptor: `BE/src/main/java/com/yeosal/api/realtime/JwtChannelInterceptor.java` (room-topic regex extension target at line 43).
- Survival realtime listener (AFTER_COMMIT pattern): `BE/src/main/java/com/yeosal/api/survival/SurvivalStateRealtimeListener.java`.
- Chat module entry: `BE/src/main/java/com/yeosal/api/room/chat/` — `ChatMessage.java`, `ChatMessageKind.java`, `ChatMessageRepository.java`, `ChatService.java`, `ChatController.java`.
- Notification module: `BE/src/main/java/com/yeosal/api/notification/` — `NotificationKind.java`, `NotificationService.java`, `NotificationPref.java`.
- Friend module: `BE/src/main/java/com/yeosal/api/friend/` — `Friendship.java`, `FriendshipStatus.java`, `FriendshipRepository.java`, `FriendService.canView` lines 176-188.
- `SpectatorWriteForbiddenException` typed-subclass precedent: `BE/src/main/java/com/yeosal/api/common/SpectatorWriteForbiddenException.java`.
- Existing `<SystemMessage>` chat list variant: `FE/src/components/chat/SystemMessage.tsx` (extension target — add KUDOS case).
- Existing chat list flow: `FE/src/components/chat/ChatList.tsx` + `MessageBubble.tsx` (KUDOS rows flow through the existing non-USER delegation at `MessageBubble.tsx:19-22`).
- Existing notification routing: `FE/src/lib/notifications.ts:55-92` (extension target — add `KUDOS_RECEIVED` branch).
- Story 3.1 service-layer DIVE translation pattern: `BE/src/main/java/com/yeosal/api/revival/RevivalService.java` (`NestedExceptionUtils.getMostSpecificCause(...)` + constraint-name discrimination).
- Story 3.1 controller test pattern: `BE/src/test/java/com/yeosal/api/revival/RevivalControllerTest.java`.
- Story 3.1 concurrency test pattern: `BE/src/test/java/com/yeosal/api/revival/RevivalConcurrencyIT.java` (`CountDownLatch` + parallel `CompletableFuture` race).

## Dev Agent Record

### Agent Model Used

claude-opus-4-7[1m]

### Debug Log References

- FE typecheck after FE-1.2 union widening surfaced one downstream consumer needing `KUDOS` in its `Record<ChatMessageKind, string>`: `FE/app/(tabs)/chat.tsx` `KIND_PREFIX`. Added `KUDOS: "🌿 "` to match the kudos push title brand convention.
- `KudosServiceTest.makeDiveForConstraint` initially used `org.postgresql.util.{PSQLException, ServerErrorMessage}` to fabricate a DIVE root cause with a structured `getServerErrorMessage().getConstraint()` — replaced with a plain `java.sql.SQLException` whose message contains the constraint name, matching the simpler `RevivalService.isRevivalDedupConflict` string-match discriminator. Same approach used in `KudosService.isKudosDedupConflict`.
- Brand-voice lint scan confirmed all new Korean static strings (push title `"응원이 도착했어요 🌿"`, push body `"<nickname>이 응원을 보냈어요"`, error messages, accessibility label `"응원"`) carry zero AVOID-lexicon words; banned-words assertion in `SystemMessage.test.tsx` documents the contract.

### Completion Notes List

- **V12 migration**: ships the partial unique index `ux_kudos_one_per_day` with the **IMMUTABLE Asia/Seoul** expression `((created_at at time zone 'Asia/Seoul')::date)` per AC1. Both PRD/epics deviations documented in the story spec were respected: (1) V12 is a NEW migration since V11 already shipped without KUDOS; (2) the PRD's `date_part('day', ...)` formula was rejected because it would collide month-spanning kudos on the same day-of-month.
- **Service shape**: `KudosService` is a sibling service to `ChatService` (NEW package `com.yeosal.api.room.chat`). Friendship gate uses `findBetween(me, target).filter(status == ACCEPTED)` matching the canonical `FriendService.canView` pattern. Target eligibility is RED/SPECTATOR only; missing survival_state row is treated as ineligible per AC defensive policy.
- **Dedup defence**: Two-layer per Architecture §5.1 — `insertKudosIfAbsent` row-count check + `DataIntegrityViolationException` string-match translation for any Hibernate-flush race past the on-conflict path. Constraint discriminator is `"ux_kudos_one_per_day"`.
- **Realtime + push fan-out**: `KudosRealtimeListener` runs `AFTER_COMMIT` in `REQUIRES_NEW`; emits `/topic/rooms.{id}.kudos` with `KudosSentPayload` AND pushes `NotificationKind.KUDOS_RECEIVED` with `Duration.ZERO` debounce. Each side is independently try/catch-wrapped so a broker or push hiccup never poisons the surrounding listener tx.
- **STOMP auth**: `JwtChannelInterceptor.ROOM_TOPIC` regex extended with `|kudos`. Without this, FE subscriptions would be silently denied.
- **FE wiring**: `useSendKudos(roomId)` invalidates `qk.roomMessages(roomId)` + `qk.roomLastMessage(roomId)` on success; no cache mutation on error (consumer surfaces toast). `routeInvalidation` adds `KUDOS_RECEIVED` branch mirroring MILESTONE. `SystemMessage` gains a postcard-warm KUDOS variant (heart + coralSoft + emphasized). `KIND_LABEL["KUDOS"] = "응원"` for a11y.
- **Brand-voice contract**: All Story 3.5 static strings clean against Rule 2 (AVOID lexicon). `SystemMessage.test.tsx` asserts the rendered body explicitly.
- **Test coverage**: BE — 17-case Mockito unit (`KudosServiceTest`), 10-case `@WebMvcTest` (`KudosControllerTest`), 5-case Testcontainers migration IT (`KudosMigrationIT`), 1 concurrency IT (`KudosConcurrencyIT`). FE — 4-case `SystemMessage`, 5-case `useSendKudos`, 1 added `notifications` predicate. FE 266/266 green; BE pending CI JDK 21 (Story 3.1 precedent — opt-in via `-Dyeosal.boot-smoke=true`).
- **Out of scope respected**: No FE STOMP subscription to `/topic/rooms.{id}.kudos` (downstream Story 3.2 / 3.3 will add). No donor push, no kudos archive, no multi-room kudos, no quiet-hours override.

### File List

**NEW (BE):**
- `BE/src/main/resources/db/migration/V12__chat_kudos.sql`
- `BE/src/main/java/com/yeosal/api/room/chat/KudosController.java`
- `BE/src/main/java/com/yeosal/api/room/chat/KudosService.java`
- `BE/src/main/java/com/yeosal/api/room/chat/KudosRequest.java`
- `BE/src/main/java/com/yeosal/api/room/chat/KudosDto.java`
- `BE/src/main/java/com/yeosal/api/room/chat/KudosSentEvent.java`
- `BE/src/main/java/com/yeosal/api/room/chat/KudosSentPayload.java`
- `BE/src/main/java/com/yeosal/api/room/chat/KudosRealtimeListener.java`
- `BE/src/main/java/com/yeosal/api/room/chat/KudosAlreadySentTodayException.java`
- `BE/src/main/java/com/yeosal/api/room/chat/KudosTargetNotEligibleException.java`
- `BE/src/main/java/com/yeosal/api/room/chat/NotFriendsException.java`
- `BE/src/test/java/com/yeosal/api/room/chat/KudosServiceTest.java`
- `BE/src/test/java/com/yeosal/api/room/chat/KudosControllerTest.java`
- `BE/src/test/java/com/yeosal/api/room/chat/KudosMigrationIT.java`
- `BE/src/test/java/com/yeosal/api/room/chat/KudosConcurrencyIT.java`

**NEW (FE):**
- `FE/src/api/kudos.ts`
- `FE/src/lib/query/hooks/kudos.ts`
- `FE/src/components/chat/__tests__/SystemMessage.test.tsx`
- `FE/src/lib/query/hooks/__tests__/kudos.test.tsx`

**UPDATE (BE):**
- `BE/src/main/java/com/yeosal/api/room/chat/ChatMessageKind.java` — appended `KUDOS` + class-level javadoc note
- `BE/src/main/java/com/yeosal/api/room/chat/ChatMessageRepository.java` — appended `insertKudosIfAbsent` + `findKudosId` native queries; added `LocalDate` + `Optional` imports
- `BE/src/main/java/com/yeosal/api/common/ApiExceptionHandler.java` — 3 new `@ExceptionHandler` methods (KudosAlreadySentToday → 409, KudosTargetNotEligible → 400, NotFriends → 403) + 3 new imports
- `BE/src/main/java/com/yeosal/api/notification/NotificationKind.java` — appended `KUDOS_RECEIVED`
- `BE/src/main/java/com/yeosal/api/notification/NotificationService.java` — `isCronEnabled` switch new `case KUDOS_RECEIVED` branch
- `BE/src/main/java/com/yeosal/api/realtime/RealtimePublisher.java` — added `publishKudos(...)`, `KudosSentPayload` import, javadoc bullet
- `BE/src/main/java/com/yeosal/api/realtime/JwtChannelInterceptor.java` — `ROOM_TOPIC` regex `(chat|members|survival|points|kudos)` + javadoc bullet

**UPDATE (FE):**
- `FE/src/api/chat.ts` — appended `"KUDOS"` to `ChatMessageKind` union + docstring
- `FE/src/components/chat/SystemMessage.tsx` — KUDOS case in `visualFor` + `KIND_LABEL["KUDOS"]="응원"`
- `FE/src/lib/notifications.ts` — `routeInvalidation` `case "KUDOS_RECEIVED"` branch
- `FE/app/(tabs)/chat.tsx` — `KIND_PREFIX["KUDOS"]="🌿 "` (exhaustiveness fix surfaced by tsc)
- `FE/src/lib/__tests__/notifications.test.ts` — added `KUDOS_RECEIVED` test

**UPDATE (artifacts):**
- `_bmad-output/implementation-artifacts/sprint-status.yaml` — backlog → ready-for-dev → in-progress → review transitions + audit comments
- `_bmad-output/implementation-artifacts/3-5-kudos-message-endpoint-chat-messages-kind-extension.md` — this file

### Change Log

| Date | Author | Change |
|------|--------|--------|
| 2026-05-17 | Dev (claude-opus-4-7[1m]) | Story 3.5 implementation complete on `feat/story-3-5-kudos-message-endpoint-and-chat-kind-extension`. BE kudos module + V12 migration + FE wiring + 7 test files. FE 266/266 green; BE pending CI JDK 21. Brand-voice-lint 0 HARD. Status flipped in-progress → review. |
