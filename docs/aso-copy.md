# ASO Copy Lock — App Store + Google Play (KR storefront)

> Version-controlled storefront metadata for **yeolsal** (열살방). This file is the
> single source of truth for the App Store Connect + Google Play Console listing copy,
> the category/rating lock, and the screenshot manifest.
>
> **Korean** name + description use **"회생권"** naturally; **English** name +
> description use **"comeback pass"**. The phrases "revival ticket" and "second chance pass" are banned and must never appear in EN copy (they read as gambling-adjacent in automated content scans).
>
> Enforcement: `tools/aso-copy-lint.ts` (warn-only) scans the copy regions below on
> every `scripts/test.sh` run. The hard release gate is Story 8.4's joint PM +
> designer sign-off (CODEOWNERS-enforced). See the **Governance** section.
>
> The storefront strings below are **provisional pending Story 8.4** (release-gate
> review holds final wording authority). They are AVOID-lexicon-clean and ASO-clean
> by construction; a Story 8.4 revision is expected, not a defect.

---

## Locked storefront copy

The copy that ships to the consoles lives between the region markers below. The lint
scans **only** between the markers; everything outside them (rules, references, the
named banned phrases) is documentation and is intentionally not scanned.

Character limits are the App Store Connect / Google Play Console hard caps. The locked
strings already fit; a future editor who changes a value **must re-verify the exact
count in the console** before submission.

### Korean (한국어) — KR storefront

<!-- aso:copy:kr:start -->

| Field | Limit | Locked value |
|---|---|---|
| App name / Title | 30 | `열살방: 함께 살아남는 그룹 습관` |
| Subtitle (iOS) / Short description (Android) | 30 / 80 | `친구와 매일 약속을 지키고, 빠진 친구는 회생권으로 다시 살리는 그룹 습관 앱.` |
| Promotional text (iOS) | 170 | `혼자 지키기 어려운 약속도 친구와 함께라면 끝까지 갈 수 있어요. 빠진 친구는 회생권으로 다시 부르고, 우리 방의 포인트는 함께 쌓여요.` |
| Keywords (iOS) | 100 | `습관,그룹습관,친구,루틴,약속,동기부여,함께,회생권,컴백,동료` |
| Description (full) | 4000 | (KR block below) |

KR full description (App Store Connect description / Play Console full description):

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

<!-- aso:copy:kr:end -->

### English — EN store-algorithm metadata

> English copy is for store discovery algorithms only (NFR-9.7.3); v1 is a KR-storefront
> launch (no separate EN locale storefront).

<!-- aso:copy:en:start -->

| Field | Limit | Locked value |
|---|---|---|
| App name / Title | 30 | `Yeolsal: Survive Together` |
| Subtitle (iOS) / Short description (Android) | 30 / 80 | `Keep daily promises with friends. Miss a day? A comeback pass brings you back.` |
| Promotional text (iOS) | 170 | `Promises are hard to keep alone. With friends, you go all the way. Bring someone back with a comeback pass, and watch your room's shared pool grow together.` |
| Keywords (iOS) | 100 | `habit,group habit,friends,routine,accountability,together,comeback,daily,streak,wellness` |
| Description (full) | 4000 | (EN block below) |

EN full description:

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

<!-- aso:copy:en:end -->

---

## Category + rating lock

The listing ships in a **standard, non-gambling, non-Games** category so the
first-submission store-policy review passes without a rating escalation (PRD KPI).

| Surface | Setting |
|---|---|
| App Store primary category | **Health & Fitness** |
| App Store secondary category | Social Networking |
| Google Play category | **Health & Fitness** |
| App Store age rating | **4+** |
| Google Play content rating | **Everyone** |

Hard constraints (do not relax):

- **Not Games. Not Casino. Not any gambling-adjacent category.** A clean "comeback pass"
  copy still trips a review heuristic if the app is filed under Games/Casino — copy,
  category, and rating must be consistent end to end.
- The app has **no payment surface** and **no random/variable pricing** in v1. The rating
  questionnaire must answer **"No"** to every gambling, "simulated gambling," "contests,"
  in-app-purchase, and "unrestricted web" prompt.
- The PM may choose any standard category, but the gambling/Games exclusion above is not
  negotiable.

**Rationale:** the "comeback pass" wording removes the gambling signal from the *copy*;
the standard category + 4+/Everyone rating + all-"No" questionnaire removes it from the
*classification*. Both halves are required for a clean first-submission pass — a clean
copy under a Games category, or a standard category with gambling-adjacent wording, can
each still trigger a re-review.

---

## Screenshot manifest (Oxblood Editorial — Architecture §4.16)

Screenshots are produced from the **yeolsal v2 (Oxblood Editorial)** build and uploaded
manually. No binary assets are committed in this repo.

### Required shots (KR primary captions / EN captions)

| # | Shot | KR caption | EN caption |
|---|---|---|---|
| 1 | Onboarding concept | `혼자가 아니라, 함께 살아남는 습관` | `Survive your habits together, not alone` |
| 2 | Today survival roster | `매일 06시, 우리 방의 오늘을 확인해요` | `Every day at 06:00 KST, check your room` |
| 3 | Wallet (free comeback pass visible) | `가입하면 회생권 한 장을 바로 드려요` | `Every account gets one free comeback pass` |
| 4 | Friend-gift / revival moment | `빠진 친구를 회생권으로 다시 불러요` | `Bring a friend back with a comeback pass` |
| 5 | Final-3 monthly ceremony / room pool | `끝까지 함께 간 우리, 포인트도 함께 쌓여요` | `Go all the way together — the pool grows with you` |

Caption copy follows the same rules as the storefront copy: KR captions use no AVOID-lexicon
term; EN captions use "comeback pass" and never the banned phrases.

### Mandate

- Screenshots **must** be captured from the Oxblood Editorial (yeolsal v2) build —
  `FE/src/theme/tokens.json` palette, §4.16 token codegen — **not** the deprecated v1
  Risograph / Neobrutalist palette (Sprint Change Proposal 2026-05-10, architecture.md:148).

### Post-merge user action

Actual PNG capture (iPhone 6.7" / 6.5" / 5.5" + iPad if applicable; Android phone / tablet)
and upload to App Store Connect / Google Play Console is **manual and out of repo scope**.
This file locks the shot list, captions, and the token mandate only.

---

## Governance — the rule future ASO edits check against

This doc is self-describing so the lint is discoverable and future updates run through the
same check (realizes epics AC3).

- **KR storefront copy** uses "회생권" and never any of the 8 AVOID-lexicon terms
  (Architecture §5.5).
- **EN storefront copy** uses "comeback pass" and never the two banned noun phrases
  documented in the Reference section below (PRD FR-8.8.4).
- **Standard category, 4+ / Everyone rating**, with "No" to every gambling / contest /
  payment declaration (Category + rating lock above).

**Enforcement:**

- `tools/aso-copy-lint.ts` runs in `scripts/test.sh` (**warn-only** — it reports
  violations but never fails CI; severity is locked to WARN by Architecture §4.15).
- The **hard gate** is Story 8.4's release-gate review: a joint PM + designer sign-off,
  CODEOWNERS-enforced (epics.md:1058–1083). "All store metadata (KR + EN)" is item 4 of
  the 8.4 checklist; **this file is the artifact that item reviews.**

---

## Reference — locked phrase / lexicon table (do not change)

Sources of truth: product-brief-yeolsal.md:168, PRD FR-8.8.4 (prd.md:446),
NFR-9.7.3 (prd.md:505), Architecture §5.5 (architecture.md:543).

This section is documentation; it sits outside every copy-region marker so the lint does
not flag the phrases it names as banned.

- **Required EN phrase:** `comeback pass`.
- **Required KR term:** `회생권`.
- **Banned EN noun phrases (case-insensitive; never use in EN copy):** `revival ticket`,
  `second chance pass`. These two phrases are banned because automated store content scans
  read them as gambling-adjacent even though no money changes hands; "comeback pass"
  carries the same meaning without that signal. Note: the verb "revive" / "reviving" and
  the noun "comeback" are fine — only these two exact noun phrases are banned.
- **KR AVOID lexicon (8 terms; never use in KR copy):** `벌금`, `잃었다`, `떨어졌다`,
  `실패`, `자책`, `부담`, `패배`, `죄책감`. The lint imports this list read-only from
  `tools/brand-voice-lint.ts` (`__testing.AVOID_LEXICON`) so the two checks can never drift.

Char-limit note: App name ≤ 30, subtitle ≤ 30 (iOS) / 80 (Android), promotional text ≤ 170,
keywords ≤ 100, full description ≤ 4000. A future reviewer who rewrites a value must
re-verify the count in the console; do not silently exceed a cap.
