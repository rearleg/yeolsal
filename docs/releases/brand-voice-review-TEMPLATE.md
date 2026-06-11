# Brand-Voice Release-Gate Review — `<version>`

> **TEMPLATE — copy to `docs/releases/brand-voice-review-<version>.md` before filling; do not sign
> this file.** This template carries `<…>` placeholders only. The signed instance is the per-release
> copy, approved in the release PR by one configured PM reviewer and one configured designer
> reviewer. The canonical checklist + governance is `docs/brand-voice-review.md`.

- **Release version:** `<fill>`
- **Date:** `<fill, YYYY-MM-DD>`
- **Release PR:** `<fill, #NNN>`

---

## Sign-off

The **authoritative approval is the GitHub PR review** on the release PR — the checkboxes below are a
human-readable mirror, not the gate. The required `Brand-voice release gate` workflow verifies both
roles using the repository reviewer variables. Two **distinct** accounts are required.

- PM reviewer (`@<handle>`): [ ] approved
- Designer reviewer (`@<handle>`): [ ] approved

---

## Automated pre-pass (attach output)

Run the fail-closed commands below and paste/link the output. Do not use a skipped tools block as
evidence.

```bash
npm --prefix tools ci
tools/node_modules/.bin/tsx tools/brand-voice-lint.ts
tools/node_modules/.bin/tsx tools/aso-copy-lint.ts
tools/node_modules/.bin/tsx tools/contrast-check.ts
```

- `tools/brand-voice-lint.ts` — **HARD count MUST be `0`**; WARN count: `<fill>`
  ```
  <paste the final brand-voice-lint summary line + any WARN lines>
  ```
- `tools/aso-copy-lint.ts` — exit `<fill>`, WARN count: `<fill>`
- `tools/contrast-check.ts` — `<pass/fail summary>`

---

## Surface checklists

### Push notification copy paths (BE + FE)
- [ ] BE: `notification/SpectatorDigestScheduler.java`, `NotificationService.java`, `NotificationScheduler.java`
- [ ] FE: `lib/notifications.ts`, `lib/push.ts`, notification-settings copy
- [ ] Invitation, not demand (FR-8.8.3); no AVOID-lexicon term

### All onboarding screens
- [ ] `app/onboarding.tsx` + `components/onboarding/OnboardingCarousel.tsx` (+ `OnboardingDotIndicator.tsx`)
- [ ] PIPA consent copy; explicit opt-in framing
- [ ] Matches FR-8.8.1 locked strings; no AVOID-lexicon term

### All error message strings
- [ ] BE: domain exceptions + `common/ApiExceptionHandler.java` user-facing text
- [ ] FE: `lib/toast.ts` + per-screen `ApiError.code` branch copy
- [ ] FE component-local `COPY`, alerts, mutation `onError`, and inline failure/disabled messages
- [ ] "컴백 가능" language, never "탈락" / "실패" (FR-8.8.5)

### All store metadata (KR + EN)
- [ ] `docs/aso-copy.md` — KR "회생권", EN "comeback pass", never "revival ticket" / "second chance pass"
- [ ] aso-copy-lint pre-pass clean

### System message templates in chat_messages
- [ ] `room/chat/ChatService.java` `publish…SystemMessage` bodies in voice
- [ ] Any new SYSTEM template added this release reviewed
- [ ] No AVOID-lexicon term; dignity tone holds (even the "no survivors" case)

---

## Decision log

| Surface | Item / file:line | Lint? | Decision (accept/reject/needs-rewrite) | Reviewer (@handle) | Date | Notes |
|---|---|---|---|---|---|---|
| `<surface>` | `<file:line>` | `<yes/no>` | `<accept/reject/needs-rewrite>` | `@<handle>` | `<YYYY-MM-DD>` | `<note; for needs-rewrite use disabled: control; evidence: https://...>` |

---

**SLA reminder:** a `needs-rewrite` item is fixed within **1 business day**; if the next release window
is **< 24h** away, the affected feature is **feature-flagged off** rather than shipped with an
unresolved flag. For v1, record the concrete environment/build/runtime control (or release-branch
revert) and evidence URL in Notes as `disabled: <control>; evidence: <https URL>`. No override.
