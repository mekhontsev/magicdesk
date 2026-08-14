package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class FileManagerClipboard {
    static final class Snapshot {
        final List<String> paths;
        final boolean move;
        final long generation;

        Snapshot(
                final List<String> paths,
                final boolean move,
                final long generation) {
            this.paths = paths;
            this.move = move;
            this.generation = generation;
        }

        boolean isEmpty() {
            return paths.isEmpty();
        }
    }

    private static long sGeneration;
    private static Snapshot sSnapshot = emptySnapshot();

    private FileManagerClipboard() {
    }

    static synchronized Snapshot snapshot() {
        return sSnapshot;
    }

    static synchronized void set(
            final List<String> paths, final boolean move) {
        sGeneration++;
        sSnapshot = new Snapshot(
                Collections.unmodifiableList(new ArrayList<>(paths)),
                move,
                sGeneration);
    }

    static synchronized void clearIfGeneration(final long generation) {
        if (sSnapshot.generation != generation) {
            return;
        }
        sGeneration++;
        sSnapshot = emptySnapshot();
    }

    private static Snapshot emptySnapshot() {
        return new Snapshot(Collections.emptyList(), false, sGeneration);
    }
}
