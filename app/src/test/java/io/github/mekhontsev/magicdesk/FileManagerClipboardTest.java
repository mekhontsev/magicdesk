package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

public final class FileManagerClipboardTest {
    @Test
    public void sharesImmutableCopyOfPaths() {
        FileManagerClipboard.set(Arrays.asList("/one", "/two"), true);

        final FileManagerClipboard.Snapshot snapshot =
                FileManagerClipboard.snapshot();

        assertEquals(Arrays.asList("/one", "/two"), snapshot.paths);
        assertTrue(snapshot.move);
    }

    @Test
    public void staleMoveCannotClearNewerClipboard() {
        FileManagerClipboard.set(Arrays.asList("/old"), true);
        final long oldGeneration =
                FileManagerClipboard.snapshot().generation;
        FileManagerClipboard.set(Arrays.asList("/new"), false);

        FileManagerClipboard.clearIfGeneration(oldGeneration);

        final FileManagerClipboard.Snapshot snapshot =
                FileManagerClipboard.snapshot();
        assertFalse(snapshot.isEmpty());
        assertEquals(Arrays.asList("/new"), snapshot.paths);
    }

    @Test
    public void completedMoveClearsMatchingClipboard() {
        FileManagerClipboard.set(Arrays.asList("/move"), true);
        final long generation = FileManagerClipboard.snapshot().generation;

        FileManagerClipboard.clearIfGeneration(generation);

        assertTrue(FileManagerClipboard.snapshot().isEmpty());
    }
}
