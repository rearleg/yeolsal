package com.yeosal.api.notification;

import com.yeosal.api.user.User;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    /**
     * Race-safe idempotency gate keyed on the {@code (user_id, kind, key)}
     * unique constraint. The daily evaluator uses {@code "SURVIVAL_STATE"} as
     * the kind and {@code "{prior_entry_date}:{user_id}"} as the key — a
     * single won race lets the service know it owns the user's evaluation
     * for that date (Story 1.2 AC3/AC8).
     *
     * <p>Returns 1 when the row was actually inserted, 0 when the unique
     * constraint short-circuited. Caller branches on the returned int.
     */
    @Modifying
    @Query(value = """
            insert into notification_log (user_id, kind, key)
            values (:userId, :kind, :key)
            on conflict (user_id, kind, key) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(
            @Param("userId") long userId,
            @Param("kind") String kind,
            @Param("key") String key);
}
