package com.yeosal.api.ceremony;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yeosal.api.common.ForbiddenException;
import com.yeosal.api.common.NotFoundException;
import com.yeosal.api.kakaoshare.PngRasterizer;
import com.yeosal.api.kakaoshare.PreviewCardRenderException;
import com.yeosal.api.room.Room;
import com.yeosal.api.room.RoomMemberRepository;
import com.yeosal.api.room.RoomRepository;
import com.yeosal.api.room.chat.ChatService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class FinalThreeServiceTest {

    private static final long ROOM_ID = 42L;
    private static final YearMonth MONTH = YearMonth.of(2026, 6);

    private FinalThreePosterRepository posterRepository;
    private RoomRepository rooms;
    private RoomMemberRepository roomMembers;
    private SvgRenderer svgRenderer;
    private PngRasterizer pngRasterizer;
    private ChatService chatService;
    private EntityManager em;
    private Room room;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        posterRepository = mock(FinalThreePosterRepository.class);
        rooms = mock(RoomRepository.class);
        roomMembers = mock(RoomMemberRepository.class);
        svgRenderer = mock(SvgRenderer.class);
        pngRasterizer = mock(PngRasterizer.class);
        chatService = mock(ChatService.class);
        em = mock(EntityManager.class);
        Query advisoryLock = mock(Query.class);
        lenient().when(em.createNativeQuery(anyString())).thenReturn(advisoryLock);
        lenient().when(advisoryLock.setParameter(anyString(), any())).thenReturn(advisoryLock);
        lenient().when(advisoryLock.getSingleResult()).thenReturn(null);
        room = mock(Room.class);
        when(room.getId()).thenReturn(ROOM_ID);
        when(room.getName()).thenReturn("우리 방");
        when(rooms.findById(ROOM_ID)).thenReturn(Optional.of(room));
        when(posterRepository.save(any(FinalThreePoster.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("generatePoster — existing row short-circuits without rendering or chat")
    void generatePoster_existingRow_shortCircuits() {
        FinalThreePoster existing = new FinalThreePoster(
                ROOM_ID, "2026-06", "<svg/>", "https://api.example/posters/42-2026-06.png");
        when(posterRepository.findById(new FinalThreePosterId(ROOM_ID, "2026-06")))
                .thenReturn(Optional.of(existing));

        FinalThreeService service = serviceWithStubbedSurvivors(List.of());

        Optional<FinalThreePoster> result = service.generatePoster(ROOM_ID, MONTH);

        assertThat(result).containsSame(existing);
        verify(svgRenderer, never()).render(any(), any(), any(), anyInt());
        verify(em, never()).createNativeQuery(anyString());
        verify(chatService, never())
                .publishMonthlyNoSurvivorsSystemMessage(anyLong(), any(YearMonth.class));
        verify(posterRepository, never()).save(any());
    }

    @Test
    @DisplayName("generatePoster — concurrent winner row after advisory lock short-circuits")
    void generatePoster_existingAfterLock_shortCircuits() {
        FinalThreePoster existing = new FinalThreePoster(
                ROOM_ID, "2026-06", "<svg/>", "https://api.example/yeolsal/posters/42-2026-06.png");
        when(posterRepository.findById(new FinalThreePosterId(ROOM_ID, "2026-06")))
                .thenReturn(Optional.empty(), Optional.of(existing));

        FinalThreeService service = serviceWithStubbedSurvivors(List.of(
                new SurvivorTenureRow("alice", 10L, Instant.parse("2026-01-01T00:00:00Z"))));

        Optional<FinalThreePoster> result = service.generatePoster(ROOM_ID, MONTH);

        assertThat(result).containsSame(existing);
        verify(svgRenderer, never()).render(any(), any(), any(), anyInt());
        verify(pngRasterizer, never()).toPng(anyString());
        verify(posterRepository, never()).save(any());
    }

    @Test
    @DisplayName("generatePoster — zero survivors publishes chat fallback and returns empty")
    void generatePoster_zeroSurvivors_chatFallback() {
        when(posterRepository.findById(any())).thenReturn(Optional.empty());

        FinalThreeService service = serviceWithStubbedSurvivors(List.of());

        Optional<FinalThreePoster> result = service.generatePoster(ROOM_ID, MONTH);

        assertThat(result).isEmpty();
        verify(chatService).publishMonthlyNoSurvivorsSystemMessage(ROOM_ID, MONTH);
        verify(posterRepository, never()).save(any());
        verify(svgRenderer, never()).render(any(), any(), any(), anyInt());
    }

    @Test
    @DisplayName("generatePoster — survivors present persists SVG + PNG row")
    void generatePoster_happyPath_persistsRow() {
        when(posterRepository.findById(any())).thenReturn(Optional.empty());
        when(svgRenderer.render(any(), any(), any(), anyInt())).thenReturn("<svg>rendered</svg>");
        when(pngRasterizer.toPng(anyString())).thenReturn(new byte[] {(byte) 0x89, 'P', 'N', 'G'});

        FinalThreeService service = serviceWithStubbedSurvivors(List.of(
                new SurvivorTenureRow("alice", 10L, Instant.parse("2026-01-01T00:00:00Z")),
                new SurvivorTenureRow("bob",   11L, Instant.parse("2026-01-02T00:00:00Z"))));

        Optional<FinalThreePoster> result = service.generatePoster(ROOM_ID, MONTH);

        assertThat(result).isPresent();
        ArgumentCaptor<FinalThreePoster> captor = ArgumentCaptor.forClass(FinalThreePoster.class);
        verify(posterRepository).save(captor.capture());
        FinalThreePoster saved = captor.getValue();
        assertThat(saved.getRoomId()).isEqualTo(ROOM_ID);
        assertThat(saved.getYearMonth()).isEqualTo("2026-06");
        assertThat(saved.getSvgText()).contains("rendered");
        assertThat(saved.getPngUrl())
                .isEqualTo("https://api.example/yeolsal/posters/42-2026-06.png");
        verify(chatService, never())
                .publishMonthlyNoSurvivorsSystemMessage(anyLong(), any(YearMonth.class));
    }

    @Test
    @DisplayName("generatePoster — PNG rasterize failure persists row with pngUrl=null")
    void generatePoster_pngRasterizeFailure_svgOnly() {
        when(posterRepository.findById(any())).thenReturn(Optional.empty());
        when(svgRenderer.render(any(), any(), any(), anyInt())).thenReturn("<svg/>");
        when(pngRasterizer.toPng(anyString()))
                .thenThrow(new PreviewCardRenderException("transcode boom"));

        FinalThreeService service = serviceWithStubbedSurvivors(List.of(
                new SurvivorTenureRow("alice", 10L, Instant.parse("2026-01-01T00:00:00Z"))));

        Optional<FinalThreePoster> result = service.generatePoster(ROOM_ID, MONTH);

        assertThat(result).isPresent();
        ArgumentCaptor<FinalThreePoster> captor = ArgumentCaptor.forClass(FinalThreePoster.class);
        verify(posterRepository).save(captor.capture());
        assertThat(captor.getValue().getPngUrl()).isNull();
        assertThat(captor.getValue().getSvgText()).isEqualTo("<svg/>");
    }

    @Test
    @DisplayName("generatePoster — missing room raises NotFoundException")
    void generatePoster_missingRoom_throwsNotFound() {
        when(posterRepository.findById(any())).thenReturn(Optional.empty());
        when(rooms.findById(ROOM_ID)).thenReturn(Optional.empty());

        FinalThreeService service = serviceWithStubbedSurvivors(List.of());

        assertThatThrownBy(() -> service.generatePoster(ROOM_ID, MONTH))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("방을 찾을 수 없습니다");
    }

    @Test
    @DisplayName("getPosterForMember — non-member raises ForbiddenException")
    void getPosterForMember_nonMember_forbidden() {
        when(roomMembers.existsByRoomIdAndUserId(ROOM_ID, 99L)).thenReturn(false);
        FinalThreeService service = serviceWithStubbedSurvivors(List.of());

        assertThatThrownBy(() -> service.getPosterForMember(ROOM_ID, MONTH, 99L))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("방 멤버");
        verify(posterRepository, never()).findById(any());
    }

    @Test
    @DisplayName("getPosterForMember — member with missing poster raises PosterNotFoundException")
    void getPosterForMember_member_missing_posterNotFound() {
        when(roomMembers.existsByRoomIdAndUserId(ROOM_ID, 7L)).thenReturn(true);
        when(posterRepository.findById(new FinalThreePosterId(ROOM_ID, "2026-06")))
                .thenReturn(Optional.empty());
        FinalThreeService service = serviceWithStubbedSurvivors(List.of());

        assertThatThrownBy(() -> service.getPosterForMember(ROOM_ID, MONTH, 7L))
                .isInstanceOf(PosterNotFoundException.class);
    }

    @Test
    @DisplayName("getPosterForMember — member with existing poster returns row")
    void getPosterForMember_member_existing_returnsRow() {
        FinalThreePoster existing = new FinalThreePoster(
                ROOM_ID, "2026-06", "<svg/>", "https://api.example/posters/42-2026-06.png");
        when(roomMembers.existsByRoomIdAndUserId(ROOM_ID, 7L)).thenReturn(true);
        when(posterRepository.findById(new FinalThreePosterId(ROOM_ID, "2026-06")))
                .thenReturn(Optional.of(existing));
        FinalThreeService service = serviceWithStubbedSurvivors(List.of());

        FinalThreePoster fetched = service.getPosterForMember(ROOM_ID, MONTH, 7L);

        assertThat(fetched).isSameAs(existing);
    }

    @Test
    @DisplayName("generatePoster — writes PNG atomically to the configured output dir")
    void generatePoster_writePng_atomic() throws Exception {
        when(posterRepository.findById(any())).thenReturn(Optional.empty());
        when(svgRenderer.render(any(), any(), any(), anyInt())).thenReturn("<svg/>");
        when(pngRasterizer.toPng(anyString()))
                .thenReturn(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n'});

        FinalThreeService service = serviceWithStubbedSurvivors(List.of(
                new SurvivorTenureRow("alice", 10L, Instant.parse("2026-01-01T00:00:00Z"))));

        service.generatePoster(ROOM_ID, MONTH);

        Path expected = tempDir.resolve("42-2026-06.png");
        assertThat(Files.exists(expected)).isTrue();
        assertThat(Files.size(expected)).isGreaterThan(0);
    }

    /** Returns a service whose {@link FinalThreeService#querySurvivors(long)} is stubbed. */
    private FinalThreeService serviceWithStubbedSurvivors(List<SurvivorTenureRow> survivors) {
        FinalThreeService real = new FinalThreeService(
                posterRepository, rooms, roomMembers, svgRenderer, pngRasterizer,
                chatService, em,
                tempDir.toString(),
                "https://api.example/yeolsal");
        FinalThreeService spied = spy(real);
        doReturn(survivors).when(spied).querySurvivors(eq(ROOM_ID));
        return spied;
    }
}
