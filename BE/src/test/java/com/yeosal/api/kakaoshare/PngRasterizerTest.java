package com.yeosal.api.kakaoshare;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PngRasterizerTest {

    private static final byte[] PNG_MAGIC = { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };

    @Test
    @DisplayName("transcodes a minimal valid SVG into PNG bytes with the standard magic header")
    void minimalSvg_transcodesToPng() {
        PngRasterizer rasterizer = new PngRasterizer();
        String svg = "<svg xmlns='http://www.w3.org/2000/svg' width='2' height='2'>"
                + "<rect width='100%' height='100%' fill='black'/></svg>";

        byte[] png = rasterizer.toPng(svg);

        assertThat(png).isNotEmpty();
        for (int i = 0; i < PNG_MAGIC.length; i++) {
            assertThat(png[i]).as("PNG magic byte " + i).isEqualTo(PNG_MAGIC[i]);
        }
    }

    @Test
    @DisplayName("malformed SVG triggers PreviewCardRenderException with the Batik exception chained")
    void malformedSvg_throwsRenderException() {
        PngRasterizer rasterizer = new PngRasterizer();

        assertThatThrownBy(() -> rasterizer.toPng("<not-svg>oops</not-svg>"))
                .isInstanceOf(PreviewCardRenderException.class)
                .hasMessageContaining("transcode");
    }
}
