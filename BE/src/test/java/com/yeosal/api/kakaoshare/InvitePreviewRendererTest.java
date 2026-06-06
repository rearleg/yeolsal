package com.yeosal.api.kakaoshare;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.yeosal.api.room.Room;
import com.yeosal.api.theme.GeneratedTokens;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Story 6.1 AC5/AC10 — unit-level guarantees on the SVG renderer's output.
 * The brand-voice locked phrases ("열살", "함께", "같이 살아남자",
 * "살아남는 중") and the AVOID lexicon ("벌금", "잃었다", "떨어졌다",
 * "실패", "자책", "부담", "패배", "죄책감") are checked literally so a copy
 * edit that drifts away from the lock fails the unit suite immediately.
 */
class InvitePreviewRendererTest {

    private static final String[] AVOID_LEXICON = {
            "벌금", "잃었다", "떨어졌다", "실패", "자책", "부담", "패배", "죄책감"
    };

    private final InvitePreviewRenderer renderer = new InvitePreviewRenderer();

    private Room roomNamed(String name) {
        Room room = mock(Room.class);
        when(room.getName()).thenReturn(name);
        return room;
    }

    @Test
    @DisplayName("output is a well-formed SVG document with the 800x420 viewport from AC5")
    void output_isWellFormedSvg() {
        String svg = renderer.render(roomNamed("기본 방"), 5, "DAILY_UPDATE", true);

        assertThat(svg).startsWith("<?xml version=\"1.0\"");
        assertThat(svg).contains("<svg xmlns=\"http://www.w3.org/2000/svg\"");
        assertThat(svg).contains("width=\"800\"");
        assertThat(svg).contains("height=\"420\"");
        assertThat(svg).contains("viewBox=\"0 0 800 420\"");
        assertThat(svg).endsWith("</svg>\n");
    }

    @Test
    @DisplayName("D1 Editorial sub-mode constants are present in the SVG output")
    void output_referencesEditorialSubModeConstants() {
        String svg = renderer.render(roomNamed("열살 방"), 12, "DAILY_UPDATE", false);

        assertThat(svg).contains("font-weight=\""
                + GeneratedTokens.SubMode.Editorial.TYPOGRAPHY_HEADING_WEIGHT + "\"");
        assertThat(svg).contains("letter-spacing=\""
                + GeneratedTokens.SubMode.Editorial.TYPOGRAPHY_HEADING_TRACKING + "\"");
    }

    @Test
    @DisplayName("base GeneratedTokens colour constants reach the fill attributes (no raw hex)")
    void output_consumesGeneratedTokenColours() {
        String svg = renderer.render(roomNamed("방"), 1, "DAILY_UPDATE", true);

        assertThat(svg).contains("fill=\"" + GeneratedTokens.COLOR_BG_CANVAS + "\"");
        assertThat(svg).contains("fill=\"" + GeneratedTokens.COLOR_TEXT_PRIMARY + "\"");
        assertThat(svg).contains("fill=\"" + GeneratedTokens.COLOR_KEY_DEFAULT + "\"");
        assertThat(svg).contains("fill=\"" + GeneratedTokens.COLOR_TEXT_SECONDARY + "\"");
    }

    @Test
    @DisplayName("XML-special characters in the room name are escaped (no unescaped <, >, &)")
    void roomName_xmlEscaped() {
        String svg = renderer.render(roomNamed("<script>alert(\"hi\")</script>&amp"),
                3, "DAILY_UPDATE", false);

        assertThat(svg).doesNotContain("<script>");
        assertThat(svg).contains("&lt;script&gt;");
        assertThat(svg).contains("&quot;hi&quot;");
        assertThat(svg).contains("&amp;amp");
    }

    @Test
    @DisplayName("output contains no AVOID lexicon phrases (PRD FR-8.8.2 / brand-voice lint)")
    void output_avoidsBannedLexicon() {
        String svg = renderer.render(roomNamed("기본"), 7, "DAILY_UPDATE", true);

        for (String banned : AVOID_LEXICON) {
            assertThat(svg).as("banned phrase: " + banned).doesNotContain(banned);
        }
    }

    @Test
    @DisplayName("output preserves brand-voice locked phrases (열살, 함께, 살아남, 같이 살아남자)")
    void output_containsLockedBrandPhrases() {
        String svg = renderer.render(roomNamed("방"), 5, "DAILY_UPDATE", true);

        assertThat(svg).contains("열살");
        assertThat(svg).contains("5명이 함께 살아남는 중");
        assertThat(svg).contains("같이 살아남자");
    }
}
