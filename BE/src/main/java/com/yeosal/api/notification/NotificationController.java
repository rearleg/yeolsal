package com.yeosal.api.notification;

import com.yeosal.api.common.ApiResponse;
import com.yeosal.api.common.CurrentUser;
import com.yeosal.api.common.ForbiddenException;
import com.yeosal.api.common.NotFoundException;
import com.yeosal.api.user.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
public class NotificationController {

    private final NotificationService service;
    private final NotificationPrefRepository prefs;
    private final PushTokenRepository pushTokens;
    private final CurrentUser currentUser;

    public NotificationController(
            NotificationService service,
            NotificationPrefRepository prefs,
            PushTokenRepository pushTokens,
            CurrentUser currentUser
    ) {
        this.service = service;
        this.prefs = prefs;
        this.pushTokens = pushTokens;
        this.currentUser = currentUser;
    }

    @GetMapping("/notification-prefs")
    public ApiResponse<NotificationPrefDto> getPrefs(Authentication auth) {
        User me = currentUser.require(auth);
        return ApiResponse.of(NotificationPrefDto.from(service.getOrCreatePref(me)));
    }

    @PutMapping("/notification-prefs")
    public ApiResponse<NotificationPrefDto> updatePrefs(Authentication auth, @Valid @RequestBody UpdatePrefsRequest body) {
        User me = currentUser.require(auth);
        NotificationPref pref = service.getOrCreatePref(me);
        pref.setGoalNudgeEnabled(body.goalNudgeEnabled());
        pref.setReflectionNudgeEnabled(body.reflectionNudgeEnabled());
        pref.setEventHooksEnabled(body.eventHooksEnabled());
        pref.setQuietStartHour((short) body.quietStartHour());
        pref.setQuietEndHour((short) body.quietEndHour());
        return ApiResponse.of(NotificationPrefDto.from(prefs.save(pref)));
    }

    @PostMapping("/push-tokens")
    public ApiResponse<PushTokenDto> registerToken(Authentication auth, @Valid @RequestBody RegisterTokenRequest body) {
        User me = currentUser.require(auth);
        PushToken token = pushTokens.findByUserAndToken(me, body.token())
                .orElseGet(() -> new PushToken(me, body.token(), body.platform()));
        token.touch(Instant.now());
        return ApiResponse.of(PushTokenDto.from(pushTokens.save(token)));
    }

    @DeleteMapping("/push-tokens/{id}")
    public ResponseEntity<Void> deleteToken(Authentication auth, @PathVariable long id) {
        User me = currentUser.require(auth);
        PushToken token = pushTokens.findById(id)
                .orElseThrow(() -> new NotFoundException("푸시 토큰을 찾을 수 없습니다."));
        if (!token.getUser().getId().equals(me.getId())) {
            throw new ForbiddenException("토큰 삭제 권한이 없습니다.");
        }
        pushTokens.delete(token);
        return ResponseEntity.noContent().build();
    }

    public record UpdatePrefsRequest(
            boolean goalNudgeEnabled,
            boolean reflectionNudgeEnabled,
            boolean eventHooksEnabled,
            @Min(0) @Max(23) int quietStartHour,
            @Min(0) @Max(23) int quietEndHour
    ) {}

    public record RegisterTokenRequest(
            @NotBlank @Size(max = 255) String token,
            @NotBlank @Size(max = 20) String platform
    ) {}

    public record NotificationPrefDto(
            boolean goalNudgeEnabled,
            boolean reflectionNudgeEnabled,
            boolean eventHooksEnabled,
            int quietStartHour,
            int quietEndHour
    ) {
        static NotificationPrefDto from(NotificationPref p) {
            return new NotificationPrefDto(
                    p.isGoalNudgeEnabled(),
                    p.isReflectionNudgeEnabled(),
                    p.isEventHooksEnabled(),
                    p.getQuietStartHour(),
                    p.getQuietEndHour()
            );
        }
    }

    public record PushTokenDto(long id, String platform) {
        static PushTokenDto from(PushToken t) {
            return new PushTokenDto(t.getId(), t.getPlatform());
        }
    }
}
