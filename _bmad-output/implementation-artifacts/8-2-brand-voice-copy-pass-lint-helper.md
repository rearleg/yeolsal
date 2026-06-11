# Story 8.2: Brand-voice copy pass + lint helper

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As an engineer,
I want `tools/brand-voice-lint.ts` to scan only the user-facing strings of the FE/BE codebase against the 8-term AVOID lexicon (skipping test files, code comments, and BE message bundles that don't exist yet) **and** I want the handful of remaining `실패`-framed user-facing strings rewritten into brand voice,
so that brand-voice drift is caught early as a warn-level signal — never a false-positive release blocker — and the v1 codebase has zero AVOID-lexicon hits in real user copy.

**Strategic context (read once before opening any file):**

- **The tool already exists and is wired into CI.** This story is NOT a greenfield build. `tools/brand-voice-lint.ts` has shipped since Story 1.5 with three rules: Rule 1 (HARD gate, NFR-9.6.1 survival-color packed-type), Rule 2 (WARN, AVOID lexicon — the focus of THIS story), Rule 3 (WARN, design-token literal guard — explicitly OUT OF SCOPE here). It is invoked by `scripts/test.sh` as `tsx tools/brand-voice-lint.ts` (no args → scans `FE/src` + `FE/app`).
- **This story closes three tool gaps named verbatim by the epics ACs** (epics.md:1017–1033): (1) ignore `*.test.{ts,tsx}` files; (2) scan `BE/src/main/resources/messages*.properties` **(if present)**; (3) drive AVOID-lexicon matches in user-facing strings to zero "excluding code comments and non-user-facing logs." Gap (3) decomposes into a **tool enhancement** (mask comments before the Rule-2 scan) plus a **copy pass** (rewrite the 7 genuine user-facing `실패` strings).
- **BE has NO `messages*.properties` and NO `MessageSource`/`ResourceBundle`** (verified: `BE/src/main/resources/` holds only `application*.yml`, `tokens.schema.json`, and `db/migration/V*.sql`; zero i18n grep hits in `BE/src/main/java`). BE user-facing strings are inline Java literals. The epics "(if present)" clause therefore resolves to a **future-ready scaffold that is a no-op on the current tree** — wire the conditional scan-root + a unit test proving the `.properties` value-scan path works, then document that it matches zero files today. Do NOT externalize BE strings into a properties file (that is a separate, unbudgeted refactor).
- **Rule 3 (token-literal guard) is OUT OF SCOPE.** Do not change its behavior, its blocklist, or its SVG path. The 57 non-test Rule-3 warnings (chiefly the 40 in the *generated* `FE/src/theme/tokens.ts`) are a pre-existing backlog that survives this story untouched.
- **Severity model is locked by Architecture §4.15.** Rule 2 stays **WARN** (exit code unaffected; human review via Story 8.4 release gate remains authoritative). Do NOT promote AVOID-lexicon to a hard gate. The only HARD gate in this tool is Rule 1, and it is not in scope.
- **Final brand copy is PM/designer-owned.** The byte-locked replacement strings in AC4 are the implementation target and are AVOID-lexicon-clean by construction, but Story 8.4 (release-gate review) holds final authority. Implement them exactly as written; if a reviewer revises wording during Story 8.4, that is expected and not a 8.2 defect.
- **This story is FE-tooling + FE-copy only.** Zero BE source files. Zero migration. Zero new endpoints. Zero STOMP topics. Zero new RealtimeEvent variants. Zero new NotificationKind values. Zero `tokens.json` edits. Zero new dependencies. Zero new CI workflow.

## Acceptance Criteria

### AC0 — Existing infrastructure inventory (NO REWORK, READ ONLY)

**Read these before writing a line of code. Each MUST stay byte-identical unless it appears in the AC10 MODIFIED list.**

- `tools/brand-voice-lint.ts` — the tool being enhanced. Key seams you will touch:
  - `collectFiles(roots)` / `walkDir(dir, entries)` (lines 89–128) — the file-walk. **This is where the `*.test.{ts,tsx}` skip belongs** (AC1) — filter at collection time so the skip applies uniformly to all rules, matching the epics' blanket "ignores `*.test.{ts,tsx}` files."
  - `SCAN_EXT` (line 72) = `{".ts", ".tsx", ".svg"}` and `SKIP_DIR_SEGMENTS` (lines 75–82) — the existing extension + directory filters. The `.properties` scan (AC3) is a SEPARATE code path, not an addition to `SCAN_EXT` (properties parsing differs from TS scanning).
  - `lintFileRule2(file, content)` (lines 203–222) — the AVOID-lexicon scan: a naive `content.indexOf(term)` loop over `AVOID_LEXICON`. **This is where comment-masking belongs** (AC2): scan a comment-masked copy of `content`, not the raw content.
  - `AVOID_LEXICON` (lines 50–59) — the 8 terms: `벌금`, `잃었다`, `떨어졌다`, `실패`, `자책`, `부담`, `패배`, `죄책감`. **Do NOT add, remove, or reorder terms** — PRD §FR-8.8.2 and Architecture §5.5 both lock exactly these 8.
  - `lintFiles(files)` (lines 334–358) and `lintContent(relPath, content, options?)` (lines 360–383) — the two public entry points. `lintFiles` reads from disk; `lintContent` is the in-memory variant the tests drive. **Both must route Rule 2 through the same comment-mask helper** so a `lintContent("x.ts", "// 실패")` call returns zero Rule-2 warnings.
  - `lintFileRule1` (Rule 1, HARD) and `lintFileRule3` / `lintSvgRule3` (Rule 3, WARN) — **DO NOT MODIFY.** Rule 1 and Rule 3 keep scanning raw content. Only Rule 2 gets comment-masking; only `collectFiles` gets the test-file skip.
  - `main(argv)` (lines 404–426) — exit code is `hardViolations.length === 0 ? 0 : 1`. Rule 2 / Rule 3 are warnings and never affect exit code. **Preserve this.**
  - `__testing` export (lines 390–394) — extend (don't replace) if the new tests need to reach internal helpers (e.g. export the comment-mask helper here).
- `tools/__tests__/brand-voice-lint.test.ts` — the test file you extend (AC9). Runner is **`node:test` + `node:assert/strict`** (NOT jest). Assertion style: `assert.equal`, `assert.ok`, `assert.match`. Drives `lintContent` / `lintFiles` / `collectFiles` / `loadHexAllowlist` with synthetic relPaths + inline source. Mirror this exactly for new cases.
- `tools/analytics-taxonomy-lint.ts` — sibling warn-only lint. **It does NOT skip test files or comments** — so it is NOT the pattern for AC1/AC2. It IS the pattern for: warn-only severity, `walkDir` skip-dir conventions, and the structured-report shape. Do not refactor it.
- `tools/package.json` — `tsx ^4.19.0`, `typescript ~5.9.0`, `@types/node ^20`. Test script: `tsx --test __tests__/**/*.test.ts`. Lint script: `tsx brand-voice-lint.ts`. `tools/tsconfig.json` is `strict`, `noUncheckedIndexedAccess: true`, ES2022/Bundler. **Honor strict mode** — every array index access needs a guard.
- `scripts/test.sh:19–27` — the CI invocation: `tsx tools/brand-voice-lint.ts` then `contrast-check.ts` then `analytics-taxonomy-lint.ts` then the `tsx --test` suite. Silent-skip when `tools/node_modules` is absent; a real violation when the tools DO run is a hard failure (Rule 1 only). **Do NOT edit `scripts/test.sh`** — the no-arg invocation already picks up your enhancements.
- `tools/contrast-check.ts` — sibling tool; your verify matrix mirrors its run pattern but you do not touch it.
- The 7 copy-pass source sites (AC4) and the 1 paired test (AC5) — read each in full before editing; they are pure string-literal swaps with identical surrounding control flow.
- `FE/src/components/chat/SpectatorReadOnlyBanner.tsx:8–14`, `FE/src/components/survival/SelfReviveCTA.tsx:1–13`, `FE/src/components/rooms/GraceBanner.tsx:1–9` — each contains a **code comment that legitimately lists the AVOID lexicon** for documentation. These are the 17 comment-hits that AC2's comment-masking eliminates. **DO NOT edit these comments** — they are useful brand-voice documentation; the tool change is what clears them, not a source edit.
- `PRD §FR-8.8.2` (prd.md:442–444), `Architecture §4.15` (architecture.md:400–413), `Architecture §5.5` (architecture.md:538–543) — the lexicon + severity contract. Quoted in AC7.

### AC1 — Tool: ignore `*.test.{ts,tsx}` files (epics.md:1033)

**File:** `tools/brand-voice-lint.ts` — MODIFY (`collectFiles` / `walkDir`).

**Given** the lint walks `FE/src` and `FE/app`,
**When** it encounters a file whose name matches `/\.test\.tsx?$/`,
**Then** it is excluded from the returned `FileEntry[]` — so NO rule (1, 2, or 3) scans it.

- Implement as a filename predicate (e.g. `isTestFile(name)`) checked in `walkDir` alongside the existing `SCAN_EXT` check. The blanket skip is intentional and matches the epics' unqualified "ignores `*.test.{ts,tsx}` files."
- **Census proof (verify before/after):** all 84 current Rule-2 test-file hits live in `.test.tsx?`-suffixed files (zero in `__tests__/` non-`.test.` helpers), so a suffix-based skip clears 100% of them. Incidentally, 33 Rule-3 test-file warnings also drop — this is expected, not a regression (AC11 documents the new 57-warning total).
- **Do NOT skip by directory** (`__tests__/`) — the epics AC keys on the filename suffix, and `tools/__tests__/*.test.ts` is outside the scanned roots anyway.

### AC2 — Tool: mask code comments before the Rule-2 (AVOID lexicon) scan

**File:** `tools/brand-voice-lint.ts` — MODIFY (`lintFileRule2`, plus a new `maskComments` helper).

**Given** an AVOID term appears **inside a `//` line comment or a `/* … */` block comment**,
**When** Rule 2 scans the file,
**Then** the occurrence is NOT reported.

**Given** an AVOID term appears inside a **string literal** (`'…'`, `"…"`, or `` `…` ``) or bare code,
**When** Rule 2 scans the file,
**Then** the occurrence IS still reported (user-facing strings are string literals — they must stay in scope).

**Implementation — `maskComments(content: string): string`:**

- Return a copy of `content` in which **every character inside a comment region is replaced by a space**, while every other character (including all string-literal content) is preserved verbatim. **Replace-with-space, do NOT delete** — this keeps byte offsets, line numbers, and columns identical so `locate()` still reports the true `line:column` of any surviving (string-literal) hit.
- Use a **single-pass character state machine** with these states: `CODE`, `LINE_COMMENT`, `BLOCK_COMMENT`, `SQUOTE`, `DQUOTE`, `BACKTICK`. Transitions:
  - In `CODE`: `//` → `LINE_COMMENT`; `/*` → `BLOCK_COMMENT`; `'` → `SQUOTE`; `"` → `DQUOTE`; `` ` `` → `BACKTICK`.
  - In `LINE_COMMENT`: newline → back to `CODE` (newline itself preserved). All other chars → space.
  - In `BLOCK_COMMENT`: `*/` → back to `CODE` (both chars → space). All other chars → space (newlines inside the block preserved as newlines so line counts stay correct).
  - In a string state: honor backslash-escape (`\` skips the next char) so `\"` / `\'` / `` \` `` do not close the string; a `//` or `/*` inside a string is **not** a comment (protects `"https://…"` from false comment-stripping). Closing quote → back to `CODE`.
- **Documented v1 limitation (acceptable):** template-literal `${…}` interpolations are treated as ordinary backtick-string content; a `//`-comment nested inside a `${}` expression is not separately detected. This is vanishingly rare and would never contain an AVOID word; note it in completion notes. Do not build a nested-expression parser (YAGNI).
- **Wiring:** `lintFileRule2` scans `maskComments(content)` for the AVOID terms but reports the resolved `line:column` against that same masked string (positions are identical to the raw string by construction). `lintContent`'s Rule-2 path uses the same helper, so `lintContent("x.ts", "// 실패")` → 0 warnings and `lintContent("x.ts", 'const m = "실패";')` → 1 warning.
- **Scope guard:** comment-masking applies to **Rule 2 only.** Rule 1 (`lintFileRule1`) and Rule 3 (`lintFileRule3` / `lintSvgRule3`) continue to scan raw `content` — unchanged behavior, predictable Rule-3 count.

**Census proof:** the 17 non-test comment-hits (SpectatorReadOnlyBanner.tsx:12 ×8, SelfReviveCTA.tsx:15 ×8, GraceBanner.tsx:9 ×1) all sit in `//` or `/* */` comments and are cleared by this masking with zero source edits to those files.

### AC3 — Tool: scan `BE/src/main/resources/messages*.properties` (if present) — future-ready scaffold

**File:** `tools/brand-voice-lint.ts` — MODIFY (new `.properties` scan path + conditional scan-root).

**Given** one or more files matching `BE/src/main/resources/messages*.properties` exist,
**When** the default (no-arg) lint runs,
**Then** the **value side** of each `key=value` line is scanned for the 8 AVOID terms (Rule 2, WARN); `#`/`!`-prefixed comment lines and the key portion are NOT scanned.

**Given** no such files exist (the current tree),
**When** the lint runs,
**Then** the path is a silent no-op and the scanned-file count is unaffected.

- Add the BE resources dir as a conditionally-included scan root in the default roots: only contribute `messages*.properties` matches (glob the dir for `messages*.properties`; if `statSync` of the dir fails or the glob is empty, contribute nothing). Reuse the existing `collectFiles` "is this a file?" branch or a small dedicated collector — keep it minimal.
- Add `lintPropertiesRule2(file, content)`: split on lines; for each line, skip if it starts (after trim) with `#` or `!`; locate the first unescaped `=` or `:`; scan only the substring AFTER the separator for AVOID terms; report `line:column` of any hit. Java `.properties` are ISO-8859-1 with `\uXXXX` escapes for non-ASCII, but a `messages_ko*.properties` may be UTF-8 — read as UTF-8 (consistent with the rest of the tool) and document that `\uXXXX`-escaped Korean is not un-escaped in v1 (no such file exists to need it).
- `.properties` files get **Rule 2 only** — Rules 1 and 3 are TS/JSX/SVG concerns.
- **Do NOT create any `messages*.properties` file.** The deliverable is the scan capability + a `lintContent`-driven unit test (AC9) proving a synthetic `messages.properties` string is scanned correctly. Real BE string externalization is explicitly OUT OF SCOPE (AC8).

### AC4 — Copy pass: rewrite the 7 genuine user-facing `실패` strings (BYTE-LOCKED)

**Files:** the 7 sites below — MODIFY (pure string-literal swap; identical surrounding control flow).

Every replacement removes `실패`, uses an agency-preserving "마치지/저장하지/참여하지 못했어요" frame (no blame), and adds the invitation-tone retry suffix per FR-8.8.3 ("invitation, not demand"). All replacements are AVOID-lexicon-clean (verified against all 8 terms).

| # | File:line | Current literal | New literal (LOCKED) |
|---|---|---|---|
| 1 | `FE/src/lib/query/hooks/today.ts:19` (`REFLECTION_GENERIC_ERROR` const) | `회고 저장에 실패했어요. 잠시 뒤 다시 시도해 주세요.` | `회고를 저장하지 못했어요. 잠시 뒤 다시 시도해 주세요.` |
| 2 | `FE/app/join.tsx:58` (toast fallback) | `그룹 참여에 실패했어요.` | `그룹에 참여하지 못했어요. 잠시 뒤 다시 시도해 주세요.` |
| 3 | `FE/app/login.tsx:48` (email toast fallback) | `로그인에 실패했어요.` | `로그인을 마치지 못했어요. 잠시 뒤 다시 시도해 주세요.` |
| 4 | `FE/app/login.tsx:62` (kakao toast fallback) | `카카오 로그인에 실패했어요.` | `카카오 로그인을 마치지 못했어요. 잠시 뒤 다시 시도해 주세요.` |
| 5 | `FE/app/notification-settings.tsx:58` (toast fallback) | `알림 설정 저장에 실패했어요.` | `알림 설정을 저장하지 못했어요. 잠시 뒤 다시 시도해 주세요.` |
| 6 | `FE/app/signup.tsx:38` (toast fallback) | `가입에 실패했어요.` | `가입을 마치지 못했어요. 잠시 뒤 다시 시도해 주세요.` |
| 7 | `FE/src/auth/AuthContext.tsx:231` (Kakao deeplink `new Error(...)` message — reaches login.tsx kakao() `error.message` toast) | `` Kakao 로그인 실패: ${error} `` | `` Kakao 로그인을 마치지 못했어요: ${error} `` |

**Rules:**
- Swap ONLY the literal text. Do NOT change the `error instanceof Error ? error.message : <fallback>` logic, the toast call, the const name, or any imports.
- Site #7 is the only `new Error(...)` case (borderline log/user-facing — it surfaces via the kakao() catch). Rewriting it removes the warning cleanly, which is why no `console.*`/logger-context detection is built into the tool (the AC's "non-user-facing logs" exclusion is satisfied by elimination, not by tool heuristics — KISS).
- These 7 are the COMPLETE set of genuine user-facing string hits (census-verified). There are no AVOID-lexicon `console.*`/Sentry occurrences requiring rewrite.

### AC5 — Paired test update (the ONE test that asserts a rewritten literal)

**File:** `FE/src/lib/query/hooks/__tests__/today.test.tsx:494` — MODIFY.

**Given** the today.ts `REFLECTION_GENERIC_ERROR` const is rewritten (AC4 #1),
**When** `today.test.tsx` asserts the toast error text,
**Then** line 494's expected literal `회고 저장에 실패했어요. 잠시 뒤 다시 시도해 주세요.` is updated to the new value `회고를 저장하지 못했어요. 잠시 뒤 다시 시도해 주세요.` (byte-identical to AC4 #1).

- This is the ONLY existing test asserting any of the 7 rewritten literals (grep-verified). The other 6 toast fallbacks are not asserted in any test, so they need no paired edits.
- This test file is `.test.tsx` → the enhanced lint skips it (AC1), so it will not itself trip Rule 2 even though it now contains brand-clean copy.

### AC6 — End-state: zero AVOID-lexicon matches in user-facing strings (epics.md:1029–1031)

**Given** AC1 + AC2 + AC3 + AC4 + AC5 are complete,
**When** `tsx tools/brand-voice-lint.ts` runs against the v1 tree,
**Then** the **Rule-2 (AVOID-lexicon) warning count is exactly 0** — comments, test files, and non-user-facing logs are excluded by construction; the 7 user-facing strings are rewritten.

- Verify: `tsx tools/brand-voice-lint.ts 2>&1 | grep -c "Rule 2"` → `0`.
- Rule 1 HARD violations stay `0` (exit 0). Rule 3 token-literal warnings are unchanged in behavior; the total warning count drops from **198 → 57** (108 Rule-2 eliminated + 33 Rule-3 test-file hits incidentally skipped via AC1). The 57 residual are all Rule 3 and OUT OF SCOPE (AC8).

### AC7 — Lexicon reference (DO NOT CHANGE THE 8 TERMS)

**Source of truth — PRD §FR-8.8.2 (prd.md:442–444) + Architecture §5.5 (architecture.md:538–543):**

- **AVOID (exactly 8, lint Rule 2):** `벌금`, `잃었다`, `떨어졌다`, `실패`, `자책`, `부담`, `패배`, `죄책감`.
- **USE (positive frame, 9):** `함께`, `선물`, `응원`, `컴백`, `회생`, `그룹`, `동료`, `우리`, `살리다`.
- **Tone (FR-8.8.3):** invitation, not demand — "수진이 회생을 기다리고 있어요" ✓, not "수진이가 죽었다 살려라!" ✗.
- **Elimination copy (FR-8.8.5):** use "컴백 가능" language, not "탈락" / "실패".

The tool's `AVOID_LEXICON` array already matches this set exactly. `탈락`/`챌린저스`/`도전`/`챌린지` are strategic-context terms, NOT canonical AVOID-lexicon entries — do NOT add them to the array (they are addressed editorially, and `실패` already covers the elimination-error case).

### AC8 — Out-of-scope fence

The following are explicitly NOT part of Story 8.2:

1. **Rule 3 (token-literal guard) behavior, blocklist, or SVG path** — untouched. The 57 residual Rule-3 warnings (40 in generated `FE/src/theme/tokens.ts`) stay.
2. **Rule 1 (NFR-9.6.1 packed-type HARD gate)** — untouched (except that AC1's test-file skip means a `.test.tsx` survival-color ref no longer trips it; current HARD count is 0, so no regression).
3. **Promoting Rule 2 to a HARD gate** — Architecture §4.15 locks it as WARN; human review (Story 8.4) is authoritative.
4. **BE string externalization into `messages*.properties`** — none exist; AC3 ships a scan scaffold only.
5. **Editing the AVOID-lexicon set** (add/remove/reorder the 8 terms).
6. **New CI workflow** — `scripts/test.sh`'s no-arg invocation already picks up the tool changes.
7. **`docs/brand-voice-review.md`** — that human-review checklist is Story 8.4's deliverable.
8. **Touching the 3 documentation comments** (SpectatorReadOnlyBanner / SelfReviveCTA / GraceBanner) that list the AVOID lexicon — masking clears them; the comments stay.
9. **English/ASO store copy** — Story 8.3.
10. **`tokens.json` / token codegen / contrast-check / analytics-taxonomy-lint** — untouched.
11. **New dependencies** — the comment state-machine and `.properties` parser are hand-written with `node:fs` only.
12. **Backfilling brand voice into BE Java inline strings** — no BE source edits at all.

### AC9 — Tool test matrix (NET-ADDITIVE, `node:test` + `node:assert/strict`)

**File:** `tools/__tests__/brand-voice-lint.test.ts` — MODIFY (extend; keep existing cases byte-identical). Minimum new cases:

| # | Case | Asserts |
|---|---|---|
| 1 | `lintContent("x.ts", "// 실패")` | 0 Rule-2 warnings (line comment masked) |
| 2 | `lintContent("x.ts", "/* 벌금 잃었다 */")` | 0 Rule-2 warnings (block comment masked) |
| 3 | `lintContent("x.ts", 'const m = "실패";')` | exactly 1 Rule-2 warning at the correct `line:column` (string literal still scanned) |
| 4 | `lintContent("x.ts", 'const u = "https://a.io/실패";')` | 1 Rule-2 warning — proves `//` inside a string is NOT treated as a comment (no false-strip of the term after the URL) |
| 5 | `lintContent("x.ts", 'const t = `안전 // 실패`;')` | 1 Rule-2 warning — `//` inside a template literal is string content, not a comment |
| 6 | `collectFiles` over a temp/synthetic root (or a `walkDir`-level unit) | a `foo.test.ts` / `foo.test.tsx` file is excluded; a `foo.ts` is included |
| 7 | `lintContent("BE/src/main/resources/messages.properties", "err.fail=가입에 실패했어요")` via the `.properties` path | 1 Rule-2 warning on the VALUE; `# 실패` comment line and the key side produce 0 |
| 8 | Rule-1 + Rule-3 regression | a survival-color-without-label case still HARD-fails and a raw-hex case still WARNs (proves comment-mask + test-skip did NOT alter Rule 1 / Rule 3) |

**TDD order:** RED → GREEN per case. Run with `cd tools && ./node_modules/.bin/tsx --test __tests__/brand-voice-lint.test.ts`. Existing brand-voice-lint test cases stay green unchanged.

**FE tests:** the only FE test edit is AC5 (today.test.tsx:494 literal). No new FE test files. BE tests: 0.

### AC10 — File / scope fence (LOCKED ALLOW LIST)

**Story 8.2 modifies exactly these files. The reviewer's diff-sanity gate (AC11 gate 8) MUST find no others.**

**MODIFIED — tool (2):**
```
tools/brand-voice-lint.ts                                   (AC1 test-skip + AC2 comment-mask for Rule 2 + AC3 .properties scaffold)
tools/__tests__/brand-voice-lint.test.ts                    (AC9 new cases)
```

**MODIFIED — copy pass (7 source):**
```
FE/src/lib/query/hooks/today.ts                             (AC4 #1)
FE/app/join.tsx                                             (AC4 #2)
FE/app/login.tsx                                            (AC4 #3, #4)
FE/app/notification-settings.tsx                           (AC4 #5)
FE/app/signup.tsx                                          (AC4 #6)
FE/src/auth/AuthContext.tsx                                 (AC4 #7)
```

**MODIFIED — paired test (1):**
```
FE/src/lib/query/hooks/__tests__/today.test.tsx            (AC5 literal update)
```

**MODIFIED — process (2):**
```
_bmad-output/implementation-artifacts/sprint-status.yaml                       (status flip)
_bmad-output/implementation-artifacts/8-2-brand-voice-copy-pass-lint-helper.md (Tasks/File List/Completion Notes)
```

**Banned paths (`git diff origin/main --stat` MUST show zero hits):**
- `BE/**` — no backend work of any kind (BE test delta = 0)
- `BE/src/main/resources/messages*.properties` — do NOT create the file (scaffold only)
- `BE/src/main/resources/db/migration/**` — no migration
- `FE/src/theme/tokens.json` / `FE/src/theme/tokens.ts` — no token edits (Rule 3 OOS)
- `tools/analytics-taxonomy-lint.ts` / `tools/contrast-check.ts` — sibling tools untouched
- `scripts/test.sh` / `scripts/verify.sh` / `scripts/build.sh` — no script edits (no-arg invocation already works)
- `.github/workflows/**` — no CI workflow edits
- `docs/**` — no docs (brand-voice-review.md is Story 8.4)
- `FE/package.json` / `tools/package.json` — no new deps
- The 3 documentation-comment files (`SpectatorReadOnlyBanner.tsx`, `SelfReviveCTA.tsx`, `GraceBanner.tsx`) — masking clears them; do not edit
- Any `FE/app/__tests__/` path — tests for `app/`-level files live in `FE/src/__tests__/` (Jest testMatch), though this story adds no new FE test files

### AC11 — Pre-merge verify gates (12-GATE MATRIX)

| # | Gate | How | Pass criterion |
|---|---|---|---|
| 1 | Brand-voice Rule-2 = 0 | `cd <repo> && tools/node_modules/.bin/tsx tools/brand-voice-lint.ts 2>&1 \| grep -c "Rule 2"` | `0` |
| 2 | Brand-voice HARD = 0 / exit 0 | `tsx tools/brand-voice-lint.ts; echo $?` | `0 HARD violation(s)` in summary; exit `0` |
| 3 | Warning total = 57 (informational) | `tsx tools/brand-voice-lint.ts 2>&1 \| tail -1` | `57 warning(s)` (down from 198; 0 Rule-2 + 57 Rule-3). A different Rule-3 total is acceptable ONLY if explained; Rule-2 MUST be 0 |
| 4 | Tools unit tests | `cd tools && ./node_modules/.bin/tsx --test __tests__/brand-voice-lint.test.ts __tests__/contrast-check.test.ts __tests__/analytics-taxonomy-lint.test.ts` | all green; brand-voice suite gains ≥ 8 new cases (AC9) |
| 5 | Tools typecheck | `cd tools && npx tsc --noEmit` | 0 new errors in production code; the three pre-existing TS5097 test-import errors are an accepted baseline until the tools tsconfig is fixed |
| 6 | FE typecheck | `cd FE && npm run typecheck` | 0 new errors (pre-existing FriendsTodayPager 2-error baseline allowed per PR #98) |
| 7 | FE Jest | `cd FE && npm test` | 82 suites / 572 tests / 9 snapshots / 0 failures (baseline preserved; today.test.tsx asserts the new literal) |
| 8 | Diff sanity (scope fence) | `git diff origin/main --stat \| grep -E "^(BE/\|infra/\|\.github/\|docs/\|scripts/\|FE/src/theme/tokens\|tools/(analytics-taxonomy-lint\|contrast-check)\|FE/package\.json\|tools/package\.json)"` | 0 hits (AC10 banned paths) |
| 9 | Copy-pass completeness | `git grep -nE "실패했어요\|로그인 실패" -- FE/ \| grep -vE "\.test\."` | 0 hits (all 7 user-facing `실패` strings rewritten; the GraceBanner JSDoc uses `'실패'` in quotes — masked by AC2 and not matched by this grep) |
| 10 | FE ESLint scoped | `cd FE && npx eslint app/join.tsx app/login.tsx app/signup.tsx app/notification-settings.tsx src/auth/AuthContext.tsx src/lib/query/hooks/today.ts src/lib/query/hooks/__tests__/today.test.tsx` | 0 errors / 0 new warnings |
| 11 | BE delta = 0 | `git diff origin/main --stat -- BE/ \| wc -l` | `0` (no BE work) |
| 12 | `.properties` scaffold proof | the AC9 case #7 passes AND `find BE/src/main/resources -name 'messages*.properties' \| wc -l` | test green AND `0` files (scaffold present, no file created) |

**No manual EAS gate.** Story 8.2 changes only a lint tool + 7 string literals — no new screens, navigation, or native surface. The copy-pass strings are exercised by existing toast paths; gate 7 (Jest) covers the asserted one. (This story has no analogue to Story 8.1's gates 13–14 device smoke.)

## Tasks / Subtasks

- [x] **Task 1 — Read-only inventory** (AC0)
  - [x] Read `tools/brand-voice-lint.ts` in full; mark the exact seams: `collectFiles`/`walkDir`, `lintFileRule2`, `lintFiles`, `lintContent`, `main`, `__testing`.
  - [x] Read `tools/__tests__/brand-voice-lint.test.ts` (node:test pattern) + `tools/analytics-taxonomy-lint.ts` (warn-only/skip-dir conventions).
  - [x] Read the 7 copy-pass sites + `today.test.tsx:494`.
  - [x] Baseline run: `tsx tools/brand-voice-lint.ts` → confirmed `0 HARD / 198 warning(s)`, `grep -c "Rule 2"` = `108` (Rule-3 = 90).
- [x] **Task 2 — Tool: test-file skip** (AC1) — RED→GREEN
  - [x] Add AC9 case #6 (test); run → RED.
  - [x] Add `isTestFile(name)` predicate to `walkDir`; run → GREEN. Re-run lint: Rule-2 dropped 108→24.
- [x] **Task 3 — Tool: comment-mask for Rule 2** (AC2) — RED→GREEN
  - [x] Add AC9 cases #1–#5 (line/block/string/URL/template); run → RED (#1/#2 red; #3/#4/#5 were already-green guards).
  - [x] Implement `maskComments(content)` state machine (replace-with-space, string-aware, escape-aware); route `lintFileRule2` through it (both `lintFiles` + `lintContent` call it); exported via `__testing`; run → GREEN. Re-run lint: Rule-2 dropped 24→7.
  - [x] Add AC9 case #8 (Rule 1 + Rule 3 regression) → GREEN (proves Rules 1/3 still scan raw content).
- [x] **Task 4 — Tool: `.properties` scan-if-present scaffold** (AC3) — RED→GREEN
  - [x] Add AC9 case #7 (`lintContent` over synthetic `messages.properties`); run → RED.
  - [x] Add `lintPropertiesRule2` + conditional `collectMessagesProperties()` scan root (no-op when no files); run → GREEN. Verified `find BE/src/main/resources -name 'messages*.properties'` = 0 files, lint scanned-count unchanged.
- [x] **Task 5 — Tool typecheck** (AC11 gate 5) — `cd tools && npx tsc --noEmit`: logic file `brand-voice-lint.ts` is type-clean; the only errors are a PRE-EXISTING `TS5097` `.ts`-import-extension baseline (3) in the three `__tests__/*.test.ts` files (confirmed via `git stash` that the baseline carries the same 3). `tools/tsconfig.json` lacks `allowImportingTsExtensions` and is outside the AC10 allow-list, so it is left untouched. Deviation logged.
- [x] **Task 6 — Copy pass** (AC4) — applied all 7 byte-locked swaps; each `실패` removed per the AC4 table (Rule-2 7→0).
- [x] **Task 7 — Paired test** (AC5) — updated today.test.tsx:494 to the new literal.
- [x] **Task 8 — Full verification** (AC6, AC11) — Rule-2 = 0, total = 57, FE Jest 82/572/9, tools 47/47, scope fence + BE delta clean, copy-pass grep clean. Gate 5 has only the formally accepted three-error TS5097 baseline and no production-code errors.
- [x] **Task 9 — Closeout** — filled File List + Completion Notes; flipped sprint-status `8-2` → `review`.

### Review Findings

- [x] [Review][Patch] Rule-2 comment masking misclassifies regex literals containing quotes, so a following comment can still emit false AVOID warnings [tools/brand-voice-lint.ts:278]
- [x] [Review][Patch] The `.properties` parser ignores valid whitespace-separated key/value entries, allowing message values to bypass Rule 2 [tools/brand-voice-lint.ts:368]
- [x] [Review][Patch] `maskComments` exceeds the project-context 50-line function limit and should be split without changing parser behavior [tools/brand-voice-lint.ts:261]
- [x] [Review][Patch] AC9's properties test does not exercise an AVOID term on the key side or an `!` comment line despite claiming both exclusions [tools/__tests__/brand-voice-lint.test.ts:279]
- [x] [Review][Patch] New production comments reference the current story/AC, contrary to the project rule that task references belong in review history rather than source comments [tools/brand-voice-lint.ts:75]
- [x] [Review][Patch] The completion record says all 12 gates passed even though AC11 Gate 5 requires zero errors and `npx tsc --noEmit` returns three TS5097 errors; record the gate as failed/pre-existing or formally amend the criterion [_bmad-output/implementation-artifacts/8-2-brand-voice-copy-pass-lint-helper.md:273]

## Dev Notes

### Implementation traps (ranked by likelihood of biting)

1. **Naive comment-stripping corrupts URLs.** A regex `s|//.*$||` would delete everything after `https://` and could HIDE a genuine AVOID word later on the line (or, worse, false-pass). The AC2 state machine treats `//` inside a string as string content — case #4 (`"https://a.io/실패"` → 1 warning) is the guard test. Do not use a line regex.
2. **Mask must replace-with-space, not delete.** Deleting comment characters shifts byte offsets and breaks `locate()`'s `line:column`. Preserve length and newlines.
3. **Comment-mask is Rule 2 ONLY.** If you mask before Rule 1 or Rule 3 you change their counts (the 57 Rule-3 / 0 Rule-1 numbers in AC11 assume raw-content scanning for Rules 1/3). Keep `lintFileRule1`/`lintFileRule3` on raw `content`.
4. **Test-file skip is blanket (all rules), comment-mask is Rule-2-only.** Two different mechanisms at two different layers (collection vs Rule-2 scan). Don't conflate them.
5. **`lintContent` and `lintFiles` must agree.** Both public entry points route Rule 2 through `maskComments`; the tests drive `lintContent`, CI drives `lintFiles`. A fix in only one leaves the other inconsistent.
6. **strict mode + `noUncheckedIndexedAccess`.** Every `content[i]` / `match[1]` access in the state machine needs a guard or non-null assertion with justification. `tsc --noEmit` (gate 5) will catch misses.
7. **Don't touch the 8-term array.** Adding `탈락`/`도전` etc. violates AC7 and would re-introduce warnings on legitimate copy. The set is PRD/Architecture-locked.
8. **The `.properties` path is a scaffold, not a refactor.** Build the scan + 1 test; do NOT create a `messages*.properties` file or externalize any BE string (AC8 #4). Gate 12 asserts 0 files exist.
9. **today.ts has ONE paired test; the other 6 toasts have none.** Don't go hunting for tests to update on join/login/signup/notification-settings — grep confirmed only today.test.tsx:494 asserts a rewritten literal. But DO re-run full FE Jest (gate 7) in case a snapshot or integration test renders one of the toasts.
10. **Site #7 (AuthContext Error) is in a sensitive file.** It is a pure template-literal text swap inside `new Error(...)`; do not alter the reject/resolve flow or the `${error}` interpolation.
11. **Severity stays WARN.** Do not change `main`'s exit-code logic. Rule 2 warnings must never fail CI (Architecture §4.15). The copy pass is what reaches "zero," not a gate flip.
12. **Brand copy is provisional.** AC4 strings are AVOID-clean and implement-ready, but Story 8.4's human gate is authoritative. Note in completion that these are subject to PM/designer review.

### Current state → what changes (per AC0 read-of-modified-files mandate)

- **`tools/brand-voice-lint.ts`** today: walks `FE/src`+`FE/app` for `.ts/.tsx/.svg`; Rule 2 = naive `indexOf` over raw content; no test-file skip; no `.properties` path. After: `walkDir` skips `*.test.{ts,tsx}`; Rule 2 scans `maskComments(content)`; a conditional BE-resources `.properties` collector + `lintPropertiesRule2` exist (no-op now). Rules 1/3 unchanged. Exit-code contract unchanged. **Must preserve:** the `lintFiles`/`lintContent` dual API, the `__testing` export shape, the WARN/HARD split, the no-arg default-roots behavior that `scripts/test.sh` relies on.
- **7 copy-pass files** today: each has one `실패`-framed user-facing literal in an otherwise-correct toast/error path. After: each literal swapped per AC4; control flow byte-identical. **Must preserve:** `error instanceof Error ? error.message : <fallback>` precedence, toast API calls, const names, imports.
- **`today.test.tsx`** today: asserts the old reflection-error literal at :494. After: asserts the new literal. **Must preserve:** every other assertion in the file.

### Why this story matters (KPI signal)

PRD §13 #1 falsification trigger: KR users must adopt an effort-only economy **without** the 챌린저스 deposit-refund / penalty mental model. Brand voice is the deprogramming surface — a single "실패" in a toast re-anchors the penalty frame the whole product is fighting. The lint helper makes drift visible on every CI run (warn, not block, per Architecture §4.15 so velocity isn't gated on false positives), and the copy pass clears the v1 baseline to zero so Story 8.4's human release-gate starts from a clean automated signal.

### Project context reference

- `project-context.md` — SecureStore `yeosal.*` namespace, `apiRequest<T>` for API, immutable updates, route registration: **none apply** — this story touches a Node lint tool + 7 string literals, makes zero API calls, adds zero routes.
- Tools module is its own npm workspace (`tools/package.json`, `node:test` runner, tsx 4.19, strict tsconfig) — run tool tests from `tools/`, not from `FE/`.
- "Merge on deferral" norm (PRs #90/#93/#95/#98/#99/#100/#102/#103): not invoked here — all 12 gates are local and non-manual; there is no Docker-bound IT or EAS smoke to defer.

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story 8.2 (lines 1009–1033)] — the 5 ACs + scan globs + warn-not-fail + ignore-test-files + zero-matches end-state.
- [Source: _bmad-output/planning-artifacts/prd.md#FR-8.8.2 (442–444), FR-8.8.3 (445), FR-8.8.5 (447), NFR-9.6.1 (497)] — AVOID/USE lexicon, push tone, elimination copy, packed-type hard gate.
- [Source: _bmad-output/planning-artifacts/architecture.md#§4.15 (400–413), §5.5 (538–543)] — three-rule lint design, WARN vs HARD severity, scan globs incl. `messages*.properties (if present)`, human-review relationship to Story 8.4.
- [Source: tools/brand-voice-lint.ts] — the tool being enhanced (seams enumerated in AC0).
- [Source: tools/__tests__/brand-voice-lint.test.ts] — node:test pattern for AC9.
- [Source: scripts/test.sh:19–27] — CI invocation; no edits needed.
- [Source: _bmad-output/implementation-artifacts/8-1-5-screen-onboarding-flow.md] — Epic 8 sibling story; FE test-path + brand-voice byte-lock conventions.

## Dev Agent Record

### Agent Model Used

claude-opus-4-8[1m] (Opus 4.8, 1M context)

### Debug Log References

- Baseline lint: `0 HARD / 198 warning(s)`; Rule-2 = 108, Rule-3 = 90 (108 + 90 = 198).
- After AC1 test-skip + AC2 mask + AC3 scaffold (pre copy-pass): `0 HARD / 64 warning(s)`; Rule-2 = 7 (exactly the AC4 user-facing strings), Rule-3 = 57.
- After AC4 copy-pass + AC5 paired test (end state): `0 HARD / 57 warning(s)`; Rule-2 = 0, Rule-3 = 57.
- tools tests: 25/25 brand-voice (17 existing + 8 AC9); 46/46 across brand-voice + contrast + analytics-taxonomy.
- TDD: new AC9 cases #1/#2/#6/#7 confirmed RED against the pre-change tool, then GREEN after implementation; #3/#4/#5/#8 are already-green guard cases (string scanning / Rule 1+3 regression).

### Completion Notes List

**Implemented (all functional ACs + 12-gate matrix with the documented Gate 5 baseline):**

- **AC1** — `isTestFile(name)` (`/\.test\.tsx?$/`) skip added in `walkDir` at collection time, so no rule scans `*.test.{ts,tsx}` (cleared 84 Rule-2 + incidentally 33 Rule-3 test-file hits).
- **AC2** — `maskComments(content)` single-pass char state machine (`CODE`/`LINE_COMMENT`/`BLOCK_COMMENT`/`SQUOTE`/`DQUOTE`/`BACKTICK`), replace-with-space (length/newline/offset preserving), string- and escape-aware so `//` inside `"https://…"` or a template literal stays string content. `lintFileRule2` scans the masked copy; both `lintFiles` and `lintContent` inherit it because both call `lintFileRule2`. Rules 1 and 3 still scan raw `content` (cleared the 17 documentation-comment hits in SpectatorReadOnlyBanner/SelfReviveCTA/GraceBanner with zero source edits to those files).
- **AC3** — `collectMessagesProperties()` (conditional, no-op when dir absent or no `messages*.properties`) appended only on the default no-arg `main` scan; `lintPropertiesRule2` scans the value side of `key=value`/`key:value` lines, skipping `#`/`!` comment lines and the key side. Wired into both `lintFiles` and `lintContent`. Genuine no-op today: BE has 0 `messages*.properties` and 0 `MessageSource`/`ResourceBundle` (verified).
- **AC4** — 7 byte-locked `실패`-frame → agency-preserving `…못했어요` rewrites applied (today.ts const + join/login×2/notification-settings/signup toast fallbacks + AuthContext Kakao `new Error`). Multi-line wrapping used for the 6 toast ternaries to honor ESLint/Prettier line length; the literal text is byte-identical to the AC4 table.
- **AC5** — today.test.tsx:494 expected literal updated to match AC4 #1.
- **AC6** — Rule-2 = 0; total warnings 198 → 57 (all residual are Rule-3, OOS per AC8).

**Deviations / notes:**

1. **Gate 5 (`tools/tsc --noEmit`) pre-existing baseline.** Not 0 errors: there are 3 `TS5097` "import path can only end with `.ts` when `allowImportingTsExtensions` is enabled" errors, one in each of `__tests__/{brand-voice-lint,contrast-check,analytics-taxonomy-lint}.test.ts`. Confirmed via `git stash` that the baseline (without my edits) carries the same 3. The fix (`allowImportingTsExtensions: true` in `tools/tsconfig.json`) is out of the AC10 allow-list, so the config is left untouched. My production file `brand-voice-lint.ts` itself is type-clean. This mirrors the gate-6 FriendsTodayPager-baseline pattern.
2. **`maskComments` v1 limitation (acceptable, per AC2):** a `//` nested inside a template-literal `${…}` expression is treated as backtick-string content (no nested-expression parser — YAGNI). Regex literals are modeled sufficiently to keep quotes inside a regex from corrupting following comment detection. Brand copy is warn-only and Story 8.4's human gate is authoritative.
3. **`maskComments` and `collectMessagesProperties`/`lintPropertiesRule2` exported via `__testing`** (extended, not replaced) per AC0 guidance.
4. **Brand copy is provisional** — the 7 AC4 strings are AVOID-lexicon-clean by construction and implement-ready, but Story 8.4 (release-gate review) holds final authority; PM/designer wording revisions there are expected, not 8.2 defects.
5. **No manual EAS gate** — no new screen/navigation/native surface; the rewritten strings ride existing toast paths (the one asserted is covered by gate 7).

### File List

**MODIFIED — tool (2):**
- `tools/brand-voice-lint.ts` (AC1 `isTestFile` walkDir skip + AC2 `maskComments` + Rule-2 reroute + AC3 `collectMessagesProperties`/`isPropertiesFile`/`findPropertySeparatorIndex`/`lintPropertiesRule2` + wiring in `lintFiles`/`lintContent`/`main` + `__testing` extension)
- `tools/__tests__/brand-voice-lint.test.ts` (AC9 — 8 new cases + `node:fs`/`node:os` imports)

**MODIFIED — copy pass (7 source):**
- `FE/src/lib/query/hooks/today.ts` (AC4 #1)
- `FE/app/join.tsx` (AC4 #2)
- `FE/app/login.tsx` (AC4 #3, #4)
- `FE/app/notification-settings.tsx` (AC4 #5)
- `FE/app/signup.tsx` (AC4 #6)
- `FE/src/auth/AuthContext.tsx` (AC4 #7)

**MODIFIED — paired test (1):**
- `FE/src/lib/query/hooks/__tests__/today.test.tsx` (AC5 literal update at :494)

**MODIFIED — process (2):**
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (status flip ready-for-dev → in-progress → review + dated comments)
- `_bmad-output/implementation-artifacts/8-2-brand-voice-copy-pass-lint-helper.md` (Tasks/File List/Completion Notes/Status)

### Change Log

| Date | Change |
|------|--------|
| 2026-06-10 | Implemented Story 8.2 — brand-voice-lint AC1 test-skip + AC2 comment-mask (Rule 2) + AC3 `.properties` scaffold; AC4 7-string copy pass + AC5 paired test. Rule-2 108 → 0, warning total 198 → 57. Gate 5 retains the formally accepted pre-existing `TS5097` baseline. Status → review. |
| 2026-06-11 | Applied code-review patches: regex-aware comment masking, whitespace-delimited properties support, function-size split, stronger properties tests, source-comment cleanup, and accurate Gate 5 documentation. |
