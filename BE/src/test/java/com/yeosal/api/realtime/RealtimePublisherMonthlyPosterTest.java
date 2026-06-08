package com.yeosal.api.realtime;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.yeosal.api.ceremony.MonthlyPosterReadyPayload;
import java.time.YearMonth;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * Story 7.2 — pins the {@code /topic/rooms.{id}.posters} destination
 * naming and the broker-error-swallow invariant for the Final-3 poster
 * publish surface. Lives in its own file so the diff to
 * {@link RealtimePublisherTest} stays empty (AC9 scope fence).
 */
class RealtimePublisherMonthlyPosterTest {

    private final MonthlyPosterReadyPayload payload =
            MonthlyPosterReadyPayload.of(42L, YearMonth.of(2026, 5));

    @Test
    @DisplayName("publishMonthlyPosterReady routes to /topic/rooms.{id}.posters")
    void publish_usesPostersTopic() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        RealtimePublisher publisher = new RealtimePublisher(template);

        publisher.publishMonthlyPosterReady(42L, payload);

        verify(template).convertAndSend("/topic/rooms.42.posters", (Object) payload);
    }

    @Test
    @DisplayName("publishMonthlyPosterReady swallows broker exceptions so a publish hiccup never rolls back the job's transaction")
    void publish_swallowsBrokerExceptions() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        doThrow(new MessagingException("broker down"))
                .when(template).convertAndSend(anyString(), any(Object.class));
        RealtimePublisher publisher = new RealtimePublisher(template);

        assertThatCode(() -> publisher.publishMonthlyPosterReady(42L, payload))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("destination format guard — extreme roomId values still emit well-formed topic strings")
    void destinationFormat_handlesExtremeRoomIds() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        RealtimePublisher publisher = new RealtimePublisher(template);

        publisher.publishMonthlyPosterReady(0L, payload);
        publisher.publishMonthlyPosterReady(Long.MAX_VALUE, payload);

        verify(template).convertAndSend("/topic/rooms.0.posters", (Object) payload);
        verify(template).convertAndSend(
                "/topic/rooms." + Long.MAX_VALUE + ".posters", (Object) payload);
    }
}
