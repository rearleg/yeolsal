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

    /**
     * Story 2.2 — count daily entries authored on {@code entryDate} by any member
     * of {@code roomId}. The join goes through {@code room_members} because
     * {@code daily_entries} has no {@code room_id} column (entries are
     * room-agnostic and counted per room only at aggregation time). Powers the
     * spectator daily-digest aggregator.
     */
    @Query("""
            select count(e) from DailyEntry e
            where e.date = :entryDate
              and e.user.id in (
                select rm.user.id from com.yeosal.api.room.RoomMember rm
                where rm.room.id = :roomId
              )
            """)
    long countByEntryDateAndRoomId(
            @Param("entryDate") LocalDate entryDate,
            @Param("roomId") long roomId);
}
