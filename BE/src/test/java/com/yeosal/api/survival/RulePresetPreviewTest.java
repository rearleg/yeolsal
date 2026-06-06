package com.yeosal.api.survival;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Story 6.1 AC8 — covers the cross-callsite formatter shared by
 * {@code ChatService.publishRuleChangeSystemMessage} (Story 5.4) and
 * {@code InvitePreviewRenderer} (Story 6.1). The format must remain
 * byte-identical to Story 5.4's locked phrases.
 */
class RulePresetPreviewTest {

    @Test
    @DisplayName("DAILY_UPDATE + weekendInclude=true → \"매일 업데이트, 주말 포함\"")
    void dailyUpdate_weekendInclude_emitsLockedPhrase() {
        assertThat(RulePresetPreview.format("DAILY_UPDATE", true))
                .isEqualTo("매일 업데이트, 주말 포함");
    }

    @Test
    @DisplayName("DAILY_UPDATE + weekendInclude=false → \"매일 업데이트, 주말 제외\"")
    void dailyUpdate_weekendExclude_flipsTail() {
        assertThat(RulePresetPreview.format("DAILY_UPDATE", false))
                .isEqualTo("매일 업데이트, 주말 제외");
    }

    @Test
    @DisplayName("unknown preset forwards the raw token so missed extensions surface visibly")
    void unknownPreset_isForwardedVerbatim() {
        assertThat(RulePresetPreview.format("WEEKLY_DIGEST", true))
                .isEqualTo("WEEKLY_DIGEST, 주말 포함");
        assertThat(RulePresetPreview.format("WEEKLY_DIGEST", false))
                .isEqualTo("WEEKLY_DIGEST, 주말 제외");
    }
}
