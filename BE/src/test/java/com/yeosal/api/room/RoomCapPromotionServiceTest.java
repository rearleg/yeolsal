package com.yeosal.api.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.yeosal.api.user.AuthProvider;
import com.yeosal.api.user.User;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Story 5.2 — Mockito unit assertions for {@link RoomCapPromotionService}.
 * Five cases cover the AC5 lazy-promotion boundary semantics: missing
 * room, no pending edit, future month not yet due, due month (flush), and
 * idempotent re-entry after promotion.
 */
@ExtendWith(MockitoExtension.class)
class RoomCapPromotionServiceTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final long ROOM_ID = 42L;
    private static final long LEADER_ID = 7L;

    @Mock private RoomRepository rooms;

    private User leader;
    private Room room;

    @BeforeEach
    void setUp() {
        leader = makeUser(LEADER_ID, "leader@example.com", "Leader");
        room = makeRoom(ROOM_ID, leader);
    }

    private RoomCapPromotionService build(Instant now) {
        return new RoomCapPromotionService(rooms, Clock.fixed(now, KST));
    }

    @Test
    @DisplayName("requireRoom missing → returns false, no write")
    void missingRoom_returnsFalse() {
        when(rooms.findById(ROOM_ID)).thenReturn(Optional.empty());

        boolean promoted = build(april15()).promotePendingCapIfDue(ROOM_ID);

        assertThat(promoted).isFalse();
    }

    @Test
    @DisplayName("No pending → no-op, returns false")
    void noPending_returnsFalse() {
        when(rooms.findById(ROOM_ID)).thenReturn(Optional.of(room));

        boolean promoted = build(april15()).promotePendingCapIfDue(ROOM_ID);

        assertThat(promoted).isFalse();
        assertThat(room.getPendingMaxMembers()).isNull();
    }

    @Test
    @DisplayName("Pending May, current April → not due, returns false, no flush")
    void notYetDue_returnsFalse() {
        room.setPendingMaxMembers((short) 20);
        room.setPendingMaxMembersEffectiveFromMonth("2026-05");
        when(rooms.findById(ROOM_ID)).thenReturn(Optional.of(room));

        boolean promoted = build(april15()).promotePendingCapIfDue(ROOM_ID);

        assertThat(promoted).isFalse();
        assertThat(room.getMaxMembers()).isEqualTo((short) 12);
        assertThat(room.getPendingMaxMembers()).isEqualTo((short) 20);
    }

    @Test
    @DisplayName("Pending May, current May 1 00:00 KST → due, flush + clear pending")
    void dueNow_flushesAndClears() {
        room.setPendingMaxMembers((short) 20);
        room.setPendingMaxMembersEffectiveFromMonth("2026-05");
        when(rooms.findById(ROOM_ID)).thenReturn(Optional.of(room));

        boolean promoted = build(may1AtMidnightKst()).promotePendingCapIfDue(ROOM_ID);

        assertThat(promoted).isTrue();
        assertThat(room.getMaxMembers()).isEqualTo((short) 20);
        assertThat(room.getPendingMaxMembers()).isNull();
        assertThat(room.getPendingMaxMembersEffectiveFromMonth()).isNull();
    }

    @Test
    @DisplayName("Idempotent — second call on already-promoted row is a no-op")
    void afterPromotion_idempotent() {
        when(rooms.findById(ROOM_ID)).thenReturn(Optional.of(room));
        room.setMaxMembers((short) 20);

        boolean promoted = build(may1AtMidnightKst()).promotePendingCapIfDue(ROOM_ID);

        assertThat(promoted).isFalse();
        assertThat(room.getMaxMembers()).isEqualTo((short) 20);
    }

    private static Instant april15() {
        return LocalDateTime.of(2026, 4, 15, 12, 0).atZone(KST).toInstant();
    }

    private static Instant may1AtMidnightKst() {
        return LocalDateTime.of(2026, 5, 1, 0, 0).atZone(KST).toInstant();
    }

    private static User makeUser(long id, String email, String nickname) {
        User u = new User(email, nickname, "hash", AuthProvider.EMAIL);
        setField(u, "id", id);
        return u;
    }

    private static Room makeRoom(long id, User owner) {
        Room r = new Room("test-room", owner);
        setField(r, "id", id);
        r.setMaxMembers((short) 12);
        return r;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field f = findField(target.getClass(), name);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> cursor = type;
        while (cursor != null) {
            try {
                return cursor.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                cursor = cursor.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
