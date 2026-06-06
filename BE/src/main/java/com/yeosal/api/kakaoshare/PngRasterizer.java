package com.yeosal.api.kakaoshare;

import jakarta.annotation.PostConstruct;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import org.apache.batik.transcoder.TranscoderException;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Apache Batik wrapper. {@link PNGTranscoder} initialises a Java AWT font
 * subsystem on first invocation (~300-500ms on JVM 21), so the constructor
 * warms it up once with a minimal SVG to keep cold renders inside the AC6
 * p95 &lt; 1s budget. Each call constructs a fresh transcoder — Batik 1.17
 * is not documented as thread-safe across concurrent transcodes.
 */
@Component
public class PngRasterizer {

    private static final Logger log = LoggerFactory.getLogger(PngRasterizer.class);

    /** Story 6.1 AC6 — locked output dimensions (KakaoTalk feed 1.91:1). */
    static final int OUTPUT_WIDTH = 800;
    static final int OUTPUT_HEIGHT = 420;

    /** Story trap #5 — pre-warm the AWT font cache so the first real
     *  KakaoTalk fetch does not eat the 500ms native init penalty. */
    @PostConstruct
    public void warmUp() {
        try {
            toPng("<svg xmlns='http://www.w3.org/2000/svg' width='1' height='1'></svg>");
        } catch (RuntimeException ex) {
            // Warm-up is best-effort — a failure here does not block boot.
            log.warn("[kakaoshare] PNG transcoder warm-up failed: {}", ex.toString());
        }
    }

    /**
     * Transcodes an SVG document text into PNG bytes. Caller owns retry /
     * fall-back semantics; this method either returns valid PNG bytes or
     * throws {@link PreviewCardRenderException}.
     */
    public byte[] toPng(String svgText) {
        PNGTranscoder transcoder = new PNGTranscoder();
        transcoder.addTranscodingHint(PNGTranscoder.KEY_WIDTH,  (float) OUTPUT_WIDTH);
        transcoder.addTranscodingHint(PNGTranscoder.KEY_HEIGHT, (float) OUTPUT_HEIGHT);

        TranscoderInput input = new TranscoderInput(new StringReader(svgText));
        ByteArrayOutputStream out = new ByteArrayOutputStream(48 * 1024);
        TranscoderOutput output = new TranscoderOutput(out);

        try {
            transcoder.transcode(input, output);
        } catch (TranscoderException ex) {
            throw new PreviewCardRenderException("SVG → PNG transcode failed", ex);
        }
        return out.toByteArray();
    }
}
