package com.yeosal.api.survival;

/**
 * Rule preset display formatter. Single source of truth for the human-readable
 * preview phrase shared by:
 * <ul>
 *   <li>Story 5.4 — chat broadcast body suffix
 *       ({@code ChatService.publishRuleChangeSystemMessage}).</li>
 *   <li>Story 6.1 — Kakao invite preview card SVG
 *       ({@code InvitePreviewRenderer}).</li>
 * </ul>
 *
 * <p>Adding a third preset MUST extend both the {@link RulePresetEvaluator}
 * switch and this formatter atomically. An unknown preset is forwarded as the
 * raw enum string so a missed extension surfaces loudly in chat and the SVG
 * instead of silently rendering a blank label.
 */
public final class RulePresetPreview {

    private RulePresetPreview() {}

    public static String format(String preset, boolean weekendInclude) {
        String presetLabel = "DAILY_UPDATE".equals(preset) ? "매일 업데이트" : preset;
        String weekendPhrase = weekendInclude ? "주말 포함" : "주말 제외";
        return presetLabel + ", " + weekendPhrase;
    }
}
