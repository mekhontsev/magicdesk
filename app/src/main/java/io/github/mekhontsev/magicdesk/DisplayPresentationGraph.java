package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Rejects compositing feedback, including multiple mirrors on the same output. */
final class DisplayPresentationGraph {
    record Edge(int output, int source) { }

    private DisplayPresentationGraph() { }

    static void requireAcyclic(List<Edge> edges, Iterable<Integer> phonePreviews) {
        final Map<Integer, List<Integer>> sources = new HashMap<>();
        for (Edge edge : edges) sources.computeIfAbsent(edge.output, id -> new ArrayList<>()).add(edge.source);
        for (int preview : phonePreviews) sources.computeIfAbsent(0, id -> new ArrayList<>()).add(preview);
        final Set<Integer> complete = new HashSet<>();
        final Set<Integer> visiting = new HashSet<>();
        for (int output : sources.keySet()) visit(output, sources, visiting, complete);
    }

    private static void visit(int display, Map<Integer, List<Integer>> sources,
            Set<Integer> visiting, Set<Integer> complete) {
        if (complete.contains(display)) return;
        if (!visiting.add(display)) throw new IllegalArgumentException("recursive display presentation");
        for (int source : sources.getOrDefault(display, List.of())) visit(source, sources, visiting, complete);
        visiting.remove(display);
        complete.add(display);
    }
}
