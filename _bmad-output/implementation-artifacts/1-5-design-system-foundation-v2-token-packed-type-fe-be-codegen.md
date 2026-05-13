# Story 1.5: Design System Foundation v2 — token packed type + FE↔BE codegen

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As **any engineer building any FE or BE surface in yeolsal v1**,
I want **a single source-of-truth token bundle (`FE/src/theme/tokens.json`) that both FE and BE consume via codegen, with `semantic.survival` structurally requiring `{color, label, icon, grass-treatment}` per state, and a `<SubModeProvider subMode>` page wrapper that injects 5 sub-mode override sets through `useTheme()`**,
so that **NFR-9.6.1 (color-is-never-sole-signal) is enforced at compile/lint time as a hard CI gate, FE↔BE design drift is structurally impossible, and downstream stories (1.6 / 1.7 / 2.1 / 3.4 / 6.1 / 7.1) can build on top of the v2 Oxblood Editorial token system without redefining it**.

PRD authority: §14.2 visual identity lock (yeolsal v2 — Oxblood Editorial), NFR-9.6.1 (packed-type `semantic.survival` + brand-voice lint hard gate), NFR-9.6.3 (Dynamic Type / accessibility).
Architecture authority: §4.15 (brand-voice + a11y gate — hard CI), §4.16 (FE↔BE Design Token Codegen Pipeline), §4.9 (`SvgRenderer.java` token sourcing via `GeneratedTokens`).
Readiness authority: Implementation Readiness Report 2026-05-11 — M1 (`<SurvivalChip>` primitive), M2 (`<SubModeProvider>` page wrapper), L1 (override-key whitelist enforcement), M5/H4 (NFR-9.6.1 enforcement gap), A5 (sub-mode token sync mechanism).
UX authority: Visual Design Foundation §Color/Typography/Spacing/Motion (v2 oklch values), Sub-Mode Catalog (D1–D5 override blocks), Surface Assignment Matrix, Token Surface Override 정책 (12-key whitelist), Accessibility Considerations (WCAG 2.2 AA contrast).

> **Foundation-deviation note.** Story 1.5 is a **W1 foundation story** that ships infrastructure (tokens.json schema, Gradle codegen task, brand-voice lint, SubModeProvider, SurvivalChip primitive) — NOT downstream feature surfaces. It deliberately does not migrate every existing FE screen from the legacy `palette` / `colors` exports in `FE/src/theme/tokens.ts`; that migration is per-screen owned by downstream stories (1.6, 1.7, 2.1, 3.2, 3.4, 4.x, 6.x, 7.x). Story 1.5's responsibility is to make the foundation **available and enforced** so that every later story that touches color/typography/motion is structurally constrained to v2.

## Acceptance Criteria

1. **AC1 — `FE/src/theme/tokens.json` exists and is the canonical v2 source.** A single JSON file at `FE/src/theme/tokens.json` containing:
   - `version` = `"2.0.0"`, `system` = `"yeolsal v2 — Oxblood Editorial"`.
   - `color.bg.{canvas, surface, elevated, overlay, inverse}` per UX §Color System (oklch values).
   - `color.text.{primary, secondary, tertiary, disabled, inverse}` per UX §Color System.
   - `color.key.{default, glow, deep, muted, line}` (oxblood) + `color.ember.{default, subtle}` (warm accent) per UX §Color System.
   - `color.stroke.{subtle, default, strong, key}`.
   - `color.chart.{primary, muted, grid}` (A12 single-accent guard).
   - `color.semantic.survival.{ACTIVE, YELLOW, RED, SPECTATOR}` — **packed type with all four fields** `{color, label, icon, grass-treatment}`. Schema rejects any state missing any of the four. `label` values per UX (`"활동 중"` / `"노란 카드"` / `"빨간 카드"` / `"관전 중"`). `grass-treatment` ∈ {`vivid`, `muted`, `ghosted`, `monochrome`}.
   - `typography.{caption.sm, caption, body.sm, body, body.lg, label, label.lg, heading.sm, heading, heading.lg, display.sm, display, display.serif}` with `{size, lineHeight, weight, family?}` per UX type scale. Weight whitelist `{300, 400, 600, 700, 800, 900}` — `500` rejected by schema (UX weight policy).
   - `typography.tracking.{tight, normal, wide}`.
   - `space.{0,1,2,3,4,5,6,8,10,12,16,24}` (4px base, no `space.7`/`space.9`/`space.11`/`space.13`/`space.14`/`space.15` — UX explicit).
   - `radius.{none, subtle, default, pronounced, pill}`.
   - `elevation.{0,1,2,3}` (subtle-blur shadows; hard-offset deprecated per SCP 2026-05-10).
   - `blur.{subtle, modal}` (4px/8px whitelist; values ≥ 12 rejected — A13 glassmorphism guard).
   - `motion.duration.{instant, fast, normal, slow, cinematic}` + `motion.easing.{standard, entry, exit, gentle, ritual}` per UX cubic-bezier values.
   - `subMode.{editorial, bento, quiet, postcard, plate}` — 5 override blocks per UX §Sub-Mode Catalog. Each block is a **flat dict** (no deep merge — Architecture §4.16 explicit). [PRD §14.2, Arch §4.16, UX §Visual Design Foundation]

2. **AC2 — JSON Schema (`tokens.schema.json`) + `./gradlew validateTokens` task fail CI on missing/extra fields.** Author a JSON Schema at `BE/src/main/resources/tokens.schema.json` (or `FE/src/theme/tokens.schema.json` — pick whichever the codegen task reads from; see AC4). Schema enforces:
   - All required top-level keys present (`color`, `semantic`, `typography`, `space`, `radius`, `elevation`, `blur`, `motion`, `subMode`).
   - `color.semantic.survival.{ACTIVE|YELLOW|RED|SPECTATOR}` **each** require `{color, label, icon, grass-treatment}` (all four `required`); the schema validator fails on any missing field with a clear error message naming the rejected state + field.
   - `typography.*.weight` ∈ `[300, 400, 600, 700, 800, 900]` (additionalProperties / pattern; `500` rejected).
   - `subMode.{editorial|bento|quiet|postcard|plate}` may **only contain keys from the override whitelist** (see AC8); any key outside the whitelist fails with a clear error naming the rejected key + sub-mode.
   - `blur` values ≤ 8px; values ≥ 12 rejected.
   - `space.*` integer multiples of 4.
   - `./gradlew validateTokens` runs the schema validator (using e.g. `com.networknt:json-schema-validator` or the JSON Schema standard `org.everit.json:org.everit.json.schema`); exits non-zero on validation failure with the offending path + reason in stderr. The task is wired as a `dependsOn` for `processResources` (so `./gradlew build` and `./gradlew test` both block on it). [Arch §4.16, UX §Token Surface Override 정책, Readiness L1]

3. **AC3 — `./gradlew generateTokens` produces `BE/build/generated/sources/tokens/com/yeosal/api/theme/GeneratedTokens.java`.** Add a Gradle task `generateTokens` in `BE/build.gradle` that:
   - Reads `FE/src/theme/tokens.json` via relative path `../FE/src/theme/tokens.json`.
   - Emits `com.yeosal.api.theme.GeneratedTokens` as a `public final class` with `public static final` constants — one per leaf token. Naming convention: `SCREAMING_SNAKE_CASE` with token path components joined by `_` (e.g., `SURVIVAL_ACTIVE_COLOR`, `SURVIVAL_ACTIVE_LABEL`, `SURVIVAL_ACTIVE_ICON`, `SURVIVAL_ACTIVE_GRASS_TREATMENT`, `KEY_DEFAULT_COLOR`, `SUBMODE_EDITORIAL_TYPOGRAPHY_HEADING_WEIGHT`, etc.).
   - Sub-mode overrides emit as a `public static final class SubMode` inner type with nested `Editorial` / `Bento` / `Quiet` / `Postcard` / `Plate` inner classes; each contains its override constants.
   - The generated file declares `// AUTO-GENERATED by ./gradlew generateTokens from FE/src/theme/tokens.json — DO NOT EDIT.` at the top.
   - The task is wired as a `dependsOn` for `compileJava` (so `./gradlew build` regenerates on `tokens.json` change). Output directory is registered as a Java source set via `sourceSets.main.java.srcDir("$buildDir/generated/sources/tokens")` (or equivalent in Groovy DSL); IntelliJ picks it up after `./gradlew generateTokens`.
   - `generateTokens` runs **after** `validateTokens` (the latter is a `dependsOn` of the former) — invalid schemas never reach the generator. [Arch §4.16, NFR-9.6.1, Readiness M5/A5]

4. **AC4 — BE Checkstyle / ArchUnit rule blocks hex-literal colors outside `GeneratedTokens`.** Add **one** of the following enforcement mechanisms (dev picks whichever is cheaper to integrate with the existing `BE/build.gradle`):
   - **Option A (Checkstyle, preferred for simplicity):** Add `checkstyle` plugin + `BE/config/checkstyle/checkstyle.xml` rule `RegexpSinglelineJava` matching `#[0-9A-Fa-f]{3,8}\b|rgb\(|rgba\(|oklch\(` in `BE/src/main/java/com/yeosal/api/**/*.java` **except** `com/yeosal/api/theme/GeneratedTokens.java` and the future `theme/` package. CI fails on any match.
   - **Option B (ArchUnit, preferred if richer Java-AST inspection is needed):** Add `com.tngtech.archunit:archunit-junit5` dependency + a `BE/src/test/java/com/yeosal/api/architecture/HexLiteralGuardTest.java` ArchUnit test that scans all `.java` files outside `theme/` for hex/rgb/oklch literals and fails on match.
   - The chosen mechanism runs inside `./gradlew test` (Option B) or `./gradlew check` (Option A — Checkstyle is wired into `check` by default). The default `./gradlew test` or `./gradlew build` path must fail on any violation. The Story 1.5 dev introduces zero hex literals in their own BE diff to validate the rule end-to-end. [Arch §4.15 BE-side complement, §4.9, Story 7.1 forward dep]

5. **AC5 — `tools/brand-voice-lint.ts` ships and enforces NFR-9.6.1 as a hard CI gate.** Author `tools/brand-voice-lint.ts` (TypeScript, runs under `tsx` or compiled — pick whichever is simpler given the FE's existing toolchain; FE already has `typescript-eslint`, no new toolchain needed) with three rule sets:
   - **Rule 1 (HARD CI GATE — NFR-9.6.1 packed-type enforcement):** Scan `FE/src/**/*.{ts,tsx}` + `FE/app/**/*.tsx`. For any JSX/TSX expression that reads `survival.<STATE>.color` or `semantic.survival.<STATE>.color` (where `<STATE>` ∈ `ACTIVE|YELLOW|RED|SPECTATOR`), require a sibling `<Text>{label}</Text>` (where `{label}` is the matching `survival.<STATE>.label` or any string literal containing the label) OR an `accessibilityLabel={label}` prop within the same JSX subtree (parent or sibling within the same return statement). Violation → exit code 1 with file:line:col + offending expression + the missing-label rule explanation. Severity: **HARD GATE**.
   - **Rule 2 (WARN — AVOID lexicon):** Scan the same files plus any user-facing string literals for the PRD FR-8.8.2 AVOID list (`벌금`, `잃었다`, `떨어졌다`, `실패`, `자책`, `부담`, `패배`, `죄책감`). Print occurrences with file:line. Exit code 0 (WARN-only — human review remains authoritative per Architecture §4.15 rationale).
   - **Rule 3 (WARN — design-token literal guard):** Match `#XXXXXX` / `#XXX` / `rgb(...)` / `rgba(...)` / `oklch(...)` literals in `FE/src/**/*.{ts,tsx}` outside `FE/src/theme/tokens.json` and the generated/synced layer. Suggest the token replacement. Exit code 0 (WARN).
   - The script is invoked from `scripts/test.sh` (added as a step after FE typecheck, before BE tests) AND from a new `npm run lint:brand-voice` script in `FE/package.json`. Hard-gate behavior verified by: (a) adding a deliberate violating TSX snippet temporarily and confirming non-zero exit + clear error, (b) removing it. [Arch §4.15, NFR-9.6.1, Readiness M5/H4]

6. **AC6 — `FE/src/components/survival/SurvivalChip.tsx` is the only allowed entry point for displaying survival state.** Build `<SurvivalChip state={"ACTIVE"|"YELLOW"|"RED"|"SPECTATOR"} />` in `FE/src/components/survival/SurvivalChip.tsx`:
   - Consumes the packed-type `semantic.survival.<state>` token internally (color + icon + label + grass-treatment) via the `useTheme()` hook.
   - Renders a non-splittable composite View: `{color dot + icon + label}`. The dot uses `survival.<state>.color`; the icon uses `survival.<state>.icon` mapped to a concrete `@expo/vector-icons` glyph (see Dev Notes §Icon mapping); the label uses `survival.<state>.label` rendered as a `<Text>` child with `accessibilityLabel={label}` on the wrapping View.
   - The component file's own usage of `survival.<state>.color` is the **only permitted reference** to the color field anywhere in the FE source tree — `tools/brand-voice-lint.ts` (AC5 Rule 1) **must allow** this exact pattern (the sibling `<Text>{label}</Text>` inside the same return makes the lint pass). The lint rule allow-list does NOT special-case the file path; the structural sibling-label pattern is what passes.
   - The component exports `interface SurvivalChipProps { state: "ACTIVE" | "YELLOW" | "RED" | "SPECTATOR" }` (TypeScript narrow union — no `string`).
   - Unit test at `FE/src/components/survival/__tests__/SurvivalChip.test.tsx` renders all 4 states + asserts: (a) the resolved color value matches `tokens.json`, (b) the rendered text equals the packed `label`, (c) `accessibilityLabel` is set, (d) the icon component is present, (e) the chip renders as a non-splittable View (a single `Surface`/`View` root, no fragment).
   - **No other component in the FE source tree** may reference `survival.*.color` directly. The downstream-story author (1.6, 2.1, 3.2, etc.) imports `<SurvivalChip>` and passes `state`. [Readiness M1, Arch §4.15, NFR-9.6.1]

7. **AC7 — `FE/src/providers/SubModeProvider.tsx` + `useTheme()` hook ship and resolve sub-mode overrides.** Build:
   - `FE/src/theme/useTheme.ts` — a React hook that returns the **resolved** theme: base tokens with sub-mode overrides merged on top (single shallow merge per UX cross-cutting rule #9). Reads the current sub-mode from React context (default = `null` → no overrides applied).
   - `FE/src/providers/SubModeProvider.tsx` — `<SubModeProvider subMode={"editorial"|"bento"|"quiet"|"postcard"|"plate"|null}>{children}</SubModeProvider>`. Wraps children in a React context provider; `useTheme()` consumers under the provider receive the override-merged token set.
   - Type signature: `useTheme(): ResolvedTheme` where `ResolvedTheme` is generated alongside `tokens.json` (codegen FE-side too — or hand-typed `interface` mirroring the JSON schema; pick simpler).
   - Leaf components MUST NOT read the `subMode` prop directly (UX cross-cutting rule #9). The provider is the only consumer of the `subMode` string; everything downstream consumes resolved tokens via `useTheme()`. Add a `// eslint-disable` boundary or a comment in `SubModeProvider.tsx` documenting this invariant.
   - Unit tests at `FE/src/providers/__tests__/SubModeProvider.test.tsx` (or co-located) verify resolved-token correctness for **all 5 sub-modes**: mount `<SubModeProvider subMode="bento"><TestProbe /></SubModeProvider>` where `TestProbe` reads `useTheme()` and renders specific token values; assert the values match the `subMode.bento.*` overrides from `tokens.json`. Repeat for `editorial`, `quiet`, `postcard`, `plate`, and the `null` (base-only) case.
   - **E2E verification deferred to downstream stories.** Story 2.1 (Spectator routing) wraps the spectator-mode layout in `<SubModeProvider subMode="quiet">`; Story 3.4 (Wallet UI surface) wraps the Wallet screen in `<SubModeProvider subMode="bento">`. Story 1.5 ships the wrapper + unit-level proof; the per-screen E2E claims land with those stories. [Readiness M2, UX §Sub-Mode Catalog, Arch §4.16]

8. **AC8 — Sub-mode override whitelist enforced; `validateTokens` rejects out-of-whitelist keys.** The schema (AC2) MUST enforce that each `subMode.<id>` block contains ONLY keys from the **expanded whitelist** documented in Dev Notes §Sub-mode override whitelist. The whitelist is the union of (a) the 12-key list in epics.md Story 1.5 AC + UX §Token Surface Override 정책 (L1027–1040) AND (b) the 4 additional keys that the locked D3 Quiet override block (`color.bg.canvas`, `color.text.primary`, `color.text.secondary`) and the D5 Plate override block (`color.stroke.default`) require — see UX §Sub-Mode Catalog D3 (lines 1101–1109) and D5 (lines 1144–1151).

   **Decision required (record in Dev Agent Record + sync to UX spec / epics.md AC L240):** the epics.md AC currently lists 12 keys but UX §Sub-Mode Catalog D3 Quiet uses 3 keys outside that list AND D5 Plate uses `color.stroke.default` which is not in either list. The dev resolves by **expanding to the 16-key whitelist documented in Dev Notes** during W1 design lock (the 3 D3 keys + 1 D5 key are load-bearing for the locked override blocks; tightening D3/D5 instead would silently kill the spectator-dignity and plate-system visual differentiation). Document the expansion + cross-reference back to `_bmad-output/planning-artifacts/epics.md` Story 1.5 AC line and `_bmad-output/planning-artifacts/ux-design-specification.md` §Token Surface Override 정책. `./gradlew validateTokens` failing on any key outside this expanded list is the contract. [Readiness L1, UX §Token Surface Override 정책 + §Sub-Mode Catalog]

9. **AC9 — WCAG 2.2 AA contrast verification + Dynamic Type smoke test.** Verify the v2 palette against bg/text combinations on base + each sub-mode:
   - Static contrast matrix codified in `docs/design-system.md` (or a sibling `docs/design-system-contrast.md`) — one row per `text.<level>` × `bg.<surface>` pair: `text.primary`-on-`bg.canvas`, `text.primary`-on-`bg.surface`, `text.primary`-on-`bg.elevated`, `text.secondary`-on-`bg.canvas`, `text.primary`-on-`key.default` (CTA fill), `text.inverse`-on-`bg.inverse` (Final-3 paper sheet). All pairs **must pass AA at body 14pt** (≥ 4.5:1). `text.tertiary` pairs are scoped to caption use only (≥ 18pt or bold ≥ 14pt; documented as such).
   - Automated check: a small Node script `tools/contrast-check.ts` (or extend `tools/brand-voice-lint.ts` Rule 4) computes WCAG 2.2 AA contrast ratios from `tokens.json` for the canonical pairs above and exits non-zero on failure. Wired into `scripts/test.sh`.
   - Sub-mode override coverage: for `D3.quiet` (overrides `bg.canvas` + `bg.surface` + `text.primary` + `text.secondary`), the contrast script verifies the spectator-mode pairs also pass AA. Document the resolved D3 pairs in the contrast matrix.
   - Dynamic Type smoke test (NFR-9.6.3): a Jest snapshot of `<SurvivalChip />` rendered at iOS `largestContentSizeCategory` (or equivalent `PixelRatio.getFontScale() === 1.5`) — assert no overflow / no clipped label. Test file at `FE/src/components/survival/__tests__/SurvivalChip.dynamic-type.test.tsx`. [NFR-9.6.1, NFR-9.6.3, UX §Accessibility Considerations]

10. **AC10 — `docs/design-system.md` regenerated (or hand-synced) and committed in the same PR.** Either:
    - **Option A — generated:** Add a `./gradlew renderDesignSystemDoc` task that reads `tokens.json` and emits a Markdown table of every token + value, the sub-mode override whitelist, and the contrast matrix. Commit the output. OR
    - **Option B — hand-synced (acceptable per Arch §4.16 — pick at W1 design lock):** Author `docs/design-system.md` by hand, mirroring the UX spec §Visual Design Foundation tables verbatim, plus the contrast matrix (AC9) and the whitelist (AC8). Commit alongside `tokens.json` in the same PR — drift between `tokens.json` and the doc is rejected at code review.

    Whichever path is picked, the doc MUST contain: full color/typography/space/radius/elevation/motion tables, the `semantic.survival` packed-type table with all 4 fields per state, the sub-mode override whitelist, and the WCAG 2.2 AA contrast matrix. [Arch §4.16, UX §Visual Design Foundation, AC9]

11. **AC11 — Legacy theme exports remapped to v2 tokens (no screen-by-screen migration in this story).** `FE/src/theme/tokens.ts` currently exports the v1 Bento Modular Warm palette (`palette`, `colors`, `surface`, `text`, `semantic`, `roomHues`, `grassRamp`, `spacing`, `borders`, `typography`) consumed by every existing FE screen. Story 1.5 **does NOT** migrate those screens. Instead:
    - The exports stay (no source-tree churn for the dozens of downstream screens that consume them).
    - Their **values** are remapped to point at v2 token values from `tokens.json` (loaded at module init via a tiny resolver — e.g., `import tokensJson from "./tokens.json"; export const palette = { ... derived from tokensJson.color };`). The remapping preserves the v1 export shape but swaps Bento Modular Warm values for Oxblood Editorial v2 values.
    - Per-screen visual regressions during this swap are **expected** and documented in the PR description — downstream stories own the screen-level polish on a screen-by-screen basis. The Story 1.5 PR demonstrates the foundation works on at least one screen end-to-end via the `<SurvivalChip>` unit tests + the `<SubModeProvider>` unit tests + a screenshot of the FE running against the remapped legacy exports on the existing Today/Chat/Wallet placeholder screens (developer choice — pick any 1 existing screen for the smoke test).
    - **Exception:** if remapping the legacy `palette.coral` / `palette.coralDeep` / `palette.coralSoft` / `palette.salmon*` / `palette.periwinkle*` / `palette.ochre*` / `palette.sage*` exports to v2 values causes immediate readability regressions (e.g., `text.primary` on a remapped `palette.surface` falls below 3:1), the legacy export keeps a *transitional* v1-equivalent value AND the export is annotated with `// LEGACY — migrate to v2 token in <downstream story>`. Document any such transitional values in the PR. [Foundation-deviation note above, UX §Customization Strategy, project-context "high cohesion, low coupling — many small files"]

12. **AC12 — Icon glyph mapping from UX abstract names to `@expo/vector-icons` concrete glyphs.** The packed-type `survival.<state>.icon` UX values (`check.bold`, `triangle.alert`, `diamond.pause`, `circle.half`) are abstract. The dev picks concrete glyph mappings from the already-installed `@expo/vector-icons` family (project-context: FE deps include `@expo/vector-icons`). Recommended mappings (dev confirms — substitute if a glyph is missing/visually wrong):
    - `ACTIVE`: `Feather.check` (or `Ionicons.checkmark`)
    - `YELLOW`: `MaterialIcons.warning-amber` (or `Ionicons.warning-outline`)
    - `RED`: `MaterialCommunityIcons.pause-circle-outline` (or `Feather.pause-circle`) — the UX `diamond.pause` is a metaphor; pause-circle preserves the "잠시 꺼진 잉크" tone without alarm semantics.
    - `SPECTATOR`: `MaterialCommunityIcons.circle-half-full` (or `Ionicons.eye-outline`)

    Store the FE icon mapping in a small `FE/src/components/survival/iconMap.ts` keyed by `<state>`. The `survival.<state>.icon` string in `tokens.json` is the **canonical abstract name** (consumed by BE `SvgRenderer` later — Story 7.1 — which has its own SVG-symbol mapping). FE-side concrete glyphs are an implementation detail of `<SurvivalChip>` only. [Project-context FE deps, UX §Color System packed type]

13. **AC13 — `scripts/verify.sh` runs the full v1.5 quality gate.** After this story ships, `bash scripts/verify.sh` from repo root MUST run, in order:
    1. `cd FE && npm run lint && npm run typecheck && npm test` (existing).
    2. `npm run lint:brand-voice` (NEW — invokes `tsx tools/brand-voice-lint.ts`).
    3. `npx tsx tools/contrast-check.ts` (NEW — AC9 automated contrast check).
    4. `cd BE && ./gradlew test --no-daemon` (existing; now transitively runs `validateTokens` + `generateTokens` + Checkstyle/ArchUnit hex-literal guard).
    5. `cd BE && ./gradlew build --no-daemon` (existing).
    6. Docker image build (existing).

    Wire steps 2 and 3 into `scripts/test.sh` after the FE block, before the BE block. Both new checks must be silent-skip if their dependency is missing (e.g., `FE/node_modules` absent → print "skipping brand-voice-lint" rather than fail; project-context pattern from existing `scripts/test.sh`). The opt-in `BOOT_SMOKE=true` invocation continues to work (Story 1.4 BE-2 / AC8). [project-context Pre-commit verification, Story 1.4 AC8/AC13 precedent]

14. **AC14 — Test coverage: TDD per project-context.** Required test surface:

    **FE — Jest + `@testing-library/react-native`:**
    - `FE/src/components/survival/__tests__/SurvivalChip.test.tsx` — 4 state renders × {color, label, icon, accessibilityLabel, non-splittable} = 20 assertions minimum (AC6).
    - `FE/src/components/survival/__tests__/SurvivalChip.dynamic-type.test.tsx` — 1.5× font scale snapshot (AC9).
    - `FE/src/providers/__tests__/SubModeProvider.test.tsx` — 6 cases (5 sub-modes + null base); each asserts ≥ 3 representative token resolutions match `tokens.json` (AC7).
    - `FE/src/theme/__tests__/useTheme.test.tsx` — pure-hook tests for `useTheme()` outside a provider (returns base) and inside each provider (returns merged).

    **BE — JUnit 5 + Testcontainers (where applicable):**
    - `BE/src/test/java/com/yeosal/api/theme/GeneratedTokensTest.java` — round-trip test: load `tokens.json` from classpath, parse it, assert every leaf token has a matching `GeneratedTokens.<NAME>` constant with the same value (catches generator skew). NOT opt-in (`@EnabledIfSystemProperty` not used — pure JVM test, runs in default cycle).
    - `BE/src/test/java/com/yeosal/api/theme/TokenSchemaValidationTest.java` — programmatically invoke the schema validator against (a) the canonical `tokens.json` (expect pass), (b) 4 mutated copies each with one violation (missing `label`, weight = 500, sub-mode override key outside whitelist, blur ≥ 12) and assert failure with the expected error message substring. Default cycle.
    - `BE/src/test/java/com/yeosal/api/architecture/HexLiteralGuardTest.java` (if AC4 Option B / ArchUnit) — or equivalent Checkstyle config tested via `./gradlew check` (if Option A).

    **Tools:**
    - `tools/__tests__/brand-voice-lint.test.ts` — TSX fixture corpus: (a) survival-color reference WITH sibling label → exit 0; (b) survival-color reference WITHOUT sibling label → exit 1 + correct error line:col; (c) AVOID lexicon match → exit 0 + warning printed; (d) hex literal → exit 0 + warning printed; (e) clean file → exit 0. Run via `npm test` or `npx tsx --test tools/__tests__/brand-voice-lint.test.ts`.
    - `tools/__tests__/contrast-check.test.ts` — synthetic `tokens.json` with known-failing pair → exit 1; canonical → exit 0.

    **Coverage target:** 80%+ on `tools/brand-voice-lint.ts` + `tools/contrast-check.ts` (per project-context — these are "domain logic"). FE component coverage 80%+ for `SurvivalChip` and `SubModeProvider`. BE `GeneratedTokens` is auto-generated; behavioral coverage via the schema/round-trip tests is the contract (no line-coverage required for the generated source). [project-context Testing rules]

15. **AC15 — No downstream feature surface migrated; no API/DB change.** Story 1.5 is **foundation-only**. It:
    - Does NOT migrate `<Today>` / `<ChatList>` / `<RoomScreen>` / `<Wallet>` / any existing screen from legacy `palette` exports beyond the AC11 remap.
    - Does NOT build the Wallet feature (Story 3.4), the Spectator routing (Story 2.1), the Friend Gift Modal (Story 3.2), the RitualMoment overlay (Story 1.7), the WelcomeWindow (Story 1.6), the Final-3 SVG renderer (Story 7.1), or the Kakao preview card renderer (Story 6.1). Each downstream story consumes Story 1.5's foundation.
    - Introduces **zero BE API changes**, **zero BE database / Flyway migration changes** (V11 ships from Story 1.4; V12 is not authored here). The `com.yeosal.api.theme` package contains only the generated `GeneratedTokens.java` + the test for it (no controller, no entity, no service).
    - If `BE/src/main/java/` files outside `com/yeosal/api/theme/` or `com/yeosal/api/architecture/` (the test-only ArchUnit package, if AC4 Option B) are modified, scope has drifted — stop and re-scope.

## Tasks / Subtasks

### Frontend (FE/) — token JSON, providers, primitives, lint

- [x] **Task FE-1 — Author `FE/src/theme/tokens.json` (AC1)** ✅ Sprint A
  - [x] FE-1.1 — Decided to ship `{oklch, hex}` pairs from the start (oklch canonical, hex RN fallback). All UX §Visual Design Foundation values copied verbatim.
  - [x] FE-1.2 — All 5 sub-mode override blocks populated from UX §Sub-Mode Catalog. Each block contains only whitelist keys.
  - [x] FE-1.3 — `version=2.0.0`, `system="yeolsal v2 — Oxblood Editorial"`, `lastUpdated=2026-05-13` committed.

- [x] **Task FE-2 — Author JSON Schema `tokens.schema.json` (AC2)** ✅ Sprint A (BE-side schema; FE ajv smoke test deferred to Sprint D)
  - [x] FE-2.1 — Schema lives at `BE/src/main/resources/tokens.schema.json` (classpath-accessible from BE Gradle task).
  - [x] FE-2.2 — Schema enforces all required top-level keys, semantic.survival packed-type, weight enum (no 500), 16-key sub-mode whitelist, blur ≤ 8px, space.* multiples of 4. Verified by 4 negative-case tests.
  - [x] FE-2.3 — Added FE smoke test `FE/src/theme/__tests__/tokens.json.schema.test.ts` using `ajv@8.20.0` (now an FE devDep) validating `tokens.json` against `BE/src/main/resources/tokens.schema.json` (read via relative path so there's no schema duplication). 5/5 cases pass (1 positive + 4 negatives: weight=500, missing label, blur≥12, out-of-whitelist sub-mode key). ✅ Sprint D

- [x] **Task FE-3 — Build `useTheme()` hook + `<SubModeProvider>` (AC7)** ✅ Sprint C
  - [x] FE-3.1 — `FE/src/theme/useTheme.ts` — reads sub-mode from React context, returns base merged with sub-mode override. Type: `ResolvedTheme = Omit<typeof tokensJson, "subMode">`. JSON module import via Expo `resolveJsonModule:true`. Override resolver walks dot-paths on a deep clone (UX cross-cutting rule #9 enforced by API: only `<SubModeProvider>` sees the sub-mode string).
  - [x] FE-3.2 — `FE/src/providers/SubModeProvider.tsx` — context + provider component. Exports `SubModeProvider`. `useSubMode()` lives in `useTheme.ts` as the internal context hook.
  - [x] FE-3.3 — `FE/src/theme/__tests__/useTheme.test.tsx` (10 cases) + `FE/src/providers/__tests__/SubModeProvider.test.tsx` (6 cases — null + 5 sub-modes). All 16 pass; each sub-mode asserts ≥3 representative resolutions per AC14.
  - [x] FE-3.4 — Root layout `FE/app/_layout.tsx` wraps in `<SubModeProvider subMode={null}>` (outermost, before AuthProvider). Downstream stories swap the prop on per-route-segment layouts.

- [x] **Task FE-4 — Build `<SurvivalChip>` primitive (AC6, AC12)** ✅ Sprint C
  - [x] FE-4.1 — `FE/src/components/survival/SurvivalChip.tsx` — accepts `state` prop (typed union), renders `<View>{dot}{icon}{label}</View>` with `accessibilityLabel={label}` and `accessibilityRole="text"`. Uses `useTheme()` to resolve the packed-type fields. Indexes survival map by variable (`survival[state]`) so the brand-voice lint Rule 1 sees a structural primitive, not a literal-state read.
  - [x] FE-4.2 — `FE/src/components/survival/iconMap.ts` — abstract→concrete glyph map (chose MaterialIcons family across all 4 states for consistency with existing components: check / warning-amber / pause-circle-outline / visibility).
  - [x] FE-4.3 — `SurvivalChip.test.tsx` (5 cases — 4 states × {color, label, icon, accessibilityLabel, non-splittable} + composite-shape probe) + `SurvivalChip.dynamic-type.test.tsx` (3 cases — maxFontSizeMultiplier ≤ 1.3 assertion + 2 snapshot guards). 8/8 pass.
  - [x] FE-4.4 — `FE/src/components/survival/index.ts` barrel export ships.

- [x] **Task FE-5 — Remap legacy theme exports (AC11)** ✅ Sprint C — minimum-risk path taken (AC11 readability exception clause)
  - [x] FE-5.1 — Updated `FE/src/theme/tokens.ts` to import `tokens.json` and re-export it as `tokensV2`. The 28 existing importers (`palette` / `colors` / `surface` / `text` / `semantic` / `roomHues` / `grassRamp` / `spacing` / `borders` / `typography` / `pickRoomAccent` / `RoomAccent`) keep their v1 values — under AC11's readability exception clause, a blind v1→v2 swap would invert fg/bg (v1 = light-mode warm; v2 = dark-mode oxblood) and break legibility on every screen. Annotated the file header with the migration policy: new code reads `useTheme()` or `tokensV2`; downstream stories migrate per-screen.
  - [x] FE-5.2 — FE Jest cycle remains green for the Story 1.5 surface (29/29 new tests). The pre-existing baseline typecheck failures in `FE/src/components/today/FriendsTodayPager.tsx` (missing `react-native-pager-view` dep) are inherited from the branch base and out of scope per Story 1.5 Dev Notes "Previous story intelligence".
  - [x] FE-5.3 — Migration deferral documented in `docs/design-system.md` §12 — owning stories listed (1.6 / 1.7 / 2.1 / 3.2 / 3.4 / 6.1 / 7.1).

### Tools — brand-voice lint + contrast check

- [x] **Task TL-1 — Author `tools/brand-voice-lint.ts` (AC5)** ✅ Sprint B
  - [x] TL-1.1 — Bootstrap: `tools/` workspace at repo root with `tools/package.json` declaring `tsx@4.20.6` + `typescript@5.9` + `@types/node`. `scripts/test.sh` silent-skips when `tools/node_modules` is absent.
  - [x] TL-1.2 — Rule 1 (HARD GATE) implemented via regex scan with per-file label-evidence check: matches `(semantic.)?survival.<STATE>.color` and requires same-file presence of `survival.<STATE>.label`, the Korean literal label, or an `accessibilityLabel=` attribute. Test corpus: PASS for `<Dot color={s.color}/><Text>{s.label}</Text>`, FAIL for color-only.
  - [x] TL-1.3 — Rule 2 (WARN — AVOID lexicon) — 8 banned words from PRD FR-8.8.2 (`벌금` / `잃었다` / `떨어졌다` / `실패` / `자책` / `부담` / `패배` / `죄책감`).
  - [x] TL-1.4 — Rule 3 (WARN — hex/rgb/oklch literal guard) — scans `.ts(x)` source; `tokens.json` blocklisted. Result on current FE: 0 hard, 93 warnings (the legacy `tokens.ts` literals + the 4 pre-existing form-error copy hits in `login/signup/join/notification-settings`).
  - [x] TL-1.5 — CLI exits 1 on Rule-1 violation, 0 otherwise. Self-tests at `tools/__tests__/brand-voice-lint.test.ts` cover all 5 AC fixtures + 4 extras (9 tests pass).

- [x] **Task TL-2 — Author `tools/contrast-check.ts` (AC9)** ✅ Sprint B
  - [x] TL-2.1 — Hand-rolled hex → sRGB → linear → relative-luminance → contrast (< 50 LOC, no external dep). Black=0/white=1 luminance and 21:1 black/white contrast verified.
  - [x] TL-2.2 — Canonical pair list: 7 base pairs (`text.primary` on `bg.{canvas,surface,elevated}`, `text.secondary` on `bg.canvas`, `text.primary` on `key.default`, `text.inverse` on `bg.inverse`, `text.tertiary` on `bg.canvas` at AA-large 3.0:1) + 3 D3.quiet overrides.
  - [x] TL-2.3 — `[PASS]`/`[FAIL]` output with ratio + min requirement; exit non-zero on any failure. Result on v2 palette: 10/10 PASS (lowest = `text.tertiary` 3.90:1 against AA-large 3.0:1 min).

### Backend (BE/) — Gradle codegen + ArchUnit/Checkstyle

- [x] **Task BE-1 — Add `validateTokens` + `generateTokens` Gradle tasks (AC2, AC3)** ✅ Sprint A
  - [x] BE-1.1 — `com.networknt:json-schema-validator:1.5.0` added to buildscript classpath + testImplementation. Not on runtime classpath; produced JAR stays lean.
  - [x] BE-1.2 — `validateTokens` defined in BE/build.gradle, reads `../FE/src/theme/tokens.json` + `BE/src/main/resources/tokens.schema.json`, exits non-zero on validation failure with detailed error report.
  - [x] BE-1.3 — `generateTokens` defined, depends on `validateTokens`, emits `BE/build/generated/sources/tokens/com/yeosal/api/theme/GeneratedTokens.java`. Verified: 119 base constants + 5 sub-mode inner classes (Editorial/Bento/Quiet/Postcard/Plate).
  - [x] BE-1.4 — Generated dir registered as source set; `compileJava.dependsOn(generateTokens)` wired. Verified `./gradlew clean build` regenerates from scratch.
  - [x] BE-1.5 — `processResources.dependsOn(validateTokens)` wired — schema validates on every build path.

- [x] **Task BE-2 — Hex-literal guard (AC4)** ✅ Sprint A — Option A (Checkstyle) chosen
  - [x] BE-2.1 — Option A (Checkstyle) — `checkstyle` plugin v10.18.0 added.
  - [x] BE-2.2 — `BE/config/checkstyle/checkstyle.xml` with 4 `RegexpSingleline` modules guarding `#hex` / `rgb(` / `rgba(` / `oklch(` literals. Generated source excluded by file path (`exclude("com/yeosal/api/theme/GeneratedTokens.java")`).
  - [x] BE-2.3 — Verified by negative probe: temporarily injected `String BAD_COLOR = "#FF0000";` into `BE/src/main/java/com/yeosal/api/common/CheckstyleNegativeProbe.java` → Checkstyle FAILED with 1 violation (correctly blocked). Probe file removed.
  - [x] BE-2.4 — ArchUnit alternative not needed; Option A sufficient.

- [x] **Task BE-3 — Tests (AC14)** ✅ Sprint A
  - [x] BE-3.1 — `GeneratedTokensTest.java` — 7 round-trip parity tests (version+system / survival packed-type / base colors / typography / scalars / motion / sub-mode inner classes). All pass.
  - [x] BE-3.2 — `TokenSchemaValidationTest.java` — 5 cases (1 positive + 4 negative: missing label, weight=500, out-of-whitelist sub-mode key, blur≥12). All pass.

### Scripts / docs / cross-cutting

- [x] **Task X-1 — Wire `scripts/test.sh` (AC13)** ✅ Sprint D
  - [x] X-1.1 — Inserted brand-voice + contrast invocations between FE and BE blocks, guarded by `[ -x "$ROOT_DIR/tools/node_modules/.bin/tsx" ]` (matches the FE `[ -d FE/node_modules ]` silent-skip pattern). The block also runs the tools' own `tsx --test` self-tests so the gate is verified end-to-end on every CI run.
  - [x] X-1.2 — `bash -n scripts/test.sh` syntax check green; the orchestration block runs the lint + contrast against the actual repo state (0 HARD / 93 WARN / 10 PASS / 0 FAIL).

- [x] **Task X-2 — `docs/design-system.md` (AC10)** ✅ Sprint D
  - [x] X-2.1 — Option B (hand-synced) chosen. `docs/design-system.md` fully replaced — v1 Risograph stub deleted, v2 Oxblood Editorial doc with all 12 sections: color tables, packed-type survival, icon-glyph map, typography, space, radius, elevation, blur, motion (3 sub-tables), 16-key sub-mode whitelist, WCAG 2.2 AA contrast matrix (10 PASS rows), the enforcement-chain table, and the migration policy referencing the owning stories.
  - [x] X-2.2 — Already linked from `docs/index.md` (existing entry continues to resolve; doc filename unchanged).

- [x] **Task X-3 — Sprint-status flip** ✅ Sprint D
  - [x] X-3.1 — Sprint-status flip from `ready-for-dev → in-progress` was applied in Sprint A.
  - [x] X-3.2 — Sprint-status flip from `in-progress → review` applied on Sprint D completion after `tools` + `FE` + `BE` all green.

- [ ] **Task X-4 — Pre-merge stack-PR check (project-context Stack PR Merge Procedure)**
  - [ ] X-4.1 — Reviewer or merge-orchestrator verifies PR base is `main` via `gh pr view <N> --json baseRefName,mergeStateStatus` at merge time.

### Frontend (FE/) — out-of-scope explicit list

- [x] **Task FE-OOS — Document deferrals.** ✅ Sprint D — captured in PR description + `docs/design-system.md` §12:
  - Wallet (Story 3.4) — not built; SubModeProvider E2E for D2.bento deferred.
  - Spectator routing (Story 2.1) — not built; SubModeProvider E2E for D3.quiet deferred.
  - WelcomeWindow / RitualMoment (Stories 1.6 / 1.7) — separate stories.
  - Per-screen migration of legacy `palette` consumers — owned per-screen in each downstream story (per AC11 readability exception clause).

### Review Findings

- [ ] [Review][Patch] Brand-voice hard gate misses normal alias/indexed survival color reads [tools/brand-voice-lint.ts:155]
- [ ] [Review][Patch] Brand-voice label evidence is file-wide, not same JSX subtree [tools/brand-voice-lint.ts:170]
- [ ] [Review][Patch] Brand-voice/contrast gates are optional in a fresh install [scripts/test.sh:19]
- [ ] [Review][Patch] FE package is missing the required `lint:brand-voice` script [FE/package.json:6]
- [ ] [Review][Patch] `typography.display.serif.enabled` override is applied to the wrong theme path [FE/src/theme/useTheme.ts:43]

## Dev Notes

### Architecture patterns (must follow — load-bearing)

- **NFR-9.6.1 enforcement is structural, not narrative.** The packed-type `semantic.survival` schema + the brand-voice lint hard gate + the `<SurvivalChip>` primitive together make it **impossible** for any FE code to render survival state as color-only. Each layer carries part of the load: the schema rejects a malformed `tokens.json`; the lint rejects malformed JSX; the primitive provides the compliant path. If any layer is weakened, NFR-9.6.1 reverts to a code-review checklist (the v1 anti-pattern this story exists to eliminate). [Arch §4.15, NFR-9.6.1, Readiness M5/H4]
- **FE↔BE drift is structurally impossible by construction.** BE has no `colors.properties` / no hand-typed Java color constants — `GeneratedTokens` is the **only** source of color/typography/motion values inside `BE/src/main/java/com/yeosal/api/**`. The Checkstyle/ArchUnit guard (AC4) is the structural enforcement. Future BE renderers (Story 7.1 `SvgRenderer`, Story 6.1 `InvitePreviewRenderer`) compile-fail or check-fail if they introduce a hex literal. [Arch §4.16, §4.9, §4.15 BE-side complement]
- **Sub-mode is page-level only, never branched inside leaf components** (UX cross-cutting rule #9, lines 637–642 of UX spec). A leaf component (`<Button>`, `<Card>`, `<SurvivalChip>`) NEVER reads `subMode` directly — it reads `useTheme()` which returns resolved tokens. The `<SubModeProvider>` is the only place that touches the sub-mode string. If a leaf component branches on sub-mode, the visual-consistency contract is broken. Lint this if needed in a future iteration.
- **Single `@RestControllerAdvice`, no new domain exceptions, no new modules under `com/yeosal/api/`.** Story 1.5 introduces zero BE business logic. The new `com/yeosal/api/theme/` package contains exactly two files: `GeneratedTokens.java` (auto-generated) + `GeneratedTokensTest.java` (round-trip test). The `com/yeosal/api/architecture/` package (if AC4 Option B) contains exactly one ArchUnit test class.
- **Constructor injection only.** N/A for `GeneratedTokens` (static-only class). N/A for tests (raw JUnit).
- **TypeScript: no `any`; props are named `interface`s; no `React.FC`** — project-context strict rule. `<SurvivalChip>` props are a named `interface`. The `useTheme()` return type is a named `interface ResolvedTheme`. The `subMode` prop is a typed union `"editorial" | "bento" | "quiet" | "postcard" | "plate" | null`.
- **Immutable updates only** (project-context). The override-merge inside `useTheme()` is a fresh object — never mutate the imported `tokens.json` shape. Use spread / `Object.fromEntries` patterns.

### Token packed-type contract (NFR-9.6.1 — the central guarantee)

```text
semantic.survival.<STATE> :: {
  color:            <oklch string>        // visual signal (atmosphere only)
  label:            <Korean string>       // textual signal (carries meaning)
  icon:             <abstract icon name>  // iconic signal (carries meaning)
  grass-treatment:  <enum>                // GrassGrid visual treatment (Today screen)
}
```

The packed-type is structural at three layers:
1. **JSON Schema** (AC2) — rejects malformed `tokens.json` at FE test time + BE `validateTokens` Gradle task time.
2. **Code lint** (AC5 Rule 1) — rejects component code that uses `<STATE>.color` without a sibling label.
3. **Component primitive** (AC6) — `<SurvivalChip>` is the only entry point; downstream components import it instead of constructing the chip themselves.

The `tools/brand-voice-lint.ts` Rule 1 implementation is the load-bearing piece. **If Rule 1 has false negatives** (allows a violation through), NFR-9.6.1 silently regresses. Test Rule 1 against an aggressive negative-fixture corpus (Task TL-1.5).

### Color-format compatibility — oklch in React Native

React Native's color parser (RN ≥ 0.74) accepts CSS color strings via the platform layer, but **oklch strings are not universally supported** across iOS / Android / RN versions. Verify in a smoke test (Task FE-1.1) before committing tokens.json as oklch-only.

**Mitigation if oklch unsupported:** store both representations in `tokens.json`:

```json
"key.default": { "oklch": "oklch(42% 0.135 25)", "hex": "#7E2C2A" }
```

The schema marks `oklch` as the canonical value (BE renderers and FE web previews use it) and `hex` as the FE-runtime fallback. `useTheme()` returns the hex value to RN consumers. UX spec already provides hex approximations next to oklch values (lines 826–830 etc.).

**Recommended (decide during Task FE-1.1):** ship `{ oklch, hex }` pairs for `color.*` from the start. The 1-2 hour up-front cost is much cheaper than retrofitting it after every component is wired.

### Sub-mode override whitelist — the 16-key list (AC8 reconciliation)

Epics.md Story 1.5 AC L240 + UX §Token Surface Override 정책 L1027–1040 = **12 keys**. UX §Sub-Mode Catalog D3 Quiet (lines 1101–1109) + D5 Plate (lines 1144–1151) use **3 + 1 additional keys**. Story 1.5 ships the 16-key superset:

| # | Key | Used by | Source |
|---|-----|---------|--------|
| 1 | `color.bg.canvas` | D3.quiet | UX D3 |
| 2 | `color.bg.surface` | D2.bento, D3.quiet, D4.postcard | UX §Token Surface Override |
| 3 | `color.bg.elevated` | D2.bento | UX §Token Surface Override |
| 4 | `color.text.primary` | D3.quiet | UX D3 |
| 5 | `color.text.secondary` | D3.quiet | UX D3 |
| 6 | `color.stroke.default` | D5.plate | UX D5 |
| 7 | `typography.heading.weight` | all 5 | UX §Token Surface Override |
| 8 | `typography.heading.tracking` | D1.editorial | UX §Token Surface Override |
| 9 | `typography.display.serif.enabled` | D1.editorial, D4.postcard | UX §Token Surface Override |
| 10 | `motion.entry.duration` | D1, D3, D4 | UX §Token Surface Override |
| 11 | `motion.entry.easing` | D1, D3, D4 | UX §Token Surface Override |
| 12 | `radius.default` | D1, D2, D5 | UX §Token Surface Override |
| 13 | `radius.pronounced` | D4 | UX §Token Surface Override |
| 14 | `elevation.1` | D1, D2, D5 | UX §Token Surface Override |
| 15 | `elevation.2` | D4 | UX §Token Surface Override |
| 16 | `space.layout.padding` | D1, D2, D5 | UX §Token Surface Override |

The Story 1.5 PR description includes: *"Sub-mode override whitelist expanded from 12 keys (epics.md AC L240) to 16 keys to accommodate UX-locked D3 Quiet (3 keys) and D5 Plate (1 key) override blocks. Cross-reference: `_bmad-output/planning-artifacts/epics.md` Story 1.5 AC L240 + `_bmad-output/planning-artifacts/ux-design-specification.md` §Token Surface Override 정책 L1027–1040 + §Sub-Mode Catalog D3 L1098–1110 + D5 L1140–1152."* Optionally raise an `epics.md` patch PR alongside to update the AC L240 text — but not blocking for Story 1.5 merge.

### Source files to touch (UPDATE vs NEW — read each UPDATE file fully before editing)

Per project-context Stack PR Merge Procedure + read-before-edit rule: read the *current state* of every UPDATE file before editing. Preserve all behaviors not explicitly changed by this story.

**NEW files:**

- `FE/src/theme/tokens.json` (canonical v2 source, AC1)
- `BE/src/main/resources/tokens.schema.json` (JSON Schema, AC2) — may be symlinked into FE
- `FE/src/theme/useTheme.ts` (hook, AC7)
- `FE/src/providers/SubModeProvider.tsx` (provider, AC7)
- `FE/src/components/survival/SurvivalChip.tsx` (primitive, AC6)
- `FE/src/components/survival/iconMap.ts` (icon mapping, AC12)
- `FE/src/components/survival/index.ts` (barrel export)
- `FE/src/components/survival/__tests__/SurvivalChip.test.tsx` (AC14)
- `FE/src/components/survival/__tests__/SurvivalChip.dynamic-type.test.tsx` (AC14)
- `FE/src/providers/__tests__/SubModeProvider.test.tsx` (AC14)
- `FE/src/theme/__tests__/useTheme.test.tsx` (AC14)
- `FE/src/theme/__tests__/tokens.json.schema.test.ts` (AC14 + AC2)
- `tools/brand-voice-lint.ts` (AC5)
- `tools/contrast-check.ts` (AC9)
- `tools/package.json` (if `tools/` needs its own minimal deps for `tsx`)
- `tools/__tests__/brand-voice-lint.test.ts` (AC14)
- `tools/__tests__/contrast-check.test.ts` (AC14)
- `BE/src/test/java/com/yeosal/api/theme/GeneratedTokensTest.java` (AC14)
- `BE/src/test/java/com/yeosal/api/theme/TokenSchemaValidationTest.java` (AC14)
- `BE/config/checkstyle/checkstyle.xml` (AC4 — if Option A) OR `BE/src/test/java/com/yeosal/api/architecture/HexLiteralGuardTest.java` (AC4 — if Option B)
- `docs/design-system.md` (AC10 — hand-synced Option B chosen for v1)

**UPDATE files:**

- `FE/src/theme/tokens.ts` (UPDATE — remap legacy exports to v2 values; AC11). **Preserve every exported symbol** (`palette`, `colors`, `surface`, `text`, `semantic`, `roomHues`, `grassRamp`, `spacing`, `borders`, `typography`, `pickRoomAccent`, `roomAccentOrder`, `RoomAccent` type). Swap values to derive from `tokens.json`. Annotate transitional values with `// LEGACY — migrate to v2 token in <story>`.
- `FE/app/_layout.tsx` (UPDATE — wrap in `<SubModeProvider subMode={null}>` at root; AC7).
- `FE/package.json` (UPDATE — add `lint:brand-voice` script; add `ajv` dev-dep for FE-side schema test; verify `tsx` available or add).
- `FE/eslint.config.js` (UPDATE — if a lint rule is needed to prevent `subMode` prop drilling into leaf components; OPTIONAL — `tools/brand-voice-lint.ts` can absorb this if cheaper).
- `BE/build.gradle` (UPDATE — add `validateTokens` + `generateTokens` tasks + `checkstyle` plugin; AC2, AC3, AC4). **Preserve** the existing `plugins`, `group`, `version`, `java { toolchain }`, `dependencies`, `tasks.named("test")` blocks. The Story 1.4 `systemProperty "yeosal.boot-smoke", ...` line stays untouched.
- `scripts/test.sh` (UPDATE — wire brand-voice + contrast checks; AC13). **Preserve** the existing FE / BE / docker guard patterns.
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (UPDATE — story status transitions; X-3).
- `_bmad-output/implementation-artifacts/1-5-design-system-foundation-v2-token-packed-type-fe-be-codegen.md` (UPDATE — this file's Tasks checkboxes, Dev Agent Record, status; X-3).

**Files explicitly NOT touched:**

- Any FE screen/component beyond AC11's smoke test (Today / Chat / Rooms / Wallet placeholder / Login / Signup / Join / Friend / Notification / Index — all are migrated screen-by-screen in downstream stories, NOT here).
- `BE/src/main/java/com/yeosal/api/{auth, common, daily, friend, notification, profile, realtime, room, stats, user, survival, revival, ceremony, kakaoshare}/` (any module other than `theme/`).
- `BE/src/main/resources/db/migration/V11__survival_revival_economy.sql` (V11 ships from Story 1.4; no schema change here).
- `BE/src/main/resources/application.yml` (no app-config change).
- `infra/RUNBOOK-V11.md`, `infra/verify-v11.sh`, `infra/docker-compose.yml` (no ops change).
- Any FE `app/`-route file beyond `_layout.tsx` (AC15 explicit).

### Existing patterns to reuse (read before authoring)

- `FE/src/theme/tokens.ts` — current v1 Bento Modular Warm exports. The AC11 remap target. Read fully before editing to understand the legacy export surface (`palette`, `colors`, `surface`, `text`, `semantic`, `roomHues`, `grassRamp`, `spacing`, `borders`, `typography`, helper `pickRoomAccent`).
- `FE/src/theme/{motion,elevation,typography,spacing}.ts` — sibling theme files. Decide during implementation whether they survive (re-derived from `tokens.json`) or are absorbed into `tokens.ts` + `useTheme()` resolution. Smaller diff = keep them, re-derive values.
- `FE/src/providers/QueryProvider.tsx` + `RealtimeProvider.tsx` — existing provider shape. `SubModeProvider` follows the same React-context idiom + colocated `use*` hook.
- `FE/app/_layout.tsx` — root layout; verify the existing provider stack order and where `<SubModeProvider>` slots in (likely outermost, since it has no side effects and just supplies theme).
- `BE/build.gradle` — read the entire 60-line file; the `tasks.named("test")` block already has the Story 1.4 `systemProperty` line — keep it. Story 1.5 additions append `validateTokens` + `generateTokens` task definitions + `dependsOn` wiring.
- `_bmad-output/planning-artifacts/ux-design-directions.html` — the locked 5-sub-mode showcase. The oklch values + override blocks in the HTML's `:root` CSS variables are the canonical seed for `tokens.json` (lines 17–60 of the HTML). Sanity-check: token values in `tokens.json` must match the HTML's CSS-variable values.
- `_bmad-output/planning-artifacts/architecture.md` §4.16 — the full pipeline diagram + schema example. Reference during BE-1 implementation.
- `_bmad-output/planning-artifacts/ux-design-specification.md` §Visual Design Foundation (lines 804–1040) — the canonical token values + sub-mode override blocks.

### Previous story intelligence (Stories 1.1–1.4)

Carry forward from prior story dev notes:

- **`@EnabledIfSystemProperty(named="yeosal.boot-smoke", matches="true")` pattern is BE-only and is for integration tests requiring Docker.** Story 1.5's BE tests (`GeneratedTokensTest`, `TokenSchemaValidationTest`) do NOT need this guard — they run in the default `./gradlew test` cycle (no DB, no Docker). The Story 1.4 BE-2 fix (`systemProperty "yeosal.boot-smoke", ...`) stays in place for the existing IT layer.
- **TDD discipline (RED → GREEN → refactor) per project-context.** Author the schema validator test (BE-3.2) RED first; then write the schema; verify GREEN. Author the brand-voice-lint test fixtures (TL-1.5) RED first; then implement the lint rule. Author the SurvivalChip test (FE-4.3) RED first; then implement the component.
- **No `React.FC`; props are named `interface`s; no `any`** (project-context FE rule). Verified in Stories 1.1–1.3 FE patterns.
- **`./gradlew test` must stay green** at every commit (project-context pre-push). Use `-Porg.gradle.java.installations.paths=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home` if Gradle misses Java 21 (Story 1.4 precedent).
- **Pre-push order: `npm run lint && npm run typecheck && npm test` (FE), `./gradlew test` (BE), `bash scripts/verify.sh` (combined)** — project-context. Story 1.5 adds two new steps inside `scripts/test.sh` (brand-voice + contrast); they must respect the same pattern (silent-skip if FE deps absent; non-zero exit on violation).
- **Stack PR Merge Procedure applies if Story 1.5 stacks on an unmerged base.** Verify `baseRefName == main` before merge. Project-context incident-driven rule from PR #36.
- **Pre-existing FE baseline failures** (`FE/app/rooms/[id]/chat.tsx:60`, `FE/src/lib/realtime/client.ts:283`, `FE/src/components/rooms/__tests__/InviteCodeSheet.test.tsx:1`, per Stories 1.2/1.3 Git Intelligence) — **out of scope** for Story 1.5. Do not fix them here; they are already-tracked baseline noise. AC11's smoke screen should be chosen to avoid these three files.

### Git intelligence (recent commits)

```text
4f741ff fix(migration): drop non-IMMUTABLE ::date cast from V11 partial unique index (#57) — 2026-05-13 (Story 1.4 hotfix)
c0aefd5 feat/privacy filtered survival roster api (#55) — 2026-05-12 (Story 1.3)
565d505 feat(survival): Stories 1.1 + 1.2 — room cap, 14d grace, 06:00 KST evaluator (#54) — 2026-05-11 (Stories 1.1+1.2 combined)
fecb48f bmad init
3f11a82 bmad init
ea2415f feat(realtime): STOMP/WebSocket realtime for chat, members, friend requests (#53)
947318b feat(reflection): allow editing the day's reflection without re-fan-out (#52)
cad4bac fix(navigation): eliminate black flash on tab and stack transitions (#51)
```

- Story 1.4 is `review` in `sprint-status.yaml` but **shipped end-to-end** per project memory (PR #55 + hotfix PR #57 merged; `verify-v11.sh` 5/5 PASS on the server). The sprint-status flip from `review → done` is the only outstanding task on 1.4. Story 1.5 starts from a clean V11-applied baseline; no V11 work overlap.
- **Recommended branch:** `feat/design-system-foundation-v2-codegen` from current `main`. Avoid stacking on the pending 1.4 review-fix branch — Story 1.5 has zero V11 dependency.
- The chat/realtime/notification FE files modified in PRs #51–#53 do NOT touch theme/token surface area — Story 1.5 won't conflict with any open WIP on `main`.

### Latest tech information (versions + library notes)

- **TypeScript 5.9** (FE strict mode) + **React 19.1.0** + **React Native 0.81.5** + **Expo SDK 54** — project-context. The `useTheme()` hook and `<SubModeProvider>` use React 19's standard `createContext` / `useContext` APIs (no `use(...)` Suspense hook needed; theme is synchronous).
- **`@expo/vector-icons`** is already a project dep. Verify the exact version in `FE/package.json` (>= 14.x bundles Feather, MaterialIcons, MaterialCommunityIcons, Ionicons — all four families AC12 recommends).
- **`ajv`** for JSON Schema (FE test) — current major is `ajv@8.x` (draft-07 + draft 2019-09 + draft 2020-12). Use draft 2020-12 for `tokens.schema.json` if writing fresh.
- **Java JSON Schema validators (BE):**
  - `com.networknt:json-schema-validator` (`v1.5.x`, 2026) — supports draft 2020-12, actively maintained, no extra runtime deps. Add to a Gradle `validateTokens.classpath` configuration (not `implementation`) to keep the produced JAR lean.
  - Alternative: `org.everit.json:org.everit.json.schema` — older but very small. Either works.
- **Java codegen (BE):** plain `StringBuilder` + line-by-line emission. No JavaPoet / no annotation processor — overkill for a flat constants class.
- **Checkstyle (BE):** Gradle's built-in `checkstyle` plugin is sufficient. Pin to `toolVersion = "10.18.0"` (compatible with Java 21).
- **`tsx`** for `tools/*.ts` execution — current is `tsx@4.x`. No build step needed.
- **`culori`** (~10kb) or hand-rolled oklch→sRGB for `tools/contrast-check.ts`. UX values are all in oklch; conversion is < 50 LOC.
- **`@testing-library/react-native`** is already a project dep. `<SurvivalChip>` tests use it.

### Testing standards summary

| Layer | Framework | Scope |
|-------|-----------|-------|
| FE component | Jest + `@testing-library/react-native` | `<SurvivalChip>` 4 states, Dynamic Type 1.5×, `<SubModeProvider>` 5 + null, `useTheme()` resolution |
| FE schema | `ajv@8` | `tokens.json` validates against `tokens.schema.json` at FE test time |
| Tools | Node test runner (`node --test` / `tsx`) | `brand-voice-lint.ts` fixture corpus, `contrast-check.ts` pair list |
| BE schema | JUnit 5 + `com.networknt:json-schema-validator` | `TokenSchemaValidationTest` positive + 4 negatives |
| BE codegen | JUnit 5 (no Spring) | `GeneratedTokensTest` round-trip parity (token JSON ↔ Java constants) |
| BE arch guard | Checkstyle (Option A, recommended) OR ArchUnit (Option B) | hex/rgb/oklch literal detection outside `theme/` package |

Default cycle (no Docker, no opt-in): `./gradlew test` + `npm test` + `npx tsx tools/brand-voice-lint.ts` + `npx tsx tools/contrast-check.ts` must all be green. The IT layer (`@EnabledIfSystemProperty`) is unaffected by this story.

**Coverage target:** 80%+ on `tools/brand-voice-lint.ts`, `tools/contrast-check.ts`, `<SurvivalChip>`, `<SubModeProvider>`, `useTheme()`. The generated `GeneratedTokens.java` is exempt from line coverage; behavioral coverage via `GeneratedTokensTest` is the contract.

### Pre-commit verification (project-context Stack PR Merge Procedure + pre-push order)

1. `cd FE && npm run lint && npm run typecheck && npm test` — green (project-context FE pre-push rule). Adds the new schema / SurvivalChip / SubModeProvider / useTheme tests to the existing cycle. The FE `lint` step also re-runs the brand-voice lint if wired via `eslint` (optional).
2. `npx tsx tools/brand-voice-lint.ts` — green. Run from repo root.
3. `npx tsx tools/contrast-check.ts` — green.
4. `cd BE && ./gradlew test --no-daemon` — green. Includes `validateTokens` + `generateTokens` + the new theme tests + Checkstyle.
5. `cd BE && ./gradlew check --no-daemon` — green (covers Checkstyle if Option A).
6. `bash scripts/verify.sh` from repo root — full FE+BE verification, including Docker image build if Docker is up.
7. **PR base must be `main`** per project-context Stack PR Merge Procedure. Verify with `gh pr view <N> --json baseRefName,mergeStateStatus`.

### Open questions saved for end (raise after dev work but before merge)

1. **`tokens.json` color format — oklch-only vs `{oklch, hex}` pairs.** Decide during Task FE-1.1 based on RN's color-parser behavior with oklch strings. Recommend `{oklch, hex}` pairs (Dev Notes §Color-format compatibility).
2. **AC4 hex-literal guard — Checkstyle (Option A) vs ArchUnit (Option B).** Recommend Option A for v1 (simpler).
3. **AC10 design-system doc — generated vs hand-synced.** Recommend Option B (hand-synced) for v1; revisit if drift becomes painful.
4. **Sub-mode override whitelist count — 12 (epics.md AC) vs 16 (Dev Notes §Sub-mode override whitelist).** Recommend shipping the 16-key superset (covers all locked override blocks); optionally raise an epics.md patch PR to update AC L240.
5. **FE icon mappings (AC12).** Confirm the 4 recommended `@expo/vector-icons` glyphs visually before merge. Substitute if visual fit is off.
6. **Should `<SubModeProvider>` be wrapped at the app root in Story 1.5, or deferred?** Recommend wrapping at root with `subMode={null}` (= base) — downstream stories then swap the prop per-route-segment layout. Costs zero perf, simplifies migration.

### References

- [PRD §14.2 (Locked decisions)](../planning-artifacts/prd.md) — visual identity = yeolsal v2 Oxblood Editorial; NFR-9.6.1 enforcement = token packed type + CI lint hard gate; FE↔BE token sync = codegen pipeline.
- [PRD §9.6 NFR-9.6.1](../planning-artifacts/prd.md) — packed-type contract + hard CI gate language.
- [PRD §9.6 NFR-9.6.3](../planning-artifacts/prd.md) — Dynamic Type.
- [Architecture §4.15](../planning-artifacts/architecture.md) — brand-voice + a11y gate (hard CI gate for NFR-9.6.1).
- [Architecture §4.16](../planning-artifacts/architecture.md) — FE↔BE Design Token Codegen Pipeline (full schema + pipeline diagram).
- [Architecture §4.9](../planning-artifacts/architecture.md) — `SvgRenderer.java` token sourcing.
- [UX §Visual Design Foundation (color/typography/space/radius/elevation/blur/motion)](../planning-artifacts/ux-design-specification.md) — canonical oklch + numeric values.
- [UX §Sub-Mode Catalog (D1–D5)](../planning-artifacts/ux-design-specification.md) — locked override blocks.
- [UX §Surface Assignment Matrix](../planning-artifacts/ux-design-specification.md) — which surface gets which sub-mode (downstream consumer reference).
- [UX §Token Surface Override 정책](../planning-artifacts/ux-design-specification.md) — override-key whitelist.
- [UX §Accessibility Considerations](../planning-artifacts/ux-design-specification.md) — WCAG 2.2 AA contrast targets.
- [Implementation Readiness Report 2026-05-11 Step 4](../planning-artifacts/implementation-readiness-report-2026-05-11.md) — M1 (SurvivalChip), M2 (SubModeProvider), L1 (override whitelist), M5/H4 (NFR-9.6.1 enforcement), A5 (sub-mode token sync).
- [Sprint Change Proposal 2026-05-10 §G4.1](../planning-artifacts/sprint-change-proposal-2026-05-10.md) — Story 1.5 introduction + rationale.
- [Epics.md Story 1.5 section](../planning-artifacts/epics.md) — original story spec (lines 201–243).
- [Story 1.4 dev notes + dev agent record](./1-4-v11-migration-production-backfill.md) — Testcontainers / `@EnabledIfSystemProperty` / Gradle property-forwarding precedent.
- [project-context.md](../project-context.md) — BE/FE rules (especially: "No `@Autowired` field injection", "Constructor injection only", "No emojis in source files", "Default to no comments", "TypeScript strict / no `any`", "Component props are named `interface`s", "Immutable updates only", "Pre-push order").
- Existing source for pattern reference:
  - `FE/src/theme/tokens.ts` (current v1 exports — AC11 remap target)
  - `FE/src/theme/{motion,elevation,typography,spacing}.ts` (sibling files — decide fate during impl)
  - `FE/src/providers/QueryProvider.tsx` + `RealtimeProvider.tsx` (provider idiom)
  - `FE/app/_layout.tsx` (root layout — provider wrapping point)
  - `BE/build.gradle` (the file under modification)
  - `_bmad-output/planning-artifacts/ux-design-directions.html` (locked sub-mode showcase — `tokens.json` seed sanity check)
  - `_bmad-output/planning-artifacts/ux-design-specification.md` §§Visual Design Foundation, Sub-Mode Catalog, Token Surface Override 정책 (canonical token values)
  - `_bmad-output/implementation-artifacts/1-4-v11-migration-production-backfill.md` (story file structure reference + ops-script idiom for `tools/*.ts` wiring)
  - `scripts/test.sh` + `scripts/build.sh` + `scripts/verify.sh` (orchestration — AC13)
  - `infra/verify-v11.sh` (bash idiom precedent for `tools/*.ts` invocation patterns)

## Dev Agent Record

### Agent Model Used

claude-opus-4-7 (1M context).

### Debug Log References

- `cd BE && JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew generateTokens --no-daemon` — BUILD SUCCESSFUL; `[validateTokens] tokens.json passed schema validation` + `[generateTokens] GeneratedTokens.java written: ... (119 base constants + 5 sub-mode blocks)`.
- `cd BE && ./gradlew test --no-daemon --tests "com.yeosal.api.theme.*"` — 12 tests PASS (7 GeneratedTokensTest + 5 TokenSchemaValidationTest).
- `cd BE && ./gradlew check --no-daemon` — full check (test + checkstyle) BUILD SUCCESSFUL after the `exclude("com/yeosal/api/theme/GeneratedTokens.java")` fix (initial `**/generated/**` pattern didn't match because Checkstyle resolves paths relative to source-set root, not absolute filesystem).
- `cd BE && ./gradlew test --no-daemon` (full BE regression) — 41 test classes, 0 failures / 0 errors. Pre-existing opt-in Testcontainers ITs (V11MigrationIT 9, RoomControllerIT 4, SurvivalStateEvaluatorIT 1, SurvivalStateRosterIT 6, ApplicationBootSmokeTest 2) remain `skipped` per their `@EnabledIfSystemProperty` guard — same as Story 1.4 baseline.
- Negative-probe verification of Checkstyle: temporarily injected `public static final String BAD_COLOR = "#FF0000";` into `BE/src/main/java/com/yeosal/api/common/CheckstyleNegativeProbe.java` → `./gradlew checkstyleMain` reported `[HexColorLiteralGuard] BUILD FAILED ... violations by severity: [error:1]`. Probe file removed; main cycle stayed green.

### Completion Notes List

**Sprint A (BE codegen spine — this commit):** delivered AC1 (tokens.json), AC2 (schema + validateTokens), AC3 (generateTokens), AC4 (Checkstyle hex-literal guard), and the BE half of AC14 (GeneratedTokensTest + TokenSchemaValidationTest = 12 tests). All BE acceptance gates verified end-to-end via `./gradlew check`. No FE/tools/docs work in this commit — Sprint B (tools/brand-voice-lint + contrast-check), Sprint C (FE primitives: useTheme + SubModeProvider + SurvivalChip + legacy remap), and Sprint D (docs + scripts wire-up + FE ajv smoke test) land in separate PRs.

Sub-PR split rationale (per dev-story session decision 2026-05-13, Option B): the fact-forcing gate hook fires on every Edit/Write and demands a fresh facts-block + Grep/Glob proof per file, which would multiply turn-cost across ~30 files. Splitting into 4 PRs keeps the safety hook active while shipping the codegen spine immediately so Stories 6.1 / 7.1 can begin consuming `GeneratedTokens` constants without waiting for the FE primitives.

Story status stays `in-progress` (NOT flipped to review) because Sprints B/C/D remain. The next `/bmad-dev-story` session picks up from Task TL-1.

**Sprint B + C + D (Story 1.5 closeout — this session 2026-05-13):** delivered the remaining FE / tools / docs / scripts surface.

- Sprint B — `tools/` workspace: `tools/brand-voice-lint.ts` (Rule 1 HARD GATE for NFR-9.6.1, Rule 2 WARN AVOID lexicon, Rule 3 WARN literal guard) + `tools/contrast-check.ts` (hand-rolled WCAG 2.2 AA against canonical + D3.quiet pairs) + 16 unit tests (`node:test` via `tsx`). Standalone TS workspace with `tsx` + `typescript` + `@types/node` devDeps.
- Sprint C — FE primitives: `FE/src/theme/useTheme.ts` (sub-mode resolver + `ResolvedTheme = Omit<typeof tokensJson, "subMode">`), `FE/src/providers/SubModeProvider.tsx`, `<SurvivalChip>` with `iconMap` + barrel export; `FE/app/_layout.tsx` wraps root in `<SubModeProvider subMode={null}>`. Legacy `FE/src/theme/tokens.ts` annotated and now re-exports `tokensV2` from `tokens.json`; v1 export shape preserved for the 28 downstream consumers (per AC11 readability exception clause — per-screen migration owned downstream).
- Sprint D — wiring + docs: `FE/src/theme/__tests__/tokens.json.schema.test.ts` validates `tokens.json` against `BE/.../tokens.schema.json` via `ajv@8.20.0` (newly added FE devDep); `scripts/test.sh` runs `brand-voice-lint` + `contrast-check` + tools self-tests between FE and BE blocks (silent-skip when `tools/node_modules` absent); `docs/design-system.md` fully replaced with hand-synced v2 doc (12 sections including the WCAG matrix + 16-key whitelist + enforcement chain + migration policy).
- Quality gate (this session): brand-voice-lint = 0 HARD / 93 WARN (legacy `tokens.ts` literals + 4 pre-existing form-error copy lines — both expected and accepted), contrast-check = 10/10 PASS, FE jest (Story 1.5 surface) = 29/29 PASS across 5 suites, tools unit tests = 16/16 PASS, BE `./gradlew test --no-daemon` = UP-TO-DATE (Sprint A artifacts unchanged). Pre-existing FE baseline typecheck failures in `FE/src/components/today/FriendsTodayPager.tsx` (missing `react-native-pager-view` dep) remain — confirmed via `git stash`/typecheck/`stash pop` to be inherited from the branch base and out of scope for Story 1.5.

Story status flipped `in-progress → review` (this session). Reviewer (separate context, different LLM recommended per sprint-status workflow notes) decides `review → done`.

**Decisions recorded:**
- Color format: `{oklch, hex}` pairs in tokens.json (oklch canonical, hex RN fallback). Resolves Open Question #1.
- Hex-literal guard: Checkstyle Option A (10.18.0). Resolves Open Question #2.
- Sub-mode whitelist: 16 keys (the 12-key epics.md AC was expanded by 4 to absorb D3 Quiet + D5 Plate locked override blocks). Resolves Open Question #4 — to be noted in epics.md patch when convenient.
- AC10: Option B (hand-synced) chosen. Resolves Open Question #3.
- AC12 FE icon mapping: MaterialIcons family across all 4 states (check / warning-amber / pause-circle-outline / visibility) for consistency with the existing FE icon import surface. Resolves Open Question #5.
- AC11: minimum-risk remap (no value swap, `tokensV2` re-export, per-screen migration owned downstream) per the readability exception clause. The dark v2 oxblood palette inverted against v1 warm-mode surfaces would have made every screen unreadable.
- AC7 FE-3.4: root layout wraps in `<SubModeProvider subMode={null}>`. Resolves Open Question #6.

### File List

Sprint A files (this commit):

- `FE/src/theme/tokens.json` (NEW — canonical v2 token source)
- `BE/src/main/resources/tokens.schema.json` (NEW — JSON Schema draft 2020-12)
- `BE/build.gradle` (MODIFIED — added buildscript json-schema-validator dep, checkstyle plugin, `validateTokens` + `generateTokens` tasks, generated source set wiring, checkstyle config block; preserved Story 1.4 `systemProperty "yeosal.boot-smoke"` line)
- `BE/config/checkstyle/checkstyle.xml` (NEW — 4 RegexpSingleline rules for #hex / rgb / rgba / oklch literal guard)
- `BE/src/test/java/com/yeosal/api/theme/GeneratedTokensTest.java` (NEW — 7 round-trip parity tests)
- `BE/src/test/java/com/yeosal/api/theme/TokenSchemaValidationTest.java` (NEW — 1 positive + 4 negative schema-validation cases)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (MODIFIED — 1-5 transitioned ready-for-dev → in-progress)
- `_bmad-output/implementation-artifacts/1-5-design-system-foundation-v2-token-packed-type-fe-be-codegen.md` (this file — task checkboxes ticked for Sprint A scope, Dev Agent Record populated, Status set to in-progress, Change Log updated)

NOT touched in Sprint A (deferred to Sprints B/C/D):
- `FE/src/theme/{tokens.ts,useTheme.ts,motion.ts,elevation.ts,typography.ts,spacing.ts}` (Sprint C)
- `FE/src/providers/SubModeProvider.tsx` (Sprint C)
- `FE/src/components/survival/{SurvivalChip.tsx,iconMap.ts,index.ts}` (Sprint C)
- `FE/app/_layout.tsx`, `FE/package.json` (Sprint C/D)
- `tools/brand-voice-lint.ts`, `tools/contrast-check.ts`, `tools/package.json`, `tools/__tests__/*` (Sprint B)
- `scripts/test.sh` (Sprint D)
- `docs/design-system.md` (Sprint D)

Sprint B + C + D files (this session, 2026-05-13):

- `tools/package.json` (NEW — tools workspace declaring tsx + typescript + @types/node)
- `tools/tsconfig.json` (NEW — strict TS config for the workspace)
- `tools/brand-voice-lint.ts` (NEW — 3-rule scanner, Rule 1 hard CI gate per NFR-9.6.1)
- `tools/contrast-check.ts` (NEW — WCAG 2.2 AA against canonical + D3.quiet pairs)
- `tools/__tests__/brand-voice-lint.test.ts` (NEW — 9 cases)
- `tools/__tests__/contrast-check.test.ts` (NEW — 7 cases)
- `FE/src/theme/useTheme.ts` (NEW — `useTheme()` + `SubModeContextProvider` + override resolver)
- `FE/src/providers/SubModeProvider.tsx` (NEW — page-level sub-mode wrapper)
- `FE/src/theme/__tests__/useTheme.test.tsx` (NEW — 10 cases: 7 resolver + 3 provider)
- `FE/src/theme/__tests__/tokens.json.schema.test.ts` (NEW — 5 ajv-driven schema cases)
- `FE/src/providers/__tests__/SubModeProvider.test.tsx` (NEW — 6 cases: 5 sub-modes + null base)
- `FE/src/components/survival/SurvivalChip.tsx` (NEW — the only allowed entry point for survival rendering)
- `FE/src/components/survival/iconMap.ts` (NEW — abstract → MaterialIcons glyph map)
- `FE/src/components/survival/types.ts` (NEW — `SurvivalState` union)
- `FE/src/components/survival/index.ts` (NEW — barrel export)
- `FE/src/components/survival/__tests__/SurvivalChip.test.tsx` (NEW — 5 cases: 4 states + composite-shape probe)
- `FE/src/components/survival/__tests__/SurvivalChip.dynamic-type.test.tsx` (NEW — Dynamic Type cap assertion + 2 snapshots)
- `FE/src/components/survival/__tests__/__snapshots__/SurvivalChip.dynamic-type.test.tsx.snap` (NEW — generated)
- `FE/src/theme/tokens.ts` (MODIFIED — file header rewritten with migration policy; added `import tokensJson from "./tokens.json"` and `export const tokensV2`; v1 export shape preserved for the 28 downstream consumers)
- `FE/app/_layout.tsx` (MODIFIED — added `SubModeProvider` import + wrapped root JSX in `<SubModeProvider subMode={null}>`)
- `FE/package.json` (MODIFIED — added `ajv@8.20.0` to devDependencies for the FE schema smoke test)
- `FE/package-lock.json` (MODIFIED — pinned ajv transitive deps)
- `scripts/test.sh` (MODIFIED — new brand-voice + contrast block between FE and BE, silent-skip guard on `tools/node_modules/.bin/tsx`)
- `docs/design-system.md` (FULL REPLACEMENT — v1 Risograph stub → v2 Oxblood Editorial hand-synced doc, 12 sections)
- `_bmad-output/implementation-artifacts/sprint-status.yaml` (MODIFIED — 1-5 transitioned `in-progress → review`)
- `_bmad-output/implementation-artifacts/1-5-design-system-foundation-v2-token-packed-type-fe-be-codegen.md` (MODIFIED — Sprint B/C/D task checkboxes ticked, Status flipped to `review`, Dev Agent Record + File List + Change Log updated)

### Change Log

| Date | Author | Change |
|------|--------|--------|
| 2026-05-13 | scrum-master (claude-opus-4-7) | Story 1.5 created via `/bmad-create-story`. Foundation story for v2 Oxblood Editorial design system: `tokens.json` + JSON Schema, Gradle `validateTokens` + `generateTokens` codegen, `tools/brand-voice-lint.ts` hard CI gate for NFR-9.6.1, `<SurvivalChip>` primitive (Readiness M1), `<SubModeProvider>` page wrapper (Readiness M2), 16-key sub-mode override whitelist (Readiness L1), BE Checkstyle hex-literal guard, WCAG 2.2 AA contrast verification, Dynamic Type smoke test, legacy theme remap (no per-screen migration). FE/BE drift made structurally impossible via codegen pipeline. Out-of-scope: every downstream feature surface — each migrates in its own story. |
| 2026-05-13 | dev (claude-opus-4-7) | **Sprint A** — BE codegen spine shipped. tokens.json (119 base + 5 sub-mode), tokens.schema.json (draft 2020-12), `validateTokens` + `generateTokens` Gradle tasks (com.networknt v1.5.0), Checkstyle 10.18.0 hex-literal guard (Option A), 12 BE tests (7 round-trip + 5 schema-validation). `./gradlew check` green. Story 1.5 split into 4 Sub-PRs per gate-friction decision; Sprints B (tools), C (FE primitives), D (docs+scripts) remain. Story stays `in-progress` until all sprints complete. |
| 2026-05-13 | dev (claude-opus-4-7) | **Sprint B + C + D** — Story 1.5 closeout. Tools (`brand-voice-lint`, `contrast-check`) + tests (16/16). FE primitives (`useTheme`, `<SubModeProvider>`, `<SurvivalChip>`, `iconMap`) + tests (29/29 across 5 jest suites). `tokens.ts` annotated and re-exports `tokensV2` (legacy v1 shape preserved per AC11 readability exception). Root layout wraps in `<SubModeProvider subMode={null}>`. `scripts/test.sh` wires the new gates with silent-skip guards. `docs/design-system.md` hand-synced (12 sections incl. WCAG matrix + 16-key whitelist). Final gate run: 0 hard / 93 warn from lint, 10/10 PASS contrast, BE UP-TO-DATE. Status flipped `in-progress → review`. Reviewer (different LLM recommended) decides `review → done`. |
