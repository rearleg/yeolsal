# Test Plan

## Frontend

- Token/component render tests for neobrutalist styling.
- Login/signup validation.
- Today entry creation, todo completion, and reflection state display.
- Profile grass rendering, date selection, and completed todo count display.
- Logo loading on login, splash, and header.

## Backend

- Daily mission calculation: entry only, reflection only, before/after 06:00, and month boundary.
- Grass calculation: mission completion, completed todo count, and intensity.
- Auth API: signup, login, refresh, invalid token.
- Kakao login with mocked Kakao client.
- Friend request/acceptance/feed/profile authorization.
- Postgres integration tests with Testcontainers.

## Full Verification

Run `scripts/verify.sh` from repo root.
