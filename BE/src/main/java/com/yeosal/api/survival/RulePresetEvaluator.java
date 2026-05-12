package com.yeosal.api.survival;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * Compliance-preset interpreter used by {@link SurvivalStateService#evaluateRoom}.
 * Package-private and stateless — not a Spring bean — so it can be called
 * from inside the evaluator's hot loop without introducing another DI seam.
 *
 * <p>v1 supports only the {@code DAILY_UPDATE} preset; the only knob is
 * {@code weekendInclude}, which when {@code false} skips Sat/Sun (KST).
 * Future presets land here; keep the switch tight.
 */
final class RulePresetEvaluator {

    private RulePresetEvaluator() {}

    /**
     * Returns {@code true} iff {@code d} (already in KST) is a day the rule
     * actually evaluates. A {@code false} result means "weekend skip" —
     * no progress, no miss, no notification row (Story 1.2 AC2).
     *
     * <p>The default for a missing {@code weekendInclude} key is {@code true}
     * (mirrors V11 step 14 backfill where {@code weekendInclude: true} is the
     * default). A {@code null} payload defensively returns {@code true} —
     * the V11 backfill guarantees a non-null row, so a null here is a
     * data-shape bug the evaluator should let pass to surface elsewhere.
     */
    static boolean shouldEvaluate(JsonNode rulePayload, LocalDate d) {
        if (rulePayload == null) {
            return true;
        }
        boolean weekendInclude = rulePayload.path("weekendInclude").asBoolean(true);
        if (weekendInclude) {
            return true;
        }
        DayOfWeek dow = d.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
    }
}
