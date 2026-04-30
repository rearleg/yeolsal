package com.yeosal.api.notification;

import com.yeosal.api.user.User;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PushTokenRepository extends JpaRepository<PushToken, Long> {

    List<PushToken> findByUser(User user);

    Optional<PushToken> findByUserAndToken(User user, String token);
}
