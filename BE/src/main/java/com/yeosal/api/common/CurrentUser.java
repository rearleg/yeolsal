package com.yeosal.api.common;

import com.yeosal.api.auth.UserPrincipal;
import com.yeosal.api.user.User;
import com.yeosal.api.user.UserRepository;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component
public class CurrentUser {
    private final UserRepository users;

    public CurrentUser(UserRepository users) {
        this.users = users;
    }

    public User require(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            throw new UnauthorizedException("인증이 필요합니다.");
        }
        return users.findById(principal.id())
                .orElseThrow(() -> new UnauthorizedException("인증이 필요합니다."));
    }
}
