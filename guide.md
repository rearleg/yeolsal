# React Native + Spring 앱 개발용 ECC/Codex 하네스 가이드

이 문서는 `everything-claude-code`를 로컬 지식/워크플로 소스로 삼고, Codex를 실제 설계, 계획, 구현, 테스트, 빌드 작업의 주 실행기로 쓰기 위한 단계별 하네스 구성 가이드다.

## 1. 목표

최종 하네스의 목표는 다음이다.

- React Native 모바일 앱과 Spring Boot 백엔드를 한 저장소에서 일관되게 개발한다.
- 설계와 계획은 먼저 문서화하고 승인한 뒤 구현한다.
- 구현은 TDD를 기본값으로 하고, 모바일/백엔드/계약/API 테스트를 분리한다.
- 빌드 실패는 작은 단위로 고치고, 최종 병합 전 검증 루프를 반드시 돈다.
- ECC의 에이전트, 스킬, 룰, 명령 프롬프트를 Codex에서 재사용한다.

## 2. ECC에서 확인한 핵심 구성

`everything-claude-code`는 단순 설정 모음이 아니라 에이전트 하네스다. 주요 자산은 다음 위치에 있다.

- `AGENTS.md`: 전체 개발 원칙, 에이전트 사용 기준, 테스트/보안/워크플로 요구사항.
- `.codex/AGENTS.md`: Codex 전용 보강 지침. Codex는 `AGENTS.md` 중심으로 작동하고, 스킬은 `.agents/skills/`에서 읽는 구조다.
- `.codex/config.toml`: Codex 기본 설정 예시. `workspace-write` 샌드박스, MCP, multi-agent 설정을 포함한다.
- `skills/`: 실제 유지보수되는 워크플로 본문. Spring Boot, API 설계, TDD, 검증, 프론트엔드 패턴 등이 있다.
- `commands/`: Claude Code slash command 호환 레이어. Codex에서는 이 내용을 프롬프트/절차로 재사용한다.
- `rules/`: 항상 따라야 하는 공통/언어별 규칙.
- `agents/`: planner, architect, tdd-guide, code-reviewer, security-reviewer, java-reviewer, typescript-reviewer, build-error-resolver 등 역할별 지침.
- `scripts/sync-ecc-to-codex.sh`: ECC 자산을 `~/.codex`로 동기화하는 스크립트.

React Native + Spring 프로젝트에 특히 중요한 ECC 자산은 다음이다.

- 설계/계획: `agents/planner.md`, `agents/architect.md`, `commands/plan.md`, `commands/feature-dev.md`
- 백엔드: `skills/springboot-patterns`, `skills/springboot-tdd`, `skills/springboot-security`, `skills/springboot-verification`, `skills/api-design`
- 모바일/프론트엔드: `skills/frontend-patterns`, `skills/android-clean-architecture`, `rules/typescript`, `rules/web`
- 품질: `skills/tdd-workflow`, `skills/verification-loop`, `commands/build-fix.md`, `commands/code-review.md`
- 운영: `commands/orchestrate.md`, `skills/dmux-workflows`, `skills/autonomous-agent-harness`

## 3. 권장 저장소 구조

새 앱 저장소는 모노레포로 시작하는 편이 Codex와 ECC 하네스 운영에 가장 쉽다.

```text
app-root/
├── AGENTS.md
├── guide.md
├── docs/
│   ├── product.md
│   ├── architecture.md
│   ├── api-contract.md
│   ├── test-plan.md
│   └── decisions/
├── apps/
│   └── mobile/              # React Native 또는 Expo 앱
├── services/
│   └── api/                 # Spring Boot API
├── packages/
│   └── contracts/           # OpenAPI, generated types, shared schemas
├── e2e/
│   ├── mobile/              # Detox/Maestro/Appium 등
│   └── api/                 # API smoke/integration scripts
├── infra/
│   ├── docker-compose.yml
│   └── local/
├── scripts/
│   ├── verify.sh
│   ├── build.sh
│   └── test.sh
└── everything-claude-code/   # submodule 또는 vendored reference
```

`everything-claude-code`는 앱 코드와 직접 섞기보다 `everything-claude-code/` 디렉터리 또는 git submodule로 보관하고, 필요한 자산만 프로젝트 루트의 `AGENTS.md`, `.codex/`, `.agents/skills/`로 복사/동기화한다.

## 4. Step 1: ECC를 프로젝트에 배치

이미 이 워크스페이스에는 `everything-claude-code`가 있다. 새 앱 저장소에서는 다음 중 하나를 선택한다.

```bash
# 선택 A: submodule로 고정
git submodule add https://github.com/affaan-m/everything-claude-code.git everything-claude-code

# 선택 B: 단순 clone
git clone https://github.com/affaan-m/everything-claude-code.git
```

패키지 의존성이 필요한 ECC 스크립트를 쓰려면 ECC 루트에서 설치한다.

```bash
cd everything-claude-code
npm install
```

## 5. Step 2: Codex 설정 동기화

ECC의 Codex 동기화 스크립트는 다음을 수행한다.

- `~/.codex/AGENTS.md`에 ECC 지침을 marker 기반으로 병합한다.
- `commands/*.md`를 Codex 프롬프트로 변환한다.
- `.codex/agents` 역할 설정을 복사한다.
- MCP 설정을 `~/.codex/config.toml`에 add-only 방식으로 병합한다.
- 전역 git 안전 훅을 설치한다.

먼저 dry run으로 확인한다.

```bash
cd everything-claude-code
./scripts/sync-ecc-to-codex.sh --dry-run
```

문제가 없으면 적용한다.

```bash
./scripts/sync-ecc-to-codex.sh
```

MCP 설정까지 ECC 권장값으로 갱신하려면 명시적으로 실행한다.

```bash
./scripts/sync-ecc-to-codex.sh --update-mcp
```

주의: 기존 `~/.codex/config.toml`과 `~/.codex/AGENTS.md`는 백업되지만, 팀 공용 머신에서는 적용 전 diff를 확인한다.

## 6. Step 3: 프로젝트 로컬 Codex 기준 설정

앱 저장소 루트에 `.codex/config.toml`을 둔다. ECC의 `.codex/config.toml`을 복사한 뒤 프로젝트에 맞게 줄이는 방식이 좋다.

```bash
mkdir -p .codex
cp everything-claude-code/.codex/config.toml .codex/config.toml
```

React Native + Spring 앱의 기본값은 다음을 권장한다.

```toml
approval_policy = "on-request"
sandbox_mode = "workspace-write"
web_search = "live"
persistent_instructions = "Follow project AGENTS.md. Prefer TDD, contract-first API changes, and explicit verification."

[features]
multi_agent = true

[agents]
max_threads = 6
max_depth = 1

[agents.explorer]
description = "Read-only codebase explorer."
config_file = "agents/explorer.toml"

[agents.reviewer]
description = "Correctness, security, and missing-test reviewer."
config_file = "agents/reviewer.toml"

[agents.docs_researcher]
description = "Framework and API documentation verifier."
config_file = "agents/docs-researcher.toml"
```

API 문서 확인이 잦다면 Context7, Playwright, GitHub MCP를 유지한다. 비용/속도 때문에 모든 MCP를 항상 켜기보다는 필요한 서버만 둔다.

## 7. Step 4: 프로젝트 `AGENTS.md` 작성

루트 `AGENTS.md`는 Codex가 가장 먼저 읽는 운영 계약이다. ECC의 루트 `AGENTS.md`와 `.codex/AGENTS.md`를 그대로 붙여 넣기보다, 앱 프로젝트용으로 압축해서 둔다.

권장 골격:

```markdown
# Project Agent Instructions

## Product
- React Native mobile app in `apps/mobile`.
- Spring Boot API in `services/api`.
- Shared API contract in `packages/contracts`.

## Workflow
1. For complex work, produce a plan before editing.
2. Use TDD: failing test, minimal implementation, refactor.
3. Update API contract before changing client/server integration.
4. Run targeted verification after each slice and full verification before handoff.

## Architecture Rules
- Mobile calls backend only through typed API client generated from contract.
- Spring controllers expose DTOs, not entities.
- Domain logic stays outside controllers and React components.
- Secrets live in environment files or secret manager, never in source.

## Verification
- Mobile: lint, typecheck, unit/component tests, platform build.
- API: compile, unit tests, integration tests, coverage, security scan.
- Contract: OpenAPI/schema validation and generated client check.
- E2E: at least one critical happy path before release.

## ECC Skills To Apply
- springboot-patterns, springboot-tdd, springboot-verification
- api-design
- frontend-patterns
- tdd-workflow
- verification-loop
- security-review
```

## 8. Step 5: 필요한 스킬만 프로젝트에 복사

Codex는 `.agents/skills/`를 스킬 표면으로 사용할 수 있다. 앱 프로젝트에는 전체 ECC 스킬을 복사하지 말고 필요한 것만 둔다.

```bash
mkdir -p .agents/skills
cp -R everything-claude-code/skills/springboot-patterns .agents/skills/
cp -R everything-claude-code/skills/springboot-tdd .agents/skills/
cp -R everything-claude-code/skills/springboot-security .agents/skills/
cp -R everything-claude-code/skills/springboot-verification .agents/skills/
cp -R everything-claude-code/skills/api-design .agents/skills/
cp -R everything-claude-code/skills/frontend-patterns .agents/skills/
cp -R everything-claude-code/skills/android-clean-architecture .agents/skills/
cp -R everything-claude-code/skills/tdd-workflow .agents/skills/
cp -R everything-claude-code/skills/verification-loop .agents/skills/
cp -R everything-claude-code/skills/security-review .agents/skills/
```

스킬을 많이 넣을수록 지침 표면이 커진다. 기본은 8~12개 정도로 시작하고, 필요할 때 추가한다.

## 9. Step 6: 룰 세트 구성

룰은 항상 지켜야 하는 팀 표준이다.

```bash
mkdir -p rules
cp -R everything-claude-code/rules/common rules/
cp -R everything-claude-code/rules/typescript rules/
cp -R everything-claude-code/rules/java rules/
cp -R everything-claude-code/rules/web rules/
```

`AGENTS.md`에는 다음 문장을 넣어 Codex가 룰을 보게 한다.

```markdown
Before implementation or review, read relevant files under `rules/common`, `rules/typescript`, `rules/java`, and `rules/web`.
```

## 10. Step 7: 앱 골격 생성

### 모바일

`apps/mobile`에는 React Native 앱을 둔다. 팀이 Expo를 허용하면 Expo 기반으로 시작하는 것이 개발/빌드/테스트 루프가 단순하다. 네이티브 모듈 요구가 강하거나 기존 Android/iOS 코드가 많으면 React Native CLI를 선택한다.

권장 모바일 구조:

```text
apps/mobile/
├── src/
│   ├── app/
│   ├── features/
│   ├── shared/
│   ├── api/
│   └── test/
├── __tests__/
├── package.json
└── tsconfig.json
```

모바일 기본 검증 명령은 `package.json`에 맞춘다.

```json
{
  "scripts": {
    "lint": "eslint .",
    "typecheck": "tsc --noEmit",
    "test": "jest",
    "build:android": "echo configure android build",
    "build:ios": "echo configure ios build"
  }
}
```

### 백엔드

`services/api`에는 Spring Boot 앱을 둔다. Maven 또는 Gradle 중 팀 표준 하나만 고른다.

권장 백엔드 구조:

```text
services/api/
├── src/main/java/.../
│   ├── config/
│   ├── domain/
│   ├── application/
│   ├── infrastructure/
│   └── interfaces/
├── src/test/java/.../
├── pom.xml 또는 build.gradle
└── src/main/resources/
```

Spring 계층 기준:

- `interfaces`: controller, request/response DTO
- `application`: service/use case
- `domain`: domain model, domain policy
- `infrastructure`: JPA entity, repository implementation, external clients
- `config`: security, CORS, observability, profiles

## 11. Step 8: API 계약 우선 개발

모바일과 백엔드가 동시에 바뀌는 기능은 반드시 계약을 먼저 고친다.

권장 방식:

1. `docs/api-contract.md`에 사람이 읽는 요구사항을 적는다.
2. `packages/contracts/openapi.yaml` 또는 equivalent schema를 수정한다.
3. Spring DTO/controller test를 먼저 작성한다.
4. 모바일 API client type을 생성하거나 수동 타입을 갱신한다.
5. 양쪽 테스트가 같은 예시 payload를 사용하게 한다.

Codex 프롬프트 예시:

```text
Apply the api-design, springboot-patterns, and frontend-patterns skills.
Design the API contract for [feature].
Update docs/api-contract.md first.
Then propose the OpenAPI shape, Spring DTOs, and React Native API client types.
Do not implement until the plan is approved.
```

## 12. Step 9: 설계 단계 운영

새 기능은 바로 구현하지 말고 다음 순서로 Codex에 요청한다.

```text
Use ECC planning style.
Read AGENTS.md, docs/product.md, docs/architecture.md, and relevant rules.
Plan [feature].
Include:
- requirements restatement
- affected mobile/backend/contract files
- test plan
- security risks
- rollout/build risks
Wait for confirmation before editing.
```

산출물은 `docs/decisions/YYYY-MM-DD-feature-name.md` 또는 `docs/architecture.md`에 반영한다. 임시 계획은 `.codex/plans/`나 `docs/plans/` 중 하나를 정해 보관한다.

## 13. Step 10: 구현 단계 운영

승인 후에는 슬라이스를 작게 나눈다.

1. Contract slice: schema, example payload, generated/client type.
2. Backend slice: failing controller/service/repository tests, implementation, local verify.
3. Mobile slice: failing hook/component tests, implementation, local verify.
4. Integration slice: API smoke test, mobile happy path, error state.
5. Review slice: code review, security review, build fix.

Codex 프롬프트 예시:

```text
Implement phase 1 only from docs/plans/[plan].md.
Use TDD.
Start by adding failing tests.
After tests fail for the expected reason, implement the minimal code.
Run targeted verification for the touched package.
Stop and report before moving to phase 2.
```

## 14. Step 11: 테스트 전략

### 모바일 테스트

- Unit: pure utilities, API client mapping, validation.
- Component: screen state, loading/error/empty states, form validation.
- Integration: navigation plus mocked API.
- E2E: critical user journey only, not every screen.

권장 명령:

```bash
cd apps/mobile
npm run lint
npm run typecheck
npm test -- --coverage
npm run build:android
```

### Spring 테스트

- Unit: domain policy, service logic.
- Web layer: `@WebMvcTest`, MockMvc.
- Persistence: `@DataJpaTest`.
- Integration: `@SpringBootTest`, Testcontainers.
- Security: auth/authorization, CORS, validation, error leakage.

Maven 기준:

```bash
cd services/api
mvn -T 4 test
mvn -T 4 verify
mvn jacoco:report
```

Gradle 기준:

```bash
cd services/api
./gradlew test jacocoTestReport
./gradlew clean build
```

## 15. Step 12: 빌드/검증 스크립트

루트에 공통 스크립트를 둬서 Codex와 사람이 같은 명령을 실행하게 한다.

`scripts/test.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

(cd apps/mobile && npm run lint && npm run typecheck && npm test -- --coverage)
(cd services/api && mvn -T 4 test)
```

`scripts/build.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

(cd apps/mobile && npm run build:android)
(cd services/api && mvn -T 4 clean verify)
```

`scripts/verify.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

git diff --stat
./scripts/test.sh
./scripts/build.sh
```

프로젝트가 Gradle을 쓰면 `mvn` 명령을 `./gradlew`로 바꾼다.

## 16. Step 13: 빌드 실패 처리

ECC의 `build-fix` 절차를 Codex에 그대로 적용한다.

프롬프트:

```text
Apply ECC build-fix workflow.
Detect the failing build command.
Group errors by file.
Fix one error class at a time with minimal diffs.
After each fix, rerun the smallest relevant command.
Stop if the same error persists after 3 attempts or if a fix requires architecture changes.
```

실행 순서:

1. 가장 작은 실패 명령부터 실행한다.
2. import/type/config 오류를 먼저 고친다.
3. 테스트 기대값 변경은 마지막 수단으로만 한다.
4. build가 green이 되면 full verify를 실행한다.

## 17. Step 14: 코드 리뷰와 보안 리뷰

기능 완료 후 Codex에 다음 리뷰를 요청한다.

```text
Review the current diff using ECC code-reviewer and security-reviewer criteria.
Focus on:
- correctness bugs
- missing tests
- mobile/backend contract drift
- auth and authorization
- input validation
- secret leakage
- error handling
- build or release risks
Return findings first, ordered by severity, with file/line references.
```

Spring 보안 체크:

- controller 입력값에 validation이 있는가.
- entity가 API 응답으로 직접 노출되지 않는가.
- 인증/인가 경계가 테스트되는가.
- CORS가 wildcard가 아닌가.
- exception message가 그대로 응답되지 않는가.
- secrets가 `application.yml`에 하드코딩되지 않았는가.

React Native 보안 체크:

- token 저장소가 안전한가.
- API base URL과 secret이 구분되어 있는가.
- 민감 로그가 남지 않는가.
- deep link, push notification payload, local storage 처리에 검증이 있는가.

## 18. Step 15: 설계부터 빌드까지 표준 프롬프트

### 설계

```text
Read AGENTS.md and relevant docs.
Use planner and architect style from ECC.
Design [feature] for React Native mobile + Spring Boot API.
Return requirements, architecture, API contract, data model, tests, and risks.
Wait for approval before edits.
```

### 계획

```text
Create a step-by-step implementation plan for [feature].
Split into contract, backend, mobile, integration, verification phases.
For each phase list files, tests, commands, and done criteria.
Do not edit files yet.
```

### 구현

```text
Implement phase [N] only.
Use TDD.
Write failing tests first, run them, then implement minimal code.
Keep diffs scoped.
Run targeted verification and report results.
```

### 테스트

```text
Apply ECC verification-loop.
Run the smallest relevant tests first, then package-level tests.
Report pass/fail, coverage if available, and unresolved risks.
Do not hide failing tests.
```

### 빌드

```text
Run full build for mobile and API.
If it fails, apply ECC build-fix workflow.
Fix one error class at a time and rerun the relevant command after each fix.
```

### 최종 리뷰

```text
Review the final diff as ECC code-reviewer + security-reviewer.
Find bugs, regressions, missing tests, and security issues.
Then provide a concise release readiness summary.
```

## 19. Step 16: 병렬 작업 하네스

큰 기능은 Codex multi-agent나 tmux/worktree orchestration으로 나눈다.

권장 분리:

- Explorer: 기존 코드 구조와 영향 범위 조사. 읽기 전용.
- Backend worker: `services/api`, `packages/contracts` 일부.
- Mobile worker: `apps/mobile`, generated client 사용부.
- Reviewer: diff 리뷰, 보안, 테스트 누락.

동시에 쓰는 파일이 겹치지 않게 책임 범위를 정한다.

```text
Backend worker owns services/api and backend tests.
Mobile worker owns apps/mobile and mobile tests.
Only the main session owns packages/contracts/openapi.yaml.
No worker should revert changes made by others.
```

worktree를 쓸 때는 ECC의 `scripts/orchestrate-worktrees.js` 패턴을 참고한다. Codex 단독 작업에서는 우선 in-process multi-agent로 충분하고, 독립 빌드가 필요한 장기 작업에서만 worktree를 쓴다.

## 20. Step 17: 완료 기준

기능이 완료되려면 아래가 모두 참이어야 한다.

- 계획 문서 또는 결정 기록이 최신이다.
- API 계약과 모바일 client/server 구현이 일치한다.
- 백엔드 unit/web/integration 테스트가 통과한다.
- 모바일 lint/typecheck/unit/component 테스트가 통과한다.
- 최소 1개 핵심 플로우가 E2E 또는 smoke 수준으로 검증됐다.
- `scripts/verify.sh` 또는 동등한 full verification이 통과했다.
- code review/security review에서 critical/high 이슈가 없다.
- 빌드 산출물 생성 명령이 문서화되어 있다.

## 21. 추천 작업 루프

매 기능마다 이 순서를 반복한다.

1. `docs/product.md`에 요구사항을 적는다.
2. Codex에 ECC planning style로 설계를 요청한다.
3. 계획을 `docs/plans/` 또는 `docs/decisions/`에 저장한다.
4. contract를 먼저 확정한다.
5. backend를 TDD로 구현한다.
6. mobile을 TDD로 구현한다.
7. integration/smoke 테스트를 붙인다.
8. targeted verify를 실행한다.
9. full verify를 실행한다.
10. ECC review/security 기준으로 diff를 리뷰한다.
11. 문서와 빌드 명령을 업데이트한다.

이 루프가 유지되면 `everything-claude-code`는 지식/정책/워크플로 레이어가 되고, Codex는 실제 코드 변경과 검증을 수행하는 실행 레이어가 된다.
