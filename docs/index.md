# Project Documentation Index — yeolsal

Generated: **2026-05-09** by `/bmad-document-project` (initial_scan, quick).

This is the primary entry point for AI-assisted development. Downstream BMad workflows (PRD, architecture, sprint planning) should be pointed at this file.

---

## Project Overview

- **Type**: multi-part repo with **2 parts**
- **Primary languages**: TypeScript (FE), Java 21 (BE)
- **Architecture**: Expo React Native mobile (FE) ↔ Spring Boot 3.3 monolith (BE) over HTTPS REST + STOMP/WSS
- **Persistence**: PostgreSQL via Flyway (V1–V10)
- **Deploy**: Docker Compose (api + postgres + nginx, ext. port 8088); EAS for mobile builds

For the full overview narrative see [`./project-overview.md`](./project-overview.md).

## Quick Reference by Part

### `fe` — Yeosal Mobile App (`FE/`)

- **Type**: mobile
- **Tech**: Expo SDK 54.0.34 · React Native 0.81.5 · React 19.1 · TypeScript 5.9 (strict) · expo-router 6 · TanStack Query 5.100.6 · @stomp/stompjs 7.3.0
- **Entry**: `FE/app/_layout.tsx` (root nav), `FE/src/api/client.ts` (HTTP), `FE/src/providers/RealtimeProvider.tsx` (single STOMP client)
- **Docs**: [Architecture](./architecture-fe.md) · [Component Inventory](./component-inventory-fe.md) _(To be generated)_ · [Development Guide](./development-guide-fe.md) _(To be generated — see `../RUNBOOK.md` for the canonical commands)_

### `be` — Yeosal API (`BE/`)

- **Type**: backend
- **Tech**: Java 21 · Spring Boot 3.3.5 · Spring Security · Spring Data JPA (`validate`) · Flyway · JJWT 0.12.6 · STOMP/WebSocket · springdoc-openapi 2.6.0 · Testcontainers
- **Entry**: `YeosalApiApplication`, `SecurityConfig`, `WebSocketConfig`, `ApiExceptionHandler`, `RealtimePublisher`
- **Docs**: [Architecture](./architecture-be.md) · [API Contracts](./api-contracts-be.md) · [Data Models](./data-models-be.md) · [Development Guide](./development-guide-be.md) _(To be generated — see `../RUNBOOK.md` for the canonical commands)_

## Cross-Part

- **Integration architecture**: [`./integration-architecture.md`](./integration-architecture.md) — REST envelope, JWT lifecycle, STOMP topics, REST/WS dedupe, push tokens, configuration boundary
- **Multi-part metadata**: [`./project-parts.json`](./project-parts.json)

## Generated Documentation

- [Project Overview](./project-overview.md)
- [Source Tree Analysis](./source-tree-analysis.md)
- [Architecture — FE](./architecture-fe.md)
- [Architecture — BE](./architecture-be.md)
- [API Contracts — BE](./api-contracts-be.md)
- [Data Models — BE](./data-models-be.md)
- [Component Inventory — FE](./component-inventory-fe.md) _(To be generated)_
- [Development Guide — FE](./development-guide-fe.md) _(To be generated)_
- [Development Guide — BE](./development-guide-be.md) _(To be generated)_
- [Deployment Guide](./deployment-guide.md)
- [Integration Architecture](./integration-architecture.md)
- [Project Parts (metadata)](./project-parts.json)
- [Project Scan Report (state)](./project-scan-report.json)

## Existing Lightweight Docs (pre-BMad)

- [Product](./product.md) — MVP scope summary
- [Architecture (sketch)](./architecture.md) — short architecture overview
- [API Contract (sketch)](./api-contract.md) — minimal endpoint list
- [Design System](./design-system.md) — Risograph + neobrutalist tokens
- [Test Plan](./test-plan.md) — high-level coverage plan

## Repo-wide Operating Contract

- [`../AGENTS.md`](../AGENTS.md) — engineering rules (timezone, secrets, daily-count semantics)
- [`../CONTRIBUTING.md`](../CONTRIBUTING.md) — stack-PR merge procedure (incident-driven), pre-push verification
- [`../RUNBOOK.md`](../RUNBOOK.md) — run / test / build / deploy how-to
- [`../guide.md`](../guide.md) — ECC harness setup
- [`../_bmad-output/project-context.md`](../_bmad-output/project-context.md) — **must-read** agent rules covering language-/framework-/test-/style-/workflow-/anti-pattern guidance

## Getting Started

For a new contributor or AI agent:

1. Read [`../_bmad-output/project-context.md`](../_bmad-output/project-context.md) for the project's rules-of-the-road. This is non-negotiable context.
2. Skim [`./project-overview.md`](./project-overview.md) and [`./source-tree-analysis.md`](./source-tree-analysis.md) for layout.
3. Pick a part and read the matching architecture doc.
4. For server work, read [`./api-contracts-be.md`](./api-contracts-be.md), [`./data-models-be.md`](./data-models-be.md), and [`./integration-architecture.md`](./integration-architecture.md).
5. Use [`../RUNBOOK.md`](../RUNBOOK.md) for any setup/run/build command. Run `bash scripts/verify.sh` before pushing.

## Brownfield PRD Pointer

When starting a PRD or architecture workflow next:

- Point `bmad-create-prd` / `bmad-create-architecture` / `bmad-sprint-planning` at this `index.md`.
- Multi-part metadata for those workflows is in `./project-parts.json`.

## Re-Generating This Documentation

Run `/bmad-document-project` from the repo root:

- **Re-scan entire project** — picks the same workflow path with the latest code state.
- **Deep-dive into specific area** — generate exhaustive docs for one feature/folder.
- **Cancel** — keep existing documentation as-is.

The workflow detects this file's existence and prompts for the choice. Marker convention: items above tagged _(To be generated)_ are detected automatically and offered for completion in Step 11.
