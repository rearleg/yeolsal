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
