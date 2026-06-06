package com.yeosal.api.kakaoshare;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.yeosal.api.room.RoomInvite;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ShareUrlBuilderTest {

    private RoomInvite inviteWith(String code) {
        RoomInvite invite = mock(RoomInvite.class);
        when(invite.getCode()).thenReturn(code);
        return invite;
    }

    @Test
    @DisplayName("default bases produce the documented deeplink + preview-card URLs")
    void defaultBases_emitDocumentedUrls() {
        ShareUrlBuilder builder = new ShareUrlBuilder(
                "https://yeolsal.app", "https://api.rearleg.com/yeolsal");

        assertThat(builder.kakaoShareUrl(inviteWith("A7K9PXMQ")))
                .isEqualTo("https://yeolsal.app/join?code=A7K9PXMQ");
        assertThat(builder.previewCardImageUrl(42L))
                .isEqualTo("https://api.rearleg.com/yeolsal/api/v1/rooms/42/invites/preview-card");
    }

    @Test
    @DisplayName("trailing slash on either base is stripped once at construction")
    void trailingSlash_isStripped() {
        ShareUrlBuilder builder = new ShareUrlBuilder(
                "https://yeolsal.app/", "https://api.rearleg.com/yeolsal/");

        assertThat(builder.kakaoShareUrl(inviteWith("ABC123")))
                .isEqualTo("https://yeolsal.app/join?code=ABC123");
        assertThat(builder.previewCardImageUrl(7L))
                .isEqualTo("https://api.rearleg.com/yeolsal/api/v1/rooms/7/invites/preview-card");
    }

    @Test
    @DisplayName("env override changes both bases independently")
    void envOverride_changesBothBases() {
        ShareUrlBuilder builder = new ShareUrlBuilder(
                "https://staging.example.com", "https://stage-api.example.com/yeolsal");

        assertThat(builder.kakaoShareUrl(inviteWith("XYZ987")))
                .isEqualTo("https://staging.example.com/join?code=XYZ987");
        assertThat(builder.previewCardImageUrl(99L))
                .isEqualTo("https://stage-api.example.com/yeolsal/api/v1/rooms/99/invites/preview-card");
    }

    @Test
    @DisplayName("invite code is forwarded into the query verbatim (no URL-encoding here)")
    void code_isForwardedVerbatim() {
        ShareUrlBuilder builder = new ShareUrlBuilder(
                "https://yeolsal.app", "https://api.rearleg.com/yeolsal");

        assertThat(builder.kakaoShareUrl(inviteWith("aA1zZ9")))
                .isEqualTo("https://yeolsal.app/join?code=aA1zZ9");
    }
}
