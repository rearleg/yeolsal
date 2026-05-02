package com.yeosal.api.daily;

import com.yeosal.api.user.User;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DailyEntryRepository extends JpaRepository<DailyEntry, Long> {
    Optional<DailyEntry> findByUserAndDate(User user, LocalDate date);

    // Group-mode Today reads todos + reflection off these rows; eagerly load
    // them so the rendering loop doesn't trigger one lazy query per member.
    @EntityGraph(attributePaths = {"todos", "reflection"})
    List<DailyEntry> findByUserInAndDate(List<User> users, LocalDate date);

    List<DailyEntry> findByUserAndDateBetween(User user, LocalDate from, LocalDate to);

    /**
     * Batch fetch entries for multiple users with todos + reflection eagerly
     * loaded. Used by the friend feed so streak computation does not trigger
     * one lazy todos query per entry per friend.
     */
    @EntityGraph(attributePaths = {"todos", "reflection"})
    @Query("select e from DailyEntry e where e.user in :users and e.date between :from and :to")
    List<DailyEntry> findGrassEntriesByUsersBetween(
            @Param("users") Collection<User> users,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);

    @Query("select count(e) from DailyEntry e join e.reflection r where e.user = :user and e.date between :from and :to and r.submittedAt is not null")
    long countWithReflectionBetween(@Param("user") User user, @Param("from") LocalDate from, @Param("to") LocalDate to);
}
