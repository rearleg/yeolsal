package com.yeosal.api.ceremony;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Story 7.2 AC7 — boot-time regression guard. Asserts the
 * {@link FinalThreeJob#runMonthlyBatch()} {@code @Scheduled} is actually
 * registered with the expected cron string and KST zone. Without this
 * IT, a typo (e.g., {@code "0 30 6 1 * ?"}) or accidentally dropping
 * {@code @Component} from the job would silently no-op the entire
 * monthly batch — Spring skips {@code @Scheduled} on non-managed beans.
 *
 * <p>Spring 7's {@link CronTrigger} exposes {@code getExpression()} but
 * keeps {@code zoneId} private (no getter). Reflection reads the private
 * field — the alternative would be peeking at {@code toString()} which
 * only emits the expression, losing the zone assertion.
 *
 * <p>Opt-in via {@code yeosal.boot-smoke=true} (CI gate); local
 * {@code ./gradlew test} stays Docker-free.
 */
@SpringBootTest
@Testcontainers
@EnabledIfSystemProperty(named = "yeosal.boot-smoke", matches = "true")
class FinalThreeJobSchedulerRegistrationIT {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("yeosal")
                    .withUsername("yeosal")
                    .withPassword("yeosal");

    @Autowired private ScheduledTaskHolder scheduledTaskHolder;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    @DisplayName("FinalThreeJob.runMonthlyBatch is registered with cron \"0 30 6 1 * *\" + zone Asia/Seoul")
    void runMonthlyBatch_isRegisteredWithExpectedCronAndZone() throws Exception {
        boolean matched = false;
        for (ScheduledTask task : scheduledTaskHolder.getScheduledTasks()) {
            if (!(task.getTask() instanceof CronTask cronTask)) continue;
            if (!(cronTask.getTrigger() instanceof CronTrigger cronTrigger)) continue;
            String expression = cronTrigger.getExpression();
            ZoneId zone = readZoneId(cronTrigger);
            if ("0 30 6 1 * *".equals(expression)
                    && zone != null
                    && "Asia/Seoul".equals(zone.getId())) {
                matched = true;
                break;
            }
        }
        assertThat(matched)
                .as("expected a ScheduledTask with cron \"0 30 6 1 * *\" + zone Asia/Seoul")
                .isTrue();
    }

    private static ZoneId readZoneId(CronTrigger trigger) throws Exception {
        Field f = CronTrigger.class.getDeclaredField("zoneId");
        f.setAccessible(true);
        return (ZoneId) f.get(trigger);
    }
}
