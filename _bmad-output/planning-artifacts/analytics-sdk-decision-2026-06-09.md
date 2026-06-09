# Analytics SDK Decision — Pre-2 (Story 8.5 Prerequisite)

- **Date:** 2026-06-09
- **Decision owner:** rearleg (Project Lead)
- **Context:** Epic 7 retrospective §9 critical-path item Pre-2 — Story 8.1 (5-screen onboarding) hard blocker. Story 8.5 (Analytics SDK selection + event taxonomy) was the original W1 readiness mandate (2026-05-11 M3) that slipped to W7.
- **Status:** **DECIDED** — PostHog self-hosted.

---

## 1. Decision

**Analytics SDK:** PostHog (self-hosted on the existing infra stack alongside Spring Boot + Postgres).

**Versions to lock at Story 8.5 implementation (verify latest at spec authoring time):**

- FE: `posthog-react-native` (Expo SDK 54 compatible)
- BE: `posthog-java` (Spring Boot 3.3.5 compatible)
- Self-host image: `posthog/posthog:latest-release` (pin minor version once chosen)

**Data residency:** Self-hosted on the same VPS / cloud region as the existing Postgres + Spring Boot deployment. KR users → KR data path. **PIPA gold** — no SCC contract needed, no cross-border transfer.

---

## 2. Rationale (vs. rejected alternatives)

Full comparison matrix lives in the conversation history of the 2026-06-09 Pre-2 brainstorming session. Summary of the decision:

| Option | Why rejected |
|---|---|
| **PostHog Cloud (EU)** | Adds GDPR-via-adequacy chain on top of PIPA. Self-host removes the chain entirely. EU latency adds Korea→EU round-trip on every event. |
| **Mixpanel Cloud (APAC Singapore)** | APAC region is 1-hop from KR but still cross-border; SCC contract overhead. Proprietary query language adds modest lock-in. Funnel UI is more mature than PostHog's but doesn't justify the residency tradeoff. |
| **Amplitude** | 50K MTU free tier may bind quickly post-launch. AP region only on Enterprise tier. Same proprietary lock-in profile as Mixpanel. |
| **Custom (own BE)** | W1 deadline impossible. Funnel + cohort SQL + opt-in UI + lint helper would consume ~2-4 weeks; this is exactly what Story 8.5 was scoped to *avoid*. PostHog gives all of those OOB. |

**Why PostHog self-hosted specifically (vs. PostHog Cloud):**

1. **PIPA compliance gold standard.** Data stays on KR infra → no transfer notice in privacy policy, no SCC contract, no DPA negotiation with vendor. Simpler PIPA opt-in language on onboarding screen 5.
2. **Unlimited events.** v1 launch trajectory is unknown; phase-2 BM validation will want every revival/share/spectator event preserved indefinitely. Cloud free tiers cap at 1M events/month; self-hosted has no cap.
3. **OSS — sidecar tooling is straightforward.** Story 8.5 AC mandates `tools/analytics-taxonomy-lint.ts`. With self-hosted PostHog, we can also fork the schema or add a custom event ingestion endpoint if needed.
4. **Sentry mirror pattern.** The existing Sentry self-hosting story is well-documented in `RUNBOOK.md`. Adding PostHog as a second long-running service in the same docker-compose follows the same operational shape.

**Tradeoffs accepted:**

- **+1 service on infra.** PostHog needs Postgres (or ClickHouse for events tier; PostHog uses ClickHouse for event store + Postgres for metadata). This adds operational surface (backups, monitoring, disk usage). RUNBOOK update required as Story 8.5 sub-task.
- **Self-host upgrade discipline.** PostHog releases roughly monthly; we'll need a defined upgrade cadence (quarterly recommended unless security advisory). Add to RUNBOOK §17-equivalent.
- **Smaller Korean reference base than Mixpanel/Amplitude.** Most KR product analytics blog posts reference Mixpanel/Amplitude. PostHog docs are EN-only. Acceptable — the integration is straightforward.

---

## 3. Story 8.5 spec inputs (what the spec author should consume)

### 3.1 ACs that are now answered

Story 8.5's AC1 (`docs/analytics.md` with chosen SDK + version + env vars + data-residency note + privacy alignment + rationale) is **half-pre-resolved** by this doc. The Story 8.5 spec should:

- Reference this doc instead of re-running the decision discussion.
- Define `EXPO_PUBLIC_POSTHOG_HOST` + `EXPO_PUBLIC_POSTHOG_API_KEY` (project API key, not personal API key) as the FE env var names.
- BE: `POSTHOG_HOST` + `POSTHOG_PROJECT_API_KEY` + `POSTHOG_PERSONAL_API_KEY` (latter only if admin API needed for taxonomy lint helper).

### 3.2 Event taxonomy compatibility check

All 5 funnels + user properties from Story 8.5 epic AC are PostHog-compatible without modification:

| Story 8.5 AC | PostHog mechanism |
|---|---|
| 5 funnels (activation / revival / friend-gift / spectator-cohort / Final-3 share) | `posthog.capture(event_name, properties)` — flat event model; no schema migration needed for new events |
| User properties (`user_id`, `account_age_days`, `room_count`, `current_survival_state`, `is_room_leader`) | `posthog.identify(distinct_id, person_properties)` + group-analytics if room-scoped properties needed |
| Server-side events (`friend_gift.push_sent`, `spectator.entered`) | `posthog-java` SDK from Spring Boot service layer; no internal endpoint needed — direct ingestion to self-hosted PostHog |
| PIPA opt-in/opt-out | `posthog.opt_out_capturing()` + `posthog.opt_in_capturing()` + init with `disabled: true` if user denies on onboarding screen 5 |
| Taxonomy lint helper | `tools/analytics-taxonomy-lint.ts` grep + AST-walk source for `posthog.capture('...')` calls, cross-reference `docs/analytics.md` table; PostHog provides no built-in registry, custom lint is the right shape |

### 3.3 PIPA opt-in/opt-out flow design

PostHog SDK init flow on FE (PIPA-compliant pattern for KR convention):

```typescript
// FE/src/lib/analytics.ts (Story 8.5 owns)
import { usePostHog } from 'posthog-react-native';

// On app boot:
posthog.init(EXPO_PUBLIC_POSTHOG_API_KEY, {
  host: EXPO_PUBLIC_POSTHOG_HOST,
  disable_session_recording: true,  // off by default for v1; revisit at phase-2
  opt_out_capturing_by_default: !userConsent,  // userConsent = AsyncStorage('analytics_consent') === 'opt_in'
});

// On user toggle in Settings → Privacy → "사용 통계 공유":
if (toggleOn) {
  posthog.opt_in_capturing();
  AsyncStorage.setItem('analytics_consent', 'opt_in');
} else {
  posthog.opt_out_capturing();
  AsyncStorage.setItem('analytics_consent', 'opt_out');
}
```

**Default state decision (deferred to Story 8.1 onboarding screen 5 PM lock):** Story 8.5 AC line 1116 says "defaults to opt-in for KR convention but with clear explanation; alternatively defaults to opt-out — PM decides at W1 lock; tested for both paths". This decision is **NOT** in Pre-2 scope; flag for Story 8.1 spec authoring.

### 3.4 Self-hosting infra additions

**Docker compose service addition** (Story 8.5 will land):

```yaml
services:
  posthog:
    image: posthog/posthog:latest-release
    environment:
      DATABASE_URL: postgres://posthog:${POSTHOG_DB_PASSWORD}@posthog-db:5432/posthog
      REDIS_URL: redis://posthog-redis:6379
      SECRET_KEY: ${POSTHOG_SECRET_KEY}
      SITE_URL: https://analytics.yeolsal.app   # subdomain TBD with infra owner
      KAFKA_HOSTS: posthog-kafka:9092
      CLICKHOUSE_HOST: posthog-clickhouse
    depends_on:
      - posthog-db
      - posthog-redis
      - posthog-clickhouse
      - posthog-kafka
    ports:
      - "8000:8000"
```

(PostHog uses ClickHouse for events + Postgres for metadata. Single-instance self-host adds 4 services: posthog app, posthog-db Postgres, posthog-redis, posthog-clickhouse, posthog-kafka. RUNBOOK §X update needed.)

**Backup strategy:** events go to ClickHouse — daily snapshot via `clickhouse-backup`. Metadata Postgres → existing Postgres backup pattern. Story 8.5 sub-task.

### 3.5 Sentry boundary

**Sentry stays BE-error-only.** Story 8.5 AC line 1099 says "rationale (no Sentry replacement; complement only)" — explicitly. PostHog handles product analytics + funnels + cohorts + (later) feature flags. Sentry handles exception telemetry + performance traces + alerting (NFR-9.3.7 mass-elimination alert).

No cross-wiring needed. No event duplication.

---

## 4. Open questions for Story 8.5 spec authoring

1. **Subdomain for self-hosted PostHog UI** — `analytics.yeolsal.app` vs. `yeolsal.app/analytics` vs. unique domain. DNS + cert decision needed with infra owner.
2. **Default analytics_consent state** — opt-in by default vs. opt-out by default (Story 8.5 AC defers to PM at W1 lock; this is Story 8.1 onboarding screen 5 copy decision).
3. **PostHog event retention** — self-hosted ClickHouse can hold years; PRD §3.1 Day-7 / Day-30 cohort math needs at least 60 days. **Decision: 365 days retention** for phase-2 validation. (Confirm with rearleg at Story 8.5 sub-task.)
4. **Lint helper severity** — Story 8.5 AC says "warns (not fails)". Confirm at spec authoring; if changed to fail, brand-voice-lint pattern applies.
5. **Internal staff opt-out** — Story 8.5 should add a `is_internal: true` user property auto-set if `EXPO_PUBLIC_INTERNAL_BUILD=true` so internal builds don't pollute KPIs.

---

## 5. Cross-references

- Epic 7 retro: `_bmad-output/implementation-artifacts/epic-7-retro-2026-06-08.md` §9 Pre-2, §10 Discovery 1
- Story 8.5 epic spec: `_bmad-output/planning-artifacts/epics.md` §Story 8.5 (lines 1085–1126)
- PRD KPIs: `_bmad-output/planning-artifacts/prd.md` §3.1 (Activation & Retention KPIs)
- Architecture Sentry note: `_bmad-output/planning-artifacts/architecture.md` §3 (Sentry remains BE-error only)
- Readiness M3 (2026-05-11) — original W1 mandate for Story 8.5

---

## 6. What this doc does NOT decide

- **Story 8.5 sprint position** — Discovery 1 from Epic 7 retro recommends 8.5 → first position in Epic 8 sprint order. That sprint reorder is a separate action (use `bmad-correct-course` or `bmad-sprint-planning` skill). This Pre-2 doc just confirms which SDK gets ordered first.
- **Story 8.1 onboarding screen 5 PIPA copy** — opt-in default vs opt-out default + the Korean copy. That belongs in Story 8.1 spec authoring + PM lock.
- **Backfill instrumentation pass** — Stories 4.x / 6.x / 7.x emit-points were shipped before this decision and have no PostHog calls. Decision deferred: either accept v1-launch gap or schedule a small instrumentation pass in Epic 8 after Story 8.5 SDK is live. Flag for Epic 8 sprint planning session.
