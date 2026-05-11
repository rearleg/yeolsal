package com.yeosal.api.survival;

import com.yeosal.api.room.Room;
import com.yeosal.api.user.User;
import java.time.Duration;
import java.time.Instant;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Survival-state lifecycle service (Architecture §4.1, PRD FR-8.1.*).
 *
 * <h2>AC3/AC4 grace contract — DO NOT REGRESS (Story 1.1 → Story 1.2)</h2>
 *
 * <p>Story 1.1 only mints rows. The daily evaluator in Story 1.2 is the
 * only legitimate caller of any future transition method, and it MUST gate
 * every YELLOW→RED transition behind {@link #inGraceWindow(SurvivalState, Instant)}:
 *
 * <ul>
 *   <li>If {@code inGraceWindow(state, now)} returns {@code true}, the only
 *       legal transition for a missed-rule day is {@code ACTIVE → YELLOW}.
 *       {@code YELLOW → RED} is forbidden while in grace.</li>
 *   <li>If {@code inGraceWindow(state, now)} returns {@code false} (legacy
 *       {@code grace_ends_at == null} rows or {@code now >= grace_ends_at}),
 *       the member is fully subject to the rolling 7-day window — Story 1.2
 *       may transition {@code YELLOW → RED}.</li>
 * </ul>
 *
 * <p>The boundary is <em>exclusive</em>: at the exact instant
 * {@code now == grace_ends_at}, the guard reports out-of-grace. This matches
 * the contract asserted by {@code SurvivalStateServiceTest}.
 */
@Service
public class SurvivalStateService {

    private static final Duration GRACE_WINDOW = Duration.ofDays(14);

    private final SurvivalStateRepository repository;

    public SurvivalStateService(SurvivalStateRepository repository) {
        this.repository = repository;
    }

    /**
     * Mints the {@code survival_state} row for a fresh (room, user) join.
     * Status defaults to {@link SurvivalStatus#ACTIVE}; {@code grace_ends_at}
     * is {@code joinedAt + 14 days}.
     *
     * <p>Idempotent under the unique {@code (room_id, user_id)} race: when
     * two concurrent {@code save} calls collide we let the loser swallow the
     * {@link DataIntegrityViolationException} and re-read the winner's row,
     * so callers can stay single-transaction without an explicit advisory lock.
     */
    @Transactional
    public SurvivalState initializeOnJoin(Room room, User user, Instant joinedAt) {
        Instant graceEndsAt = joinedAt.plus(GRACE_WINDOW);
        SurvivalState row = new SurvivalState(room, user, graceEndsAt);
        try {
            return repository.save(row);
        } catch (DataIntegrityViolationException race) {
            return repository
                    .findByRoomIdAndUserId(room.getId(), user.getId())
                    .orElseThrow(() -> race);
        }
    }

    /**
     * Returns {@code true} iff the member is still inside the 14-day grace
     * window — i.e. the row carries a non-null {@code grace_ends_at} and
     * {@code now} is strictly before it. The boundary is exclusive so a
     * member whose grace ends exactly at {@code now} is already exposed to
     * Story 1.2's full transition rules.
     *
     * <p>See the AC3/AC4 contract in the class-level Javadoc — Story 1.2's
     * evaluator must consult this method before any {@code YELLOW → RED}
     * transition.
     */
    public boolean inGraceWindow(SurvivalState state, Instant now) {
        Instant graceEndsAt = state.getGraceEndsAt();
        return graceEndsAt != null && now.isBefore(graceEndsAt);
    }
}
