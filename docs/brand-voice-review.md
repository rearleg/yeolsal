# Brand-Voice Release-Gate Review

> The canonical, version-controlled release-gate **checklist** for brand-voice review of
> **yeolsal** (열살방), plus the **sign-off mechanism** that enforces it. Brand integrity is
> a release gate: no production release ships without a joint **PM + designer** brand-voice
> sign-off, recorded per release and enforced by a required GitHub Actions check plus branch and
> tag rulesets. `CODEOWNERS` requests the reviewers; it does not prove that both roles approved.
>
> Epic 8 exists to defuse the "penalty / 벌금" mental model and make survival feel like
> dignity. Stories 8.1–8.3 authored the dignity-first copy and the lint that keeps it honest.
> This gate is the **governance** that prevents drift after launch: as new features add new
> copy, the release gate forces a human brand-voice sign-off before any production release.
>
> **This file is evergreen — the template of record.** It carries no version number and no
> filled sign-off. Each release duplicates it to `docs/releases/brand-voice-review-<version>.md`
> (from `docs/releases/brand-voice-review-TEMPLATE.md`), fills the checkboxes, records the
> decisions inline, and is approved in the release PR by the two `CODEOWNERS` reviewers.

---

## 1. Purpose and how it gates a release

Before every major release, the copy that users read is reviewed against the yeolsal brand
voice — invitation over demand, comeback over elimination, "우리" over "나". The hard stop is a
**joint PM + designer sign-off**, enforced GitHub-natively:

- The per-release instance file (`docs/releases/brand-voice-review-<version>.md`) is part of the
  release PR.
- `.github/CODEOWNERS` requests review from the designated brand-voice reviewers.
- `.github/workflows/brand-voice-release-gate.yml` requires exactly one per-release checklist and
  independently verifies an approval from a configured **PM reviewer** and **designer reviewer**.
- A `main` branch ruleset requires that workflow check before a release PR can merge. A separate
  `v*` tag ruleset restricts release-tag creation (see [Post-merge user actions](#post-merge-user-actions-github-side-settings)).

**There is no override.** A flagged item is fixed and the checklist re-run; it is not waived
(epics.md:1068, 1077). The automated lint is an advisory pre-pass — the human sign-off is
authoritative (architecture.md §4.15).

---

## 2. Automated pre-pass (run first, attach output)

Run the existing tools first and **attach their output** to the per-release instance. They are
the machine baseline; the reviewer then applies human judgment on top.

Fail-closed command (installs the tools workspace when needed, then runs each required pre-pass):

```bash
npm --prefix tools ci
tools/node_modules/.bin/tsx tools/brand-voice-lint.ts
tools/node_modules/.bin/tsx tools/aso-copy-lint.ts
tools/node_modules/.bin/tsx tools/contrast-check.ts
```

`bash scripts/test.sh` remains the full repository verification command, but it deliberately skips
the tools block when `tools/node_modules` is absent. A skipped block is **not** valid release-gate
evidence. Attach output from all three commands above.

| Tool | What it reports | Severity | Attach |
|---|---|---|---|
| `tools/brand-voice-lint.ts` | AVOID-lexicon occurrences (Rule 2/3 WARN) **and** the NFR-9.6.1 packed-type sub-gate (Rule 1 HARD) | warn-only **except** the one Rule-1 HARD sub-gate | the final line — the **HARD count must be `0`** — plus any WARN lines |
| `tools/aso-copy-lint.ts` | store-copy WARN occurrences over `docs/aso-copy.md` copy regions | warn-only | exit code + WARN count |
| `tools/contrast-check.ts` | WCAG 2.2 AA contrast pass/fail | a11y note | pass/fail summary |

**Lint is advisory; the human sign-off is authoritative** (architecture.md §4.15). A clean lint
does not mean the copy is in voice — the lint only knows the 8 banned words and the token rules;
it cannot judge tone, warmth, or whether a new string invites rather than demands. Conversely, a
WARN is not an automatic block — the reviewer may `accept` it with a recorded rationale.

---

## 3. The checklist — five surface categories

The five categories below are fixed by the epics AC (epics.md:1070–1075). Each names the concrete
sites a reviewer opens in **this** codebase — open the files, read the user-visible strings, and
judge each against the [Reference](#reference--brand-voice-lexicon-and-rules) lexicon and tone rules.
A reviewer should never have to guess where a surface lives.

### 3.1 Push notification copy paths (BE + FE)

Tone rule: **invitation, not demand** (FR-8.8.3). "수진이 회생을 기다리고 있어요" ✓ — not
"수진이가 죽었다 살려라!" ✗.

- [ ] **BE push bodies/titles** — `BE/src/main/java/com/yeosal/api/notification/`, especially
  `SpectatorDigestScheduler.java` (e.g. the title literal `"오늘도 %s 함께 살아남고 있어요"`),
  `NotificationService.java`, `NotificationScheduler.java`. Read every Korean push string literal.
- [ ] **FE push presentation** — `FE/src/lib/notifications.ts`, `FE/src/lib/push.ts`, and any
  notification-settings copy (`FE/app/notification-settings.tsx`).
- [ ] No AVOID-lexicon term in any push title or body; every push reads as an invitation to
  return/help, never as a demand or a guilt trip.

### 3.2 All onboarding screens

- [ ] **Onboarding script** — `FE/app/onboarding.tsx` + `FE/src/components/onboarding/OnboardingCarousel.tsx`
  (+ `OnboardingDotIndicator.tsx`): the 5-screen carousel copy and the returning-user change-summary
  screen (Story 8.1).
- [ ] **PIPA consent copy** — the consent prompt strings on the final onboarding screen. Confirm the
  consent ask is framed as an invitation and the default is explicit opt-in.
- [ ] Onboarding copy matches the FR-8.8.1 locked strings; no AVOID-lexicon term; the "함께 살아남는"
  framing is intact.

### 3.3 All error message strings

Elimination / error copy uses **"컴백 가능" language, never "탈락" / "실패"** (FR-8.8.5).

- [ ] **BE user-facing messages** — domain exceptions across `com.yeosal.api.*` and the messages
  surfaced by `BE/src/main/java/com/yeosal/api/common/ApiExceptionHandler.java`. (Error responses
  must not leak internals — the handler sanitizes; review only the user-facing text.)
- [ ] **FE error/toast copy** — `FE/src/lib/toast.ts` and the per-screen `ApiError.code` branch
  messages (the strings shown when a request fails).
- [ ] **Component-local error copy** — search `FE/src/components/` and `FE/app/` for `COPY`
  constants, `Alert.alert`, toast calls, mutation `onError` handlers, and inline failure/disabled
  messages. These strings are not centralized in `toast.ts`.
- [ ] No "탈락" / "실패" framing for an eliminated/at-risk member; the copy points at comeback
  ("컴백 가능", "회생") rather than failure or loss.

### 3.4 All store metadata (KR + EN)

- [ ] **Storefront copy** — `docs/aso-copy.md` (Story 8.3). KR uses **"회생권"**; EN uses
  **"comeback pass"**, never "revival ticket" / "second chance pass". The category/rating lock and
  screenshot captions are in the same file.
- [ ] The automated pre-pass for this item is `tools/aso-copy-lint.ts`; this file **is** the artifact
  the item reviews. Confirm no AVOID-lexicon term in the KR copy regions and no banned EN noun phrase
  in the EN copy regions.

### 3.5 System message templates in chat_messages

The `publish…SystemMessage` methods in `BE/src/main/java/com/yeosal/api/room/chat/ChatService.java`
write byte-locked SYSTEM rows. The review confirms existing templates stay in voice and any **new**
one added this release is reviewed:

- [ ] Rule-change broadcast body — `"다음 달부터 새 규칙이 적용됩니다: " + preview` (Story 5.4).
- [ ] Monthly no-survivors ceremony body — `"이번 달은 아무도 살아남지 못했어요 — 다음 달은 함께 가요"`
  (Story 7.1).
- [ ] Member-joined and any other `publish…SystemMessage` template added since the last release.
- [ ] No AVOID-lexicon term; the dignity/togetherness tone holds even in the "no survivors" case.

---

## 4. Sign-off mechanism (LOCKED)

Frozen 2026-05-11 (epics.md:1068) — implement, do not redesign:

1. This checklist lives in the repo (`docs/brand-voice-review.md`).
2. Each release's instance is duplicated as `docs/releases/brand-voice-review-<version>.md` from
   `docs/releases/brand-voice-review-TEMPLATE.md`.
3. Sign-off is a **GitHub PR review approval** from a PM-designated reviewer **and** a
   designer-designated reviewer. The disjoint role lists live in the repository variables
   `BRAND_VOICE_PM_REVIEWERS` and `BRAND_VOICE_DESIGNER_REVIEWERS`.
4. The required `Brand-voice release gate / PM + designer brand-voice approval` check verifies both
   roles and the checklist before a release PR can merge to `main`. The `v*` tag ruleset then limits
   tag creation to release maintainers.

The authoritative approval is the **GitHub PR review** — the checkboxes in the instance file are a
human-readable mirror, not the gate. GitHub's native CODEOWNERS behavior accepts any one owner on a
matching pattern, so the required workflow performs the role-specific verification. Do not
substitute a different approval channel. See `.github/CODEOWNERS`,
`.github/workflows/brand-voice-release-gate.yml`, and the
[Post-merge user actions](#post-merge-user-actions-github-side-settings).

---

## 5. Inline decision recording (LOCKED)

When the lint flags an item — or a human spots one — the reviewer records the decision **inline in
the per-release Markdown** as one of `accept` / `reject` / `needs-rewrite`, with the reviewer's GH
handle and the date. The per-release decision-log table uses these locked columns:

```
| Surface | Item / file:line | Lint? | Decision (accept/reject/needs-rewrite) | Reviewer (@handle) | Date | Notes |
```

- `accept` — in voice (or an acceptable WARN); ships as-is, rationale in Notes.
- `reject` — out of voice; the copy change is reverted/blocked before release.
- `needs-rewrite` — fix required; see the SLA below, then re-run the checklist.

---

## 6. Needs-rewrite SLA (LOCKED)

Frozen 2026-05-11 (epics.md:1081):

- A `needs-rewrite` item is addressed within **1 business day**.
- If the next release window is **< 24h** away, the affected feature is **feature-flagged off**
  rather than shipped with an unresolved brand-voice flag.
- For v1, "feature-flagged off" means the release must name a concrete disable control in the
  decision row's Notes field using
  `disabled: <environment/build/runtime control>; evidence: <https URL>`. The control may be an
  existing runtime flag, an environment/build exclusion, or reverting the affected feature from the
  release branch. A vague promise to disable later is not evidence.
- No override. After the fix, **re-run the checklist** — the gate passes only on a clean instance
  with both role approvals. A `reject` row fails the required workflow. A `needs-rewrite` row fails
  unless its disable control and evidence URL are recorded in that exact format.

---

## 7. Versioning and maintenance

- This checklist is versioned in the repo and updated as new copy surfaces are added in future
  releases (epics.md:1083).
- **Rule:** any PR that introduces a **new user-facing copy surface** (a new push path, a new screen,
  a new error string family, a new `chat_messages` SYSTEM template, a new store field) adds that
  surface to the [category list in §3](#3-the-checklist--five-surface-categories) in the same PR.
  The checklist must stay an exhaustive map of where copy lives.
- An optional future hardening (not enabled here): broaden `.github/CODEOWNERS` to also own
  `docs/aso-copy.md` and `FE/src/components/onboarding/` so copy edits route through brand-voice
  reviewers. Today CODEOWNERS owns only the release-review artifacts, to avoid routing every copy PR
  through the gate.

---

## Post-merge user actions (GitHub-side settings)

These are GitHub repo settings that **cannot** be fully committed as files. Do them after this story
merges, or the workflow and CODEOWNERS only report/request review without blocking merge.

> **Status (v1 — solo build): DEFERRED, not enforced.** Both `.github/CODEOWNERS` slots are
> `@rearleg` and the role variables are unset, so the gate currently only *requests/reports* — it
> does not block a merge. A genuine joint gate requires two **distinct** GitHub accounts (a PM and a
> designer); the workflow enforces disjoint role lists and two distinct approvals by design, so a
> single account cannot satisfy it. Activate with the commands below once those accounts exist.
> Tracked in the Epic 8 retrospective (`_bmad-output/implementation-artifacts/epic-8-retro-2026-06-12.md`, L3).

1. **Configure distinct role accounts.**
   - Replace the `@rearleg` placeholders in `.github/CODEOWNERS` with the real reviewer accounts.
   - In Settings → Secrets and variables → Actions → Variables, set
     `BRAND_VOICE_PM_REVIEWERS` and `BRAND_VOICE_DESIGNER_REVIEWERS` to disjoint,
     comma-separated GitHub logins. The workflow rejects overlapping role lists.

   **Activation commands** (run once distinct PM + designer accounts exist; steps 2–3 need repo-admin):

   ```bash
   # 1. Disjoint role reviewer lists (replace the logins with the real accounts):
   gh variable set BRAND_VOICE_PM_REVIEWERS --body "pm-login"
   gh variable set BRAND_VOICE_DESIGNER_REVIEWERS --body "designer-login"

   # 2. Require the gate check on `main` (branch ruleset) — repo-admin, GitHub UI:
   #    Settings → Rules → Rulesets → New branch ruleset → target `main`,
   #    require status check: "Brand-voice release gate / PM + designer brand-voice approval".

   # 3. Restrict `v*` tags (tag ruleset) — repo-admin, GitHub UI:
   #    Settings → Rules → Rulesets → New tag ruleset → target `v*`.
   ```
2. **Protect release PR merges to `main`.**
   - GitHub UI: Settings → Rules → Rulesets → New branch ruleset; target `main`.
   - Require a pull request, dismiss stale approvals, block force pushes/deletions, and require the
     status check `Brand-voice release gate / PM + designer brand-voice approval`.
   - Release PRs use `release/v<x.y.z>` as the **head** and `main` as the **base**. Protection applies
     to the base branch, which is why the required check belongs on `main`, not `release/*`.
3. **Protect release tags separately.**
   - Settings → Rules → Rulesets → New tag ruleset; target `v*`.
   - Restrict tag creation/update/deletion to the release-maintainer bypass list. Create the tag only
     from the merge commit that passed the release PR check.
   - Branch protection does not govern tags; do not treat a branch rule as tag protection.
   - **Until both rulesets are active, the repository does not have an enforced release gate.**
4. **Per-release ritual.** For each release:
   1. branch `release/v<x.y.z>` from `main`;
   2. copy `docs/releases/brand-voice-review-TEMPLATE.md` → `docs/releases/brand-voice-review-v<x.y.z>.md`;
   3. run the [automated pre-pass](#2-automated-pre-pass-run-first-attach-output) and attach output;
   4. fill the five checklists + the decision log;
   5. open a PR from that branch to `main`; obtain one configured PM approval and one configured
      designer approval; wait for the required workflow to pass;
   6. merge, then create the protected `v<x.y.z>` tag from that merge commit.

Configuring branch protection is a human action — no committed file can enable it. The `gh variable
set` lines above are provided as a convenience for the reviewer-list step, but enabling the branch
and tag rulesets still requires repo-admin in the GitHub UI (like Story 8.3's screenshot upload and
Story 1.4's prod migration). Until that is done, the gate is documented but **not enforced**.

---

## Reference — brand-voice lexicon and rules

This section is **documentation**: it names the terms for the human reviewer. The canonical machine
list lives in `tools/brand-voice-lint.ts` (`__testing.AVOID_LEXICON`); this doc does not redefine the
lint. (`docs/**` is **not** in any lint's scan roots — `brand-voice-lint` scans `FE/src` / `FE/app` /
BE `messages*.properties`, and `aso-copy-lint` scans only `docs/aso-copy.md`'s copy regions — so naming
the AVOID terms here does not self-trigger a scan. If a future scan over `docs/**` is ever added, it must
exclude this doc the way `aso-copy-lint` scopes to copy-region markers.)

Sources of truth: architecture.md §5.5 (architecture.md:538–543), prd.md FR-8.8.2/.3/.4/.5 (prd.md:442–448).

- **USE lexicon:** 함께, 선물, 응원, 컴백, 회생, 그룹, 동료, 우리, 살리다.
- **AVOID lexicon (8):** 벌금, 잃었다, 떨어졌다, 실패, 자책, 부담, 패배, 죄책감.
- **Tone rule:** push and system copy is **invitation, not demand** (FR-8.8.3).
- **Elimination / error copy:** use **"컴백 가능"** language, never **"탈락"** / **"실패"** (FR-8.8.5).
- **Store copy:** EN **"comeback pass"**, KR **"회생권"**, never "revival ticket" / "second chance pass"
  (FR-8.8.4 → defer to `docs/aso-copy.md`).

### Related artifacts

- `docs/aso-copy.md` — storefront copy lock + its Governance section (the store-metadata item, §3.4).
- `tools/brand-voice-lint.ts`, `tools/aso-copy-lint.ts`, `tools/contrast-check.ts`, `scripts/test.sh`
  (tools block) — the automated pre-pass (§2).
- `.github/CODEOWNERS` — review requests for the release artifacts (§4).
- `.github/workflows/brand-voice-release-gate.yml` — checklist and role-specific approval enforcement.
- `docs/releases/brand-voice-review-TEMPLATE.md` — the per-release instance template.
