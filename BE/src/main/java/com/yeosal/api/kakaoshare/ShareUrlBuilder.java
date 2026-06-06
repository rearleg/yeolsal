package com.yeosal.api.kakaoshare;

import com.yeosal.api.room.RoomInvite;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Builds the deeplink + preview-card URLs included in the createInvite
 * response (Story 6.1 AC1, FR-8.6.1). Trailing slashes on the base URLs are
 * stripped once at construction so concatenation is safe across env values.
 */
@Component
public class ShareUrlBuilder {

    private final String deeplinkBase;
    private final String previewCardBase;

    public ShareUrlBuilder(
            @Value("${yeosal.share.deeplink-base:https://yeolsal.app}") String deeplinkBase,
            @Value("${yeosal.share.preview-card-base:https://api.rearleg.com/yeolsal}") String previewCardBase) {
        this.deeplinkBase = stripTrailingSlash(deeplinkBase);
        this.previewCardBase = stripTrailingSlash(previewCardBase);
    }

    public String kakaoShareUrl(RoomInvite invite) {
        return deeplinkBase + "/join?code=" + invite.getCode();
    }

    public String previewCardImageUrl(long roomId) {
        return previewCardBase + "/api/v1/rooms/" + roomId + "/invites/preview-card";
    }

    private static String stripTrailingSlash(String s) {
        if (s == null || s.isEmpty()) return s;
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
