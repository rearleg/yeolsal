package com.yeosal.api.ceremony;

import com.yeosal.api.room.Room;
import com.yeosal.api.theme.GeneratedTokens;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * D1 Editorial sub-mode poster renderer for Story 7.1. Produces a
 * self-contained 800 × 420 SVG document with the top-3 by-tenure survivors
 * highlighted and the remaining survivors line-wrapped beneath. Token
 * consumption flows exclusively through {@link GeneratedTokens} +
 * {@link GeneratedTokens.SubMode.Editorial} constants — the Checkstyle
 * hex-literal guard at {@code BE/build.gradle:284-303} blocks any direct
 * hex string from compiling.
 *
 * <p>The signature is pure-function: callers pre-fetch the
 * {@link SurvivorTenureRow} list so this renderer has no clock, no
 * repository, and no I/O. {@link FinalThreeService} owns the orchestration
 * with the {@code (roomId, yearMonth)} entry point.
 */
@Component
public class SvgRenderer {

    static final String WORDMARK = "열살";
    static final String FOOTER = "함께 살아남은 우리";
    static final String SURVIVOR_STAT_SUFFIX = "명 생존";
    static final String NAME_SEPARATOR = " · ";

    /** Column pitch for top-3 highlight row; 3 columns across 720px content area. */
    private static final int TOP_THREE_X_PITCH = 240;
    /** Greedy line-wrap budget for the remaining-survivors block. */
    private static final int REMAINING_MAX_CHARS = 70;
    private static final int REMAINING_MAX_LINES = 4;
    /** Vertical anchor for the remaining-survivors block start. */
    private static final int REMAINING_Y_START = 260;
    private static final int REMAINING_LINE_HEIGHT = 24;

    public String render(
            Room room,
            YearMonth yearMonth,
            List<SurvivorTenureRow> allSurvivors,
            int totalSurvivorCount) {

        if (totalSurvivorCount < 1 || allSurvivors == null || allSurvivors.isEmpty()) {
            // FinalThreeService is the eligibility gate; reaching the renderer
            // with zero survivors is an invariant violation, not a soft path.
            throw new IllegalArgumentException(
                    "SvgRenderer invoked with zero survivors; FinalThreeService"
                            + " must short-circuit via zero-survivor chat fallback (AC5).");
        }

        String roomName = escapeXml(room.getName());
        String yearLabel = "%d년 %d월".formatted(
                yearMonth.getYear(), yearMonth.getMonthValue());
        String topThreeBlock = renderTopThreeRow(allSurvivors);
        String remainingBlock = renderRemainingRow(allSurvivors);
        String survivorStat = totalSurvivorCount + SURVIVOR_STAT_SUFFIX;

        return String.format(
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n"
              + "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"800\" height=\"420\""
              + " viewBox=\"0 0 800 420\">\n"
              + "  <rect width=\"100%%\" height=\"100%%\" fill=\"%s\"/>\n"
              + "  <text x=\"48\" y=\"48\" font-family=\"-apple-system, sans-serif\""
              + " font-size=\"%d\" fill=\"%s\">%s</text>\n"
              + "  <text x=\"752\" y=\"48\" text-anchor=\"end\""
              + " font-family=\"-apple-system, sans-serif\""
              + " font-size=\"%d\" fill=\"%s\">%s</text>\n"
              + "  <text x=\"48\" y=\"130\" font-family=\"Nanum Myeongjo, serif\""
              + " font-weight=\"%d\" font-size=\"56\" fill=\"%s\""
              + " letter-spacing=\"%s\">%s</text>\n"
              + "  %s\n"
              + "  %s\n"
              + "  <text x=\"48\" y=\"392\" font-family=\"-apple-system, sans-serif\""
              + " font-size=\"%d\" fill=\"%s\">%s</text>\n"
              + "  <text x=\"752\" y=\"392\" text-anchor=\"end\""
              + " font-family=\"-apple-system, sans-serif\" font-style=\"italic\""
              + " font-size=\"%d\" fill=\"%s\">%s</text>\n"
              + "</svg>\n",
                GeneratedTokens.COLOR_BG_CANVAS,
                GeneratedTokens.TYPOGRAPHY_CAPTION_SIZE,
                GeneratedTokens.COLOR_TEXT_SECONDARY,
                WORDMARK,
                GeneratedTokens.TYPOGRAPHY_CAPTION_SIZE,
                GeneratedTokens.COLOR_TEXT_SECONDARY,
                yearLabel,
                GeneratedTokens.SubMode.Editorial.TYPOGRAPHY_HEADING_WEIGHT,
                GeneratedTokens.COLOR_KEY_DEFAULT,
                GeneratedTokens.SubMode.Editorial.TYPOGRAPHY_HEADING_TRACKING,
                roomName,
                topThreeBlock,
                remainingBlock,
                GeneratedTokens.TYPOGRAPHY_BODY_SIZE,
                GeneratedTokens.COLOR_TEXT_PRIMARY,
                survivorStat,
                GeneratedTokens.TYPOGRAPHY_CAPTION_SIZE,
                GeneratedTokens.COLOR_TEXT_TERTIARY,
                FOOTER);
    }

    private String renderTopThreeRow(List<SurvivorTenureRow> all) {
        int n = Math.min(3, all.size());
        StringBuilder spans = new StringBuilder();
        for (int i = 0; i < n; i++) {
            String name = escapeXml(all.get(i).nickname());
            int x = 48 + i * TOP_THREE_X_PITCH;
            spans.append("<tspan x=\"").append(x).append("\" y=\"210\">")
                    .append(name)
                    .append("</tspan>");
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
        if (all.size() <= 3) return "";
        List<SurvivorTenureRow> remaining = all.subList(3, all.size());
        List<String> lines = wrapNames(
                remaining.stream().map(r -> escapeXml(r.nickname())).toList(),
                REMAINING_MAX_CHARS,
                REMAINING_MAX_LINES,
                NAME_SEPARATOR);

        StringBuilder out = new StringBuilder();
        int y = REMAINING_Y_START;
        for (int i = 0; i < lines.size(); i++) {
            out.append("<text x=\"48\" y=\"").append(y)
                    .append("\" font-family=\"-apple-system, sans-serif\" font-size=\"")
                    .append(GeneratedTokens.TYPOGRAPHY_BODY_LG_SIZE)
                    .append("\" fill=\"")
                    .append(GeneratedTokens.COLOR_TEXT_SECONDARY)
                    .append("\">").append(lines.get(i)).append("</text>");
            if (i < lines.size() - 1) out.append("\n  ");
            y += REMAINING_LINE_HEIGHT;
        }
        return out.toString();
    }

    /**
     * Greedy line-wrap by character budget. When the last line would
     * overflow the {@code maxLines} budget mid-stream, the remainder is
     * collapsed into a "외 N명" overflow marker.
     */
    static List<String> wrapNames(List<String> names, int maxChars, int maxLines, String sep) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int idx = 0; idx < names.size(); idx++) {
            String n = names.get(idx);
            String addition = current.isEmpty() ? n : sep + n;
            if (current.length() + addition.length() > maxChars) {
                if (current.isEmpty()) {
                    lines.add(n);
                    continue;
                }
                if (lines.size() + 1 == maxLines && idx < names.size()) {
                    int remainingCount = names.size() - idx;
                    if (!current.isEmpty()) current.append(sep);
                    current.append("외 ").append(remainingCount).append("명");
                    lines.add(current.toString());
                    return lines;
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

    /** Minimal XML escape — five entities are enough for nicknames and the
     *  room name (no CDATA / no full XML semantics needed). Mirrors
     *  {@code kakaoshare/InvitePreviewRenderer.escapeXml}. */
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
