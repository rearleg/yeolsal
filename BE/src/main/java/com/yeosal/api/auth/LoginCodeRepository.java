package com.yeosal.api.auth;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LoginCodeRepository extends JpaRepository<LoginCode, Long> {
    Optional<LoginCode> findByCodeAndConsumedAtIsNull(String code);
}
