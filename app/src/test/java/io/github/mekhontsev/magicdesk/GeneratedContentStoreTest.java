package io.github.mekhontsev.magicdesk;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;

import static org.junit.Assert.*;

public final class GeneratedContentStoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private static final long NOW = 1_700_000_000_000L;

    @Test public void exportedBytesRemainReadableUntilExpiry() throws Exception {
        File dir = temporary.newFolder();
        String id = GeneratedContentStore.publish(dir, out -> out.write(new byte[]{1, 2, 3}), NOW);
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(
                GeneratedContentStore.resolve(dir, id, NOW + 1).toPath()));
        assertThrows(FileNotFoundException.class, () -> GeneratedContentStore.resolve(
                dir, id, NOW + GeneratedContentStore.LIFETIME_MILLIS));
        GeneratedContentStore.publish(dir, out -> out.write(4), NOW + GeneratedContentStore.LIFETIME_MILLIS);
        assertFalse(new File(dir, id).exists());
    }

    @Test public void failedWriterLeavesNoPartialExport() throws Exception {
        File dir = temporary.newFolder();
        assertThrows(IOException.class, () -> GeneratedContentStore.publish(dir, out -> {
            out.write(1); throw new IOException("failed");
        }, NOW));
        assertEquals(0, dir.list().length);
    }

    @Test public void rejectsPathsAndMissingIds() throws Exception {
        File dir = temporary.newFolder();
        for (String id : new String[]{"../private", "/etc/passwd", "", "a/b",
                "00000000-0000-0000-0000-000000000000"}) {
            assertThrows(FileNotFoundException.class, () -> GeneratedContentStore.resolve(dir, id, NOW));
        }
    }

    @Test public void quotaRejectsExportWithoutEvictingFreshFiles() throws Exception {
        File dir = temporary.newFolder();
        String id = GeneratedContentStore.publish(dir, out -> out.write(1), NOW);
        File file = new File(dir, id);
        try (RandomAccessFile sparse = new RandomAccessFile(file, "rw")) {
            sparse.setLength(GeneratedContentStore.MAX_BYTES - 1);
        }
        assertTrue(file.setLastModified(NOW));
        assertThrows(IOException.class, () -> GeneratedContentStore.publish(dir, out -> out.write(new byte[2]), NOW));
        assertEquals(1, dir.list().length);
        assertEquals(file, GeneratedContentStore.resolve(dir, id, NOW));
    }

    @Test public void countLimitAndCancellationAreBounded() throws Exception {
        File dir = temporary.newFolder();
        try {
            Thread.currentThread().interrupt();
            assertThrows(IOException.class, () -> GeneratedContentStore.publish(dir, out -> out.write(1), NOW));
        } finally { Thread.interrupted(); }
        assertEquals(0, dir.list().length);
        for (int i = 0; i < 128; i++) GeneratedContentStore.publish(dir, out -> out.write(1), NOW);
        assertThrows(IOException.class, () -> GeneratedContentStore.publish(dir, out -> out.write(1), NOW));
        assertEquals(128, dir.list().length);
    }
}
