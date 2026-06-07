package com.yeosal.api.ceremony;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.yeosal.api.room.Room;
import com.yeosal.api.theme.GeneratedTokens;
import com.yeosal.api.user.AuthProvider;
import com.yeosal.api.user.User;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SvgRendererTest {

    private final SvgRenderer renderer = new SvgRenderer();

    @Test
    @DisplayName("render — three survivors produces top-3 row + no remaining block")
    void render_threeSurvivors_topThreeOnly() {
        String svg = renderer.render(
                room("우리 방"),
                YearMonth.of(2026, 6),
                List.of(
                        survivor("alice", 10L, Instant.parse("2026-01-01T00:00:00Z")),
                        survivor("bob",   11L, Instant.parse("2026-01-02T00:00:00Z")),
                        survivor("carol", 12L, Instant.parse("2026-01-03T00:00:00Z"))),
                3);

        assertThat(svg).contains("alice");
        assertThat(svg).contains("bob");
        assertThat(svg).contains("carol");
        // Remaining block emits an additional <text x="48" y="260"...> row;
        // confirm the y=260 anchor is absent (only the top-3 row is rendered).
        assertThat(svg).doesNotContain("y=\"260\"");
        assertThat(svg).contains("3" + SvgRenderer.SURVIVOR_STAT_SUFFIX);
    }

    @Test
    @DisplayName("render — five survivors emits remaining block with middle-dot separator")
    void render_fiveSurvivors_remainingBlock() {
        String svg = renderer.render(
                room("우리 방"),
                YearMonth.of(2026, 6),
                List.of(
                        survivor("alice", 10L, Instant.parse("2026-01-01T00:00:00Z")),
                        survivor("bob",   11L, Instant.parse("2026-01-02T00:00:00Z")),
                        survivor("carol", 12L, Instant.parse("2026-01-03T00:00:00Z")),
                        survivor("dave",  13L, Instant.parse("2026-01-04T00:00:00Z")),
                        survivor("eve",   14L, Instant.parse("2026-01-05T00:00:00Z"))),
                5);

        assertThat(svg).contains("dave" + SvgRenderer.NAME_SEPARATOR + "eve");
        assertThat(svg).contains("y=\"260\"");
        assertThat(svg).contains("5" + SvgRenderer.SURVIVOR_STAT_SUFFIX);
    }

    @Test
    @DisplayName("render — one survivor emits only that name in top row")
    void render_oneSurvivor_singleSpan() {
        String svg = renderer.render(
                room("작은 방"),
                YearMonth.of(2026, 6),
                List.of(survivor("alice", 10L, Instant.parse("2026-01-01T00:00:00Z"))),
                1);

        assertThat(svg).contains("alice");
        assertThat(svg).contains("1" + SvgRenderer.SURVIVOR_STAT_SUFFIX);
    }

    @Test
    @DisplayName("render — two survivors emits both names in top row")
    void render_twoSurvivors_twoSpans() {
        String svg = renderer.render(
                room("작은 방"),
                YearMonth.of(2026, 6),
                List.of(
                        survivor("alice", 10L, Instant.parse("2026-01-01T00:00:00Z")),
                        survivor("bob",   11L, Instant.parse("2026-01-02T00:00:00Z"))),
                2);

        assertThat(svg).contains("alice");
        assertThat(svg).contains("bob");
        assertThat(svg).contains("2" + SvgRenderer.SURVIVOR_STAT_SUFFIX);
        assertThat(svg).doesNotContain("y=\"260\"");
    }

    @Test
    @DisplayName("render — twenty-five survivors fits within the 4-line budget")
    void render_twentyFive_wrapsWithinBudget() {
        List<SurvivorTenureRow> survivors = IntStream.range(0, 25)
                .mapToObj(i -> survivor(
                        "u%d".formatted(i),
                        100L + i,
                        Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i)))
                .toList();

        String svg = renderer.render(room("큰 방"), YearMonth.of(2026, 6), survivors, 25);

        assertThat(svg).contains("25" + SvgRenderer.SURVIVOR_STAT_SUFFIX);
        // First three names live in the top row; the rest spread across <= 4
        // remaining lines at y=260, 284, 308, 332.
        assertThat(svg).contains("y=\"260\"");
        // Remaining anchor of the 5th line would be y="356"; the budget caps at 4.
        assertThat(svg).doesNotContain("y=\"356\"");
    }

    @Test
    @DisplayName("render — thirty survivors collapses overflow into 외 N명 marker")
    void render_thirty_overflowMarker() {
        List<SurvivorTenureRow> survivors = IntStream.range(0, 30)
                .mapToObj(i -> survivor(
                        "longishname%02d".formatted(i),
                        100L + i,
                        Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i)))
                .toList();

        String svg = renderer.render(room("큰 방"), YearMonth.of(2026, 6), survivors, 30);

        assertThat(svg).contains("외 ");
        assertThat(svg).contains("명");
        assertThat(svg).contains("30" + SvgRenderer.SURVIVOR_STAT_SUFFIX);
    }

    @Test
    @DisplayName("render — year label uses Korean format with no leading zero")
    void render_yearLabel_koreanFormat() {
        String svg = renderer.render(
                room("우리 방"),
                YearMonth.of(2026, 3),
                List.of(survivor("alice", 10L, Instant.parse("2026-01-01T00:00:00Z"))),
                1);

        assertThat(svg).contains("2026년 3월");
        assertThat(svg).doesNotContain("2026년 03월");
    }

    @Test
    @DisplayName("render — locked phrases present (wordmark, footer, survivor suffix)")
    void render_lockedPhrases_present() {
        String svg = renderer.render(
                room("우리 방"),
                YearMonth.of(2026, 6),
                List.of(survivor("alice", 10L, Instant.parse("2026-01-01T00:00:00Z"))),
                1);

        assertThat(svg).contains(SvgRenderer.WORDMARK);
        assertThat(svg).contains(SvgRenderer.FOOTER);
        assertThat(svg).contains(SvgRenderer.SURVIVOR_STAT_SUFFIX);
    }

    @Test
    @DisplayName("render — room name with XML metacharacters is escaped")
    void render_escapeXml_roomName() {
        String svg = renderer.render(
                room("A & B <strong>"),
                YearMonth.of(2026, 6),
                List.of(survivor("alice", 10L, Instant.parse("2026-01-01T00:00:00Z"))),
                1);

        assertThat(svg).contains("A &amp; B &lt;strong&gt;");
        assertThat(svg).doesNotContain("<strong>");
    }

    @Test
    @DisplayName("render — token constants appear (proves indirection through GeneratedTokens)")
    void render_consumesGeneratedTokensOnly() {
        String svg = renderer.render(
                room("우리 방"),
                YearMonth.of(2026, 6),
                List.of(survivor("alice", 10L, Instant.parse("2026-01-01T00:00:00Z"))),
                1);

        assertThat(svg).contains(GeneratedTokens.COLOR_BG_CANVAS);
        assertThat(svg).contains(GeneratedTokens.COLOR_KEY_DEFAULT);
        assertThat(svg).contains(GeneratedTokens.COLOR_KEY_LINE);
        assertThat(svg).contains(GeneratedTokens.COLOR_TEXT_SECONDARY);
        assertThat(svg).contains(GeneratedTokens.COLOR_TEXT_PRIMARY);
        assertThat(svg).contains(GeneratedTokens.COLOR_TEXT_TERTIARY);
        assertThat(svg).contains(GeneratedTokens.SubMode.Editorial.TYPOGRAPHY_HEADING_TRACKING);
    }

    @Test
    @DisplayName("render — zero survivors is an invariant violation, not a soft path")
    void render_zeroSurvivors_throws() {
        assertThatThrownBy(() -> renderer.render(
                room("빈 방"), YearMonth.of(2026, 6), List.of(), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("zero survivors");
    }

    @Test
    @DisplayName("wrapNames — overflow marker accounts for remaining count")
    void wrapNames_overflow_correctRemainingCount() {
        List<String> names = List.of("aaaaaaaaaa", "bbbbbbbbbb", "cccccccccc", "dddddddddd",
                "eeeeeeeeee", "ffffffffff", "gggggggggg", "hhhhhhhhhh", "iiiiiiiiii", "jjjjjjjjjj");

        List<String> lines = SvgRenderer.wrapNames(names, /* maxChars = */ 25,
                /* maxLines = */ 2, SvgRenderer.NAME_SEPARATOR);

        assertThat(lines).hasSize(2);
        // Last line must include the 외 marker because the budget was exceeded.
        assertThat(lines.get(1)).contains("외 ");
        assertThat(lines.get(1)).contains("명");
    }

    @Test
    @DisplayName("wrapNames — over-budget first name does not emit a blank line")
    void wrapNames_firstNameOverBudget_noBlankLine() {
        List<String> lines = SvgRenderer.wrapNames(
                List.of("a".repeat(80), "bob"),
                /* maxChars = */ 70,
                /* maxLines = */ 4,
                SvgRenderer.NAME_SEPARATOR);

        assertThat(lines).isNotEmpty();
        assertThat(lines).doesNotContain("");
        assertThat(lines.get(0)).isEqualTo("a".repeat(80));
    }

    private static Room room(String name) {
        User owner = new User("dev@example.com", "owner", null, AuthProvider.EMAIL);
        return new Room(name, owner);
    }

    private static SurvivorTenureRow survivor(String nickname, long userId, Instant joinedAt) {
        return new SurvivorTenureRow(nickname, userId, joinedAt);
    }
}
