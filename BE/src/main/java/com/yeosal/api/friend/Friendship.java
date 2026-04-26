package com.yeosal.api.friend;

public record Friendship(long id, long requesterId, long addresseeId, FriendshipStatus status) {}
