package com.yeosal.api.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yeosal.api.auth.JwtService;
import com.yeosal.api.auth.UserPrincipal;
import com.yeosal.api.room.RoomMemberRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.core.Authentication;

/**
 * STOMP auth has the same security weight as a regular HTTP filter — a
 * bug here lets unauthenticated clients subscribe to any user's chat
 * room. Each test pins one specific deny path so a refactor that
 * accidentally relaxes a check fails loudly here.
 */
class JwtChannelInterceptorTest {

    private final MessageChannel channel = mock(MessageChannel.class);

    @Test
    @DisplayName("CONNECT with valid Bearer token: sets Authentication on accessor and lets the message through")
    void connect_validToken_authenticates() {
        JwtService jwtService = mock(JwtService.class);
        RoomMemberRepository roomMembers = mock(RoomMemberRepository.class);
        when(jwtService.parse("good-token"))
                .thenReturn(new UserPrincipal(7L, "alice@example.com"));
        JwtChannelInterceptor interceptor = new JwtChannelInterceptor(jwtService, roomMembers);

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.addNativeHeader("Authorization", "Bearer good-token");
        Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(message, channel);

        assertThat(result).as("valid CONNECT must propagate").isNotNull();
        StompHeaderAccessor out = StompHeaderAccessor.wrap(result);
        assertThat(out.getUser())
                .as("authenticated principal must be attached so SUBSCRIBE can see it")
                .isNotNull();
        Authentication auth = (Authentication) out.getUser();
        assertThat(((UserPrincipal) auth.getPrincipal()).id()).isEqualTo(7L);
    }

    @Test
    @DisplayName("CONNECT without Authorization header is rejected (returns null)")
    void connect_missingHeader_isRejected() {
        JwtService jwtService = mock(JwtService.class);
        RoomMemberRepository roomMembers = mock(RoomMemberRepository.class);
        JwtChannelInterceptor interceptor = new JwtChannelInterceptor(jwtService, roomMembers);

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(message, channel);

        assertThat(result).as("anonymous CONNECT must be denied").isNull();
        verify(jwtService, never()).parse(any());
    }

    @Test
    @DisplayName("CONNECT with malformed (non-Bearer) Authorization is rejected")
    void connect_nonBearerScheme_isRejected() {
        JwtService jwtService = mock(JwtService.class);
        RoomMemberRepository roomMembers = mock(RoomMemberRepository.class);
        JwtChannelInterceptor interceptor = new JwtChannelInterceptor(jwtService, roomMembers);

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.addNativeHeader("Authorization", "Basic abc");
        Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(message, channel);

        assertThat(result).isNull();
        verify(jwtService, never()).parse(any());
    }

    @Test
    @DisplayName("CONNECT with invalid/expired JWT is rejected (parse throws)")
    void connect_invalidJwt_isRejected() {
        JwtService jwtService = mock(JwtService.class);
        RoomMemberRepository roomMembers = mock(RoomMemberRepository.class);
        when(jwtService.parse("bad-token")).thenThrow(new RuntimeException("expired"));
        JwtChannelInterceptor interceptor = new JwtChannelInterceptor(jwtService, roomMembers);

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.addNativeHeader("Authorization", "Bearer bad-token");
        Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(message, channel);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("SUBSCRIBE to /topic/rooms.{id}.chat as room member: allowed")
    void subscribe_chatTopic_member_allowed() {
        JwtService jwtService = mock(JwtService.class);
        RoomMemberRepository roomMembers = mock(RoomMemberRepository.class);
        when(roomMembers.existsByRoomIdAndUserId(42L, 7L)).thenReturn(true);
        JwtChannelInterceptor interceptor = new JwtChannelInterceptor(jwtService, roomMembers);

        Message<?> result = interceptor.preSend(
                buildSubscribe("/topic/rooms.42.chat", 7L), channel);

        assertThat(result).isNotNull();
        verify(roomMembers).existsByRoomIdAndUserId(42L, 7L);
    }

    @Test
    @DisplayName("SUBSCRIBE to /topic/rooms.{id}.members as room member: allowed")
    void subscribe_membersTopic_member_allowed() {
        JwtService jwtService = mock(JwtService.class);
        RoomMemberRepository roomMembers = mock(RoomMemberRepository.class);
        when(roomMembers.existsByRoomIdAndUserId(42L, 7L)).thenReturn(true);
        JwtChannelInterceptor interceptor = new JwtChannelInterceptor(jwtService, roomMembers);

        Message<?> result = interceptor.preSend(
                buildSubscribe("/topic/rooms.42.members", 7L), channel);

        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("SUBSCRIBE to /topic/rooms.{id}.posters as room member: allowed")
    void subscribe_postersTopic_member_allowed() {
        JwtService jwtService = mock(JwtService.class);
        RoomMemberRepository roomMembers = mock(RoomMemberRepository.class);
        when(roomMembers.existsByRoomIdAndUserId(42L, 7L)).thenReturn(true);
        JwtChannelInterceptor interceptor = new JwtChannelInterceptor(jwtService, roomMembers);

        Message<?> result = interceptor.preSend(
                buildSubscribe("/topic/rooms.42.posters", 7L), channel);

        assertThat(result).isNotNull();
        verify(roomMembers).existsByRoomIdAndUserId(42L, 7L);
    }

    @Test
    @DisplayName("SUBSCRIBE to /topic/rooms.{id}.chat as NON-member: rejected (CRITICAL)")
    void subscribe_chatTopic_nonMember_rejected() {
        JwtService jwtService = mock(JwtService.class);
        RoomMemberRepository roomMembers = mock(RoomMemberRepository.class);
        when(roomMembers.existsByRoomIdAndUserId(42L, 7L)).thenReturn(false);
        JwtChannelInterceptor interceptor = new JwtChannelInterceptor(jwtService, roomMembers);

        Message<?> result = interceptor.preSend(
                buildSubscribe("/topic/rooms.42.chat", 7L), channel);

        // Pinning the most security-critical deny path. A bug that relaxes
        // the membership check turns the chat into a public broadcast.
        assertThat(result).as("non-member must NOT be able to subscribe to a room").isNull();
    }

    @Test
    @DisplayName("SUBSCRIBE to /topic/rooms.{id}.members as NON-member: rejected")
    void subscribe_membersTopic_nonMember_rejected() {
        JwtService jwtService = mock(JwtService.class);
        RoomMemberRepository roomMembers = mock(RoomMemberRepository.class);
        when(roomMembers.existsByRoomIdAndUserId(42L, 7L)).thenReturn(false);
        JwtChannelInterceptor interceptor = new JwtChannelInterceptor(jwtService, roomMembers);

        Message<?> result = interceptor.preSend(
                buildSubscribe("/topic/rooms.42.members", 7L), channel);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("SUBSCRIBE to /topic/rooms.{id}.posters as NON-member: rejected")
    void subscribe_postersTopic_nonMember_rejected() {
        JwtService jwtService = mock(JwtService.class);
        RoomMemberRepository roomMembers = mock(RoomMemberRepository.class);
        when(roomMembers.existsByRoomIdAndUserId(42L, 7L)).thenReturn(false);
        JwtChannelInterceptor interceptor = new JwtChannelInterceptor(jwtService, roomMembers);

        Message<?> result = interceptor.preSend(
                buildSubscribe("/topic/rooms.42.posters", 7L), channel);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("SUBSCRIBE without an authenticated principal is rejected even if destination format is valid")
    void subscribe_anonymous_rejected() {
        JwtService jwtService = mock(JwtService.class);
        RoomMemberRepository roomMembers = mock(RoomMemberRepository.class);
        JwtChannelInterceptor interceptor = new JwtChannelInterceptor(jwtService, roomMembers);

        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/topic/rooms.42.chat");
        // No setUser() — simulates a CONNECT-stripped or forged frame.
        Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        Message<?> result = interceptor.preSend(message, channel);

        assertThat(result).isNull();
        verify(roomMembers, never()).existsByRoomIdAndUserId(anyLong(), anyLong());
    }

    @Test
    @DisplayName("SUBSCRIBE to an unknown destination pattern is rejected (deny-by-default)")
    void subscribe_unknownDestination_rejected() {
        JwtService jwtService = mock(JwtService.class);
        RoomMemberRepository roomMembers = mock(RoomMemberRepository.class);
        JwtChannelInterceptor interceptor = new JwtChannelInterceptor(jwtService, roomMembers);

        Message<?> result = interceptor.preSend(
                buildSubscribe("/topic/all-rooms", 7L), channel);

        assertThat(result).as("deny-by-default: only known destination patterns may be subscribed to").isNull();
    }

    @Test
    @DisplayName("SUBSCRIBE to /user/queue/notifications as authenticated user: allowed (no membership check)")
    void subscribe_userQueue_authenticated_allowed() {
        JwtService jwtService = mock(JwtService.class);
        RoomMemberRepository roomMembers = mock(RoomMemberRepository.class);
        JwtChannelInterceptor interceptor = new JwtChannelInterceptor(jwtService, roomMembers);

        Message<?> result = interceptor.preSend(
                buildSubscribe("/user/queue/notifications", 7L), channel);

        assertThat(result).isNotNull();
        verify(roomMembers, never()).existsByRoomIdAndUserId(anyLong(), anyLong());
    }

    @Test
    @DisplayName("Non-CONNECT, non-SUBSCRIBE frames pass through untouched (e.g. UNSUBSCRIBE, DISCONNECT, SEND)")
    void otherCommands_passThrough() {
        JwtService jwtService = mock(JwtService.class);
        RoomMemberRepository roomMembers = mock(RoomMemberRepository.class);
        JwtChannelInterceptor interceptor = new JwtChannelInterceptor(jwtService, roomMembers);

        for (StompCommand command : new StompCommand[] {
                StompCommand.UNSUBSCRIBE, StompCommand.DISCONNECT, StompCommand.SEND}) {
            StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
            Message<?> message = MessageBuilder.createMessage(
                    new byte[0], accessor.getMessageHeaders());
            assertThat(interceptor.preSend(message, channel))
                    .as("command %s must propagate", command)
                    .isNotNull();
        }
    }

    private static Message<?> buildSubscribe(String destination, long userId) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        accessor.setUser(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                new UserPrincipal(userId, "user@example.com"),
                null,
                java.util.List.of()));
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
