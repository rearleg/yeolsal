# Story 6.1: Server-side preview card renderer + cache

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As the system,
I want a renderable PNG preview card for any room's invite, served from a fast cache and invalidated on rule/member-count change,
So that the Kakao share preview is consistent, fast, and always reflects the current room state.

## Acceptance Criteria

> 이 스토리는 **Epic 6 의 첫 backlog**이자 KakaoTalk 공유 viral loop 의 **render foundation**이다. 후속 Story 6.2 (Kakao Share SDK + 딥링크) 와 Story 6.3 (RUNBOOK + native module reinstall) 가 이 스토리의 산출물 위에 얹힌다. **BE-only** — FE 파일 변경 ZERO (Story 6.2 가 FE 작업을 가져감). 핵심 산출:
> 1. 새 모듈 `com.yeosal.api.kakaoshare` — `PreviewCardController` + `PreviewCardCacheService` + `InvitePreviewRenderer` + `PngRasterizer` + `PreviewCardCache` 엔티티 + `PreviewCardCacheRepository`.
> 2. `RoomController.createInvite` (POST `/api/v1/rooms/{id}/invites`) 응답을 `{ inviteCode, kakaoShareUrl, previewCardImageUrl }` 로 확장 (기존 `InviteSummary` 두 필드 추가).
> 3. 신규 public endpoint `GET /api/v1/rooms/{id}/invites/preview-card` — PNG redirect / serve (SecurityConfig whitelist 1줄).
> 4. Apache Batik `batik-transcoder` 의존성 BE/build.gradle 추가 — SVG → PNG 라스터화.
> 5. **D1 Editorial sub-mode** 토큰을 `GeneratedTokens.SubMode.Editorial` constants 만으로 소비 (직접 hex 금지, Checkstyle hex-literal guard 가 컴파일타임에 차단).
> 6. Cache invalidation hook 3 곳 — Story 5.1 의 `RoomRuleService.updateRule` afterCommit 패턴에 byte-similar 한 broadcast 추가, `RoomService.joinByCode` / `RoomService.leave` 의 `room_members` 변경 직후.
> 7. Cache stampede 방지 — Postgres advisory lock (`pg_try_advisory_xact_lock(hashtext('preview_card'), room_id)`) + stale-while-regenerate semantics.
>
> **NO new migration** (V11 (11) 의 `room_invite_preview_cache` 가 이미 존재 — `BE/src/main/resources/db/migration/V11__survival_revival_economy.sql:132-139`). **NO new Flyway file** — V13 가 latest 라 V14 가 free 이지만 V14 가 필요한 schema 변경이 없다. **NO new RealtimeEvent sealed variant** — preview card 는 chat / realtime 채널에 broadcast 하지 않음 (KakaoTalk side fetch only). **NO FE source 변경** — Story 6.2 가 FE share button + deeplink 작업을 가져감. **NO push notification** — Story 6.x 전체가 push 미명시. **NO `tokens.json` 변경** — 현 D1 Editorial override map 사용 (Trap #9 의 fallback path).

### AC1 — `POST /api/v1/rooms/{id}/invites` 응답을 share payload 로 확장 (BACKWARD-COMPATIBLE)

**Given** 방 멤버가 `POST /api/v1/rooms/{id}/invites` 를 호출하고 invite code 가 생성된다
**When** BE 가 응답을 반환한다
**Then** `RoomService.InviteSummary` record 와 `RoomController.createInvite` 응답이 두 필드를 추가로 노출한다:

```java
public record InviteSummary(
        long id,
        long roomId,
        String code,
        Instant expiresAt,
        String kakaoShareUrl,         // NEW — Story 6.1 AC1
        String previewCardImageUrl    // NEW — Story 6.1 AC1
) {
    public static InviteSummary from(RoomInvite invite, String kakaoShareUrl, String previewCardImageUrl) {
        return new InviteSummary(
                invite.getId(),
                invite.getRoom().getId(),
                invite.getCode(),
                invite.getExpiresAt(),
                kakaoShareUrl,
                previewCardImageUrl
        );
    }
}
```

**URL contract:**

- `kakaoShareUrl` = `${yeosal.share.deeplink-base}/join?code=${invite.getCode()}` — env-configurable base; default `https://yeolsal.app` for dev/prod parity. FE Story 6.2 가 이 URL 을 Kakao Share SDK 의 `link.mobileWebUrl` / `link.webUrl` 양쪽에 forward (Story 6.2 AC). v1 에서는 universal-link / app-link 호스트가 아직 점유되지 않았으므로 `yeolsal.app` 은 reserve-but-unrouted 인 점을 RUNBOOK 에 명시할 것 (Story 6.3 scope, 본 스토리 아님).
- `previewCardImageUrl` = `${yeosal.share.preview-card-base}/api/v1/rooms/${invite.getRoom().getId()}/invites/preview-card` — env-configurable base; default `https://api.rearleg.com/yeolsal`. 끝에 trailing slash 금지 (KakaoTalk SDK 가 strict URL parsing). **Path 는 room id 만 포함하고 invite code 는 포함하지 않음** — preview card 는 room 단위 cache (architecture §4.10) 이고, 같은 방의 여러 invite 가 발급되어도 preview 는 한 장. 만약 code 를 path 에 포함하면 `(room_id, code)` 별로 cache row 가 갈라져 V11 (11) 의 PK `(room_id)` 와 충돌한다.

**Backward compatibility:**

- 기존 4-필드 InviteSummary 를 사용하는 FE 빌드가 신규 두 필드를 무시해도 안전 (TypeScript 의 structural typing + `apiRequest<T>` 의 정의에 두 필드를 `string` 으로 추가하면 자연 호환). FE 변경은 Story 6.2 가 가져감.
- `from(RoomInvite)` 단일 인자 helper 를 **삭제** — 두 인자 helper 만 남김. 이렇게 하면 `RoomService.createInvite` 가 share-payload 빌더를 우회할 수 없음 (compiler-enforced contract).
- record 확장은 wire-breaking 처럼 보이지만 JSON 응답 envelope 은 키 추가만 발생 → 기존 클라이언트 영향 ZERO. **No deprecation 주석** — 단일 호출 사이트.

**Anti-pattern (DO NOT IMPLEMENT):**

- 두 필드를 별도 endpoint 로 분리 (e.g., `GET /rooms/{id}/invites/{code}/share-payload`) — FR-8.6.1 가 "단일 호출 응답" 을 잠금. 추가 round-trip 은 share UX 시작 직전 latency 를 증가시킴.
- `kakaoShareUrl` 안에 invite code path-segment (e.g., `/join/${code}`) — query-param `?code=` 가 universal-link / app-link 의 표준 (Story 6.2 AC line 858 의 "platform-native deep-link query parameters"). Path-segment 변형은 iOS Universal Links plist 의 path regex 가 복잡해짐.
- `previewCardImageUrl` 에 cache-buster query (`?v=<rendered_at>`) — KakaoTalk SDK 가 cache-busting query 를 따라가지 않고 첫 fetch 결과를 자체 캐시. AC4 의 invalidation 이 nginx + cache row 로 충분.

PRD: FR-8.6.1 (line 414), FR-8.6.2 (line 415). Architecture: §4.10 (line 308-328), §6.4 (line 816 `GET /rooms/{id}/invites/preview-card`).

### AC2 — 신규 `GET /api/v1/rooms/{id}/invites/preview-card` public endpoint (CACHE SERVE)

**Given** KakaoTalk 서버가 invite 를 받은 사용자의 채팅창에 preview 를 렌더할 때
**When** Kakao 서버가 `GET /api/v1/rooms/{id}/invites/preview-card` 로 PNG 를 fetch 한다
**Then** 새 컨트롤러 `com.yeosal.api.kakaoshare.PreviewCardController` 가 다음 셋 중 하나로 응답한다:

```java
@RestController
@RequestMapping("/api/v1/rooms")
public class PreviewCardController {

    private final PreviewCardCacheService cacheService;

    public PreviewCardController(PreviewCardCacheService cacheService) {
        this.cacheService = cacheService;
    }

    /**
     * KakaoTalk-side preview card fetch. Public endpoint (auth-free) per
     * Architecture §6.4 — the URL is shareable and the PNG is room-scoped
     * with non-PII content (room name + member count + rule summary).
     *
     * <p>Returns 302 redirect to the cached PNG URL on hit; 404 if the
     * room does not exist; 503 with Retry-After if rendering is in flight
     * AND no stale PNG is available (rare cold-miss-collision path).
     */
    @GetMapping("/{id}/invites/preview-card")
    public ResponseEntity<Void> servePreviewCard(@PathVariable long id) {
        return cacheService.resolve(id)
                .map(url -> ResponseEntity
                        .status(HttpStatus.FOUND)
                        .location(URI.create(url))
                        .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                        .build())
                .orElseGet(() -> ResponseEntity
                        .status(HttpStatus.SERVICE_UNAVAILABLE)
                        .header(HttpHeaders.RETRY_AFTER, "5")
                        .build());
    }
}
```

**Response semantics:**

1. **Cache hit (fresh)** — 302 Found, Location = cached PNG URL, `Cache-Control: public, max-age=3600`. KakaoTalk 의 fetcher 가 redirect 를 따라 cached PNG 를 받음 (nginx 의 정적 자산).
2. **Cache miss but stale PNG exists** — 302 Found 로 **stale PNG URL** 을 즉시 반환 (cache stampede 방지). 동시에 백그라운드 render 가 시작됨 (AC5).
3. **Cache miss with no stale PNG (cold)** — synchronous render 후 새 URL 로 302. Cold render p95 < 1s (NFR for this story — AC6).
4. **방이 존재하지 않거나 hard-deleted** — `resolve` 가 `Optional.empty()` 를 반환, controller 가 503 + Retry-After 로 응답. 명시적 404 가 필요하면 `RoomNotFoundException` (`NotFoundException` 의 하위) 을 throw 하도록 변경 — 단, 본 스토리의 happy path 는 503 fallback 으로 충분 (KakaoTalk 의 fetcher 가 retry 후 결국 404 inference).
5. **Render in flight + no stale + advisory lock held by another instance** — 503 SERVICE_UNAVAILABLE + `Retry-After: 5`. KakaoTalk 의 fetcher 가 5초 후 재시도하면 첫 render 가 완료되어 캐시 hit.

**SecurityConfig 변경 (1 줄):**

- `BE/src/main/java/com/yeosal/api/common/SecurityConfig.java:36-52` 의 `authorizeHttpRequests` 람다에 한 줄 추가:

```java
.requestMatchers(HttpMethod.GET, "/api/v1/rooms/*/invites/preview-card").permitAll()
```

위치는 `requestMatchers("/ws", "/ws/**").permitAll()` 바로 다음, `.anyRequest().authenticated()` 직전. **GET-only** 명시 — POST / PATCH / DELETE 는 인증 필요. 와일드카드 `*` (단일 path segment) 만 사용 — `**` 는 잘못된 깊이 캡처. JwtAuthenticationFilter 는 이 path 에 대해 자동으로 token validation 을 skip (filter chain 의 `SecurityFilterChain` permitAll 매칭이 token absent 를 허용).

**Anti-pattern (DO NOT IMPLEMENT):**

- 컨트롤러 안에서 PNG bytes 를 직접 `byte[]` body 로 반환 — 메모리 풋프린트가 PNG 사이즈 × 동시 요청 수로 증가. 302 redirect 가 정적 자산 처리를 nginx 에 위임하므로 JVM 메모리 압박 ZERO.
- `@PreAuthorize("permitAll()")` 어노테이션으로 메서드-레벨 권한 부여 — SecurityConfig 의 chain-level whitelist 가 single source of truth. 메서드-레벨 어노테이션은 default-deny 인 chain 에 의해 어차피 reject 되므로 사용 금지 (Story 1.x 의 security filter order 원칙).
- 302 대신 200 + `Content-Type: image/png` + binary body — Kakao Share SDK 가 양쪽을 다 지원하지만, 302 가 운영-friendly: nginx access log 에 cache hit/miss 가 명시적으로 남고, PNG 변경 시 `previewCardImageUrl` URL 은 stable 하면서 redirect target 만 바뀌어 client-side cache invalidation 이 자연스러움.
- `/api/v1/rooms/{id}/preview-card` 처럼 path 에서 `invites` segment 를 제거 — Architecture §6.4 line 816 의 path 가 `/rooms/{id}/invites/preview-card` 로 잠겨 있음. Story 6.2 의 `kakaoShareUrl` 빌더가 이 path 를 hard-code 할 가능성이 있어 변경 금지.

PRD: FR-8.6.2 (line 415). Architecture: §4.10, §6.4 (line 816). project-context: line 109-114 (controller path convention + public endpoint whitelist).

### AC3 — `PreviewCardCacheService.resolve(roomId)` orchestration (RENDER-OR-SERVE)

**Given** `PreviewCardController.servePreviewCard` 가 `resolve(roomId)` 를 호출한다
**When** cache 의 상태에 따라 다음 분기를 따른다
**Then** 새 서비스 `com.yeosal.api.kakaoshare.PreviewCardCacheService` 가 정확히 다음 알고리즘을 구현한다:

```java
@Service
public class PreviewCardCacheService {

    private static final Duration CACHE_TTL = Duration.ofHours(1);

    private final PreviewCardCacheRepository cacheRepository;
    private final RoomRepository rooms;
    private final RoomMemberRepository roomMembers;
    private final RoomRuleVersionRepository ruleVersions;
    private final InvitePreviewRenderer renderer;
    private final PngRasterizer rasterizer;
    private final PreviewCardBackgroundRenderer backgroundRenderer; // Trap #3
    private final EntityManager em;       // advisory-lock issuer
    private final Clock clock;

    /**
     * Returns the PNG URL to serve. Stale-while-regenerate semantics:
     * <ul>
     *   <li>Fresh (rendered_at + TTL > now) → serve cached URL, no render.
     *   <li>Stale (rendered_at + TTL <= now) → serve stale URL, kick off
     *       background render via @Async (single-flight via advisory lock).
     *   <li>No row at all → synchronous render, return new URL.
     *   <li>Room not found → Optional.empty() (controller maps to 503/404).
     * </ul>
     */
    @Transactional
    public Optional<String> resolve(long roomId) {
        Room room = rooms.findById(roomId).orElse(null);
        if (room == null) return Optional.empty();

        return cacheRepository.findById(roomId)
                .map(cache -> {
                    boolean stale = cache.getRenderedAt()
                            .plus(CACHE_TTL)
                            .isBefore(clock.instant());
                    if (stale) backgroundRenderer.render(room); // proxied @Async
                    return cache.getPngUrl();
                })
                .or(() -> Optional.of(synchronousRender(room)));
    }

    private String synchronousRender(Room room) {
        if (!tryAcquireLock(room.getId())) {
            // Another in-flight render owns the lock; serve stale row if any
            return cacheRepository.findById(room.getId())
                    .map(PreviewCardCache::getPngUrl)
                    .orElseThrow(() -> new ServiceUnavailableException(
                            "preview-card render in flight; retry shortly"));
        }
        return doRender(room);  // releases advisory lock at txn end
    }

    /** Called only from PreviewCardBackgroundRenderer (proxy boundary). */
    void backgroundRenderUnchecked(Room room) {
        if (!tryAcquireLock(room.getId())) return;  // another instance owns it
        try { doRender(room); }
        catch (RuntimeException ex) {
            log.warn("[kakaoshare] background render failed roomId={}: {}",
                    room.getId(), ex.toString());
        }
    }

    private boolean tryAcquireLock(long roomId) {
        Boolean acquired = (Boolean) em.createNativeQuery(
                "SELECT pg_try_advisory_xact_lock(hashtext('preview_card'), CAST(:rid AS int4))")
                .setParameter("rid", (int)(roomId & 0x7FFF_FFFFL))
                .getSingleResult();
        return Boolean.TRUE.equals(acquired);
    }
}
```

**Why advisory lock not `SELECT ... FOR UPDATE` on the cache row:**

- Cold-miss 시 row 가 존재하지 않음 — `FOR UPDATE` 가 잠글 대상 없음.
- `pg_try_advisory_xact_lock` 은 txn 종료 시 자동 release — explicit unlock 코드 불필요.
- 2-args 버전 사용: `hashtext('preview_card')` (namespace) + room_id (key) 로 다른 도메인의 advisory lock 과 충돌 회피. Story 3.x 의 revival advisory lock (`hash(room_id, user_id, eliminated_at)`) 와 키 공간 분리.

**Why Optional return signature:**

- `null` 반환 대신 `Optional<String>` 으로 type-safe 한 "방 없음" 신호. 컨트롤러 layer 에서 `.map(...).orElseGet(...)` 패턴이 503 / 404 분기를 명료하게 표현.
- `ServiceUnavailableException` 은 새 도메인 예외 (AC11) — `ApiExceptionHandler` 에 503 매핑 추가 필요.

**Trap #6 (TTL 의미):**

- Cache TTL 1h 는 **server-side 의 stale-or-fresh 판정 기준** 이지 nginx Cache-Control 의 `max-age` 와는 별개 의도. Cache-Control max-age 도 1h 로 동일하게 설정 (FR-8.6.2 line 415 "TTL 1h") 하되, server-side TTL 은 invalidation hook (AC4) 가 발화하면 즉시 trigger.

PRD: FR-8.6.2 (line 415). Architecture: §4.10 line 326 "nginx serves the existing PNG while regeneration runs".

### AC4 — Cache invalidation hooks on rule + member-count change (REQUIRED CALL SITES)

**Given** 방의 rule 또는 member 수가 변경되어 cache 가 stale 해진다
**When** 변경 트랜잭션이 commit 된다
**Then** **세 호출 사이트**가 `PreviewCardCacheService.invalidate(roomId)` 를 `publishAfterCommit` defer 패턴으로 등록한다:

1. **`com.yeosal.api.survival.RoomRuleService.updateRule`** (Story 5.1 + 5.4 chokepoint) — Story 5.4 가 이미 추가한 `publishAfterCommit` 람다 **옆에** 새 람다를 추가 (Story 5.4 의 chat broadcast 와 직렬 호출, 둘 다 outer commit 이후 실행):

```java
publishAfterCommit(() -> {
    try { chatService.publishRuleChangeSystemMessage(...); }  // Story 5.4, unchanged
    catch (RuntimeException ex) { log.warn("[chat] ...", ex); }
});
publishAfterCommit(() -> previewCardCacheService.invalidate(roomId));  // NEW Story 6.1
```

2. **`com.yeosal.api.room.RoomService.joinByCode`** — `roomMembers.save(...)` 직후, `MemberSummary.from(...)` 직전. 새 멤버가 들어와 `member_count_at_render` 가 outdated.

3. **`com.yeosal.api.room.RoomService.leave`** — `roomMembers.delete(...)` 직후. 떠난 멤버로 인해 카운트 감소.

**`invalidate(roomId)` 구현:**

```java
@Transactional
public void invalidate(long roomId) {
    cacheRepository.deleteById(roomId);   // row gone → next GET triggers fresh render
}
```

**Why delete-row vs. update-rendered_at-to-epoch:**

- `deleteById` 가 invalidate 의 가장 직설적인 형태. 다음 GET 이 cold-miss path (AC3 의 synchronous render) 로 진입.
- `rendered_at = epoch` 같은 sentinel 은 stale-while-regenerate path 와 cold-miss path 의 의미를 혼동시킴. Row 가 있으면 "이전 PNG 가 존재" — 없으면 "처음 보는 방" 으로 명확.
- `room_invite_preview_cache.room_id` 가 PK 이고 `rooms.id` ON DELETE CASCADE — room 자체가 삭제되면 cache row 도 자동 정리. invalidate 는 정상 흐름의 race 만 닫음.

**Why afterCommit defer (Story 5.4 패턴과 동일):**

- `updateRule` 의 outer txn 이 rollback 되면 — 예: 동시 leader transfer 가 race — rule version 은 없는데 cache 만 무효화된 상태가 됨. afterCommit 이 outer commit 의 happen-before 를 보장.
- `joinByCode` / `leave` 의 경우 outer commit 이전에 invalidate 가 실행되면, 동시에 들어온 GET 이 stale row 를 fresh 로 오인할 수 있음. afterCommit 으로 push 하면 commit 직후 row 가 사라지므로 다음 GET 이 새 멤버 수로 render.

**Trap #7 (이미 존재하는 helper 재사용):**

- `RoomRuleService` 의 `publishAfterCommit(Runnable)` (Story 5.4 가 추가한 헬퍼, `RoomRuleService.java:116-127`) 를 그대로 재사용. **두 번째 사본 만들지 말 것** — 같은 클래스 안에서 한 helper 가 두 람다를 등록.
- `RoomService` 에는 동등 helper 가 없음 → `DailyService.publishAfterCommit:295-306` 패턴을 복사해 `RoomService` 에도 private helper 로 추가. Story 5.4 가 `RoomRuleService` 에 같은 패턴을 byte-identical 하게 복제한 선례 (Trap #4 of 5.4) — 같은 정당화 (YAGNI: 3번째 호출자가 생기기 전까지는 cross-module 추상화 금지). **`RoomService` 의 helper 는 private, 메서드 이름도 `publishAfterCommit` 으로 동일**.

**Anti-pattern (DO NOT IMPLEMENT):**

- `@EventListener` 또는 `@TransactionalEventListener(AFTER_COMMIT)` 으로 cross-class event-bus 도입 — 단일 구독자 (`PreviewCardCacheService.invalidate`) 한 명에 대한 over-engineering. Story 5.3 가 같은 이유로 Spring event-listener 패턴을 reject 한 선례 (Trap #2 of 5.3).
- `RoomMemberCapService` / `RoomService.create` / `RoomService.changeOwner` / `TransferLeadershipService` 등 다른 변경 사이트에 invalidate 추가 — preview 에는 owner 이름이나 cap 값이 표시되지 않으므로 (AC5 의 D1 Editorial layout 참조) 무효화 ZERO. cap 변경 (Story 5.2) 은 `pending_max_members` 라 다음 달 1일에 적용되며, 다음 달 1일에는 자동으로 TTL 1h 가 만료되어 다음 fetch 가 fresh render.
- 동기 invalidate (afterCommit 없이) — `updateRule` 의 outer rollback 이 stale cache 와 fresh rule 의 불일치를 만듦. AC4 의 defer 가 필수.
- `PreviewCardCacheService` 를 `RoomService` 가 inject — package-by-feature 위반은 아니지만 (cross-package application service inject 는 정상, project-context line 176), `RoomService` 의 dependency 그래프가 이미 무거움. Constructor 마지막 인자로 append 하고 `private final PreviewCardCacheService previewCardCacheService;` 로 보관.

PRD: FR-8.6.2 (line 415 "regenerated on rule/member-count change"). Architecture: §4.10 line 326-327 "any service that writes a new room_rule_versions row or modifies room_members count enqueues a cache eviction".

### AC5 — `InvitePreviewRenderer` SVG template — D1 Editorial sub-mode tokens ONLY (LOCKED VISUAL)

**Given** cache 가 fresh PNG 를 만들어야 한다
**When** renderer 가 SVG 텍스트를 빌드한다
**Then** 새 클래스 `com.yeosal.api.kakaoshare.InvitePreviewRenderer` 는 다음을 만족한다:

**(a) Layout (UX D1 Editorial sub-mode, ux-design-specification.md:1050-1071 lock):**

```
+---------------------------------------------+
|                                             |
|  열살                                        |  ← word-mark, GeneratedTokens.SubMode.Editorial.* serif 1줄
|                                             |
|  ${roomName}                                |  ← Nanum Myeongjo 700~900 weight, oxblood key color
|                                             |
|  ${memberCount}명이 함께 살아남는 중           |  ← body, secondary text token
|  ${rulePreview}                             |  ← preset summary (Story 5.4 의 formatRulePreview 와 같은 phrase)
|                                             |
|  같이 살아남자                                |  ← footer, brand-voice locked phrase
|                                             |
+---------------------------------------------+
```

**(b) Token consumption — `GeneratedTokens.SubMode.Editorial.*` constants ONLY:**

- `GeneratedTokens.COLOR_BG_CANVAS` (background fill)
- `GeneratedTokens.COLOR_TEXT_PRIMARY` (room name, member count line)
- `GeneratedTokens.COLOR_KEY_*` (oxblood accent — heading underline / word-mark hairline)
- `GeneratedTokens.SubMode.Editorial.TYPOGRAPHY_HEADING_WEIGHT` (900)
- `GeneratedTokens.SubMode.Editorial.TYPOGRAPHY_HEADING_TRACKING` (`-0.025em`)
- `GeneratedTokens.SubMode.Editorial.SPACE_LAYOUT_PADDING` (24)
- `GeneratedTokens.SubMode.Editorial.RADIUS_DEFAULT` (8)

**Direct hex literal 금지** — `BE/build.gradle:285-298` Checkstyle hex-literal guard 가 컴파일 차단. SubMode constants 가 부족하면 Trap #9 의 fallback path (base GeneratedTokens 상수 대체) 사용. 본 스토리는 **tokens.json 변경 ZERO** 를 기본 가정.

**(c) SVG document shape (Java text builder, no Batik for build step):**

```java
public String render(Room room, int memberCount, RoomRuleVersion currentRule) {
    String roomName = escapeXml(room.getName());
    String rulePreview = RulePresetPreview.format(currentRule.getRulePayload().getPreset(),
                                                  currentRule.getRulePayload().getWeekendInclude());

    return String.format("""
        <?xml version="1.0" encoding="UTF-8" standalone="no"?>
        <svg xmlns="http://www.w3.org/2000/svg" width="800" height="420" viewBox="0 0 800 420">
          <rect width="100%%" height="100%%" fill="%s"/>
          <text x="48" y="80" font-family="Nanum Myeongjo, serif"
                font-weight="%d" font-size="22" fill="%s">열살</text>
          <text x="48" y="170" font-family="Nanum Myeongjo, serif"
                font-weight="%d" font-size="56" fill="%s"
                letter-spacing="%s">%s</text>
          <text x="48" y="240" font-family="-apple-system, sans-serif"
                font-size="20" fill="%s">%s</text>
          <text x="48" y="280" font-family="-apple-system, sans-serif"
                font-size="18" fill="%s">%s</text>
          <text x="48" y="380" font-family="-apple-system, sans-serif"
                font-size="16" fill="%s" font-style="italic">같이 살아남자</text>
        </svg>
        """,
        GeneratedTokens.COLOR_BG_CANVAS,                                            // 1
        GeneratedTokens.SubMode.Editorial.TYPOGRAPHY_HEADING_WEIGHT,                // 2
        GeneratedTokens.COLOR_TEXT_PRIMARY,                                         // 3
        GeneratedTokens.SubMode.Editorial.TYPOGRAPHY_HEADING_WEIGHT,                // 4
        GeneratedTokens.COLOR_KEY_DEFAULT,                                          // 5 (oxblood)
        GeneratedTokens.SubMode.Editorial.TYPOGRAPHY_HEADING_TRACKING,              // 6
        roomName,                                                                   // 7
        GeneratedTokens.COLOR_TEXT_PRIMARY,                                         // 8
        memberCount + "명이 함께 살아남는 중",                                       // 9
        GeneratedTokens.COLOR_TEXT_SECONDARY,                                       // 10
        rulePreview,                                                                // 11
        GeneratedTokens.COLOR_TEXT_SECONDARY                                        // 12
    );
}
```

**(d) Output dimensions:**

- Width 800 × Height 420 (Kakao Custom Feed template `feed` 의 권장 1.91:1 aspect ratio 근사값). KakaoTalk 의 fetcher 가 어떤 정확한 dims 를 요구하는지는 SDK 문서 — 본 스토리는 800×420 으로 lock, Story 6.2 가 SDK 의 `link.imageWidth` / `link.imageHeight` 를 일치시킴.

**(e) Locked brand-voice phrases:**

- `"열살"` — 워드마크.
- `"<N>명이 함께 살아남는 중"` — `함께`, `살아남` 둘 다 USE 어휘 (PRD FR-8.8.2).
- `"같이 살아남자"` — Story 6.2 의 share text 와 동일 잠금 (epics line 864 "같이 살아남자 톤").
- **AVOID lexicon 제로** — `벌금 / 잃었다 / 떨어졌다 / 실패 / 자책 / 부담 / 패배 / 죄책감` (tools/brand-voice-lint.ts:50-59). 위 phrase set 은 정의상 hit 하지 않음.

**Anti-pattern (DO NOT IMPLEMENT):**

- 직접 `#7E2C2A` 같은 hex literal 을 SVG 빌더에 작성 — Checkstyle hex-literal guard 가 컴파일 실패. **`GeneratedTokens.*` constants 만 통과**.
- `escapeXml` 누락 — room name 에 `<`, `>`, `&`, `"`, `'` 가 들어가면 SVG XML 이 invalid 가 됨. 5-char inline replace (`replace("&", "&amp;").replace("<", "&lt;")...`) 가 안전. Apache Commons Text 추가 의존성 도입 금지.
- D2 Bento / D3 Quiet / D4 Postcard / D5 Plate sub-mode 토큰 사용 — D1 Editorial 만 (epics line 828 "sub-mode D1 Editorial overrides applied"; UX line 1070 "Final-3 monthly ceremony, Kakao invite preview card").
- Member nickname 노출 — privacy 보호 + payload 폭증 방지. `<N>명이 함께 살아남는 중` 만 (member count 만 노출).
- Survival state breakdown 노출 (ACTIVE/YELLOW/RED/SPECTATOR 카운트) — privacy 위반 (Story 2.x 의 spectator-mode 24h cooldown 가 broad visibility 를 제한). 본 스토리는 **방 멤버 총수**만.
- Owner / leader 이름 노출 — privacy + leader transfer (Story 5.2) 시 invalidate trigger 가 없으므로 stale 가능. **Owner identity 미노출**.
- `<image href="https://...">` 같은 외부 리소스 — Apache Batik 의 PNG transcoder 가 외부 HTTP fetch 를 시도하다 timeout / SSRF 위험. SVG 는 self-contained text 만.

PRD: FR-8.6.2 (line 415). UX: line 1050-1071 (D1 Editorial sub-mode). Architecture: §4.9 line 296 "Token sourcing — Tokens are NEVER hard-coded in SvgRenderer.java", §4.16 (codegen pipeline). Brand-voice: tools/brand-voice-lint.ts:50-59.

### AC6 — `PngRasterizer` — Apache Batik wrapper (DEPENDENCY ADD)

**Given** `InvitePreviewRenderer` 가 SVG 문자열을 만들었다
**When** PNG 가 필요하다 (Kakao Share SDK 가 PNG 만 받음 — SVG 직접 미지원)
**Then** 새 클래스 `com.yeosal.api.kakaoshare.PngRasterizer` 가 Apache Batik 의 `PNGTranscoder` 를 wrap 한다:

**Build.gradle 의존성 추가 (정확한 좌표):**

```groovy
// BE/build.gradle line 29 의 dependencies { ... } 블록에 두 줄 추가
implementation "org.apache.xmlgraphics:batik-transcoder:1.17"
implementation "org.apache.xmlgraphics:batik-codec:1.17"  // PNG output 지원
```

- Version **1.17** — 2023-12 stable, Java 21 호환 (Maven Central 확인). 1.16 이전은 Java 17 까지만 검증.
- `batik-transcoder` 만 가져오면 `PNGTranscoder` 의 인스턴스화는 가능하지만 `org.apache.batik.transcoder.image.PNGTranscoder` 가 output 시 `batik-codec` 의 PNG writer 를 NoClassDefFoundError 로 잃음 — **둘 다 implementation** 으로 등록.
- Architecture §3.3 line 156 의 표가 batik-transcoder 만 등재 — 본 스토리가 codec 추가의 출처. Architecture 문서 한 줄 follow-up 은 별도 doc PR (non-blocker, AC14).

**`PngRasterizer` 구현:**

```java
@Component
public class PngRasterizer {

    private static final int OUTPUT_WIDTH = 800;
    private static final int OUTPUT_HEIGHT = 420;

    /**
     * Transcodes an SVG document text into PNG bytes. Apache Batik 1.17
     * is single-instance-thread-safe but rasterizing in parallel is
     * acceptable — each call constructs a fresh transcoder.
     */
    public byte[] toPng(String svgText) {
        PNGTranscoder transcoder = new PNGTranscoder();
        transcoder.addTranscodingHint(PNGTranscoder.KEY_WIDTH,  (float) OUTPUT_WIDTH);
        transcoder.addTranscodingHint(PNGTranscoder.KEY_HEIGHT, (float) OUTPUT_HEIGHT);

        TranscoderInput input = new TranscoderInput(new StringReader(svgText));
        ByteArrayOutputStream out = new ByteArrayOutputStream(48 * 1024);
        TranscoderOutput output = new TranscoderOutput(out);

        try {
            transcoder.transcode(input, output);
        } catch (TranscoderException ex) {
            throw new PreviewCardRenderException("SVG → PNG transcode failed", ex);
        }
        return out.toByteArray();
    }
}
```

**Storage of PNG bytes:**

- v1 은 별도 object-store (S3) 가 없음. PNG bytes 를 **로컬 디스크** (`/var/yeosal/preview-cards/${roomId}.png`) 에 쓰고, nginx 가 정적 자산 location 으로 직접 serve.
- nginx 의 `location /preview-cards/` 블록을 추가해 정적 파일 serve. `previewCardImageUrl` 은 AC1 의 endpoint 가 302 redirect → `/preview-cards/${roomId}.png`.
- Dev / docker-compose 에서 `infra/docker-compose.yml` 의 `api` 서비스에 `volumes: ["./preview-cards-cache:/var/yeosal/preview-cards"]` 추가, nginx 에도 같은 volume mount.
- **Path 의 PNG 파일명 = `${roomId}.png`** — `${roomId}_${rendered_at_epoch}.png` 처럼 timestamp 를 붙이지 말 것. invalidate → 새 render 가 같은 파일을 overwrite (atomic move 권장: `Files.move(temp, target, ATOMIC_MOVE)`).

**Cold render 성능 (story-locked p95 < 1s):**

- 첫 render 의 cost = SVG 빌드 (μs) + Batik transcode (PNG 800×420 기준 200-400ms on JVM 21 single thread) + disk write (10ms). 총 p95 < 800ms 의 마진.
- Cold p95 가 1s 를 초과하면 (예: Batik native font lookup 이 처음 호출에서 글꼴 캐시 빌드) — 보조 mitigation 으로 `@PostConstruct` 에서 `PNGTranscoder` 를 한 번 warm-up (Trap #5).

**Anti-pattern (DO NOT IMPLEMENT):**

- `java.awt.image.BufferedImage` + `javax.imageio.ImageIO.write(..., "png", ...)` 로 직접 PNG 빌드 — Batik 의 SVG-to-PNG 가 SVG text/font 렌더링을 처리해주는 핵심. Plain Graphics2D 는 SVG semantic 을 재구현해야 함.
- Batik 의 `SAXSVGDocumentFactory` + `JSVGCanvas` + AWT — desktop GUI 컴포넌트가 headless 서버에서 화면 없이 동작하지 못함. `PNGTranscoder` 만 transcoder 인터페이스로 headless 안전.
- PNG bytes 를 DB (`bytea`) 또는 `room_invite_preview_cache` 의 컬럼에 직접 저장 — V11 (11) 의 schema 는 `png_url` (varchar 512) 만 가짐. 변경 시 V14 migration 필요 → AC0 의 "NO new migration" 잠금 위반. 정적 파일 + URL 만.
- `org.apache.xmlgraphics:fop` 추가 — FOP 는 PDF 출력용, PNG 에 불필요. 의존성 비대화 회피.

PRD: FR-8.6.2 (line 415). Architecture: §3.3 line 156 (Batik 선택 결정). Story 4.3 precedent: PoolStack SVG → PNG 아님 (FE 측 declarative SVG); 본 스토리가 BE-side first SVG-to-PNG.

### AC7 — `RoomService.createInvite` share-payload builder integration (CALL-SITE WIRING)

**Given** `RoomController.createInvite` 가 `RoomService.createInvite(creator, roomId, ttl)` 를 호출한다
**When** `RoomService` 가 `InviteSummary` 를 빌드한다
**Then** `RoomService.createInvite` 가 두 새 의존성을 inject 하고 share-payload 를 빌드한다:

```java
// RoomService field 추가
private final PreviewCardCacheService previewCardCacheService;  // NEW Story 6.1 — invalidate hook
private final ShareUrlBuilder shareUrlBuilder;                  // NEW Story 6.1 — URL builder

// constructor — 마지막 두 인자 append (mid-list 삽입 금지, Story 5.4 Trap #4)

@Transactional
public InviteSummary createInvite(User creator, long roomId, Duration ttl) {
    Room room = requireRoom(roomId);
    requireMembership(room, creator);

    String code = codeGenerator.generate(roomInvites::existsByCodeAndRevokedAtIsNull);
    Instant expiresAt = ttl == null ? null : clock.instant().plus(ttl);
    RoomInvite saved = roomInvites.save(new RoomInvite(room, code, creator, expiresAt));

    // Story 6.1 — share payload (URL builders are stateless, no I/O here)
    String kakaoShareUrl       = shareUrlBuilder.kakaoShareUrl(saved);
    String previewCardImageUrl = shareUrlBuilder.previewCardImageUrl(saved.getRoom().getId());

    return InviteSummary.from(saved, kakaoShareUrl, previewCardImageUrl);
}
```

**`ShareUrlBuilder` 구현 (new component):**

```java
@Component
public class ShareUrlBuilder {

    private final String deeplinkBase;
    private final String previewCardBase;

    public ShareUrlBuilder(
            @Value("${yeosal.share.deeplink-base:https://yeolsal.app}") String deeplinkBase,
            @Value("${yeosal.share.preview-card-base:https://api.rearleg.com/yeolsal}") String previewCardBase) {
        // Strip trailing slashes once at construction — defensive
        this.deeplinkBase    = stripTrailingSlash(deeplinkBase);
        this.previewCardBase = stripTrailingSlash(previewCardBase);
    }

    public String kakaoShareUrl(RoomInvite invite) {
        return deeplinkBase + "/join?code=" + invite.getCode();
    }

    public String previewCardImageUrl(long roomId) {
        return previewCardBase + "/api/v1/rooms/" + roomId + "/invites/preview-card";
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
```

**`application.yml` 추가 (2 줄, env-overridable):**

```yaml
yeosal:
  share:
    deeplink-base: ${YEOSAL_SHARE_DEEPLINK_BASE:https://yeolsal.app}
    preview-card-base: ${YEOSAL_SHARE_PREVIEW_CARD_BASE:https://api.rearleg.com/yeolsal}
```

위치는 `yeosal.kakao` 블록 (application.yml line 25-28) 다음. **prod env 에서 두 값을 override** — RUNBOOK 한 줄 (Story 6.3 scope, 본 스토리는 application.yml + env-var 만 추가).

**Anti-pattern (DO NOT IMPLEMENT):**

- `RoomService` 가 `PreviewCardCacheService` 의 메서드를 직접 호출 (e.g., `cacheService.warmUp(roomId)`) — invite 생성 자체는 cache 를 건드릴 필요 없음. cache 의 warm-up 은 KakaoTalk 의 첫 fetch 가 자연스럽게 trigger (cold-miss path). Pre-warm 은 over-engineering.
- `ShareUrlBuilder` 를 static utility class 로 — `@Value` injection 이 안 됨. `@Component` 로 유지.
- URL 빌더를 `RoomService` 내부 private method 로 — single-responsibility 위반 + 미래 deeplink format 변경 시 사이트 분산. 별도 `@Component` 가 testable 한 단위.
- `kakaoShareUrl` 안에 fragment (`#`) 또는 추가 쿼리 (`?code=...&room=...`) — `code` 만으로 충분 (BE 가 `code` 로 room 을 resolve). 추가 쿼리는 wire-breaking.

PRD: FR-8.6.1 (line 414). Architecture: §3.1 line 138 (constructor injection only).

### AC8 — `formatRulePreview` shared helper extraction (REFACTOR — Story 5.4 와의 통합)

**Given** Story 5.4 가 `ChatService` 안에 `private static String formatRulePreview(String preset, boolean weekendInclude)` 를 보유한다 (`BE/src/main/java/com/yeosal/api/room/chat/ChatService.java`, Story 5.4 AC1)
**When** Story 6.1 의 `InvitePreviewRenderer` 도 같은 phrase set 을 필요로 한다 (`매일 업데이트, 주말 포함/제외`)
**Then** **두 callsite 가 분명히 발생하는 시점**의 cross-module abstraction 라인을 처음으로 넘는다:

1. `formatRulePreview` 를 **`com.yeosal.api.survival.RulePresetPreview`** 클래스의 `public static` 유틸로 이동 (survival module — preset 정의의 출처가 `RulePresetEvaluator` 이고, preset 라벨 매핑이 같은 도메인).

```java
package com.yeosal.api.survival;

/**
 * Rule preset display formatter. Single source of truth for the human-
 * readable preview phrase shared by:
 * <ul>
 *   <li>Story 5.4 — chat broadcast body suffix (ChatService.publishRuleChangeSystemMessage)</li>
 *   <li>Story 6.1 — Kakao invite preview card SVG (InvitePreviewRenderer)</li>
 * </ul>
 *
 * <p>Adding a third preset MUST extend both the {@link RulePresetEvaluator}
 * switch and this map atomically. The {@code else} fallback returns the raw
 * preset enum string so a missed extension is loudly visible in chat / SVG
 * instead of silently rendering a blank.
 */
public final class RulePresetPreview {
    private RulePresetPreview() {}

    public static String format(String preset, boolean weekendInclude) {
        String presetLabel = "DAILY_UPDATE".equals(preset) ? "매일 업데이트" : preset;
        String weekendPhrase = weekendInclude ? "주말 포함" : "주말 제외";
        return presetLabel + ", " + weekendPhrase;
    }
}
```

2. **`ChatService.formatRulePreview` 를 삭제** — `publishRuleChangeSystemMessage` 안의 호출을 `RulePresetPreview.format(...)` 으로 교체. Story 5.4 의 unit test `ChatServiceRuleChangeTest` 가 같은 결과를 assert 하므로 GREEN 유지.

3. **`InvitePreviewRenderer.render` 안에서도 같은 클래스 호출** — `rulePreview = RulePresetPreview.format(preset, weekendInclude)`.

**Why now (2-callsite 통합 시점):**

- Story 5.4 Trap #4 가 명시: "single-class scope, 추출하지 않음 — YAGNI: 같은 패턴을 가진 호출자가 셋 이상 생기기 전까지는 cross-module abstraction 금지". 본 스토리는 정확히 **2번째 callsite** — Story 5.4 의 형식주의를 한 박자 빨리 깬다.
- **이유:** 만약 두 사본을 두면 wording 표류 위험이 크다 (`InvitePreviewRenderer` 와 `ChatService` 가 같은 phrase 를 두 번 정의). 미래 i18n 작업이 한 쪽만 변경하면 chat 본문과 SVG 가 갈라짐. **단일 source of truth** 가 한 helper 메서드의 작은 utility class 보다 가치가 큼.
- **결정 근거:** Story 5.4 의 3-callsite rule 은 *Spring service / @Transactional 헬퍼* (`publishAfterCommit`) 에 한정된 정당화. 순수 static formatter 는 부수효과 / DI 가 없어 추출 비용 ≈ 0.

**Anti-pattern (DO NOT IMPLEMENT):**

- 5.4 의 `formatRulePreview` 를 그대로 두고 **6.1 가 자체 사본**을 만드는 패턴 — wording 표류 위험. 본 스토리 AC8 가 그 위험을 사전 차단.
- 추출 대상 클래스 위치를 `chat` 또는 `kakaoshare` 로 — `RulePresetEvaluator` 가 preset 의 출처이고 같은 패키지 (`survival`) 에 두는 게 단방향 의존 (chat / kakaoshare → survival) 을 보존.
- helper 를 `interface RuleDisplayFormatter` + `@Component` Spring bean — preset → label 매핑은 stateless pure function. static utility 로 충분. Spring DI 가 필요한 시점은 i18n locale-aware formatter 가 들어올 때 (v1 KR-only, NFR-9.7.1).

PRD: FR-8.5.8 (Story 5.4 lineage), FR-8.6.2 (본 스토리). Architecture: §5.1 (constructor injection, but utility classes are exempt). Story 5.4 file: `_bmad-output/implementation-artifacts/5-4-rule-change-broadcast-in-chat.md:60-65, 511-512`.

### AC9 — File / scope fence (LOCKED ALLOW LIST)

**Given** Story 6.1 의 diff 가 review 단계에 들어간다
**When** `git diff --stat origin/main` 가 실행된다
**Then** 변경된 파일은 **정확히 다음 allow list** 에만 머무른다:

**MODIFIED (existing files):**

- `BE/src/main/java/com/yeosal/api/room/RoomController.java` — `createInvite` 응답 타입 변경 ZERO (RoomService.InviteSummary 가 record 확장).
- `BE/src/main/java/com/yeosal/api/room/RoomService.java` — `createInvite` 의 share-payload builder 호출, `joinByCode` / `leave` 의 invalidate hook, `publishAfterCommit` helper 추가, constructor 2-arg widening, `InviteSummary` record 확장 + `from` 시그니처 변경.
- `BE/src/main/java/com/yeosal/api/survival/RoomRuleService.java` — Story 5.4 의 `publishAfterCommit` 람다 옆에 invalidate 람다 1개 추가, constructor 1-arg widening (`PreviewCardCacheService`).
- `BE/src/main/java/com/yeosal/api/room/chat/ChatService.java` — Story 5.4 의 `formatRulePreview` 호출을 `RulePresetPreview.format` 으로 교체, 본인 helper 삭제 (AC8).
- `BE/src/main/java/com/yeosal/api/common/SecurityConfig.java` — 한 줄 추가 (preview-card GET permitAll, AC2).
- `BE/src/main/java/com/yeosal/api/common/ApiExceptionHandler.java` — `ServiceUnavailableException` 매핑 1 메서드 추가 (503), `PreviewCardRenderException` 매핑 1 메서드 추가 (500 with logged context).
- `BE/build.gradle` — Apache Batik 두 라인 추가 (transcoder + codec).
- `BE/src/main/resources/application.yml` — `yeosal.share` 블록 2 줄 추가.
- `BE/src/main/java/com/yeosal/api/YeosalApiApplication.java` — `@EnableAsync` 추가 (background render 용 `@Async`, AC3). 만약 이미 다른 모듈이 enable 한 상태면 skip.
- `infra/docker-compose.yml` — `api` + `nginx` 서비스에 `preview-cards-cache` volume 추가.
- `infra/nginx/default.conf` — `location /preview-cards/` 정적 자산 블록 추가.

**NEW (untracked files):**

- `BE/src/main/java/com/yeosal/api/kakaoshare/PreviewCardController.java`
- `BE/src/main/java/com/yeosal/api/kakaoshare/PreviewCardCacheService.java`
- `BE/src/main/java/com/yeosal/api/kakaoshare/PreviewCardBackgroundRenderer.java` — Trap #3 의 proxy-boundary wrapper.
- `BE/src/main/java/com/yeosal/api/kakaoshare/PreviewCardCache.java` — `@Entity @Table(name = "room_invite_preview_cache")`.
- `BE/src/main/java/com/yeosal/api/kakaoshare/PreviewCardCacheRepository.java`
- `BE/src/main/java/com/yeosal/api/kakaoshare/InvitePreviewRenderer.java`
- `BE/src/main/java/com/yeosal/api/kakaoshare/PngRasterizer.java`
- `BE/src/main/java/com/yeosal/api/kakaoshare/ShareUrlBuilder.java`
- `BE/src/main/java/com/yeosal/api/kakaoshare/PreviewCardRenderException.java`
- `BE/src/main/java/com/yeosal/api/common/ServiceUnavailableException.java` — common 으로 위치 (다른 도메인의 503 도 재사용 여지).
- `BE/src/main/java/com/yeosal/api/kakaoshare/PreviewCardRenderExecutorConfig.java` — `@Bean TaskExecutor previewCardRenderExecutor` (Story 6.1 만의 격리된 executor pool, 2-thread fixed).
- `BE/src/main/java/com/yeosal/api/survival/RulePresetPreview.java` — AC8 의 공유 helper.
- `BE/src/test/java/com/yeosal/api/kakaoshare/PreviewCardCacheServiceTest.java` — 8+ unit cases.
- `BE/src/test/java/com/yeosal/api/kakaoshare/InvitePreviewRendererTest.java` — 6+ unit cases (SVG output contract).
- `BE/src/test/java/com/yeosal/api/kakaoshare/PngRasterizerTest.java` — 2 cases (transcoder smoke + byte size sanity).
- `BE/src/test/java/com/yeosal/api/kakaoshare/PreviewCardControllerTest.java` — 5 cases (cache hit / stale / cold / room-missing / lock contention).
- `BE/src/test/java/com/yeosal/api/kakaoshare/ShareUrlBuilderTest.java` — 4 cases (default base / trailing slash / env override / special-char code).
- `BE/src/test/java/com/yeosal/api/survival/RulePresetPreviewTest.java` — 3 cases (weekend include / exclude / unknown preset fallback).
- `BE/src/test/java/com/yeosal/api/kakaoshare/PreviewCardEndToEndIT.java` — opt-in `@SpringBootTest @Testcontainers` + JDBC + Batik. 3 cases (cold render → row inserted → GET 302; rule change → invalidate; member join → invalidate).

**BANNED-PATHS (must be ZERO diff):**

- `BE/src/main/resources/db/migration/**` — NO new migration (V11 (11) 가 이미 cache table 보유, AC0).
- `FE/src/**`, `FE/app/**` — FE 변경 ZERO (Story 6.2 scope).
- `BE/src/main/java/com/yeosal/api/notification/**` — push notification ZERO (Epic 6 미명시, OOS).
- `BE/src/main/java/com/yeosal/api/realtime/**` — preview card 가 STOMP 채널을 사용하지 않음.
- `FE/src/theme/tokens.json` — D1 Editorial override map 변경 ZERO (이미 lock, AC5).

**Verification command:**

```bash
git diff --stat origin/main -- \
  BE/src/main/resources/db/migration/ \
  FE/src/ FE/app/ \
  BE/src/main/java/com/yeosal/api/notification/ \
  BE/src/main/java/com/yeosal/api/realtime/ \
  FE/src/theme/tokens.json
# Expected: empty output.
```

### AC10 — Brand-voice + Checkstyle gates (LOCKED)

**Given** Story 6.1 의 diff 가 build pipeline 을 통과해야 한다
**When** lint / Checkstyle 게이트가 실행된다
**Then** 다음 두 조건이 동시에 만족된다:

1. **Brand-voice lint** (`tools/brand-voice-lint.ts`) — Story 5.4 의 baseline 인 0 HARD / 198 warnings 를 유지. BE-only 변경이므로 FE-scoped lint 는 vacuous pass. 만약 future-Story 6.2 가 FE 의 share button copy 를 추가하면 그 스토리가 자체 baseline 책임.
2. **Checkstyle hex-literal guard** (`BE/build.gradle:285-298`) — `InvitePreviewRenderer.java` 가 `GeneratedTokens.SubMode.Editorial.*` constants 만 참조 → guard 통과. **만약 D1 Editorial override 가 필요한 color token 을 노출하지 않는다면, base GeneratedTokens 상수로 fallback** — Trap #9 참조. 본 스토리의 happy path 는 현 `GeneratedTokens.SubMode.Editorial.*` 가 충분한 가정.

**Test 가 brand-voice 보조 게이트:**

- `InvitePreviewRendererTest.svgAvoidsBannedLexicon` — SVG 텍스트에 AVOID 8 단어가 zero 임을 assert (Story 3.5 KudosService + Story 5.4 ChatServiceRuleChangeTest 패턴 mirror).
- `InvitePreviewRendererTest.svgUsesBrandLockedPhrases` — `함께`, `같이 살아남자`, `살아남는 중` literal 셋의 occurrence 검증.

### AC11 — Test matrix (NET-ADDITIVE, RED → GREEN order)

**Given** TDD enforced (project-context.md:145, "RED → GREEN → refactor")
**When** Story 6.1 의 test suite 가 작성된다
**Then** 다음 새 테스트 케이스가 정확히 add 된다 — **기존 테스트는 fixture widening 외 byte-identical**:

**Unit tests (new files):**

| File | Cases | Coverage |
|------|-------|----------|
| `PreviewCardCacheServiceTest` | 8 | resolve: fresh hit, stale → background kick, cold synchronous, room-not-found Empty, advisory-lock contention → ServiceUnavailable, invalidate: delete row idempotent, async render swallows RuntimeException, cache TTL boundary (rendered_at + 1h - 1ms vs + 1h + 1ms) |
| `InvitePreviewRendererTest` | 6 | SVG well-formed XML, GeneratedTokens constants present, no hex literal in output, room name XML-escape, brand-voice avoid-lexicon zero, brand-voice locked-phrases present |
| `PngRasterizerTest` | 2 | minimal valid SVG → non-empty PNG bytes, PNG magic header `89 50 4E 47` |
| `PreviewCardControllerTest` (WebMvc slice) | 5 | 302 with Location + Cache-Control on hit, 302 with stale URL on stale, 503 + Retry-After on lock contention, 503 + Retry-After on missing room, no JWT required (anonymous access) |
| `ShareUrlBuilderTest` | 4 | default base produces `https://yeolsal.app/join?code=ABC` and `https://api.rearleg.com/yeolsal/api/v1/rooms/1/invites/preview-card`, trailing-slash strip in constructor, env-override changes both bases, code with special chars (defensive — codeGenerator 의 alphanum 가정 이외 edge) |
| `RulePresetPreviewTest` | 3 | DAILY_UPDATE + true → "매일 업데이트, 주말 포함", DAILY_UPDATE + false → "매일 업데이트, 주말 제외", unknown preset → preset literal forwarded |

**Existing test fixture widening (existing files, 1-2 lines each):**

- `RoomServiceTest` — constructor `new RoomService(...)` 의 마지막 두 인자에 `mock(PreviewCardCacheService.class)`, `mock(ShareUrlBuilder.class)` append. `createInvite` 케이스 1-2개에서 `InviteSummary.kakaoShareUrl()` / `.previewCardImageUrl()` assert 추가.
- `RoomRuleServiceTest` — constructor 의 8번째 인자 추가 (Story 5.4 가 7번째까지 widening, 본 스토리가 8번째). 새 케이스 1개: `updateRule_invalidatesPreviewCardAfterCommit`.
- `RoomControllerTest` (WebMvc slice) — `@MockBean PreviewCardCacheService` + `@MockBean ShareUrlBuilder` 한 줄씩 추가. 기존 `createInvite` 케이스 응답 JSON path 어설션 두 줄 (`$.data.kakaoShareUrl`, `$.data.previewCardImageUrl`) 추가.
- `ChatServiceRuleChangeTest` (Story 5.4 의 7-case 유닛) — `formatRulePreview` 가 더 이상 `ChatService` private member 가 아니므로, test 가 helper 를 직접 호출하지 않고 `publishRuleChangeSystemMessage` 의 결과 body 만 assert (이미 그렇게 작성되어 있어 byte-identical 유지).

**Opt-in IT (new file, `@EnabledIfSystemProperty(named="yeosal.boot-smoke",matches="true")`):**

- `PreviewCardEndToEndIT` — 3 cases:
  1. `freshInvite_returnsSharePayload_with302PointingToFreshlyRenderedPng` — POST invite → assert response has 3 new fields → GET preview-card → 302 with Location header → fetch Location returns PNG (magic header check).
  2. `ruleEdit_invalidatesPreviewCache_nextGetTriggersFreshRender` — POST invite → GET preview-card (cold render → cache row exists) → PATCH rule → wait afterCommit → assert cache row deleted; next GET re-renders.
  3. `memberJoin_invalidatesPreviewCache` — 동일 패턴 with `joinByCode`.

**Why opt-in IT only:**

- Docker 가 dev host 에서 종종 unavailable (Story 5.1 / 5.2 / 5.3 / 5.4 의 동일 deferral). PR-CI 에서 `-Dyeosal.boot-smoke=true` flag 가 켜져 자동 실행.

**TDD execution order (per file):**

1. RED — new test file 생성, 컴파일 실패 (target 클래스 / 메서드 미존재).
2. GREEN — production class 의 최소 구현, test 통과.
3. Refactor — 명료성 (helper 추출, 명명).

**Gradle command:**

```bash
cd BE && ./gradlew test --tests "*kakaoshare*" --tests "*RulePresetPreview*"
# 후속:
cd BE && ./gradlew test    # 전체 BE 스위트 GREEN (574 existing + ~31 new = ~605 cases)
```

### AC12 — Verification matrix (gate before sprint-status flip)

**Given** Dev 가 모든 AC 를 구현했다
**When** Story 6.1 가 review 로 진입한다
**Then** 다음 12 게이트가 모두 GREEN:

| # | Gate | Command | Expected |
|---|------|---------|----------|
| 1 | BE Gradle full suite | `cd BE && ./gradlew test` | BUILD SUCCESSFUL + (574+~31) tests GREEN |
| 2 | Checkstyle | `cd BE && ./gradlew checkstyleMain` | Zero hex-literal violations |
| 3 | Token codegen | `cd BE && ./gradlew validateTokens generateTokens` | tokens.json schema OK + GeneratedTokens.java rebuilt |
| 4 | Brand-voice lint | `cd FE && npm run brand-voice-lint` | 0 HARD / ≤198 warnings (baseline preserved) |
| 5 | FE typecheck baseline | `cd FE && npm run typecheck` | Same 2 FriendsTodayPager errors (Story 5.4 baseline), no new |
| 6 | FE Jest | `cd FE && npm test -- --watchAll=false` | All existing tests GREEN, Δ=0 |
| 7 | Scope fence | `git diff --stat origin/main -- <banned-paths>` (AC9) | empty output |
| 8 | Diff sanity | `git diff --check HEAD` | clean |
| 9 | File list match | `git diff --name-only origin/main \| sort` | matches AC9 allow list |
| 10 | Opt-in IT compile | `cd BE && ./gradlew compileTestJava` | BUILD SUCCESSFUL (IT class compiles even when Docker absent) |
| 11 | Manual VERIFY-A | POST `/rooms/{id}/invites` via curl with valid JWT | response JSON contains 3 keys: code/kakaoShareUrl/previewCardImageUrl |
| 12 | Manual VERIFY-B | GET preview card URL from VERIFY-A response (no auth) | 302 → fetched PNG is well-formed (`file output.png` reports `PNG image data, 800 x 420`) |

VERIFY-A / VERIFY-B 의 manual smoke 는 Docker dev stack 이 살아있을 때만 수행. Dev host 에서 unavailable 면 PR-open 시 review reviewer 가 수행 (Story 5.1 / 5.2 / 5.3 / 5.4 의 동일 패턴).

### AC13 — Post-merge user action (RUNBOOK note)

**Given** 본 PR 이 main 에 머지된다
**When** prod 배포가 일어난다
**Then** PR description 의 "Post-merge user action" 섹션에 다음 두 줄을 포함 (project-context.md:229 "Any change with significant operational impact must include a Post-merge user action section"):

```
- nginx 의 `/preview-cards/` location 블록이 새로 추가됨 — `infra/nginx/default.conf` 의 변경이 적용되려면 nginx 컨테이너 재시작 필요. `docker compose restart nginx` 또는 blue-green deploy 시 nginx side 의 graceful reload.
- `preview-cards-cache` 볼륨 디렉토리가 신규 — prod host 에 `/var/yeosal/preview-cards` 디렉토리 (또는 docker volume) 가 mount 되어야 함. mount 누락 시 첫 render 가 FileNotFoundException 으로 실패하지만 service 는 503 으로 graceful (AC2).
- (optional) YEOSAL_SHARE_DEEPLINK_BASE / YEOSAL_SHARE_PREVIEW_CARD_BASE env-var 가 default 값으로 충분한지 확인. default 는 https://yeolsal.app / https://api.rearleg.com/yeolsal — prod 도메인 변경 시 override 필요.
```

본 스토리는 V11 (11) cache table 을 *사용* 만 함 (이미 schema 존재), schema 변경 ZERO, NotNullSetter / partial unique index 추가 ZERO. **Migration 측면의 post-merge action 없음.**

### AC14 — Architecture deviation notes (DOC FOLLOW-UP, NON-BLOCKER)

**Given** 본 스토리의 구현이 architecture 문서와 다음 두 부분이 어긋난다
**When** PR description 또는 architecture 문서 PR 이 작성된다
**Then** 명시:

1. **Architecture §3.3 의 의존성 표** (architecture.md:154-156) 가 `batik-transcoder` 만 등재 — 본 스토리가 `batik-codec` 도 함께 등재해야 PNG output 이 가능. **Doc follow-up PR**: line 156 의 행에 "+ `batik-codec` for PNG writer (NoClassDefFoundError without it on JVM 21)" 한 줄 추가. 비-블로커.
2. **Architecture §6.1 의 `kakaoshare/` 모듈 outline** (architecture.md:588-592) 가 `PreviewCardController` / `PreviewCardCacheService` / `PreviewCardCacheRepository` / `PreviewCardCache` 4 클래스만 enumerate — 본 스토리가 추가로 `InvitePreviewRenderer`, `PngRasterizer`, `ShareUrlBuilder`, `PreviewCardRenderException`, `PreviewCardRenderExecutorConfig`, `PreviewCardBackgroundRenderer` 6 클래스를 도입. **Doc follow-up PR**: §6.1 의 kakaoshare 블록을 확장. 비-블로커.
3. **Architecture §597 의 sealed `RealtimeEvent` variant 표** 가 본 스토리에 영향받지 않음 — preview card 는 realtime 채널을 사용하지 않으므로 sealed variant 추가 / 변경 ZERO. (Story 5.4 도 같은 이유로 `RuleChange` variant 를 추가하지 않았음, 본 스토리도 동일 정신.)

### AC15 — Sentry / observability hook (LIGHT-TOUCH)

**Given** Story 5.4 는 Sentry 별도 wiring 미작성 (BE only, ApiExceptionHandler 가 5xx Sentry 자동 캐치)
**When** 본 스토리의 cold-render 가 p95 > 1s 로 분명히 느려지면 운영자가 알 수 있어야 한다
**Then** **추가 wiring ZERO** — Sentry 의 기본 자동 instrumentation 이 controller 응답 시간을 측정. `PreviewCardRenderException` 가 RuntimeException 으로 ApiExceptionHandler 의 5xx 채널에 자동 포함. 별도 `Sentry.captureException(...)` 호출 없음 (NFR-9.4.1 가 "KakaoTalk SDK invite link generation" 을 instrument 대상으로 enumerate 하지만, 본 스토리는 SDK 호출이 아닌 BE 측 렌더이고, controller-level transaction 이 충분).

**비-목표:** Custom Sentry breadcrumb / measurement 추가. 추후 cold-render p95 가 실제로 SLA 를 위반하면 별도 story 가 instrumentation 깊이를 결정.

### AC16 — Sprint-status transitions

**Given** 본 스토리가 ready-for-dev → in-progress → review → done 사이클을 돈다
**When** sprint-status.yaml 이 업데이트된다
**Then** transitions:

1. **create-story (본 워크플로우)** — `epic-6: backlog → in-progress`, `6-1-server-side-preview-card-renderer-cache: backlog → ready-for-dev`. (Story 6.1 가 Epic 6 의 첫 backlog → epic 자동 flip.)
2. **dev-story 시작** — `6-1-...: ready-for-dev → in-progress`.
3. **dev-story 완료** — `6-1-...: in-progress → review`.
4. **code-review 완료** — `6-1-...: review → done`. 본 스토리가 epic-6 의 첫 done. epic-6 자체는 `in-progress` 유지 (6-2 / 6-3 backlog 잔존).

## Tasks / Subtasks

- [x] **Task 1 — RED phase setup** (AC: 11)
  - [x] Test file `BE/src/test/java/com/yeosal/api/kakaoshare/PreviewCardCacheServiceTest.java` 생성, 8 cases.
  - [x] `InvitePreviewRendererTest`, `PngRasterizerTest`, `PreviewCardControllerTest`, `ShareUrlBuilderTest`, `RulePresetPreviewTest` 작성.
  - [x] `PreviewCardEndToEndIT` (opt-in) 작성 — `@EnabledIfSystemProperty(named="yeosal.boot-smoke")` 로 dev test 사이클 제외.
- [x] **Task 2 — Production code: `kakaoshare/` module foundations** (AC: 2, 3, 5, 6)
  - [x] `PreviewCardCache` entity (`@Entity @Table(name = "room_invite_preview_cache")`, 기존 V11 컬럼 매핑).
  - [x] `PreviewCardCacheRepository extends JpaRepository<PreviewCardCache, Long>`.
  - [x] `InvitePreviewRenderer` — D1 Editorial constants only, SVG text builder, 5-char XML-escape.
  - [x] `PngRasterizer` — Apache Batik wrapper, 800×420 + `@PostConstruct` warm-up (trap #5).
  - [x] `PreviewCardCacheService` — `resolve`, `synchronousRender`, `backgroundRenderUnchecked`, `invalidate`, advisory lock helper, atomic PNG write (trap #10).
  - [x] `PreviewCardBackgroundRenderer` — `@Async("previewCardRenderExecutor")` proxy boundary (trap #3).
  - [x] `PreviewCardController` — 302 / 503 분기.
  - [x] `ShareUrlBuilder` — `@Value` env-bound base URLs + trailing-slash strip.
  - [x] `PreviewCardRenderException`, `common/ServiceUnavailableException`.
  - [x] `PreviewCardRenderExecutorConfig` — `@Bean TaskExecutor previewCardRenderExecutor(2 threads)`.
  - [x] `YeosalApiApplication` 에 `@EnableAsync` 추가.
- [x] **Task 3 — Shared helper extraction (AC8)** (AC: 8)
  - [x] `RulePresetPreview.format` 새 utility (`com.yeosal.api.survival`).
  - [x] `ChatService.formatRulePreview` 삭제, `publishRuleChangeSystemMessage` 안의 호출 교체.
  - [x] `ChatServiceRuleChangeTest` GREEN 유지 (byte-identical).
- [x] **Task 4 — `RoomService` integration** (AC: 1, 7)
  - [x] `InviteSummary` record 2 필드 확장, 3-arg `from(invite, kakaoShareUrl, previewCardImageUrl)` 시그너처 변경, 1-arg `from` 삭제.
  - [x] `RoomService` constructor 2-arg widening + private `publishAfterCommit` helper 복사 (3rd byte-identical site).
  - [x] `createInvite` 가 `ShareUrlBuilder` 호출 후 새 3-arg `from` 으로 빌드.
  - [x] `joinByCode` afterCommit invalidate hook.
  - [x] `leave` afterCommit invalidate hook (non-owner path; owner-disband cascade에 의존).
  - [x] `RoomServiceTest` fixture widening + `createInvitePersistsCode` kakaoShareUrl/previewCardImageUrl 어설션.
  - [x] `RoomServiceEvaluationTest` + `RoomServiceMemberJoinSystemMessageTest` fixture widening (21-arg ctor).
- [x] **Task 5 — `RoomRuleService` integration** (AC: 4, 8)
  - [x] Constructor 1-arg widening (`PreviewCardCacheService`).
  - [x] `updateRule` 의 기존 chat broadcast `publishAfterCommit` 람다 옆에 `publishAfterCommit(() -> previewCardCacheService.invalidate(roomId))` 람다 추가.
  - [x] `RoomRuleServiceTest` 의 ctor fixture 8-arg widening + 2 새 케이스 (`updateRule_invalidatesPreviewCardAfterCommit`, `updateRule_swallowsPreviewCardInvalidateFailure`).
- [x] **Task 6 — Cross-cutting: SecurityConfig, ApiExceptionHandler, build.gradle, application.yml** (AC: 2, 6, 7)
  - [x] `SecurityConfig` 의 `requestMatchers(HttpMethod.GET, "/api/v1/rooms/*/invites/preview-card").permitAll()` 한 줄.
  - [x] `ApiExceptionHandler` 의 `ServiceUnavailableException` (503 + Retry-After:5) + `PreviewCardRenderException` (500 with `[kakaoshare]` log prefix) 매핑 2 메서드.
  - [x] `build.gradle` 의존성 두 줄 (batik-transcoder 1.17 + batik-codec 1.17).
  - [x] `application.yml` 의 `yeosal.share` 블록 3 줄 (`deeplink-base`, `preview-card-base`, `preview-cards-dir`).
- [x] **Task 7 — Infra: docker-compose + nginx** (AC: 6, 13)
  - [x] `infra/docker-compose.yml` 의 `api` + `nginx` 서비스에 `preview-cards-cache` named volume 마운트 + 3개 env-var forward.
  - [x] `infra/nginx/default.conf` 의 `location /preview-cards/` 블록 — `alias /var/yeosal/preview-cards/;`, `expires 1h;`, `add_header Cache-Control "public, max-age=3600";`, `try_files $uri =404;`.
- [x] **Task 8 — Run tests + lint + scope-fence verification** (AC: 9, 10, 11, 12)
  - [x] `cd BE && ./gradlew test` GREEN — **603 tests** (574 + 29 net-additive).
  - [x] `cd BE && ./gradlew checkstyleMain` GREEN (hex-literal guard 통과).
  - [x] `cd BE && ./gradlew validateTokens generateTokens` GREEN (tokens schema OK, 119 base + 5 sub-mode emitted).
  - [x] `cd FE && npm run brand-voice-lint` — vacuous pass (FE source Δ=0).
  - [x] `cd FE && npm run typecheck && npm test` — FE source Δ=0 → baseline 보존.
  - [x] `git diff --stat origin/main -- <banned-paths>` 빈 출력 (검증 명령 실행 OK).
  - [x] `git diff --check HEAD` clean.
- [x] **Task 9 — Manual VERIFY-A/B + status flip** (AC: 12, 16)
  - [x] Docker dev stack 미가용 → VERIFY-A/B PR-open reviewer 에게 deferred (Story 5.1/5.2/5.3/5.4 precedent).
  - [x] sprint-status.yaml: `6-1-...: ready-for-dev → in-progress → review`. last_updated = 2026-06-04 (already current).

### Review Findings

- [ ] [Review][Patch] Break the `PreviewCardCacheService` ↔ `PreviewCardBackgroundRenderer` constructor cycle before boot smoke / production startup [BE/src/main/java/com/yeosal/api/kakaoshare/PreviewCardCacheService.java:68]
- [ ] [Review][Patch] Align the rendered PNG redirect URL with the nginx static location; the default `/yeolsal` preview-card base currently produces `/yeolsal/preview-cards/{id}.png`, while nginx only serves `/preview-cards/` [BE/src/main/java/com/yeosal/api/kakaoshare/PreviewCardCacheService.java:164]

## Dev Notes

### Context — what V11 + Story 1.5 + Story 5.4 가 이미 ship 한 것 (Story 6.1 의 기반)

**V11 migration (shipped Story 1.4, PR #55+#57 merged 2026-05-13):**
- `room_invite_preview_cache` table (V11 (11), line 132-139 of `V11__survival_revival_economy.sql`). PK `(room_id)` + FK ON DELETE CASCADE on `rooms`. 컬럼 4개: `png_url varchar(512) not null`, `rendered_at timestamptz`, `rule_version_id bigint references room_rule_versions(id)`, `member_count_at_render smallint`.
- **본 스토리는 schema 변경 ZERO** — JPA `@Entity` 매핑만 새로 작성.

**Story 1.5 (Design System Foundation v2, PR #58~#62 merged 2026-05-13):**
- `FE/src/theme/tokens.json` — canonical token source. D1 Editorial sub-mode override map 이 활성 (UX line 1050-1071 lock).
- `BE/build.gradle` 의 `generateTokens` task — `tokens.json` → `GeneratedTokens.java` static class 생성. `GeneratedTokens.SubMode.Editorial.*` constants 가 본 스토리의 `InvitePreviewRenderer` 가 참조하는 entry point.
- `Checkstyle hex-literal guard` (build.gradle:285-298) — BE Java code 에 직접 hex literal 금지, `GeneratedTokens.java` 만 제외. 본 스토리의 SVG 빌더가 이 게이트를 만족해야 컴파일됨.

**Story 5.4 (chat broadcast for rule change, current branch HEAD `e97c528`):**
- `RoomRuleService.updateRule` 에 `publishAfterCommit` helper (Story 5.4 가 `RoomRuleService.java:116-127` 에 추가) 가 존재. 본 스토리가 람다 한 개 더 등록.
- `formatRulePreview` (ChatService 안의 private static) — Story 5.4 가 잠근 phrase set. 본 스토리가 cross-callsite 통합 (AC8).
- afterCommit defer 패턴 — outer rollback / inner txn / try-catch RuntimeException 의 정형. 본 스토리의 invalidate hook 이 byte-similar.

**Story 4.3 (PoolStack SVG, PR #85 merged 2026-06-02):**
- FE-side declarative SVG (React Native `react-native-svg` 의 `<Svg>` runtime). 본 스토리는 BE-side server-rendered SVG 라 직접 코드 공유는 없지만, **token consumption 패턴**은 동일: D1 sub-mode constants 만 참조, 직접 hex 금지.
- AC5 ember.default 1.95:1 contrast finding (Story 4.3) — Stage5 keystone 이 ember.subtle (3.03:1) 로 substitute. 본 스토리의 preview card 도 같은 contrast 게이트를 만족해야 함 (text on bg AA 4.5:1, large heading AA 3:1).

**Architecture §4.16 (FE→BE codegen, locked 2026-05-10):**
- `tokens.json` 이 canonical, `GeneratedTokens` 가 BE consumer. 본 스토리가 *처음* 으로 BE-side SVG renderer 가 sub-mode constants 를 실제로 사용 → 만약 GeneratedTokens 가 D1 Editorial override map 의 일부를 누락하고 있다면 Trap #9 path 로 진입.

### Implementation trap #1 — Advisory lock 의 hash 충돌 + namespace 분리

`pg_advisory_xact_lock(key bigint)` 의 단일 인자 버전은 전체 BIGINT 키 공간이 공유됨. Story 3.x revival flow 가 `pg_advisory_xact_lock(hash(room_id, user_id, eliminated_at))` 를 쓰면, 같은 hash 가 우연히 preview-card render key 와 충돌하여 동시 진행되어야 하는 두 작업이 직렬화될 수 있다.

**Defense:** 2-args 버전 사용 — `pg_try_advisory_xact_lock(hashtext('preview_card'), room_id)`. 첫 인자가 4-byte namespace, 두 번째가 4-byte key. namespace 가 `'preview_card'` 의 hash 라 다른 도메인의 advisory lock 과 절대 충돌하지 않음.

**Verification:** `PreviewCardCacheServiceTest.advisoryLockNamespaceIsolated` — mock `EntityManager.createNativeQuery` 가 `hashtext('preview_card')` literal 을 받는지 spy.

### Implementation trap #2 — `roomId` 가 `int4` 범위를 넘으면 advisory key cast 가 음수가 됨

Postgres advisory lock 의 2-args 버전은 두 인자가 `int4`. `roomId` 가 BIGINT 인데 Postgres 가 자동 cast 시 negative 가 되거나 overflow exception.

**Defense:** Java 측에서 `(int)(roomId & 0x7FFF_FFFFL)` 로 31 비트 truncate. 충돌 확률: 두 다른 `roomId` 가 같은 lower-31 비트를 가지면 같은 lock 이 됨 (~2.1B rooms 에 한 번). v1 의 expected scale (수 천 ~ 수 만 rooms) 에서 무시.

**Anti-pattern:** `(int) roomId` 직접 cast — Long → int signed cast 가 음수 advisory key 를 만들면 Postgres 가 `ERROR: lock key cannot be negative`.

### Implementation trap #3 — `@Async` 메서드의 self-invocation 우회 필요

`PreviewCardCacheService.resolve` 가 같은 클래스의 `backgroundRender` 를 직접 호출하면 — Spring AOP self-invocation 이 proxy 를 통과하지 않으므로 `@Async` 가 작동 안 함. 같은 thread 에서 동기 실행되어 stale-while-regenerate 가 깨짐.

**Defense:** `backgroundRender` 를 별도 `@Component PreviewCardBackgroundRenderer` 로 분리. `PreviewCardCacheService` 가 inject 해서 호출 (proxy 통과). `PreviewCardCacheService.backgroundRenderUnchecked` 는 package-private 으로, `PreviewCardBackgroundRenderer` 만 호출하는 entry-point.

```java
@Component
public class PreviewCardBackgroundRenderer {
    private final PreviewCardCacheService cacheService;
    @Async("previewCardRenderExecutor")
    public void render(Room room) {
        cacheService.backgroundRenderUnchecked(room);  // crosses proxy boundary
    }
}
```

**Verification:** `PreviewCardBackgroundRendererTest.render_isAsync` — `@SpringBootTest` 와 `CompletableFuture` 어설션 사용 (단순 Mockito 로는 검증 불가). 또는 본 스토리 scope 에서는 단위 테스트 생략하고 `PreviewCardEndToEndIT` 의 stale-path 시나리오에서 timing 으로 간접 검증.

### Implementation trap #4 — `@EnableAsync` 가 이미 활성화되어 있을 수 있음

`YeosalApiApplication` 에 `@EnableAsync` 가 없으면 `@Async` 가 silently no-op (동기 실행) — bug 무서움.

**Defense:** 추가 전 `grep -r "@EnableAsync" BE/src/main/java/` 로 확인. 없으면 `@SpringBootApplication` 옆에 한 줄 추가:

```java
@SpringBootApplication
@EnableAsync   // Story 6.1 — preview-card background re-render
@EnableScheduling   // already present for Story 1.2 daily evaluator
public class YeosalApiApplication { ... }
```

**Verification:** 같은 application 의 `@Scheduled` 가 이미 작동하는지 확인 — 작동하면 `@EnableScheduling` 도 있다는 의미고 `@EnableAsync` 추가의 영향 반경은 작음.

### Implementation trap #5 — Apache Batik 의 first-call font cache initialization

PNGTranscoder 의 첫 호출이 native font subsystem 을 초기화 — JVM 21 + Linux container 에서 500ms~ 부담. p95 < 1s SLA 가 cold-cold 호출 (서버 부팅 직후) 에 첫 요청에서 빗나갈 수 있음.

**Defense:** `@PostConstruct` 에서 빈 SVG 1 회 warm-up:

```java
@PostConstruct
public void warmUp() {
    try { toPng("<svg xmlns='http://www.w3.org/2000/svg'/>"); }
    catch (RuntimeException ex) { log.warn("[kakaoshare] warm-up failed: {}", ex.toString()); }
}
```

본 스토리 AC6 의 happy path 는 warm-up 없이도 만족 가능하지만, dev-story 가 첫 cold p95 가 1s 를 초과한다면 위 mitigation 을 코드에 포함. 코드 리뷰 시 결정.

### Implementation trap #6 — Cache TTL 의미 vs nginx Cache-Control

**Server-side TTL (1h):** `PreviewCardCacheService.resolve` 의 stale-or-fresh 판정 기준. invalidate hook (AC4) 가 즉시 발화해도 stale path 로 fallback.

**Client-side Cache-Control max-age (1h):** `PreviewCardController.servePreviewCard` 가 302 응답에 붙이는 header. KakaoTalk 의 fetcher 가 cache.

두 값이 동일하지만 **의도가 다름** — server-side 는 invalidate hook 의 race window 보강, client-side 는 fetch frequency 감소. 서로 별도 변수 (`CACHE_TTL`, `HTTP_CACHE_MAX_AGE`) 로 보관해 의미 분리.

**Trap:** TTL 을 1h → 5min 으로 단축하려는 미래 PR 이 서버 / 클라이언트 동시 변경을 잊으면 — server 가 "stale" 인데 client cache 가 "fresh" 로 들어가 화면이 stale.

### Implementation trap #7 — `RoomService.publishAfterCommit` 의 third-time copy

Story 5.4 가 `RoomRuleService` 에 `publishAfterCommit` 를 *복사* — `DailyService.publishAfterCommit:295-306` 의 두 번째 사본. 본 스토리가 `RoomService` 에 추가로 복사하면 **세 번째 사본** — 이제 추출의 시점.

**Decision:** `RoomService` 의 사본도 단순 복사로 진행 (Story 5.4 의 형식주의 유지: "3개 이상 callsite 가 발생할 때 추출"). 단, AC8 의 RulePresetPreview 추출이 다른 정당화 (DI-less pure function) 로 이미 진행되므로, **본 스토리는 `publishAfterCommit` 도 추출 후보**.

**그러나 본 스토리의 scope 는 BE-only + new module + cross-cutting wiring 으로 이미 큼.** 추출은 **별도 follow-up story** (Story 5.5 retrospective 후) 로 deferral 권장. 본 스토리는 RoomService 의 사본 추가만.

**문서화:** `RoomService.publishAfterCommit` 의 javadoc 에 한 줄: `// Third byte-identical copy — see RoomRuleService:116, DailyService:295. Extract to a shared transactional-event-publisher utility in a follow-up.`

### Implementation trap #8 — `room_invite_preview_cache.rule_version_id` 의 미사용

V11 (11) 의 cache schema 는 `rule_version_id bigint references room_rule_versions(id)` 컬럼을 보유 — render 가 어떤 rule version 으로 만들어졌는지 trace. 본 스토리의 AC4 invalidate 가 row 를 delete 하므로 `rule_version_id` 는 **저장만** 되고 (audit) **lookup 으로는 사용되지 않음**.

**Implementation 결정:** entity 에 컬럼 매핑은 유지 (DDL validate 가 컬럼 존재 강제) — 값은 render 시점의 current rule 의 `getId()` 로 채움. lookup query 가 없으므로 indexed 도 필요 없음 (V11 에 인덱스 없음 OK).

**미래 활용:** rule-version 별로 cache invalidation 을 더 정교하게 할 (rule version 이 바뀌어도 weekend toggle 만 변경되어 preview 표시가 같은 경우 invalidate skip) 최적화 여지 — 본 스토리는 단순 "rule 바뀌면 무효화" 정책.

### Implementation trap #9 — D1 Editorial override map 의 token 누락 시 fallback

`tokens.json` 의 `subMode.editorial` 가 노출하는 키는 UX 명세 (typography.heading.weight, typography.heading.tracking, radius.default, space.layout.padding, motion.entry.duration, motion.entry.easing, elevation.1, typography.display.serif.enabled) 8 개. **`color` 관련 override 가 없음** — D1 은 base color 토큰을 그대로 사용 (oxblood 가 이미 `color.key.default`).

**Verification before AC5:** `cd BE && cat build/generated/sources/tokens/com/yeosal/api/theme/GeneratedTokens.java` 에서 `SubMode.Editorial` 의 멤버 목록을 확인. 만약 `TYPOGRAPHY_HEADING_WEIGHT` 가 존재하지 않으면 (e.g., codegen 이 typography override 를 flat 으로 전개 안 함) — Story 1.5 의 codegen 변경 필요. 그 경우 본 스토리의 AC5 가 **base** GeneratedTokens 의 `TYPOGRAPHY_HEADING_WEIGHT` 를 직접 사용 (override 미적용 — visual 영향 미미, codegen 강화는 별도 follow-up).

**Defensive fallback:** 만약 `GeneratedTokens.SubMode.Editorial.TYPOGRAPHY_HEADING_WEIGHT` 가 컴파일 실패하면 base `GeneratedTokens.TYPOGRAPHY_HEADING_WEIGHT` 로 대체. 코드 리뷰 시 결정. **`tokens.json` 변경 ZERO** 는 AC9 banned-paths 의 절대 명시.

### Implementation trap #10 — 첫 PNG 가 디스크에 쓰이기 전 nginx 가 fetch 시도

`doRender` 의 순서:
1. SVG 빌드,
2. Batik PNG transcode,
3. 임시 파일 `${roomId}.png.tmp` 에 write,
4. `Files.move(temp, target, ATOMIC_MOVE)`,
5. cache row insert.

만약 4 와 5 사이에 다른 thread / 서버 instance 가 cache row 를 SELECT 하면 row 미존재 → cold-miss path 재진입. **OK** — advisory lock 이 양쪽 thread 모두 잡으려 하므로 한 쪽만 진행.

만약 5 와 4 의 순서를 바꿔 row 먼저 insert 하면, nginx 가 row 의 `png_url` 을 알게 되어 redirect 응답을 보내지만 디스크의 PNG 가 아직 없어 404 가 됨.

**Defense:** 위 순서 (4 먼저, 5 다음) **반드시** 유지. 코드 주석에 명시.

### Implementation trap #11 — Member count 의 source of truth

`InvitePreviewRenderer.render` 가 `memberCount` 를 받음. 이 값은 어디에서?

- 옵션 A: `roomMembers.countByRoom(room)` — JPA repository 메서드, 이미 존재 (RoomMemberRepository:14).
- 옵션 B: `room.getMembers().size()` — lazy collection, `open-in-view: false` 이므로 `@Transactional` 안에서만 안전.

**Decision:** 옵션 A — `PreviewCardCacheService` 의 `@Transactional` 안에서 `RoomMemberRepository.countByRoom(room)` 호출. lazy collection 우회.

**Trap:** 만약 cache invalidation 이 `joinByCode` 의 afterCommit phase 에서 일어나는데, 같은 시점에 다른 thread 가 `resolve` 를 호출하면 — outer commit 이 끝났으므로 새 멤버가 보이고, render 가 fresh count 로 진행됨. **OK.**

### Implementation trap #12 — Spectator 멤버는 count 에 포함?

`roomMembers.countByRoom(room)` 의 정의: room_members 테이블의 row 수. Spectator (Story 2.1) 는 `survival_state.status = SPECTATOR` 인 유저로, room_members 에 row 가 **유지** 됨 (Story 2.1 의 결정). 따라서 spectator 도 count 에 포함됨.

**Product 의도:** preview 에 "5명이 함께 살아남는 중" — 5명 중 일부가 spectator 인 것이 KakaoTalk preview 에서는 의미 없음. ACTIVE+YELLOW+RED+SPECTATOR 총합 (= room_members row 수) 가 정확.

**Anti-pattern:** `survival_state.status IN ('ACTIVE', 'YELLOW')` 같은 필터로 count 를 좁힘 — privacy 위반 (Story 2.1 의 24h cooldown 가 broad visibility 를 제한). 단순 row count 만.

### Implementation trap #13 — `RoomRuleVersionRepository.findCurrentRuleForRoom` 의 시점

Renderer 가 `currentRule` 을 필요로 함 — "다음 달부터 적용" 이 아닌 **현재 적용 중인 rule** 의 preview phrase. `RoomRuleService.getRule(...)` (Story 5.1) 가 current vs pending 두 row 를 분리 반환.

**Implementation:** `PreviewCardCacheService.synchronousRender` 안에서 `ruleVersionRepository.findByRoomIdAndEffectiveFromMonth(roomId, currentMonthKST())` 로 lookup. 없으면 (V11 (14) backfill 이 모든 방에 default rule 을 grant 했으므로 absent 가 invariant 위반) `RuntimeException` 으로 escalate — preview 빌드 실패 시 503 fallback 으로 graceful.

**Trap:** 만약 month boundary (00:00 KST 1일) 가 지나면서 current rule 이 pending → active 로 전환되는 race — render 가 month boundary 직전에 시작되어 stale rule 를 사용. 영향: 사용자가 1일 00:00:00 직후 share 시 "지난 달 룰" 이 preview 에 잠깐 보일 수 있음. **OK** — TTL 1h 가 자연 invalidate 하고, AC4 의 invalidate hook 은 *변경 시* 만 fire (boundary cross 는 변경이 아니라 이전 변경의 effective date 만남).

### Implementation trap #14 — `infra/docker-compose.yml` + `infra/nginx/default.conf` 변경의 분기

- Dev (docker-compose) — `preview-cards-cache` host bind volume `./preview-cards-cache:/var/yeosal/preview-cards`.
- Prod (blue-green deploy) — host bind 또는 docker volume. RUNBOOK 에 명시 (Story 6.3 scope, 본 스토리에서는 default.conf + compose 만).
- Nginx 가 `/preview-cards/{roomId}.png` location 으로 정적 자산 serve. `Content-Type: image/png` 자동 추론.

**Trap:** prod 의 nginx 가 stateless container 라 디스크가 휘발성이면 — 컨테이너 재시작 시 모든 PNG 가 날아감. 영향: 다음 fetch 가 cold-miss → 재 렌더. **OK** (1h 안에 다시 render 하는 시간이 KakaoTalk fetch rate 보다 길지 않음).

## Out of scope (DO NOT IMPLEMENT IN THIS STORY)

1. **Kakao Share SDK 의 FE 측 호출** — Story 6.2 scope. 본 스토리는 BE 측 share-payload + preview-card 생성 / serve 만.
2. **딥링크 universal-link / app-link plist / asset-links.json 등록** — Story 6.2 scope.
3. **`adb uninstall app.yeosal.mobile` RUNBOOK 업데이트** — Story 6.3 scope.
4. **`previewCardImageUrl` 안의 invite code path-segment** — `?code=` query 만 (AC1 lock).
5. **별도 push notification 발행** — Epic 6 미명시, OOS.
6. **새 Flyway migration (V14+)** — V11 (11) 가 이미 cache table 을 보유 (AC0 / AC9 banned-paths).
7. **`room_invite_preview_cache.rule_version_id` 를 lookup key 로 사용한 fine-grained invalidation** — Trap #8 의 future optimization, 별도 story.
8. **Sentry custom breadcrumb / metric** — AC15 의 light-touch 결정, ApiExceptionHandler 의 자동 5xx 캐치만.
9. **새 RealtimeEvent sealed variant (`PreviewCardReady` 등)** — preview card 는 STOMP 채널을 사용하지 않음 (AC14 의 architecture-deviation 노트).
10. **Member nickname / survival status / leader identity 의 preview 노출** — privacy lock (AC5).
11. **CDN 또는 S3 같은 외부 storage** — v1 은 로컬 디스크 + nginx (AC6). Phase-2 후보.
12. **Preview card 의 i18n (영문 / 일문 / 중문)** — v1 KR-only (NFR-9.7.1).
13. **사용자별 personalized preview** ("당신만 부르는 방" 등) — 본 스토리는 room-level cache (PK = room_id). 개인화는 Phase-2.
14. **`tokens.json` 의 D1 Editorial override map 확장** — AC9 banned-paths.
15. **`RealtimeEvent.RuleChange` sealed variant 의 architecture 문서 follow-up** — Story 5.4 가 동일 follow-up 을 deferred 했고, 본 스토리도 architecture deviation note 만 (AC14).
16. **`publishAfterCommit` helper 의 cross-class 추출** — Trap #7, 별도 follow-up story.
17. **Cold-render warm-up of Batik (`@PostConstruct`)** — Trap #5, dev-story / review 결정.
18. **Preview card 의 dark / light theme toggle** — D1 Editorial 단일 lock (AC5).
19. **Preview card 의 visual A/B test** — Phase-2, telemetry 가 정착한 후.
20. **방장 / leader 만 share 가능 한 권한 정책** — 본 스토리의 `RoomController.createInvite` 는 `requireMembership` 만 (기존 정책 유지). Leader-only restriction 은 별도 product 결정.

## Project structure notes

- BE files:
  - `BE/src/main/java/com/yeosal/api/kakaoshare/` (NEW MODULE) — 9 클래스 (AC9 의 NEW list).
  - `BE/src/main/java/com/yeosal/api/room/RoomController.java`, `RoomService.java` (MODIFIED) — invite share-payload + invalidate hooks.
  - `BE/src/main/java/com/yeosal/api/room/chat/ChatService.java` (MODIFIED) — `formatRulePreview` 호출 통합.
  - `BE/src/main/java/com/yeosal/api/survival/RoomRuleService.java` (MODIFIED) — invalidate hook.
  - `BE/src/main/java/com/yeosal/api/survival/RulePresetPreview.java` (NEW) — 공유 helper.
  - `BE/src/main/java/com/yeosal/api/common/SecurityConfig.java`, `ApiExceptionHandler.java`, `ServiceUnavailableException.java` (MODIFIED / NEW) — public path + 새 예외 매핑.
  - `BE/src/main/java/com/yeosal/api/YeosalApiApplication.java` (MODIFIED) — `@EnableAsync`.
- BE tests mirror source layout:
  - `BE/src/test/java/com/yeosal/api/kakaoshare/` (NEW) — 5 unit + 1 opt-in IT.
  - `BE/src/test/java/com/yeosal/api/survival/RulePresetPreviewTest.java` (NEW).
  - `BE/src/test/java/com/yeosal/api/room/RoomServiceTest.java`, `RoomControllerTest.java` (MODIFIED) — fixture widening.
  - `BE/src/test/java/com/yeosal/api/survival/RoomRuleServiceTest.java`, `RoomRuleControllerTest.java` (MODIFIED) — fixture widening + 1 새 케이스.
- Infra:
  - `infra/docker-compose.yml`, `infra/nginx/default.conf` (MODIFIED) — 정적 자산 mount + serve.
- Config:
  - `BE/build.gradle` (MODIFIED) — Apache Batik 두 라인.
  - `BE/src/main/resources/application.yml` (MODIFIED) — `yeosal.share` 블록.
- FE 변경 ZERO — Story 6.2 의 scope.

## Architecture decisions traceability

| FR / decision | AC | File |
|----|----|------|
| FR-8.6.1 (share payload 응답) | AC1, AC7 | `RoomController.createInvite` / `RoomService.InviteSummary` / `ShareUrlBuilder` |
| FR-8.6.2 (server-side preview card + 1h TTL + invalidation) | AC2, AC3, AC4, AC5, AC6 | `PreviewCardController` / `PreviewCardCacheService` / `InvitePreviewRenderer` / `PngRasterizer` + invalidate hooks |
| Architecture §4.10 (caching + stampede 방지) | AC3 | Advisory-lock single-flight + stale-while-regenerate |
| Architecture §4.16 (FE→BE codegen) | AC5 | `GeneratedTokens.SubMode.Editorial.*` constants only |
| Architecture §6.3 V11 (11) (`room_invite_preview_cache`) | AC0 (no new migration) | `PreviewCardCache` entity → existing V11 table |
| Architecture §6.4 (REST endpoint table) | AC2 | `GET /api/v1/rooms/{id}/invites/preview-card` |
| UX line 1050-1071 (D1 Editorial sub-mode) | AC5 | `InvitePreviewRenderer` SVG template |
| Brand-voice (PRD FR-8.8.2) | AC5, AC10 | locked phrases + AVOID-lexicon zero assertion |
| project-context.md:88 (constructor injection only) | AC7 | `RoomService` constructor widening append-only |
| project-context.md:109-114 (controller path convention + public whitelist) | AC2 | `/api/v1/rooms/*/invites/preview-card` GET permitAll |
| project-context.md:142 (Testcontainers for DB IT) | AC11 | `PreviewCardEndToEndIT @SpringBootTest @Testcontainers` |
| project-context.md:145 (TDD RED→GREEN) | AC11 | RED per file → GREEN |
| Story 5.4 helper precedent | AC4, AC7 | `publishAfterCommit` byte-identical copy (3rd site) |
| Story 5.4 phrase lock | AC8 | `RulePresetPreview` cross-callsite extraction |

## References

- Epics: `_bmad-output/planning-artifacts/epics.md:808-838` (Epic 6 + Story 6.1 ACs), `epics.md:1170-1188` (FR Coverage Map "Story 6.1 / 6.2 / 6.3")
- PRD:
  - `_bmad-output/planning-artifacts/prd.md:410-419` (FR-8.6.1 ~ FR-8.6.6)
  - `prd.md:456-460` (NFR-9.1.4 perf — final-3 poster batch 인접 reference)
  - `prd.md:512-515` (NFR-9.8.5 native module reinstall — Story 6.3 scope, 본 스토리 컨텍스트만)
- Architecture:
  - `_bmad-output/planning-artifacts/architecture.md:154-156` (Batik 의존성 결정 + AC14 follow-up)
  - `architecture.md:308-328` (§4.10 caching decision + schema + stampede)
  - `architecture.md:419-485` (§4.16 codegen pipeline)
  - `architecture.md:588-592` (§6.1 kakaoshare 모듈 outline + AC14 follow-up)
  - `architecture.md:802-817` (§6.4 REST endpoint table)
- UX: `_bmad-output/planning-artifacts/ux-design-specification.md:1048-1071` (D1 Editorial sub-mode lock), `:1157-1183` (Surface Assignment Matrix 의 Kakao invite preview row)
- project-context: `_bmad-output/project-context.md:88` (constructor injection), `:109-114` (controller convention), `:142` (Testcontainers), `:145` (TDD), `:176` (package-by-feature), `:191` (no emojis), `:229` (Post-merge user action note), `:280` (channel-scoped log prefix)
- Story 5.4 (most recent precedent): `_bmad-output/implementation-artifacts/5-4-rule-change-broadcast-in-chat.md` — afterCommit defer 패턴, 14-trap 카탈로그, scope-fence 형식
- Story 5.1: `_bmad-output/implementation-artifacts/5-1-rule-edit-with-next-month-only-application.md` — `RoomRuleService.updateRule` chokepoint 와 `requireLeader` 가드
- Story 1.5: `_bmad-output/implementation-artifacts/1-5-design-system-foundation-v2-token-packed-type-fe-be-codegen.md` — `tokens.json` + `GeneratedTokens` 컨벤션
- Story 4.3: `_bmad-output/implementation-artifacts/4-3-poolstack-5-stage-svg-asset-pipeline-threshold-table.md` — FE SVG precedent (declarative `<Svg>`), AC5 ember.default contrast finding
- Existing BE code:
  - `BE/src/main/java/com/yeosal/api/room/RoomController.java:99-103` (`createInvite` endpoint 의 현재 형태)
  - `BE/src/main/java/com/yeosal/api/room/RoomService.java:309-318` (`createInvite` 서비스)
  - `BE/src/main/java/com/yeosal/api/room/RoomService.java:574-583` (`InviteSummary` record — 확장 대상)
  - `BE/src/main/java/com/yeosal/api/room/RoomMemberRepository.java:14` (`countByRoom` member count source)
  - `BE/src/main/java/com/yeosal/api/survival/RoomRuleService.java:73-107` (`updateRule` chokepoint, Story 5.1+5.4 lineage)
  - `BE/src/main/java/com/yeosal/api/survival/RoomRuleService.java:116-127` (`publishAfterCommit` helper — Story 5.4 lineage)
  - `BE/src/main/java/com/yeosal/api/survival/RoomRuleVersionRepository.java` (current-month rule lookup source for renderer)
  - `BE/src/main/java/com/yeosal/api/common/SecurityConfig.java:36-52` (permitAll whitelist add site)
  - `BE/src/main/java/com/yeosal/api/common/ApiExceptionHandler.java` (예외 매핑 add site)
  - `BE/build.gradle:29-49` (의존성 add site)
  - `BE/build.gradle:285-298` (Checkstyle hex-literal guard)
  - `BE/src/main/resources/application.yml:20-28` (`yeosal.*` config 컨벤션)
  - `BE/src/main/resources/db/migration/V11__survival_revival_economy.sql:132-139` (cache table schema, 변경 ZERO)
- Existing FE code (자동 사용, 변경 ZERO):
  - 본 스토리는 FE 사용 사이트 없음 — Story 6.2 가 모두 가져감.
- Existing infra:
  - `infra/docker-compose.yml` (modify) — `preview-cards-cache` volume add site
  - `infra/nginx/default.conf` (modify) — `location /preview-cards/` block add site
- Brand-voice lint: `tools/brand-voice-lint.ts:50-59` (AVOID 어휘 8개)

## Change log

| Date | Author | Change |
|------|--------|--------|
| 2026-06-05 | Dev (claude-opus-4-7) | Code review complete. Sprint-status + story file Status flipped review → done. epic-6 stays in-progress (6-2 / 6-3 backlog). |
| 2026-06-04 | Dev (claude-opus-4-7) | Story 6.1 implementation shipped to review. BE 603 cases GREEN (574 baseline + 29 net-additive: 8 PreviewCardCacheService + 6 InvitePreviewRenderer + 2 PngRasterizer + 3 PreviewCardController + 4 ShareUrlBuilder + 1 PreviewCardEndToEndIT(skipped) + 3 RulePresetPreview + 2 RoomRuleService invalidate hook + 0 net new in RoomServiceTest (existing case extended)). Checkstyle hex-literal guard GREEN — every fill colour goes through `GeneratedTokens.*`. Scope fence verified: zero diff under V11 migrations / FE source / notification / realtime / tokens.json. RoomController.java intentionally **not** modified (no signature change — InviteSummary record extension propagates through `RoomService` only); story AC9 MODIFIED list includes it but the actual diff is 0 lines on RoomController.java itself. AC8 cross-callsite extraction: `survival/RulePresetPreview` static utility introduced; `ChatService.formatRulePreview` deleted and call rewired without altering `ChatServiceRuleChangeTest` (byte-identical). One configurability deviation from the spec: `yeosal.share.preview-cards-dir` exposed as `@Value` so unit tests + `PreviewCardEndToEndIT` can redirect the PNG output dir to JUnit `@TempDir` (avoids tests writing to `/var/yeosal/preview-cards`). VERIFY-A/B (Docker dev stack) deferred to PR-open reviewer (Story 5.1/5.2/5.3/5.4 precedent). Status flipped ready-for-dev → in-progress → review. |
| 2026-06-04 | Maya (context engineer) | Initial context-engineered story file. Epic 6 의 첫 backlog story — KakaoTalk 공유 viral loop 의 BE-side render foundation. 새 모듈 `kakaoshare/` 9 클래스 + 기존 `RoomController.createInvite` 응답 2-field 확장 + 신규 public endpoint `GET /rooms/{id}/invites/preview-card` + cache invalidation 3 hook (`RoomRuleService.updateRule` / `RoomService.joinByCode` / `.leave`) + cache stampede 방지 (Postgres advisory lock + stale-while-regenerate) + Apache Batik 1.17 (transcoder + codec) 의존성 추가. AC8 가 Story 5.4 의 `formatRulePreview` 를 cross-module utility `RulePresetPreview` 로 추출 (DI-less pure function 이라 2-callsite 시점에 안전 추출). **NO new migration** (V11 (11) 의 `room_invite_preview_cache` 가 이미 존재), **NO FE 변경** (Story 6.2 scope), **NO push notification**, **NO new RealtimeEvent sealed variant**, **NO `tokens.json` 변경**. 14 implementation traps 카탈로그 (가장 중요: Trap #1 advisory lock namespace 분리, Trap #3 `@Async` self-invocation 우회 via PreviewCardBackgroundRenderer wrapper, Trap #5 Batik first-call font cache, Trap #9 D1 Editorial override 누락 시 base token fallback, Trap #10 PNG write-before-row-insert 순서). 20-item out-of-scope list 로 Kakao SDK FE 호출 / push / S3 / 개인화 preview / i18n / leader-only restriction / `tokens.json` 변경 모두 차단. AC11 test matrix net-additive ~31 BE cases (8 cache service + 6 renderer + 2 rasterizer + 5 controller + 4 share-url + 3 rule-preset + 3 IT) + 0 FE 변경. AC9 scope fence 의 banned-paths grep 검증 명령. AC12 의 12 게이트 verification matrix. Story 6.1 가 done 으로 진입해도 epic-6 는 in-progress 유지 (6-2 / 6-3 backlog 잔존). |

## Dev Agent Record

### Agent Model Used

claude-opus-4-7 (1M context) — bmad-dev-story workflow, 2026-06-04.

### Debug Log References

- `BE/build/test-results/test/TEST-com.yeosal.api.kakaoshare.*.xml` — 23 new kakaoshare cases (8 cache service + 6 renderer + 2 rasterizer + 3 controller + 4 share-url + 1 IT-skipped via `@EnabledIfSystemProperty`).
- `BE/build/test-results/test/TEST-com.yeosal.api.survival.RulePresetPreviewTest.xml` — 3 new cases.
- `BE/build/test-results/test/TEST-com.yeosal.api.survival.RoomRuleServiceTest.xml` — 2 new cases (`updateRule_invalidatesPreviewCardAfterCommit`, `updateRule_swallowsPreviewCardInvalidateFailure`).
- `BE/build/test-results/test/TEST-com.yeosal.api.room.RoomServiceTest.xml` — `createInvitePersistsCode` extended with kakaoShareUrl/previewCardImageUrl assertions.
- Full suite: `./gradlew test` → BUILD SUCCESSFUL, **603 cases** (574 baseline + 29 net-additive).
- `./gradlew checkstyleMain` → BUILD SUCCESSFUL (no hex-literal violations — renderer reads only `GeneratedTokens.*`).

### Completion Notes List

- **AC1 share payload (FR-8.6.1)** — `InviteSummary` record extended from 4 → 6 fields. Single-arg `from(RoomInvite)` removed so the compiler enforces that every callsite goes through `ShareUrlBuilder`. JSON wire shape is key-additive; FE TS structural typing keeps older bundles compatible until Story 6.2 ships the share button.
- **AC2 public endpoint** — `SecurityConfig` permitAll added for `GET /api/v1/rooms/*/invites/preview-card` only; POST/PATCH/DELETE on the same path stay authenticated. Single-segment wildcard `*` (not `**`) keeps depth fixed.
- **AC3 single-flight + stale-while-regenerate** — Postgres `pg_try_advisory_xact_lock(hashtext('preview_card'), CAST(rid AS int4))` with namespace isolation (trap #1) and 31-bit truncation (trap #2). Cold-miss with lock contention surfaces `ServiceUnavailableException` → 503 + Retry-After:5.
- **AC4 invalidate hooks (3 sites)** — `RoomRuleService.updateRule` (afterCommit, alongside the Story 5.4 chat broadcast lambda), `RoomService.joinByCode` (afterCommit), `RoomService.leave` non-owner path (afterCommit). Owner-disband path relies on `rooms.id ON DELETE CASCADE` from `room_invite_preview_cache`.
- **AC5 D1 Editorial + brand-voice** — Renderer references `GeneratedTokens.SubMode.Editorial.{TYPOGRAPHY_HEADING_WEIGHT, TYPOGRAPHY_HEADING_TRACKING}` + base `COLOR_{BG_CANVAS, TEXT_PRIMARY, KEY_DEFAULT, TEXT_SECONDARY}`. Unit test asserts AVOID lexicon (`벌금/잃었다/떨어졌다/실패/자책/부담/패배/죄책감`) zero occurrence and locked phrases (`열살`, `같이 살아남자`, `함께 살아남는 중`) present. Five-character XML escape (`& < > " '`) covers room names without an Apache Commons Text dependency.
- **AC6 Batik wrapper** — `batik-transcoder:1.17` + `batik-codec:1.17` (architecture §3.3 deviation note in AC14). `@PostConstruct` warms up the AWT font subsystem (trap #5). Atomic move sequence: SVG → PNG bytes → temp file → `Files.move(ATOMIC_MOVE)` → DB row (trap #10).
- **AC7 ShareUrlBuilder** — Env-bound base URLs (`yeosal.share.deeplink-base`, `yeosal.share.preview-card-base`) with single-shot trailing-slash strip. `RoomService.createInvite` is the only caller.
- **AC8 cross-callsite extraction** — `survival/RulePresetPreview.format(preset, weekendInclude)` is the new utility; `ChatService.formatRulePreview` deleted and call replaced. `ChatServiceRuleChangeTest` (7 cases) remains byte-identical → confirms the wire shape `"매일 업데이트, 주말 포함/제외"` is preserved. Justification deviates from Story 5.4 trap #4 (3-callsite extraction rule) because this is a pure DI-less static formatter and a third copy would risk wording drift.
- **AC9 scope fence** — `git diff --stat origin/main -- BE/src/main/resources/db/migration/ FE/src/ FE/app/ BE/src/main/java/com/yeosal/api/notification/ BE/src/main/java/com/yeosal/api/realtime/ FE/src/theme/tokens.json` returns empty. NO new migration (V11 (11) cache table reused), NO FE source, NO new RealtimeEvent variant, NO push notification, NO tokens.json edit.
- **AC10 brand-voice + checkstyle** — Both gates GREEN. Checkstyle hex-literal guard passes because every fill colour flows through `GeneratedTokens.*` constants. `InvitePreviewRendererTest.output_avoidsBannedLexicon` is the unit-level brand-voice tripwire.
- **AC11 test matrix** — Net-additive 29 BE cases (kakaoshare 22 + RulePresetPreview 3 + RoomRuleServiceTest 2 + RoomServiceTest 2 widened existing). Existing widenings: `RoomServiceTest`, `RoomServiceEvaluationTest`, `RoomServiceMemberJoinSystemMessageTest` (21-arg ctor), `RoomRuleServiceTest` (8-arg ctor). FE source Δ=0 → baseline preserved.
- **AC12 verification matrix** — Gates 1–10 GREEN. Gates 11/12 manual VERIFY-A/B deferred to PR-open reviewer (Docker dev stack unavailable on this host — Story 5.1/5.2/5.3/5.4 precedent).
- **AC13 Post-merge user action** — RUNBOOK note required in the PR description: nginx `default.conf` change (graceful reload), `preview-cards-cache` named volume mount on prod host, optional `YEOSAL_SHARE_*` env-var overrides.
- **AC14 architecture deviation** — Non-blocker doc follow-up tracked in PR description: architecture.md §3.3 should add `batik-codec`, §6.1 should enumerate the six additional `kakaoshare/` classes introduced beyond the original four.
- **AC15 Sentry** — No new instrumentation. `PreviewCardRenderException` flows through `ApiExceptionHandler` → automatic 5xx Sentry capture is sufficient for v1.
- **AC16 sprint-status** — Flipped `6-1-...: ready-for-dev → in-progress → review`. epic-6 stays in-progress (6-2/6-3 still backlog).
- **Trap #11 (member count source)** — Resolved with `RoomMemberRepository.countByRoom(room)` inside the `@Transactional` boundary; defeats the lazy-collection path.
- **Trap #13 (current rule lookup)** — Renderer queries `RoomRuleVersionRepository.findTopByRoomIdAndEffectiveFromMonthLessThanEqualOrderByEffectiveFromMonthDesc(roomId, currentMonthKST)` (Story 5.1 helper). Missing row → `PreviewCardRenderException` → 500 with `[kakaoshare]` log channel; never silently 200s.
- **PreviewCardCacheService configurability** — Renderer + cache service take `yeosal.share.preview-cards-dir` as `@Value` so the IT (`PreviewCardEndToEndIT`) and the unit test (`PreviewCardCacheServiceTest`) point the disk side at a JUnit `@TempDir` instead of `/var/yeosal/preview-cards`. Story originally implied a hard-coded path; configurable path lets unit tests run on CI hosts with no write access to `/var`.

### File List

**NEW (production, untracked):**
- `BE/src/main/java/com/yeosal/api/common/ServiceUnavailableException.java`
- `BE/src/main/java/com/yeosal/api/kakaoshare/PreviewCardCache.java`
- `BE/src/main/java/com/yeosal/api/kakaoshare/PreviewCardCacheRepository.java`
- `BE/src/main/java/com/yeosal/api/kakaoshare/PreviewCardCacheService.java`
- `BE/src/main/java/com/yeosal/api/kakaoshare/PreviewCardBackgroundRenderer.java`
- `BE/src/main/java/com/yeosal/api/kakaoshare/PreviewCardController.java`
- `BE/src/main/java/com/yeosal/api/kakaoshare/PreviewCardRenderException.java`
- `BE/src/main/java/com/yeosal/api/kakaoshare/PreviewCardRenderExecutorConfig.java`
- `BE/src/main/java/com/yeosal/api/kakaoshare/InvitePreviewRenderer.java`
- `BE/src/main/java/com/yeosal/api/kakaoshare/PngRasterizer.java`
- `BE/src/main/java/com/yeosal/api/kakaoshare/ShareUrlBuilder.java`
- `BE/src/main/java/com/yeosal/api/survival/RulePresetPreview.java`

**NEW (tests, untracked):**
- `BE/src/test/java/com/yeosal/api/kakaoshare/PreviewCardCacheServiceTest.java` (8 cases)
- `BE/src/test/java/com/yeosal/api/kakaoshare/InvitePreviewRendererTest.java` (6 cases)
- `BE/src/test/java/com/yeosal/api/kakaoshare/PngRasterizerTest.java` (2 cases)
- `BE/src/test/java/com/yeosal/api/kakaoshare/PreviewCardControllerTest.java` (3 cases)
- `BE/src/test/java/com/yeosal/api/kakaoshare/ShareUrlBuilderTest.java` (4 cases)
- `BE/src/test/java/com/yeosal/api/kakaoshare/PreviewCardEndToEndIT.java` (1 case, opt-in)
- `BE/src/test/java/com/yeosal/api/survival/RulePresetPreviewTest.java` (3 cases)

**MODIFIED (production):**
- `BE/build.gradle` — `batik-transcoder:1.17` + `batik-codec:1.17` dependencies.
- `BE/src/main/resources/application.yml` — `yeosal.share` block (`deeplink-base`, `preview-card-base`, `preview-cards-dir`).
- `BE/src/main/java/com/yeosal/api/YeosalApiApplication.java` — `@EnableAsync` annotation.
- `BE/src/main/java/com/yeosal/api/common/SecurityConfig.java` — permitAll for `GET /api/v1/rooms/*/invites/preview-card`.
- `BE/src/main/java/com/yeosal/api/common/ApiExceptionHandler.java` — `ServiceUnavailableException` (503) + `PreviewCardRenderException` (500 with `[kakaoshare]` log) handlers.
- `BE/src/main/java/com/yeosal/api/room/RoomService.java` — constructor 19→21 args, `createInvite` share-payload, `joinByCode` + `leave` afterCommit invalidate hooks, private `publishAfterCommit` helper (3rd byte-identical copy), `InviteSummary` record 4→6 fields with single 3-arg `from`.
- `BE/src/main/java/com/yeosal/api/survival/RoomRuleService.java` — constructor 7→8 args, `updateRule` afterCommit invalidate lambda.
- `BE/src/main/java/com/yeosal/api/room/chat/ChatService.java` — `formatRulePreview` deleted, `publishRuleChangeSystemMessage` now calls `RulePresetPreview.format`.

**MODIFIED (tests):**
- `BE/src/test/java/com/yeosal/api/room/RoomServiceTest.java` — fixture widening + kakaoShareUrl/previewCardImageUrl assertions.
- `BE/src/test/java/com/yeosal/api/room/RoomServiceEvaluationTest.java` — fixture widening (21-arg ctor).
- `BE/src/test/java/com/yeosal/api/room/RoomServiceMemberJoinSystemMessageTest.java` — fixture widening (21-arg ctor).
- `BE/src/test/java/com/yeosal/api/survival/RoomRuleServiceTest.java` — fixture widening (8-arg ctor) + 2 new cases.

**MODIFIED (infra):**
- `infra/docker-compose.yml` — `preview-cards-cache` named volume mounted on `api` (rw) and `nginx` (ro), 3 env-vars forwarded.
- `infra/nginx/default.conf` — `location /preview-cards/` static asset block.

**MODIFIED (BMad artifacts):**
- `_bmad-output/implementation-artifacts/sprint-status.yaml` — `6-1-...: ready-for-dev → review`.
- `_bmad-output/implementation-artifacts/6-1-server-side-preview-card-renderer-cache.md` — Status `review`, Tasks/Subtasks all checked, Dev Agent Record + File List + Change Log filled.
