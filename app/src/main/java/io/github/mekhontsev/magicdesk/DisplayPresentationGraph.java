package io.github.mekhontsev.magicdesk;

import java.util.HashSet;
import java.util.Map;

/** Rejects compositing feedback without assuming display types or runtime ID order. */
final class DisplayPresentationGraph {
    private DisplayPresentationGraph() { }

    static void requireAcyclic(Map<Integer, Integer> outputToSource) {
        for (int start : outputToSource.keySet()) {
            final HashSet<Integer> visited = new HashSet<>();
            int current = start;
            while (outputToSource.containsKey(current)) {
                if (!visited.add(current)) throw new IllegalArgumentException("recursive display presentation");
                current = outputToSource.get(current);
            }
        }
    }

    static void requireAcyclic(Map<Integer, Integer> outputToSource,
            Iterable<Integer> phonePreviews) {
        requireAcyclic(outputToSource);
        // Android overlay displays already have a preview on display 0. Include
        // that implicit edge so mirroring the phone cannot capture its viewer.
        for (int preview : phonePreviews) {
            int current = preview;
            while (outputToSource.containsKey(current)) {
                current = outputToSource.get(current);
                if (current == 0) throw new IllegalArgumentException("recursive phone preview presentation");
            }
        }
    }
}
