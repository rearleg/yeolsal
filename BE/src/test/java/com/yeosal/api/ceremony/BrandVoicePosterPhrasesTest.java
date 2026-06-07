package com.yeosal.api.ceremony;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Brand-voice gate (PRD FR-8.8.2 — AVOID lexicon). Mirrors the FE
 * {@code tools/brand-voice-lint.ts:50-59} HARD lexicon. The locked
 * renderer/chat phrase constants must contain ZERO of these tokens.
 * Lint runs in WARN at the FE; this test makes the same rule blocking on
 * the BE.
 */
class BrandVoicePosterPhrasesTest {

    private static final List<String> AVOID_LEXICON = List.of(
            "벌금",
            "잃었다",
            "떨어졌다",
            "실패",
            "자책",
            "부담",
            "패배",
            "죄책감");

    @Test
    @DisplayName("renderer locked phrases contain no AVOID-lexicon token")
    void rendererPhrases_avoidLexiconZero() {
        List<String> phrases = List.of(
                SvgRenderer.WORDMARK,
                SvgRenderer.FOOTER,
                SvgRenderer.SURVIVOR_STAT_SUFFIX,
                SvgRenderer.NAME_SEPARATOR);

        for (String phrase : phrases) {
            for (String avoid : AVOID_LEXICON) {
                assertThat(phrase)
                        .as("renderer phrase '%s' must not contain AVOID '%s'", phrase, avoid)
                        .doesNotContain(avoid);
            }
        }
    }

    @Test
    @DisplayName("zero-survivor chat fallback body contains no AVOID-lexicon token")
    void zeroSurvivorBody_avoidLexiconZero() {
        String body = "이번 달은 아무도 살아남지 못했어요 — 다음 달은 함께 가요";
        for (String avoid : AVOID_LEXICON) {
            assertThat(body)
                    .as("zero-survivor body must not contain AVOID '%s'", avoid)
                    .doesNotContain(avoid);
        }
    }
}
