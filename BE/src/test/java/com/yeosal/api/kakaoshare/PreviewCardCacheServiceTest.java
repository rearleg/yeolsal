package com.yeosal.api.kakaoshare;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yeosal.api.common.ServiceUnavailableException;
import com.yeosal.api.room.Room;
import com.yeosal.api.room.RoomMemberRepository;
import com.yeosal.api.room.RoomRepository;
import com.yeosal.api.survival.RoomRuleVersion;
import com.yeosal.api.survival.RoomRuleVersionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PreviewCardCacheServiceTest {

    private static final long ROOM_ID = 42L;
    private static final Instant NOW = Instant.parse("2026-06-04T03:00:00Z");

    @Mock private PreviewCardCacheRepository cacheRepository;
    @Mock private RoomRepository rooms;
    @Mock private RoomMemberRepository roomMembers;
    @Mock private RoomRuleVersionRepository ruleVersions;
    @Mock private InvitePreviewRenderer renderer;
    @Mock private PngRasterizer rasterizer;
    @Mock private PreviewCardBackgroundRenderer backgroundRenderer;
    @Mock private EntityManager em;
    @Mock private Query lockQuery;

    private Room room;
    private RoomRuleVersion currentRule;
    private final Clock clock = Clock.fixed(NOW, ZoneId.of("Asia/Seoul"));

    private Path tempDir;
    private PreviewCardCacheService service;

    @BeforeEach
    void setUp(@TempDir Path tmp) {
        this.tempDir = tmp;
        room = makeRoom(ROOM_ID, "기본 방");
        currentRule = makeRule(700L, "2026-06");

        service = new PreviewCardCacheService(
                cacheRepository, rooms, roomMembers, ruleVersions, renderer,
                rasterizer, backgroundRenderer, em, clock,
                tempDir.toString(),
                "https://api.example.com/yeolsal");
    }

    @Test
    @DisplayName("resolve: room not found returns Optional.empty (controller maps to 503/404)")
    void resolve_unknownRoom_returnsEmpty() {
        when(rooms.findById(999L)).thenReturn(Optional.empty());

        assertThat(service.resolve(999L)).isEmpty();
        verifyNoInteractions(backgroundRenderer);
    }

    @Test
    @DisplayName("resolve: fresh cache row → serve URL, no background render")
    void resolve_freshRow_servesUrlNoBackgroundRender() {
        when(rooms.findById(ROOM_ID)).thenReturn(Optional.of(room));
        PreviewCardCache fresh = new PreviewCardCache(
                ROOM_ID, "https://example.com/42.png", NOW.minusSeconds(60), 700L, 5);
        when(cacheRepository.findById(ROOM_ID)).thenReturn(Optional.of(fresh));

        Optional<String> result = service.resolve(ROOM_ID);

        assertThat(result).contains("https://example.com/42.png");
        verifyNoInteractions(backgroundRenderer);
    }

    @Test
    @DisplayName("resolve: stale cache row → serve URL + kick off background render")
    void resolve_staleRow_kicksBackgroundRender() {
        when(rooms.findById(ROOM_ID)).thenReturn(Optional.of(room));
        PreviewCardCache stale = new PreviewCardCache(
                ROOM_ID, "https://example.com/42.png",
                NOW.minusSeconds(3 * 3600), 700L, 5);
        when(cacheRepository.findById(ROOM_ID)).thenReturn(Optional.of(stale));

        Optional<String> result = service.resolve(ROOM_ID);

        assertThat(result).contains("https://example.com/42.png");
        verify(backgroundRenderer).render(room);
    }

    @Test
    @DisplayName("resolve: cold miss with lock acquired → synchronous render + new URL")
    void resolve_coldMissWithLock_synchronousRender() {
        when(rooms.findById(ROOM_ID)).thenReturn(Optional.of(room));
        when(cacheRepository.findById(ROOM_ID)).thenReturn(Optional.empty());
        stubLockAcquire(true);
        stubRenderHappy();

        Optional<String> result = service.resolve(ROOM_ID);

        assertThat(result).contains("https://api.example.com/yeolsal/preview-cards/42.png");
        verify(cacheRepository).save(any(PreviewCardCache.class));
        assertThat(Files.exists(tempDir.resolve("42.png"))).isTrue();
    }

    @Test
    @DisplayName("resolve: cold miss + lock contention + no stale row → ServiceUnavailableException")
    void resolve_coldMissLockContentionNoStale_throws503() {
        when(rooms.findById(ROOM_ID)).thenReturn(Optional.of(room));
        when(cacheRepository.findById(ROOM_ID)).thenReturn(Optional.empty());
        stubLockAcquire(false);

        assertThatThrownBy(() -> service.resolve(ROOM_ID))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("retry shortly");
    }

    @Test
    @DisplayName("invalidate: deletes the cache row by id (idempotent — repo silences absent)")
    void invalidate_deletesRow() {
        service.invalidate(ROOM_ID);
        verify(cacheRepository).deleteById(ROOM_ID);
    }

    @Test
    @DisplayName("invalidate: repository failure is swallowed (next resolve rebuilds)")
    void invalidate_swallowsRepositoryFailure() {
        doThrow(new RuntimeException("DB down")).when(cacheRepository).deleteById(ROOM_ID);

        service.invalidate(ROOM_ID);

        verify(cacheRepository).deleteById(ROOM_ID);
    }

    @Test
    @DisplayName("backgroundRenderUnchecked: lock-not-acquired path is a no-op (no render kick)")
    void backgroundRenderUnchecked_lockMissedIsNoop() {
        stubLockAcquire(false);

        service.backgroundRenderUnchecked(room);

        verify(rasterizer, never()).toPng(anyString());
        verify(cacheRepository, never()).save(any(PreviewCardCache.class));
    }

    // ----- helpers -----

    private void stubLockAcquire(boolean acquired) {
        lenient().when(em.createNativeQuery(anyString())).thenReturn(lockQuery);
        lenient().when(lockQuery.setParameter(eq("rid"), any())).thenReturn(lockQuery);
        lenient().when(lockQuery.getSingleResult()).thenReturn(acquired);
    }

    private void stubRenderHappy() {
        when(roomMembers.countByRoom(room)).thenReturn(5L);
        when(ruleVersions
                .findTopByRoomIdAndEffectiveFromMonthLessThanEqualOrderByEffectiveFromMonthDesc(
                        eq(ROOM_ID), anyString()))
                .thenReturn(Optional.of(currentRule));
        when(renderer.render(eq(room), eq(5), eq("DAILY_UPDATE"), eq(false)))
                .thenReturn("<svg/>");
        byte[] pngHeader = { (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A };
        when(rasterizer.toPng(anyString())).thenReturn(pngHeader);
        lenient().when(cacheRepository.save(any(PreviewCardCache.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    private static Room makeRoom(long id, String name) {
        Room room = org.mockito.Mockito.mock(Room.class);
        lenient().when(room.getId()).thenReturn(id);
        lenient().when(room.getName()).thenReturn(name);
        return room;
    }

    private RoomRuleVersion makeRule(long id, String month) {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode payload;
        try {
            payload = mapper.readTree("{\"preset\":\"DAILY_UPDATE\",\"weekendInclude\":false}");
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
        RoomRuleVersion rule = new RoomRuleVersion(ROOM_ID, month, payload, 7L);
        try {
            Field idField = RoomRuleVersion.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(rule, id);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
        return rule;
    }
}
