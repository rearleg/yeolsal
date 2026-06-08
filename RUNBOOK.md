# Yeosal 실행, 테스트, 빌드, 서버 배포 가이드

이 문서는 `yeosal/` 루트에서 시작한다고 가정합니다.

```bash
cd /Users/rearleg/dev/codex-test/yeosal
```

## 1. 기본 준비

필수 설치:

- Node.js와 npm
- Android Studio, Android SDK, Android Emulator
- Xcode
- Docker Desktop
- Java 21
- Gradle

현재 프로젝트 검증:

```bash
bash scripts/verify.sh
```

검증 항목:

- FE lint
- FE TypeScript typecheck
- FE Jest
- BE Gradle test
- BE Gradle build
- Docker daemon이 실행 중이면 BE Docker image build

## 2. FE 의존성 설치

처음 한 번 실행합니다.

```bash
cd FE
npm install
```

루트 npm workspace를 쓰고 싶으면 루트에서도 가능합니다.

```bash
npm install
```

## 3. Android Emulator에서 앱 실행

Android Studio에서 emulator를 먼저 켭니다.

그 다음:

```bash
cd FE
npm run android
```

React/Expo dependency를 바꾼 직후에는 Metro cache를 비우고 다시 실행합니다.

```bash
cd FE
npx expo start -c
```

터미널에서 `a`를 누르면 Android emulator로 실행됩니다.

`expo-secure-store` 같은 native module을 추가/제거한 뒤에는 기존 앱 바이너리를 지우고 다시 빌드해야 합니다.

```bash
adb uninstall app.yeosal.mobile
npm run android
```

단순 reload나 Metro cache 초기화만으로는 native module이 앱에 포함되지 않습니다.

### Kakao Share SDK (Story 6.2, FR-8.6.6 / NFR-9.8.5)

Kakao Share SDK 는 v1 의 두 번째 native module 추가 작업 (첫 번째 = `expo-secure-store`). `FE/package.json` 에서 앱 바이너리에 포함되는 관련 native module 은 현재 다음 세 개입니다.

- `expo-secure-store` — 인증 토큰과 pending invite 저장
- `@react-native-kakao/share` — Kakao 공유 SDK (Story 6.2)
- `@react-native-kakao/core` — Kakao SDK 초기화 + Expo config plugin 번들 (Story 6.2)

별도 패키지였던 `@react-native-kakao/expo-config-plugin` 은 v2 SDK 가 `core` 안에 plugin 을 번들하면서 npm 에서 사라졌습니다. `FE/app.config.ts` 의 plugin 배열에는 `"@react-native-kakao/core"` 만 등록되어 있으면 됩니다.

`npm install` 만으로는 새 native code 가 기존 앱 바이너리에 포함되지 않습니다. Metro reload, `npx expo start -c`, JS hot-reload 어느 것도 native module 을 binary 에 inject 하지 않습니다.

Android (Emulator / 실기기 공통):

```bash
cd FE
adb uninstall app.yeosal.mobile
npx expo prebuild --clean
npx expo run:android
```

iOS Simulator:

```bash
cd FE
xcrun simctl uninstall booted app.yeosal.mobile
npx expo prebuild --clean
npx expo run:ios
```

iOS 실기기:

```bash
cd FE
# 실기기 홈 화면에서 기존 앱을 삭제한 뒤:
npx expo prebuild --clean
npx expo run:ios --device
```

`npx expo prebuild --clean` 은 기존 `android/` 와 `ios/` 를 다시 생성하므로, 실행 전 커밋되지 않은 native project 변경이 없는지 확인합니다.

재빌드 후 KakaoTalk 이 설치된 단말에서 방의 invite-share 버튼 (`KakaoTalk으로 공유`) 을 탭했을 때 KakaoTalk 앱으로 hand-off 되면 native SDK 가 정상 동작한 것입니다. 일반 OS share sheet 로 떨어진다면 SDK 호출이 실패해 mutation `onError` 의 `Share.share` fallback 이 실행된 것입니다. 다음 항목을 확인합니다.

- `EXPO_PUBLIC_KAKAO_NATIVE_APP_KEY` 가 비어 있음 → §12 의 Native App Key 절차로 채웁니다.
- `FE/app.config.ts` 의 plugin 배열에 `@react-native-kakao/core` 등록 누락 → `npx expo prebuild --clean` 후 다시 빌드합니다.
- Kakao Developers Console 의 Native App Key 에 Android package/key hash 또는 iOS Bundle ID 가 미등록 → §12.1 의 platform 등록 단계로 처리합니다.
- Kakao Developers Console 의 Product Link Web domain 에 `https://yeolsal.app` 이 미등록 → §12.1 의 Product Link 단계로 처리합니다.

`FE/src/lib/kakaoShare.ts` 는 SDK 오류를 다시 throw 하고, `FE/app/rooms/[id].tsx` 의 mutation `onError` 가 일반 `Share.share` fallback 을 호출합니다. KakaoTalk 이 설치되지 않은 단말에서는 SDK 의 web share dialog 가 먼저 사용되며, 그것도 실패한 경우에만 일반 OS share sheet 로 전환됩니다.

Out of scope for Story 6.3: `yeolsal.app` 의 `.well-known/apple-app-site-association` 과 `.well-known/assetlinks.json` 의 정적 호스팅은 OS-level Universal Link / App Link verification 의 prerequisite 이지만, 본 스토리의 epic 정의 (FR-8.6.6) 에는 포함되지 않습니다. 현재 공유 payload 는 `https://yeolsal.app/join?code=X` 만 emit 하므로 OS verification 전에는 카드 탭이 브라우저로 열릴 수 있고 VERIFY-B 를 보장할 수 없습니다. `yeosal://join?code=X` 는 직접 열면 동작하는 dev용 scheme 이지만 현재 공유 카드에서 자동 전환되는 fallback 은 아닙니다. `.well-known/*` 호스팅은 별도 infra PR 또는 후속 스토리에서 완료해야 합니다.

Metro 서버만 먼저 띄우고 싶으면:

```bash
cd FE
npm start
```

Expo Go로 테스트할 수도 있습니다.

```bash
cd FE
npm start
```

터미널 또는 브라우저에 뜨는 QR/URL을 Expo Go에서 엽니다.

## 4. iOS Simulator에서 앱 실행

Xcode를 한 번 열어서 license와 component 설치를 마친 뒤 실행합니다.

```bash
cd FE
npm run ios
```

Metro 서버만 먼저 띄우고 싶으면:

```bash
cd FE
npm start
```

이후 터미널에서 `i`를 누르면 iOS Simulator로 실행됩니다.

## 5. FE 로컬 테스트 명령

```bash
cd FE
npm run lint
npm run typecheck
npm test
```

한 번에 전체 검증:

```bash
cd ..
bash scripts/test.sh
```

## 6. Android APK 빌드

### 방법 A: EAS로 APK 만들기

Expo managed workflow에서 가장 안전한 방법입니다.

처음 한 번 EAS CLI를 설치합니다.

```bash
npm install -g eas-cli
```

Expo 계정에 로그인합니다.

```bash
eas login
```

APK 빌드:

```bash
cd FE
eas build --platform android --profile preview
```

`preview` 프로파일은 `FE/eas.json`에 `android.buildType = apk`로 설정되어 있습니다. 빌드가 끝나면 EAS가 APK 다운로드 링크를 출력합니다.

다운로드한 APK를 emulator 또는 연결된 Android 기기에 설치:

```bash
adb install path/to/app.apk
```

이미 같은 앱이 설치되어 있으면 덮어쓰기:

```bash
adb install -r path/to/app.apk
```

프로덕션 AAB 빌드:

```bash
cd FE
eas build --platform android --profile production
```

Google Play 배포에는 일반적으로 APK가 아니라 AAB를 사용합니다.

### Story 6.2 Kakao 공유 SDK smoke

`preview` 프로파일 APK 가 빌드된 직후 (`eas build --platform android --profile preview`), KakaoTalk 이 설치된 실기기에서 다음 세 시나리오를 손으로 검증합니다 (Story 6.2 AC12 의 VERIFY-A/B/C). 정확히는 `FE/eas.json` 의 `preview.android.buildType = apk` 로 빌드된 APK 가 대상입니다 (AAB 는 `adb install` 불가).

1. VERIFY-A — 임의 방의 invite 발급 → `InviteCodeSheet` 의 `KakaoTalk으로 공유` primary 버튼 탭 → 설치된 KakaoTalk 앱으로 hand-off → preview card image + 방 이름 + 같이 살아남자 버튼이 노출됩니다.
2. VERIFY-B — VERIFY-A 의 메시지를 같은 단말의 KakaoTalk 로 다른 카카오 계정으로 받은 뒤 preview card 를 탭 → 본인 앱으로 deep-link 진입 → `app/join.tsx` 의 invite-code 필드가 `?code=X` 로 자동 채워지고 auto-submit 됩니다.
3. VERIFY-C — `max_members` 가 가득 찬 방의 invite-code 로 join 시도 → toast `방이 가득 찼어요. 친구에게 새 방을 만들어 달라고 요청하세요.` + 폼 유지. (BE 의 `RoomFullException` → 409 + code `ROOM_FULL` mapping 의 e2e 확인.)

KakaoTalk 이 설치되지 않은 단말에서는 SDK 의 web share dialog 가 기본 fallback 입니다. web dialog 호출까지 실패해야 일반 `Share.share` 의 plain text + invite-code 경로가 실행됩니다. VERIFY-A 의 native hand-off 는 KakaoTalk 이 설치된 실기기에서 검증해야 합니다.

### 방법 B: 로컬 Gradle APK 빌드

Expo native Android 프로젝트를 생성합니다.

```bash
cd FE
npx expo prebuild --platform android
```

Debug APK:

```bash
cd android
./gradlew assembleDebug
```

결과물:

```text
FE/android/app/build/outputs/apk/debug/app-debug.apk
```

Debug APK를 emulator에 설치:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Release APK:

```bash
cd FE/android
./gradlew assembleRelease
```

결과물:

```text
FE/android/app/build/outputs/apk/release/app-release.apk
```

주의: release APK를 실제 기기에 설치하려면 signing 설정이 필요합니다. EAS `preview` 빌드는 이 과정을 대신 처리해 주므로 MVP 단계에서는 EAS 방식을 권장합니다.

## 7. iOS 빌드

### iOS Simulator 빌드

```bash
cd FE
npx expo run:ios
```

### EAS iOS 빌드

iOS 실기기 설치용 빌드는 Apple Developer 계정이 필요합니다.

```bash
cd FE
eas build --platform ios --profile development
```

App Store/TestFlight용 production 빌드:

```bash
cd FE
eas build --platform ios --profile production
```

### 로컬 Xcode 빌드

native iOS 프로젝트를 생성합니다.

```bash
cd FE
npx expo prebuild --platform ios
```

Xcode에서 열기:

```bash
open ios/Yeosal.xcworkspace
```

Xcode에서 signing team을 설정한 뒤 `Product > Build` 또는 `Product > Archive`를 실행합니다.

## 8. BE 로컬 실행

Postgres 없이 Spring 앱만 컴파일/테스트:

```bash
cd BE
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home gradle test --no-daemon
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home gradle build --no-daemon
```

로컬 Postgres가 떠 있고 DB가 준비되어 있으면 API 실행:

```bash
cd BE
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/yeosal \
SPRING_DATASOURCE_USERNAME=yeosal \
SPRING_DATASOURCE_PASSWORD=yeosal \
YEOSAL_JWT_SECRET=replace-with-at-least-32-random-characters \
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home \
gradle bootRun --no-daemon
```

API 기본 주소:

```text
http://localhost:8080/yeolsal/api/v1
```

예시:

```bash
curl http://localhost:8080/yeolsal/api/v1/friends
curl "http://localhost:8080/yeolsal/api/v1/feed/daily?date=2026-04-26"
curl "http://localhost:8080/yeolsal/api/v1/profiles/1/grass?from=2026-04-01&to=2026-04-30"
```

## 9. Docker로 서버 띄우기

Docker Desktop을 먼저 실행합니다.

환경 파일 생성:

```bash
cd infra
cp .env.example .env
```

`.env`에서 최소한 아래 값은 바꿉니다.

```text
POSTGRES_PASSWORD=change-me
YEOSAL_JWT_SECRET=replace-with-at-least-32-random-characters
KAKAO_CLIENT_ID=카카오_REST_API_KEY
KAKAO_REDIRECT_URI=https://api.rearleg.com/yeolsal/api/v1/auth/kakao/callback
KAKAO_MOBILE_REDIRECT_URI=yeosal://auth/kakao
```

서버 실행:

```bash
docker compose up --build
```

백그라운드 실행:

```bash
docker compose up --build -d
```

상태 확인:

```bash
docker compose ps
```

로그 보기:

```bash
docker compose logs -f api
docker compose logs -f nginx
docker compose logs -f postgres
```

서버 확인:

```bash
curl http://localhost:8088/health
curl http://localhost:8088/yeolsal/health
curl http://localhost:8088/yeolsal/api/v1/friends
```

중지:

```bash
docker compose down
```

DB 데이터까지 삭제:

```bash
docker compose down -v
```

## 10. Docker 이미지 직접 빌드

```bash
cd BE
docker build -t yeosal-api:local .
```

직접 실행:

```bash
docker run --rm -p 8080:8080 \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:5432/yeosal \
  -e SPRING_DATASOURCE_USERNAME=yeosal \
  -e SPRING_DATASOURCE_PASSWORD=yeosal \
  -e YEOSAL_JWT_SECRET=replace-with-at-least-32-random-characters \
  yeosal-api:local
```

대부분의 경우 직접 `docker run`보다 `infra/docker-compose.yml`을 사용하는 편이 낫습니다. Compose가 Postgres와 nginx까지 같이 올려 줍니다.

## 11. 운영 API 주소

FE의 기본 API 주소는 다음 값으로 고정되어 있습니다.

```text
https://api.rearleg.com/yeolsal/api/v1
```

로컬이나 다른 서버로 바꿔 테스트하려면 `FE/.env`를 만듭니다.

```bash
cd FE
cp .env.example .env
```

예시:

```text
EXPO_PUBLIC_API_BASE_URL=https://api.rearleg.com/yeolsal/api/v1
```

환경 값을 바꾼 뒤에는 Metro cache를 비우고 다시 실행합니다.

```bash
npx expo start -c
```

Kakao Developers에는 HTTP/HTTPS redirect URI만 등록할 수 있습니다. 모바일 딥링크 `yeosal://...`는 Kakao에 등록하는 값이 아니라 BE callback이 앱으로 되돌릴 때 쓰는 값입니다.

```text
https://api.rearleg.com/yeolsal/api/v1/auth/kakao/callback
```

## 12. Kakao REST API 설정

Kakao Developers에서 앱을 만들고 아래 순서로 설정합니다.

1. `https://developers.kakao.com`에서 애플리케이션을 생성합니다.
2. `앱 설정 > 앱 키`에서 `REST API 키`를 복사합니다.
3. `제품 설정 > 카카오 로그인`에서 카카오 로그인을 활성화합니다.
4. `제품 설정 > 카카오 로그인 > Redirect URI`에 아래 값을 등록합니다.

```text
https://api.rearleg.com/yeolsal/api/v1/auth/kakao/callback
```

5. `제품 설정 > 카카오 로그인 > 동의항목`에서 이메일 제공 동의를 설정합니다. BE는 Kakao email로 사용자를 찾거나 생성합니다.
6. 모바일 개발 환경의 `FE/.env`에 REST API 키를 넣습니다.

파일이 없으면 먼저 만듭니다.

```bash
cd FE
cp .env.example .env
```

```text
EXPO_PUBLIC_API_BASE_URL=https://api.rearleg.com/yeolsal/api/v1
```

7. Linux BE 서버의 `.env`에는 같은 REST API 키와 redirect URI를 넣습니다.

```text
KAKAO_CLIENT_ID=복사한_REST_API_키
KAKAO_REDIRECT_URI=https://api.rearleg.com/yeolsal/api/v1/auth/kakao/callback
KAKAO_MOBILE_REDIRECT_URI=yeosal://auth/kakao
```

8. `.env` 변경 후 모바일 앱은 Metro를 재시작합니다.

```bash
cd FE
npx expo start -c
```

Kakao REST API 키는 FE에 넣지 않습니다. 앱은 `/auth/kakao/authorize`로 서버를 먼저 열고, 서버가 Kakao로 redirect합니다.

### 12.1 Kakao Share SDK — Native App Key 셋업 (Story 6.2)

Story 6.2 가 도입한 Kakao 공유 SDK 는 REST API 키와 **별개** 인 **Native App Key** 를 사용합니다. 두 키를 혼동하면 보안 사고로 직결되므로 다음 순서대로 채웁니다.

9. `앱 설정 > 앱 키` 에서 **Native App Key** 를 복사합니다.
   - 이 키는 REST API 키와 **다른 키** 입니다. REST API 키는 서버 (`yeosal.kakao.client-id`, `KAKAO_CLIENT_ID`) 가, Native App Key 는 모바일 클라이언트 (`EXPO_PUBLIC_KAKAO_NATIVE_APP_KEY`) 가 사용합니다.
   - Native App Key 는 Kakao 가 의도적으로 클라이언트 번들에 포함되는 공개 식별자입니다. `EXPO_PUBLIC_*` 으로 노출해도 안전합니다. (`_bmad-output/project-context.md:235` 의 "Kakao REST API key lives on the BE only" rule 은 그대로 유지 — REST API Key 만 FE 노출 금지.)

10. `앱 설정 > 플랫폼 키 > Native App Key` 에서 platform 정보를 등록합니다.
    - Android: package name `app.yeosal.mobile` 과 사용하는 모든 signing key hash 를 등록합니다. 로컬 debug key, EAS preview/release key, Google Play App Signing key가 다르면 각각 필요합니다.
    - 로컬 debug key hash 예시:

      ```bash
      keytool -exportcert -alias androiddebugkey \
        -keystore ~/.android/debug.keystore \
        -storepass android -keypass android \
        | openssl sha1 -binary | openssl base64
      ```

    - EAS signing credential 은 `cd FE && eas credentials --platform android` 에서 확인하거나 내려받아 같은 방식으로 key hash 를 계산합니다. Play 배포본은 Google Play Console 의 App Signing certificate 값도 등록합니다.
    - iOS: Bundle ID `app.yeosal.mobile` 을 등록합니다.
    - 정보가 실제 바이너리와 다르면 `invalid android_key_hash` 또는 `invalid ios_bundle_id` 오류가 발생하고 일반 share fallback 으로 전환됩니다.

11. `앱 설정 > Product Link > Web domain` 에 `https://yeolsal.app` 을 등록합니다. 현재 Default Feed template 의 `mobileWebUrl` 과 `webUrl` 이 이 도메인을 사용하므로 platform 등록과 별도로 필요합니다.

12. 로컬 dev 머신의 `FE/.env` 에 Console 에 표시된 Native App Key 값을 그대로 채웁니다.

    ```text
    EXPO_PUBLIC_KAKAO_NATIVE_APP_KEY=실제_네이티브_앱_키
    ```

    `FE/.env.example` 는 placeholder (`replace-with-kakao-developers-console-native-app-key`) 만 commit 되어 있습니다. 실제 값이 들어간 `.env` 는 `.gitignore` 에 포함되어 있습니다.

13. EAS 빌드용으로 Native App Key 를 **EAS Environment Variable** 로 등록합니다. 이 값은 client bundle 에 포함되는 공개 식별자이며, `app.config.ts` 해석 시 읽혀야 하므로 secret visibility 를 사용하지 않습니다.

    ```bash
    cd FE
    eas env:create --scope project --environment preview \
      --visibility plaintext \
      --name EXPO_PUBLIC_KAKAO_NATIVE_APP_KEY \
      --value 실제_네이티브_앱_키
    eas env:create --scope project --environment production \
      --visibility plaintext \
      --name EXPO_PUBLIC_KAKAO_NATIVE_APP_KEY \
      --value 실제_네이티브_앱_키
    ```

    이후 각 build profile 이 대응하는 EAS environment 값을 build config 와 client bundle 에 주입합니다.

14. 환경 변수 변경 후 로컬은 native project 재생성 + rebuild 가 필요합니다 (Metro restart 만으로는 부족합니다).

    ```bash
    cd FE
    adb uninstall app.yeosal.mobile
    npx expo prebuild --clean
    npx expo run:android
    # iOS Simulator: npx expo run:ios
    # iOS 실기기: npx expo run:ios --device
    ```

## 13. 모바일 앱에서 로컬 Docker 서버를 바라보게 할 때

현재 화면은 mock data 중심이지만, API client 기본 주소는 `FE/src/api/config.ts`에 있습니다. 로컬 Docker 서버를 바라보려면 `FE/.env`의 `EXPO_PUBLIC_API_BASE_URL`을 환경별로 바꿉니다.

Android Emulator에서 Mac host 접근:

```text
http://10.0.2.2:8088/yeolsal/api/v1
```

iOS Simulator에서 Mac host 접근:

```text
http://localhost:8088/yeolsal/api/v1
```

실기기에서 접근:

```text
http://<Mac의 같은 Wi-Fi IP>:8088/yeolsal/api/v1
```

예시:

```text
http://192.168.0.25:8088/yeolsal/api/v1
```

## 14. 자주 쓰는 전체 명령

전체 검증:

```bash
bash scripts/verify.sh
```

FE 개발 서버:

```bash
cd FE
npm start
```

Android 실행:

```bash
cd FE
npm run android
```

iOS 실행:

```bash
cd FE
npm run ios
```

BE 테스트:

```bash
cd BE
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home gradle test --no-daemon
```

Docker 서버:

```bash
cd infra
docker compose up --build
```

APK:

```bash
cd FE
eas build --platform android --profile preview
```

## 15. Sentry 설정

FE `src/lib/sentry.ts`는 `EXPO_PUBLIC_SENTRY_DSN`이 없으면 자동으로 비활성화됩니다 (개발 시 fork에서 별도 처리 불필요).

### 처음 한 번만

1. https://sentry.io 에서 프로젝트 생성 (`react-native` 플랫폼).
2. DSN을 복사. (`https://...@o123.ingest.sentry.io/456`)
3. https://sentry.io/settings/account/api/auth-tokens/ 에서 Auth Token 발급. 권한: `project:releases`, `org:read`. (sourcemap 업로드용)

### 로컬 개발

DSN 없이 사용 (Sentry 비활성화).

```bash
cd FE
echo "EXPO_PUBLIC_SENTRY_DSN=" >> .env
```

또는 staging 프로젝트에 직접 보고하려면 `.env`에 DSN을 채워 넣으세요. `.env`는 `.gitignore`에 있어야 합니다.

### EAS 빌드용 시크릿

`SENTRY_AUTH_TOKEN`은 빌드 시점에만 필요하고 클라이언트 번들로 새지 않습니다. EAS Secrets에 저장하세요.

```bash
cd FE
eas secret:create --scope project --name SENTRY_AUTH_TOKEN --value <TOKEN>
eas secret:create --scope project --name EXPO_PUBLIC_SENTRY_DSN --value <DSN>
```

`EXPO_PUBLIC_SENTRY_ENVIRONMENT`는 `eas.json` 프로필별로 박혀 있으므로 시크릿으로 관리할 필요가 없습니다.

### 빌드 시 sourcemap 업로드

`@sentry/react-native/expo` 플러그인이 자동으로 처리합니다. EAS Secrets에 `SENTRY_AUTH_TOKEN`이 있으면 production 빌드에서 sourcemap을 Sentry에 업로드합니다.

### 검증

앱 실행 후 임의로 throw → ErrorBoundary fallback → Sentry 대시보드의 Issues 탭에 1분 내 이벤트가 떠야 정상.

```bash
cd FE
npm run ios
# 또는 Android
```

login.tsx 등에 임시로 `throw new Error("sentry test")`를 넣고 화면을 열면 ErrorBoundary가 fallback을 그리고 동시에 Sentry로 이벤트가 갑니다.

## 16. FE CI 와 native module 추가의 관계

본 리포의 GitHub Actions 는 **BE 전용** 입니다 (`.github/workflows/be-it-boot-smoke.yml` — Story 1.4 retro action item T3 의 V11 IT 게이트). FE 는 로컬 사전 검증 + EAS 빌드만 사용하며, 현재 FE 전용 CI workflow 는 ZERO 입니다.

`FE/package.json`, `FE/package-lock.json`, `FE/app.json`, `FE/app.config.ts`, config plugin, `FE/android/**`, `FE/ios/**` 변경이 native dependency 또는 native configuration 에 영향을 주는 경우, 다음 중 한 가지 방식으로 **clean native rebuild** 가 PR 검증 경로에 포함되어야 합니다.

1. **EAS 빌드 (현행 권장 경로)** — `eas build --profile preview --platform <android|ios>` 가 매 빌드마다 fresh prebuild + native compile 을 수행합니다. PR 리뷰어가 EAS 빌드 링크를 첨부하고 §6 의 VERIFY-A/B/C smoke 를 마치면 충분합니다.
2. **(미래) FE CI workflow** — PR 트리거 워크플로우가 추가될 경우, 단순 `npm test` / `npm run typecheck` 또는 `expo prebuild` 만으로 green 을 받을 수 없습니다. Android 예시는 `npx expo prebuild --clean --platform android` 후 `cd android && ./gradlew assembleDebug` 까지 실행해야 합니다. iOS 도 EAS build 또는 `xcodebuild` 로 실제 native compile 을 수행해야 합니다. Jest mock 만으로는 wire-level API 차이를 catch 할 수 없습니다 (Story 6.2 의 deviation: v1 의 `KakaoShareLink.sendDefault` 가 v2 에서 제거되어 wrapper 가 `shareFeedTemplate` 로 변경 — 단위 mock 으로는 이 차이가 드러나지 않음).

요약: native module 추가 PR 은 **로컬 `adb uninstall + npx expo prebuild --clean + npx expo run:android` 또는 EAS preview 빌드 + VERIFY-A/B/C** 가 PR 리뷰의 hard gate 입니다. 이 가드는 본 리포의 어떤 CI workflow 도 자동화하지 않으므로 **사람 (PR 작성자 + 리뷰어) 이 의식적으로 수행** 해야 합니다.

> "Native module changes require `adb uninstall app.yeosal.mobile` + clean rebuild" — Architecture §5.2 (line 520)

본 단락의 첫 문장 ("FE 전용 CI workflow 는 ZERO") 은 작성 시점 (2026-06-07) 의 사실입니다. 향후 FE CI 가 추가되면 이 한 줄을 업데이트하고, 위 (2) 의 가드를 실제 workflow 파일로 옮겨 자동화해야 합니다.

## 17. opt-in IT 워크플로 (`be-it-boot-smoke.yml`) timeout 정책

`.github/workflows/be-it-boot-smoke.yml` 은 Epic 1 retro action item **T3** 결과로 도입된 opt-in Testcontainers IT 레이어를 PR 마다 강제하는 워크플로입니다. 처음에는 `timeout-minutes: 30` 으로 시작했지만 Epic 7 시점 (Stories 7.1 / 7.2 누적) 에 다음 IT 클래스들이 누적되며 cap 을 초과하기 시작했습니다:

- `RoomControllerIT`
- `SurvivalStateEvaluatorIT`
- `SurvivalStateRosterIT`
- `V11MigrationIT`
- `FinalThreeServiceIT` (Story 7.1)
- `SvgRendererTokenDiffIT` (Story 7.1)
- `FinalThreeJobIT` (Story 7.2)
- `FinalThreeJobSchedulerRegistrationIT` (Story 7.2)
- `PreviewCardEndToEndIT` (Story 6.1)
- `ChatServiceRuleChangeIT` (Story 5.4)

PR #90 / #93 / #95 모두 30분 cap 에 도달해 `cancelled` 상태로 종료됐고, AC11 deferral allowance + `deferred-work.md` 코멘트로 squash-merge 했습니다 — Epic 1 retro G3 (*"never sign off a V11-class audit without a green opt-in IT run"*) 의 enforcement 메커니즘이 사실상 무력화된 상태였습니다.

**2026-06-08 Epic 7 retro A1 1차 시도 (가장 보수적인 즉시 unblock):** `timeout-minutes` 를 30 → 60 으로 상향. PR #98 에 박제.

**2026-06-08 1차 시도 결과:** PR #98 의 CI 가 정확히 1h 0m 15s 에 `cancelled` — 60min cap 도 구조적으로 부족함이 확인됨. 누적 28 개의 `@SpringBootTest`-annotated opt-in IT 가 각 ~25–30s 의 startup tax (Spring context cache miss + Testcontainers Postgres start) 를 지불하므로 12–14 min 이 단순 부팅 오버헤드. (run [27117144879](https://github.com/rearleg/yeolsal/actions/runs/27117144879))

**2026-06-08 Epic 7 retro A1 2차 시도 — 옵션 (d) Gradle 병렬 test forking + Testcontainers reuse:** 별도의 follow-up PR 로 처리. PR #98 마무리 직후 ship.

| 대안 | 장점 | 단점 | 결정 |
|---|---|---|---|
| (a) **60min cap (PR #98)** | 1줄 변경, 즉시 incremental 개선 | 60min 도 cap 에 도달 (PR #98 자체에서 확인) | ✅ ship, 부족 확인 |
| (b) **워크플로 matrix-split** (test class 단위로 parallel job 분할) | 실제 wall-clock 단축 | YAML 복잡도 ↑, 각 job 의 cold-start (Gradle cache, Docker pull) 중복으로 비용 ↑ | (d) 가 더 surgical, 보류 |
| (c) **Nightly schedule + PR manual-trigger backdoor** | PR wait 제거 | PR 머지 시점에 enforcement signal 없음 (deferral allowance 가 norm 화) | 최후 수단, 보류 |
| (d) **Gradle `maxParallelForks` + Testcontainers reuse** (현재 선택) | 단일 job 유지, build.gradle 9 줄 + workflow 1 step. 로컬 dev 영향 zero (opt-in via `-Dyeosal.test.parallel=N`). 28 × ~25s 직렬 startup → 4-fork 병렬 + 컨테이너 재사용으로 3–4min 추정 | build.gradle 변경 (대부분 story 의 banned-paths) — 별도 chore PR 필요 | ✅ 채택 |

**옵션 (d) 구현:**

- `BE/build.gradle` `tasks.named("test")` 블록 — `-Dyeosal.test.parallel=N` 으로 opt-in 분기. 로컬 `./gradlew test` 는 single-fork 유지 (concurrent Postgres 컨테이너가 dev 머신을 oversaturate 하지 않도록).
- `.github/workflows/be-it-boot-smoke.yml` — Testcontainers reuse 활성화를 위한 step (`echo "testcontainers.reuse.enable=true" > ~/.testcontainers.properties`) + 기존 `./gradlew test -Dyeosal.boot-smoke=true` 에 `-Dyeosal.test.parallel=4` 플래그 추가 (ubuntu-latest 의 4 vCPU 매칭).

**측정 기준 (다음 PR-CI 시):** 워크플로 wall-clock 이 60min 미만으로 떨어지면 (d) 성공. 여전히 cap 에 도달하면 (b) 워크플로 matrix-split 또는 (c) nightly schedule 로 추가 escalate. 이 결정의 owner 는 `_bmad-output/implementation-artifacts/epic-7-retro-2026-06-08.md` §8 A1 의 rearleg 입니다.
