package com.yeosal.api.ceremony;

import static org.assertj.core.api.Assertions.assertThat;

import com.yeosal.api.room.Room;
import com.yeosal.api.theme.GeneratedTokens;
import com.yeosal.api.user.AuthProvider;
import com.yeosal.api.user.User;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Story 7.1 AC8 — epic AC2 token-indirection gate. Asserts the rendered
 * SVG carries the *current* {@link GeneratedTokens} literal values, which
 * proves the renderer indirects through the codegen constants instead of
 * inlining hex.
 *
 * <p>Opt-in via {@code -Dyeosal.boot-smoke=true} for parity with
 * {@code V11MigrationIT} (Story 1.4) and {@code PreviewCardEndToEndIT}
 * (Story 6.1). PR-CI passes the property; Docker-less dev hosts skip.
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "yeosal.boot-smoke", matches = "true")
class SvgRendererTokenDiffIT {

    @Autowired
    private SvgRenderer renderer;

    @Test
    @DisplayName("renderer output contains live GeneratedTokens constant values")
    void renderer_contains_tokenLiterals() {
        User owner = new User("dev@example.com", "owner", null, AuthProvider.EMAIL);
        Room room = new Room("우리 방", owner);
        String svg = renderer.render(
                room,
                YearMonth.of(2026, 6),
                List.of(new SurvivorTenureRow("alice", 10L, Instant.parse("2026-01-01T00:00:00Z"))),
                1);

        // If the renderer were ever to inline a hex literal, the next codegen
        // run (changing the constant value) would NOT change the SVG output —
        // these assertions would fail because the live constant no longer
        // matches the embedded literal.
        assertThat(svg).contains(GeneratedTokens.COLOR_BG_CANVAS);
        assertThat(svg).contains(GeneratedTokens.COLOR_KEY_DEFAULT);
        assertThat(svg).contains(GeneratedTokens.COLOR_KEY_LINE);
        assertThat(svg).contains(GeneratedTokens.COLOR_TEXT_SECONDARY);
    }
}
