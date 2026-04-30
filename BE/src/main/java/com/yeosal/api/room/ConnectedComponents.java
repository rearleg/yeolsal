package com.yeosal.api.room;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Pure graph-component utility used by {@link DefaultRoomMigrationRunner} to
 * group friendship adjacency into seed rooms. Returned components are sorted
 * deterministically (smallest user id first within each component, components
 * ordered by their smallest member).
 */
final class ConnectedComponents {

    private ConnectedComponents() {}

    static List<SortedSet<Long>> find(Map<Long, Set<Long>> adjacency) {
        Set<Long> visited = new HashSet<>();
        List<SortedSet<Long>> result = new ArrayList<>();

        List<Long> nodes = new ArrayList<>(adjacency.keySet());
        nodes.sort(Long::compareTo);

        for (Long start : nodes) {
            if (visited.contains(start)) continue;
            SortedSet<Long> component = new TreeSet<>();
            Deque<Long> queue = new ArrayDeque<>();
            queue.add(start);
            visited.add(start);
            while (!queue.isEmpty()) {
                Long node = queue.poll();
                component.add(node);
                for (Long neighbor : adjacency.getOrDefault(node, Set.of())) {
                    if (visited.add(neighbor)) {
                        queue.add(neighbor);
                    }
                }
            }
            result.add(component);
        }

        result.sort((a, b) -> Long.compare(a.first(), b.first()));
        return result;
    }
}
