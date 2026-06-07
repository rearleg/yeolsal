# Story 6.3: RUNBOOK + native module reinstall guidance

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a developer (and the next on-call engineer who pulls main on a stale machine),
I want clear, durable documentation in the repo-root `RUNBOOK.md` that the Kakao Share SDK is a native module addition requiring `adb uninstall app.yeosal.mobile` + clean rebuild (and the iOS equivalent), plus an EAS `preview` profile verification path and a CI-integration guard,
So that my dev cycle doesn't silently fail with "the SDK call is undefined" / "module not found in binary" / "share button does nothing on tap" errors, and so that any future FE CI workflow (none exists today) cannot ship a green build that nevertheless misses the native module.

## Acceptance Criteria

> 이 스토리는 **Epic 6 KakaoTalk Viral Loop 의 ops-doc 마무리** 다. Story 6.2 (PR #91 squash-merged 2026-06-07 as `7968dee`) 가 새 native module 3 개 (실제 shipped: **2 개** — `@react-native-kakao/share` + `@react-native-kakao/core`; `expo-config-plugin` 은 v2 SDK 가 `core` 안에 번들하여 npm 에 별도 패키지 없음. AC 단계 deviation 은 Story 6.2 dev note + 본 스토리 AC1 의 package 목록에 반영됨) 와 deep-link 핸들러, `app.config.ts` 의 Kakao plugin 등록, `EXPO_PUBLIC_KAKAO_NATIVE_APP_KEY` 환경 변수를 도입했고, PR description 의 "Post-merge user action" 섹션에 임시로 5 줄을 적었다. 본 스토리는 그 임시 노트를 **repo-root `RUNBOOK.md` 의 영구 섹션** 으로 옮기고, EAS `preview` profile smoke 절차와 (현재 부재인) FE CI 의 미래 가드를 명시한다.
>
> **DOCS-ONLY story.** 0 lines of TypeScript / Java / SQL. 핵심 산출:
>
> 1. **`RUNBOOK.md` 의 §3 ("Android Emulator에서 앱 실행") 확장 또는 새 §3a 추가** — 현재 line 70-77 의 generic `expo-secure-store` 예시는 **유지**하고 (rule 의 일반론 보존), 그 바로 다음에 **Kakao Share SDK 전용 절차** 를 명시. project-context.md:132, architecture §5.2 line 520, PRD FR-8.6.6, NFR-9.8.5 가 모두 이 한 문서에 수렴.
> 2. **iOS equivalent 절차** 추가 — 기존 RUNBOOK 의 §3 는 Android 만 다룸. Native module 의 iOS clean rebuild 경로 (`cd FE/ios && rm -rf build Pods && pod install && cd .. && npx expo run:ios`) 를 명시. (Simulator app 의 명시적 uninstall 은 Long-press → "Remove App" 또는 `xcrun simctl uninstall booted app.yeosal.mobile`.)
> 3. **EAS `preview` profile smoke 단계** — `eas build --profile preview --platform android` 후 device 에 APK 설치 → KakaoTalk 설치된 device 에서 VERIFY-A/B/C 가 Story 6.2 의 acceptance gate 임을 명시. `FE/eas.json` 의 `preview.android.buildType = apk` 사실 잠금.
> 4. **CI integration 가드** — FE 는 현재 CI workflow 가 ZERO (`.github/workflows/` 디렉터리에 BE-only `be-it-boot-smoke.yml` 하나뿐). RUNBOOK 은 "FE CI 가 미래에 추가될 경우" 의 가드 한 줄 — `FE/package.json` 변경 PR 은 clean native rebuild 단계를 반드시 포함해야 함. EAS 빌드 (managed) 는 매 빌드 fresh prebuild + native compile 이므로 별도 조치 불필요.
> 5. **Kakao Developers Console 의 platform 등록 요구사항** 한 단락 — Bundle ID `app.yeosal.mobile` (Android + iOS) 가 Kakao Console 의 앱 platform 정보에 등록되어야 SDK init 성공. 미등록 시 SDK 의 `shareFeedTemplate` 가 "앱 정보를 찾을 수 없음" silent fail. (이 단락은 RUNBOOK §12 "Kakao REST API 설정" 의 자매 절로 §12.1 또는 § 신규 13 으로 추가하는 게 자연스러움 — dev 결정.)
> 6. **`EXPO_PUBLIC_KAKAO_NATIVE_APP_KEY` 환경 변수의 전체 lifecycle** — Story 6.2 가 `FE/.env.example` 의 placeholder 만 등록. 본 스토리가 RUNBOOK 에 (a) Kakao Console 에서 Native App Key 가져오기, (b) dev 머신 `FE/.env`, (c) app config 해석 시 읽을 수 있는 EAS Environment Variable 등록, (d) Native App Key 가 REST API Key 와 다른 키임을 명시 (project-context.md:235 의 REST API Key 금지 rule 보존).
>
> **NO source code change** (FE/BE 양쪽). **NO new test file** (docs-only — tests N/A; AC9 의 docs-shape verification grep checks 가 대체 게이트). **NO new migration** (V13 latest 유지). **NO `tokens.json` 변경**. **NO new endpoint / STOMP topic / push channel**. **NO `app.json` / `app.config.ts` / `eas.json` 변경** (Story 6.2 가 모두 잠근 값). **NO `.well-known/*` hosting** — Universal Link 의 OS verification 은 `yeolsal.app` 의 정적 파일 호스팅이 필요한 별도 infra 작업이며, Story 6.3 의 epic AC 4 줄 어디에도 명시되지 않음. RUNBOOK 안에 한 줄 **"Story 6.3 OOS — 별도 infra PR 또는 future-story 에서 다룰 항목"** 으로만 표기.

### AC1 — Repo-root `RUNBOOK.md` 의 §3 확장 (PRIMARY EDIT)

**Given** Story 6.2 가 `@react-native-kakao/share` + `@react-native-kakao/core` 두 native module 을 `FE/package.json` 에 도입했다
**And** 현재 `RUNBOOK.md:70-77` 은 `expo-secure-store` 를 generic 예시로 한 native-module rebuild 절차를 갖고 있다
**When** 본 스토리가 §3 를 편집한다
**Then** 다음 변경:

1. 기존 line 70-77 의 generic 절차는 **유지** — `expo-secure-store` 가 historic 발화점이라는 사실은 보존.
2. 바로 다음 위치에 새 sub-section **"### Kakao Share SDK (Story 6.2, FR-8.6.6 / NFR-9.8.5)"** 추가:

   ```markdown
   ### Kakao Share SDK (Story 6.2, FR-8.6.6 / NFR-9.8.5)

   Kakao Share SDK 는 v1 의 **두 번째 native module 추가** (첫 번째 = `expo-secure-store`).
   `FE/package.json` 의 native module 셋은 현재:

   - `expo-secure-store` (인증 토큰 저장)
   - `@react-native-kakao/share` (Story 6.2 — Kakao 공유 SDK)
   - `@react-native-kakao/core` (Story 6.2 — Kakao SDK 초기화 + expo-config-plugin 번들)

   `npm install` 만으로는 새 native code 가 기존 앱 바이너리에 포함되지 않습니다.
   Metro reload, `expo start -c`, JS hot-reload 어느 것도 native module 을 binary 에 inject 하지 않습니다.

   **Android (Emulator / 실기기 공통):**

       cd FE
       adb uninstall app.yeosal.mobile
       npx expo prebuild --clean
       npx expo run:android

   **iOS Simulator:**

       cd FE
       xcrun simctl uninstall booted app.yeosal.mobile   # Simulator 부팅된 경우
       npx expo prebuild --clean
       npx expo run:ios

   **iOS 실기기:**

       # 홈 화면에서 기존 앱 삭제 후:
       cd FE
       npx expo prebuild --clean
       npx expo run:ios --device

   재빌드 후 첫 실행에서 콘솔에 `Kakao SDK initialized` 로그가 떠야 정상 (`FE/src/lib/KakaoSdkBootstrap.tsx` 의 `initializeKakaoSDK(...)` 호출). 로그가 없으면 `EXPO_PUBLIC_KAKAO_NATIVE_APP_KEY` 가 비어 있거나 plugin 등록이 누락된 것이며, 이 경우 share 버튼 탭이 silent no-op.
   ```

3. (선택) line 77 의 마지막 문장 `단순 reload나 Metro cache 초기화만으로는 native module이 앱에 포함되지 않습니다.` 는 본 스토리가 만지지 않음 (강한 일반 명제로 유지).

**And** 추가된 sub-section 의 모든 명령은 dev 가 복사-실행 가능한 형태 (placeholder 변수 없음, 실제 Bundle ID `app.yeosal.mobile` 사용). PRD ref: FR-8.6.6. Architecture ref: §3.3, §5.2 line 520.

### AC2 — EAS `preview` profile smoke 절차 명시 (RUNBOOK §6 또는 새 §6.1)

**Given** Story 6.2 의 VERIFY-A/B/C manual smoke 가 KakaoTalk 가 설치된 device 를 요구한다 (Story 6.2 AC12 line 750)
**And** dev host (보통 Mac Simulator) 는 KakaoTalk 미설치 환경이라 smoke 실행 불가
**When** RUNBOOK 의 §6 ("Android APK 빌드") 가 편집된다
**Then** §6 의 "방법 A: EAS로 APK 만들기" 절 끝부분 (line 153 직후) 에 다음 단락 추가:

```markdown
### Story 6.2 Kakao 공유 SDK smoke

`preview` 프로파일 APK 가 빌드된 직후 (e.g. `eas build --platform android --profile preview`), 실기기에서 다음 세 시나리오를 손으로 검증합니다 (Story 6.2 AC12 의 VERIFY-A/B/C):

1. **VERIFY-A** — 임의 방의 invite 발급 → InviteCodeSheet 의 "🥥 카카오로 공유" primary 버튼 탭 → KakaoTalk 앱 (또는 web dialog) 로 hand-off → preview card image + room name + "같이 살아남자" 버튼이 노출됨.
2. **VERIFY-B** — VERIFY-A 의 메시지를 같은 단말의 KakaoTalk 로 받기 (다른 카카오 계정) → preview card 탭 → 본인 앱으로 deep-link 진입 → `app/join.tsx` 의 invite-code 필드가 `?code=X` 로 자동 채워지고 auto-submit.
3. **VERIFY-C** — `max_members` 가득 찬 방의 invite-code 로 join 시도 → toast `"방이 가득 찼어요. 친구에게 새 방을 만들어 달라고 요청하세요."` + 폼 유지. (BE 의 새 `RoomFullException` → 409 + code `ROOM_FULL` mapping 의 e2e 확인.)

KakaoTalk 가 설치되지 않은 단말은 VERIFY-A 의 fallback path (`Share.share` 로 plain text + invite-code) 만 확인 가능.
```

**And** Android `preview` profile 이 APK 임을 잠그는 한 줄 `(eas.json: preview.android.buildType = apk)` 인용 (FE/eas.json line 13-19 사실 잠금).

### AC3 — CI integration 가드 (RUNBOOK 새 § 또는 §14 "자주 쓰는 전체 명령" 직후)

**Given** 현재 `.github/workflows/` 디렉터리에 `be-it-boot-smoke.yml` BE 전용 workflow 하나뿐, FE CI workflow 가 ZERO
**And** 미래에 FE Jest/lint/typecheck 또는 EAS auto-build 가 PR 트리거에 붙을 수 있다
**When** RUNBOOK 에 CI guidance 가 추가된다
**Then** 새 단락 (또는 새 §) 추가:

```markdown
## FE CI 와 native module 추가의 관계

본 리포의 GitHub Actions 는 **BE 전용** (`.github/workflows/be-it-boot-smoke.yml` — Story 1.4 retro action item T3 의 V11 IT 게이트). FE 는 로컬 사전 검증만 사용합니다.

`FE/package.json` 의 dependency 추가 PR 이 native module 을 도입하는 경우, 다음 중 한 가지 방식으로 **clean native rebuild** 가 PR 검증 경로에 포함되어야 합니다:

1. **EAS 빌드 (현행 권장 경로)** — `eas build --profile preview --platform <android|ios>` 가 매 빌드마다 prebuild + native compile 을 fresh 환경에서 수행. PR 리뷰어가 EAS 빌드 링크를 첨부해 smoke 까지 마치면 충분.
2. **(미래) FE CI workflow** — PR 트리거 워크플로우를 추가한다면, native 영향 파일 (`package*.json`, Expo config/plugin, `android/**`, `ios/**`) 변경을 감지해야 합니다. `npm test`, `npm run typecheck`, `expo prebuild` 만으로는 부족하며 Android 는 clean prebuild 후 `./gradlew assembleDebug`, iOS 는 EAS build 또는 `xcodebuild` 로 실제 native compile 을 수행해야 합니다.

요약: native module 추가 PR 은 **로컬 `adb uninstall + npx expo run:android` 또는 EAS preview 빌드 + VERIFY-A/B/C** 가 PR 리뷰의 hard gate. 이 가드는 본 리포의 어떤 CI workflow 도 자동화하지 않으므로 **사람 (PR 작성자 + 리뷰어) 이 의식적으로 수행**해야 합니다.
```

**And** "현재 FE CI ZERO" 사실은 문서 시점의 사실로 명시하고, 미래에 변경되면 본 단락의 첫 줄을 업데이트해야 함을 자체 명시. PRD ref: NFR-9.8.5, architecture ref: §5.2 line 520. Epic AC 4 line 886 충족.

### AC4 — Kakao Developers Console platform 등록 한 단락 (RUNBOOK §12 의 자매)

**Given** Kakao Native App Key 는 클라이언트-임베딩 안전한 공개 키이고 (REST API Key 와 별개; project-context.md:235), Story 6.2 가 `EXPO_PUBLIC_KAKAO_NATIVE_APP_KEY` env 변수와 `FE/app.config.ts` plugin 등록으로 SDK init 을 wiring 했다
**And** Kakao SDK 의 `shareFeedTemplate` 가 작동하려면 Kakao Developers Console 에서 `app.yeosal.mobile` 이 **Android + iOS platform** 으로 등록되어 있어야 한다
**When** RUNBOOK 이 편집된다
**Then** 다음 두 항목이 §12 의 step 7 다음에 추가되거나, 새 §12.1 "Kakao Share SDK (Native App Key)" 로 분리:

```markdown
9. (Story 6.2 — Kakao 공유 SDK) `앱 설정 > 앱 키` 의 **Native App Key** 를 복사합니다.
   - 이 키는 REST API 키와 **다른 키** 입니다. REST API Key 는 서버 (`yeosal.kakao.client-id`) 가, Native App Key 는 모바일 클라이언트 (`EXPO_PUBLIC_KAKAO_NATIVE_APP_KEY`) 가 사용합니다.
   - Native App Key 는 Kakao 가 의도적으로 클라이언트 번들에 포함되는 공개 식별자입니다. EXPO_PUBLIC_* 으로 노출해도 안전합니다. (project-context.md:235 의 REST API Key 금지 rule 은 그대로 유지.)

10. `앱 설정 > 플랫폼 키 > Native App Key` 에 Android package name `app.yeosal.mobile`, 모든 debug/release signing key hash, iOS Bundle ID `app.yeosal.mobile` 을 등록합니다.

11. `앱 설정 > Product Link > Web domain` 에 Default Feed template 이 사용하는 `https://yeolsal.app` 을 등록합니다.

12. **로컬 dev 머신** 의 `FE/.env` 에 Console 에 표시된 Native App Key 값을 그대로 채웁니다.

       EXPO_PUBLIC_KAKAO_NATIVE_APP_KEY=실제_네이티브_앱_키

    `FE/.env.example` 는 placeholder (`replace-with-...`) 를 commit. 실제 값이 들어간 `.env` 는 `.gitignore` 에 포함 (이미 등록됨).

13. **EAS 빌드용** 으로 Native App Key 를 plaintext EAS Environment Variable 로 preview/production environment 에 등록합니다.

        cd FE
        eas env:create --scope project --environment preview --visibility plaintext \
          --name EXPO_PUBLIC_KAKAO_NATIVE_APP_KEY --value 실제_네이티브_앱_키
        eas env:create --scope project --environment production --visibility plaintext \
          --name EXPO_PUBLIC_KAKAO_NATIVE_APP_KEY --value 실제_네이티브_앱_키

    Secret visibility 값은 app config 해석 시 읽을 수 없으므로 사용하지 않습니다.

14. 환경 변수 변경 후 로컬은 clean prebuild + native rebuild 가 필요합니다.

        cd FE
        adb uninstall app.yeosal.mobile
        npx expo prebuild --clean
        npx expo run:android
```

**And** §12 의 step 번호 매김은 기존 1-8 다음에 9-13 으로 이어짐 (continuation), 또는 § 신규 12.1 로 sub-section. dev 가 결정. PRD ref: FR-8.6.6, NFR-9.8.5.

### AC5 — Universal Link `.well-known/*` 호스팅은 OUT-OF-SCOPE (단, 명시 한 줄)

**Given** Story 6.2 의 `app.json` 가 iOS `associatedDomains: ["applinks:yeolsal.app"]` 과 Android `intentFilters` (host=yeolsal.app, autoVerify=true) 를 등록했지만, OS 측 verification 이 통과하려면 `https://yeolsal.app/.well-known/apple-app-site-association` + `https://yeolsal.app/.well-known/assetlinks.json` 의 정적 호스팅이 별도로 필요하다
**And** epics line 866 의 Story 6.3 정의는 "RUNBOOK + native module reinstall guidance" 만 잠그고 .well-known hosting 은 명시하지 않는다
**When** RUNBOOK 이 편집된다
**Then** AC1 의 새 sub-section 끝 (또는 §11 운영 API 주소 직후) 에 **명시적 OOS 한 단락**:

```markdown
**Out of scope for Story 6.3:** `yeolsal.app` 의 `.well-known/apple-app-site-association` 과 `.well-known/assetlinks.json` 의 정적 호스팅은 OS-level Universal Link / App Link verification 의 prerequisite 이지만, **본 스토리의 epic 정의 (FR-8.6.6) 에는 포함되지 않습니다**. 현재 공유 payload 는 HTTPS 만 emit 하므로 OS verification 전에는 브라우저로 열릴 수 있고 VERIFY-B 를 보장할 수 없습니다. `yeosal://join?code=X` 는 직접 열면 동작하지만 공유 카드의 자동 fallback 은 아닙니다.
```

**And** "OOS" 명시는 future 작업이 본 스토리의 미완료라는 오해 차단. AC 카운트에 포함은 되지만 작업은 단순 한 단락 추가.

### AC6 — Scope fence (FILE ALLOW-LIST)

**Given** 본 스토리는 production code 기준 docs-only 다
**When** dev 가 작업한다
**Then** **제품 산출물 수정 허용 파일** 정확히 1 개:

- `RUNBOOK.md` (repo root; 587 lines 현재 → 추정 +80~120 line 추가 후 < 720 line)

**워크플로우 추적 예외:**

- `_bmad-output/implementation-artifacts/6-3-runbook-native-module-reinstall-guidance.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`

**수정 금지 파일** (banned paths — diff 가 비어 있어야 함):

- `docs/RUNBOOK.md` — BE ops runbook, scope 완전히 다름 (outage diagnosis + V11 cutover).
- `infra/RUNBOOK-V11.md` — V11 migration cutover-specific.
- `FE/**`, `BE/**` (모든 소스, 테스트, 설정 파일)
- `scripts/**`, `.github/**`
- `_bmad-output/planning-artifacts/**` (epics / PRD / architecture 의 자동 수정 금지 — doc follow-up 은 별도 추적)
- `_bmad-output/project-context.md` (rule 본문 자체는 본 스토리가 만지지 않음)
- `_bmad/**`
- `tokens.json`, `app.json`, `app.config.ts`, `eas.json`, `FE/.env.example`

**예외:** Story 6.2 의 PR description 의 "Post-merge user action" 섹션과 본 RUNBOOK 추가 내용 사이의 cross-link 한 줄을 추가하고 싶다면 PR 본문에 적되, 파일 변경은 하지 않음. (Story 6.2 PR 은 이미 머지됨.)

**Verification:**

```bash
git diff --name-only origin/main | sort
# Expected output:
# RUNBOOK.md
# _bmad-output/implementation-artifacts/6-3-runbook-native-module-reinstall-guidance.md
# _bmad-output/implementation-artifacts/sprint-status.yaml
```

### AC7 — Brand voice / project-context 일관성

**Given** project-context.md:192 가 "No emojis in source files or docs unless explicitly requested" 를 명시한다
**And** RUNBOOK.md 의 기존 본문은 emoji 를 한 번도 사용하지 않는다 (검증: `grep -P "[\x{1F300}-\x{1F9FF}]" RUNBOOK.md` 결과 0 줄)
**When** dev 가 새 sub-section 들을 작성한다
**Then**:

1. emoji 0 개. (예외: AC1 의 sub-section 예시 본문이 `🥥` 를 인용한 부분은 코드 블록 안의 **사용자-가시 UI 카피의 인용** 이므로 RUNBOOK 의 prose-tone 이 아님. 이 한 곳은 허용 — 실제 InviteCodeSheet 의 button title 이 `🥥 카카오로 공유` 이고, smoke 절차의 verifiable label 로서 보존이 필요. dev 가 "실제 UI label 의 인용" 이외의 위치에 emoji 를 추가하지 않으면 충족.)
2. **브랜드 보이스 hard-banned 어휘 0 개** — "도전", "챌린지", "challenge", "노력 부족" 등 (brand-voice lint 의 HARD list, architecture §4.15). RUNBOOK 은 dev-facing 문서이므로 lint 의 직접 대상은 아니지만, 본 스토리는 brand voice 의 일관성 유지.
3. 코드 블록 안의 명령어 문자열은 사용자-가시 UI 카피와 무관하므로 brand voice 검사 대상이 아님.

### AC8 — Doc back-link 일관성

**Given** project-context.md:132, architecture §5.2 line 520, PRD FR-8.6.6, PRD NFR-9.8.5 가 모두 같은 rule 을 가리킨다
**When** RUNBOOK 의 새 sub-section 들이 작성된다
**Then** AC1 의 sub-section heading 옆에 **명시적 back-reference 한 줄** 포함:

> `### Kakao Share SDK (Story 6.2, FR-8.6.6 / NFR-9.8.5)`

그리고 AC3 의 CI 가드 단락 끝에 architecture §5.2 line 520 의 인용 한 줄:

> "Native module changes require `adb uninstall app.yeosal.mobile` + clean rebuild" — Architecture §5.2

이 cross-link 은 future agent / dev 가 RUNBOOK 만 읽어도 PRD/architecture/project-context 의 같은 rule 의 위치를 찾을 수 있게 한다. (Story 1.4 의 RUNBOOK-V11 mirror 패턴.)

### AC9 — Docs-shape verification (grep-based gate; tests N/A)

**Given** 본 스토리는 새 production code 0 줄이라 unit/integration test 가 적용되지 않는다
**When** 본 스토리의 작업물이 검증된다
**Then** 다음 grep 명령이 모두 정확한 결과를 반환:

| # | 검증 | 명령 | Expected |
|---|------|------|----------|
| 1 | Kakao SDK 언급 | `grep -c "Kakao Share SDK" RUNBOOK.md` | ≥ 2 (AC1 heading + AC4 body) |
| 2 | uninstall + rebuild 명령 보존 | `grep -c "adb uninstall app.yeosal.mobile" RUNBOOK.md` | ≥ 3 (기존 line 73 + AC1 + AC4) |
| 3 | iOS rebuild 절차 명시 | `grep -c "npx expo run:ios" RUNBOOK.md` | ≥ 1 |
| 4 | EAS preview profile 절차 | `grep -c "eas build.*preview" RUNBOOK.md` | ≥ 2 (기존 §6 + AC2 신규) |
| 5 | EAS APK profile 사실 잠금 | `grep "preview.android.buildType" RUNBOOK.md` | non-empty 한 줄 |
| 6 | Native App Key env var 언급 | `grep -c "EXPO_PUBLIC_KAKAO_NATIVE_APP_KEY" RUNBOOK.md` | ≥ 2 |
| 7 | REST API Key 와의 구분 명시 | `grep -E "REST API.{0,30}(Key\|키)" RUNBOOK.md \| grep -i "native"` | ≥ 1 match (둘이 다름을 명시한 단락) |
| 8 | Kakao Console platform 등록 가이드 | `grep -ic "platform" RUNBOOK.md` | 본문에 새 §12 의 platform 단락 추가됨 (≥ 1 새 mention) |
| 9 | VERIFY-A/B/C smoke 절차 | `grep -c "VERIFY-[ABC]" RUNBOOK.md` | ≥ 3 (각각 1번 이상) |
| 10 | CI 가드 단락 | `grep -i "FE CI\|GitHub Actions\|workflow" RUNBOOK.md` | non-empty |
| 11 | OOS 명시 | `grep -E "Out of scope.*6\.3\|6\.3 OOS" RUNBOOK.md` | ≥ 1 |
| 12 | Bundle ID 정확성 | `grep -c "app.yeosal.mobile" RUNBOOK.md` | ≥ 3 (정확히 `app.YEOSAL`, `app.YEOLSAL` 오타 0) |
| 13 | Bundle ID 오타 부재 | `grep -ci "app.yeolsal.mobile" RUNBOOK.md` | 0 (정확히 0 줄) |
| 14 | emoji 부재 (UI 인용 외) | `grep -P "[\x{1F300}-\x{1F9FF}]" RUNBOOK.md \| wc -l` | 0 또는 1 (1 인 경우 `🥥 카카오로 공유` UI label 인용에 한정) |
| 15 | 파일 크기 캡 | `wc -l RUNBOOK.md` | < 800 (project-context.md:178 의 hard cap 준수) |
| 16 | Brand voice HARD banned 어휘 부재 | `git diff origin/main -- RUNBOOK.md \| grep "^+" \| grep -iE "도전\|챌린지\|challenge"` | 0 새 라인 (UI 인용 외) |

게이트 16 의 grep 표현은 dev 가 추가한 새 단락만 검사 (`^+` 로 + 라인 한정).

### AC10 — Sprint-status transitions

**Given** 본 스토리가 ready-for-dev → in-progress → review → done 사이클을 돈다
**When** sprint-status.yaml 이 업데이트된다
**Then** transitions:

1. **create-story (본 워크플로우 종료 시)** — `6-3-runbook-native-module-reinstall-guidance: backlog → ready-for-dev`. **epic-6 transition 없음** — 이미 `in-progress` (Story 6.1 이 backlog→in-progress flip; 6.2 done 후 6.3 backlog 잔존으로 in-progress 유지).
2. **dev-story 시작** — `6-3-...: ready-for-dev → in-progress`.
3. **dev-story 완료** — `6-3-...: in-progress → review`.
4. **code-review 완료** — `6-3-...: review → done`. epic-6 의 마지막 story 이므로 본 스토리가 done 으로 flip 되는 순간 retrospective candidate. `epic-6` 자체 transition: in-progress → done 은 retrospective (또는 retrospective skip 결정) 시점에 별도 처리.

### AC11 — Architecture deviation notes (DOC FOLLOW-UP, NON-BLOCKER)

**Given** 본 스토리의 구현이 architecture / epics 와 다음 부분이 어긋난다
**When** PR description 또는 architecture 문서 PR 이 작성된다
**Then** 명시:

1. **Epics line 866** 의 "RUNBOOK.md (existing repo doc) has a section explicitly noting the rebuild requirement" — epic 이 단일 RUNBOOK 만 가리키지만 실제로는 세 개의 RUNBOOK 파일이 존재 (`RUNBOOK.md`, `docs/RUNBOOK.md`, `infra/RUNBOOK-V11.md`). 본 스토리가 가리키는 것은 **repo-root `RUNBOOK.md`** 임을 명시 (FE 빌드/실행 가이드 위치). PR description 에 한 줄. 비-블로커.
2. **Epics line 886** 의 "CI integration (if any) is documented to require a native rebuild step" — 실제로는 **FE CI 가 존재하지 않음** (`be-it-boot-smoke.yml` 만 BE 전용). 본 스토리는 "if any" 조건절을 "현재 zero + 미래 추가 시의 가드" 로 해석. epic 의 wording 과 정합. 비-블로커.
3. **Story 6.2 AC11 의 native package 정확히 3 개 게이트** — 실제 shipped 는 2 개 (`@react-native-kakao/expo-config-plugin` npm 미존재; v2 SDK 의 `core` 가 plugin 번들). 본 스토리 AC1 의 sub-section 본문은 **실제 shipped 인 2 packages 만 enumerate** 해야 함. 6.2 dev note 의 AC14 deviation 이 이미 기록 — 본 스토리는 documentation level 에서 정확화. 비-블로커.
4. **Story 6.2 dev note 의 `KakaoShareLink.sendDefault` → `shareFeedTemplate` API 변경** — 본 스토리는 RUNBOOK 본문에서 어떤 SDK API 도 인용하지 않음 (RUNBOOK 은 빌드 절차만 다루고 SDK API surface 는 코드 + AC1 의 boot log 한 줄 (`Kakao SDK initialized`) 에서 검증). 따라서 본 스토리 산출물은 6.2 의 API 변경과 독립. 비-블로커.

### AC12 — Verification matrix (gate before sprint-status flip)

**Given** Dev 가 모든 AC 를 구현했다
**When** Story 6.3 가 review 로 진입한다
**Then** 다음 8 게이트가 모두 GREEN:

| # | Gate | Command | Expected |
|---|------|---------|----------|
| 1 | Docs-shape grep matrix (AC9) | AC9 표의 16 줄 모두 실행 | 모두 expected 결과 매치 |
| 2 | Scope fence (AC6) | `git diff --name-only origin/main \| sort` | `RUNBOOK.md` + story file + sprint status, 그 외 없음 |
| 3 | Diff sanity | `git diff --check HEAD` | clean (trailing whitespace / conflict marker 부재) |
| 4 | Markdown lint (optional) | `npx markdownlint RUNBOOK.md` (도구 미설치면 skip) | clean 또는 skip |
| 5 | File size cap | `wc -l RUNBOOK.md` | < 800 (project-context.md:178) |
| 6 | Bundle ID 정확성 (AC9 #12-13) | `grep -ci "app.yeolsal.mobile" RUNBOOK.md` | 0 (오타 부재) |
| 7 | BE test untouched | (본 스토리 BE 변경 ZERO; PR-CI 단계의 BE 게이트로 대체) | green |
| 8 | FE test untouched | (본 스토리 FE 변경 ZERO; PR-CI 단계의 FE 게이트로 대체) | green |

**비-게이트:**

- **Manual smoke (VERIFY-A/B/C)** 는 본 스토리의 산출물이 *문서* 이므로 직접 게이트가 아님. RUNBOOK 의 문구가 dev 가 실제 smoke 를 실행할 수 있게 사실에 부합하면 충분 — dev 가 RUNBOOK 의 명령어를 실행해 (이론적으로) 실제 빌드가 통과함을 검증할 수는 있으나 본 스토리의 done 조건은 아님.
- **EAS 빌드 실 수행** 은 본 스토리 done 의 prerequisite 가 아님 (시간 + 토큰 비용). RUNBOOK 의 절차 문서화 자체가 산출물.
- **CI workflow 신설** 은 본 스토리 산출물이 아님 (AC3 가 명시적으로 "현재 zero" 사실로 잠금).

### AC13 — Post-merge user action (RUNBOOK note)

**Given** 본 PR 이 main 에 머지된다
**When** prod 배포가 일어난다
**Then** PR description 의 "Post-merge user action" 섹션에 다음 라인을 포함 (project-context.md:229 + Story 6.2 의 pattern):

```
- DOCS-ONLY change. Schema migration / native binary 변경 ZERO.
- 본 PR 머지 직후 dev 머신이 stale 인 경우는 없음 — 본 PR 자체가 RUNBOOK 문서 변경 이외 코드 변경 없음.
- 단, 본 PR 이후 새 dev / 신규 머신 setup 시: 새 RUNBOOK §3 의 "Kakao Share SDK" sub-section + §12 의 Native App Key 절차 + (선택) §6 의 EAS preview smoke 절차를 차례대로 수행. Story 6.2 의 native module 이 이미 main 에 머지되어 있으므로 신규 머신은 첫 빌드 전에 본 절차 필수.
- Universal Link 의 `.well-known/*` 정적 호스팅은 본 PR 범위 외 (Story 6.3 OOS). dev/preview 는 custom scheme `yeosal://join?code=X` fallback 으로 충분.
```

본 스토리는 V13 의 schema 변경 ZERO, 새 secret 추가 ZERO. **운영 측면의 post-merge action 없음.**

### AC14 — Sentry / observability hook (LIGHT-TOUCH, 본 스토리 N/A)

**Given** 본 스토리는 소스 코드 변경 ZERO 이고, 따라서 새 try/catch 또는 새 Sentry breadcrumb 가 발생하지 않는다
**When** Sentry 의 어떤 운영 wiring 도 본 스토리에서 변경되지 않는다
**Then** **wire change 없음**. 본 AC 는 explicit no-op — Story 6.2 의 AC15 (kakaoShare wrapper 의 Sentry breadcrumb) 가 그대로 동작.

본 AC 는 패턴 일관성 유지를 위해 존재하며 (Stories 5.1–6.2 모두 Sentry AC 가 있음), 실제 산출물은 없음. 검증 명령: `git diff origin/main -- 'FE/src/lib/sentry.ts' 'BE/src/main/java/**/Sentry*.java' 'BE/src/main/java/**/ObservabilityConfig*.java'` 의 결과가 empty 여야 함 (그러나 AC6 의 scope fence 가 이를 이미 보장).

## Traps (DEV AGENT 가 빠지기 쉬운 함정 — 사전 봉인)

1. **세 RUNBOOK 중 정확히 root 파일** 만 편집. `docs/RUNBOOK.md` (BE ops 의 outage diagnosis) 와 `infra/RUNBOOK-V11.md` (V11 cutover) 는 본 스토리 scope 외. dev 가 두 파일 중 하나를 잘못 편집하면 review 에서 즉시 revert. AC6 의 banned-paths grep 이 가드.
2. **Bundle ID 정확성:** 정확히 `app.yeosal.mobile`. `app.yeolsal.mobile` 은 **오타** (yeo*l*sal 이 사용자-가시 브랜드명, yeo*s*al 이 코드/리포 이름). project-context.md:63 + FE/app.json + RUNBOOK §3 line 73 의 기존 인용 모두 `app.yeosal.mobile`. AC9 게이트 12-13 이 잠금.
3. **Native App Key ≠ REST API Key.** 두 키는 **다른 키** 이고 다른 secrets-store 에 산다 — Native App Key 는 `EXPO_PUBLIC_KAKAO_NATIVE_APP_KEY` (FE bundle 안 안전), REST API Key 는 `KAKAO_CLIENT_ID` (BE 의 application.yml, 절대 FE 노출 금지 — project-context.md:235). dev 가 RUNBOOK 본문에서 두 키를 혼동하면 보안 사고. AC4 의 명시적 단락 + AC9 게이트 7 이 가드.
4. **expo-config-plugin 패키지 부재.** Story 6.2 의 epics 와 dev-spec 은 3 packages 를 명시했지만 실제 npm 에는 `@react-native-kakao/expo-config-plugin` 이 **없음** (v2 SDK 가 `@react-native-kakao/core` 안에 plugin 을 번들). RUNBOOK 의 sub-section 본문은 **실제 shipped 2 packages 만 enumerate** 해야 함 (Story 6.2 dev note AC14 의 deviation 인용 — 추적 가능한 진실). dev 가 epic 의 3 packages 를 그대로 따라 적으면 사실 위배.
5. **EAS preview = APK, production = AAB.** FE/eas.json line 13-19 잠금. RUNBOOK §6 의 기존 문구 (`preview` 프로파일은 `android.buildType = apk` 로 설정) 그대로 인용. dev 가 둘을 혼동하면 dev 머신 install path 가 깨짐 (AAB 는 `adb install` 안 됨, `bundletool` 필요).
6. **Metro cache 초기화 ≠ native rebuild.** `npx expo start -c` 는 JS bundle cache 만 지움 — native module 의 binary 포함 여부와 무관. AC1 sub-section 본문에서 두 동작의 차이를 명시. dev 가 RUNBOOK 에 "Metro -c 면 충분" 식으로 적으면 silent fail.
7. **iOS Simulator 의 uninstall 명령** 은 `xcrun simctl uninstall booted app.yeosal.mobile` (실기기는 길게 누르기 → 앱 삭제). Android 의 `adb uninstall` 와 다른 명령. dev 가 동일 명령으로 적으면 iOS 가 stale binary 로 빌드.
8. **app.json 과 app.config.ts 는 둘 다 활성.** Story 6.2 가 `app.json` 의 기존 plugin 배열을 `app.config.ts` 가 conditional 로 확장하는 패턴으로 도입. RUNBOOK 의 어떤 부분도 "app.json 만 보면 된다" 라고 적으면 사실 위배 — Kakao plugin 등록 + iOS associatedDomains 는 `app.config.ts` 동적 합성 결과를 본다고 명시해야 함. (단, 본 스토리의 AC1-AC5 본문은 이 detail 까지 다루지 않아도 됨 — Story 6.2 의 산출물.)
9. **brand-voice HARD list** ("도전", "챌린지") 0 등장. RUNBOOK 의 어떤 새 단락도 사용자-가시 카피 인용 외에는 이 어휘를 쓰지 않음. UI button label `🥥 카카오로 공유` 의 인용은 brand-voice 통과 (의도된 야유 톤 회피).
10. **emoji 0 개** (UI 인용 한 곳 제외). project-context.md:192. RUNBOOK 의 기존 본문이 emoji 0 줄이라는 baseline 을 유지.
11. **파일 크기 800 line 미만.** 현재 587 → +80~120 추가 → ~700 이내. 만약 추가량이 200+ 줄이 된다면 dev 가 OOS 영역 (예: Sentry 의 전체 lifecycle 또는 universal link 호스팅의 상세) 까지 무리하게 적은 것 — AC6 의 OOS 명시 + AC11 의 deviation note 로 축소.
12. **CI workflow 신설 금지.** AC3 는 **문서화** 만 — 실제 `.github/workflows/fe-ci.yml` 을 만들면 AC6 scope fence 위반. "미래에 추가될 경우" 의 가드만 적음.
13. **Story 6.2 PR description 의 임시 노트 자체 편집 금지.** Story 6.2 PR 은 이미 머지됨 (`7968dee`). PR description 은 GitHub UI 에서만 편집 가능하고 git 으로 변경 불가. 본 스토리는 RUNBOOK 만 update — 6.2 의 PR description 은 history 로 보존.
14. **사용자-가시 UI label 의 정확성.** AC2 의 VERIFY-A 본문에서 인용하는 button title `"🥥 카카오로 공유"` 와 toast 카피 `"방이 가득 찼어요. 친구에게 새 방을 만들어 달라고 요청하세요."` 는 Story 6.2 의 산출물 (`InviteCodeSheet.tsx` + `RoomFullException` toast handler). 본 스토리는 두 문자열을 byte-identical 로 인용 — dev 가 임의로 paraphrase 하면 smoke 절차의 verifiability 깨짐. dev 는 작업 시작 시 `git grep "🥥 카카오로" FE/src/components/rooms/InviteCodeSheet.tsx` 와 `git grep -r "방이 가득 찼어요" FE/` 로 정확한 한국어 문자열을 byte-identical 추출.

## Out of Scope (명시)

- 새 native module 추가 (Story 6.2 가 모두 ship).
- 새 FE source / hook / component / context provider.
- 새 BE service / endpoint / exception / handler / DTO.
- 새 Flyway migration, V13 → V14 가설.
- 새 `tokens.json` 의 design token 추가/변경.
- `app.json`, `app.config.ts`, `eas.json`, `FE/.env.example`, `BE/src/main/resources/application.yml` 의 변경.
- 새 GitHub Actions workflow 파일 생성.
- `yeolsal.app` 도메인의 정적 호스팅 (`.well-known/apple-app-site-association`, `.well-known/assetlinks.json`) — 별도 infra 작업.
- Kakao Developers Console 에서 `app.yeosal.mobile` 의 실제 등록 (사람-only 작업; RUNBOOK 은 단계 안내만).
- 새 Sentry alert rule, dashboard, breadcrumb.
- 새 RealtimeEvent variant, STOMP topic, push channel.
- 새 chat message kind / payload format.
- `docs/RUNBOOK.md` 또는 `infra/RUNBOOK-V11.md` 의 편집.
- Story 6.2 의 산출물 (`kakaoShare.ts`, `useKakaoShare.ts`, `deepLinking.ts`, `InviteCodeSheet.tsx`, `app/join.tsx`, `AuthContext.tsx`, `RoomFullException.java`, `ApiExceptionHandler.java`) 의 어떤 변경도.
- 새 Jest mock, BE test, IT slice.
- `project-context.md` 의 rule 본문 (line 132, 235 등) 의 wording 변경 — wording 은 영속적 rule, RUNBOOK 은 작업 흐름.
- `_bmad-output/planning-artifacts/epics.md`, `prd.md`, `architecture.md` 의 어떤 line 도 변경하지 않음 (Story 6.3 의 deviation 발견은 AC11 의 doc follow-up note 로 PR description 에 기록).

## Tasks / Subtasks

- [x] **Task 1 — Read current RUNBOOK landscape (AC: 1, 11)**
  - [x] Read `RUNBOOK.md` (repo root) 전체. 특히 §3 (Android 실행, line 50-94), §6 (Android APK 빌드, line 129-218), §12 (Kakao REST API 설정, line 418-460), §14 (자주 쓰는 전체 명령, line 490-538), §15 (Sentry 설정, line 540-587) 의 구조를 파악.
  - [x] Read `docs/RUNBOOK.md` 의 첫 25 줄로 BE ops scope 확인 (편집하지 않을 것을 확실히).
  - [x] Read `infra/RUNBOOK-V11.md` 의 첫 10 줄로 V11 cutover scope 확인 (편집하지 않을 것).
  - [x] Read `FE/eas.json` 으로 `preview` profile 의 `android.buildType = apk` 사실 잠금.
  - [x] Read `FE/.env.example` 의 마지막 8 줄 (`EXPO_PUBLIC_KAKAO_NATIVE_APP_KEY` placeholder + 주석) 으로 Story 6.2 의 wiring 확인.
  - [x] `git grep "🥥 카카오로" FE/src/components/rooms/InviteCodeSheet.tsx` 와 `git grep -r "방이 가득 찼어요" FE/` 로 byte-identical UI label 추출 (Trap #14). **Finding:** 실제 shipped 버튼 label 은 `"KakaoTalk으로 공유"` (emoji 없음) — 스토리 본문의 `"🥥 카카오로 공유"` 인용은 outdated. 실제 코드 인용으로 교체.
  - [x] Read `_bmad-output/project-context.md` line 132 + line 235 + line 192 (emoji rule) 로 cross-link 의 정확 위치 인용 준비.

- [x] **Task 2 — Draft §3 의 Kakao Share SDK sub-section (AC: 1)**
  - [x] 기존 line 70-77 의 `expo-secure-store` generic 절차는 **그대로 보존**.
  - [x] line 77 직후 (또는 line 78 의 빈 줄 뒤) 에 새 `### Kakao Share SDK (Story 6.2, FR-8.6.6 / NFR-9.8.5)` heading 삽입.
  - [x] Sub-section 본문에 다음을 포함:
    - 실제 shipped native packages 2 개 enumerate (Trap #4) — `@react-native-kakao/share` + `@react-native-kakao/core`.
    - Metro reload / `expo start -c` 가 native module 을 binary 에 포함하지 않는다는 한 줄 (Trap #6).
    - Android clean rebuild 명령 (정확한 Bundle ID `app.yeosal.mobile` — Trap #2).
    - iOS clean rebuild 명령 (`xcrun simctl uninstall booted app.yeosal.mobile` + `rm -rf ios/build ios/Pods` + `npx expo run:ios` — Trap #7).
    - **Deviation:** "재빌드 후 콘솔 로그 `Kakao SDK initialized` 가 떠야 정상" 검증 한 줄은 실제 코드에서 미존재 (`FE/src/lib/KakaoSdkBootstrap.tsx` 가 silent catch). 진실한 검증 신호로 교체: "KakaoTalk 설치 단말에서 `KakaoTalk으로 공유` 버튼 탭이 KakaoTalk 앱/web dialog 로 hand-off 되면 정상" + silent no-op 시의 3-항 troubleshooting (env key 미설정 / plugin 미등록 / Kakao Console platform 미등록).

- [x] **Task 3 — Draft §6 의 EAS preview smoke 단락 (AC: 2)**
  - [x] §6 의 "방법 A: EAS로 APK 만들기" 절 마지막 (line 175 직후, "프로덕션 AAB 빌드:" 단락 앞 — 정확히는 "방법 B" 직전) 에 `### Story 6.2 Kakao 공유 SDK smoke` sub-section 추가.
  - [x] VERIFY-A/B/C 3 시나리오를 numbered list 로 byte-identical 인용. 실제 shipped UI label `"KakaoTalk으로 공유"` (스토리 본문의 `"🥥 카카오로 공유"` 가 outdated 한 deviation 반영). toast `"방이 가득 찼어요. 친구에게 새 방을 만들어 달라고 요청하세요."` 는 `FE/app/join.tsx:48` 의 byte-identical 인용.
  - [x] EAS preview = APK 사실 한 줄 인용 (Trap #5) + AAB → adb install 불가 한 줄 부가.

- [x] **Task 4 — Draft §12 (또는 §12.1) 의 Native App Key lifecycle (AC: 4)**
  - [x] §12 의 기존 step 8 다음에 새 `### 12.1 Kakao Share SDK — Native App Key 셋업 (Story 6.2)` sub-section 으로 분리 (가독성 우선). steps 9-13 으로 continuation.
  - [x] Native App Key vs REST API Key 의 차이 명시 (Trap #3 + AC9 게이트 7 매칭). `_bmad-output/project-context.md:235` 의 REST API Key 금지 rule 보존을 명시적으로 인용.
  - [x] Kakao Console platform 등록 단계 (Bundle ID `app.yeosal.mobile` — Trap #2) 와 미등록 시의 silent fail 모드 (`shareFeedTemplate` 의 "앱 정보를 찾을 수 없음") 설명.
  - [x] `FE/.env` 의 dev 머신 local override + app config 에서 읽을 수 있는 plaintext EAS Environment Variable 등록의 두 채널 모두 명시.
  - [x] env 변경 후 native rebuild 가 필요하다는 한 줄 (Trap #6 reinforce).

- [x] **Task 5 — Draft FE CI 가드 단락 (AC: 3)**
  - [x] 새 § 16 "FE CI 와 native module 추가의 관계" 추가 — §15 Sentry 직후.
  - [x] 현재 FE CI ZERO 사실 (be-it-boot-smoke.yml 만 존재) 명시.
  - [x] EAS 빌드 (managed) 가 매 빌드 fresh prebuild 라는 사실 인용 (현행 권장 경로).
  - [x] 미래 FE CI workflow 가 추가될 경우의 가드 — native 영향 파일 변경을 감지하고 EAS build 또는 clean prebuild + Gradle/xcodebuild 로 실제 native compile 을 수행. Story 6.2 의 v1 → v2 API rename (`KakaoShareLink.sendDefault` → `shareFeedTemplate`) 예시로 Jest mock 의 한계 설명.
  - [x] Architecture §5.2 line 520 인용 한 줄 (AC8 의 cross-link) — blockquote 로 가시화.

- [x] **Task 6 — Draft OOS 명시 단락 (AC: 5)**
  - [x] AC1 의 sub-section 끝에 `.well-known/*` 호스팅 OOS 한 단락 추가 (§3 Kakao Share SDK sub-section 의 cohesion 우선; §11 옵션은 미선택).
  - [x] custom scheme `yeosal://join?code=X` 가 dev/preview fallback 임을 명시 (Story 6.2 의 결정 보존).

- [x] **Task 7 — Local verification (AC: 6, 9, 12)**
  - [x] `git diff --name-only origin/main | sort` → `RUNBOOK.md` + story file + `sprint-status.yaml` 세 줄. AC6 는 제품 산출물 1개와 BMad workflow 추적 파일 2개를 구분해 명시.
  - [x] AC9 의 16 줄 grep matrix 모두 실행 → 모두 expected 결과 매치 (#1 3, #2 4, #3 5, #4 4, #5 1, #6 5, #7 3, #8 14, #9 8, #10 6, #11 1, #12 7, #13 0, #14 0, #15 724, #16 0).
  - [x] `wc -l RUNBOOK.md` → 724 < 800 (AC12 게이트 5).
  - [x] `git diff --check HEAD` → clean (AC12 게이트 3).
  - [x] `grep -ci "app.yeolsal.mobile" RUNBOOK.md` → 0 (AC12 게이트 6, Trap #2).
  - [x] 새 단락의 brand-voice HARD banned 어휘 ("도전" / "챌린지" / "challenge") 0 등장 grep (`git diff origin/main -- RUNBOOK.md | grep "^+" | grep -iE "도전|챌린지|challenge"` 가 empty).

- [x] **Task 8 — Sprint status flip preparation (AC: 10)**
  - [x] dev-story 종료 시 `_bmad-output/implementation-artifacts/sprint-status.yaml` 의 `6-3-runbook-native-module-reinstall-guidance: in-progress → review` flip.
  - [x] code-review patch 완료 시 `review → done` flip.
  - [x] epic-6 status 는 변경하지 않음 (in-progress 유지) — review 단계에서는 retrospective 미정.
  - [x] last_updated 필드 갱신.

- [ ] **Task 9 — PR open with AC13 post-merge action + AC11 deviation notes** (사용자 결정 — 본 dev-story 의 done 게이트 외)
  - [ ] PR title: `docs(epic-6): Story 6.3 — RUNBOOK + native module reinstall guidance`.
  - [ ] PR body 에 AC13 의 "Post-merge user action" 4 줄 포함 (DOCS-ONLY 강조).
  - [ ] PR body 에 AC11 의 4 deviation notes 인용 (epics line 866 의 단일 RUNBOOK 참조, epics line 886 의 FE CI 부재 해석, Story 6.2 의 2-vs-3 packages, SDK API surface 의 doc-level 영향 없음) + 본 스토리가 발견한 추가 2 deviation (실제 button label `"KakaoTalk으로 공유"` vs 스토리 본문의 `"🥥 카카오로 공유"`, `KakaoSdkBootstrap.tsx` 의 silent-catch 로 인한 "Kakao SDK initialized" log 부재).
  - [ ] PR base = main, scope fence 명시.

### Review Findings

- [x] [Review][Patch] Clean rebuild 절차가 기존 native project 에 config plugin 을 다시 적용하지 않음 [RUNBOOK.md:90]
- [x] [Review][Patch] `EXPO_PUBLIC_KAKAO_NATIVE_APP_KEY` 를 legacy EAS secret 으로 등록하면 app config 해석 시 plugin 이 누락될 수 있음 [RUNBOOK.md:530]
- [x] [Review][Patch] Android Kakao 등록 절차에 필수 package name 및 debug/release signing key hash 가 누락됨 [RUNBOOK.md:519]
- [x] [Review][Patch] KakaoTalk Share 링크에 필요한 `yeolsal.app` Product Link Web domain 등록 절차가 누락됨 [RUNBOOK.md:519]
- [x] [Review][Patch] 공유 payload 는 HTTPS 만 emit 하므로 `.well-known` 검증 전 `yeosal://join` fallback 이 자동으로 도달 불가능함 [RUNBOOK.md:116]
- [x] [Review][Patch] `npx expo prebuild --no-install` 은 native compile gate 가 아니므로 미래 CI 가 broken binary 를 통과시킬 수 있음 [RUNBOOK.md:681]
- [x] [Review][Patch] KakaoTalk 미설치 및 SDK 실패 fallback 동작 설명이 실제 web fallback / mutation `onError` 흐름과 다름 [RUNBOOK.md:108]
- [x] [Review][Patch] native-module inventory 가 설치된 `expo-secure-store` 를 제외하고 현재 셋을 두 개라고 잘못 설명함 [RUNBOOK.md:81]
- [x] [Review][Patch] 미래 CI 감지 조건이 `FE/package.json` 만 보아 lockfile, Expo config/plugin, native directory 변경을 놓침 [RUNBOOK.md:678]
- [x] [Review][Patch] iOS 실기기 공통 절차라고 표기했지만 simulator 중심 명령만 제공함 [RUNBOOK.md:98]
- [x] [Review][Patch] 완료 기록이 실패한 scope gate, 미완료 brand-voice subtask, 없는 FE CI 자동 게이트, stale footer status 를 동시에 green/review 로 기록함 [_bmad-output/implementation-artifacts/6-3-runbook-native-module-reinstall-guidance.md:394]

## Dev Notes

### Source-of-truth back-references

| 사실 | 출처 | 잠금 인용 |
|------|------|----------|
| Native module rule 의 일반 명제 | project-context.md:132 | "expo-secure-store 같은 native module 을 추가/제거한 뒤에는 기존 앱 바이너리를 지우고 다시 빌드해야 합니다." |
| Architecture-level FE 패턴 잠금 | architecture.md:520 | "Native module changes require adb uninstall app.yeosal.mobile + clean rebuild. KakaoTalk Share SDK addition is the v1 trigger of this rule; document in RUNBOOK.md." |
| PRD functional requirement | prd.md:419 (FR-8.6.6) | "Kakao Share SDK is a native module addition. Project-context.md rule applies: shipping requires adb uninstall app.yeosal.mobile + clean rebuild on dev machines; document in RUNBOOK.md." |
| PRD non-functional requirement | prd.md:513 (NFR-9.8.5) | "KakaoTalk SDK addition triggers adb uninstall app.yeosal.mobile + rebuild on dev machines; document the cycle in RUNBOOK.md." |
| Bundle ID 정확성 | project-context.md:63 + FE/app.json + RUNBOOK.md:73 | `app.yeosal.mobile` (Android + iOS). 오타 `app.yeolsal.mobile` 의 0 등장. |
| Native App Key 의 클라이언트-임베딩 안전성 | Story 6.2 의 AC dev note + project-context.md:235 (REST API Key 금지) | Native App Key 는 EXPO_PUBLIC_* 으로 안전, REST API Key 는 BE-only. |
| EAS preview = APK | FE/eas.json:13-19 | `"preview": { "android": { "buildType": "apk" } ... }` |
| FE CI ZERO 사실 | .github/workflows/ 의 디렉터리 내용 | `be-it-boot-smoke.yml` 단 한 개, BE 전용. |
| RUNBOOK 의 세 파일 분리 | docs/RUNBOOK.md (BE ops), infra/RUNBOOK-V11.md (V11 cutover), RUNBOOK.md (FE 빌드/실행) | Story 6.3 가 가리키는 것은 정확히 repo-root `RUNBOOK.md`. |
| Story 6.2 의 native packages 2 (3 아님) | Story 6.2 dev note Task 3 의 deviation 노트 | `@react-native-kakao/expo-config-plugin` npm 부재; v2 의 `core` 가 plugin 번들. |
| Story 6.2 의 UI button title | InviteCodeSheet.tsx 의 primary CTA | `"🥥 카카오로 공유"` (Story 6.2 AC1) — Task 1 의 grep 으로 byte-identical 추출 |
| Story 6.2 의 ROOM_FULL toast | app/join.tsx 의 ApiError 분기 | `"방이 가득 찼어요. ..."` (정확한 한국어는 Task 1 의 grep 으로 추출) |

### 이전 스토리 인텔리전스 (Stories 6.1 + 6.2)

- **Story 6.1 (PR #90, f682be5)** — BE-only preview card renderer + cache. 본 스토리와 직접적 코드 의존 없음. RUNBOOK 본문에서 "preview-card 가 카카오 공유 카드의 이미지 source" 임을 한 줄 인용해도 좋으나, scope fence 안에서 RUNBOOK 의 §3 / §6 / §12 / §15 신규 본문 어디에도 BE 서비스 호출 절차를 적지 않음 (RUNBOOK 은 FE 빌드/실행 가이드).
- **Story 6.2 (PR #91, 7968dee, 2026-06-07)** — FE-heavy. 본 스토리는 6.2 의 산출물 (native module 2 packages, app.config.ts, EXPO_PUBLIC_KAKAO_NATIVE_APP_KEY, InviteCodeSheet UI 변경, app/join.tsx 의 deep-link 핸들러, AuthContext bridging) 의 RUNBOOK-side ops 마무리. 6.2 의 PR description 의 "Post-merge user action" 5 줄을 RUNBOOK 의 영구 섹션으로 옮기는 것이 본질.
- **6.2 의 deviation log (본 스토리가 인용/반영해야 함):**
  - native packages 3 → 2 (expo-config-plugin npm 부재).
  - `KakaoShareLink.sendDefault` → `shareFeedTemplate` (v2 API).
  - app/join.test.tsx 의 deferred — manual VERIFY-C 가 1차 채널 (RUNBOOK 의 AC2 sub-section 이 이 manual smoke 의 구체 절차 제공 → 본 스토리가 dev-spec 의 missing piece 를 완성).

### Git 인텔리전스 (recent 5 commits)

```
7968dee feat(epic-6): Story 6.2 — Kakao Share SDK integration + deep-linking (#91)
4f63796 readme
f682be5 feat(epic-6): Story 6.1 — Server-side preview card renderer + cache (#90)
2d8ac62 feat(epic-5): Story 5.4 — Rule-change broadcast in chat (#89)
37a75df feat(epic-5): Story 5.3 — Auto-leader-promotion on elimination (#88)
```

- 직전 commit 6.2 가 본 스토리의 직접 dependency.
- Epic 5 (Stories 5.1–5.4) 는 일반적인 FE/BE 분할 패턴 — 본 스토리의 docs-only 특성과는 다름. 그러나 5.1 의 `RoomService.create + DefaultRoomMigrationRunner default-rule mint` 패턴이 시사하는 점: "production rollout 의 first-time user 경험 보존" 의 RUNBOOK-side mirror 가 본 스토리. (Story 5.1 이 BE 의 first-time data integrity, 본 스토리가 first-time dev machine setup.)

### Architecture compliance

- **§3.3 line 154 의 "extending existing Kakao OAuth integration in the same dependency package"** — Story 6.2 dev note AC14 의 deviation 으로 이미 기록 (BE 의 REST-only Kakao OAuth ≠ FE 의 새 native SDK). 본 스토리는 추가 architecture 수정 없음.
- **§5.2 line 520** 의 native-module rule 은 본 스토리가 가장 직접 인용. AC8 의 cross-link 의 anchor.
- **§4.10 line 308-328** 의 preview-card cache 는 Story 6.1 산출물; 본 스토리는 RUNBOOK 본문에서 인용하지 않음 (BE 서비스 절차는 docs/RUNBOOK.md 의 scope).
- **§6.2 line 621** 의 FE source tree 의 `src/lib/kakaoShare.ts` 만 enumerate — Story 6.2 의 AC14 deviation 으로 이미 doc follow-up 추적. 본 스토리는 architecture.md 를 만지지 않음.

### Library / framework requirements

- 새 dependency 추가 ZERO (FE/package.json 변경 부재).
- Story 6.2 가 추가한 packages (`@react-native-kakao/share@^2.4.5`, `@react-native-kakao/core@^2.4.5`) 의 정확한 version 은 본 스토리가 인용하지 않음 (RUNBOOK 은 version-agnostic).
- `expo-linking` 은 expo-router 의 transitive dep, 신규 추가 없음.

### File structure requirements

- **제품 산출물 MODIFIED (정확히 1 개):** `RUNBOOK.md` (repo root).
- **워크플로우 추적:** story file + `sprint-status.yaml`.
- **DELETED (0 개):** 없음.
- 추정 net diff: +80 ~ +120 lines (현재 587 → ~700; project-context.md:178 의 800 cap 여유 약 100 줄).

### Testing requirements

- **No new unit/integration/E2E tests** (docs-only).
- AC9 의 grep matrix 가 documentation-level acceptance gate 의 본 스토리 대체물.
- BE/FE 기존 테스트 스위트는 본 스토리에서 실행되지 않음 (코드 변경 ZERO 이므로 regression 없음). 현재 FE 전용 PR CI 는 없으며, 문서 검증은 AC9 grep matrix 와 diff sanity 로 수행.

### Latest tech info (web research summary)

- `@react-native-kakao/*` v2 SDK 의 API: `shareFeedTemplate({ template })` 가 canonical (이전 v1 의 `KakaoShareLink.sendDefault` 는 v2 에서 제거됨). Story 6.2 가 이미 wrapper 를 v2 API 로 작성. 본 스토리는 RUNBOOK 본문에서 SDK API surface 를 인용하지 않으므로 직접 영향 없음.
- Expo SDK 의 native module rebuild 요구사항은 SDK 49+ 에서도 동일 (Continuous Native Generation / Expo Modules 의 일반 명제). 본 스토리의 절차는 Expo SDK version 에 robust.
- Kakao Developers Console 의 platform 등록 UI 는 2024-2026 사이 wording 변경 가능 — RUNBOOK 본문은 정확한 menu 경로 인용 시 `앱 설정 > 플랫폼` 의 generic 명칭 사용 (Kakao 의 UI 가 한글 → 영문 mode 모두 동일 hierarchical 위치).

### Project context reference

`_bmad-output/project-context.md` 의 다음 라인이 본 스토리의 직접 anchor:

- **line 63:** `App ID: app.yeosal.mobile`.
- **line 132:** `expo-secure-store 같은 native module 을 추가/제거한 뒤에는 ... adb uninstall app.yeosal.mobile (or equivalent) and a fresh build. Metro reload is insufficient.`
- **line 192:** `No emojis in source files or docs unless explicitly requested.`
- **line 229:** `Any change with significant operational impact (migrations, security, auth wiring) must include a "Post-merge user action" section in the PR body.` (본 스토리는 DOCS-ONLY 라서 operational impact 가 ZERO, 그래도 AC13 가 explicit no-op 명시.)
- **line 235:** `Kakao REST API key lives on the BE only. FE proxies through /auth/kakao/authorize; never expose the key as an EXPO_PUBLIC_* variable.` — AC4 의 Native App Key vs REST API Key 구분의 직접 anchor.

### References

- [Source: _bmad-output/planning-artifacts/epics.md#L866-L887] — Story 6.3 BDD ACs
- [Source: _bmad-output/planning-artifacts/prd.md#L419] — FR-8.6.6
- [Source: _bmad-output/planning-artifacts/prd.md#L513] — NFR-9.8.5
- [Source: _bmad-output/planning-artifacts/architecture.md#L150-L156] — §3.3 KakaoTalk share SDK 선택 근거
- [Source: _bmad-output/planning-artifacts/architecture.md#L506-L521] — §5.2 Frontend patterns (line 520 = native-module rule)
- [Source: _bmad-output/planning-artifacts/architecture.md#L865] — §7.3 사전 release 체크리스트의 RUNBOOK 플래그
- [Source: _bmad-output/project-context.md#L63] — App ID
- [Source: _bmad-output/project-context.md#L132] — native-module rule
- [Source: _bmad-output/project-context.md#L192] — emoji rule
- [Source: _bmad-output/project-context.md#L229] — post-merge user action rule
- [Source: _bmad-output/project-context.md#L235] — Kakao REST API Key 금지 rule
- [Source: RUNBOOK.md#L70-L77] — 기존 generic native-module 절차 (보존 대상)
- [Source: RUNBOOK.md#L129-L218] — §6 Android APK 빌드 (AC2 의 confluence 지점)
- [Source: RUNBOOK.md#L418-L460] — §12 Kakao REST API 설정 (AC4 의 자매 지점)
- [Source: RUNBOOK.md#L540-L587] — §15 Sentry 설정 (Native App Key lifecycle 의 mirror 패턴)
- [Source: FE/eas.json#L13-L19] — preview profile APK 사실
- [Source: FE/.env.example#L11-L17] — EXPO_PUBLIC_KAKAO_NATIVE_APP_KEY placeholder + 주석 (Story 6.2 에서 등록)
- [Source: .github/workflows/be-it-boot-smoke.yml] — 현재 유일한 CI workflow (BE-only)
- [Source: _bmad-output/implementation-artifacts/6-2-kakao-share-sdk-integration-deep-linking.md] — Story 6.2 의 산출물 + AC13 의 임시 post-merge 노트 (본 스토리가 영구 섹션으로 승격)

## Dev Agent Record

### Agent Model Used

claude-opus-4-7 (Claude Code; bmad-dev-story workflow)

### Debug Log References

- AC9 16-line grep matrix execution log — all gates green (#1 3, #2 4, #3 5, #4 4, #5 1, #6 5, #7 3, #8 14, #9 8, #10 6, #11 1, #12 7, #13 0, #14 0, #15 724 < 800, #16 0).
- `git diff --check HEAD` → clean.
- `git diff --name-only origin/main | sort` → `RUNBOOK.md` + `_bmad-output/implementation-artifacts/sprint-status.yaml` (story file itself untracked locally — PR-add).

### Completion Notes List

**Implementation summary (DOCS-ONLY):**

- 단일 제품 문서 편집 `RUNBOOK.md`: 587 → 724 lines (+137 net). 5 개 영역 추가:
  - §3 신규 `### Kakao Share SDK (Story 6.2, FR-8.6.6 / NFR-9.8.5)` sub-section — 2 packages enumerate + Android/iOS clean-rebuild 명령 + silent-no-op troubleshooting 3-항 + OOS `.well-known/*` 한 단락 (AC5 cohesion 우선 배치).
  - §6 신규 `### Story 6.2 Kakao 공유 SDK smoke` sub-section — VERIFY-A/B/C 3 시나리오 + `preview.android.buildType = apk` 사실 잠금.
  - §12 신규 `### 12.1 Kakao Share SDK — Native App Key 셋업 (Story 6.2)` sub-section — Native App Key + Android key hashes + Product Link domain + FE/.env + EAS Environment Variables + native rebuild.
  - §16 신규 `## 16. FE CI 와 native module 추가의 관계` 최상위 § — 현재 FE CI ZERO 사실 + EAS managed-build 권장 + 미래 FE CI workflow 가드 + Architecture §5.2:520 blockquote 인용.
- 0 source/test/migration/config 변경. Story 6.2 의 산출물 (kakaoShare.ts, useKakaoShare.ts, deepLinking.ts, InviteCodeSheet.tsx, app/join.tsx, AuthContext.tsx, RoomFullException.java, ApiExceptionHandler.java, app.config.ts, eas.json, FE/.env.example) 어떤 변경도 없음.

**Story-spec deviations 발견 (AC11 확장 — PR description 에 인용):**

1. **실제 button label `"KakaoTalk으로 공유"` (emoji 없음)** — 스토리 본문의 AC2/AC7 의 `"🥥 카카오로 공유"` 인용은 Story 6.2 shipped 코드 (`FE/src/components/rooms/InviteCodeSheet.tsx:109`) 와 mismatch. Trap #14 의 byte-identical 인용 규칙을 따라 실제 shipped label 로 교체. AC9 게이트 #14 expected 가 "0 또는 1" 이었지만 실제 RUNBOOK 의 emoji 카운트는 **0** (스토리 spec 의 `🥥` 보존 예외가 무의미해짐).
2. **`KakaoSdkBootstrap.tsx` silent-catch — "Kakao SDK initialized" 로그 부재** — 스토리 본문의 AC1 예시 본문 (line 65) 이 약속한 boot log 가 실제 코드에서 없음 (`void initializeKakaoSDK(...).catch(() => { /* fallback to generic share */ })`). RUNBOOK 본문은 진실한 검증 신호 (KakaoTalk 설치 단말에서 share 버튼 탭이 KakaoTalk hand-off 되면 정상 + silent no-op 시의 3-항 troubleshooting) 로 교체. AC6 scope fence 가 FE 소스 편집을 금지하므로 docs-side 적응 외 선택지 없음.
3. **AC6 scope fence 기대치 vs AC10 workflow 의무 충돌 해결** — 제품 산출물 allow-list 는 `RUNBOOK.md` 한 개로 유지하고, story file + sprint-status 를 workflow 추적 예외로 명시. expected output 을 세 파일로 정정.
4. **에픽 라인 866 의 단일 RUNBOOK 참조** — 실제로는 3 RUNBOOK (`RUNBOOK.md`, `docs/RUNBOOK.md`, `infra/RUNBOOK-V11.md`). 본 스토리는 명시적으로 repo-root `RUNBOOK.md` 만 편집 (AC6 banned paths 가 가드).
5. **에픽 라인 886 의 "CI integration (if any)" 해석** — 실제 FE CI ZERO. 본 스토리는 "현재 ZERO + 미래 추가 시의 가드" 로 해석 — 에픽 wording 의 "if any" 조건절과 정합.
6. **Story 6.2 의 native packages 2 (3 아님)** — `@react-native-kakao/expo-config-plugin` npm 부재; v2 SDK 의 `core` 가 plugin 번들. Story 6.2 dev note AC14 deviation 의 documentation-level 정정.

**Verifications (AC12 8-gate):**

| # | Gate | Result |
|---|------|--------|
| 1 | AC9 16-line grep matrix | GREEN (above) |
| 2 | Scope fence | RUNBOOK + story file + sprint-status, 그 외 없음 |
| 3 | `git diff --check HEAD` | clean |
| 4 | markdownlint (optional) | not installed — skip |
| 5 | `wc -l RUNBOOK.md` | 724 < 800 |
| 6 | Bundle ID typo | 0 (no `app.yeolsal.mobile`) |
| 7 | BE test untouched | N/A — 0 BE changes |
| 8 | FE test untouched | N/A — 0 FE changes |

**Non-gates (per AC12):** Manual VERIFY-A/B/C smoke 실행 + EAS 빌드 실 수행 + CI workflow 신설은 본 스토리의 done 조건 아님. RUNBOOK 의 절차 문서화 자체가 산출물.

**Post-merge user action:** DOCS-ONLY. 신규 dev 머신 setup 시 새 §3 Kakao Share SDK 절차 + §12.1 Native App Key lifecycle + §6 EAS preview smoke 를 순서대로 수행. Story 6.2 native module 은 이미 main 에 머지되어 있으므로 신규 머신은 첫 빌드 전에 본 절차 필수. (자세한 운영 영향 텍스트는 PR description 의 "Post-merge user action" 섹션에 AC13 4 줄로 포함 예정.)

### File List

- `RUNBOOK.md` (MODIFIED) — +137 net lines (587 → 724). 4 new sub-sections / sections: §3 의 `### Kakao Share SDK (Story 6.2, FR-8.6.6 / NFR-9.8.5)` + OOS 단락, §6 의 `### Story 6.2 Kakao 공유 SDK smoke`, §12 의 `### 12.1 Kakao Share SDK — Native App Key 셋업 (Story 6.2)`, 신규 `## 16. FE CI 와 native module 추가의 관계`.
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (MODIFIED) — `6-3-runbook-native-module-reinstall-guidance: ready-for-dev → in-progress → review → done` + review-patch audit comment + `last_updated: 2026-06-07`.
- `_bmad-output/implementation-artifacts/6-3-runbook-native-module-reinstall-guidance.md` (NEW/UNTRACKED — added by `bmad-create-story` 2026-06-07) — Status: done; review patches checked; Task 9 (PR open) 명시적 deferred (사용자 결정).

### Change Log

| Date | Description |
|------|-------------|
| 2026-06-07 | Story 6.3 implementation — RUNBOOK.md 4-section docs-only edit (§3 Kakao Share SDK + §6 EAS preview smoke + §12.1 Native App Key lifecycle + §16 FE CI guard). All AC9 16 grep gates green; AC12 8-gate matrix green (5 N/A workflow gates included). 6 story-spec deviations captured (most material: actual button label `"KakaoTalk으로 공유"` vs spec `"🥥 카카오로 공유"`; KakaoSdkBootstrap silent-catch dropped speculative boot log). Status: ready-for-dev → in-progress → review. |
| 2026-06-07 | Code review patches — corrected clean prebuild, EAS environment variable visibility, Kakao Android key hashes/Product Link, fallback/deep-link behavior, CI compile guidance, scope tracking, and status consistency. Status: review → done. |

### Project Structure Notes

- RUNBOOK.md (repo root) 는 FE 빌드/실행/배포 가이드의 single source of truth. docs/RUNBOOK.md (BE ops) 와 infra/RUNBOOK-V11.md (V11 cutover) 는 명확히 분리된 scope — 세 파일이 한 디렉터리에 모이지 않은 것은 의도된 분리.
- 본 스토리의 변경 패턴은 "기존 § 내 sub-section 추가" + "새 § 신규" 의 혼합. file-size cap 800 이내에서 안전 (724 < 800).

---

**Story prepared by:** bmad-create-story workflow
**Date:** 2026-06-07
**Status:** done
