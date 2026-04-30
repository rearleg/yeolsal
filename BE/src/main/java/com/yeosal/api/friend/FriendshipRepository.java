package com.yeosal.api.friend;

import com.yeosal.api.user.User;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FriendshipRepository extends JpaRepository<Friendship, Long> {
    @Query("""
            select f from Friendship f
            where ((f.requester = :a and f.addressee = :b) or (f.requester = :b and f.addressee = :a))
            """)
    Optional<Friendship> findBetween(@Param("a") User a, @Param("b") User b);

    @Query("""
            select f from Friendship f
            where (f.requester = :user or f.addressee = :user) and f.status = :status
            """)
    List<Friendship> findByUserAndStatus(@Param("user") User user, @Param("status") FriendshipStatus status);

    List<Friendship> findByAddresseeAndStatus(User addressee, FriendshipStatus status);

    List<Friendship> findByStatus(FriendshipStatus status);
}
