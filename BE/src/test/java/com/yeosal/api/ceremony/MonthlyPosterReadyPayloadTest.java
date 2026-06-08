package com.yeosal.api.ceremony;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.YearMonth;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Story 7.2 — pins the {@link MonthlyPosterReadyPayload} wire shape. The
 * FE (Story 7.3) consumes the JSON exactly as serialised here, so a
 * silent field rename would silently break the wire — this test is the
 * contract.
 */
class MonthlyPosterReadyPayloadTest {

    @Test
    @DisplayName("of(roomId, YearMonth) builds payload with ISO YYYY-MM yearMonth")
    void factory_buildsIsoYearMonth() {
        MonthlyPosterReadyPayload payload =
                MonthlyPosterReadyPayload.of(42L, YearMonth.of(2026, 5));

        assertThat(payload.roomId()).isEqualTo(42L);
        assertThat(payload.yearMonth()).isEqualTo("2026-05");
    }

    @Test
    @DisplayName("constructor rejects null yearMonth")
    void constructor_rejectsNullYearMonth() {
        assertThatThrownBy(() -> new MonthlyPosterReadyPayload(42L, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("yearMonth");
    }

    @Test
    @DisplayName("JSON serialisation emits {roomId, yearMonth} field shape — STOMP wire contract")
    void jsonSerialisation_emitsExpectedShape() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        MonthlyPosterReadyPayload payload =
                MonthlyPosterReadyPayload.of(42L, YearMonth.of(2026, 5));

        String json = mapper.writeValueAsString(payload);

        assertThat(json).isEqualTo("{\"roomId\":42,\"yearMonth\":\"2026-05\"}");
    }
}
