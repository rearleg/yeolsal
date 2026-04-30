package com.yeosal.api.daily;

import org.springframework.stereotype.Component;

/**
 * Locked grass rule for 열살방.
 *
 * <pre>
 *   L0 — goal OR reflection missing (regardless of todo count) → empty cell
 *   L1 — goal + reflection present, 0 todos done
 *   L2 — goal + reflection present, 1-2 todos done
 *   L3 — goal + reflection present, 3-4 todos done
 *   L4 — goal + reflection present, 5+ todos done
 * </pre>
 *
 * Mirrors the FE-side {@code lib/bucket.ts}. Stateless and deterministic.
 */
@Component
public class GateRule {
    public int bucket(boolean goalSet, boolean reflectionSubmitted, int completedTodoCount) {
        if (!goalSet || !reflectionSubmitted) {
            return 0;
        }
        int n = Math.max(0, completedTodoCount);
        if (n == 0) {
            return 1;
        }
        if (n <= 2) {
            return 2;
        }
        if (n <= 4) {
            return 3;
        }
        return 4;
    }
}
