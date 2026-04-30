package com.yeosal.api.daily;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GateRuleTest {
    private final GateRule rule = new GateRule();

    @Test
    void noGoalReturnsZeroEvenWithReflection() {
        assertThat(rule.bucket(false, true, 5)).isEqualTo(0);
    }

    @Test
    void noReflectionReturnsZeroEvenWithGoalAndTodos() {
        assertThat(rule.bucket(true, false, 9)).isEqualTo(0);
    }

    @Test
    void goalAndReflectionWithZeroTodosIsLevelOne() {
        assertThat(rule.bucket(true, true, 0)).isEqualTo(1);
    }

    @Test
    void oneOrTwoTodosIsLevelTwo() {
        assertThat(rule.bucket(true, true, 1)).isEqualTo(2);
        assertThat(rule.bucket(true, true, 2)).isEqualTo(2);
    }

    @Test
    void threeOrFourTodosIsLevelThree() {
        assertThat(rule.bucket(true, true, 3)).isEqualTo(3);
        assertThat(rule.bucket(true, true, 4)).isEqualTo(3);
    }

    @Test
    void fiveOrMoreTodosIsLevelFour() {
        assertThat(rule.bucket(true, true, 5)).isEqualTo(4);
        assertThat(rule.bucket(true, true, 50)).isEqualTo(4);
    }

    @Test
    void negativeTodoCountClampsToZero() {
        assertThat(rule.bucket(true, true, -1)).isEqualTo(1);
    }
}
