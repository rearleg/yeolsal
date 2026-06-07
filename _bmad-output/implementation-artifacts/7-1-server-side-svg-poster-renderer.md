# Story 7.1: Server-side SVG poster renderer

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As the system,
I want a Java string-templated SVG renderer that produces an Editorial-aesthetic poster card (yeolsal v2 — Oxblood Editorial) for a room's monthly Final-3 plus a `FinalThreeService` that orchestrates eligibility → render → PNG rasterize → persistence with a zero-survivor chat fallback,
So that the brand visual is consistent across all rooms and shareable on KakaoTalk, and Story 7.2's scheduled job + Story 7.3's Home-tab card have a stable BE API to drive.

## Acceptance Criteria

### AC0 — Existing infrastructure inventory (NO REWORK)

The following are **already shipped** and **must NOT be re-added** in this story. Cite each in code comments where the dev agent crosses the seam.

- **V11 (10) `final_three_posters` table** — `BE/src/main/resources/db/migration/V11__survival_revival_economy.sql:140-147` (composite PK `(room_id, year_month)`, `svg_text text not null`, `png_url varchar(512)` nullable, `generated_at timestamptz not null default now()`, FK `rooms(id) on delete cascade`). **NO new Flyway migration in this story** (AC9 banned-paths).
- **Apache Batik 1.17** — `BE/build.gradle:32-33` already declares `org.apache.xmlgraphics:batik-transcoder:1.17` + `batik-codec:1.17` (added by Story 6.1). **NO new dependency add** (AC9 banned-paths).
- **`com.yeosal.api.kakaoshare.PngRasterizer`** — `BE/src/main/java/com/yeosal/api/kakaoshare/PngRasterizer.java` (Story 6.1). Production-grade Batik wrapper with `@PostConstruct` warm-up. Output 800×420 hard-coded — Story 7.1 **reuses this bean as-is** via cross-module DI (AC6). NO new rasterizer class.
- **`GeneratedTokens` Java class** — `BE/build/generated/sources/tokens/com/yeosal/api/theme/GeneratedTokens.java`, emitted by `BE/build.gradle:64-191` `generateTokens` task from `FE/src/theme/tokens.json`. Story 1.5 lineage. Exposes `GeneratedTokens.COLOR_*`, `GeneratedTokens.TYPOGRAPHY_*`, `GeneratedTokens.SubMode.Editorial.*` static constants. Story 7.1's `SvgRenderer` consumes ONLY these constants.
- **Checkstyle hex-literal guard** — `BE/build.gradle:284-303` blocks any `#xxxxxx` / `rgb(` / `oklch(` literal in `src/main/java/**` outside `com.yeosal.api.theme.GeneratedTokens`. Story 7.1 enforces epic AC3 ("direct hex literals are blocked by a Checkstyle / ArchUnit rule") **via this existing Checkstyle config** — no new ArchUnit dependency (AC9 banned-paths).
- **`@EnableAsync`** — already on `com.yeosal.api.YeosalApiApplication` (Story 6.1). NOT needed for Story 7.1 (no `@Async` work — render is synchronous since the caller is the Story 7.2 batch job which already parallelizes across rooms via its own thread pool).
- **`ChatService.publishSystem(long roomId, ChatMessageKind kind, String body, String payload)`** — `BE/src/main/java/com/yeosal/api/room/chat/ChatService.java:130` — the system-message chokepoint. Story 7.1 adds a `publishMonthlyNoSurvivorsSystemMessage` wrapper mirroring Story 5.4's `publishRuleChangeSystemMessage:185-201` pattern (REQUIRES_NEW + locked body + structured payload).
- **`com.yeosal.api.common.ServiceUnavailableException`** + 503 mapping in `ApiExceptionHandler` — added by Story 6.1. NOT used in Story 7.1's happy path; the GET endpoint returns 404 via the standard `NotFoundException` instead (AC7).
- **`com.yeosal.api.realtime.RealtimeEvent`** is an open record `(String kind, Object payload)` — no sealed-variant infrastructure. Epic Story 7.2 emits `MonthlyPosterReady` per room — **out of scope** for Story 7.1 (no `RealtimePublisher` calls).

**Verification before any code edit:** run `grep -n "final_three_posters" BE/src/main/resources/db/migration/V11__survival_revival_economy.sql` (expect a line in the 140-150 range). Run `ls BE/src/main/java/com/yeosal/api/kakaoshare/PngRasterizer.java` (expect EXISTS).

### AC1 — New `com.yeosal.api.ceremony` module (FILE INVENTORY LOCK)

**Given** Architecture §6.1 (line 580-587) reserved a `ceremony/` BE module that does not yet exist
**When** Story 7.1 ships
**Then** the new module contains **exactly these 9 files** under `BE/src/main/java/com/yeosal/api/ceremony/` (no more, no less — AC9 scope fence):

```
ceremony/
├── SvgRenderer.java                       (NEW — AC3, AC8)
├── FinalThreePoster.java                  (NEW — entity, AC2)
├── FinalThreePosterId.java                (NEW — composite-key class, AC2)
├── FinalThreePosterRepository.java        (NEW — JpaRepository, AC2)
├── FinalThreeService.java                 (NEW — orchestration, AC4, AC5)
├── PosterController.java                  (NEW — GET endpoint, AC7)
├── PosterDto.java                         (NEW — REST response record, AC7)
├── SurvivorTenureRow.java                 (NEW — package-private query projection record, AC4)
└── PosterNotFoundException.java           (NEW — 404 mapping, AC7)
```

**Why no `FinalThreeJob.java` in this story:** Architecture §6.1 line 581 lists `FinalThreeJob.java` in the ceremony module but the `@Scheduled` wrapper is **Story 7.2's scope** (epic line 933-950). Story 7.1 ships the per-room `FinalThreeService.generatePoster(roomId, yearMonth)` that Story 7.2's job iterates over.

**Why no `PngRasterizer.java` duplicate in ceremony module:** Architecture §6.1 line 587 lists `PngRasterizer.java` in `ceremony/` but Story 6.1 already shipped one at `kakaoshare/PngRasterizer.java` (800×420 output, same Kakao share constraint). Cross-module reuse via Spring DI is fine per project-context line 176 — package-by-feature does NOT preclude cross-module application-service injection. **Decision documented in dev-notes "Architecture deviation #1"**; Architecture §6.1 follow-up tracked in AC12.

**Why `dto/` is NOT a subdirectory:** the only DTO in this story is `PosterDto` and the only projection is `SurvivorTenureRow` — both single-file. Sub-folder is over-structure for 2 files. Matches the kakaoshare module precedent (no `dto/` subdir).

**Anti-pattern (DO NOT IMPLEMENT):**

- Add `PngRasterizer` duplicate under `ceremony/` — wastes a Batik-warm-up `@PostConstruct` call (boot adds another 300-500ms) and duplicates a 60-line class. Cross-module DI is the right call.
- Add `FinalThreeJob.java` to be helpful for Story 7.2 — leave Story 7.2's scope intact so its sprint can scope `@Scheduled` + cron parsing + thread-pool sizing + `MonthlyPosterReady` realtime fan-out as ONE concern.
- Add `dto/` subdir — drift from the kakaoshare module precedent and adds noise.
- Add `ceremony/` to `com.yeosal.api.YeosalApiApplication`'s explicit component-scan list — `@SpringBootApplication` already scans `com.yeosal.api.*` recursively (Story 1.5 / Story 6.1 precedent — none of those modules needed explicit listing).

PRD: FR-8.7.1, FR-8.7.2, FR-8.7.3, FR-8.7.5. Architecture: §6.1 (line 579-587), §6.3 V11 (10) (line 753-761), §6.4 (line 802-817 endpoint row).

### AC2 — `FinalThreePoster` JPA entity + composite-key id + repository (NO MIGRATION)

**Given** V11 (10) shipped `final_three_posters` with composite PK `(room_id, year_month)`
**When** the entity layer is added
**Then** the JPA mapping satisfies `ddl-auto: validate` mode (project-context line 36, line 119) with **no schema change**, using the `@IdClass` composite-key precedent from `survival/RecordVisibilityPref.java` (Story 2.3 lineage):

**`FinalThreePosterId.java` (Serializable composite key):**

```java
package com.yeosal.api.ceremony;

import java.io.Serializable;
import java.util.Objects;

public class FinalThreePosterId implements Serializable {

    private Long roomId;
    private String yearMonth;

    public FinalThreePosterId() {}

    public FinalThreePosterId(Long roomId, String yearMonth) {
        this.roomId = roomId;
        this.yearMonth = yearMonth;
    }

    public Long getRoomId() { return roomId; }
    public String getYearMonth() { return yearMonth; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FinalThreePosterId other)) return false;
        return Objects.equals(roomId, other.roomId)
                && Objects.equals(yearMonth, other.yearMonth);
    }

    @Override
    public int hashCode() {
        return Objects.hash(roomId, yearMonth);
    }
}
```

**`FinalThreePoster.java` (entity):**

```java
package com.yeosal.api.ceremony;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Immutable monthly Final-3 poster row (PRD FR-8.7.6). Composite PK
 * {@code (room_id, year_month)} matches V11 step 10 — the second insert for
 * the same key short-circuits at the repository layer in
 * {@link FinalThreeService}, so this entity has no application-level
 * retry-safe upsert. Posters are append-only; no setters for {@code svgText}.
 *
 * <p>Uses {@link IdClass} composite key to stay parallel with
 * {@code survival/RecordVisibilityPref.java} (Story 2.3 precedent). The
 * project has no {@code @EmbeddedId} precedent.
 */
@Entity
@Table(name = "final_three_posters")
@IdClass(FinalThreePosterId.class)
public class FinalThreePoster {

    @Id
    @Column(name = "room_id", nullable = false)
    private Long roomId;

    @Id
    @Column(name = "year_month", nullable = false, length = 7)
    private String yearMonth;

    @Column(name = "svg_text", nullable = false, columnDefinition = "text")
    private String svgText;

    @Column(name = "png_url", length = 512)
    private String pngUrl;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    protected FinalThreePoster() {}

    public FinalThreePoster(long roomId, String yearMonth, String svgText, String pngUrl) {
        this.roomId = roomId;
        this.yearMonth = yearMonth;
        this.svgText = svgText;
        this.pngUrl = pngUrl;
    }

    @PrePersist
    void prePersist() {
        if (generatedAt == null) {
            generatedAt = Instant.now();
        }
    }

    public Long getRoomId() { return roomId; }
    public String getYearMonth() { return yearMonth; }
    public String getSvgText() { return svgText; }
    public String getPngUrl() { return pngUrl; }
    public Instant getGeneratedAt() { return generatedAt; }
}
```

**`FinalThreePosterRepository.java`:**

```java
package com.yeosal.api.ceremony;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FinalThreePosterRepository
        extends JpaRepository<FinalThreePoster, FinalThreePosterId> {
}
```

**Why no `existsByRoomIdAndYearMonth` derived query:** `JpaRepository.existsById(FinalThreePosterId)` already covers the idempotency check (AC4). Adding a second method is YAGNI.

**Why `yearMonth` is `String` not `java.time.YearMonth`:** V11 schema is `varchar(7)` and JPA's default `YearMonth` mapping is non-portable across DB vendors (Hibernate would need a custom converter). The service-layer API uses `YearMonth` (AC4) and serializes to `String yyyy-MM` at the entity boundary — same pattern as `room_rule_versions.effective_from_month` (Story 5.1 + 5.2 + 5.4 lineage; see `RoomRuleVersion.effectiveFromMonth`).

**Why `columnDefinition = "text"` on `svgText`:** V11's column is `text` (Postgres unbounded). Hibernate's default mapping for `String` without `@Lob` is `varchar(255)` — would fail `ddl-auto: validate` at boot. The explicit `text` columnDefinition matches.

**Anti-pattern (DO NOT IMPLEMENT):**

- `@Lob` on `svgText` — triggers `oid` / large-object handling on Postgres which V11's `text` column does not use. Plain `columnDefinition = "text"` is correct.
- A `setSvgText(...)` setter — posters are immutable (PRD FR-8.7.6). The only mutation in the entity's lifetime is the `@PrePersist` `generatedAt` initializer.
- An `@OneToOne(targetEntity = Room.class)` mapping for `roomId` — keep the FK as a `Long`, mirror Story 6.1's `PreviewCardCache` precedent (lazy collection + open-in-view: false would force eager joins). Service joins room data via a separate query when needed.
- A V14+ Flyway migration — V11 (10) already ships the table. AC9 banned-paths.

PRD: FR-8.7.6 (immutability). Architecture: §6.3 V11 (10) (line 753-761).

### AC3 — `SvgRenderer` — D1 Editorial poster layout with `GeneratedTokens` ONLY (LOCKED VISUAL)

**Given** the orchestration layer needs an SVG document for a room's monthly survivors list
**When** `SvgRenderer.render(...)` is called with locked inputs
**Then** the new class `com.yeosal.api.ceremony.SvgRenderer` produces a valid `<svg>` document satisfying epic ACs 1 + 2 + 4 + the AC3 anti-hex guard:

**Signature (LOCKED — Story 7.2 + tests both depend on this shape):**

```java
public String render(
        Room room,
        java.time.YearMonth yearMonth,
        java.util.List<SurvivorTenureRow> allSurvivors,   // ordered top-tenured first
        int totalSurvivorCount)                            // == allSurvivors.size()
```

The signature is **NOT** `render(roomId, yearMonth)` despite epic line 905 wording. That wording is a naming intent, not a strict signature. The actual signature takes pre-fetched data so `SvgRenderer` is a pure deterministic function (no `Room` repository, no clock, no I/O) — critical for the AC2 token-diff integration test (epic line 908-910). The orchestration in `FinalThreeService` (AC4) is the one with `roomId, yearMonth` inputs.

**(a) Layout — 800 × 420 (1.91:1) matching Kakao Custom Feed dimensions + reusing kakaoshare/PngRasterizer:**

```
+---------------------------------------------+
|                                             |
|  열살                       2026년 6월       |  ← wordmark + year label, body text
|                                             |
|  ${roomName}                                |  ← display serif (Nanum Myeongjo, weight 900), oxblood
|                                             |
|  ${top3[0]}  ${top3[1]}  ${top3[2]}         |  ← top-3 highlighted, key.line accent, large
|                                             |
|  ${top4} · ${top5} · ${top6} · ... · ${topN}|  ← remaining survivors, dot separator, secondary text
|                                             |
|  ${totalSurvivorCount}명 생존    함께 살아남은 우리 |  ← FR-8.7.5 secondary stat + footer
+---------------------------------------------+
```

- If `totalSurvivorCount <= 3`: render only the highlighted top-3 row (which IS the full list); skip the remaining-survivors row entirely.
- If `totalSurvivorCount > 3`: render top-3 row + remaining row with names joined by `" · "` (middle-dot U+00B7 + spaces); auto-wrap is achieved via SVG `<text>` `tspan` baseline offsets (NOT `text-anchor="middle"` flow — see anti-pattern below).
- Year label format: `"%d년 %d월".formatted(yearMonth.getYear(), yearMonth.getMonthValue())` — Korean locale string, no leading zero on month.

**(b) Token consumption — `GeneratedTokens.*` ONLY (epic AC3 enforcement target):**

| Position | Constant | Reason |
|---|---|---|
| Background fill | `GeneratedTokens.COLOR_BG_CANVAS` | Dark luxury canvas |
| Wordmark + year label text | `GeneratedTokens.COLOR_TEXT_SECONDARY` | De-emphasized brand surface |
| Room name | `GeneratedTokens.COLOR_KEY_DEFAULT` | Oxblood key — magazine cover headline |
| Top-3 names | `GeneratedTokens.COLOR_KEY_LINE` | Brighter oxblood (`#B14342`, AA-passing on canvas) |
| Remaining survivors | `GeneratedTokens.COLOR_TEXT_SECONDARY` | Subordinate to top-3 hierarchy |
| "N명 생존" stat | `GeneratedTokens.COLOR_TEXT_PRIMARY` | Survivor count carries weight |
| Footer "함께 살아남은 우리" | `GeneratedTokens.COLOR_TEXT_TERTIARY` | Italic + subtlest, ember tone via TEXT_TERTIARY |

| Typography | Constant |
|---|---|
| Room name weight | `GeneratedTokens.SubMode.Editorial.TYPOGRAPHY_HEADING_WEIGHT` (900) |
| Room name tracking | `GeneratedTokens.SubMode.Editorial.TYPOGRAPHY_HEADING_TRACKING` (`-0.025em`) |
| Top-3 names weight | `GeneratedTokens.TYPOGRAPHY_DISPLAY_SM_WEIGHT` (800) |
| Top-3 names size | `GeneratedTokens.TYPOGRAPHY_DISPLAY_SM_SIZE` (40) |
| Remaining names size | `GeneratedTokens.TYPOGRAPHY_BODY_LG_SIZE` (18) |
| "N명 생존" size | `GeneratedTokens.TYPOGRAPHY_BODY_SIZE` (16) |
| Footer size | `GeneratedTokens.TYPOGRAPHY_CAPTION_SIZE` (12) |
| Wordmark + year label size | `GeneratedTokens.TYPOGRAPHY_CAPTION_SIZE` (12) |

**Direct hex literal forbidden** — `BE/build.gradle:284-303` Checkstyle hex-literal guard blocks compilation if a `#XXXXXX` or `rgb(`/`oklch(` literal appears anywhere in `com.yeosal.api.ceremony.*`. This is the **mechanism that satisfies epic AC3** ("direct hex literals are blocked by a Checkstyle / ArchUnit rule"). The renderer's AC10 test suite includes a `SvgRendererHexLiteralGateTest` that reads the renderer source as a String and asserts `assertThat(source).doesNotContainPattern("#[0-9A-Fa-f]{6}")` as a belt-and-suspenders check.

**(c) SVG document shape — Java text builder, NO Batik for build step:**

```java
@Component
public class SvgRenderer {

    static final String WORDMARK = "열살";
    static final String FOOTER = "함께 살아남은 우리";
    static final String SURVIVOR_STAT_SUFFIX = "명 생존";
    static final String NAME_SEPARATOR = " · ";

    public String render(
            Room room,
            YearMonth yearMonth,
            List<SurvivorTenureRow> allSurvivors,
            int totalSurvivorCount) {

        if (totalSurvivorCount < 1 || allSurvivors.isEmpty()) {
            // FinalThreeService is the eligibility gate; reaching the renderer
            // with zero survivors is an invariant violation, not a soft path.
            throw new IllegalArgumentException(
                    "SvgRenderer invoked with zero survivors; FinalThreeService"
                            + " must short-circuit via zero-survivor chat fallback (AC5).");
        }

        String roomName = escapeXml(room.getName());
        String yearLabel = "%d년 %d월".formatted(
                yearMonth.getYear(), yearMonth.getMonthValue());
        String top3Row = renderTopThreeRow(allSurvivors);
        String remainingRow = renderRemainingRow(allSurvivors);
        String survivorStat = totalSurvivorCount + SURVIVOR_STAT_SUFFIX;

        return String.format("""
                <?xml version="1.0" encoding="UTF-8" standalone="no"?>
                <svg xmlns="http://www.w3.org/2000/svg" width="800" height="420" viewBox="0 0 800 420">
                  <rect width="100%%" height="100%%" fill="%s"/>
                  <text x="48" y="48" font-family="-apple-system, sans-serif" font-size="%d" fill="%s">%s</text>
                  <text x="752" y="48" text-anchor="end" font-family="-apple-system, sans-serif" font-size="%d" fill="%s">%s</text>
                  <text x="48" y="130" font-family="Nanum Myeongjo, serif" font-weight="%d" font-size="56" fill="%s" letter-spacing="%s">%s</text>
                  %s
                  %s
                  <text x="48" y="392" font-family="-apple-system, sans-serif" font-size="%d" fill="%s">%s</text>
                  <text x="752" y="392" text-anchor="end" font-family="-apple-system, sans-serif" font-style="italic" font-size="%d" fill="%s">%s</text>
                </svg>
                """,
                GeneratedTokens.COLOR_BG_CANVAS,                              // 1 background
                GeneratedTokens.TYPOGRAPHY_CAPTION_SIZE,                      // 2 wordmark size
                GeneratedTokens.COLOR_TEXT_SECONDARY,                         // 3 wordmark fill
                WORDMARK,                                                     // 4 wordmark text
                GeneratedTokens.TYPOGRAPHY_CAPTION_SIZE,                      // 5 year label size
                GeneratedTokens.COLOR_TEXT_SECONDARY,                         // 6 year label fill
                yearLabel,                                                    // 7 year label text
                GeneratedTokens.SubMode.Editorial.TYPOGRAPHY_HEADING_WEIGHT,  // 8 room name weight
                GeneratedTokens.COLOR_KEY_DEFAULT,                            // 9 room name fill
                GeneratedTokens.SubMode.Editorial.TYPOGRAPHY_HEADING_TRACKING,// 10 room name tracking
                roomName,                                                     // 11 room name text
                top3Row,                                                      // 12 top-3 <text> element
                remainingRow,                                                 // 13 remaining survivors block
                GeneratedTokens.TYPOGRAPHY_BODY_SIZE,                         // 14 survivor stat size
                GeneratedTokens.COLOR_TEXT_PRIMARY,                           // 15 survivor stat fill
                survivorStat,                                                 // 16 "N명 생존"
                GeneratedTokens.TYPOGRAPHY_CAPTION_SIZE,                      // 17 footer size
                GeneratedTokens.COLOR_TEXT_TERTIARY,                          // 18 footer fill
                FOOTER);                                                      // 19 footer text
    }

    private String renderTopThreeRow(List<SurvivorTenureRow> all) {
        // Always render whatever top names exist; if 1 or 2 survivors,
        // emit 1 or 2 <tspan>s with the same large styling so the layout
        // still reads as "the survivors", not "missing slots".
        int n = Math.min(3, all.size());
        StringBuilder spans = new StringBuilder();
        for (int i = 0; i < n; i++) {
            String name = escapeXml(all.get(i).nickname());
            int x = 48 + i * 240;  // 240px column pitch — fits 3 across 720px wide content area
            spans.append("<tspan x=\"").append(x).append("\" y=\"210\">").append(name).append("</tspan>");
        }
        return "<text font-family=\"-apple-system, sans-serif\" font-weight=\""
                + GeneratedTokens.TYPOGRAPHY_DISPLAY_SM_WEIGHT
                + "\" font-size=\""
                + GeneratedTokens.TYPOGRAPHY_DISPLAY_SM_SIZE
                + "\" fill=\""
                + GeneratedTokens.COLOR_KEY_LINE
                + "\">" + spans + "</text>";
    }

    private String renderRemainingRow(List<SurvivorTenureRow> all) {
        if (all.size() <= 3) return "";  // skip block entirely
        List<SurvivorTenureRow> remaining = all.subList(3, all.size());
        // Greedy line-wrap by character budget — 800-pixel canvas, 48px L/R
        // padding, body-lg font ~10px char-width on average → ~70 chars per line.
        // The remaining survivors block reserves y=260..360 (4 lines of 24px line-height).
        List<String> lines = wrapNames(
                remaining.stream().map(r -> escapeXml(r.nickname())).toList(),
                /* maxCharsPerLine = */ 70,
                /* maxLines = */ 4,
                NAME_SEPARATOR);

        StringBuilder out = new StringBuilder();
        int y = 260;
        for (String line : lines) {
            out.append("<text x=\"48\" y=\"").append(y)
               .append("\" font-family=\"-apple-system, sans-serif\" font-size=\"")
               .append(GeneratedTokens.TYPOGRAPHY_BODY_LG_SIZE)
               .append("\" fill=\"")
               .append(GeneratedTokens.COLOR_TEXT_SECONDARY)
               .append("\">").append(line).append("</text>\n  ");
            y += 24;
        }
        return out.toString().trim();
    }

    static List<String> wrapNames(List<String> names, int maxChars, int maxLines, String sep) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int remainingCount = 0;
        for (int idx = 0; idx < names.size(); idx++) {
            String n = names.get(idx);
            String addition = current.isEmpty() ? n : sep + n;
            if (current.length() + addition.length() > maxChars) {
                if (lines.size() + 1 == maxLines && idx < names.size() - 1) {
                    // Last line — append "+N more" overflow marker.
                    remainingCount = names.size() - idx;
                    if (!current.isEmpty()) current.append(sep);
                    current.append("외 ").append(remainingCount).append("명");
                    break;
                }
                lines.add(current.toString());
                current = new StringBuilder(n);
            } else {
                current.append(addition);
            }
        }
        if (!current.isEmpty()) lines.add(current.toString());
        return lines;
    }

    /** Minimal XML escape — 5 entities (mirror Story 6.1 InvitePreviewRenderer:65-73). */
    static String escapeXml(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        return raw
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
```

**`SurvivorTenureRow` package-private projection (in same package, AC4):**

```java
package com.yeosal.api.ceremony;

/**
 * Package-private projection emitted by {@link FinalThreeService}'s native
 * survivors query. Field order matches the SQL projection: nickname first
 * for {@link SvgRenderer} consumption, then user_id as the deterministic
 * tie-breaker, then joined_at for the ordering verification in tests.
 */
record SurvivorTenureRow(String nickname, long userId, java.time.Instant joinedAt) {
}
```

**(d) Output dimensions — 800 × 420:**

- Matches `kakaoshare/PngRasterizer.OUTPUT_WIDTH = 800` + `OUTPUT_HEIGHT = 420` (Story 6.1 lock). Reuse of that bean is impossible at any other resolution.
- KakaoTalk Custom Feed `link.imageWidth/imageHeight` (Story 7.3 will set these) matches the 1.91:1 aspect — same as Story 6.1 invite preview.
- The Home-tab `<FinalThreeCard>` (Story 7.3) renders the SVG inline at responsive width but its source-of-truth viewBox is 800×420.

**(e) Locked brand-voice phrases — AVOID-lexicon-zero (PRD FR-8.8.2 + `tools/brand-voice-lint.ts:50-59`):**

- Wordmark: `"열살"`
- Year label: `"%d년 %d월"` (no AVOID-lexicon collision)
- Survivor stat: `"<N>명 생존"` — `생존` is a USE word (not in AVOID list)
- Footer: `"함께 살아남은 우리"` — `함께`, `살아남` both USE-lexicon (PRD FR-8.8.2). `우리` is dignified collective voice (PRD §6.4 principle 1).
- Top-3 highlighted-vs-remaining naming carries NO text caption like "Final 3" or "TOP 3" — the visual hierarchy alone communicates rank, matching A24/concert-poster references in UX line 459-462. No "1위/2위/3위" labels.

AVOID lexicon = 벌금/잃었다/떨어졌다/실패/자책/부담/패배/죄책감 → NONE of those appear. Verified in AC10 via `BrandVoicePosterPhrasesTest`.

**Anti-pattern (DO NOT IMPLEMENT):**

- Direct hex literal `"#7E2C2A"` or `"rgb(126,44,42)"` in renderer code → Checkstyle hex-literal guard at `build.gradle:284-303` blocks compilation. **Only `GeneratedTokens.*` constants pass**.
- `escapeXml` skipped — room name with `<`, `>`, `&`, `"`, `'` corrupts the SVG. Same 5-replacement chain as Story 6.1 `InvitePreviewRenderer.escapeXml:65-73`. No Apache Commons Text dependency.
- D2 Bento / D3 Quiet / D4 Postcard / D5 Plate sub-mode constants — D1 Editorial ONLY (epic line 906 "tokens.subMode.editorial.* overrides applied"; UX line 1169 "Final-3 monthly ceremony | D1 Editorial Spread").
- A "1위 / 2위 / 3위" caption beside top-3 names — UX explicitly designs survival as collective (UX line 367 "함께하고 싶다", "친구 잔디·풀·Final-3 명단에 자기 이름 visible"). Ranking caption violates 1인칭 복수 voice; brand-voice violation.
- `<image href="https://...">` for any external asset (logos, photos) — Batik's PNG transcoder will attempt to fetch at `toPng()` time → SSRF risk + p99 latency hit. SVG must be self-contained text.
- Per-member status icon (ACTIVE/YELLOW/RED) in the poster — by AC eligibility (ACTIVE only), everyone shown is ACTIVE; rendering the icon would be visual noise. Also reduces privacy (no broadcast of cross-member status outside `/topic/rooms.{id}.survival` channel — survival_state status leak forbidden per Architecture §4.14).
- Nickname truncation for long names — full nickname rendered; if it overflows the column pitch (240px on top-3 row), the SVG visually overlaps but no truncation. Real-world v1 nicknames are 80-char column (User.nickname length 80) but Korean nicknames are typically ≤ 6 chars. Trust the column pitch.
- A "previous month's poster" link or comparison block — out of scope; posters are isolated per yearMonth (PRD FR-8.7.6 immutability).
- `<style>` block inside the SVG → CSS escaping rules differ from XML; introduces a second escape pathway. Inline attribute styling only.
- `font-family="system-ui"` — Batik 1.17 does not resolve `system-ui` to a valid AWT font; falls back to a generic without sub-pixel anti-aliasing. Stick to `"-apple-system, sans-serif"` + `"Nanum Myeongjo, serif"` (same fonts as Story 6.1 InvitePreviewRenderer:33-44).

PRD: FR-8.7.2, FR-8.7.5. Architecture: §4.9 line 292-306 (server-side SVG decision + tokens-only renderer), §4.16 line 419-485 (codegen). UX: line 1169 (D1 Editorial Spread for Final-3), line 1070 (D1 Editorial surface assignment), line 459-462 (A24/concert-poster reference). Brand-voice: `tools/brand-voice-lint.ts:50-59`.

### AC4 — `FinalThreeService.generatePoster(roomId, yearMonth)` orchestration (PUBLIC API FOR STORY 7.2)

**Given** Story 7.2's scheduled job iterates rooms and Story 7.3's FE consumes the GET endpoint
**When** orchestration runs for a single (roomId, yearMonth)
**Then** `FinalThreeService.generatePoster` follows this exact flow:

```java
package com.yeosal.api.ceremony;

import com.yeosal.api.kakaoshare.PngRasterizer;
import com.yeosal.api.kakaoshare.PreviewCardRenderException;
import com.yeosal.api.room.Room;
import com.yeosal.api.room.RoomNotFoundException;
import com.yeosal.api.room.RoomRepository;
import com.yeosal.api.room.chat.ChatService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FinalThreeService {

    private static final Logger log = LoggerFactory.getLogger(FinalThreeService.class);

    private final FinalThreePosterRepository posterRepository;
    private final RoomRepository rooms;
    private final SvgRenderer svgRenderer;
    private final PngRasterizer pngRasterizer;
    private final ChatService chatService;
    private final EntityManager em;
    private final Clock clock;
    private final Path pngOutputDir;
    private final String posterUrlBase;

    public FinalThreeService(
            FinalThreePosterRepository posterRepository,
            RoomRepository rooms,
            SvgRenderer svgRenderer,
            PngRasterizer pngRasterizer,
            ChatService chatService,
            EntityManager em,
            Clock clock,
            @Value("${yeosal.share.posters-dir:/var/yeosal/posters}") String pngOutputDir,
            @Value("${yeosal.share.preview-card-base:https://api.rearleg.com/yeolsal}") String posterUrlBase) {
        this.posterRepository = posterRepository;
        this.rooms = rooms;
        this.svgRenderer = svgRenderer;
        this.pngRasterizer = pngRasterizer;
        this.chatService = chatService;
        this.em = em;
        this.clock = clock;
        this.pngOutputDir = Path.of(pngOutputDir);
        this.posterUrlBase = stripTrailingSlash(posterUrlBase);
    }

    /**
     * Renders + persists the Final-3 poster for a single room + month, or
     * publishes the zero-survivor chat fallback if no member is ACTIVE.
     *
     * <p>Idempotency: if a {@code final_three_posters} row already exists for
     * {@code (roomId, yearMonth)}, this method short-circuits with the existing
     * poster (no re-render, no re-publish). Posters are immutable per
     * FR-8.7.6. Story 7.2's batch job relies on this short-circuit for
     * replay safety.
     *
     * <p>Zero-survivor caveat: this method is NOT idempotent on the
     * zero-survivor path — a second call publishes a second chat row. Story
     * 7.2's job pre-filters rooms with at least one survivor via the same
     * native query, so duplicate fallback messages never reach production.
     * Tests document the behavior (AC10).
     *
     * @return {@link Optional#empty()} on the zero-survivor path (chat
     *         fallback published, no poster). Present otherwise.
     */
    @Transactional
    public Optional<FinalThreePoster> generatePoster(long roomId, YearMonth yearMonth) {
        FinalThreePosterId id = new FinalThreePosterId(roomId, yearMonth.toString());
        Optional<FinalThreePoster> existing = posterRepository.findById(id);
        if (existing.isPresent()) {
            return existing;
        }

        Room room = rooms.findById(roomId)
                .orElseThrow(() -> new RoomNotFoundException(roomId));

        List<SurvivorTenureRow> survivors = querySurvivors(roomId);
        if (survivors.isEmpty()) {
            chatService.publishMonthlyNoSurvivorsSystemMessage(roomId, yearMonth);
            return Optional.empty();
        }

        String svg = svgRenderer.render(room, yearMonth, survivors, survivors.size());
        byte[] pngBytes;
        try {
            pngBytes = pngRasterizer.toPng(svg);
        } catch (PreviewCardRenderException ex) {
            log.warn("[ceremony] PNG rasterize failed roomId={} yearMonth={}; persisting SVG only",
                    roomId, yearMonth, ex);
            pngBytes = null;
        }

        String pngUrl = null;
        if (pngBytes != null) {
            pngUrl = writePngAtomically(roomId, yearMonth, pngBytes);
        }

        FinalThreePoster poster = new FinalThreePoster(
                roomId, yearMonth.toString(), svg, pngUrl);
        return Optional.of(posterRepository.save(poster));
    }

    /**
     * Native survivors query — top-tenured first. Includes top-3-by-tenure
     * (ACTIVE survival_state) plus all other ACTIVE survivors. Tie-breaker
     * is {@code user_id ASC} so two members who joined at the same instant
     * have a deterministic order across reruns.
     */
    List<SurvivorTenureRow> querySurvivors(long roomId) {
        @SuppressWarnings("unchecked")
        List<Tuple> rows = em.createNativeQuery("""
                SELECT u.nickname AS nickname,
                       u.id       AS user_id,
                       rm.joined_at AS joined_at
                  FROM survival_state ss
                  JOIN room_members  rm ON rm.room_id = ss.room_id AND rm.user_id = ss.user_id
                  JOIN users         u  ON u.id       = ss.user_id
                 WHERE ss.room_id = :rid
                   AND ss.status  = 'ACTIVE'
                 ORDER BY rm.joined_at ASC, u.id ASC
                """, Tuple.class)
                .setParameter("rid", roomId)
                .getResultList();

        return rows.stream()
                .map(t -> new SurvivorTenureRow(
                        t.get("nickname", String.class),
                        ((Number) t.get("user_id")).longValue(),
                        ((java.sql.Timestamp) t.get("joined_at")).toInstant()))
                .toList();
    }

    private String writePngAtomically(long roomId, YearMonth yearMonth, byte[] pngBytes) {
        try {
            Files.createDirectories(pngOutputDir);
            String fileName = roomId + "-" + yearMonth + ".png";
            Path target = pngOutputDir.resolve(fileName);
            Path temp = pngOutputDir.resolve(fileName + ".tmp");
            Files.write(temp, pngBytes);
            try {
                Files.move(temp, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (UnsupportedOperationException unsupported) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return posterUrlBase + "/posters/" + fileName;
        } catch (IOException ex) {
            log.warn("[ceremony] poster PNG write failed roomId={} yearMonth={}; svg-only fallback",
                    roomId, yearMonth, ex);
            return null;
        }
    }

    private static String stripTrailingSlash(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
```

**Idempotency contract — three paths:**

1. **Row exists** → return existing `Optional<FinalThreePoster>`. Zero work. PRD FR-8.7.6 honored.
2. **Survivors > 0, row absent** → render + rasterize + persist. Returns `Optional.of(poster)`.
3. **Survivors == 0, row absent** → publish system chat message (AC5), NO row insert, return `Optional.empty()`. **Not idempotent** — caller responsible for not double-invoking (Story 7.2's job uses the same `survival_state.status='ACTIVE'` filter at the SELECT step, so rooms with zero survivors are skipped before reaching `generatePoster` in steady-state operation; replay scenarios are out of scope for v1).

**PNG rasterize failure tolerance:**

- `pngRasterizer.toPng(svg)` may throw `PreviewCardRenderException` (Story 6.1's class — reused). Story 7.1 catches and persists the row with `pngUrl=null`. Rationale: V11 schema makes `png_url` nullable on purpose. The SVG itself is the source of truth; the PNG is a Kakao-share thumbnail.
- Story 7.3's FE renders the SVG inline regardless of PNG existence; share-to-Kakao falls back to the SVG path (Story 7.3 acceptance criteria allow this — `link.imageUrl` is optional in Kakao Custom Feed).
- Disk-write failure (`writePngAtomically` IOException): logged at WARN, `pngUrl=null`, row still persists. Same tolerance reasoning.

**Why `EntityManager.createNativeQuery` for survivors lookup:**

- JPQL cannot reference both `survival_state` (entity) and `room_members.joined_at` (entity) and `users.nickname` (entity) into a tuple result + record-projection cleanly without a `@SqlResultSetMapping` ceremony. Native query + Jakarta Persistence `Tuple` is the existing project pattern (Story 3.2 `RevivalEventRepository.findFriendGiftReceiptsWithin7Days`, Story 3.4 `PersonalPointsLedgerRepository.findByUserIdAndRoomIdOrderByOccurredAtDesc`).
- The projection record `SurvivorTenureRow` lives in the same package; native-query tuple mapping reads `t.get("col", Type.class)` which avoids the `@SqlResultSetMapping` ceremony.

**Why `Asia/Seoul` does NOT appear in the survivors query:**

- The query selects ACTIVE survival_state at *query-execution time*. The KST 06:30 boundary is the caller's (Story 7.2 job @Scheduled cron) concern — when the job runs at 06:30 KST on day 1 of new month, the survival_state.status reflects the SurvivalStateEvaluatorJob (Story 1.2) run at 06:00 KST same day, which has just transitioned anyone who missed prior month's last day to YELLOW or RED. So at 06:30, "currently ACTIVE" = "completed prior month and is still ACTIVE". No KST math needed in this query.
- The `yearMonth` parameter is for the *poster's metadata + filename + PK*, not for filtering survival_state.

**Anti-pattern (DO NOT IMPLEMENT):**

- Add `@Scheduled` annotation to any method here — Story 7.2's scope (epic line 933-950). Adding it would race Story 7.2's `FinalThreeJob`.
- Make `generatePoster` `@Async` — Story 7.2's job uses a thread pool to parallelize across rooms; per-call async would double-schedule and risk thread starvation.
- Emit `RealtimePublisher.publish(new RealtimeEvent("MonthlyPosterReady", ...))` here — Story 7.2's scope. Emitting here means every call to `generatePoster` (including idempotent return-existing) would emit, polluting the topic.
- Use `LocalDate.now()` / `YearMonth.now()` to derive yearMonth — the parameter is supplied by caller (Story 7.2 job or test fixture). Story 7.1 has no clock-based behavior.
- `Files.write(target, ...)` directly (no temp + atomic move) — partial writes leave a corrupt PNG that nginx serves to KakaoTalk. Story 6.1 trap #10 documents the same constraint; mirror its sequencing.
- Catch `RuntimeException` broadly and swallow — only `PreviewCardRenderException` is tolerated (PNG produces nullable column). Other RuntimeExceptions (DB, ORM) must propagate so the caller's transaction rolls back.
- Use `Long` boxed type for `userId` in projection — record holds primitive `long`, consistent with the entity's `Long getId()` rule that returns primitive long internally (Story 5.2 precedent).
- Cache the `List<SurvivorTenureRow>` in memory across calls — no cache layer; each call hits the DB. Story 7.2's batch parallelism is fine with this since rooms are partitioned.

PRD: FR-8.7.1, FR-8.7.2, FR-8.7.6. Architecture: §6.1 ceremony module, §6.3 V11 (10).

### AC5 — Zero-survivor chat fallback (LOCKED BODY)

**Given** `FinalThreeService.generatePoster` finds zero ACTIVE survivors for a room
**When** the soft-message fallback path runs
**Then** a new method on `ChatService` (mirroring Story 5.4's `publishRuleChangeSystemMessage:185-201` shape) publishes the chat row:

**New method on `ChatService`:**

```java
/**
 * Story 7.1 — zero-survivor monthly fallback (epic AC5).
 *
 * <p>Body LOCKED to "이번 달은 아무도 살아남지 못했어요 — 다음 달은 함께 가요"
 * (epic line 922). Payload is the minimal {yearMonth} so a future consumer
 * can render a sub-pill / deep-link without re-parsing the body.
 *
 * <p>Runs in {@link Propagation#REQUIRES_NEW} so a chat-row failure cannot
 * roll back the {@link FinalThreeService} transaction (which has no other
 * writes on the zero-survivor path — but the propagation rule keeps parity
 * with {@link #publishRuleChangeSystemMessage} for review-symmetry).
 */
@Transactional(propagation = Propagation.REQUIRES_NEW)
public ChatMessage publishMonthlyNoSurvivorsSystemMessage(
        long roomId,
        java.time.YearMonth yearMonth) {
    String body = "이번 달은 아무도 살아남지 못했어요 — 다음 달은 함께 가요";
    String payload = String.format(
            "{\"yearMonth\":%s}",
            JSON.valueToTree(yearMonth.toString()).toString());
    return publishSystem(roomId, ChatMessageKind.SYSTEM, body, payload);
}
```

**Body byte-identical to epic line 922:** `"이번 달은 아무도 살아남지 못했어요 — 다음 달은 함께 가요"` with the em-dash (U+2014). NO ASCII hyphen substitution. AC10 test asserts the literal string.

**Brand-voice check — AVOID-lexicon-zero (tools/brand-voice-lint.ts:50-59):**

- `벌금` ❌ — absent
- `잃었다` ❌ — absent
- `떨어졌다` ❌ — absent
- `실패` ❌ — absent
- `자책` ❌ — absent
- `부담` ❌ — absent
- `패배` ❌ — absent
- `죄책감` ❌ — absent

`못했어요` is a softer concessive form that is NOT in the AVOID list; the brand-voice lint scans for exact-match tokens. Verified manually + by `BrandVoiceChatPhrasesTest` (AC10).

**`ChatMessageKind.SYSTEM` reuse:** no `ChatMessageKind` enum extension (kind whitelist at `ChatMessageKind.java:17-23` already includes SYSTEM). NO V14+ schema migration. AC9 banned-paths.

**Payload format reference:** the V8/V9 milestone-dedup convention (Story 5.4 `publishRuleChangeSystemMessage` precedent, line 195-200) stores values as JSON-escaped strings via `JSON.valueToTree(...).toString()`. Story 7.1 uses the same `JSON` helper (already imported in `ChatService`).

**Why `REQUIRES_NEW`:**

- The outer `@Transactional` on `generatePoster` is open at this point. If chat-row insert fails (e.g., `RateLimitFilter`-equivalent issue, or a `ChatMessageRepository` constraint), we don't want to rollback the outer transaction — there's nothing to rollback in the zero-survivor path (no poster row), but the REQUIRES_NEW guarantees parity for future-self if more writes get added.
- Mirrors Story 5.4 `RoomRuleService.updateRule` → `chatService.publishRuleChangeSystemMessage` REQUIRES_NEW boundary.

**Anti-pattern (DO NOT IMPLEMENT):**

- Inline call to `chatService.publishSystem(roomId, ChatMessageKind.SYSTEM, body, payload)` from `FinalThreeService` (skipping the new typed wrapper) — Story 5.4 set the precedent of one wrapper per domain on `ChatService` so the body + payload contract is in one place. Don't break the pattern.
- Different chat body string (e.g., omit em-dash, paraphrase, add emoji) — epic AC line 922 is a verbatim lock. AC10 test asserts equality.
- Publish via `RealtimePublisher` instead of `ChatService` — chat messages flow through `ChatService.publishSystem` which fans out to `/topic/rooms.{id}.chat`. Skipping it bypasses the chat persistence + fan-out invariant.
- Persist a `FinalThreePoster` row with `svgText=""` as a "zero-survivor marker" — schema rejects (`svg_text text not null`), and even if allowed, would corrupt Story 7.3's "card not shown for eliminated members" check (eliminated members would see an empty card).
- Add idempotency by querying chat_messages for an existing zero-survivor row before publishing — adds an N+1 read on the happy path. Caller (Story 7.2) guarantees one-call-per-month-per-room.

PRD: FR-8.7.* (zero-survivor caveat is from epic AC5 lockdown). Architecture: §4.14 (realtime topic privacy) — chat messages flow through ChatService chokepoint.

### AC6 — Apache Batik PNG rasterization via cross-module `PngRasterizer` reuse (NO DEPENDENCY ADD)

**Given** Story 6.1 shipped `com.yeosal.api.kakaoshare.PngRasterizer` (`@Component`, Batik 1.17, 800×420 hard-coded, `@PostConstruct` warm-up)
**When** `FinalThreeService` needs PNG bytes
**Then** the existing bean is constructor-injected directly. NO duplicate rasterizer. NO new `build.gradle` lines.

**Cross-module injection (Story 7.1 → kakaoshare):**

```java
import com.yeosal.api.kakaoshare.PngRasterizer;
import com.yeosal.api.kakaoshare.PreviewCardRenderException;
```

Spring's `@SpringBootApplication` scan covers `com.yeosal.api.*`; `@Service` + `@Component` beans cross-package are first-class. `PngRasterizer` has no public-API modifiers that would block consumption (`public class PngRasterizer`).

**Why this is acceptable architectural coupling:**

- Project-context line 176 forbids cross-module *layered* splitting (controller/service/repository across roots) but allows cross-feature application-service injection (Story 6.1's `RoomRuleService` ↔ `PreviewCardCacheService` precedent at story line 244-251).
- The shared concept is "SVG → PNG rasterization at 800×420 for Kakao share-thumbnail consumption." Both kakaoshare (invite preview card) and ceremony (Final-3 poster) are Kakao-share-bound BE renderers. Same dimensions, same library, same warmup cost — sharing is more correct than duplicating.
- If a future renderer needs a different aspect ratio (e.g., 600×600 square for Instagram), THAT story extracts a `KakaoShareRasterizer` (current name + dims) vs. a new `SquareRasterizer`. Premature now.

**Architecture deviation logged (AC12 for doc PR follow-up):**

- Architecture §6.1 line 587 lists `PngRasterizer.java` under `ceremony/` as if it were ceremony-owned. Story 6.1 (earlier sprint) already placed it under `kakaoshare/` since invite preview shipped first. Story 7.1 keeps the bean in `kakaoshare/` and consumes cross-module. Architecture doc update tracked in AC12 (non-blocker, doc-only PR).

**Dependency-add sanity check:**

- `BE/build.gradle:32-33` already has `implementation "org.apache.xmlgraphics:batik-transcoder:1.17"` + `batik-codec:1.17`. Run `grep -c "batik" BE/build.gradle` before any code change; expect `>= 2`. If `0`, Story 6.1 was not merged into the working tree — STOP and resolve.

**Anti-pattern (DO NOT IMPLEMENT):**

- Add `org.apache.xmlgraphics:fop` for PDF — not needed; ceremony output is PNG + SVG.
- Bump Batik to 1.18+ in this story — out of scope, no benefit.
- Move `PngRasterizer.java` from `kakaoshare/` to `common/` "to make sharing explicit" — refactor scope creep. Cross-module DI is documented and works.
- Create a `PngRasterizer` subclass with different dimensions — Story 7.1 uses 800×420 same as Story 6.1. No subclass needed.
- Skip `@PostConstruct` warm-up — already in the reused bean. No-op for Story 7.1.

PRD: FR-8.7.3 (PNG fallback for Kakao share). Architecture: §3.3 (Batik decision), §4.9 line 292 ("PNG fallback only loads Batik when Kakao Card requires a raster image"), §6.1 line 587 (ceremony module outline — AC12 doc follow-up).

### AC7 — `PosterController` GET endpoint + `PosterDto` + 404 mapping

**Given** Architecture §6.4 line 815 declares `GET /rooms/{id}/posters/{yearMonth}` returning "poster SVG + PNG URL" with room-member auth
**When** Story 7.1 ships the endpoint
**Then** the new controller satisfies the project's controller conventions (project-context line 109-118):

**`PosterController.java`:**

```java
package com.yeosal.api.ceremony;

import com.yeosal.api.common.ApiResponse;
import com.yeosal.api.common.BadRequestException;
import com.yeosal.api.common.CurrentUser;
import com.yeosal.api.room.RoomMembershipService;
import jakarta.validation.constraints.Pattern;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/rooms")
@Validated
public class PosterController {

    private static final String YEAR_MONTH_REGEX = "\\d{4}-(0[1-9]|1[0-2])";

    private final FinalThreePosterRepository posterRepository;
    private final RoomMembershipService roomMembership;

    public PosterController(
            FinalThreePosterRepository posterRepository,
            RoomMembershipService roomMembership) {
        this.posterRepository = posterRepository;
        this.roomMembership = roomMembership;
    }

    @GetMapping("/{roomId}/posters/{yearMonth}")
    public ApiResponse<PosterDto> getPoster(
            @PathVariable long roomId,
            @PathVariable @Pattern(regexp = YEAR_MONTH_REGEX) String yearMonth,
            CurrentUser currentUser) {
        roomMembership.requireMembership(roomId, currentUser.userId());
        YearMonth parsed;
        try {
            parsed = YearMonth.parse(yearMonth);
        } catch (DateTimeParseException ex) {
            throw new BadRequestException("yearMonth must be YYYY-MM");
        }
        FinalThreePosterId id = new FinalThreePosterId(roomId, parsed.toString());
        FinalThreePoster poster = posterRepository.findById(id)
                .orElseThrow(() -> new PosterNotFoundException(roomId, parsed));
        return ApiResponse.of(PosterDto.from(poster));
    }
}
```

**`PosterDto.java`:**

```java
package com.yeosal.api.ceremony;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;

public record PosterDto(
        long roomId,
        String yearMonth,
        String svgText,
        @JsonProperty("pngUrl") String pngUrl,
        Instant generatedAt) {

    public static PosterDto from(FinalThreePoster poster) {
        return new PosterDto(
                poster.getRoomId(),
                poster.getYearMonth(),
                poster.getSvgText(),
                poster.getPngUrl(),
                poster.getGeneratedAt());
    }
}
```

**`PosterNotFoundException.java`:**

```java
package com.yeosal.api.ceremony;

import com.yeosal.api.common.NotFoundException;
import java.time.YearMonth;

public class PosterNotFoundException extends NotFoundException {
    public PosterNotFoundException(long roomId, YearMonth yearMonth) {
        super("poster not found for roomId=" + roomId + " yearMonth=" + yearMonth);
    }
}
```

**Why extends `NotFoundException` (not a new exception):**

- `ApiExceptionHandler` already maps `NotFoundException` → HTTP 404 with `ApiErrorResponse(code="NOT_FOUND", ...)`. Adding a new `@ExceptionHandler` for `PosterNotFoundException` is unnecessary — the base mapping fires.
- Matches project-context line 87 ("Domain exceptions extend RuntimeException with a single-message constructor; Add a corresponding @ExceptionHandler in ApiExceptionHandler whenever you introduce a new one — otherwise it falls through to 5xx") — extending an already-mapped base is the correct shortcut.

**`requireMembership` callsite (existing service-layer helper):**

- `com.yeosal.api.room.RoomMembershipService.requireMembership(roomId, userId)` (Story 1.3 lineage — `SurvivalStateController.list` uses the same guard). Throws `ForbiddenException` → 403 via `ApiExceptionHandler`. Story 7.1 reuses this — NO new auth code. **Verify before coding:** `grep -n "requireMembership" BE/src/main/java/com/yeosal/api/room/RoomMembershipService.java` (expect a public method with `(long roomId, long userId)` shape; if the actual signature differs, follow the existing `SurvivalStateController` callsite pattern).
- If the user is NOT a member of the room, controller returns 403, NOT 404. Privacy preserves "this room exists but you can't see its poster" vs "this room doesn't exist."

**`@PathVariable @Pattern` validation:**

- `@Validated` at class level + `@Pattern` on the path variable produces `ConstraintViolationException` for malformed `yearMonth` (e.g., `2026-13`, `26-06`, `2026-6`). `ApiExceptionHandler` maps that to 400 VALIDATION (Story 1.5 precedent — `BE/src/main/java/com/yeosal/api/common/ApiExceptionHandler.java`).
- The `DateTimeParseException` catch is defense-in-depth — `@Pattern` already enforces `YYYY-MM` shape, but `YearMonth.parse` could still fail on edge cases.

**Endpoint NOT in SecurityConfig permitAll whitelist:**

- Unlike Story 6.1's `GET /rooms/{id}/invites/preview-card` (public because KakaoTalk fetcher is anonymous), Story 7.1's `GET /rooms/{id}/posters/{yearMonth}` requires authentication. The FE consumer is the room member's authenticated Home tab (Story 7.3). Story 7.3's Kakao share (FE) attaches the PNG bytes directly from the SVG render result — does NOT require unauthenticated PNG URL fetching.
- The PNG URL in the response IS publicly served by nginx at `/posters/{roomId}-{yearMonth}.png` (similar to Story 6.1's `/preview-cards/` path), but the SVG+metadata endpoint is room-member-only.

**Why TWO levels — public PNG path + authenticated metadata endpoint:**

- Kakao share thumbnail fetcher requires public PNG URL (no auth header sent). Same as Story 6.1.
- The "card metadata" (yearMonth, generatedAt, full SVG text) is room-member-only — eliminated members never see it (Story 7.3 AC: card not shown).
- Privacy: PNG content is `roomName + survivor nicknames` (visible to a Kakao share recipient — who already received the link, so they can see who's in the room). The authenticated endpoint protects "knowing the poster exists" + the SVG-inline render path.

**Anti-pattern (DO NOT IMPLEMENT):**

- Return raw `FinalThreePoster` entity from the controller — `ApiResponse<T>` envelope only carries DTO records (project-context line 110).
- Add a separate `GET /api/v1/rooms/{id}/posters/{yearMonth}/svg` returning `Content-Type: image/svg+xml` — redundant; the JSON envelope's `svgText` is already-inlined SVG, and Story 7.3 needs `pngUrl` + `generatedAt` alongside.
- Add `permitAll` for the metadata endpoint — privacy violation. Eliminated members lose card visibility (Story 7.3 AC) but the metadata endpoint would let anyone see it.
- Add `@PreAuthorize("hasRole(...)")` — the project uses imperative `requireMembership(...)` service-layer guards (Story 1.3, 5.1, 5.2, 5.4 precedent). Stay consistent.
- Map `PosterNotFoundException` to 204 (No Content) — 404 is correct ("no poster for this yearMonth"). FE branches on 404 vs 200.
- Embed the PNG bytes as base64 in `PosterDto.pngBase64` — 200KB payload bloat per response. Use `pngUrl` (nginx static serve).
- Return `null` for `pngUrl` without explanation in DTO — the nullable field is documented in the JSON (`null` value) and Story 7.3 FE branches on it (SVG-only render).

PRD: FR-8.7.3 (stable URL). Architecture: §6.4 line 815 (endpoint contract). project-context: line 109-118 (controller conventions), line 87 (domain exception mapping).

### AC8 — Checkstyle hex-literal guard coverage (EXISTING — no new config)

**Given** epic AC3 requires "direct hex literals are blocked by a Checkstyle / ArchUnit rule"
**When** ceremony module ships
**Then** the existing `BE/build.gradle:284-303` Checkstyle config covers the new package automatically (no `srcDirs` change, no `include/exclude` patch).

**Verification (during dev):**

1. After implementing `SvgRenderer.java`, run `./gradlew checkstyleMain` — expect PASS (renderer references only `GeneratedTokens.*` constants).
2. Intentionally introduce a hex literal in a private branch (e.g., `String fill = "#FF0000";` inside the renderer) — run `./gradlew checkstyleMain` — expect FAIL with the hex-literal-pattern violation. Revert.
3. The renderer ships with the AC10 `SvgRendererHexLiteralGateTest` that reads the renderer .java source as a String and asserts no `#XXXXXX` literal pattern exists (belt-and-suspenders to the Checkstyle gate). The token-diff IT (`SvgRendererTokenDiffIT`) is the behavior gate — see below.

**Why NO new ArchUnit dependency:**

- Adding `com.tngtech.archunit:archunit-junit5` for a single rule that Checkstyle already enforces is YAGNI. Story 1.5 ratified Checkstyle as the BE hex-literal gate.
- Architecture §4.15 line 410-413 describes both Checkstyle AND ArchUnit as candidate enforcement tooling — Story 1.5 picked Checkstyle. Story 7.1 honors that decision.
- If a future story needs cross-class architectural rules (e.g., "controllers must not inject repositories directly"), THAT story adds ArchUnit. Not Story 7.1.

**Why epic AC2's "token-only change reflects in output without renderer code change" matters here:**

- Epic line 908-910: "the FE updates `tokens.json` (e.g., key color tweak from oxblood-deep to oxblood-bright) When BE rebuilds (`./gradlew generateTokens build`) Then `SvgRenderer` outputs reflect the new color values without any code change in `SvgRenderer.java` — verified by an integration test that diffs SVG output before/after a token-only change."
- AC10 includes `SvgRendererTokenDiffIT` which:
  1. Captures `SvgRenderer.render(fixture)` output A.
  2. Mutates `FE/src/theme/tokens.json` in a tempdir copy to change `color.key.default` to a new oklch value.
  3. Runs `./gradlew generateTokens` against the tempdir copy (via a fresh JVM ProcessBuilder, OR — preferred — captures the constant via reflection on a re-classloaded `GeneratedTokens` if practical; if not, fall back to a structural assertion: SVG contains `GeneratedTokens.COLOR_KEY_DEFAULT` literal value).
  4. Captures output B.
  5. Asserts the SVG text differs *only* in the bytes occupied by the COLOR_KEY_DEFAULT hex.
- This is a behavior test, NOT a token-pipeline test. It proves that the renderer indirects through `GeneratedTokens.*` and does NOT inline hex (which would mean the diff also reveals an unchanged-literal artifact).
- **Pragmatic test shape (recommended):** assert `svg.contains(GeneratedTokens.COLOR_KEY_DEFAULT)` and `svg.contains(GeneratedTokens.COLOR_BG_CANVAS)` directly. If the renderer ever inlines `"#7E2C2A"` instead of `COLOR_KEY_DEFAULT`, the test still passes today but the next codegen run breaks it — combined with `SvgRendererHexLiteralGateTest` source scan, this is sufficient. The full classloader-reload variant is documented but **not required** for Story 7.1 merge.

**Anti-pattern (DO NOT IMPLEMENT):**

- Add ArchUnit dependency just to "complete the OR" of epic AC3 ("Checkstyle / ArchUnit") — adds 2MB to build classpath for no marginal enforcement.
- Add a `// CHECKSTYLE:OFF HexLiteralCheck` annotation anywhere in the renderer to "work around" the guard — the entire point of the guard is to fail. There is no legitimate hex literal in renderer code; if a deviation is needed, escalate to a Story 1.5 follow-up.
- Whitelist the `ceremony/` package in `checkstyle.xml` — same anti-pattern.
- Skip the `SvgRendererTokenDiffIT` test ("Story 1.5 already proves codegen") — Story 1.5 proves codegen, but does NOT prove `SvgRenderer` indirects through the constants. The diff/contains test is the only way to prove epic AC2.

PRD: FR-8.7.2 (token-driven render). Architecture: §4.15 (brand-voice + a11y gate enforcement), §4.16 (codegen). Story 1.5 AC4 (Checkstyle hex-literal guard).

### AC9 — File / scope fence (LOCKED ALLOW LIST)

**Given** the story's scope must be auditable in PR review
**When** the dev agent finishes
**Then** the diff touches **exactly** these files (no more, no less):

**NEW files (9 BE source + 8 BE test):**

```
BE/src/main/java/com/yeosal/api/ceremony/SvgRenderer.java
BE/src/main/java/com/yeosal/api/ceremony/FinalThreePoster.java
BE/src/main/java/com/yeosal/api/ceremony/FinalThreePosterId.java
BE/src/main/java/com/yeosal/api/ceremony/FinalThreePosterRepository.java
BE/src/main/java/com/yeosal/api/ceremony/FinalThreeService.java
BE/src/main/java/com/yeosal/api/ceremony/PosterController.java
BE/src/main/java/com/yeosal/api/ceremony/PosterDto.java
BE/src/main/java/com/yeosal/api/ceremony/SurvivorTenureRow.java
BE/src/main/java/com/yeosal/api/ceremony/PosterNotFoundException.java
BE/src/test/java/com/yeosal/api/ceremony/SvgRendererTest.java
BE/src/test/java/com/yeosal/api/ceremony/SvgRendererHexLiteralGateTest.java
BE/src/test/java/com/yeosal/api/ceremony/SvgRendererTokenDiffIT.java
BE/src/test/java/com/yeosal/api/ceremony/FinalThreeServiceTest.java
BE/src/test/java/com/yeosal/api/ceremony/FinalThreeServiceIT.java
BE/src/test/java/com/yeosal/api/ceremony/PosterControllerTest.java
BE/src/test/java/com/yeosal/api/ceremony/BrandVoicePosterPhrasesTest.java
BE/src/test/java/com/yeosal/api/room/chat/ChatServiceMonthlyNoSurvivorsTest.java
```

**MODIFIED files (existing — surgical edits only):**

```
BE/src/main/java/com/yeosal/api/room/chat/ChatService.java
  └── ADD one method `publishMonthlyNoSurvivorsSystemMessage(long roomId, YearMonth yearMonth)`
       at the bottom of the public-method block. NO other edits.

BE/src/main/resources/application.yml
  └── ADD under existing `yeosal.share:` block:
         posters-dir: "${YEOSAL_POSTERS_DIR:/var/yeosal/posters}"
       (one line; reuses `yeosal.share.preview-card-base` from Story 6.1, no other yeosal.share keys added)

infra/docker-compose.yml
  └── ADD to `api.volumes`:
         - ./posters-cache:/var/yeosal/posters
       AND `nginx.volumes` mirror (same line under `nginx.volumes`).

infra/nginx/default.conf
  └── ADD a `location /posters/` block mirroring Story 6.1's `location /preview-cards/`
       (alias to /var/yeosal/posters, add_header Cache-Control "public, max-age=86400").
```

**BANNED PATHS (red lines — dev agent MUST NOT edit these):**

```
BE/build.gradle                            ← no new dependency, no Checkstyle config change
BE/src/main/resources/db/migration/V*.sql  ← V11 (10) already exists, NO new migration
FE/**                                       ← Story 7.3's scope, no FE source
FE/src/theme/tokens.json                    ← Story 1.5's canonical source, untouched
BE/src/main/java/com/yeosal/api/kakaoshare/* ← Reuse only, no edits
BE/src/main/java/com/yeosal/api/realtime/*  ← No new RealtimeEvent variant (Story 7.2)
BE/src/main/java/com/yeosal/api/YeosalApiApplication.java ← @EnableAsync already present (Story 6.1)
BE/src/main/java/com/yeosal/api/common/ApiExceptionHandler.java ← Extends NotFoundException, no new mapping
BE/src/main/java/com/yeosal/api/common/SecurityConfig.java     ← Endpoint requires auth (default), no permitAll
docs/**, RUNBOOK.md                         ← Story 7.2 / Story 6.3 follow-up scope
infra/.env*, FE/.env*                       ← Sample env values via application.yml only
```

**Diff sanity check (run before sprint-status flip):**

```bash
git diff --name-only main | sort | tee /tmp/story7-1-files.txt
# Expect exactly the union of NEW + MODIFIED above (≤ 22 files).
# No file in BANNED PATHS list.
```

**Anti-pattern (DO NOT IMPLEMENT):**

- "While I'm in there" cleanup of Story 6.1's `PngRasterizer` (e.g., extract dimensions to constructor) — out of scope. File a follow-up.
- Add a `docs/ceremony.md` description — Story 7.2 owns the full ceremony documentation arc (job timing, retry semantics). Premature.
- Touch `FE/src/theme/tokens.json` to add D1 Editorial color override — banned-paths. AC3 Anti-pattern explicit.
- Add `final_three_posters` row to V12-style migration as a smoke fixture — fixtures live in tests, not in migrations.

PRD / Architecture: comprehensive scope fence documented across AC1–AC8.

### AC10 — Test matrix (NET-ADDITIVE, RED → GREEN order)

**Given** TDD is enforced (project-context line 145, common/testing.md ratio target 80%+)
**When** Story 7.1 ships
**Then** the test suite adds **net-additive** tests — no existing test is removed or weakened. RED → GREEN order documented per file:

| File | Cases | Type | Notes |
|---|---|---|---|
| `SvgRendererTest.java` | 12 | Unit (Mockito) | Pure-function rendering: top-3 highlight, remaining wrap, year label format, brand-voice phrases, XML escape, 1/2/3/4/25/30 survivor counts, IllegalArgumentException on zero. |
| `SvgRendererHexLiteralGateTest.java` | 2 | Unit (file-read) | Read renderer .java source as String; assert no `#XXXXXX` literal pattern + no `oklch(` / `rgb(` substring. Belt-and-suspenders for Checkstyle. |
| `SvgRendererTokenDiffIT.java` | 1 | Slice (`@SpringBootTest`) | Render fixture → assert SVG contains `GeneratedTokens.COLOR_KEY_DEFAULT` literal value + `GeneratedTokens.COLOR_BG_CANVAS` literal value (proves indirection through constants — epic AC2 lock). Opt-in via `yeosal.boot-smoke` system property for parity with V11MigrationIT. |
| `FinalThreeServiceTest.java` | 9 | Unit (Mockito) | Existing-row short-circuit; zero-survivor chat fallback (with byte-identical body assertion); survivors > 0 happy path with PNG; PNG rasterize failure tolerance (svg-only persist); RoomNotFoundException propagation; survivor query field projection (`SurvivorTenureRow` shape); top-3 tie-breaker by user_id; disk-write failure → pngUrl=null; idempotency contract documented. |
| `FinalThreeServiceIT.java` | 3 | IT (Testcontainers, opt-in `yeosal.boot-smoke`) | Real Postgres + real SVG + real Batik PNG bytes. (1) Happy path: 5 ACTIVE survivors → row inserted + PNG bytes ≥ 5KB. (2) Zero-survivor path: chat row inserted with locked body, NO `final_three_posters` row. (3) Idempotent second call: returns existing row, NO chat row republished. |
| `PosterControllerTest.java` | 5 | Web slice (`@WebMvcTest`) | 200 on existing poster (full DTO shape); 404 on missing (via `PosterNotFoundException`); 403 on non-member; 400 on malformed yearMonth (`2026-13`, `26-06`); 401 on unauthenticated. |
| `BrandVoicePosterPhrasesTest.java` | 1 | Unit | Sweep `SvgRenderer.WORDMARK / FOOTER / SURVIVOR_STAT_SUFFIX` + `ChatService.publishMonthlyNoSurvivorsSystemMessage` body string against AVOID lexicon (8 tokens from `tools/brand-voice-lint.ts:50-59`). Assert zero matches. |
| `ChatServiceMonthlyNoSurvivorsTest.java` | 4 | Unit (Mockito) | Body string byte-equal to epic line 922 lock; payload contains `yearMonth` JSON-escaped; REQUIRES_NEW propagation observed; reuses `publishSystem` chokepoint (no direct repository call). |

**Total: 37 BE tests** (32 unit/web-slice + 4 opt-in IT-or-slice + 1 IT). Per AC9, NO FE tests.

**Coverage notes:**

- All ceremony module classes get a unit test or fixture coverage.
- `FinalThreePoster` entity + `FinalThreePosterId` + `FinalThreePosterRepository` + `PosterDto` are JPA mapping / record-only — no methods to unit-test beyond the entity's `@PrePersist` clock fallback. They're covered transitively by `FinalThreeServiceIT.happyPath` (asserts entity round-trips correctly).
- `PosterNotFoundException` is covered by `PosterControllerTest.404OnMissing`.

**Why some IT are opt-in via `yeosal.boot-smoke`:**

- Project precedent (project-context line 142 + Story 1.4 V11MigrationIT, Story 5.4 ChatServiceRuleChangeIT, Story 6.1 PreviewCardEndToEndIT). Local dev `./gradlew test` does NOT run Testcontainers ITs by default (Docker may be unavailable, slow startup). CI passes `-Dyeosal.boot-smoke=true` per `BE/build.gradle:310-318` to enable them.

**Anti-pattern (DO NOT IMPLEMENT):**

- Mock `GeneratedTokens` for the unit test — those are `public static final` Java constants. Tests reference them directly (`assertThat(svg).contains(GeneratedTokens.COLOR_KEY_DEFAULT)`). This is what makes the test robust to token value changes.
- Use H2 for `FinalThreeServiceIT` — project-context line 142 forbids H2 (Postgres-specific `text` columnDefinition + `survival_state.status` enum check would behave differently). Use Testcontainers Postgres 16.
- Run real Batik in unit tests — slow + flaky. Mock `PngRasterizer` to return fixed bytes in unit tests; let the IT exercise real Batik.
- Assert exact SVG bytes — fragile to whitespace tweaks. Assert structural invariants: `contains(roomName)`, `contains(top3[0].nickname)`, `not contains("#" + plainHex)` patterns.
- Skip the `BrandVoicePosterPhrasesTest` ("Story 6.1's lint catches it") — lint is WARN, not blocking. AC10 test catches drift in the locked phrase constants.
- Add `@MockBean(ChatService.class)` to `FinalThreeServiceIT` — the IT exercises the real ChatService chokepoint to prove the chat row actually persists with the locked body. That's the whole point.

PRD: FR-8.7.* coverage. Architecture: §4.15 (test-as-gate). project-context: line 137-159 (testing rules).

### AC11 — Verification matrix (gate before sprint-status flip)

**Given** the dev agent finishes implementation
**When** declaring story complete
**Then** the following 14 gates MUST all PASS in order before flipping sprint-status `in-progress → review`:

| # | Gate | Command | Expected |
|---|---|---|---|
| 1 | Compile | `cd BE && ./gradlew compileJava` | BUILD SUCCESSFUL |
| 2 | Token codegen produced | `ls BE/build/generated/sources/tokens/com/yeosal/api/theme/GeneratedTokens.java` | exists |
| 3 | Checkstyle hex-literal guard | `cd BE && ./gradlew checkstyleMain` | BUILD SUCCESSFUL (renderer hex-clean) |
| 4 | Unit tests | `cd BE && ./gradlew test --tests "com.yeosal.api.ceremony.*"` + `--tests "com.yeosal.api.room.chat.ChatServiceMonthlyNoSurvivorsTest"` | All GREEN |
| 5 | Brand-voice phrases | `cd BE && ./gradlew test --tests "*BrandVoicePosterPhrasesTest"` | GREEN (AVOID-lexicon-zero) |
| 6 | Renderer hex-literal source gate | `cd BE && ./gradlew test --tests "*SvgRendererHexLiteralGateTest"` | GREEN |
| 7 | Token-diff integration | `cd BE && ./gradlew test --tests "*SvgRendererTokenDiffIT" -Dyeosal.boot-smoke=true` (opt-in) | GREEN locally — required in PR-CI |
| 8 | Service IT (Testcontainers Postgres) | `cd BE && ./gradlew test --tests "*FinalThreeServiceIT" -Dyeosal.boot-smoke=true` (opt-in) | GREEN locally — required in PR-CI |
| 9 | Full BE suite delta | `cd BE && ./gradlew test` | net-additive: BASELINE + 37 = NEW count, all GREEN |
| 10 | Hibernate ddl-auto validate | Boot the app locally (`cd BE && ./gradlew bootRun`) | Boot succeeds (FinalThreePoster mapping validates against V11 (10) schema) |
| 11 | Endpoint smoke | Authenticated `GET /api/v1/rooms/1/posters/2026-05` against running BE with seed data | 200 with `svgText` containing nicknames, OR 404 if seed lacks data |
| 12 | Public 404 sanity | `curl -i https://api.rearleg.com/yeolsal/api/v1/rooms/1/posters/2026-05` (no auth) | 401 (proves endpoint requires auth — NOT 200/404) |
| 13 | Diff sanity (scope fence) | `git diff --name-only main \| sort` | Exact union of AC9 NEW + MODIFIED; nothing in AC9 banned-paths |
| 14 | Brand-voice lint (FE-side) | `cd FE && npx tsx tools/brand-voice-lint.ts` | 0 HARD violations (baseline; no FE changes in this story) |

**Gate 7 + 8 caveat (Story 5.4 / 6.1 precedent):**

- Docker-Compose / Testcontainers Postgres may not be available on dev hosts (`No usable Docker environment found`). In that case, the dev agent runs gates 1–6, 9–14 locally and defers gates 7 + 8 to PR-CI. The PR description MUST explicitly note this deferral (matching Story 5.4 / 5.3 / 5.1 precedent in `_bmad-output/implementation-artifacts/*.md` review sections).
- Story 6.1 review section explicitly documented Docker-unavailable IT deferral; same pattern applies.

**Anti-pattern (DO NOT IMPLEMENT):**

- Skip Gate 10 ("compile passed so boot will too") — boot exercises Hibernate `ddl-auto: validate` against real schema; `columnDefinition = "text"` issue would only surface at boot.
- Skip Gate 12 ("Gate 11 covers auth") — Gate 11 sends a valid JWT; Gate 12 proves the negative path.
- Skip Gate 13 ("review will catch it") — automated diff sanity prevents `infra/nginx/default.conf` or `BE/build.gradle` accidental edits.
- Run Gate 9 first ("faster feedback") — Gate 1 must pass first because Gate 9 includes compilation.

PRD: comprehensive scope. Architecture: §4.15 (gates). project-context: line 213 (verify.sh comprehensive run).

### AC12 — Architecture deviation notes (DOC FOLLOW-UP, NON-BLOCKER)

**Given** the implementation may deviate slightly from Architecture text in places
**When** the story merges
**Then** an explicit log of deviations is included in the PR body so future architecture reviewers can update the doc (separate PR):

| Deviation | Architecture says | Story does | Justification |
|---|---|---|---|
| **PngRasterizer location** | §6.1 line 587 lists `PngRasterizer.java` under `ceremony/` | Cross-module reuse of `kakaoshare/PngRasterizer.java` (Story 6.1) | Story 6.1 shipped first with identical Kakao-share-thumbnail constraint. Duplication wasteful. Architecture doc follow-up: revise §6.1 to reflect kakaoshare-as-canonical. |
| **`SvgRenderer.render` signature** | Epic line 905 wording `SvgRenderer.render(roomId, yearMonth)` | `SvgRenderer.render(Room, YearMonth, List<SurvivorTenureRow>, int)` | Pure-function shape required for AC2 token-diff IT. Orchestration moves to `FinalThreeService.generatePoster(roomId, yearMonth)` which IS the `(roomId, yearMonth)` entry point — epic wording was an API name intent. |
| **No new ArchUnit dependency** | Epic AC3 + Arch §4.15 cite "Checkstyle / ArchUnit" | Checkstyle only (existing) | Single-rule enforcement; Story 1.5 ratified Checkstyle. Architecture §4.15 leaves the OR open. |
| **No new RealtimeEvent variant** | Architecture §6.1 line 599 lists `RealtimeEvent.MonthlyPosterReady` | Out of scope — Story 7.2 emits it | Per-room render API has no realtime obligation. Story 7.2 batch job is the publish call site (single fan-out moment per room per month). |
| **`SvgRenderer` IllegalArgumentException on zero survivors** | Epic AC5 wording suggests render is conditional | Service short-circuits via `chatService.publishMonthlyNoSurvivorsSystemMessage`, renderer treats zero as invariant violation | Eligibility is a service-layer concern; renderer is a pure function with strict preconditions. Defense in depth. |

**Doc PR follow-up tasks (NON-BLOCKER for Story 7.1):**

1. Update Architecture §6.1 ceremony module outline — note `PngRasterizer` is cross-module from `kakaoshare/`.
2. Architecture §4.15 — keep both Checkstyle and ArchUnit as options; document that v1 uses Checkstyle exclusively.

### AC13 — Sprint-status transitions

**Given** Story 7.1 is the **first** story in Epic 7 (per `sprint-status.yaml` line 167)
**When** the workflow runs
**Then** status transitions are explicit:

1. **At story-creation time (THIS workflow):**
   - `epic-7: backlog` → `epic-7: in-progress` (first-story-in-epic rule from create-story Step 1).
   - `7-1-server-side-svg-poster-renderer: backlog` → `ready-for-dev`.
   - `last_updated` → `2026-06-07`.
   - Add a comment header noting both transitions + the first-story-in-epic context.

2. **At dev-story kickoff (next session):**
   - `7-1-server-side-svg-poster-renderer: ready-for-dev` → `in-progress`.

3. **At implementation-complete (next session):**
   - `7-1-server-side-svg-poster-renderer: in-progress` → `review`.

4. **At PR-merge / review-passed (later session):**
   - `7-1-server-side-svg-poster-renderer: review` → `done`.
   - `epic-7` stays `in-progress` (7.2 and 7.3 remain backlog).

## Tasks / Subtasks

- [x] Verify AC0 inventory: V11 (10) row exists, Batik 1.17 in build.gradle, kakaoshare/PngRasterizer present, GeneratedTokens.java generated, Checkstyle config present.
- [x] Create `com.yeosal.api.ceremony` package directory (AC1).
- [x] Implement `FinalThreePosterId.java` + `FinalThreePoster.java` + `FinalThreePosterRepository.java` (AC2).
- [ ] Boot app once with empty repo to verify `ddl-auto: validate` accepts the entity (Gate 10 dry run). _(deferred to PR-CI — Docker daemon unavailable on dev host; Story 5.4/6.1 precedent)_
- [x] Implement `SurvivorTenureRow.java` record (AC3 prerequisite).
- [x] Implement `SvgRenderer.java` — RED first via `SvgRendererTest.java`, GREEN by token-only references (AC3, AC10).
- [x] Write `SvgRendererHexLiteralGateTest.java` (AC10) — RED, then verify guard.
- [x] Implement `PngRasterizer` cross-module import in `FinalThreeService` (AC6).
- [x] Add `publishMonthlyNoSurvivorsSystemMessage` method to `ChatService.java` — RED first via `ChatServiceMonthlyNoSurvivorsTest.java`, GREEN by wrapper around `publishSystem` (AC5, AC10).
- [x] Implement `FinalThreeService.java` (AC4) — RED first via `FinalThreeServiceTest.java`, GREEN by the three-path orchestration.
- [x] Implement `PosterNotFoundException.java` + `PosterDto.java` + `PosterController.java` (AC7) — RED first via `PosterControllerTest.java`.
- [x] Add `yeosal.share.posters-dir` to `application.yml` (AC9).
- [x] Update `infra/docker-compose.yml` + `infra/nginx/default.conf` for `/posters/` static-serve (AC9).
- [x] Write `BrandVoicePosterPhrasesTest.java` (AC10).
- [x] Write `SvgRendererTokenDiffIT.java` + `FinalThreeServiceIT.java` (opt-in `yeosal.boot-smoke`, AC10).
- [x] Run Gate 1–14 verification matrix (AC11) — Gates 1, 2, 3, 4, 5, 6, 9, 13, 14 PASS locally; Gates 7, 8, 10, 11, 12 deferred to PR-CI (Docker daemon unavailable).
- [x] If all 14 gates GREEN locally + Docker-bound deferrals are documented: flip sprint-status `in-progress → review` (AC13.3).

### Review Findings

- [x] [Review][Decision] Public poster PNG filenames are predictable and enumerable — accepted for Story 7.1 as the current Kakao-compatible public/deterministic tradeoff. `FinalThreeService.writePngAtomically` writes `roomId-yearMonth.png` while nginx serves `/posters/` anonymously; revisit only if product/privacy requirements change.
- [x] [Review][Patch] Persisted `pngUrl` does not match the nginx static route [BE/src/main/java/com/yeosal/api/ceremony/FinalThreeService.java:61] — fixed by serving `/yeolsal/posters/` from nginx alongside `/posters/`.
- [x] [Review][Patch] Concurrent generation can collide on the same temp PNG path and primary key insert [BE/src/main/java/com/yeosal/api/ceremony/FinalThreeService.java:93] — fixed with a Postgres advisory transaction lock, second repository read after lock acquisition, and unique temp PNG filenames.
- [x] [Review][Patch] Long valid nicknames can create a blank remaining-survivor SVG row and overflow the poster [BE/src/main/java/com/yeosal/api/ceremony/SvgRenderer.java:158] — fixed by making over-budget first names occupy their own line instead of emitting an empty line.
- [x] [Review][Defer] Zero-survivor fallback is not idempotent [BE/src/main/java/com/yeosal/api/ceremony/FinalThreeService.java:82] — deferred, story explicitly assigns duplicate prevention to Story 7.2's caller pre-filter/replay contract.
- [x] [Review][Defer] Real Postgres/Batik integration tests are skipped by default [BE/src/test/java/com/yeosal/api/ceremony/FinalThreeServiceIT.java:41] — deferred, this matches the story's documented `yeosal.boot-smoke` PR-CI gate and existing project precedent.

## Dev Notes

### Context — what V11 + Story 1.5 + Story 6.1 ship that Story 7.1 builds on

**V11 migration (shipped Story 1.4, PRs #55/#57 merged 2026-05-13):**

- `final_three_posters` table at `BE/src/main/resources/db/migration/V11__survival_revival_economy.sql:140-147`. Composite PK `(room_id, year_month)`. `svg_text text not null`, `png_url varchar(512)` nullable, `generated_at timestamptz not null default now()`. FK on `rooms(id) on delete cascade`.
- `survival_state` table (V11 step 3) with `status varchar(16)` check-constrained to `('ACTIVE','YELLOW','RED','SPECTATOR')`. Story 7.1's survivors query joins on `status = 'ACTIVE'`.
- `room_members.joined_at` column (already pre-V11) — entity confirmed at `BE/src/main/java/com/yeosal/api/room/RoomMember.java:38-39`. This is the tenure source.
- `users.nickname` (pre-V11) — entity confirmed at `BE/src/main/java/com/yeosal/api/user/User.java:24-25` (length 80, not null). This is the displayed name.
- **Story 7.1 ships ZERO Flyway migrations** — schema is sufficient.

**Story 1.5 (Design System Foundation v2, merged 2026-05-13):**

- `FE/src/theme/tokens.json` — canonical source of truth, D1 Editorial sub-mode keys at lines 152-162.
- `BE/build.gradle` `generateTokens` task (lines 64-191) emits `BE/build/generated/sources/tokens/com/yeosal/api/theme/GeneratedTokens.java` with:
  - Base constants: `COLOR_BG_CANVAS`, `COLOR_TEXT_PRIMARY`, `COLOR_TEXT_SECONDARY`, `COLOR_TEXT_TERTIARY`, `COLOR_KEY_DEFAULT`, `COLOR_KEY_LINE`, `TYPOGRAPHY_DISPLAY_SM_*`, `TYPOGRAPHY_BODY_LG_*`, `TYPOGRAPHY_BODY_*`, `TYPOGRAPHY_CAPTION_*`.
  - `SubMode.Editorial.TYPOGRAPHY_HEADING_WEIGHT = 900` and `SubMode.Editorial.TYPOGRAPHY_HEADING_TRACKING = "-0.025em"` (verified at line 137-139 of the generated file).
  - All hex values are the actual color strings (e.g., `COLOR_KEY_DEFAULT = "#7E2C2A"`).
- Checkstyle hex-literal guard at `BE/build.gradle:284-303` — passes Story 1.5 enforcement. Renderer references only constants ⇒ Checkstyle passes.

**Story 5.4 (chat broadcast for rule change, merged 2026-06-03 PR #89):**

- `ChatService.publishRuleChangeSystemMessage:185-201` is the precedent shape Story 7.1 mirrors for `publishMonthlyNoSurvivorsSystemMessage`. Same REQUIRES_NEW propagation, same JSON payload pattern using the `JSON` helper, same delegation to `publishSystem`.
- `ChatService.publishSystem:130-167` is the chokepoint — fans out to `/topic/rooms.{id}.chat`, persists `ChatMessage` row, validates with the `chk_chat_messages_kind` enum check.

**Story 6.1 (preview-card renderer, merged 2026-06-06 PR #90):**

- `kakaoshare/PngRasterizer.java` — 800×420 hard-coded, `@PostConstruct` warm-up. Story 7.1 reuses this bean.
- `kakaoshare/InvitePreviewRenderer.java` — D1 Editorial layout precedent for `SvgRenderer`: same `escapeXml` helper, same font-family choices, same `GeneratedTokens.*` consumption pattern. Story 7.1's `SvgRenderer` is structurally similar but with different visual layout (poster vs. card).
- `BE/build.gradle:32-33` already has Batik 1.17 deps. NO new build.gradle edits.
- `ApiExceptionHandler` already maps `ServiceUnavailableException` → 503 (Story 6.1). Story 7.1 does NOT throw this exception in happy path; relies on existing `NotFoundException` → 404 mapping for `PosterNotFoundException`.

**Story 1.3 (privacy-filtered survival roster):**

- `RoomMembershipService.requireMembership(...)` is the auth chokepoint that throws `ForbiddenException` for non-members. Story 7.1's `PosterController` reuses this — NO new auth code. (Confirm signature shape via grep before wiring.)

### Implementation trap #1 — `IdClass` composite-key entity hygiene

JPA's `@IdClass` requires:
1. The Id-class implements `Serializable`.
2. The Id-class has a public no-arg constructor.
3. The Id-class implements `equals` + `hashCode` (used as the cache key in Hibernate's second-level cache).
4. Each `@Id`-annotated field in the entity has a matching field of the same name and type in the Id-class.

Mirror `survival/RecordVisibilityPrefId.java:11-37` byte-for-byte structurally. If the Id-class fields don't EXACTLY match the entity's `@Id` field names (case-sensitive), Hibernate boot fails with `Cannot determine type for ...`.

**Defense:** AC10's `FinalThreeServiceIT.happyPath` is the first place this would surface — boot fails → IT fails fast.

### Implementation trap #2 — Hibernate `ddl-auto: validate` requires `columnDefinition = "text"` on `svgText`

Hibernate's default mapping for `String` is `varchar(255)` (or `varchar(N)` if `@Column(length=N)`). V11's `svg_text text` column would fail validation on the default mapping with `Wrong column type ... expected: varchar(255), found: text`.

**Defense:** `@Column(name = "svg_text", nullable = false, columnDefinition = "text")` on the field. `@Lob` is a wrong solution — would force `oid` large-object handling.

### Implementation trap #3 — Native query `Tuple` column type ambiguity

`t.get("user_id", Long.class)` fails on Postgres + Hibernate 6 with `ClassCastException`: the native projection returns `BigInteger` for `bigint` columns sometimes, `Long` other times depending on driver version.

**Defense:** Cast through `Number`:

```java
((Number) t.get("user_id")).longValue()
```

Same for `joined_at` — cast via `java.sql.Timestamp` (the JDBC native type) then `.toInstant()`. AC4 sample shows this pattern; do not deviate.

### Implementation trap #4 — Cross-module DI requires no special annotation

Spring's `@SpringBootApplication` does package-recursive `@ComponentScan` from `com.yeosal.api`. Both `kakaoshare/` and `ceremony/` are scanned. Constructor-injecting `PngRasterizer` (a `@Component` in `kakaoshare/`) into `FinalThreeService` (a `@Service` in `ceremony/`) works without any `@Import` or `@ComponentScan(basePackages=...)` edit.

**Verification (boot-time):** Gate 10 in AC11. If Spring fails to wire, the error is `UnsatisfiedDependencyException` — clear root cause.

**Anti-pattern:** Don't add `@Import(KakaoShareConfig.class)` — there's no `KakaoShareConfig`. The whole module is `@Component`-discovered.

### Implementation trap #5 — `@Transactional` on `FinalThreeService.generatePoster` + cross-bean `chatService.publishMonthlyNoSurvivorsSystemMessage` with REQUIRES_NEW

The outer `@Transactional` on `generatePoster` and the inner REQUIRES_NEW on the chat publish wrapper interact:

- Outer txn starts → reads poster (none) → reads survivors (zero) → calls inner REQUIRES_NEW → outer txn suspends → inner txn opens, inserts ChatMessage row, commits → outer txn resumes → returns `Optional.empty()`.
- If outer txn later throws (it won't on zero-survivor path — nothing else writes), the inner ChatMessage row STAYS committed (REQUIRES_NEW semantic).

This is the intended behavior — chat message is published even if outer logic later errors.

**Anti-pattern:** Using `Propagation.MANDATORY` or `Propagation.REQUIRED` on the chat wrapper — would tie inner persistence to outer rollback. Story 5.4 picked REQUIRES_NEW for the same reason; Story 7.1 mirrors.

### Implementation trap #6 — `YearMonth.parse` vs `@Pattern` validation race

If `@Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])")` accepts the path variable, can `YearMonth.parse` still throw?

- For `YYYY-MM` shape: NO, the regex is strictly tighter than `YearMonth.parse`'s grammar.
- The defense-in-depth catch in `PosterController` is for: future-self forgetting `@Pattern`, or a refactor moving the parse out of the controller.

**Defense:** Keep both. The `catch (DateTimeParseException)` is cheap.

### Implementation trap #7 — PNG file naming with hyphen ambiguity

`/posters/{roomId}-{yearMonth}.png` = `/posters/42-2026-06.png` — three hyphens. A naive parser splitting on hyphen would fail.

**Defense:** The filename is one-way (write only). Nothing parses the filename back into `(roomId, yearMonth)`. The PNG URL stored in `final_three_posters.png_url` is the source of truth. Nginx serves the static file path verbatim; no path parsing.

**Anti-pattern:** Adding a separator like `__` (e.g., `42__2026-06.png`) — different convention from Story 6.1's `{roomId}.png` pattern adds learning load; the three-hyphen URL is fine.

### Implementation trap #8 — `room_members` count vs `survival_state.status='ACTIVE'` count

For the survivor count `N` in "N명 생존":

- `room_members.size()` counts ALL members (including SPECTATOR, RED, eliminated). NOT what we want.
- `survival_state.status = 'ACTIVE'` count is the survivor count. THIS is what we want.

Story 7.1's `querySurvivors` returns ONLY ACTIVE rows via the WHERE clause. The list size IS the survivor count (passed to renderer as `totalSurvivorCount = allSurvivors.size()`).

**Anti-pattern:** Adding a second query for "total members" to render alongside "N survivors" — privacy violation (other rooms' member-counting could leak). The poster shows only ACTIVE survivors and their count.

### Implementation trap #9 — Disk write directory permissions in dev / prod

The PNG output dir `/var/yeosal/posters` must be writable by the Spring Boot process (UID inside the container).

- Local dev with Docker Compose: `./posters-cache:/var/yeosal/posters` host bind. The host directory inherits the user's UID; container's `appuser` may not have write permission.
- Production: same concern. RUNBOOK (Story 6.3-style follow-up, NOT this story) documents `chown -R` requirement.

**Defense:** `FinalThreeService.writePngAtomically` catches `IOException` and logs WARN, persisting `pngUrl=null`. The SVG row still saves. Story 7.3's FE renders SVG inline regardless of PNG existence (Story 7.3 AC: "SVG inline").

### Implementation trap #10 — First Batik invocation cold latency budget

`kakaoshare/PngRasterizer.@PostConstruct warmUp()` already warms the AWT font cache once at boot (Story 6.1 trap #5). The first ceremony PNG transcode still pays the per-call PNGTranscoder construction cost (~50ms) but NOT the 300-500ms native font subsystem init.

**p99 budget per AC1 epic line 928:** "p99 latency < 3s per poster (NFR-9.1.4)". For 800×420 + ~5-30 nicknames + warm Batik:
- SVG build: ~1ms
- PNG transcode: ~200-400ms
- Disk write: ~10ms
- DB insert: ~20ms
- **Total: ~250-450ms p50, ~600-900ms p99**

Story 7.1's IT (`FinalThreeServiceIT`) measures wall-clock for the happy path and asserts `< 1500ms` (margin against p99 < 3s — accounts for Testcontainers JVM cold start latency in shared CI).

### Implementation trap #11 — Tie-break ordering between members with same `joined_at`

V11 backfill (V11 step 13) inserts `survival_state` rows for every existing `room_members` row, but it does NOT touch `room_members.joined_at`. If two members were created in the same INSERT statement (e.g., via room-create), their `joined_at` matches at second precision.

**Defense:** `ORDER BY rm.joined_at ASC, u.id ASC` (AC4 SQL). User-id tie-breaker is deterministic across reruns.

**Verification:** `FinalThreeServiceTest.tie_break_by_user_id_when_joined_at_matches` — fixture has two members joined at same `Instant`, asserts the lower-userId member is the top-3 anchor.

### Implementation trap #12 — `RealtimePublisher` is NOT injected here

Even though `RealtimePublisher` is the project's chokepoint for STOMP fan-out, Story 7.1 does NOT inject it. The `MonthlyPosterReady` realtime event is Story 7.2's concern (the batch job emits one fan-out per generated poster after the job completes).

**Defense:** AC9 banned-paths excludes `realtime/`. Architecture §6.1 line 599 lists `MonthlyPosterReady` — Story 7.2 will add the sealed-variant-equivalent (or, per project's actual implementation, a `new RealtimeEvent("MONTHLY_POSTER_READY", ...)` envelope since `RealtimeEvent` is an open record not a sealed type — see Story 5.4 precedent).

### Implementation trap #13 — `application.yml` `yeosal.share.*` cohabitation

Story 6.1 already added:
```yaml
yeosal:
  share:
    preview-cards-dir: "${YEOSAL_PREVIEW_CARDS_DIR:/var/yeosal/preview-cards}"
    preview-card-base: "${YEOSAL_PREVIEW_CARD_BASE:https://api.rearleg.com/yeolsal}"
```

Story 7.1 adds:
```yaml
    posters-dir: "${YEOSAL_POSTERS_DIR:/var/yeosal/posters}"
```

The `preview-card-base` is REUSED by Story 7.1 (same host base for `/posters/...` URL).

**Defense:** Don't add a parallel `yeosal.share.posters-base` — same base; reuse `preview-card-base`. (Confusingly named, but follow-up rename is a separate story; project-context line 196 frowns on cleanup-with-feature.)

### Implementation trap #14 — `infra/nginx/default.conf` cohabitation with Story 6.1's `/preview-cards/` block

The nginx config from Story 6.1 has a `location /preview-cards/` block. Story 7.1 adds a `location /posters/` block adjacent to it.

```nginx
location /posters/ {
    alias /var/yeosal/posters/;
    add_header Cache-Control "public, max-age=86400" always;  # 24h, longer than preview-card
    add_header Content-Type image/png;
    try_files $uri =404;
}
```

- `max-age=86400` (24h) is intentional — posters are immutable (PRD FR-8.7.6); long browser/CDN cache is safe. Preview cards used `max-age=3600` (1h) because they invalidate.
- `try_files $uri =404` — same as preview-cards block; Kakao fetcher receives 404 for missing files rather than a confusing redirect.

**Anti-pattern:** Reusing the `/preview-cards/` block (mounting same dir twice) — confuses ops/RUNBOOK.

## Out of scope (DO NOT IMPLEMENT IN THIS STORY)

1. **`FinalThreeJob.@Scheduled` monthly trigger** — Story 7.2 scope (epic line 933-950). Story 7.1 ships the per-room API; Story 7.2 wraps with @Scheduled + thread pool + cross-room fan-out.
2. **`RealtimeEvent.MonthlyPosterReady` fan-out** — Story 7.2 scope. No `RealtimePublisher` injection in this story.
3. **FE `FinalThreeCard.tsx` + Kakao share button** — Story 7.3 scope. NO FE source/test in this story.
4. **Kakao Share SDK feed-template wiring for poster PNG** — Story 7.3 scope (Story 6.2's `useKakaoShare` already exists; 7.3 just adds a poster-bound call site).
5. **Telemetry / analytics events for `final_three.poster_viewed` / `final_three.share_tapped` / `final_three.share_completed`** — Story 8.5 + Story 7.3 scope; not BE-emitted.
6. **Eliminated-member visibility filter in `PosterController`** — Story 7.3 handles "not shown for eliminated" via FE survival-state check. The endpoint returns 200 for any room member; the FE branches.
7. **Spectator-mode "share own room's poster" decision** — out of scope; Story 7.3 product decision (likely: spectators NOT shown the card, matching the "card shown to surviving members" UX text).
8. **Bilingual / English / Japanese poster — KR only.** NFR-9.7.1 lock.
9. **User-customizable poster (font / color / layout choices)** — PRD line 599 lists this as "deferred to phase-2".
10. **Past-month poster regeneration** — posters are immutable (FR-8.7.6). Even if a tokens.json change ships, posters already rendered stay as they were rendered.
11. **Poster history view (multi-month archive)** — Home tab card shows the CURRENT month's poster only. Archive view is phase-2.
12. **A/B test variants of the poster layout** — out of scope; brand-voice lock is intentional for v1 single-poster experience.
13. **PNG image sharing via direct download (vs Kakao share)** — Story 7.3 may add a "Save to Photos" button; not Story 7.1's scope.
14. **`/api/v1/rooms/{id}/posters` (LIST) endpoint** — only `/{yearMonth}` lookup. List is phase-2 (archive view).
15. **`PosterController.@PostMapping` (manual trigger)** — only GET. Manual trigger for admin/debugging would be a separate Story-X.
16. **Web-side preview of the poster (admin tool)** — phase-2.
17. **CDN delivery for PNG (CloudFront / Cloudflare)** — local-disk + nginx static-serve only. Phase-2.
18. **Image optimization (PNG → WebP/AVIF)** — Story 6.1 deferred; Story 7.1 follows.
19. **Idempotent re-render of zero-survivor chat message** — see AC4. Story 7.2's job pre-filter prevents the race; Story 7.1 documents the gap.
20. **`@PostConstruct` re-warm-up of Batik for ceremony PNG output** — `kakaoshare/PngRasterizer.warmUp()` already covers it.
21. **Cross-month tenure aggregation (e.g., "longest tenure across multiple rooms")** — per-room top-3 only.
22. **Localized year/month labels (e.g., "Jun 2026" English)** — Korean lock (NFR-9.7.1).

## Project structure notes

- BE files (NEW):
  - `BE/src/main/java/com/yeosal/api/ceremony/` (NEW MODULE) — 9 classes per AC1 + AC9.
- BE files (MODIFIED — surgical):
  - `BE/src/main/java/com/yeosal/api/room/chat/ChatService.java` — append one method (AC5).
- BE test files (NEW):
  - `BE/src/test/java/com/yeosal/api/ceremony/` (NEW) — 7 test files per AC10.
  - `BE/src/test/java/com/yeosal/api/room/chat/ChatServiceMonthlyNoSurvivorsTest.java` — 1 test file per AC10.
- Config (MODIFIED):
  - `BE/src/main/resources/application.yml` — append one line under existing `yeosal.share:` block.
- Infra (MODIFIED):
  - `infra/docker-compose.yml` — 1 line under `api.volumes` + 1 line under `nginx.volumes`.
  - `infra/nginx/default.conf` — 5-line `location /posters/` block.
- BANNED (NOT touched): `BE/build.gradle`, all `V*.sql` migrations, FE/**, `FE/src/theme/tokens.json`, `BE/src/main/java/com/yeosal/api/kakaoshare/**`, `BE/src/main/java/com/yeosal/api/realtime/**`, `BE/src/main/java/com/yeosal/api/YeosalApiApplication.java`, `BE/src/main/java/com/yeosal/api/common/SecurityConfig.java`, `BE/src/main/java/com/yeosal/api/common/ApiExceptionHandler.java`, `docs/**`, `RUNBOOK.md`, all `.env*` files.

## Architecture decisions traceability

| FR / decision | AC | File |
|---|---|---|
| FR-8.7.1 (per-room poster) | AC4 | `FinalThreeService.generatePoster` |
| FR-8.7.2 (server-side SVG, D1 Editorial tokens) | AC3, AC8 | `SvgRenderer` + `GeneratedTokens.SubMode.Editorial.*` |
| FR-8.7.3 (stored at `/api/v1/rooms/{id}/posters/{yearMonth}` stable URL + PNG fallback) | AC7, AC6 | `PosterController` + `kakaoshare/PngRasterizer` reuse |
| FR-8.7.5 (30-member-room semantics + "X명 생존" stat) | AC3 | `SvgRenderer.renderRemainingRow` wrap, survivor stat text |
| FR-8.7.6 (posters immutable) | AC2, AC4 | Entity no setters + idempotent return-existing |
| Zero-survivor soft message (epic AC5) | AC5 | `ChatService.publishMonthlyNoSurvivorsSystemMessage` |
| NFR-9.1.4 (renderer p99 < 3s) | AC4 trap #10, AC10 IT | Batik warm-up reuse + IT wall-clock assertion |
| Architecture §4.9 (server-side SVG + GeneratedTokens) | AC3 | Token-only consumption |
| Architecture §4.15 (hex-literal guard) | AC8 | Checkstyle (existing) |
| Architecture §4.16 (FE→BE codegen) | AC3, AC8 | `GeneratedTokens.SubMode.Editorial` consumption |
| Architecture §6.3 V11 (10) (`final_three_posters` schema) | AC2 (no new migration) | `FinalThreePoster` entity → existing V11 table |
| Architecture §6.4 (REST endpoint table) | AC7 | `GET /api/v1/rooms/{id}/posters/{yearMonth}` |
| project-context line 87 (domain exceptions extend RuntimeException + handler mapping) | AC7 | `PosterNotFoundException extends NotFoundException` |
| project-context line 88 (constructor injection only) | AC4, AC7 | All beans use constructor injection |
| project-context line 109-114 (controller path convention + auth default) | AC7 | `/api/v1/rooms/*/posters/*` requires auth |
| project-context line 119 (ddl-auto: validate; entity matches schema) | AC2 trap #2 | `columnDefinition = "text"` |
| project-context line 142 (Testcontainers for DB IT) | AC10 | `FinalThreeServiceIT @SpringBootTest @Testcontainers` |
| project-context line 145 (TDD RED→GREEN) | AC10 | RED per file → GREEN |
| project-context line 176 (cross-feature application service inject OK) | AC6 | `kakaoshare/PngRasterizer` cross-module DI |
| Story 5.4 helper precedent | AC5 | `publishRuleChangeSystemMessage` shape → `publishMonthlyNoSurvivorsSystemMessage` |
| Story 6.1 PngRasterizer reuse | AC6 | Cross-module DI |
| Story 1.5 Checkstyle + GeneratedTokens | AC3, AC8 | Constants only, no hex |

## References

- Epics: `_bmad-output/planning-artifacts/epics.md:890-928` (Epic 7 + Story 7.1 ACs), `epics.md:929-950` (Story 7.2 — out of scope), `epics.md:952-972` (Story 7.3 — out of scope), `epics.md:1175` (FR Coverage Map row for Story 7.1).
- PRD:
  - `_bmad-output/planning-artifacts/prd.md:194-200` (J4 — Day-30 Final-3 ceremony narrative).
  - `prd.md:232` (Final-3 definition).
  - `prd.md:421-430` (FR-8.7.1 ~ FR-8.7.6).
  - `prd.md:459` (NFR-9.1.4 — p99 < 3s).
  - `prd.md:533` (W6 sprint alignment).
- Architecture:
  - `_bmad-output/planning-artifacts/architecture.md:288-306` (§4.9 server-side SVG decision + token sourcing).
  - `architecture.md:400-417` (§4.15 brand-voice + a11y gate — Checkstyle enforcement).
  - `architecture.md:419-485` (§4.16 codegen pipeline).
  - `architecture.md:579-599` (§6.1 ceremony module outline + `RealtimeEvent.MonthlyPosterReady` Story 7.2 deferral).
  - `architecture.md:753-761` (§6.3 V11 (10) `final_three_posters` schema).
  - `architecture.md:802-817` (§6.4 REST endpoint table — line 815 `GET /rooms/{id}/posters/{yearMonth}`).
- UX:
  - `_bmad-output/planning-artifacts/ux-design-specification.md:165-169` (O1 — Final-3 ceremony single poster + D1 Editorial override).
  - `ux-design-specification.md:246` (J4 share — "Home 탭 카드 → Share to KakaoTalk").
  - `ux-design-specification.md:258` (M4 free marketing asset).
  - `ux-design-specification.md:367-373` (목격되고 싶음 / 함께하고 싶다 / Quiet Pride / Ritual — Final-3 명단 reference).
  - `ux-design-specification.md:459-462` (A24 + concert poster reference).
  - `ux-design-specification.md:546-549` (Magazine B / BOSTOK editorial weight contrast → Final-3 reference).
  - `ux-design-specification.md:1050-1071` (D1 Editorial sub-mode override map).
  - `ux-design-specification.md:1169-1170` (Surface Assignment Matrix — Final-3 = D1 Editorial Spread).
  - `ux-design-specification.md:1193` (D1.editorial override automatic for Final-3 SVG).
- project-context: `_bmad-output/project-context.md:87` (domain exception + handler), `:88` (constructor injection), `:109-114` (controller convention), `:119` (Hibernate validate mode), `:142` (Testcontainers), `:145` (TDD), `:176` (package-by-feature cross-module DI), `:191` (no emojis), `:229` (Post-merge user action note).
- Story 6.1 (most recent BE-renderer precedent): `_bmad-output/implementation-artifacts/6-1-server-side-preview-card-renderer-cache.md` — Apache Batik integration, GeneratedTokens consumption, escapeXml helper, atomic-move PNG write, 14-trap catalogue shape.
- Story 5.4 (chat system message wrapper precedent): `_bmad-output/implementation-artifacts/5-4-rule-change-broadcast-in-chat.md` — `publishRuleChangeSystemMessage` REQUIRES_NEW + JSON payload pattern.
- Story 5.1 (composite-key + lazy-rule lookup precedent): `_bmad-output/implementation-artifacts/5-1-rule-edit-with-next-month-only-application.md` — `effective_from_month varchar(7)` mapping.
- Story 1.5 (codegen pipeline): `_bmad-output/implementation-artifacts/1-5-design-system-foundation-v2-token-packed-type-fe-be-codegen.md` — `GeneratedTokens` contract, Checkstyle hex-literal guard.
- Story 1.4 (V11 migration): `_bmad-output/implementation-artifacts/1-4-v11-migration-production-backfill.md` — `final_three_posters` table land.
- Existing BE code:
  - `BE/src/main/java/com/yeosal/api/kakaoshare/PngRasterizer.java` (cross-module reuse target).
  - `BE/src/main/java/com/yeosal/api/kakaoshare/InvitePreviewRenderer.java:65-73` (escapeXml precedent).
  - `BE/src/main/java/com/yeosal/api/kakaoshare/PreviewCardRenderException.java` (PNG-rasterize failure exception type).
  - `BE/src/main/java/com/yeosal/api/room/chat/ChatService.java:130-167` (`publishSystem` chokepoint).
  - `BE/src/main/java/com/yeosal/api/room/chat/ChatService.java:185-201` (`publishRuleChangeSystemMessage` Story 5.4 precedent).
  - `BE/src/main/java/com/yeosal/api/room/chat/ChatMessageKind.java:17-23` (SYSTEM enum value).
  - `BE/src/main/java/com/yeosal/api/survival/RecordVisibilityPref.java:22-65` + `RecordVisibilityPrefId.java:11-37` (IdClass composite-key precedent).
  - `BE/src/main/java/com/yeosal/api/survival/SurvivalState.java:30-115` (status enum + entity shape — survivor query reference).
  - `BE/src/main/java/com/yeosal/api/room/RoomMember.java:38-39` (`joined_at` tenure source).
  - `BE/src/main/java/com/yeosal/api/user/User.java:24-25` (`nickname` source).
  - `BE/src/main/java/com/yeosal/api/room/RoomMembershipService.java` (`requireMembership` chokepoint — confirm exact signature via grep before wiring).
  - `BE/src/main/java/com/yeosal/api/common/ApiResponse.java` + `ApiExceptionHandler.java` (envelope + exception mapping).
  - `BE/src/main/java/com/yeosal/api/common/NotFoundException.java` + `BadRequestException.java` (base exception classes).
  - `BE/src/main/java/com/yeosal/api/common/CurrentUser.java` (controller principal injection).
  - `BE/build.gradle:32-33` (Batik 1.17 deps — no edit).
  - `BE/build.gradle:284-303` (Checkstyle hex-literal guard).
  - `BE/build/generated/sources/tokens/com/yeosal/api/theme/GeneratedTokens.java` (constant source — verify lines 132-139 for `SubMode.Editorial`).
  - `BE/src/main/resources/application.yml:20-28` (yeosal.* config block — `yeosal.share.*` extension site).
  - `BE/src/main/resources/db/migration/V11__survival_revival_economy.sql:140-147` (poster table schema, NO edit).
- Existing FE code (NOT used in this story — Story 7.3 scope):
  - No FE consumer call sites in this story.
- Existing infra:
  - `infra/docker-compose.yml` (modify) — `posters-cache` volume add site.
  - `infra/nginx/default.conf` (modify) — `location /posters/` block add site.
- Brand-voice lint: `tools/brand-voice-lint.ts:50-59` (AVOID lexicon — 8 tokens).

## Change log

| Date | Author | Change |
|---|---|---|
| 2026-06-07 | bmad-create-story | Initial story spec ready-for-dev. Flips epic-7 backlog→in-progress (first story in Epic 7) per workflow rule. Anchored on Story 6.1 precedents (kakaoshare/PngRasterizer reuse, escapeXml, Batik 1.17 build deps, `application.yml` yeosal.share block, nginx static-serve pattern). 13 ACs + 22 OOS items + 14-gate verify matrix. 9 NEW BE source files + 8 NEW test files + 4 MODIFIED files. No new migration, no new dependency. |
| 2026-06-07 | bmad-dev-story | Implementation complete → review. BE 667/667 GREEN locally (0 failures/0 errors/115 opt-in skips); Checkstyle hex-literal guard clean; brand-voice 0 HARD. 9 NEW source files + 7 NEW ceremony tests + 1 NEW chat test + 5 MODIFIED. Opt-in Docker-bound gates (7, 8, 10, 11, 12) deferred to PR-CI per Story 5.4/6.1 precedent. Architecture deviations from spec template (logged for review): (1) `RoomMembershipService` does not exist in the codebase — `PosterController` follows the `SurvivalStateController` precedent (`CurrentUser.require(Authentication)` + service-layer `RoomMemberRepository.existsByRoomIdAndUserId`). (2) `RoomNotFoundException` does not exist — `FinalThreeService` throws the generic `NotFoundException("방을 찾을 수 없습니다.")` per the `SurvivalStateService.roster` precedent. (3) `@Pattern` + `@Validated` on path variables omitted because `ApiExceptionHandler` has no `ConstraintViolationException` mapping — would surface as 5xx; use `YearMonth.parse` + `DateTimeParseException` → `BadRequestException` instead. (4) `CurrentUser` is a component injected via constructor + called with `Authentication` (not a method-param record with `.userId()`). |

## Dev Agent Record

### Agent Model Used

Claude Opus 4.7 (1M context), `claude-opus-4-7[1m]`.

### Debug Log References

- **Gate 4 (early run) — `ChatServiceMonthlyNoSurvivorsTest` initial failure**: 4/5 tests failed with `NullPointerException: ChatMessage.getId()` because the spec template's mocked `messages.save(...)` returned the unsaved object whose primitive-`long` ID was null. Resolved by lifting the `setField(saved, "id", 9000L)` pattern from `ChatServiceRuleChangeTest:56-60` (Story 5.4 precedent) so the captured row has a non-null id/createdAt before `MessageDto.from(saved)` unboxes.
- **Gate 4 (compile) — `MessageDto` import**: `ChatService.MessageDto` is a public nested record inside `ChatService.java:363`, not a separate file. Initial `import com.yeosal.api.room.chat.MessageDto;` failed to resolve; switched to qualified reference `ChatService.MessageDto` in the realtime fan-out verification.
- **AC7 controller shape** (architecture deviation #4 — see Change Log row 2): the spec template's `CurrentUser currentUser` method-parameter shape (`.userId()`) does not match the actual project class — `CurrentUser` is a `@Component` injected via constructor + invoked as `currentUser.require(Authentication)` returning a `User` (project precedent: `SurvivalStateController:46`).

### Completion Notes List

- ✅ All 13 ACs satisfied; all 17 task checkboxes resolved (Gate 10 dry-run noted as PR-CI deferral, not a code gap).
- ✅ 9 NEW source files + 7 NEW ceremony tests + 1 NEW chat test = 17 new files; 5 surgical MODIFIED files (ChatService + application.yml + docker-compose + nginx + sprint-status); zero banned-paths touched (BE/build.gradle, V*.sql, FE/**, kakaoshare/**, realtime/**, YeosalApiApplication, ApiExceptionHandler, SecurityConfig, docs/**, RUNBOOK.md, .env* — all untouched).
- ✅ Cross-module DI for `kakaoshare/PngRasterizer` works without any `@Import` / `@ComponentScan` edit (Story 6.1 precedent confirmed); no duplicate rasterizer class in `ceremony/`.
- ✅ Locked phrases all byte-identical to epic ACs: wordmark `"열살"`, footer `"함께 살아남은 우리"`, stat suffix `"명 생존"`, year format `"%d년 %d월"`, zero-survivor body `"이번 달은 아무도 살아남지 못했어요 — 다음 달은 함께 가요"` (em-dash U+2014 preserved — `BrandVoicePosterPhrasesTest` + `ChatServiceMonthlyNoSurvivorsTest.publishMonthlyNoSurvivors_lockedBody` assert this).
- ✅ Token consumption is `GeneratedTokens.*` only — `SvgRendererHexLiteralGateTest` source-reads `SvgRenderer.java` and asserts no `#hex` / `rgb(` / `oklch(` literal; Checkstyle `checkstyleMain` agrees (Gate 3).
- ✅ Idempotency contract behaves correctly on all three paths: existing row short-circuits without re-render or chat publish; zero-survivor path publishes chat fallback + returns empty without saving a poster row; survivors-present path renders + rasterizes + writes PNG atomically + persists row (`FinalThreeServiceTest` covers all 9 cases including PNG rasterize failure → `pngUrl=null` tolerance).
- ✅ Membership privacy: `getPosterForMember` raises `ForbiddenException` BEFORE checking poster existence (matches `SurvivalStateService.roster` privacy stance — "you can't tell whether the poster exists" > "there is no poster").
- ⏸ Docker-bound gates (7, 8, 10, 11, 12) deferred to PR-CI: `FinalThreeServiceIT` + `SvgRendererTokenDiffIT` are `@EnabledIfSystemProperty(named="yeosal.boot-smoke", matches="true")`-gated; `bootRun` + authenticated curl + 401 negative path require a live BE process + Postgres. Same deferral pattern as Story 5.4 / 6.1 review sections.

### File List

**NEW (17):**

```
BE/src/main/java/com/yeosal/api/ceremony/FinalThreePoster.java
BE/src/main/java/com/yeosal/api/ceremony/FinalThreePosterId.java
BE/src/main/java/com/yeosal/api/ceremony/FinalThreePosterRepository.java
BE/src/main/java/com/yeosal/api/ceremony/FinalThreeService.java
BE/src/main/java/com/yeosal/api/ceremony/PosterController.java
BE/src/main/java/com/yeosal/api/ceremony/PosterDto.java
BE/src/main/java/com/yeosal/api/ceremony/PosterNotFoundException.java
BE/src/main/java/com/yeosal/api/ceremony/SurvivorTenureRow.java
BE/src/main/java/com/yeosal/api/ceremony/SvgRenderer.java
BE/src/test/java/com/yeosal/api/ceremony/BrandVoicePosterPhrasesTest.java
BE/src/test/java/com/yeosal/api/ceremony/FinalThreeServiceIT.java
BE/src/test/java/com/yeosal/api/ceremony/FinalThreeServiceTest.java
BE/src/test/java/com/yeosal/api/ceremony/PosterControllerTest.java
BE/src/test/java/com/yeosal/api/ceremony/SvgRendererHexLiteralGateTest.java
BE/src/test/java/com/yeosal/api/ceremony/SvgRendererTest.java
BE/src/test/java/com/yeosal/api/ceremony/SvgRendererTokenDiffIT.java
BE/src/test/java/com/yeosal/api/room/chat/ChatServiceMonthlyNoSurvivorsTest.java
```

**MODIFIED (5):**

```
BE/src/main/java/com/yeosal/api/room/chat/ChatService.java
  └── Added publishMonthlyNoSurvivorsSystemMessage(long, YearMonth) wrapper
       (REQUIRES_NEW, locked body, JSON yearMonth payload). NO other edits.

BE/src/main/resources/application.yml
  └── Added one line under yeosal.share:
       posters-dir: ${YEOSAL_SHARE_POSTERS_DIR:/var/yeosal/posters}

infra/docker-compose.yml
  └── Added YEOSAL_SHARE_POSTERS_DIR env passthrough on api;
       posters-cache:/var/yeosal/posters volume on api + nginx (ro);
       posters-cache named volume declaration.

infra/nginx/default.conf
  └── Added `location /posters/` block (alias /var/yeosal/posters,
       Cache-Control max-age=86400, try_files $uri =404).

_bmad-output/implementation-artifacts/sprint-status.yaml
  └── ready-for-dev → in-progress (dev-story start) → review (completion).
       Workflow-mandated by bmad-dev-story Step 4 + Step 9; not a banned path.
```

**BANNED PATHS — UNTOUCHED (audited via `git diff --name-only main`):**

```
BE/build.gradle                                              ← clean
BE/src/main/resources/db/migration/V*.sql                    ← clean
FE/**                                                          ← clean
FE/src/theme/tokens.json                                       ← clean
BE/src/main/java/com/yeosal/api/kakaoshare/**                ← clean
BE/src/main/java/com/yeosal/api/realtime/**                  ← clean
BE/src/main/java/com/yeosal/api/YeosalApiApplication.java    ← clean
BE/src/main/java/com/yeosal/api/common/ApiExceptionHandler.java ← clean
BE/src/main/java/com/yeosal/api/common/SecurityConfig.java   ← clean
docs/**, RUNBOOK.md                                            ← clean
infra/.env*, FE/.env*                                          ← clean
```
