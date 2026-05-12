package com.yeosal.api.survival;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.yeosal.api.realtime.RealtimePublisher;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class PendingRealtimeBroadcastDispatcherTest {

    private static final Instant NOW = Instant.parse("2026-05-12T03:14:15Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("UTC"));

    @Mock private PendingRealtimeBroadcastRepository pendingBroadcasts;
    @Mock private RealtimePublisher publisher;

    private ObjectMapper objectMapper;
    private PendingRealtimeBroadcastDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        // Mirror Spring Boot's auto-configured ObjectMapper: JavaTimeModule +
        // FAIL_ON_UNKNOWN_PROPERTIES=false. The persisted broadcast payload
        // carries an extra "eventKind" discriminator that the record doesn't
        // declare; Spring Boot's default tolerates it, so the test must too.
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        dispatcher = new PendingRealtimeBroadcastDispatcher(
                pendingBroadcasts, publisher, CLOCK, objectMapper);
    }

    @Test
    @DisplayName("drain: no due rows → no emit, no markEmitted")
    void drain_noDueRows_isNoop() {
        when(pendingBroadcasts.findDueForEmission(eq(NOW), any(Pageable.class)))
                .thenReturn(List.of());

        dispatcher.drain();

        verify(publisher, never()).publishSurvivalStateBroadcast(anyLong(), any());
        verify(pendingBroadcasts, never()).markEmitted(anyLong(), any());
    }

    @Test
    @DisplayName("drain: due row + broker success → publish + markEmitted with reconstructed payload")
    void drain_dueRow_emitsAndMarks() {
        PendingRealtimeBroadcast row = makeRow(101L, payloadFor(42L, 7L));
        when(pendingBroadcasts.findDueForEmission(eq(NOW), any(Pageable.class)))
                .thenReturn(List.of(row));
        when(publisher.publishSurvivalStateBroadcast(eq(42L), any()))
                .thenReturn(true);

        dispatcher.drain();

        ArgumentCaptor<SurvivalStateChangePayload> payloadCap =
                ArgumentCaptor.forClass(SurvivalStateChangePayload.class);
        verify(publisher).publishSurvivalStateBroadcast(eq(42L), payloadCap.capture());
        SurvivalStateChangePayload payload = payloadCap.getValue();
        assertThat(payload.roomId()).isEqualTo(42L);
        assertThat(payload.userId()).isEqualTo(7L);
        assertThat(payload.fromStatus()).isEqualTo(SurvivalStatus.YELLOW);
        assertThat(payload.toStatus()).isEqualTo(SurvivalStatus.RED);
        assertThat(payload.occurredAt())
                .isEqualTo(Instant.parse("2026-05-11T03:14:15Z"));
        assertThat(payload.eliminatedAt())
                .isEqualTo(Instant.parse("2026-05-11T03:14:15Z"));
        assertThat(payload.broadVisibilityAt())
                .isEqualTo(Instant.parse("2026-05-12T03:14:15Z"));

        verify(pendingBroadcasts).markEmitted(101L, NOW);
    }

    @Test
    @DisplayName("drain: due row + broker hiccup (publish returns false) → markEmitted NOT called")
    void drain_brokerFailure_doesNotMarkEmitted() {
        PendingRealtimeBroadcast row = makeRow(101L, payloadFor(42L, 7L));
        when(pendingBroadcasts.findDueForEmission(eq(NOW), any(Pageable.class)))
                .thenReturn(List.of(row));
        when(publisher.publishSurvivalStateBroadcast(eq(42L), any()))
                .thenReturn(false);

        dispatcher.drain();

        verify(publisher).publishSurvivalStateBroadcast(eq(42L), any());
        verify(pendingBroadcasts, never()).markEmitted(anyLong(), any());
    }

    @Test
    @DisplayName("drain: malformed JSON payload → no publish, no markEmitted (row retries on next tick)")
    void drain_malformedPayload_doesNotMark() {
        ObjectNode bad = JsonNodeFactory.instance.objectNode();
        // "NOT_A_REAL_STATUS" cannot be coerced to SurvivalStatus — treeToValue throws.
        bad.put("fromStatus", "NOT_A_REAL_STATUS");
        PendingRealtimeBroadcast row = makeRow(101L, bad);
        when(pendingBroadcasts.findDueForEmission(eq(NOW), any(Pageable.class)))
                .thenReturn(List.of(row));

        dispatcher.drain();

        verify(publisher, never()).publishSurvivalStateBroadcast(anyLong(), any());
        verify(pendingBroadcasts, never()).markEmitted(anyLong(), any());
    }

    @Test
    @DisplayName("drain: mixed batch — success row marked, failure row left for retry")
    void drain_mixedBatch_marksOnlySuccessfulRows() {
        PendingRealtimeBroadcast okRow = makeRow(101L, payloadFor(42L, 7L));
        PendingRealtimeBroadcast hiccupRow = makeRow(102L, payloadFor(43L, 8L));
        when(pendingBroadcasts.findDueForEmission(eq(NOW), any(Pageable.class)))
                .thenReturn(List.of(okRow, hiccupRow));
        when(publisher.publishSurvivalStateBroadcast(eq(42L), any())).thenReturn(true);
        when(publisher.publishSurvivalStateBroadcast(eq(43L), any())).thenReturn(false);

        dispatcher.drain();

        verify(pendingBroadcasts).markEmitted(101L, NOW);
        verify(pendingBroadcasts, never()).markEmitted(eq(102L), any());
    }

    // ----- helpers -----

    private PendingRealtimeBroadcast makeRow(long id, JsonNode payload) {
        PendingRealtimeBroadcast row = new PendingRealtimeBroadcast(NOW, payload);
        try {
            Field idField = PendingRealtimeBroadcast.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(row, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return row;
    }

    private JsonNode payloadFor(long roomId, long userId) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("roomId", roomId);
        node.put("userId", userId);
        node.put("fromStatus", "YELLOW");
        node.put("toStatus", "RED");
        node.put("occurredAt", "2026-05-11T03:14:15Z");
        node.put("eliminatedAt", "2026-05-11T03:14:15Z");
        node.put("broadVisibilityAt", "2026-05-12T03:14:15Z");
        node.put("eventKind", "SURVIVAL_STATE_CHANGE");
        return node;
    }
}
