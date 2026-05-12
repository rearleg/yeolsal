# Project Overview — yeolsal

## Purpose

**Yeolsal (열살)** is a mobile-first accountability app for small friend groups. A day "counts" when a member posts a daily goal/todo list and submits the next reflection before the following morning at **06:00 in `Asia/Seoul`**. The product surface is intentionally minimal: Today, Feed, Monthly, Profile, Friend Profile, plus invite-coded Rooms with chat.

Source of truth for product scope: `docs/product.md`.

## Repository Type

**Multi-part monorepo** (npm workspace at root, but BE is Gradle-managed independently).

| Part | Path | Type | Stack |
|------|------|------|-------|
| `fe` | `FE/` | mobile | Expo SDK 54, React Native 0.81, React 19.1, TypeScript 5.9 (strict), expo-router 6 |
| `be` | `BE/` | backend | Spring Boot 3.3.5, Java 21, PostgreSQL, Flyway, JPA, JJWT 0.12.6, STOMP/WS |

## Tech-Stack Summary

| Category | Technology | Version |
|----------|------------|---------|
| Mobile runtime | Expo / React Native | 54.0.34 / 0.81.5 |
| Mobile language | TypeScript (strict) | ~5.9.0 |
| Mobile routing | expo-router | 6 |
| Mobile data | TanStack Query (+ AsyncStorage persist) | 5.100.6 |
| Mobile realtime | @stomp/stompjs | 7.3.0 |
| Mobile observability | @sentry/react-native | ~7.2.0 |
| Mobile build | EAS (preview/APK, production/AAB+iOS) | — |
| Backend runtime | Java | 21 |
| Backend framework | Spring Boot | 3.3.5 |
| Backend persistence | PostgreSQL via Spring Data JPA | — |
| Backend migrations | Flyway | V1–V10 |
| Backend auth | JJWT + Kakao OAuth | 0.12.6 |
| Backend realtime | Spring WebSocket (STOMP) | — |
| Backend tests | JUnit 5 + Testcontainers PostgreSQL | — |
| API docs | springdoc-openapi | 2.6.0 |
| Container deploy | Docker Compose (api + postgres + nginx) | — |
| External port | nginx | 8088 |
| API base path | `/yeolsal/api/v1` | — |

## Architecture Type

- BE: package-by-feature monolith (`auth, common, daily, friend, notification, profile, realtime, room, stats, user`) behind a single Spring Boot module.
- FE: feature-oriented React Native app with provider-based realtime + persisted query cache.
- Integration: HTTPS REST + STOMP-over-WSS on a single `/ws` endpoint authenticated at the CONNECT frame.

## Documentation Index

- [Architecture — FE](./architecture-fe.md)
- [Architecture — BE](./architecture-be.md)
- [Source Tree Analysis](./source-tree-analysis.md)
- [API Contracts — BE](./api-contracts-be.md)
- [Data Models — BE](./data-models-be.md)
- [Component Inventory — FE](./component-inventory-fe.md)
- [Development Guide — FE](./development-guide-fe.md)
- [Development Guide — BE](./development-guide-be.md)
- [Deployment Guide](./deployment-guide.md)
- [Integration Architecture](./integration-architecture.md)

## Existing Lightweight Docs (pre-BMad)

- [`product.md`](./product.md) — MVP scope summary
- [`architecture.md`](./architecture.md) — short architecture sketch
- [`api-contract.md`](./api-contract.md) — minimal REST endpoint list
- [`design-system.md`](./design-system.md) — Risograph + neobrutalist tokens
- [`test-plan.md`](./test-plan.md) — high-level test coverage plan

## Repo-wide Operating Contract

- [`AGENTS.md`](../AGENTS.md) — engineering rules (timezone, secrets, daily-count semantics)
- [`CONTRIBUTING.md`](../CONTRIBUTING.md) — stack-PR merge procedure (incident-driven), pre-push verification
- [`RUNBOOK.md`](../RUNBOOK.md) — run/test/build/deploy how-to
- [`_bmad-output/project-context.md`](../_bmad-output/project-context.md) — agent-facing critical rules (must-read before coding)
