package com.yeosal.api.ceremony;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Belt-and-suspenders check against {@code BE/build.gradle:284-303}
 * Checkstyle hex-literal guard. Reads {@link SvgRenderer} source as a String
 * and asserts no {@code "#RRGGBB"} or {@code "rgb("} / {@code "oklch("}
 * literal made it into the renderer. The Checkstyle task is the real gate;
 * this test prevents the renderer from drifting in a way Checkstyle would
 * miss (e.g., a fixture string accidentally checked into the renderer).
 */
class SvgRendererHexLiteralGateTest {

    private static final Path RENDERER_SRC =
            Path.of("src/main/java/com/yeosal/api/ceremony/SvgRenderer.java");

    @Test
    @DisplayName("renderer source contains no #RRGGBB hex literal")
    void rendererSource_noHexLiteral() throws Exception {
        String source = Files.readString(RENDERER_SRC);
        assertThat(source).doesNotContainPattern("\"#[0-9A-Fa-f]{3,8}\\b");
    }

    @Test
    @DisplayName("renderer source contains no rgb(/rgba(/oklch( color literal")
    void rendererSource_noCssColorFunction() throws Exception {
        String source = Files.readString(RENDERER_SRC);
        assertThat(source).doesNotContain("\"rgb(");
        assertThat(source).doesNotContain("\"rgba(");
        assertThat(source).doesNotContain("\"oklch(");
    }
}
