package com.yeosal.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.yeosal.api.common.RateLimitFilter;
import com.yeosal.api.daily.DailyService;
import com.yeosal.api.room.RoomEvaluationScheduler;
import com.yeosal.api.room.chat.ChatService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Smoke test for the full Spring ApplicationContext + Flyway migrations
 * + Tomcat servlet wiring. Exists specifically to catch the class of
 * regression that took prod down twice already:
 *
 * <ol>
 *   <li>PR #34 — {@link RateLimitFilter}'s {@code @Autowired} anchor
 *       was lost when PR #26 added a second constructor, and Spring
 *       fell through to a missing default ctor at boot.</li>
 *   <li>PR #36 — V7/V8 migrations never reached prod because the
 *       stack PRs were merged onto stack base branches instead of
 *       main; Flyway then ran a partial schema and the wiring
 *       cascade lit up.</li>
 * </ol>
 *
 * <p>The Testcontainers PG runs the full V1..V9 chain on every
 * invocation, so a malformed migration (or a JPA mapping that no
 * longer agrees with the SQL DDL) shows up here before it hits
 * production. Each {@code @Autowired} parameter on the test methods
 * is a tripwire for the wiring of a load-bearing bean — adding more
 * tripwires here is cheap and the right move when a future regression
 * touches a different seam.
 */
/**
 * Opt-in: pass {@code -Dyeosal.boot-smoke=true} (or set
 * {@code YEOSAL_BOOT_SMOKE} in the CI env and forward it from the
 * Gradle test task) to actually run this. Default skip keeps the
 * regular {@code ./gradlew test} cycle fast and avoids requiring
 * Docker on every contributor's box. CI is expected to run with
 * the flag on so the boot-time wiring/migration check still gates
 * merges.
 */
@SpringBootTest
@Testcontainers
@EnabledIfSystemProperty(named = "yeosal.boot-smoke", matches = "true")
class ApplicationBootSmokeTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("yeosal")
                    .withUsername("yeosal")
                    .withPassword("yeosal");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void contextLoads() {
        // Empty body — the assertion is "Spring boots without throwing".
        // Add @Autowired tripwires below for any newly load-bearing bean.
    }

    @Test
    void loadBearingBeansAreWired(
            @Autowired RateLimitFilter rateLimitFilter,
            @Autowired ChatService chatService,
            @Autowired DailyService dailyService,
            @Autowired RoomEvaluationScheduler roomEvaluationScheduler) {
        assertThat(rateLimitFilter).isNotNull();
        assertThat(chatService).isNotNull();
        assertThat(dailyService).isNotNull();
        assertThat(roomEvaluationScheduler).isNotNull();
    }
}
