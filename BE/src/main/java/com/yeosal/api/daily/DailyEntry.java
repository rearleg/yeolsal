package com.yeosal.api.daily;

import java.time.LocalDate;
import java.util.List;

public record DailyEntry(long id, long userId, LocalDate date, String goal, List<TodoItem> todos) {}
