package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Process-local source selection and intent for shell file operations. */
final class FileOperationClipboard {
    enum Mode {
        NONE("none"),
        COPY("copy"),
        MOVE("move");

        final String wireName;

        Mode(final String wireName) {
            this.wireName = wireName;
        }
    }

    static final class Snapshot {
        final List<String> paths;
        final Mode mode;
        final long generation;
        final boolean systemPublished;

        Snapshot(
                final List<String> paths,
                final Mode mode,
                final long generation,
                final boolean systemPublished) {
            this.paths = paths;
            this.mode = mode;
            this.generation = generation;
            this.systemPublished = systemPublished;
        }

        boolean isEmpty() {
            return paths.isEmpty();
        }

        boolean isMove() {
            return mode == Mode.MOVE;
        }
    }

    private static long sGeneration;
    private static Snapshot sSnapshot = emptySnapshot();

    private FileOperationClipboard() {
    }

    static synchronized Snapshot snapshot() {
        return sSnapshot;
    }

    static synchronized Snapshot set(
            final List<String> paths, final Mode mode) {
        if (paths == null || paths.isEmpty()) {
            throw new IllegalArgumentException("file paths are required");
        }
        if (mode == null || mode == Mode.NONE) {
            throw new IllegalArgumentException("copy or move mode is required");
        }
        sGeneration++;
        sSnapshot = new Snapshot(
                Collections.unmodifiableList(new ArrayList<>(paths)),
                mode,
                sGeneration,
                false);
        return sSnapshot;
    }

    static synchronized void markSystemPublished(final long generation) {
        if (sSnapshot.generation != generation || sSnapshot.isEmpty()) {
            return;
        }
        sSnapshot = new Snapshot(
                sSnapshot.paths,
                sSnapshot.mode,
                sSnapshot.generation,
                true);
    }

    static synchronized void clearIfGeneration(final long generation) {
        if (sSnapshot.generation != generation) {
            return;
        }
        sGeneration++;
        sSnapshot = emptySnapshot();
    }

    static synchronized String diagnostics() {
        return "items=" + sSnapshot.paths.size()
                + ", mode=" + sSnapshot.mode.wireName
                + ", generation=" + sSnapshot.generation
                + ", systemPublished=" + sSnapshot.systemPublished;
    }

    private static Snapshot emptySnapshot() {
        return new Snapshot(
                Collections.emptyList(), Mode.NONE, sGeneration, false);
    }
}
