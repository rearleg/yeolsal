---
stepsCompleted: [1, 2, 3, 4, 5, 6]
inputDocuments:
  - '_bmad-output/brainstorming/brainstorming-session-2026-05-09-2305.md'
  - '_bmad-output/project-context.md'
workflowType: 'research'
lastStep: 6
research_type: 'market'
research_topic: 'Group habit-tracking / accountability / streak-based mobile apps with gamified survival mechanics — global + Korean market'
research_goals: 'Validate the differentiation thesis behind the 열살방 (todo-survival) pivot, map direct + indirect competitors, size the addressable market, benchmark monetization (revival ticket / cosmetic IAP), surface platform-policy risk, and ground the BM in human-behavior evidence.'
user_name: 'rearleg'
date: '2026-05-09'
web_research_enabled: true
source_verification: true
---

# Research Report: Market

**Date:** 2026-05-09
**Author:** rearleg
**Research Type:** Market

---

## Research Overview

This report scopes the market for **group-based, gamified habit-tracking apps with survival / streak / loss-aversion mechanics**, with a primary focus on Korea and a secondary lens on global products. It is conducted to support the proposed pivot of `yeolsal` from a generic todo-sharing service into **"열살방 — todo-survival"** (concept captured in [`brainstorming-session-2026-05-09-2305.md`](../../brainstorming/brainstorming-session-2026-05-09-2305.md)).

### Methodology

- Primary technique: web search across English- and Korean-language sources, multiple independent confirmations for sizing and player claims.
- Source quality: market sizing from third-party report aggregators (treated as directional, not authoritative); product/user signals from app stores, official blogs, and Korean tech press; behavioral evidence from product owners' own writeups and analyst breakdowns.
- Where two sources disagreed, both are surfaced with a confidence note rather than averaged.
- Korean market data is treated as the authoritative read for the pivot's home market; global data informs platform-policy and behavioral-mechanic priors.

### Confidence Note on Market Sizing Numbers

Aggregator reports for the global "habit-tracking app market" disagree by an order of magnitude (e.g., USD 1.3B vs USD 14.9B for 2026). They use different segment definitions (some include B2B wellness, productivity SaaS, corporate wellness platforms; others restrict to consumer mobile apps). Treat any single market-size number as **directional** in this report, and prefer **player-level signals** (Habitica/Stickk/Squad/Duolingo/챌린저스 specific numbers) for product decisions.

---

## Market Sizing & Dynamics

### Global

- Habit-tracking app market valued at **USD 11.42B in 2024**, projected to **USD 13.06B in 2025** and **USD 14.94B in 2026**, with CAGR commonly reported around **~14–15%** through 2034. ([Cohorty Habit Tracker Comparison 2025](https://www.cohorty.app/blog/habit-tracker-comparison-2025-12-apps-tested-free-vs-paid), [Global Growth Insights — Habit Tracking App Market Outlook](https://www.globalgrowthinsights.com/market-reports/habit-tracking-app-market-100455), [Wiseguy Reports — Habit Tracker App Market](https://www.wiseguyreports.com/reports/habit-tracker-app-market))
- Demand drivers cited across reports: 64% wellness-driven adoption, 46% rise in productivity tracking, 59% corporate integration; 58% AI-feature adoption, **61% gamified-usage boost**, 49% wearable sync. The *gamified-usage* number is the most relevant prior for a survival-mechanic pivot.
- Players: Productive Habit Tracker, Streaks, Habitica, Habitify, Beeminder, Stickk, Strides, Habi, Habitat, Squad, Cohorty.

### Korea

- **챌린저스** (Whitecube), the dominant deposit-and-refund habit platform in Korea, reached **1.71 million users by March 2024**. The product was repositioned in 2023 toward a **beauty-discount marketplace** (deposit-refund mechanic remains, but the surface area shifted to product redemption). Annual revenue **₩15B (KRW 15 billion) in 2024**, first profitable year in 2023. ([Platum: 챌린저스 첫 연간 흑자](https://platum.kr/archives/226879), [Platum: 뷰티 앱으로 피봇한 챌린저스 2024년 매출 150억](https://platum.kr/archives/249818), [Brunch: 챌린저스 분석](https://brunch.co.kr/@bydot/9))
- 챌린저스's primary user demographic: **20–40대 여성** focused on self-management.
- The pivot of 챌린저스 *away* from pure habit-formation toward beauty-product-discounting is itself a strong market signal: a financial-stake commitment device alone struggled to be sustainably profitable in Korea without a redemption marketplace overlay.
- We did not find a Korean app with a "todo-survival" framing. Adjacent competitors are deposit-refund (챌린저스), study-group apps (limited public data), and 만보기 / steps-based loyalty in fintech (토스 만보기).

### Implications for 열살방

- The market is real and growing, but **the dominant mechanic in Korea is deposit-refund, not survival/elimination**. That is both an opportunity (whitespace in mechanic) and a risk (deposit-refund is what users have learned to associate with "habit app" in Korea).
- Pivoting to a non-monetary survival mechanic with cosmetic + group-gifticon BM avoids competing on 챌린저스's home turf (deposit-refund) and avoids the platform-policy risk of being classified as a financial commitment device.
- The market-sizing disagreement is a reminder to write the PRD against **specific player numbers** (Habitica retention lift, Squad cohort sizes, 챌린저스 user count) rather than a top-level TAM.

---

## Customer Insights & Behavior

### Behavioral Evidence (Loss Aversion, Streaks, FOMO)

- Duolingo's product blog and external analyses document that **streaks rely on two psychological effects: loss aversion and sunk cost**. Loss aversion begins to lock in retention **around day 7**, after which churn drops sharply. ([Just Another PM: Psychology Behind Duolingo's Streak Feature](https://www.justanotherpm.com/blog/the-psychology-behind-duolingos-streak-feature), [Duolingo Blog: How Duolingo Streak Builds Habit](https://blog.duolingo.com/how-duolingo-streak-builds-habit/))
- Duolingo's **streak freeze** (one-day skip) was a deliberate sustainability tweak: it preserves the streak narrative for casual users without breaking the loss-aversion loop. Result: DAU went from ~16M (2021) to >30M (2023), a **4.5× DAU increase over four years** with **21% lift in current-user retention** attributed largely to this loop. ([Audiencers — Duolingo's Streak Retention](https://theaudiencers.com/55-learn-from-duolingos-impressive-streak-retention-strategy/), [TryPropel — Duolingo Customer Retention Strategy 2026](https://www.trypropel.ai/resources/duolingo-customer-retention-strategy))
- Duolingo monetizes the *emotional* investment via gems used to buy heart refills, streak freezes, and XP boosts — the streak-freeze paywall is the canonical example of monetizing loss aversion non-extractively. ([Duolingo Blog](https://blog.duolingo.com/how-streaks-keep-duolingo-learners-committed-to-their-language-goals/))
- Habitica reported a **39% retention lift and 51% boost in social-engagement metrics** after introducing community-driven group challenges in mid-2023, particularly among **Gen Z**. ([Cohorty Habit Tracker Comparison 2025](https://www.cohorty.app/blog/habit-tracker-comparison-2025-12-apps-tested-free-vs-paid))

### Korean User Behavior

- Korean self-management consumers (the 챌린저스 base) are willing to put their own money on the line (deposit-refund) — but the model needed a marketplace pivot to be profitable. This suggests **stake without redemption is a hard sell**: users want a tangible "win" beyond not losing money.
- 챌린저스 demographic skew (20–40대 여성) is broad enough that 열살방 should not pre-segment too tightly; tenure and intent matter more than demographic.
- Korean group-chat culture (KakaoTalk-native FOMO, read-receipt social pressure, 인증 culture in 단톡방s) is a **free behavioral substrate** the pivot can lean into. The brainstorming session captured this in items 119–120 (read-receipt-light chat, group mute) and 115 (weekly check-in thread).

### Customer Segments to Test

| Segment | Why they care | Acquisition path |
|---------|---------------|------------------|
| 친구 그룹 (3–8명) 자기관리 동호인 | "함께하고 싶다" tension is the hook; existing friend graph is the cohort | Invite-code virality from existing yeolsal user base |
| 학습/시험 코호트 (수능/공무원/고시/토익) | High structural commitment, weeks-long timeline matches "survival" framing | Theme rooms (Tier-2 idea #13); SEO on 100일 calendars |
| 운동/식단 동호회 | Existing group-chat habit; group-gifticon BM directly maps to existing 단체 인증 culture | 운동방 preset; partnership with fitness creators |
| 직장 30일 온보딩 / 회사 동아리 | B2B-lite vertical (Tier-3 idea #35) — can pay per-seat | After MVP product-market signal |
| 회복 그룹 (sobriety / 다이어트 동호회) | Survival metaphor is a real fit, but ToS/safety is heavy lift (Tier-3 idea #33) | Defer until brand maturity |

---

## Customer Pain Points

Synthesized from existing-product reviews, the brainstorming session, and analyst breakdowns:

1. **Generic todo apps (Things, Todoist, Notion) feel lonely.** They optimize for individual productivity, not social pressure. Habitica's 39% retention lift after adding *group* mechanics is the headline evidence here.
2. **Pure financial-stake apps (Stickk, 챌린저스) feel transactional.** Users put $51M on the line via Stickk (533K commitments), but the model has not produced a globally dominant consumer brand. The lack of *positive group narrative* leaves the experience cold; 챌린저스's pivot toward a redemption marketplace is a tell.
3. **Streak apps feel brittle.** "Lost a 200-day Duolingo streak because I missed one day" is a meme. Without a streak-freeze affordance, users churn out of fear rather than re-engaging. Loss aversion is double-edged.
4. **Group-chat-based accountability (KakaoTalk 단톡방) lacks structure.** Posting "오늘 운동 인증" in a 단톡방 has FOMO + social pressure, but no consequences, no automation, no ceremony, and no shareable record.
5. **B2C accountability apps (Squad, Habitat, Cohorty, Habi) are English-first and culturally non-native** for Korean users. Squad's 10–30 day cohort sizing (8 members max) is striking — same number as yeolsal's existing room cap — yet it has no Korean localization or KakaoTalk-native invite flow. ([Squad — Apps on Google Play](https://play.google.com/store/apps/details?id=co.joinsquad.app&hl=en_US), [Cohorty — Best small group accountability apps 2025](https://www.cohorty.app/blog/small-group-accountability-apps-complete-guide-for-2025))
6. **Eliminated users are usually frozen out completely.** None of the surveyed apps make spectator-mode a feature. The brainstorming session's Tier-1 "read-only spectator" idea (#78) is therefore a real whitespace.
7. **Revival flows are crude.** Habitica heals you when the party heals; Duolingo charges gems; Stickk takes your money on fail. None reframe revival as **a gift to the group**, which is the 열살방 hypothesis.

---

## Customer Decisions & Triggers

### Why People Try a Habit App

- Major life transitions (졸업, 입사, 이사, 새해 등 calendar triggers).
- Social proof from a friend or 단톡방 ("쟤가 100일 했다 → 나도").
- Specific exam / event D-Day creating a forcing function.

### Why They Stay (or Churn)

- **Stay**: stake (financial or social), narrative (named cohort, theme), visible accumulation (calendar art, chips), peer pressure (read receipts, ambient activity).
- **Churn**: brittle streaks, shame events, lack of progress visibility, app feels solo or transactional.

### Decision Drivers Specific to 열살방

| Decision | Trigger | Counterpart |
|----------|---------|-------------|
| Join a group at signup | Friend invite-code or theme preset (운동방 / 공부방) | Brainstorming items #87 (room previews) and #13 (themed rooms) |
| Spend the free revival ticket | First fail, social embarrassment, FOMO from spectator mode | Items #78 (spectator), #92 (revival as gift) |
| Buy the second revival ticket | "I'm in too deep / my group needs the points" | Items #11 (named donor), #50/63 (price cap) |
| Convert from free → paid | Cosmetic / room banner / sticker drop, not paywalled survival | Tier-1 / item #111 (cosmetic-only premium) |
| Recruit a friend | Mutual streak-shield reward (#90) without pyramid pressure (#68 banned) | Built into the room-discovery flow |

---

## Competitive Landscape

### Direct Competitors (Group habit / streak apps)

| Player | Mechanic | Group size | BM | Korea presence | Closest to 열살방? |
|--------|----------|------------|-----|----------------|--------------------|
| **Habitica** | RPG party + boss-fight; missed dailies hurt the whole party | Up to ~30 | Freemium + cosmetic IAP | None native | **Closest psychologically** — the "boss damages everyone" loop is the survival-metaphor cousin. ([Cohorty 2025](https://www.cohorty.app/blog/habit-tracker-comparison-2025-12-apps-tested-free-vs-paid)) |
| **Squad** (joinsquad.co) | 10–30 day cohort, daily check-ins, automated rules; up to 8/group | **8 max** (identical to yeolsal's room cap) | Subscription | None native | **Closest structurally** — same group cap, same time-box, no survival framing yet. ([Squad — App Store](https://apps.apple.com/us/app/squad-habit-accountability/id6443996585)) |
| **Cohorty** | Free small-group accountability, unlimited challenges | 3–10 | Free | None native | Direct functional overlap; weak on social pressure |
| **Habitat** | Group habits and group streaks | Small group | Freemium | None native | Group-streak loop — adjacent to "survival" framing |
| **Stickk** | Financial commitment device (US) | Solo (with referee) | Cut of forfeited stake | None native | $51M users-money on the line, 533K commitments — proves stake-based motivation works at scale, but solo-first ([Stickk — Habi](https://habi.app/insights/accountability-apps/)) |
| **Beeminder** | Solo financial commitment + numerical goal | Solo | Pay-on-fail | None native | Quant-first; not socially-motivated |

### Korea-specific Competitors

| Player | Mechanic | Notable |
|--------|----------|---------|
| **챌린저스** (Whitecube) | Deposit-refund (85%+ inspection rate refunds full deposit; 100% inspection earns prize pool) | 1.71M users (Mar 2024). Pivoted in 2023 toward **beauty discount marketplace** — a redemption layer. ₩15B 2024 revenue. First annual profit 2023. ([Platum article 1](https://platum.kr/archives/226879), [Platum article 2](https://platum.kr/archives/249818), [챌린저스 약관](https://biz-challengers.com/terms)) |
| **토스 만보기** | Step-tracking → daily chips; redeemable for tiny rewards | Massive distribution leverage (Toss user base) but no group survival mechanic |
| **Naver Cafe / KakaoTalk 단톡방 인증** | Cultural baseline ("free" group accountability) | The substrate the pivot competes against — must be obviously better than a 단톡방 |
| **카카오톡 챌린지 / KakaoTalk 인증샷 culture** | Voluntary social proof | Free, unstructured. The pivot's job is to formalize this. |

### Indirect Competitors / Lateral Validation

- **Duolingo** — streak freeze + gem economy is the canonical proof that loss aversion can be monetized non-extractively. ([TryPropel — Duolingo Customer Retention 2026](https://www.trypropel.ai/resources/duolingo-customer-retention-strategy))
- **Strava** — kudos + segment leaderboards as comeback-of-the-week analog (brainstorm items #100–101).
- **만보기 / step-collection products** — chip dopamine without monetary stakes (item #98).

### Whitespace 열살방 can Occupy

1. **Group-survival framing** — Habitica is closest, but its RPG aesthetic is too niche; Squad has the structure but no narrative tension. None are KR-native or design-distinct.
2. **Group-positive economy** — Stickk's forfeited stake is anti-narrative ("I lost"); 열살방's revival-becomes-group-gifticon is positive-sum and reframes paying as "buying the group a coffee".
3. **Read-only spectator mode** — surveyed competitors lock eliminated users out; spectator + FOMO is unbuilt territory.
4. **Risograph / neobrutalist visual identity** — habit apps trend toward minimalist Calm/Notion aesthetics; the existing yeolsal design system (`design-system.md`) is genuinely differentiated and reusable.
5. **KakaoTalk-native invite flow** — Squad/Habitica have no native KR distribution. Yeolsal can ship invite-codes that propagate inside 단톡방s.

---

## Platform & Regulatory Risk

### App Store / Google Play Policy

- Apple App Review Guidelines require gambling-style apps to include responsible-gaming features (deposit limits, self-exclusion, reality checks) and apply **age 19+ minimum in South Korea**. Apps that *resemble* gambling — i.e., deposit-and-payout — risk being flagged into the higher rating bucket. ([Apple Developer — App Review Guidelines](https://developer.apple.com/app-store/review/guidelines/), [Rachel Evans — How Apple Regulates Gambling Apps](https://rachelevans.techraisal.com/blog/how-apple-regulates-gambling-apps-on-the-app-store/), [Casino.org — Apple NC-17 Rating](https://www.casino.org/news/apple-is-requiring-gambling-apps-to-come-with-17-ratings/))
- Implication for 열살방: a fixed-price revival ticket with a free first ticket is **not** a deposit-refund commitment device and should fall well outside the gambling bucket. **Randomized revival pricing (brainstorm item #62) is a hard no**, as is tying revival cost to streak length above the entry price (brainstorm item #63 already flagged this guardrail).
- The 챌린저스 deposit-refund model itself walks the line — there's a body of practice for staying compliant in KR (the Korean app shows this is feasible) but it adds non-trivial review friction.

### Korean Regulatory

- Korea has a 19+ age floor for gambling apps. Brainstorm item #41 (no buyable revival under 19) maps directly to this risk and should remain in v1.
- The Personal Information Protection Act (PIPA) and Apple's account-deletion guidelines together require export-and-delete flows; brainstorm items #95–97 (export PDF, return-without-loss, strict unsubscription) align.

### Implication

The 열살방 mechanic, *as designed in the brainstorming session's Tier-1*, sits comfortably outside gambling/commitment-device classification: fixed cosmetic + free-tier revival, group-redeemable gifticon (not cash), capped purchase frequency. The risk surface is manageable and the current `StartupConfigValidator` + secret-management posture (per `_bmad-output/project-context.md`) gives a head start on KR data-handling compliance.

---

## Strategic Recommendations

### Differentiation Thesis

> 열살방 wins by being the only product that **gamifies group survival without humiliation**, monetizes through *group-positive* economy (revival = gift), and ships with a culturally distinct visual identity native to Korean group-chat life.

This thesis is supported by:

- Habitica's 39% retention lift confirming the *social* lever exists.
- Duolingo's 4.5× DAU + 21% retention lift confirming the *streak + loss-aversion* lever works at scale.
- 챌린저스's 1.71M-user reach + pivot to beauty marketplace confirming KR demand AND that a positive-sum redemption layer is necessary for retention.
- Squad's near-identical structure (8-member cohort, time-boxed) confirming a market validates the cohort shape, but having no Korean presence yet.

### What to Build First (PRD Spine)

1. **Single-group, mandatory join with 14-day grace trial** (combats churn; lower stakes during onboarding). Brainstorm item #38, validated by Squad's 10-day baseline.
2. **Daily-update rule with weekend toggle** (already in user-supplied concept) + **streak freeze 1/month** (item #21/105, confirmed by Duolingo) + **two-strike yellow→red within rolling 7d window** (item #26).
3. **Soft-public elimination** (visible to user + leader; broad group sees only after 24h cooldown). Combats shame risk surfaced in brainstorm items #47–60.
4. **Free first revival + fixed-price revival ticket (₩1,500 ceiling) + personal points alternative**. Avoids gambling classification; brainstorm items #49/50/63.
5. **Group point pool → platform-issued gifticon (named donor)**. The differentiating BM. Brainstorm items #11/53.
6. **Read-only spectator mode for eliminated users**, with monthly batch revival opportunity. Brainstorm items #78/108.
7. **Themed rooms (운동방 / 공부방 / 글쓰기방) with rule presets**. Distribution + onboarding surface. Brainstorm item #13.
8. **Monthly Final-3 Risograph poster ceremony**. Reuses existing design system; makes survival shareable. Brainstorm item #109.

### What to Test Before Ship

- **Revival pricing curve** (free 1 → points → ₩1,500 fixed → cosmetic bundle) — brainstorm Tier-2 item to A/B.
- **Group cap (8 vs. 12 vs. 30)** — Squad uses 8; Habitica allows ~30; pick by what social ambient density feels right in KR.
- **"Weekend-include off" semantics under 06:00 KST** — define edge cases before any code lands (item #5 in open questions).
- **Verified-by-teammate todo proof** — brainstorm item #14; toggleable per-room to fight gaming without imposing surveillance.

### What to Avoid

- **Deposit-refund mechanic.** That is 챌린저스's home turf; entering would invite (a) policy review friction, (b) head-on competition with a 1.71M-user incumbent, and (c) the same retention ceiling 챌린저스 hit before pivoting to beauty.
- **Public death icons / failure flair on the user's grass.** Permanent stigma kills the dignity differentiator. Brainstorm item #70 banned.
- **Pyramid-style "revive by inviting humans".** Brainstorm item #68 banned; ToS-unsafe and would erode trust.
- **Location verification of physical-world todos.** Brainstorm item #67 banned; surveillance.

### Go-to-Market Hypothesis

- **Initial wedge**: existing yeolsal user base + their friend graph (room invite codes). Convert one room → seed at least 100 rooms.
- **Distribution surface**: themed rooms (#13) + Risograph poster as social asset (#109/122) + KakaoTalk-native invite flow.
- **Influence path**: small KR creator partnerships with 100일 challenges (수능 D-day, 다이어트 100일, 독서 100일).
- **Pricing experiment**: free first revival → ₩1,500 fixed; A/B against pure cosmetic monetization to find the BM with the cleanest retention curve.

---

## Open Questions Raised by This Research

1. What does **챌린저스's pivot away from pure habit-formation** tell us about the ceiling of a habit-app product without an embedded marketplace? Should 열살방 plan a redemption marketplace from the start (group-redeemable gifticons are a soft form of this), or treat it as a phase-2 expansion?
2. **Does the survival metaphor translate outside Korea?** The Risograph aesthetic and KakaoTalk distribution path are KR-native; the mechanic is universal. International expansion vs. KR-deep-cohort is a strategic fork to decide before architecture finalizes.
3. **What is the legitimate price ceiling for a revival ticket** that does not trip Apple's gambling classification? Anecdotal precedent from Duolingo's gem pricing and 챌린저스's deposit ranges suggests ≤ ₩2,000 is safe, but explicit Apple review precedent would harden this.
4. **Should the room-cap be 8 (Squad), 12, or 30 (Habitica)?** A live experiment with two cohorts would settle this fast. The existing V6 migration's `min_daily_goal_days ∈ {10,15,20,31}` already accommodates either choice.
5. **Where does an eliminated user's chat live?** The single biggest UX-and-engineering decision for the spectator mode. Active room (read-only)? Ghost room? Friend DMs? Affects the WS topic schema and `room_members` visibility logic.

---

## Recommended Next BMad Step

Take this report into:

- **`/bmad-prfaq`** — Working Backwards stress-test of the differentiation thesis. The thesis statement above is the candidate "press release first paragraph".
- **`/bmad-cis-innovation-strategy`** — pressure-test the BM against 챌린저스's redemption-marketplace pivot lesson.
- **`/bmad-create-prd`** (after PRFAQ) — feed this report + brainstorming Tier-1 + `docs/index.md` + `_bmad-output/project-context.md` as input documents.

This research directly supports the brainstorming session's Tier-1 list and adds two new must-keeps:
- **Streak freeze** (Duolingo evidence is overwhelming).
- **Themed rooms with KakaoTalk-native invite virality** (no surveyed competitor occupies this).

---

## Sources

- [Cohorty — Habit Tracker Comparison 2025: 12 Apps Tested](https://www.cohorty.app/blog/habit-tracker-comparison-2025-12-apps-tested-free-vs-paid)
- [Cohorty — Best Small Group Accountability Apps 2025](https://www.cohorty.app/blog/small-group-accountability-apps-complete-guide-for-2025)
- [Global Growth Insights — Habit Tracking App Market Outlook 2025–2034](https://www.globalgrowthinsights.com/market-reports/habit-tracking-app-market-100455)
- [Wiseguy Reports — Habit Tracker App Market Analysis 2035](https://www.wiseguyreports.com/reports/habit-tracker-app-market)
- [Habi — 6 Best Accountability Apps in 2026](https://habi.app/insights/accountability-apps/)
- [Squad — App Store listing](https://apps.apple.com/us/app/squad-habit-accountability/id6443996585)
- [Squad — Google Play listing](https://play.google.com/store/apps/details?id=co.joinsquad.app&hl=en_US)
- [Squad — joinsquad.co](https://www.joinsquad.co/)
- [Just Another PM — Psychology Behind Duolingo's Streak Feature](https://www.justanotherpm.com/blog/the-psychology-behind-duolingos-streak-feature)
- [Audiencers — Learn from Duolingo's Streak Retention Strategy](https://theaudiencers.com/55-learn-from-duolingos-impressive-streak-retention-strategy/)
- [Duolingo Blog — How Duolingo Streak Builds Habit](https://blog.duolingo.com/how-duolingo-streak-builds-habit/)
- [Duolingo Blog — How Streaks Keep Duolingo Learners Committed](https://blog.duolingo.com/how-streaks-keep-duolingo-learners-committed-to-their-language-goals/)
- [TryPropel — Duolingo Customer Retention Strategy 2026](https://www.trypropel.ai/resources/duolingo-customer-retention-strategy)
- [Platum — 챌린저스 2023년 첫 연간 흑자 달성](https://platum.kr/archives/226879)
- [Platum — 뷰티 앱으로 피봇한 챌린저스 2024년 매출 150억 원](https://platum.kr/archives/249818)
- [Brunch — 이제 습관 말고 제품도 챌린지 하세요 '챌린저스' 분석](https://brunch.co.kr/@bydot/9)
- [챌린저스 — 이용약관 (deposit/refund mechanics)](https://biz-challengers.com/terms)
- [Apple Developer — App Review Guidelines](https://developer.apple.com/app-store/review/guidelines/)
- [Rachel Evans — How Apple Regulates Gambling Apps](https://rachelevans.techraisal.com/blog/how-apple-regulates-gambling-apps-on-the-app-store/)
- [Casino.org — Apple NC-17 Rating for Gambling Apps](https://www.casino.org/news/apple-is-requiring-gambling-apps-to-come-with-17-ratings/)
