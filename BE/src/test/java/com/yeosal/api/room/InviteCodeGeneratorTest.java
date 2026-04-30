package com.yeosal.api.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class InviteCodeGeneratorTest {

    private final InviteCodeGenerator generator = new InviteCodeGenerator();

    @Test
    void generatesEightCharacterCode() {
        String code = generator.generate(c -> false);

        assertThat(code).hasSize(8);
    }

    @Test
    void codeContainsOnlyUnambiguousAlphanumerics() {
        // Excludes ambiguous glyphs: 0/O, 1/I/l. Allowed alphabet is the remaining
        // upper-case letters and digits 2-9.
        String code = generator.generate(c -> false);

        assertThat(code).matches("[2-9A-HJ-NP-Z]+");
    }

    @Test
    void retriesOnCollisionUntilFreshCodeFound() {
        Set<String> taken = new HashSet<>();
        Predicate<String> isTaken = c -> {
            if (taken.size() < 3) {
                taken.add(c);
                return true;
            }
            return false;
        };

        String code = generator.generate(isTaken);

        assertThat(code).hasSize(8);
        assertThat(taken).doesNotContain(code);
    }

    @Test
    void givesUpAfterMaxAttempts() {
        Predicate<String> alwaysTaken = c -> true;

        assertThatThrownBy(() -> generator.generate(alwaysTaken))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("invite code");
    }
}
