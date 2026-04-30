package com.yeosal.api.room;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import org.junit.jupiter.api.Test;

class ConnectedComponentsTest {

    @Test
    void emptyAdjacencyYieldsNoComponents() {
        List<SortedSet<Long>> components = ConnectedComponents.find(Map.of());

        assertThat(components).isEmpty();
    }

    @Test
    void singleEdgeProducesOnePairComponent() {
        Map<Long, Set<Long>> adjacency = Map.of(
                1L, Set.of(2L),
                2L, Set.of(1L)
        );

        List<SortedSet<Long>> components = ConnectedComponents.find(adjacency);

        assertThat(components).hasSize(1);
        assertThat(components.get(0)).containsExactly(1L, 2L);
    }

    @Test
    void disjointEdgesProduceTwoComponents() {
        Map<Long, Set<Long>> adjacency = Map.of(
                1L, Set.of(2L),
                2L, Set.of(1L),
                3L, Set.of(4L),
                4L, Set.of(3L)
        );

        List<SortedSet<Long>> components = ConnectedComponents.find(adjacency);

        assertThat(components).hasSize(2);
        assertThat(components).extracting(c -> c.first()).containsExactlyInAnyOrder(1L, 3L);
    }

    @Test
    void chainCollapsesToOneComponent() {
        // 1-2, 2-3, 3-4 → {1,2,3,4}
        Map<Long, Set<Long>> adjacency = Map.of(
                1L, Set.of(2L),
                2L, Set.of(1L, 3L),
                3L, Set.of(2L, 4L),
                4L, Set.of(3L)
        );

        List<SortedSet<Long>> components = ConnectedComponents.find(adjacency);

        assertThat(components).hasSize(1);
        assertThat(components.get(0)).containsExactly(1L, 2L, 3L, 4L);
    }

    @Test
    void isolatedNodeYieldsSingletonComponent() {
        Map<Long, Set<Long>> adjacency = Map.of(7L, Set.of());

        List<SortedSet<Long>> components = ConnectedComponents.find(adjacency);

        assertThat(components).hasSize(1);
        assertThat(components.get(0)).containsExactly(7L);
    }
}
