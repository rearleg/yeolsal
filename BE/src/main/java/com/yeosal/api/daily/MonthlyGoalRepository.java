package com.yeosal.api.daily;

import com.yeosal.api.user.User;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MonthlyGoalRepository extends JpaRepository<MonthlyGoal, Long> {
    Optional<MonthlyGoal> findByUserAndMonth(User user, String month);
}
