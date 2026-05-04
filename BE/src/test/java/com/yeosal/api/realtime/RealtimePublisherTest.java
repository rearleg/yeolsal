package com.yeosal.api.realtime;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.yeosal.api.room.chat.ChatMessageKind;
import com.yeosal.api.room.chat.ChatService;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * Pins the topic naming scheme + the user-queue convention. The chat
 * destination, member destination, and per-user notification queue are
 * what the FE subscribes against — changing any of these strings
 * silently breaks the wire, so the contract lives here.
 */
class RealtimePublisherTest {

    private final ChatService.MessageDto sampleMessage = new ChatService.MessageDto(
            1001L,
            42L,
            7L,
            ChatMessageKind.USER,
            "안녕",
            JsonNodeFactory.instance.objectNode(),
            Instant.parse("2026-05-04T03:00:00Z"));

    @Test
    @DisplayName("publishChatMessage routes to /topic/rooms.{id}.chat")
    void publishChatMessage_usesChatTopic() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        RealtimePublisher publisher = new RealtimePublisher(template);

        publisher.publishChatMessage(42L, sampleMessage);

        verify(template).convertAndSend("/topic/rooms.42.chat", (Object) sampleMessage);
    }

    @Test
    @DisplayName("publishMemberAdded routes to /topic/rooms.{id}.members")
    void publishMemberAdded_usesMembersTopic() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        RealtimePublisher publisher = new RealtimePublisher(template);

        RealtimeEvent payload = new RealtimeEvent("room.member.added", "Bob");
        publisher.publishMemberAdded(42L, payload);

        verify(template).convertAndSend("/topic/rooms.42.members", (Object) payload);
    }

    @Test
    @DisplayName("publishUserEvent routes to /user/{id}/queue/notifications via convertAndSendToUser")
    void publishUserEvent_usesUserQueue() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        RealtimePublisher publisher = new RealtimePublisher(template);

        RealtimeEvent event = new RealtimeEvent("FRIEND_REQUEST_RECEIVED", null);
        publisher.publishUserEvent(7L, event);

        verify(template).convertAndSendToUser("7", "/queue/notifications", (Object) event);
    }

    @Test
    @DisplayName("publish* swallows broker exceptions so a publish failure can never roll back the actor's transaction")
    void publish_swallowsBrokerExceptions() {
        SimpMessagingTemplate template = mock(SimpMessagingTemplate.class);
        doThrow(new MessagingException("broker down"))
                .when(template).convertAndSend(ArgumentMatchers.anyString(),
                        ArgumentMatchers.<Object>any());
        doThrow(new MessagingException("broker down"))
                .when(template).convertAndSendToUser(ArgumentMatchers.anyString(),
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.<Object>any());
        RealtimePublisher publisher = new RealtimePublisher(template);

        // None of these may throw — wired callers (ChatService, RoomService,
        // FriendService) are inside @Transactional methods. A broker hiccup
        // must NOT roll back the actor's primary write.
        publisher.publishChatMessage(42L, sampleMessage);
        publisher.publishMemberAdded(42L, new RealtimeEvent("room.member.added", null));
        publisher.publishUserEvent(7L, new RealtimeEvent("FRIEND_REQUEST_RECEIVED", null));

        // Sanity: the publisher did attempt to call the broker for each.
        verify(template, times(2))
                .convertAndSend(ArgumentMatchers.anyString(), ArgumentMatchers.<Object>any());
        verify(template, times(1))
                .convertAndSendToUser(ArgumentMatchers.anyString(),
                        ArgumentMatchers.anyString(),
                        ArgumentMatchers.<Object>any());
        // Counter-test: nothing leaked into the wrong direction (e.g. user
        // event must not reach a topic destination).
        verify(template, never()).convertAndSend(
                ArgumentMatchers.eq("/user/7/queue/notifications"),
                ArgumentMatchers.<Object>any());
    }
}
