package com.yeosal.api.friend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.yeosal.api.daily.DailyEntryRepository;
import com.yeosal.api.daily.DailyService;
import com.yeosal.api.room.RoomMemberRepository;
import com.yeosal.api.user.AuthProvider;
import com.yeosal.api.user.User;
import com.yeosal.api.user.UserRepository;
import java.lang.reflect.Field;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FriendServiceCanViewTest {

    @Mock private FriendshipRepository friendships;
    @Mock private UserRepository users;
    @Mock private DailyEntryRepository dailyEntries;
    @Mock private DailyService dailyService;
    @Mock private RoomMemberRepository roomMembers;

    private FriendService service;
    private User alice;
    private User bob;

    @BeforeEach
    void setUp() {
        service = new FriendService(friendships, users, dailyEntries, dailyService, roomMembers);
        alice = makeUser(1L, "alice@example.com", "Alice");
        bob = makeUser(2L, "bob@example.com", "Bob");
    }

    @Test
    @DisplayName("canView: self always allowed without DB lookup")
    void selfAlwaysAllowed() {
        boolean ok = service.canView(alice, alice);

        assertThat(ok).isTrue();
        verifyNoInteractions(friendships, roomMembers);
    }

    @Test
    @DisplayName("canView: accepted friendship grants visibility")
    void friendshipGrantsVisibility() {
        Friendship f = new Friendship(alice, bob);
        f.setStatus(FriendshipStatus.ACCEPTED);
        when(friendships.findBetween(alice, bob)).thenReturn(Optional.of(f));

        boolean ok = service.canView(alice, bob);

        assertThat(ok).isTrue();
        verify(roomMembers, never()).existsSharedRoom(alice, bob);
    }

    @Test
    @DisplayName("canView: pending friendship without shared room → false")
    void pendingFriendshipWithoutSharedRoomDeniesVisibility() {
        Friendship f = new Friendship(alice, bob);
        f.setStatus(FriendshipStatus.PENDING);
        when(friendships.findBetween(alice, bob)).thenReturn(Optional.of(f));
        when(roomMembers.existsSharedRoom(alice, bob)).thenReturn(false);

        boolean ok = service.canView(alice, bob);

        assertThat(ok).isFalse();
    }

    @Test
    @DisplayName("canView: shared room grants visibility even without friendship")
    void sharedRoomGrantsVisibility() {
        when(friendships.findBetween(alice, bob)).thenReturn(Optional.empty());
        when(roomMembers.existsSharedRoom(alice, bob)).thenReturn(true);

        boolean ok = service.canView(alice, bob);

        assertThat(ok).isTrue();
    }

    @Test
    @DisplayName("canView: no friendship and no shared room → false")
    void noFriendshipNoRoomDeniesVisibility() {
        lenient().when(friendships.findBetween(alice, bob)).thenReturn(Optional.empty());
        lenient().when(roomMembers.existsSharedRoom(alice, bob)).thenReturn(false);

        boolean ok = service.canView(alice, bob);

        assertThat(ok).isFalse();
    }

    private static User makeUser(long id, String email, String nickname) {
        User u = new User(email, nickname, "hash", AuthProvider.EMAIL);
        try {
            Field f = User.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(u, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return u;
    }
}
