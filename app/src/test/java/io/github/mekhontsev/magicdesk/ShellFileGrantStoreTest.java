package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public final class ShellFileGrantStoreTest {
    @Test
    public void externalSelectionAcceptsOnlyReadableRegularFiles() {
        assertTrue(ShellFileGrantStore.canShareReadOnly(List.of(file(0100644, false, true))));
        assertFalse(ShellFileGrantStore.canShareReadOnly(List.of(file(0100644, true, true))));
        assertFalse(ShellFileGrantStore.canShareReadOnly(List.of(file(0100644, false, false))));
        assertFalse(ShellFileGrantStore.canShareReadOnly(List.of(file(0040755, false, true))));
        assertFalse(ShellFileGrantStore.canShareReadOnly(List.of(file(0010600, false, true))));
    }

    @Test
    public void wholeMixedSelectionStaysLocalWithoutPreparingAnyUri() {
        final ShellFileInfo regular = file(0100644, false, true);
        for (final ShellFileInfo unavailable : Arrays.asList(
                file(0040755, false, true), file(0100644, true, true),
                file(0100644, false, false), file(0140600, false, true), null)) {
            final List<ShellFileInfo> files = Arrays.asList(regular, unavailable);
            assertFalse(ShellFileGrantStore.canShareReadOnly(files));
            assertTrue(ShellFileGrantStore.createReadOnlySelection(null, files).isEmpty());
        }
    }

    @Test
    public void oversizedSelectionIsRejectedBeforeTraversalOrUriPreparation() {
        final List<ShellFileInfo> oversized = new AbstractList<>() {
            @Override
            public ShellFileInfo get(final int index) {
                throw new AssertionError("oversized selection must remain local");
            }

            @Override
            public int size() {
                return Integer.MAX_VALUE;
            }
        };
        assertFalse(ShellFileGrantStore.canShareReadOnly(oversized));
        assertTrue(ShellFileGrantStore.createReadOnlySelection(null, oversized).isEmpty());
        assertTrue(ShellFileGrantStore.canShareReadOnly(Collections.nCopies(
                AndroidContentPayload.MAX_URI_ITEMS, file(0100644, false, true))));
    }

    @Test
    public void emptySelectionDoesNotPrepareUris() {
        assertFalse(ShellFileGrantStore.canShareReadOnly(null));
        assertFalse(ShellFileGrantStore.canShareReadOnly(List.of()));
        assertTrue(ShellFileGrantStore.createReadOnlySelection(null, List.of()).isEmpty());
    }

    @Test
    public void grantsRejectNonRegularFileDescriptors() {
        for (final int mode : new int[] {0010600, 0020600, 0040755, 0060600,
                0120777, 0140600, 0}) {
            assertThrows("mode=" + Integer.toOctalString(mode),
                    IllegalArgumentException.class,
                    () -> new ShellFileGrantStore.Entry(file(mode, false, true), false));
        }
    }

    @Test
    public void grantsRejectMissingMetadata() {
        assertThrows(IllegalArgumentException.class,
                () -> new ShellFileGrantStore.Entry(null, false));
    }

    @Test
    public void explicitOpenCanShareAResolvedSymlinkToARegularFile() {
        final ShellFileInfo file = file(0100644, true, true);
        assertSame(file, new ShellFileGrantStore.Entry(file, false).info);
    }

    @Test
    public void requestedWriteCannotExceedTheSourcesAccess() {
        final ShellFileInfo readOnly = new ShellFileInfo("/tmp/file", "file", "text/plain", "",
                0, 0, 3, 17, 2000, 2000, 0100444,
                false, false, true, false, false, false);
        assertFalse(new ShellFileGrantStore.Entry(readOnly, true).writable);
        assertFalse(new ShellFileGrantStore.Entry(file(0100644, false, true), false).writable);
        assertTrue(new ShellFileGrantStore.Entry(file(0100644, false, true), true).writable);
    }

    private static ShellFileInfo file(
            final int mode, final boolean symbolicLink, final boolean readable) {
        return new ShellFileInfo("/tmp/file", "file", "text/plain", "",
                0, 0, 3, 17, 2000, 2000, mode,
                (mode & 0170000) == 0040000, symbolicLink, readable, true, false, false);
    }
}
