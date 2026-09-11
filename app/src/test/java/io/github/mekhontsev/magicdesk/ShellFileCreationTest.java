package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import android.os.ParcelFileDescriptor;

import java.io.IOException;

import org.junit.Test;

public final class ShellFileCreationTest {
    @Test
    public void closeRollsBackOnlyOriginalServiceAndFileIdentity() throws Exception {
        final Service original = new Service();
        final Service replacement = new Service();
        final ShellFileCreation target = new ShellFileCreation(original, "/tmp", "file");
        new ShellFileCreation(replacement, "/tmp", "file").commit();

        target.close();
        target.close();

        assertEquals(1, original.deletes);
        assertEquals(0, replacement.deletes);
        assertEquals("/tmp/file", original.deletedPath);
        assertEquals(3L, original.deletedDevice);
        assertEquals(17L, original.deletedInode);
    }

    @Test
    public void committedCopyIsNotRemovedOnClose() throws Exception {
        final Service service = new Service();
        try (ShellFileCreation target = new ShellFileCreation(service, "/tmp", "file")) {
            target.commit();
        }
        assertEquals(0, service.deletes);
    }

    @Test
    public void verifiedOpenFailureRollsBackAndPreservesOriginalError() {
        final Service service = new Service();
        final IOException failure = assertThrows(IOException.class, () -> {
            try (ShellFileCreation target = new ShellFileCreation(service, "/tmp", "file")) {
                target.open();
            }
        });

        assertEquals("service returned no created file descriptor", failure.getMessage());
        assertEquals(1, service.deletes);
        assertEquals("w", service.openMode);
        assertEquals(3L, service.openDevice);
        assertEquals(17L, service.openInode);
    }

    @Test
    public void cleanupErrorIsSuppressedOnTheTransferFailure() {
        final Service service = new Service();
        final IllegalStateException cleanup = new IllegalStateException("file replaced");
        service.deleteFailure = cleanup;
        final IOException original = new IOException("provider failed");

        final IOException failure = assertThrows(IOException.class, () -> {
            try (ShellFileCreation target = new ShellFileCreation(service, "/tmp", "file")) {
                throw original;
            }
        });

        assertSame(original, failure);
        assertSame(cleanup, failure.getSuppressed()[0].getCause());
    }

    @Test
    public void closedTargetCannotBeOpenedOrCommitted() throws Exception {
        final Service service = new Service();
        final ShellFileCreation target = new ShellFileCreation(service, "/tmp", "file");
        target.close();

        assertThrows(IOException.class, target::open);
        assertThrows(IllegalStateException.class, target::commit);
    }

    @Test
    public void absentCreationReplyDoesNotTriggerPathOnlyDelete() {
        final Service service = new Service();
        service.file = null;

        assertThrows(IOException.class, () -> new ShellFileCreation(service, "/tmp", "file"));
        assertEquals(0, service.deletes);
    }

    private static final class Service extends IShellCommandService.Default {
        ShellFileInfo file = new ShellFileInfo("/tmp/file", "file", "text/plain", "",
                0, 0, 3, 17, 2000, 2000, 0100600,
                false, false, true, true, false, false);
        int deletes;
        String deletedPath;
        long deletedDevice;
        long deletedInode;
        String openMode;
        long openDevice;
        long openInode;
        RuntimeException deleteFailure;

        @Override
        public ShellFileInfo createAvailableShellEntry(
                final String parent, final String name, final boolean directory) {
            assertEquals("/tmp", parent);
            assertEquals("file", name);
            assertFalse(directory);
            return file;
        }

        @Override
        public ParcelFileDescriptor openVerifiedShellFile(
                final String path, final String mode, final long device, final long inode) {
            assertEquals("/tmp/file", path);
            openMode = mode;
            openDevice = device;
            openInode = inode;
            return null;
        }

        @Override
        public void deleteVerifiedShellFile(
                final String path, final long device, final long inode) {
            deletes++;
            deletedPath = path;
            deletedDevice = device;
            deletedInode = inode;
            if (deleteFailure != null) {
                throw deleteFailure;
            }
        }
    }
}
