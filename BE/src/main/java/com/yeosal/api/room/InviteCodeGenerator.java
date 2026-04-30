package com.yeosal.api.room;

import java.security.SecureRandom;
import java.util.function.Predicate;
import org.springframework.stereotype.Component;

/**
 * Generates short, URL-safe, human-readable invite codes for rooms.
 *
 * <p>The alphabet excludes characters that look alike in common fonts
 * ({@code 0/O}, {@code 1/I/l}) so codes spoken aloud or copied by hand stay
 * unambiguous. Caller supplies an "is taken" predicate so the generator can
 * keep producing fresh codes without coupling to any persistence layer.
 */
@Component
public class InviteCodeGenerator {

    private static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final int LENGTH = 8;
    private static final int MAX_ATTEMPTS = 16;

    private final SecureRandom random = new SecureRandom();

    public String generate(Predicate<String> isTaken) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String candidate = randomCode();
            if (!isTaken.test(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "Unable to generate unique invite code after " + MAX_ATTEMPTS + " attempts");
    }

    private String randomCode() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}
