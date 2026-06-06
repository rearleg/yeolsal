package com.yeosal.api.kakaoshare;

import com.yeosal.api.room.Room;
import com.yeosal.api.survival.RulePresetPreview;
import com.yeosal.api.theme.GeneratedTokens;
import org.springframework.stereotype.Component;

/**
 * Builds the D1 Editorial sub-mode SVG for a room's KakaoTalk invite
 * preview. Token consumption flows exclusively through
 * {@link GeneratedTokens.SubMode.Editorial} constants and base
 * {@link GeneratedTokens} colour constants — direct hex literals are blocked
 * by the build.gradle Checkstyle guard (see Story 1.5 AC4).
 *
 * <p>Output is a self-contained SVG document — no external {@code <image>}
 * refs that could trigger Batik HTTP fetches at transcode time.
 */
@Component
public class InvitePreviewRenderer {

    /** Brand-voice locked phrases (PRD FR-8.8.2). */
    static final String WORDMARK = "열살";
    static final String FOOTER = "같이 살아남자";
    static final String MEMBER_COUNT_SUFFIX = "명이 함께 살아남는 중";

    public String render(Room room, int memberCount, String preset, boolean weekendInclude) {
        String roomName = escapeXml(room.getName());
        String rulePreview = escapeXml(RulePresetPreview.format(preset, weekendInclude));
        String memberLine = memberCount + MEMBER_COUNT_SUFFIX;

        return String.format(
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n"
              + "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"800\" height=\"420\" viewBox=\"0 0 800 420\">\n"
              + "  <rect width=\"100%%\" height=\"100%%\" fill=\"%s\"/>\n"
              + "  <text x=\"48\" y=\"80\" font-family=\"Nanum Myeongjo, serif\""
              + " font-weight=\"%d\" font-size=\"22\" fill=\"%s\">%s</text>\n"
              + "  <text x=\"48\" y=\"170\" font-family=\"Nanum Myeongjo, serif\""
              + " font-weight=\"%d\" font-size=\"56\" fill=\"%s\""
              + " letter-spacing=\"%s\">%s</text>\n"
              + "  <text x=\"48\" y=\"240\" font-family=\"-apple-system, sans-serif\""
              + " font-size=\"20\" fill=\"%s\">%s</text>\n"
              + "  <text x=\"48\" y=\"280\" font-family=\"-apple-system, sans-serif\""
              + " font-size=\"18\" fill=\"%s\">%s</text>\n"
              + "  <text x=\"48\" y=\"380\" font-family=\"-apple-system, sans-serif\""
              + " font-size=\"16\" fill=\"%s\" font-style=\"italic\">%s</text>\n"
              + "</svg>\n",
                GeneratedTokens.COLOR_BG_CANVAS,
                GeneratedTokens.SubMode.Editorial.TYPOGRAPHY_HEADING_WEIGHT,
                GeneratedTokens.COLOR_TEXT_PRIMARY,
                WORDMARK,
                GeneratedTokens.SubMode.Editorial.TYPOGRAPHY_HEADING_WEIGHT,
                GeneratedTokens.COLOR_KEY_DEFAULT,
                GeneratedTokens.SubMode.Editorial.TYPOGRAPHY_HEADING_TRACKING,
                roomName,
                GeneratedTokens.COLOR_TEXT_PRIMARY,
                memberLine,
                GeneratedTokens.COLOR_TEXT_SECONDARY,
                rulePreview,
                GeneratedTokens.COLOR_TEXT_SECONDARY,
                FOOTER);
    }

    /** Minimal XML escape — five core entities are enough for room names and
     *  rule preview phrases (no CDATA / no full XML semantics needed). */
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
