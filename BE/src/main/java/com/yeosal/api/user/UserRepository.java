package com.yeosal.api.user;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);

    /**
     * Story 3.1 — atomic check-and-set for the lifetime-one free revival
     * ticket (Architecture §4.12). Flips
     * {@code free_revival_ticket_used} from {@code false} to {@code true}
     * iff the current value is {@code false}, and returns the row count
     * so the service can branch:
     *
     * <ul>
     *   <li>{@code 1} — the ticket was newly consumed; proceed with the
     *       FREE_TICKET revival flow.</li>
     *   <li>{@code 0} — the ticket was already used in a parallel session
     *       (race) or in another room. The service surfaces this as
     *       {@code 400 FREE_TICKET_ALREADY_USED}.</li>
     * </ul>
     *
     * <p>Atomicity matters because the FREE_TICKET path runs inside the
     * advisory-locked transaction but the user-level flag is per-account
     * (not per-elimination), so the lock alone does not prevent two
     * concurrent revivals across DIFFERENT eliminations from both
     * consuming the flag.
     */
    @Modifying
    @Query("update User u set u.freeRevivalTicketUsed = true "
            + "where u.id = :userId and u.freeRevivalTicketUsed = false")
    int markFreeTicketUsed(@Param("userId") long userId);
}
