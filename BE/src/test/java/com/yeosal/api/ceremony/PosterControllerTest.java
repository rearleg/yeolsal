package com.yeosal.api.ceremony;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yeosal.api.auth.UserPrincipal;
import com.yeosal.api.common.ApiResponse;
import com.yeosal.api.common.BadRequestException;
import com.yeosal.api.common.CurrentUser;
import com.yeosal.api.common.ForbiddenException;
import com.yeosal.api.common.UnauthorizedException;
import com.yeosal.api.user.AuthProvider;
import com.yeosal.api.user.User;
import com.yeosal.api.user.UserRepository;
import java.lang.reflect.Field;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

/**
 * Story 7.1 AC7 — controller unit coverage. Mirrors the
 * {@code PreviewCardControllerTest} shape (direct method invocation rather
 * than {@code @WebMvcTest}) so the suite runs Docker-less. Real HTTP layer
 * coverage lives in the (currently deferred) opt-in IT.
 */
class PosterControllerTest {

    private static final long ROOM_ID = 42L;
    private static final long VIEWER_USER_ID = 7L;
    private static final YearMonth MONTH = YearMonth.of(2026, 6);

    private FinalThreeService finalThreeService;
    private PosterController controller;

    @BeforeEach
    void setUp() {
        finalThreeService = mock(FinalThreeService.class);
        UserRepository users = mock(UserRepository.class);
        CurrentUser currentUser = new CurrentUser(users);
        controller = new PosterController(finalThreeService, currentUser);

        User viewer = new User("viewer@example.com", "viewer", null, AuthProvider.EMAIL);
        setField(viewer, "id", VIEWER_USER_ID);
        when(users.findById(VIEWER_USER_ID)).thenReturn(Optional.of(viewer));
    }

    @Test
    @DisplayName("getPoster — happy path returns ApiResponse<PosterDto> with full row shape")
    void getPoster_happyPath() {
        FinalThreePoster poster = new FinalThreePoster(
                ROOM_ID, "2026-06", "<svg>ok</svg>",
                "https://api.example/posters/42-2026-06.png");
        setField(poster, "generatedAt", Instant.parse("2026-06-01T21:30:00Z"));
        when(finalThreeService.getPosterForMember(ROOM_ID, MONTH, VIEWER_USER_ID))
                .thenReturn(poster);

        ApiResponse<PosterDto> response = controller.getPoster(authForViewer(), ROOM_ID, "2026-06");

        PosterDto dto = response.data();
        assertThat(dto.roomId()).isEqualTo(ROOM_ID);
        assertThat(dto.yearMonth()).isEqualTo("2026-06");
        assertThat(dto.svgText()).isEqualTo("<svg>ok</svg>");
        assertThat(dto.pngUrl()).endsWith("/posters/42-2026-06.png");
        assertThat(dto.generatedAt()).isEqualTo(Instant.parse("2026-06-01T21:30:00Z"));
    }

    @Test
    @DisplayName("getPoster — missing poster surfaces PosterNotFoundException (→ 404 via NotFoundException handler)")
    void getPoster_missing_404() {
        when(finalThreeService.getPosterForMember(ROOM_ID, MONTH, VIEWER_USER_ID))
                .thenThrow(new PosterNotFoundException(ROOM_ID, MONTH));

        assertThatThrownBy(() -> controller.getPoster(authForViewer(), ROOM_ID, "2026-06"))
                .isInstanceOf(PosterNotFoundException.class);
    }

    @Test
    @DisplayName("getPoster — non-member surfaces ForbiddenException (→ 403)")
    void getPoster_nonMember_403() {
        when(finalThreeService.getPosterForMember(ROOM_ID, MONTH, VIEWER_USER_ID))
                .thenThrow(new ForbiddenException("방 멤버만 접근할 수 있습니다."));

        assertThatThrownBy(() -> controller.getPoster(authForViewer(), ROOM_ID, "2026-06"))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("getPoster — malformed yearMonth surfaces BadRequestException (→ 400)")
    void getPoster_malformedYearMonth_400() {
        assertThatThrownBy(() -> controller.getPoster(authForViewer(), ROOM_ID, "2026-13"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("YYYY-MM");
        assertThatThrownBy(() -> controller.getPoster(authForViewer(), ROOM_ID, "26-06"))
                .isInstanceOf(BadRequestException.class);
        verify(finalThreeService, never()).getPosterForMember(anyLong(), any(), anyLong());
    }

    @Test
    @DisplayName("getPoster — unauthenticated (null auth) surfaces UnauthorizedException (→ 401)")
    void getPoster_unauthenticated_401() {
        assertThatThrownBy(() -> controller.getPoster(null, ROOM_ID, "2026-06"))
                .isInstanceOf(UnauthorizedException.class);
    }

    private Authentication authForViewer() {
        UserPrincipal principal = new UserPrincipal(VIEWER_USER_ID, "viewer@example.com");
        return new UsernamePasswordAuthenticationToken(principal, null);
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = findField(target.getClass(), name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> cursor = type;
        while (cursor != null) {
            try {
                return cursor.getDeclaredField(name);
            } catch (NoSuchFieldException ignore) {
                cursor = cursor.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
