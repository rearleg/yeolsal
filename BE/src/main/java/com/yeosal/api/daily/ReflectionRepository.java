package com.yeosal.api.daily;

import com.yeosal.api.user.User;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReflectionRepository extends JpaRepository<Reflection, Long> {
    Optional<Reflection> findByDailyEntry(DailyEntry dailyEntry);

    @Query("""
            select r from Reflection r
            where r.dailyEntry.user = :user
            order by r.submittedAt desc
            """)
    List<Reflection> findRecentByUser(@Param("user") User user, Pageable pageable);
}
