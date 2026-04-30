package com.yeosal.api.notification;

import com.yeosal.api.user.User;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {

    boolean existsByUserAndKindAndKey(User user, NotificationKind kind, String key);

    @Query("""
            select n from NotificationLog n
            where n.user = :user and n.kind = :kind
            order by n.sentAt desc
            """)
    List<NotificationLog> findRecent(@Param("user") User user,
                                     @Param("kind") NotificationKind kind,
                                     Pageable pageable);

    default Optional<NotificationLog> findLatest(User user, NotificationKind kind) {
        return findRecent(user, kind, Pageable.ofSize(1)).stream().findFirst();
    }
}
