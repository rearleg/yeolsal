package com.yeosal.api.daily;

import static org.assertj.core.api.Assertions.assertThat;

import com.yeosal.api.user.AuthProvider;
import com.yeosal.api.user.User;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReflectionTest {

    @Test
    @DisplayName("updateBody replaces body and bumps updatedAt while preserving submittedAt")
    void updateBody_setsBodyAndBumpsUpdatedAt_preservingSubmittedAt() {
        User alice = new User("alice@example.com", "Alice", "hash", AuthProvider.EMAIL);
        DailyEntry entry = new DailyEntry(alice, LocalDate.parse("2026-04-30"), "오늘 목표");
        Reflection r = new Reflection(entry, "원본 회고");
        Instant submittedAt = Instant.parse("2026-04-30T10:00:00Z");
        setField(r, "submittedAt", submittedAt);
        setField(r, "updatedAt", submittedAt);

        Instant before = Instant.now();
        r.updateBody("수정된 회고");
        Instant after = Instant.now();

        assertThat(r.getBody()).isEqualTo("수정된 회고");
        assertThat(r.getUpdatedAt())
                .as("updatedAt must advance to a wall-clock instant taken during updateBody()")
                .isBetween(before, after);
        assertThat(r.getSubmittedAt())
                .as("submittedAt is the day-complete marker — never moved on edit")
                .isEqualTo(submittedAt);
    }

    @Test
    @DisplayName("prePersist initialises updatedAt to submittedAt for new reflections")
    void prePersist_initialisesUpdatedAtEqualToSubmittedAt() throws Exception {
        User alice = new User("alice@example.com", "Alice", "hash", AuthProvider.EMAIL);
        DailyEntry entry = new DailyEntry(alice, LocalDate.parse("2026-04-30"), "오늘 목표");
        Reflection r = new Reflection(entry, "신규 회고");

        // Direct invocation mirrors what JPA does on insert; we verify the
        // entity itself sets the audit timestamp rather than relying on the
        // DB default. Without this, freshly-submitted rows would surface a
        // null updatedAt to the FE and the "수정됨" caption logic could
        // not safely compare updatedAt against submittedAt.
        Method prePersist = Reflection.class.getDeclaredMethod("prePersist");
        prePersist.setAccessible(true);
        prePersist.invoke(r);

        assertThat(r.getSubmittedAt()).isNotNull();
        assertThat(r.getUpdatedAt()).isEqualTo(r.getSubmittedAt());
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
