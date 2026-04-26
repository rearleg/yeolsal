package com.yeosal.api.user;

import java.time.Instant;

public record User(
        Long id,
        String email,
        String nickname,
        AuthProvider authProvider,
        String timezone,
        Instant createdAt
) {}
