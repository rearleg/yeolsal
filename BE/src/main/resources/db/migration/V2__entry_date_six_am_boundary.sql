-- Phase 4-B: recompute daily_entries.entry_date under the 06:00 day boundary.
--
-- Rule: an entry written at local time before 06:00 belongs to the previous
-- calendar date. Going forward, DailyService.currentEntryDate() applies the
-- same shift via EntryDateResolver. This migration backfills history.
--
-- Safety: the unique (user_id, entry_date) constraint can collide if a user
-- somehow recorded two entries for what would now be the same shifted date.
-- We pre-detect collisions; if any exist the migration ABORTS with a clear
-- error so an operator can resolve them manually before re-running.

DO $$
DECLARE
    v_collision_count INT;
BEGIN
    SELECT COUNT(*)
    INTO v_collision_count
    FROM (
        SELECT
            de.user_id,
            ((de.created_at AT TIME ZONE COALESCE(u.timezone, 'Asia/Seoul')) - INTERVAL '6 hours')::date AS shifted_date
        FROM daily_entries de
        JOIN users u ON u.id = de.user_id
        GROUP BY de.user_id, shifted_date
        HAVING COUNT(*) > 1
    ) AS collisions;

    IF v_collision_count > 0 THEN
        RAISE EXCEPTION
            'V2 backfill aborted: % (user_id, shifted_entry_date) collisions detected. Resolve manually before retrying.',
            v_collision_count;
    END IF;
END;
$$;

UPDATE daily_entries de
SET entry_date = ((de.created_at AT TIME ZONE COALESCE(u.timezone, 'Asia/Seoul')) - INTERVAL '6 hours')::date
FROM users u
WHERE u.id = de.user_id
  AND de.entry_date <> ((de.created_at AT TIME ZONE COALESCE(u.timezone, 'Asia/Seoul')) - INTERVAL '6 hours')::date;
