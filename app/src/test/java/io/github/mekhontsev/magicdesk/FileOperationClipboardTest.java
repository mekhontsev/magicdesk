package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

public final class FileOperationClipboardTest {
    @Test
    public void sharesImmutableCopyOfPathsAndExplicitMode() {
        FileOperationClipboard.set(
                Arrays.asList("/one", "/two"),
                FileOperationClipboard.Mode.MOVE);

        final FileOperationClipboard.Snapshot snapshot =
                FileOperationClipboard.snapshot();

        assertEquals(Arrays.asList("/one", "/two"), snapshot.paths);
        assertEquals(FileOperationClipboard.Mode.MOVE, snapshot.mode);
        assertTrue(snapshot.isMove());
        assertFalse(snapshot.systemPublished);
    }

    @Test
    public void staleMoveCannotClearNewerClipboard() {
        FileOperationClipboard.set(
                Arrays.asList("/old"), FileOperationClipboard.Mode.MOVE);
        final long oldGeneration =
                FileOperationClipboard.snapshot().generation;
        FileOperationClipboard.set(
                Arrays.asList("/new"), FileOperationClipboard.Mode.COPY);

        FileOperationClipboard.clearIfGeneration(oldGeneration);

        final FileOperationClipboard.Snapshot snapshot =
                FileOperationClipboard.snapshot();
        assertFalse(snapshot.isEmpty());
        assertEquals(Arrays.asList("/new"), snapshot.paths);
        assertEquals(FileOperationClipboard.Mode.COPY, snapshot.mode);
    }

    @Test
    public void completedMoveClearsMatchingClipboard() {
        FileOperationClipboard.set(
                Arrays.asList("/move"), FileOperationClipboard.Mode.MOVE);
        final long generation =
                FileOperationClipboard.snapshot().generation;

        FileOperationClipboard.clearIfGeneration(generation);

        assertTrue(FileOperationClipboard.snapshot().isEmpty());
    }

    @Test
    public void publicationMarksOnlyMatchingGeneration() {
        final long oldGeneration = FileOperationClipboard.set(
                Arrays.asList("/old"),
                FileOperationClipboard.Mode.COPY).generation;
        FileOperationClipboard.set(
                Arrays.asList("/new"), FileOperationClipboard.Mode.COPY);

        FileOperationClipboard.markSystemPublished(oldGeneration);
        assertFalse(FileOperationClipboard.snapshot().systemPublished);

        final long currentGeneration =
                FileOperationClipboard.snapshot().generation;
        FileOperationClipboard.markSystemPublished(currentGeneration);
        assertTrue(FileOperationClipboard.snapshot().systemPublished);
    }
}
