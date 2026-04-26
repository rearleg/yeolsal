package com.yeosal.api.daily;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReflectionRepository extends JpaRepository<Reflection, Long> {
    Optional<Reflection> findByDailyEntry(DailyEntry dailyEntry);
}
