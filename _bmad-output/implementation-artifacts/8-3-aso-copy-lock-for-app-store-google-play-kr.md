# Story 8.3: ASO copy lock for App Store + Google Play KR

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As the PM,
I want a finalized, version-controlled App Store + Google Play KR storefront copy where the **Korean** name + description uses "회생권" naturally and the **English** name + description uses "comeback pass" (never "revival ticket" / "second chance pass"), plus a warn-only `tools/aso-copy-lint.ts` that keeps that copy honest on every future edit,
so that automated store-policy content scans never surface gambling adjacency, the app ships in a standard (non-gambling, non-Games) category on first submission, and any future ASO copy change runs through the same brand-voice + ASO rule check.

**Strategic context (read once before opening any file):**

- **This is a docs + tooling story — zero app code.** No FE source, no BE source, no migration, no new endpoint, no STOMP topic, no `tokens.json` edit, no native surface, no new dependency. The deliverable is (1) a locked Markdown copy file and (2) a small warn-only lint sibling. This mirrors the Epic 8 pattern: Story 8.5 shipped `docs/analytics.md` + `tools/analytics-taxonomy-lint.ts`; Story 8.2 shipped a lint + a copy pass. Story 8.3 is the **store-facing** copy lock.
- **The copy is the primary deliverable; the lint enforces AC3 ("future updates go through the same check").** `docs/aso-copy.md` holds the byte-locked KR + EN storefront strings, the ASO rule statement, the category/rating lock, and the screenshot manifest. `tools/aso-copy-lint.ts` reads that doc and warns on (a) banned EN phrases in the EN copy, (b) AVOID-lexicon terms in the KR copy, (c) a missing required term ("회생권" / "comeback pass"). Warn-only — human release review (Story 8.4) stays authoritative (Architecture §4.15).
- **The ASO rule is already locked across planning.** Canonical rationale: product-brief-yeolsal.md:168 — *"prefer 'comeback pass' over 'revival ticket' / 'second chance pass' in English store metadata. 'Revival ticket' surfaces gambling adjacency in automated content scans even though no money changes hands; 'comeback pass' carries the same meaning without that signal."* Reaffirmed at PRD FR-8.8.4 (prd.md:446), NFR-9.7.3 (prd.md:505), Architecture §5.5 (architecture.md:543), epics.md:1035–1056. Do NOT re-derive or re-debate it — implement it.
- **Mirror `tools/analytics-taxonomy-lint.ts` exactly for the new tool.** It is the canonical warn-only sibling: a `docs/*.md` reader (`loadCatalogue` reads a fenced region from `docs/analytics.md`), per-region scan, formatted `[WARN]` report, `main()` returning 0 except a broken-setup exit-1, an `invokedDirectly` guard, and a `__testing` export. Your `aso-copy-lint.ts` is the same shape pointed at `docs/aso-copy.md`. Do NOT invent a new architecture.
- **Reuse, do not duplicate, the AVOID lexicon.** `tools/brand-voice-lint.ts` exports the 8-term `AVOID_LEXICON` via its `__testing` object. Import it (`import { __testing } from "./brand-voice-lint"`). `brand-voice-lint.ts` stays **byte-identical** — importing it does not execute `main()` (the `invokedDirectly` guard is false on import; this is the same safe-import idiom `analytics-taxonomy-lint.ts` relies on). This literal reuse is what makes AC3's "the **same** brand-voice + ASO rule check" true rather than aspirational.
- **Severity is WARN, locked by Architecture §4.15.** The whole tools chain (brand-voice-lint Rule 2, analytics-taxonomy-lint) is warn-only; the hard release gate is Story 8.4's CODEOWNERS-enforced human sign-off. Do NOT make `aso-copy-lint` a hard gate. The ONLY exit-1 condition is broken setup (doc missing or copy-region markers missing) — identical to `analytics-taxonomy-lint`'s "doc/fenced block missing" exit-1.
- **The authored copy is provisional pending Story 8.4.** The byte-locked KR/EN strings in AC1 are the implementation target and are AVOID-lexicon-clean + ASO-clean by construction. Story 8.4 (release-gate review) holds final wording authority; a PM/designer revision there is expected, not an 8.3 defect. Implement them exactly as written.
- **Screenshots are a manifest + a post-merge user action, not a binary deliverable.** Real PNG capture/upload from the Oxblood Editorial build happens in App Store Connect / Play Console (manual). This story locks the required-shot list + captions + the §4.16 token mandate in the doc. No images are committed.

## Acceptance Criteria

### AC0 — Existing infrastructure inventory (NO REWORK, READ ONLY)

**Read these before writing a line of code. Each MUST stay byte-identical unless it appears in the AC10 MODIFIED list.**

- `tools/analytics-taxonomy-lint.ts` — **the mirror template.** Copy its structure: module-level `REPO_ROOT` + doc-path const; `loadCatalogue(path)` reads `docs/analytics.md` and throws on missing-doc / missing-fenced-block (the only exit-1 paths); `locate(content, index)` for `line:column`; `lintFiles`/`lintContent` split; `formatWarning(w)` → `[WARN] file:line:col — …`; `main(argv)` returns `0` warn-only; `invokedDirectly` IIFE guard; `export const __testing = {...}`. Your tool is the same shape aimed at `docs/aso-copy.md`. Do NOT refactor this file.
- `tools/brand-voice-lint.ts` — **the AVOID-lexicon source.** Its `__testing` export (lines 585–592) exposes `AVOID_LEXICON` (the 8 terms: `벌금`, `잃었다`, `떨어졌다`, `실패`, `자책`, `부담`, `패배`, `죄책감`). Import via `import { __testing } from "./brand-voice-lint"` and read `__testing.AVOID_LEXICON`. **Do NOT modify `brand-voice-lint.ts`** (it just shipped in Story 8.2 PR #104). Do NOT re-declare the 8 terms locally — that would drift. The `invokedDirectly` guard (lines 630–643) means importing it has no side effects.
- `docs/analytics.md` — **the fenced-region precedent.** Story 8.5 put a machine-parseable ```analytics-events fenced block in a human-readable Markdown doc; `analytics-taxonomy-lint` extracts it with a regex. Your `docs/aso-copy.md` uses the same idea but with HTML-comment region markers (`<!-- aso:copy:kr:start -->` … `<!-- aso:copy:kr:end -->`) so the lint scans ONLY the storefront copy, not the surrounding documentation (which legitimately *names* the banned phrases and would self-trigger otherwise).
- `scripts/test.sh:19–27` — the CI tools block: runs `tsx tools/brand-voice-lint.ts`, then `contrast-check.ts`, then `analytics-taxonomy-lint.ts`, then `tsx --test __tests__/brand-voice-lint.test.ts __tests__/contrast-check.test.ts __tests__/analytics-taxonomy-lint.test.ts`. You append the `aso-copy-lint.ts` invocation and add `__tests__/aso-copy-lint.test.ts` to the explicit `--test` list. Silent-skip-when-uninstalled guard already wraps the block; preserve it.
- `tools/package.json` — npm workspace `yeolsal-tools`; `tsx ^4.19.0`, `typescript ~5.9.0`, `@types/node ^20`. Scripts include `lint:analytics-taxonomy: "tsx analytics-taxonomy-lint.ts"` and `test: "tsx --test __tests__/**/*.test.ts"` (glob — auto-picks-up your new test). `tsconfig.json` is `strict` + `noUncheckedIndexedAccess` + ES2022/Bundler. Honor strict: guard every `match[1]` / `arr[i]` access.
- `tools/__tests__/brand-voice-lint.test.ts` — the **node:test + node:assert/strict** pattern (NOT jest): `import { test } from "node:test"`, `import assert from "node:assert/strict"`, drives the tool's exported functions with synthetic strings. Mirror this for `aso-copy-lint.test.ts`.
- `_bmad-output/implementation-artifacts/8-2-brand-voice-copy-pass-lint-helper.md` (Gate 5 deviation) + `8-5-analytics-sdk-selection-event-taxonomy.md` — Epic 8 sibling stories: the pre-existing `TS5097` tools-tsc baseline (3 errors, one per existing `__tests__/*.test.ts` that imports a tool with a `.ts` extension) and the doc+tool shape precedent.
- The canonical ASO sources (quoted in AC7, do not contradict): `product-brief-yeolsal.md:168`, `prd.md:446` (FR-8.8.4), `prd.md:505` (NFR-9.7.3), `prd.md:104` (app-store-policy KPI), `prd.md:280` + `prd.md:293` (gambling/payment bans), `architecture.md:538–543` (§5.5), `architecture.md` §4.16 (token codegen / "yeolsal v2 — Oxblood Editorial", ~440–485).

### AC1 — `docs/aso-copy.md` (NEW): byte-locked bilingual storefront copy

**File:** `docs/aso-copy.md` — NEW.

**Given** I prepare the storefront metadata,
**When** I review against the ASO copy rules (PRD FR-8.8.4, Architecture §5.5),
**Then** the **Korean** name + description uses "회생권" naturally **and** the **English** name + description uses "comeback pass" — never "revival ticket" / "second chance pass" — and zero AVOID-lexicon terms appear in the KR copy.

The doc contains a per-field table for **both** App Store Connect and Google Play Console, with the storefront copy values enclosed in machine-parseable region markers (AC3 consumes these). Char limits are noted per field (App Store Connect / Play Console hard caps); PM must re-verify exact counts in the consoles, but the locked strings below already fit.

**KR copy (inside `<!-- aso:copy:kr:start -->` … `<!-- aso:copy:kr:end -->`):**

| Field | Limit | LOCKED value |
|---|---|---|
| App name / Title | 30 | `열살방: 함께 살아남는 그룹 습관` |
| Subtitle (iOS) / Short description (Android) | 30 / 80 | `친구와 매일 약속을 지키고, 빠진 친구는 회생권으로 다시 살리는 그룹 습관 앱.` |
| Promotional text (iOS) | 170 | `혼자 지키기 어려운 약속도 친구와 함께라면 끝까지 갈 수 있어요. 빠진 친구는 회생권으로 다시 부르고, 우리 방의 포인트는 함께 쌓여요.` |
| Keywords (iOS) | 100 | `습관,그룹습관,친구,루틴,약속,동기부여,함께,회생권,컴백,동료` |
| Description (full) | 4000 | (the KR block below) |

KR full description (LOCKED):
```
열살방은 친구와 함께 살아남는 그룹 습관 방입니다.

매일의 약속을 지키면 살아남고, 하루를 빠진 친구는 다른 친구가 회생권으로 다시 부를 수 있어요. 혼자였다면 멈췄을 순간에도, 우리는 서로를 살리며 끝까지 함께 갑니다.

• 그룹장이 방을 만들고 규칙과 정원을 정해요
• 매일 06시(KST)를 기준으로 그날의 약속을 확인해요
• 가입하면 회생권 한 장을 바로 드려요 — 언제든 컴백할 수 있어요
• 친구를 살리는 건 선물이지 의무가 아니에요
• 회생할 때마다 우리 방의 포인트가 함께 쌓여, 다음 시즌의 즐거움으로 이어져요

v1에서는 어떤 결제도 없습니다 — 살아남는 것 자체가 우리의 자산이에요.

함께라면, 끝까지 갈 수 있어요.
```

**EN copy (inside `<!-- aso:copy:en:start -->` … `<!-- aso:copy:en:end -->`):**

| Field | Limit | LOCKED value |
|---|---|---|
| App name / Title | 30 | `Yeolsal: Survive Together` |
| Subtitle (iOS) / Short description (Android) | 30 / 80 | `Keep daily promises with friends. Miss a day? A comeback pass brings you back.` |
| Promotional text (iOS) | 170 | `Promises are hard to keep alone. With friends, you go all the way. Bring someone back with a comeback pass, and watch your room's shared pool grow together.` |
| Keywords (iOS) | 100 | `habit,group habit,friends,routine,accountability,together,comeback,daily,streak,wellness` |
| Description (full) | 4000 | (the EN block below) |

EN full description (LOCKED):
```
Yeolsal is a room where you survive daily habits together with friends.

Keep your daily promise and you stay in. Miss a day, and a friend can bring you back with a comeback pass. In the moments you'd quit alone, the room carries you — and you carry the room — all the way to the end.

• A group leader creates the room and sets the rule and the member cap
• Your daily promise is checked every day at 06:00 KST
• Every account gets one free comeback pass at sign-up — use it whenever you want
• Reviving a friend is a gift, never an obligation
• Every comeback adds points to your room's shared pool, growing toward a treat the whole room shares later

There is no payment of any kind in v1 — surviving together is the reward itself.

Together, you go all the way.
```

- **Region markers are mandatory and exact.** The four markers `<!-- aso:copy:kr:start -->`, `<!-- aso:copy:kr:end -->`, `<!-- aso:copy:en:start -->`, `<!-- aso:copy:en:end -->` delimit the scanned copy. Every locked value above (KR table + KR description; EN table + EN description) sits **inside** the matching region. The ASO-rule documentation (AC6) and the banned-phrase reference (AC7) sit **outside** all markers so the lint does not self-trigger on the words "revival ticket" / "second chance pass" where they are *documented as banned*.
- KR copy: verified zero of the 8 AVOID terms; uses 회생권 / 컴백 / 회생 / 함께 / 선물 / 우리 / 살리. EN copy: zero "revival ticket" / "second chance pass"; "comeback pass" appears in both subtitle and description.

### AC2 — Category + rating lock (standard, non-gambling) documented in the doc

**File:** `docs/aso-copy.md` — section (outside copy-region markers).

**Given** the metadata is submitted,
**When** the App Store and Google Play KR storefront review runs,
**Then** the app ships in a **standard category** with no gambling / NC-17 escalation (PRD KPI prd.md:104 — app-store policy review passes on first submission).

Lock the following in the doc:
- **App Store primary category:** Health & Fitness. **Secondary:** Social Networking. (Recommended; PM may choose any standard category. **Hard constraint:** NOT Games / NOT Casino / NOT any gambling-adjacent category.)
- **Google Play category:** Health & Fitness. (Same hard constraint.)
- **Age rating:** App Store **4+** / Google Play **Everyone**. No "simulated gambling," no "contests," no "unrestricted web" declarations. The app has **no payment surface** (prd.md:293) and **no random/variable pricing** (prd.md:280) — the rating questionnaire must answer "No" to every gambling/contest/purchase prompt.
- A one-line rationale linking the category/rating choice back to the "comeback pass" copy decision (avoiding the gambling signal end-to-end: copy + category + rating must be consistent, or a clean copy still trips a Games-category review heuristic).

### AC3 — `tools/aso-copy-lint.ts` (NEW): warn-only ASO rule check (mirror of analytics-taxonomy-lint)

**File:** `tools/aso-copy-lint.ts` — NEW.

**Given** any future copy update,
**When** it touches the ASO surfaces in `docs/aso-copy.md`,
**Then** `tsx tools/aso-copy-lint.ts` runs the same brand-voice + ASO rule check and **warns** (never fails) on any violation.

Behavior (mirror `analytics-taxonomy-lint.ts` shape):
- `loadCopyRegions(path = docs/aso-copy.md)`: read the doc (UTF-8). Extract the substring between `<!-- aso:copy:kr:start -->` and `<!-- aso:copy:kr:end -->`, and between the `en` markers. **Throw (caught by `main` → exit 1)** if the doc is unreadable or either marker pair is absent — this is the ONLY exit-1 path (broken setup), exactly like `loadCatalogue`'s missing-doc / missing-fence throw.
- **EN region scan (Rule: ASO banned phrases).** For each phrase in `BANNED_EN = ["revival ticket", "second chance pass"]`, case-insensitive substring search → one `[WARN]` per hit with the real `line:column` (use the same `locate(content, index)` helper, indexing into the full doc so line numbers are file-true). Message names the phrase and the "comeback pass" replacement.
- **EN region presence (Rule: required term).** If the EN region does NOT contain `comeback pass` (case-insensitive) → one `[WARN]` ("EN storefront copy must use 'comeback pass'").
- **KR region scan (Rule: AVOID lexicon — reused).** For each term in `__testing.AVOID_LEXICON` imported from `./brand-voice-lint`, substring search over the KR region → one `[WARN]` per hit (file-true `line:column`). This is the literal "same brand-voice check."
- **KR region presence (Rule: required term).** If the KR region does NOT contain `회생권` → one `[WARN]` ("KR storefront copy must use '회생권'").
- `main(argv)` returns `0` whenever the doc + markers load (warnings do not change exit code); returns `1` only on the broken-setup throw. Print each warning via `formatWarning`, then a summary line `[aso-copy-lint] scanned docs/aso-copy.md (KR + EN regions): N warning(s).`.
- `export const __testing = { ASO_DOC_PATH, BANNED_EN, loadCopyRegions, REQUIRED_EN, REQUIRED_KR }` (plus whatever internal helpers the tests need). Provide `lintRegions(kr, en, full, avoidLexicon)` (or equivalent) as an exported pure function the tests drive with synthetic strings — do NOT make the tests depend on disk for the violation cases.
- `invokedDirectly` IIFE guard at the bottom (copy from `analytics-taxonomy-lint.ts:313–326`) so importing the module in tests has no side effect.
- Strict-mode clean: guard every regex/array index access (`noUncheckedIndexedAccess`).

### AC4 — Wire the new tool into CI + npm scripts

**Files:** `scripts/test.sh` — MODIFY; `tools/package.json` — MODIFY.

- `scripts/test.sh`: inside the existing `if [ -x "$ROOT_DIR/tools/node_modules/.bin/tsx" ]` block, add `(cd "$ROOT_DIR" && tools/node_modules/.bin/tsx tools/aso-copy-lint.ts)` after the `analytics-taxonomy-lint.ts` line, and add `__tests__/aso-copy-lint.test.ts` to the explicit `tsx --test` file list. Keep the silent-skip-when-uninstalled guard intact. A short comment `# Story 8.3 AC4 — warn-only ASO copy lint.` is allowed (do NOT reference task/PR numbers per project-context comment rule).
- `tools/package.json`: add `"lint:aso-copy": "tsx aso-copy-lint.ts"` to `scripts` (mirror `lint:analytics-taxonomy`). The `test` glob already covers the new test file — do NOT change it. **Do NOT add any dependency** (`devDependencies` stays byte-identical).

### AC5 — Screenshot manifest section (Oxblood Editorial §4.16)

**File:** `docs/aso-copy.md` — section (outside copy-region markers).

**Given** the storefront copy,
**When** the accompanying screenshots are produced,
**Then** they use yeolsal v2 design tokens (Oxblood Editorial — Architecture §4.16), captured from a v2 build.

Lock in the doc:
- A required-shot list (KR primary captions + EN captions), e.g. 1) onboarding concept, 2) Today survival roster, 3) Wallet (free comeback pass visible — caption must read "comeback pass" in EN, "회생권" in KR), 4) Friend-gift / revival moment, 5) Final-3 monthly ceremony / room pool. Caption copy follows the same AVOID/ASO rules (KR no AVOID terms; EN no banned phrases).
- A mandate line: **screenshots must be captured from the Oxblood Editorial (yeolsal v2) build — `FE/src/theme/tokens.json` palette, §4.16 token codegen** — not the deprecated v1 Risograph/Neobrutalist palette (Sprint Change Proposal 2026-05-10, architecture.md:148).
- A **Post-merge user action** note: actual PNG capture (iPhone 6.7"/6.5"/5.5" + iPad if applicable; Android phone/tablet) + upload to App Store Connect / Play Console is manual and out of repo scope. No binary assets are committed.

### AC6 — ASO governance rule statement (realizes epics AC3 "future updates go through the check")

**File:** `docs/aso-copy.md` — section (outside copy-region markers).

State the rule that future ASO edits check against, so the doc is self-describing and the lint is discoverable:
- KR storefront copy uses "회생권"; never the 8 AVOID-lexicon terms (link Architecture §5.5).
- EN storefront copy uses "comeback pass"; never "revival ticket" / "second chance pass" (link FR-8.8.4).
- Standard category, 4+/Everyone, no gambling/contest/payment declarations (AC2).
- **Enforcement:** `tools/aso-copy-lint.ts` runs in `scripts/test.sh` (warn-only); the hard gate is Story 8.4's release-gate review (joint PM + designer sign-off, CODEOWNERS-enforced — epics.md:1058–1083). "All store metadata (KR + EN)" is already item 4 of the 8.4 checklist; this doc is the artifact that item reviews.

### AC7 — Phrase / lexicon reference (LOCKED — do not change)

**Sources of truth:** product-brief-yeolsal.md:168, PRD FR-8.8.4 (prd.md:446) + NFR-9.7.3 (prd.md:505), Architecture §5.5 (architecture.md:543).

- **Banned EN phrases (lint, case-insensitive):** `revival ticket`, `second chance pass`.
- **Required EN phrase:** `comeback pass`.
- **Required KR term:** `회생권`.
- **KR AVOID lexicon (8, reused from `brand-voice-lint` `__testing.AVOID_LEXICON`):** `벌금`, `잃었다`, `떨어졌다`, `실패`, `자책`, `부담`, `패배`, `죄책감`. Do NOT re-declare or extend in `aso-copy-lint.ts` — import them. `탈락`/`도전`/`챌린지`/`챌린저스` are strategic-context terms, NOT canonical lexicon entries — do not add them.

### AC8 — Out-of-scope fence

Explicitly NOT part of Story 8.3:

1. **Any FE/BE app code, migration, endpoint, STOMP topic, RealtimeEvent variant, NotificationKind.** Zero source under `FE/src`, `FE/app`, `BE/`.
2. **`FE/app.json` / `FE/app.config.ts` on-device app `name` change.** The store-listing name is recorded in the doc; wiring it into the binary display name (and the native rebuild that implies) is a separate, unbudgeted change. The current `"Yeosal"` placeholder stays untouched here.
3. **Committing screenshot binaries / icon / splash assets.** AC5 ships a manifest + post-merge action only.
4. **Modifying `brand-voice-lint.ts`, `analytics-taxonomy-lint.ts`, `contrast-check.ts`, or `tokens.json`.** `aso-copy-lint.ts` imports the AVOID lexicon read-only; siblings stay byte-identical.
5. **Promoting any ASO check to a hard CI gate.** Warn-only per Architecture §4.15; Story 8.4 is the human hard gate.
6. **Story 8.4 deliverables** (`docs/brand-voice-review.md`, the release-gate checklist, CODEOWNERS / branch-protection wiring). AC6 only *references* the 8.4 gate.
7. **New dependencies.** The lint is hand-written with `node:fs` + the existing `tsx` runtime only.
8. **A new `.github/workflows/**` file.** `scripts/test.sh` is the wiring point (matches how `analytics-taxonomy-lint` was wired in Story 8.5).
9. **`docs/index.md` entry for the new doc** — optional hygiene, not required; leave out to keep the scope fence tight (a follow-up may add it).
10. **i18n / non-KR storefronts.** v1 is KR storefront only (architecture.md:43); EN copy is for store algorithms only (NFR-9.7.3), not a separate locale storefront.

### AC9 — Tool test matrix (NET-NEW, `node:test` + `node:assert/strict`)

**File:** `tools/__tests__/aso-copy-lint.test.ts` — NEW. Runner: `node:test` + `node:assert/strict` (NOT jest). Minimum cases:

| # | Case | Asserts |
|---|---|---|
| 1 | Live doc: `loadCopyRegions(ASO_DOC_PATH)` + lint the real `docs/aso-copy.md` | 0 warnings (the locked copy is clean) |
| 2 | Synthetic EN region containing `revival ticket` | exactly 1 banned-phrase WARN at the correct `line:column` |
| 3 | Synthetic EN region containing `Second Chance Pass` | 1 banned-phrase WARN (case-insensitive match) |
| 4 | Synthetic EN region with no `comeback pass` | 1 missing-required-term WARN |
| 5 | Synthetic KR region containing `실패` | 1 AVOID-lexicon WARN (proves the imported `brand-voice-lint` lexicon is wired) |
| 6 | Synthetic KR region with no `회생권` | 1 missing-required-term WARN |
| 7 | Doc string missing the `en` markers | `loadCopyRegions` throws (broken-setup → `main` exit 1) |
| 8 | Lexicon parity | the lexicon the lint scans with equals `brandVoice.__testing.AVOID_LEXICON` (8 terms, same order) — proves DRY reuse, no drift |

**TDD order:** RED → GREEN per case. Run with `cd tools && ./node_modules/.bin/tsx --test __tests__/aso-copy-lint.test.ts`. Import the tool **without** a `.ts` extension (`from "../aso-copy-lint"`) so the new file does not add to the pre-existing `TS5097` baseline (Story 8.2 Gate 5). FE tests: 0. BE tests: 0.

### AC10 — File / scope fence (LOCKED ALLOW LIST)

**Story 8.3 creates/modifies exactly these files. The reviewer's diff-sanity gate (AC11 gate 7) MUST find no others.**

**NEW (3):**
```
docs/aso-copy.md                              (AC1 copy + AC2 category + AC5 screenshots + AC6 rule + AC7 reference)
tools/aso-copy-lint.ts                        (AC3 warn-only lint)
tools/__tests__/aso-copy-lint.test.ts         (AC9 node:test suite)
```

**MODIFIED (2 wiring):**
```
scripts/test.sh                               (AC4 — add aso-copy-lint invocation + test file)
tools/package.json                            (AC4 — add lint:aso-copy script; deps byte-identical)
```

**MODIFIED — process (2):**
```
_bmad-output/implementation-artifacts/sprint-status.yaml                                  (status flip + dated comment)
_bmad-output/implementation-artifacts/8-3-aso-copy-lock-for-app-store-google-play-kr.md   (Tasks/File List/Completion Notes/Status)
```

**Banned paths (`git diff origin/main --stat` MUST show zero hits):**
- `FE/**` — no FE source, no `app.json`, no `app.config.ts`, no token edits
- `BE/**` — no backend of any kind (BE delta = 0)
- `tools/brand-voice-lint.ts` / `tools/analytics-taxonomy-lint.ts` / `tools/contrast-check.ts` — siblings untouched (import-only)
- `tools/tsconfig.json` — not touched (TS5097 baseline stays; new test imports extensionless)
- `FE/package.json` / `tools/package.json` `devDependencies` — no new deps (only a `scripts` entry)
- `.github/workflows/**` — no CI workflow file
- `docs/**` except the new `docs/aso-copy.md` (no `docs/index.md`, no `docs/analytics.md`, etc.)
- `infra/**`, `RUNBOOK.md`

### AC11 — Pre-merge verify gates (11-GATE MATRIX)

| # | Gate | How | Pass criterion |
|---|---|---|---|
| 1 | ASO lint clean / exit 0 | `cd <repo> && tools/node_modules/.bin/tsx tools/aso-copy-lint.ts; echo $?` | `0 warning(s)` in summary; exit `0` |
| 2 | ASO lint catches a banned phrase | AC9 cases #2–#6 (negative tests) | each green (banned EN + missing-required + AVOID + missing-회생권 all WARN) |
| 3 | Tools unit tests | `cd tools && ./node_modules/.bin/tsx --test __tests__/aso-copy-lint.test.ts __tests__/brand-voice-lint.test.ts __tests__/contrast-check.test.ts __tests__/analytics-taxonomy-lint.test.ts` | all green; aso-copy suite ≥ 8 cases (AC9) |
| 4 | Tools typecheck | `cd tools && npx tsc --noEmit` | 0 new errors; the pre-existing 3 `TS5097` errors (in the other test files) are the accepted Story 8.2 baseline and stay; `aso-copy-lint.ts` + the new test add **0** new TS5097 (extensionless import) |
| 5 | EN banned-phrase grep (belt-and-suspenders) | `grep -niE "revival ticket\|second chance pass" docs/aso-copy.md \| grep -vE "banned\|do not use\|never"` | 0 hits in the copy regions (documentation lines that *name* the banned phrases are excluded) |
| 6 | Required-term presence | `grep -c "comeback pass" docs/aso-copy.md` ≥ 1 AND `grep -c "회생권" docs/aso-copy.md` ≥ 1 | both ≥ 1 |
| 7 | Diff sanity (scope fence) | `git diff origin/main --stat \| grep -vE "docs/aso-copy\.md\|tools/aso-copy-lint\.ts\|tools/__tests__/aso-copy-lint\.test\.ts\|scripts/test\.sh\|tools/package\.json\|_bmad-output/"` | 0 unexpected files (AC10 allow-list only) |
| 8 | brand-voice-lint regression | `tools/node_modules/.bin/tsx tools/brand-voice-lint.ts 2>&1 \| tail -1` | `0 HARD violation(s), 57 warning(s)` (unchanged — `docs/` is not in brand-voice-lint roots; `brand-voice-lint.ts` byte-identical) |
| 9 | analytics-taxonomy-lint regression | `tools/node_modules/.bin/tsx tools/analytics-taxonomy-lint.ts; echo $?` | exit `0`, unchanged warning count |
| 10 | FE + BE delta = 0 | `git diff origin/main --stat -- FE/ BE/ \| wc -l` | `0` |
| 11 | scripts/test.sh tools block runs end-to-end | `bash scripts/test.sh` (FE/BE may skip per environment) | reaches + passes the tools block incl. the new `aso-copy-lint.ts` invocation + test; no new failure |

**No manual EAS gate.** Story 8.3 ships a Markdown doc + a Node lint tool + 2 wiring lines — no new screen, navigation, or native surface. Screenshot capture/upload is a documented **Post-merge user action** (AC5), not a CI gate. (No Story 8.1-style device smoke, no Docker-bound IT, so the "merge on deferral" norm is not invoked.)

## Tasks / Subtasks

- [x] **Task 1 — Read-only inventory** (AC0)
  - [x] Read `tools/analytics-taxonomy-lint.ts` in full (the mirror) + `tools/brand-voice-lint.ts` `__testing` export (the AVOID lexicon) + `tools/__tests__/brand-voice-lint.test.ts` (node:test pattern) + `scripts/test.sh:19–27` + `tools/package.json` + `docs/analytics.md` (fenced-region precedent).
  - [x] Confirm the canonical ASO sources (product-brief:168, FR-8.8.4, §5.5, §4.16) say exactly what AC7 quotes; do not re-derive.
- [x] **Task 2 — Author `docs/aso-copy.md`** (AC1, AC2, AC5, AC6, AC7)
  - [x] KR + EN per-field tables + full descriptions, byte-locked per AC1, inside the four region markers.
  - [x] Category/rating lock (AC2), screenshot manifest (AC5), governance rule statement (AC6), phrase/lexicon reference (AC7) — all **outside** the copy-region markers.
  - [x] Self-check: 0 AVOID terms in KR region; 0 banned EN phrases in EN region; "회생권" + "comeback pass" present.
- [x] **Task 3 — `tools/aso-copy-lint.ts`** (AC3) — RED→GREEN
  - [x] Write `aso-copy-lint.test.ts` cases #2–#8 first (RED).
  - [x] Implement `loadCopyRegions` (marker extraction + broken-setup throw), `lintRegions` (banned EN + required EN + AVOID KR + required KR), `formatWarning`, `main` (warn-only, exit-1 only on throw), `invokedDirectly` guard, `__testing` export. Import `AVOID_LEXICON` from `./brand-voice-lint`. → GREEN.
  - [x] Case #1 (live doc) GREEN — proves the authored copy is clean.
- [x] **Task 4 — CI + npm wiring** (AC4) — add the `scripts/test.sh` invocation + `--test` entry; add `lint:aso-copy` to `tools/package.json`.
- [x] **Task 5 — Full verification** (AC11) — run the 11-gate matrix; confirm brand-voice-lint still `0 HARD / 57 warning(s)`, FE/BE delta 0, scope fence clean, tools tsc adds 0 new TS5097.
- [x] **Task 6 — Closeout** — fill File List + Completion Notes; flip sprint-status `8-3` → `review`.

### Review Findings

- [ ] [Review][Decision] Split or rewrite the iOS subtitles — The shared KR subtitle is 44 characters and the shared EN subtitle is 78 characters, but App Store subtitles are limited to 30. This contradicts the document's claim that all locked strings fit and makes the metadata impossible to submit as written. Choose whether to preserve the current strings as Android short descriptions and add separate iOS subtitles, or replace each shared value with one cross-platform string of at most 30 characters. [docs/aso-copy.md:37,72]
- [ ] [Review][Patch] Reject duplicate, overlapping, or out-of-order copy-region markers [tools/aso-copy-lint.ts:65]
- [ ] [Review][Patch] Preserve marker-derived offsets instead of relocating regions with `full.indexOf(region)` [tools/aso-copy-lint.ts:101]
- [ ] [Review][Patch] Report the actual caller-supplied input path in warnings and summaries [tools/aso-copy-lint.ts:178]
- [ ] [Review][Patch] Use the mandated `node:assert/strict` import and remove task-specific narration from new source comments [tools/__tests__/aso-copy-lint.test.ts:1]

## Dev Notes

### Implementation traps (ranked by likelihood of biting)

1. **The lint self-triggering on its own documentation.** `docs/aso-copy.md` *names* "revival ticket" / "second chance pass" in AC6/AC7 sections as the banned phrases. If the lint scanned the whole file it would warn on that documentation. The region markers are the fix: the lint scans ONLY between `aso:copy:*:start/end`; the rule/reference prose lives outside them. Verify case #1 (live doc) returns 0 — if it warns, your markers are mis-placed or the lint is scanning the full file instead of the regions.
2. **Re-declaring the AVOID lexicon instead of importing it.** Hard-coding the 8 terms in `aso-copy-lint.ts` is an instant drift bug and violates AC3's "the **same** check." Import `__testing.AVOID_LEXICON` from `./brand-voice-lint`. Case #8 (parity) guards this.
3. **Modifying `brand-voice-lint.ts` to "expose" the lexicon.** It already exposes it via `__testing`. Do not add a new export (that file is out of the AC10 allow-list and just shipped in PR #104). Import the `__testing` object as-is.
4. **Importing `brand-voice-lint.ts` executing its `main()`.** It won't — the `invokedDirectly` guard is false on import (same idiom `analytics-taxonomy-lint.ts` is safe under). Do not "fix" anything here.
5. **TS5097 baseline regression.** The existing 3 tools test files import their tool with a `.ts` extension → 3 `TS5097` errors (accepted baseline, Story 8.2 Gate 5). Import the tool **extensionless** in `aso-copy-lint.test.ts` (`from "../aso-copy-lint"`) so you add 0 new ones. Gate 4 checks this.
6. **Char-limit overrun on store fields.** The locked strings fit (App name ≤30, subtitle ≤30/80, promo ≤170, keywords ≤100). If a Story 8.4 reviewer rewrites a value, they must re-check counts in the consoles. Note this in the doc; do not silently exceed a cap.
7. **EN copy accidentally using "revival" prose.** "Reviving a friend" / "every comeback" is fine ("revival ticket" / "second chance pass" are the banned *noun phrases*, not the verb "revive"). The lint matches the exact two phrases case-insensitively — do not broaden it to ban the word "revival" outright (that would false-positive the legitimate verb, and the BE `revival_events` domain has nothing to do with store copy anyway).
8. **Severity drift.** Keep `main` warn-only (`return 0` on warnings). Only the broken-setup throw exits 1. A hard gate here contradicts Architecture §4.15 and would block CI on a copy nit — Story 8.4's human gate is the hard stop.
9. **`scripts/test.sh` silent-skip guard.** Add your invocation INSIDE the existing `if [ -x .../tsx ]` block, before the `else` that prints the skip message. Adding it outside breaks the uninstalled-tools fast path.
10. **Category vs copy consistency (AC2).** A clean "comeback pass" copy still trips review if the app is filed under Games/Casino. The doc must lock a standard category + 4+/Everyone rating + "No" to every gambling/payment questionnaire prompt. This is the other half of "passes on first submission."

### Current state → what changes (per AC0 read-of-modified-files mandate)

- **`scripts/test.sh`** today: runs `brand-voice-lint` → `contrast-check` → `analytics-taxonomy-lint` → `tsx --test` over 3 named test files, all inside the `[ -x tools/.../tsx ]` guard. After: one more invocation (`aso-copy-lint.ts`) + a 4th `--test` file. **Must preserve:** the silent-skip guard, the BE/FE blocks below it, ordering.
- **`tools/package.json`** today: `lint:brand-voice`, `lint:analytics-taxonomy`, `check:contrast`, `test` (glob), `typecheck`. After: one more script `lint:aso-copy`. **Must preserve:** `devDependencies` byte-identical (no new dep), the `test` glob (auto-discovers the new test).
- **New files** have no "current state" — but `docs/aso-copy.md`'s region markers are load-bearing for `aso-copy-lint.ts`; the two are co-designed.

### Why this story matters (KPI signal)

PRD prd.md:104 lists "App-store policy review — first submission pass, no rating escalation" as an **existential gate**. The single biggest avoidable failure is an automated content scan flagging "revival ticket" as gambling-adjacent and bumping the app into a restricted category or a re-review loop — delaying launch for a payment-free app that has zero gambling mechanics. Locking "comeback pass" (EN) + "회생권" (KR) + a standard category, and making `aso-copy-lint` warn on any regression, removes that failure mode and gives Story 8.4's human release gate a clean automated baseline to sign off against.

### Project context reference

- `project-context.md` — `apiRequest<T>`, SecureStore namespace, route registration, immutable updates, Flyway conventions: **none apply** — this story touches a Markdown doc + a Node lint tool + 2 wiring lines; zero API calls, zero routes, zero schema.
- Tools module is its own npm workspace (`tools/package.json`, `node:test` runner, tsx 4.19, strict tsconfig with `noUncheckedIndexedAccess`) — run tool tests from `tools/`, not `FE/`. Comments explain *why* only; no task/PR refs in source (project-context.md "Comments & Documentation").
- "Merge on deferral" norm (PRs #90/#93/#95/#98/#99/#100/#102/#103/#104): not invoked — all 11 gates are local + non-manual; no Docker-bound IT, no EAS smoke. The only manual item is the post-merge screenshot capture (AC5), which is a console action, not a deferred gate.

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story 8.3 (lines 1035–1056)] — KR 회생권 / EN comeback pass; standard category; future-update rule check; Oxblood Editorial screenshots.
- [Source: _bmad-output/planning-artifacts/prd.md#FR-8.8.4 (446), NFR-9.7.3 (505), KPI (104), gambling/payment bans (280, 293)] — the ASO copy lock + existential first-submission gate.
- [Source: _bmad-output/planning-artifacts/architecture.md#§5.5 (538–543)] — brand-voice patterns incl. line 543 "English store metadata uses 'comeback pass', not 'revival ticket'."
- [Source: _bmad-output/planning-artifacts/architecture.md#§4.16 (~440–485)] — yeolsal v2 Oxblood Editorial token codegen (screenshot mandate).
- [Source: _bmad-output/planning-artifacts/product-brief-yeolsal.md:168] — canonical "comeback pass over revival ticket / second chance pass" rationale (gambling-adjacency in automated scans).
- [Source: tools/analytics-taxonomy-lint.ts] — the warn-only sibling shape `aso-copy-lint.ts` mirrors.
- [Source: tools/brand-voice-lint.ts (`__testing.AVOID_LEXICON`, lines 585–592)] — the 8-term lexicon reused by the ASO lint.
- [Source: scripts/test.sh:19–27] — tools-block CI wiring point (AC4).
- [Source: docs/analytics.md] — Markdown-doc-with-machine-parseable-region precedent (Story 8.5).
- [Source: _bmad-output/implementation-artifacts/8-2-brand-voice-copy-pass-lint-helper.md] — Epic 8 sibling: node:test pattern, TS5097 baseline, warn-only severity, byte-lock conventions.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Opus 4.8, 1M context) — BMad dev-story workflow.

### Debug Log References

- RED→GREEN: `tools/__tests__/aso-copy-lint.test.ts` failed first (module `../aso-copy-lint` not found) before the tool existed; 8/8 cases green after implementing `tools/aso-copy-lint.ts`.
- Gate 5 fix during authoring: the top-of-doc blockquote initially split `"revival ticket"` onto a line without a lowercase `banned`/`never` qualifier — a false Gate-5 hit because the exclusion grep (`grep -vE "banned|do not use|never"`) is case-sensitive. Reflowed so every line that names a banned phrase also carries lowercase `banned`/`never` on the same physical line.

### Completion Notes List

Story 8.3 — **DOCS + TOOLING only; zero FE/BE/migration/dep/native** (AC8). All 11 AC11 gates GREEN. No spec deviations.

- **AC1/AC2/AC5/AC6/AC7 — `docs/aso-copy.md` (NEW):** byte-locked KR + EN per-field tables + full descriptions inside the four `<!-- aso:copy:{kr,en}:{start,end} -->` markers; category/rating lock (App Store + Play = Health & Fitness, 4+/Everyone, no gambling/Games/Casino, all-"No" questionnaire), Oxblood Editorial §4.16 screenshot manifest (capture = post-merge user action), governance rule statement, and the locked phrase/lexicon reference — **all rule/reference prose sits OUTSIDE the markers** so the lint never self-triggers on the documented banned phrases.
- **AC3 — `tools/aso-copy-lint.ts` (NEW):** warn-only mirror of `analytics-taxonomy-lint.ts` — `loadCopyRegions` (marker extraction + broken-setup throw = the only exit-1 path), `lintRegions(kr, en, full, avoidLexicon)` exported pure function (banned EN + required EN + AVOID KR + required KR, file-true `line:column`), `formatWarning`, `main` (warn-only `return 0`), `invokedDirectly` IIFE guard, `__testing` export. `AVOID_LEXICON` is imported **read-only, extensionless** from `./brand-voice-lint` `__testing` — no re-declaration, no drift (AC9 case #8 asserts the same array reference).
- **AC9 — `tools/__tests__/aso-copy-lint.test.ts` (NEW):** 8 `node:test` + `node:assert/strict` cases (live-doc-clean; banned EN ×2 incl. case-insensitive `Second Chance Pass`; missing required EN; AVOID KR `실패`; missing required KR; missing-`en`-marker throw; lexicon parity). Imports the tool **extensionless** → adds 0 new TS5097.
- **AC4 — wiring:** `scripts/test.sh` gains the `aso-copy-lint.ts` invocation + the test file in the `--test` list, both inside the existing silent-skip-when-uninstalled guard; `tools/package.json` gains `"lint:aso-copy"` (devDependencies byte-identical, no new dep).
- **Gate matrix:** (1) lint exit 0 / 0 warnings; (2) negative cases #2–#6 green; (3) 55 tools tests / 0 fail (aso = 8); (4) tools `tsc` = 3 pre-existing TS5097 only (other test files), 0 new, none in aso files; (5) EN banned grep 0 unexcluded; (6) `comeback pass` ×13, `회생권` ×10; (7) scope fence = AC10 allow-list only (3 NEW + 2 MODIFIED + 2 process = 7 paths, zero FE/BE/sibling-tool/tokens.json/CI); (8) brand-voice-lint `0 HARD / 57 warning(s)` (byte-untouched); (9) analytics-taxonomy-lint exit 0; (10) FE/BE delta 0; (11) `bash scripts/test.sh` exit 0 end-to-end (FE 82 suites/572 tests, tools block incl. aso-copy-lint, BE BUILD SUCCESSFUL).
- **No manual EAS gate** (no new screen/nav/native surface). Screenshot capture/upload is a documented **post-merge user action** (AC5). The "merge on deferral" norm is not invoked — all gates are local + non-manual. Authored copy is **provisional pending Story 8.4** human release gate.
- **Untouched (import-only / out of fence):** `brand-voice-lint.ts`, `analytics-taxonomy-lint.ts`, `contrast-check.ts`, `tokens.json`, `tools/tsconfig.json` (the 3 pre-existing TS5097 in the other test files remain the accepted Story 8.2 baseline).

### File List

NEW:
- `docs/aso-copy.md`
- `tools/aso-copy-lint.ts`
- `tools/__tests__/aso-copy-lint.test.ts`

MODIFIED (wiring):
- `scripts/test.sh`
- `tools/package.json`

MODIFIED (process):
- `_bmad-output/implementation-artifacts/sprint-status.yaml`
- `_bmad-output/implementation-artifacts/8-3-aso-copy-lock-for-app-store-google-play-kr.md`
