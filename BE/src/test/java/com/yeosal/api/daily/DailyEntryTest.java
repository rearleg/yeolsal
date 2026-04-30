package com.yeosal.api.daily;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.OrderBy;
import java.lang.reflect.Field;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DailyEntryTest {

    @Test
    @DisplayName("todos collection is ordered by id ASC so toggling completion preserves position")
    void todosFieldHasOrderByIdAsc() throws NoSuchFieldException {
        Field todos = DailyEntry.class.getDeclaredField("todos");
        OrderBy orderBy = todos.getAnnotation(OrderBy.class);

        assertThat(orderBy)
                .as("@OrderBy must be present on DailyEntry.todos to guarantee deterministic order")
                .isNotNull();
        assertThat(orderBy.value()).isEqualTo("id ASC");
    }
}
