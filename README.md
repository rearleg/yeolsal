# Yeosal

> 친한 친구들끼리 매일 인증을 이어가는 모바일 서바이벌. 다음 날 **06:00 Asia/Seoul** 전에 회고를 제출하지 못하면 yellow, 7일 rolling window에 두 번 미스하면 red(탈락)입니다. 회생은 가입 시 받는 무료 회생권, 개인 포인트, 친구가 자기 포인트를 써서 선물하는 friend-gift 세 가지로만 가능합니다.

Yeosal은 모노레포입니다. Expo React Native 클라이언트, Java 21 / Spring Boot 3 기반 API, 그리고 nginx가 API를 감싸는 Docker Compose 스택으로 구성됩니다. URL 경로와 JWT issuer는 `yeolsal`, 레포·앱 번들·브랜드는 `yeosal` — 두 표기는 의도된 것입니다.

---

## 프로젝트 히스토리

- **2026-04-26 ~ 2026-05-02 — 1차 MVP 완료**. "데일리 미션 + 다음 날 06:00 KST 전 회고" 포맷으로 친구 그룹 책임감 앱을 출시. 이 시기 문서: [`docs/product.md`](./docs/product.md), 마이그레이션 V1–V10.
- **2026-05-09 이후 — 서바이벌로 피보팅**. 같은 06:00 KST day-boundary와 회고 메커닉은 유지하되, two-strike yellow→red 탈락 / spectator mode / 회생권 / 친구 선물 회생 / Final-3 ceremony 등 서바이벌 게임 메커닉을 추가. 1차의 daily entry / reflection / grass 스키마는 그대로 재사용. 피보팅 이후 정식 문서: [`_bmad-output/planning-artifacts/product-brief-yeolsal-distillate.md`](./_bmad-output/planning-artifacts/product-brief-yeolsal-distillate.md), [`_bmad-output/planning-artifacts/prd.md`](./_bmad-output/planning-artifacts/prd.md). 마이그레이션 V11 이후가 서바이벌 영역.

`docs/product.md`는 1차 시기 스냅샷으로 보존돼 있으며 갱신하지 않습니다. 서바이벌 규칙·메커닉의 canonical 출처는 BMad planning artifacts입니다.

## 스택 한눈에 보기

| 영역 | 기술 스택 |
|------|----------|
| `FE/` — 모바일 | Expo SDK 54 · React Native 0.81 · React 19.1 · TypeScript 5.9 (strict) · expo-router 6 · TanStack Query 5 · `@stomp/stompjs` 7 · Sentry RN |
| `BE/` — API | Java 21 · Spring Boot 3.3.5 · Spring Security · Spring Data JPA (`validate`) · Flyway (V1–V10 + V11~ 서바이벌) · JJWT 0.12 · STOMP/WebSocket · springdoc-openapi · Testcontainers |
| `infra/` — Edge & deploy | Docker Compose (`api` · `postgres` · `nginx`) 외부 포트 `8088`, 모바일 빌드는 EAS |
| 인증 | Kakao OAuth (REST API 키는 BE 전용) + 이메일 로그인, JWT access/refresh는 `expo-secure-store` |
| 실시간 | STOMP over WebSocket `/ws`, JWT는 `CONNECT` 프레임에서 검증, SockJS fallback 없음 |

운영 API base: `https://api.rearleg.com/yeolsal/api/v1`.

## 아키텍처

### 전체 토폴로지

```mermaid
flowchart TB
  subgraph Mobile["모바일 (app.yeosal.mobile)"]
    direction TB
    Router["expo-router<br/>FE/app/"]
    Providers["Providers<br/>Query · Auth · Realtime"]
    Hooks["TanStack Query hooks<br/>FE/src/lib/query/hooks/"]
    Req["apiRequest&lt;T&gt;<br/>FE/src/api/"]
    STOMP["RealtimeClient<br/>@stomp/stompjs"]
    Router --> Providers --> Hooks
    Hooks --> Req
    Hooks --> STOMP
  end

  subgraph Edge["Edge (infra/)"]
    NGX["nginx :8088<br/>REST + WSS 프록시"]
  end

  subgraph Backend["Spring Boot API (BE/)<br/>ctx /yeolsal · :8080"]
    direction TB
    REST["REST Controllers<br/>/api/v1/*"]
    WS["STOMP /ws<br/>JwtChannelInterceptor"]
    SVC["Feature Services<br/>auth · daily · room · survival · revival · …"]
    RT["RealtimePublisher"]
    REST --> SVC
    SVC --> RT
    RT --> WS
    SVC --> DB
  end

  DB[("PostgreSQL<br/>Flyway V1–V10 + V11~")]
  KAKAO[["Kakao OAuth"]]
  SENTRY[["Sentry"]]

  Req -- "HTTPS REST" --> NGX
  STOMP -- "WSS" --> NGX
  NGX --> REST
  NGX --> WS
  REST -. "OAuth" .-> KAKAO
  Mobile -. "에러 리포트" .-> SENTRY
```

### 백엔드 도메인 모듈

패키지-by-feature 모놀리스. 각 모듈은 `Controller → Service → Repository` 레이어를 따릅니다.

```mermaid
flowchart LR
  subgraph common["common/"]
    SEC["SecurityConfig<br/>JwtFilter · RateLimit"]
    ERR["ApiExceptionHandler"]
  end

  subgraph features["도메인 모듈"]
    auth["auth/<br/>JWT · Kakao OAuth"]
    daily["daily/<br/>목표 · 회고 · todo"]
    room["room/<br/>방 · 멤버 · 초대"]
    chat["room/chat/<br/>ChatService · Kudos"]
    survival["survival/<br/>YELLOW/RED 평가 · 규칙"]
    revival["revival/<br/>회생권 · 포인트 · 친구선물"]
    friend["friend/<br/>친구 요청"]
    notif["notification/<br/>푸시 · 스케줄러"]
    realtime["realtime/<br/>WebSocket · Publisher"]
  end

  auth --> SEC
  daily --> chat
  daily --> survival
  room --> chat
  survival --> realtime
  revival --> realtime
  chat --> realtime
  friend --> realtime
  notif --> realtime
```

| 모듈 | 책임 |
|------|------|
| `auth/` | 이메일·Kakao 로그인, JWT 발급/갱신, refresh 토큰 회전 |
| `daily/` | 일일 목표·회고 CRUD, 제출 시 채팅 시스템 메시지·마일스톤 발행 |
| `room/` | 방 생성·가입·멤버 관리, 리더 이양, 정원 변경 |
| `room/chat/` | 채팅 CRUD, Kudos, 시스템 메시지 훅 (`publishSystem`) |
| `survival/` | 06:00 KST 평가 잡, ACTIVE→YELLOW→RED, 스펙테이터, 방 규칙 |
| `revival/` | 무료 회생권·개인 포인트·친구 선물 회생, 방 포인트 풀 |
| `realtime/` | STOMP 엔드포인트, JWT CONNECT/SUBSCRIBE 검증, `RealtimePublisher` |

### 프론트엔드 레이어

```mermaid
flowchart TB
  subgraph screens["화면 (FE/app/)"]
    Today["(tabs)/today"]
    Rooms["rooms/[id]/chat"]
    Profile["profile/…"]
  end

  subgraph components["컴포넌트 (FE/src/components/)"]
    ChatUI["chat/<br/>ChatList · MessageBubble · SystemMessage"]
    SurvivalUI["survival/<br/>PoolStack · …"]
  end

  subgraph state["상태 & 통신"]
    QH["query/hooks/*<br/>useChatMessages · useSendChatMessage"]
    RT["realtime/client.ts<br/>싱글톤 STOMP 클라이언트"]
    API2["api/*.ts<br/>타입드 REST 래퍼"]
  end

  subgraph persist["영속화"]
    QC["TanStack Query<br/>→ AsyncStorage"]
    SS["expo-secure-store<br/>JWT 토큰"]
  end

  screens --> components
  components --> QH
  QH --> API2
  QH --> RT
  QH --> QC
  API2 --> SS
```

Provider 트리: `QueryProvider` → `AuthProvider` → `RealtimeProvider` → 화면. 상세는 [`docs/architecture-fe.md`](./docs/architecture-fe.md) · [`docs/architecture-be.md`](./docs/architecture-be.md).

## 서비스 흐름

### 인증 & API 요청

```mermaid
sequenceDiagram
  actor User as 사용자
  participant FE as FE apiRequest
  participant BE as BE AuthController
  participant DB as PostgreSQL

  User->>FE: 로그인 (이메일 / Kakao)
  FE->>BE: POST /auth/login 또는 /auth/kakao/exchange
  BE->>DB: 사용자 조회 · refresh 토큰 저장
  BE-->>FE: { accessToken, refreshToken, user }
  FE->>FE: expo-secure-store에 토큰 저장

  Note over FE,BE: 이후 모든 REST 호출
  FE->>BE: Authorization: Bearer accessToken
  alt 401 Unauthorized
    FE->>BE: POST /auth/refresh
    BE-->>FE: 새 access + refresh (기존 refresh 폐기)
    FE->>BE: 원래 요청 재시도
  else refresh 실패
    FE->>FE: 토큰 삭제 · 로그인 화면으로
  end
```

### 일일 회고 & 서바이벌 평가

day-boundary는 **06:00 Asia/Seoul**. 전날 회고 미제출 시 다음 날 06:00 평가에서 strike가 누적됩니다.

```mermaid
flowchart TB
  subgraph user_actions["사용자 액션 (당일)"]
    Goal["목표 작성<br/>POST /daily/goals"]
    Reflect["회고 제출<br/>POST /daily/reflections"]
  end

  subgraph hooks["트랜잭션 후 훅"]
    ChatGoal["ChatService.publishSystem<br/>kind=GOAL"]
    ChatReflect["ChatService.publishSystem<br/>kind=REFLECTION"]
    Milestone["ChatService.publishMilestones<br/>kind=MILESTONE"]
  end

  subgraph cron["06:00 KST (SurvivalStateEvaluatorJob)"]
    Eval["SurvivalStateService.evaluateRoom"]
    Compliant["준수 → +2 포인트"]
    Freeze["미스 + streak freeze 사용"]
    Yellow["ACTIVE → YELLOW<br/>(첫 strike)"]
    Red["YELLOW → RED<br/>(7일 rolling 2회)"]
  end

  subgraph realtime_out["실시간 방출"]
    Priv["/user/queue/private-survival"]
    Topic["/topic/rooms.{id}.survival"]
  end

  Goal --> ChatGoal
  Reflect --> ChatReflect
  Reflect --> Milestone
  ChatGoal --> WS["STOMP fan-out"]
  ChatReflect --> WS
  Milestone --> WS

  Eval --> Compliant
  Eval --> Freeze
  Eval --> Yellow
  Eval --> Red
  Yellow --> Priv
  Red --> Priv
  Red --> Topic
```

| 상태 | 의미 | 채팅 쓰기 |
|------|------|----------|
| `ACTIVE` | 정상 참여 | 가능 |
| `YELLOW` | 1회 미스 (grace) | 가능 |
| `RED` | 탈락 | 불가 → `SPECTATOR` 전환 |
| `SPECTATOR` | 관전 모드 | 읽기 전용 (BE·FE 이중 차단) |

### 회생(Revival) 경로

```mermaid
flowchart LR
  RED["RED / SPECTATOR"]
  Free["무료 회생권<br/>가입 시 1회"]
  Self["개인 포인트<br/>방 포인트 풀 차감"]
  Gift["친구 선물<br/>선물자 포인트 차감"]

  RED --> Free
  RED --> Self
  RED --> Gift

  Free --> Active["ACTIVE 복귀"]
  Self --> Active
  Gift --> Active

  Active --> RT2["SURVIVAL_STATE_CHANGE<br/>private-survival + topic"]
```

## 채팅 기능

방 단위 그룹 채팅. REST로 영속화하고 STOMP로 실시간 fan-out합니다. 송신자는 REST 응답과 WS echo가 겹치므로 **메시지 `id`로 dedupe**합니다.

### 메시지 종류 (`ChatMessageKind`)

| kind | 작성 주체 | 예시 |
|------|----------|------|
| `USER` | 멤버 | 일반 대화 |
| `GOAL` | 시스템 (`DailyService`) | "오늘의 목표를 작성했어요" |
| `REFLECTION` | 시스템 | "회고를 제출했어요" |
| `MILESTONE` | 시스템 | "alice님 15일 중 7일 완료!" |
| `SYSTEM` | 시스템 | 멤버 가입, 규칙 변경 안내 |
| `AUTO_LEAVE` | 시스템 | 미달 경고 후 자동 퇴장 |
| `KUDOS` | 멤버 (`KudosService`) | 응원 메시지 (하루 1회) |

`USER` 외 kind는 `ChatController`를 거치지 않고 `ChatService.publishSystem()` 등 서비스 훅으로만 기록됩니다.

### 사용자 메시지 송수신 흐름

```mermaid
sequenceDiagram
  actor Sender as 발신자
  actor Peer as 다른 멤버
  participant Screen as rooms/[id]/chat
  participant Hook as useSendChatMessage<br/>useChatRealtime
  participant REST as POST /rooms/{id}/messages
  participant SVC as ChatService
  participant DB as chat_messages
  participant Pub as RealtimePublisher
  participant WS as /topic/rooms.{id}.chat

  Note over Screen: 화면 마운트 시 useChatRealtime 구독
  Screen->>Hook: GET /messages (cursor 페이지)
  Hook->>REST: InfiniteQuery 초기 로드

  Sender->>Screen: MessageInput 전송
  Screen->>Hook: useSendChatMessage.mutate
  Hook->>REST: POST body
  REST->>SVC: sendUserMessage (SPECTATOR 차단)
  SVC->>DB: INSERT kind=USER
  SVC->>Pub: publishChatMessage
  Pub->>WS: MessageDto fan-out
  REST-->>Hook: MessageDto (REST 응답)
  Hook->>Hook: onSuccess → 캐시에 append (id=N)

  WS-->>Hook: 동일 MessageDto (id=N)
  Hook->>Hook: dedupe by id → skip (중복 렌더 방지)

  WS-->>Peer: MessageDto
  Peer->>Peer: useChatRealtime → 캐시 merge
```

**REST/WS dedupe 규칙** (FE `useChatRealtime`):
1. REST `onSuccess`가 먼저 캐시에 메시지를 넣음.
2. WS echo가 도착하면 모든 페이지에서 `id` 중복 검사 → 있으면 무시.
3. WS 끊김 시 폴링 fallback: 연결됨 30s / 끊김 8s (`useChatMessages`).

### 시스템 메시지 발행 훅

```mermaid
flowchart TB
  subgraph triggers["트리거"]
    Daily["DailyService<br/>목표·회고 저장"]
    Room["RoomService<br/>가입 · AUTO_LEAVE"]
    Rule["RoomRuleService<br/>규칙 변경"]
    Kudos["KudosService<br/>응원 전송"]
  end

  subgraph chat_svc["ChatService"]
    PS["publishSystem()<br/>REQUIRES_NEW"]
    PM["publishMilestonesForActor()"]
    PK["Kudos → chat row"]
  end

  subgraph out["출력"]
    DB2[("chat_messages")]
    RT3["RealtimePublisher<br/>/topic/rooms.{id}.chat"]
  end

  Daily --> PS
  Daily --> PM
  Room --> PS
  Rule --> PS
  Kudos --> PK
  PS --> DB2 --> RT3
  PM --> DB2
  PK --> DB2
```

`REQUIRES_NEW`로 분리된 트랜잭션: 채팅 fan-out 실패가 목표·회고·가입 등 주 트랜잭션을 롤백하지 않습니다.

### STOMP 구독 토픽

| 목적지 | 페이로드 | 구독 위치 (FE) |
|--------|---------|---------------|
| `/topic/rooms.{id}.chat` | `MessageDto` | `useChatRealtime` (채팅 화면) |
| `/topic/rooms.{id}.members` | 멤버 추가 | 방 상세 화면 |
| `/topic/rooms.{id}.survival` | 상태·리더 변경 | 서바이벌 UI |
| `/topic/rooms.{id}.points` | 포인트 풀 변경 | 지갑 화면 |
| `/topic/rooms.{id}.kudos` | Kudos 이벤트 | 채팅/지갑 |
| `/user/queue/notifications` | `RealtimeEvent` | `RealtimeProvider` (앱 전역) |
| `/user/queue/private-survival` | `SurvivalStateChange` | `RealtimeProvider` |

`JwtChannelInterceptor`가 CONNECT 시 JWT 검증, SUBSCRIBE 시 방 멤버십을 확인합니다. SockJS fallback 없음 — 네이티브 `WebSocket`만 사용.

### FE 채팅 UI 구조

```mermaid
flowchart TB
  ChatScreen["app/rooms/[id]/chat.tsx"]

  subgraph hooks2["훅"]
    UCM["useChatMessages<br/>InfiniteQuery + 폴링"]
    USM["useSendChatMessage<br/>optimistic append"]
    UCR["useChatRealtime<br/>STOMP merge + dedupe"]
    UMR["useMarkChatRead<br/>AsyncStorage 워터마크"]
  end

  subgraph ui["컴포넌트"]
    CL["ChatList<br/>FlashList inverted"]
    MB["MessageBubble<br/>USER → 말풍선"]
    SM["SystemMessage<br/>GOAL/REFLECTION/…"]
    MI["MessageInput<br/>SPECTATOR 시 숨김"]
    SB["SpectatorReadOnlyBanner"]
  end

  ChatScreen --> hooks2
  ChatScreen --> ui
  UCM --> CL
  MB --> SM
  UCR --> UCM
  USM --> UCM
```

읽음 처리: 채팅 화면이 열려 있는 동안 최신 메시지 `id`를 `AsyncStorage`에 저장하고, 채팅 탭 목록의 unread 배지는 `useRoomLastMessage` + `useLastReadId`로 계산합니다.

---

요청 envelope, JWT 라이프사이클, STOMP 토픽, REST/WS 중복 제거, 설정 경계는 [`docs/integration-architecture.md`](./docs/integration-architecture.md) 참고.

## 레포 레이아웃

```text
yeosal/
├── FE/                 Expo React Native 앱 (npm workspace)
│   ├── app/            expo-router 파일 라우트
│   └── src/            api · auth · components · domain · hooks · lib · providers · theme · types
├── BE/                 Spring Boot 3 API (Gradle)
│   └── src/main/java/com/yeosal/api/{auth,common,daily,friend,notification,profile,realtime,room,stats,user}
├── infra/              Docker Compose, nginx, deploy.sh, RUNBOOK-V11.md
├── scripts/            verify.sh · test.sh · build.sh (루트 `npm run …`이 호출)
├── docs/               product · architecture (FE/BE) · API contracts · data models · design system · test plan
├── _bmad/              BMad agent 하네스 자료
├── _bmad-output/       생성된 기획 산출물 (project-context.md, sprint-status.yaml, prd.md, …)
├── AGENTS.md           LLM 에이전트용 단축 규칙
├── CONTRIBUTING.md     stack-PR 머지 절차 (사고 기반 룰), 사전 검증
├── RUNBOOK.md          run / test / build 표준 명령
└── guide.md            ECC 하네스 설정 노트
```

파일 단위 깊이 있는 워크스루는 [`docs/source-tree-analysis.md`](./docs/source-tree-analysis.md).

## 사전 준비

- Node.js + npm (Expo SDK 54는 활성 LTS 노드 라인 지원)
- Java 21 (`BE/build.gradle`의 toolchain에 고정)
- Docker Desktop (Compose 스택 및 BE 이미지 빌드용)
- Android Studio + 에뮬레이터 (`npm run android` 시)
- Xcode (`npm run ios` 시)

## 빠른 시작

> [`RUNBOOK.md`](./RUNBOOK.md)가 표준 출처입니다. 아래는 가장 짧은 동선이고, 어긋나면 RUNBOOK을 보세요.

**한 번 설치하고 전체 검증.**

```bash
npm install                 # FE workspace 설치
bash scripts/verify.sh      # FE lint + typecheck + jest, BE gradle test/build, Docker 떠 있으면 BE 이미지 빌드까지
```

**모바일 앱을 운영 API로 실행** (기본).

```bash
cd FE
npm run android   # 또는: npm run ios
```

네이티브 모듈을 추가/변경한 직후(`expo-secure-store` 등)는 바이너리를 다시 깔아야 합니다 — Metro reload만으로는 안 됩니다:

```bash
adb uninstall app.yeosal.mobile && npm run android
```

**API + Postgres + nginx 풀스택을 로컬에서 Docker로.**

```bash
cd infra
cp .env.example .env        # POSTGRES_PASSWORD, YEOSAL_JWT_SECRET, KAKAO_* 채우기
docker compose up --build
curl http://localhost:8088/yeolsal/health
```

모바일 앱이 이 로컬 스택을 보게 하려면 `FE/.env`에 `EXPO_PUBLIC_API_BASE_URL`을 설정하세요 (Android 에뮬레이터는 `http://10.0.2.2:8088/yeolsal/api/v1`, iOS 시뮬레이터는 `http://localhost:8088/yeolsal/api/v1`). 변경 후 `npx expo start -c`로 Metro 캐시를 비우고 재시작.

**BE만 빠르게 돌리고 싶을 때.**

```bash
cd BE
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home gradle test --no-daemon
```

`bootRun`, EAS 빌드, Sentry sourcemap 업로드 등 나머지는 [`RUNBOOK.md`](./RUNBOOK.md)의 해당 섹션을 참고하세요.

## 검증

`scripts/verify.sh` (루트 `npm run verify`로도 노출)가 "feature 완료 선언 전 한 번은 돌려야 하는" 단일 명령입니다.

| 단계 | 실행 내용 |
|------|----------|
| FE lint | `FE && npm run lint` |
| FE typecheck | `FE && npm run typecheck` |
| FE 단위 테스트 | `FE && npm test` |
| BE 테스트 | `BE && gradle test` (Testcontainers PostgreSQL — H2는 의도적으로 거부) |
| BE 빌드 | `BE && gradle build` |
| Docker 이미지 | Docker 데몬이 닿으면 `docker build` |

커버리지 목표는 도메인·서비스 로직 기준 80%. trivial getter나 config 클래스는 제외.

## 환경 변수 & 시크릿

`.env` 파일은 커밋하지 않습니다. `infra/.env.example`과 `FE/.env.example`이 source of truth.

| 변수 | 위치 | 비고 |
|------|------|------|
| `YEOSAL_JWT_SECRET` | BE | 32자 이상 필수. `dev-only-change-me-…` 기본값은 `StartupConfigValidator`가 부팅 시점에 거부. |
| `KAKAO_CLIENT_ID` | BE | Kakao REST API 키. **클라이언트에 노출 금지.** |
| `KAKAO_REDIRECT_URI` | BE | Kakao Developers에 등록한 값과 정확히 일치해야 함. |
| `KAKAO_MOBILE_REDIRECT_URI` | BE | 앱으로 돌아가는 딥링크 (예: `yeosal://auth/kakao`). |
| `POSTGRES_PASSWORD` | infra | Compose 전용 Postgres 자격증명. |
| `EXPO_PUBLIC_API_BASE_URL` | FE | 기본값은 운영 URL. 로컬 개발 시 오버라이드. `EXPO_PUBLIC_*` 키만 클라이언트 번들에 포함됨. |
| `EXPO_PUBLIC_SENTRY_DSN` | FE | 비워두면 Sentry 비활성. `src/lib/sentry.ts`가 no-op 경로 처리. |

Kakao 셋업과 Sentry 와이어링 상세는 [`RUNBOOK.md`](./RUNBOOK.md) 12·15절.

## 기여 & 워크플로

- `main`에서 `feat/…`, `fix/…`, `chore/…` 브랜치 분기. TDD 분할(`RED → GREEN → refactor`)은 별도 커밋으로 환영.
- Push 전: BE 변경이면 `cd BE && gradle test`, FE 변경이면 `cd FE && npm run lint && npm run typecheck && npm test`. Feature 완료 선언 전: `bash scripts/verify.sh`.
- **stack PR은 base chain 가장 아래부터 위로, `Delete branch` 켠 채 머지** — V7/V8 운영 미반영 사고가 있었습니다. stack PR을 열기 전 [`CONTRIBUTING.md`](./CONTRIBUTING.md) 필독.
- 스키마 변경은 Flyway 마이그레이션: `BE/src/main/resources/db/migration/V<N>__<slug>.sql`. `<N>`은 비어 있는 가장 작은 정수, 멱등 SQL 권장. partial unique expression index는 서비스 코드의 `INSERT … ON CONFLICT … WHERE …` 절과 정확히 매칭돼야 함.

## 문서 지도

- [`docs/index.md`](./docs/index.md) — 생성된 문서 인덱스, AI 보조 개발 진입점
- [`docs/product.md`](./docs/product.md) — 1차 MVP 시기의 스코프 (서바이벌 피보팅 전 스냅샷)
- [`_bmad-output/planning-artifacts/product-brief-yeolsal-distillate.md`](./_bmad-output/planning-artifacts/product-brief-yeolsal-distillate.md) — 서바이벌 메커닉 정식 출처 (locked decisions, rejected ideas, 시나리오)
- [`_bmad-output/planning-artifacts/prd.md`](./_bmad-output/planning-artifacts/prd.md) — 현재 PRD
- [`docs/architecture-fe.md`](./docs/architecture-fe.md) · [`docs/architecture-be.md`](./docs/architecture-be.md) — 표면별 아키텍처
- [`docs/api-contracts-be.md`](./docs/api-contracts-be.md) · [`docs/data-models-be.md`](./docs/data-models-be.md) — REST 표면과 영속 스키마
- [`docs/integration-architecture.md`](./docs/integration-architecture.md) — REST/STOMP 통합 계약
- [`docs/design-system.md`](./docs/design-system.md) — Risograph + neobrutalist 토큰
- [`docs/deployment-guide.md`](./docs/deployment-guide.md) — 운영 배포 절차
- [`AGENTS.md`](./AGENTS.md), [`_bmad-output/project-context.md`](./_bmad-output/project-context.md) — LLM 에이전트 운영 규칙 (시간대, 시크릿, day-boundary, 안티패턴)

## 상태

비공개 프로젝트. 모바일은 EAS로 빌드(`preview` = APK, `production` = AAB / iOS). Docker Compose 스택이 운영에서 외부 포트 `8088`로 API를 띄우고 `https://api.rearleg.com/yeolsal` 뒤에서 서비스됩니다.

## 라이선스

[PolyForm Noncommercial License 1.0.0](./LICENSE) — 상업적 이용 금지. 영리 활동(운영·배포·재판매·사내 도입 등)에 사용하려면 저작자의 별도 허가가 필요합니다.
