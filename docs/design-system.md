# Design System — yeolsal v2 Oxblood Editorial

**Token source of truth:** `FE/src/theme/tokens.json`
**Schema:** `BE/src/main/resources/tokens.schema.json`
**FE entry point:** `useTheme()` in `FE/src/theme/useTheme.ts`
**BE entry point:** generated `com.yeosal.api.theme.GeneratedTokens` (via `./gradlew generateTokens`)
**Last sync:** 2026-05-13 (Story 1.5)

This document is hand-synced (AC10 Option B). When `tokens.json` changes, update this doc in the same PR. Drift is rejected at code review.

---

## 1. Color

### 1.1 Background

| Token | oklch | hex |
| --- | --- | --- |
| `color.bg.canvas` | `oklch(14% 0.006 30)` | `#1B1715` |
| `color.bg.surface` | `oklch(18% 0.008 30)` | `#241F1C` |
| `color.bg.elevated` | `oklch(22% 0.010 30)` | `#2D2724` |
| `color.bg.overlay` | `oklch(8% 0.004 30 / 0.78)` | `#100D0BC8` |
| `color.bg.inverse` | `oklch(96% 0.005 60)` | `#F4F0EB` |

### 1.2 Text

| Token | oklch | hex |
| --- | --- | --- |
| `color.text.primary` | `oklch(96% 0.005 60)` | `#F4F0EB` |
| `color.text.secondary` | `oklch(72% 0.008 60)` | `#B7B0A6` |
| `color.text.tertiary` | `oklch(52% 0.008 60)` | `#7B756B` |
| `color.text.disabled` | `oklch(35% 0.005 60)` | `#504C46` |
| `color.text.inverse` | `oklch(18% 0.008 30)` | `#241F1C` |

### 1.3 Key (oxblood)

| Token | oklch | hex |
| --- | --- | --- |
| `color.key.default` | `oklch(42% 0.135 25)` | `#7E2C2A` |
| `color.key.glow` | `oklch(50% 0.155 25)` | `#9B3633` |
| `color.key.deep` | `oklch(32% 0.110 22)` | `#5E2120` |
| `color.key.muted` | `oklch(38% 0.045 25)` | `#5B3A39` |
| `color.key.line` | `oklch(58% 0.140 25)` | `#B14342` |

### 1.4 Ember (warm accent)

| Token | oklch | hex |
| --- | --- | --- |
| `color.ember.default` | `oklch(72% 0.130 65)` | `#D89F62` |
| `color.ember.subtle` | `oklch(60% 0.075 65)` | `#A48064` |

### 1.5 Stroke

| Token | oklch | hex |
| --- | --- | --- |
| `color.stroke.subtle` | `oklch(28% 0.006 30)` | `#3B3633` |
| `color.stroke.default` | `oklch(40% 0.008 30)` | `#594E48` |
| `color.stroke.strong` | `oklch(60% 0.010 30)` | `#8A7C72` |
| `color.stroke.key` | `oklch(58% 0.140 25)` | `#B14342` |

### 1.6 Chart (A12 single-accent guard)

| Token | oklch | hex |
| --- | --- | --- |
| `color.chart.primary` | `oklch(42% 0.135 25)` | `#7E2C2A` |
| `color.chart.muted` | `oklch(52% 0.008 60)` | `#7B756B` |
| `color.chart.grid` | `oklch(28% 0.006 30)` | `#3B3633` |

---

## 2. Semantic — Survival (packed type, NFR-9.6.1)

Each state ships all four fields together. The JSON Schema rejects any state missing any field. The FE brand-voice lint (`tools/brand-voice-lint.ts`) rejects any consumer that reads `survival.<STATE>.color` without an adjacent label.

The only allowed entry point for rendering a survival state in the FE source tree is `<SurvivalChip state="…" />` (see `FE/src/components/survival/SurvivalChip.tsx`).

| State | `color` (hex) | `label` (ko-KR) | `icon` | `grass-treatment` |
| --- | --- | --- | --- | --- |
| `ACTIVE` | `#6B9A6E` | 활동 중 | `check.bold` | `vivid` |
| `YELLOW` | `#B89C4F` | 노란 카드 | `triangle.alert` | `muted` |
| `RED` | `#7C4640` | 빨간 카드 | `diamond.pause` | `ghosted` |
| `SPECTATOR` | `#6E737E` | 관전 중 | `circle.half` | `monochrome` |

FE icon-glyph mapping (`FE/src/components/survival/iconMap.ts`):

| Abstract name | Concrete `@expo/vector-icons` glyph |
| --- | --- |
| `check.bold` | `MaterialIcons:check` |
| `triangle.alert` | `MaterialIcons:warning-amber` |
| `diamond.pause` | `MaterialIcons:pause-circle-outline` |
| `circle.half` | `MaterialIcons:visibility` |

The BE SVG renderer (Story 7.1) keeps its own SVG-symbol mapping keyed off the same abstract name in `tokens.json`, so FE and BE stay linked at the token layer without sharing glyph assets.

---

## 3. Typography

Weight whitelist: `300, 400, 600, 700, 800, 900`. Weight `500` is rejected by the schema.

| Role | size | lineHeight | weight | family |
| --- | --- | --- | --- | --- |
| `caption.sm` | 11 | 16 | 400 | — |
| `caption` | 12 | 18 | 400 | — |
| `body.sm` | 14 | 22 | 400 | — |
| `body` | 16 | 26 | 400 | — |
| `body.lg` | 18 | 30 | 400 | — |
| `label` | 14 | 20 | 600 | — |
| `label.lg` | 16 | 22 | 700 | — |
| `heading.sm` | 20 | 28 | 700 | — |
| `heading` | 24 | 32 | 700 | — |
| `heading.lg` | 32 | 40 | 800 | — |
| `display.sm` | 40 | 48 | 800 | — |
| `display` | 56 | 64 | 900 | — |
| `display.serif` | 56 | 64 | 700 | Nanum Myeongjo |

Tracking presets: `tight = -0.02em`, `normal = 0em`, `wide = 0.06em`.

Dynamic Type cap: `<Text>` shipped from `FE/src/components/ui/Text.tsx` sets `maxFontSizeMultiplier = 1.3`. The chip primitive inherits this; see `SurvivalChip.dynamic-type.test.tsx`.

---

## 4. Space

4 px base. No gaps at 7/9/11/13/14/15 (explicit per UX spec).

| Token | px |
| --- | --- |
| `space.0` | 0 |
| `space.1` | 4 |
| `space.2` | 8 |
| `space.3` | 12 |
| `space.4` | 16 |
| `space.5` | 20 |
| `space.6` | 24 |
| `space.8` | 32 |
| `space.10` | 40 |
| `space.12` | 48 |
| `space.16` | 64 |
| `space.24` | 96 |
| `space.layout.padding` | 20 |

---

## 5. Radius

| Token | px |
| --- | --- |
| `radius.none` | 0 |
| `radius.subtle` | 6 |
| `radius.default` | 10 |
| `radius.pronounced` | 14 |
| `radius.pill` | 9999 |

---

## 6. Elevation

Subtle-blur shadows. Hard-offset deprecated per SCP 2026-05-10.

| Token | value |
| --- | --- |
| `elevation.0` | `none` |
| `elevation.1` | `0 1px 2px rgba(0,0,0,0.4), 0 2px 4px rgba(0,0,0,0.2)` |
| `elevation.2` | `0 4px 12px rgba(0,0,0,0.5)` |
| `elevation.3` | `0 8px 24px rgba(0,0,0,0.6)` |

---

## 7. Blur

| Token | px |
| --- | --- |
| `blur.subtle` | 4 |
| `blur.modal` | 8 |

Values >= 12 px are rejected by the schema (A13 glassmorphism guard).

---

## 8. Motion

### 8.1 Duration

| Token | ms |
| --- | --- |
| `motion.duration.instant` | 0 |
| `motion.duration.fast` | 150 |
| `motion.duration.normal` | 250 |
| `motion.duration.slow` | 400 |
| `motion.duration.cinematic` | 1500 |

### 8.2 Easing

| Token | cubic-bezier |
| --- | --- |
| `motion.easing.standard` | `(0.4, 0, 0.2, 1)` |
| `motion.easing.entry` | `(0, 0, 0.2, 1)` |
| `motion.easing.exit` | `(0.4, 0, 1, 1)` |
| `motion.easing.gentle` | `(0.16, 1, 0.3, 1)` |
| `motion.easing.ritual` | `(0.65, 0, 0.35, 1)` |

### 8.3 Entry preset

| Field | value |
| --- | --- |
| `motion.entry.duration` | 250 |
| `motion.entry.easing` | `(0, 0, 0.2, 1)` |

---

## 9. Sub-mode override whitelist (16 keys)

The schema rejects any sub-mode override key outside this list. Story 1.5 expanded the original epics.md 12-key whitelist to 16 to cover the locked D3 Quiet and D5 Plate blocks. Cross-reference: `_bmad-output/planning-artifacts/epics.md` Story 1.5 AC L240 + `_bmad-output/planning-artifacts/ux-design-specification.md` §Token Surface Override 정책 L1027–1040 + §Sub-Mode Catalog D3 L1098–1110 + D5 L1140–1152.

| # | Key | Used by |
| --- | --- | --- |
| 1 | `color.bg.canvas` | D3.quiet |
| 2 | `color.bg.surface` | D2.bento, D3.quiet, D4.postcard |
| 3 | `color.bg.elevated` | D2.bento |
| 4 | `color.text.primary` | D3.quiet |
| 5 | `color.text.secondary` | D3.quiet |
| 6 | `color.stroke.default` | D5.plate |
| 7 | `typography.heading.weight` | all 5 |
| 8 | `typography.heading.tracking` | D1.editorial |
| 9 | `typography.display.serif.enabled` | D1.editorial, D4.postcard |
| 10 | `motion.entry.duration` | D1, D3, D4 |
| 11 | `motion.entry.easing` | D1, D3, D4 |
| 12 | `radius.default` | D1, D2, D5 |
| 13 | `radius.pronounced` | D4 |
| 14 | `elevation.1` | D1, D2, D5 |
| 15 | `elevation.2` | D4 |
| 16 | `space.layout.padding` | D1, D2, D5 |

Override semantics: each sub-mode block is a **flat dict** — no deep merge. `useTheme()` walks each dot-path on a deep clone of the base tokens and writes the leaf value. UX cross-cutting rule #9: leaf components MUST NOT read the `subMode` string — only `<SubModeProvider>` reads it, and downstream consumers receive override-merged tokens via `useTheme()`.

---

## 10. WCAG 2.2 AA contrast matrix

All pairs verified by `tools/contrast-check.ts` against the canonical `tokens.json` palette. Body pairs target ≥ 4.5:1; caption (large/bold) target ≥ 3.0:1.

| Pair | fg | bg | ratio | min | result |
| --- | --- | --- | --- | --- | --- |
| `text.primary` on `bg.canvas` (body) | `#F4F0EB` | `#1B1715` | 15.69 | 4.5 | PASS |
| `text.primary` on `bg.surface` (body) | `#F4F0EB` | `#241F1C` | 14.37 | 4.5 | PASS |
| `text.primary` on `bg.elevated` (body) | `#F4F0EB` | `#2D2724` | 12.97 | 4.5 | PASS |
| `text.secondary` on `bg.canvas` (body) | `#B7B0A6` | `#1B1715` | 8.28 | 4.5 | PASS |
| `text.primary` on `key.default` (CTA fill, body) | `#F4F0EB` | `#7E2C2A` | 8.12 | 4.5 | PASS |
| `text.inverse` on `bg.inverse` (Final-3 paper sheet) | `#241F1C` | `#F4F0EB` | 14.37 | 4.5 | PASS |
| `text.tertiary` on `bg.canvas` (caption: >= 18pt or bold >= 14pt) | `#7B756B` | `#1B1715` | 3.90 | 3.0 | PASS |
| (D3.quiet) `text.primary` on `bg.canvas` (body) | `#D8D1C7` | `#15110F` | 12.39 | 4.5 | PASS |
| (D3.quiet) `text.primary` on `bg.surface` (body) | `#D8D1C7` | `#1E1916` | 11.50 | 4.5 | PASS |
| (D3.quiet) `text.secondary` on `bg.canvas` (body) | `#928B81` | `#15110F` | 5.57 | 4.5 | PASS |

`text.tertiary` is scoped to caption usage only (>= 18pt or bold >= 14pt). Body usage at smaller sizes must use `text.secondary` or `text.primary`.

---

## 11. Enforcement chain (NFR-9.6.1, NFR-9.6.3)

| Layer | Mechanism | Failure mode |
| --- | --- | --- |
| JSON Schema | `tokens.schema.json` validated at `./gradlew validateTokens` (BE) + ajv at FE test time | CI fails with offending path + reason |
| BE codegen | `./gradlew generateTokens` emits `GeneratedTokens.java` from `tokens.json` | drift between FE and BE tokens compile-fails |
| BE arch guard | Checkstyle `RegexpSingleline` rules on `#hex` / `rgb(` / `rgba(` / `oklch(` outside `theme/` package | `./gradlew check` fails |
| FE brand-voice lint | `tools/brand-voice-lint.ts` Rule 1 (HARD GATE) | non-zero exit on missing sibling label for `survival.<STATE>.color` reads |
| FE component primitive | `<SurvivalChip>` is the only allowed entry point for survival rendering | downstream stories import the primitive instead of constructing chips ad hoc |
| FE provider | `<SubModeProvider>` is the only seat that touches the sub-mode string; leaves consume `useTheme()` | sub-mode prop drilling into leaves is a code-review reject |
| Contrast verifier | `tools/contrast-check.ts` against the canonical pair list (+ D3.quiet overrides) | non-zero exit on any AA failure |
| Dynamic Type | `<Text>` `maxFontSizeMultiplier=1.3`; `SurvivalChip.dynamic-type.test.tsx` snapshot guards it | jest snapshot diff on regression |

---

## 12. Migration policy

Story 1.5 ships the foundation. Each downstream story (1.6 WelcomeWindow, 1.7 RitualMoment, 2.1 Spectator routing, 3.2 Friend Gift, 3.4 Wallet UI, 6.1 Kakao preview card, 7.1 Final-3 SVG poster) migrates its own screens off the v1 `palette` / `colors` / etc. exports onto `useTheme()`. The v1 export surface in `FE/src/theme/tokens.ts` stays in place during the transition; values that would invert legibility under a blind v1→v2 swap remain v1-equivalent per AC11's readability exception clause until the owning story migrates them.
