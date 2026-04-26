# Architecture

## Frontend

Expo Router owns navigation. The app starts with auth screens and then a tab-like product surface: Today, Feed, Monthly, Profile, and Friend Profile. API access should be centralized under `src/api` when backend wiring begins.

## Backend

Spring Boot packages are split by capability:

- `auth`: email/JWT and Kakao auth endpoints.
- `user`: user profile and timezone state.
- `friend`: friend relationships, requests, and feed access.
- `daily`: daily entries, todos, reflections, and mission calculation.
- `stats`: monthly count aggregation.
- `profile`: public profile and grass data.
- `common`: shared DTOs/errors/config.

## Data

PostgreSQL is the source of truth. Flyway migrations live in `BE/src/main/resources/db/migration`.

## Deployment

Home server deployment runs `api`, `postgres`, and `nginx` through Docker Compose. Secrets are supplied through `.env`.
