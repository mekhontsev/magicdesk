package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class FileDirectoryReaderTest {
    private static final FileDirectoryReader.Request REQUEST =
            new FileDirectoryReader.Request("/requested", true, 2, false);

    @Test
    public void pagesKeepOneRequestAndCollectMetadataInFileOrder() throws Exception {
        final ShellFileInfo first = file("first.desktop");
        final ShellFileInfo second = file("second");
        final Source source = new Source(page(7, false, first), page(8, true, second));
        final DesktopEntry entry = new DesktopEntry("First", "", "") { };
        source.metadata.put(first.absolutePath, entry);

        final FileDirectoryReader.Listing listing = FileDirectoryReader.read(REQUEST, () -> false, source);

        assertEquals("/canonical", listing.path);
        assertEquals(List.of(first, second), listing.files);
        assertEquals(Map.of(first.absolutePath, entry), listing.desktopEntries);
        assertEquals(List.of(0, 7), source.offsets);
        assertEquals(List.of(500, 500), source.limits);
        assertEquals(List.of(first, second), source.metadataReads);
        assertEquals(List.of(REQUEST, REQUEST), source.requests);
        assertEquals("/requested", REQUEST.path);
        assertTrue(REQUEST.showHidden);
        assertEquals(2, REQUEST.sortMode);
        assertEquals(false, REQUEST.sortAscending);
    }

    @Test
    public void resultDoesNotExposeMutableCollectionsOrPageArrays() throws Exception {
        final ShellFileInfo first = file("first.desktop");
        final ShellFilePage page = page(1, true, first);
        final Source source = new Source(page);
        final DesktopEntry entry = new DesktopEntry("First", "", "") { };
        source.metadata.put(first.absolutePath, entry);
        final FileDirectoryReader.Listing listing = FileDirectoryReader.read(REQUEST, null, source);

        page.entries[0] = file("replacement");
        source.metadata.clear();
        assertEquals(List.of(first), listing.files);
        assertEquals(Map.of(first.absolutePath, entry), listing.desktopEntries);
        assertThrows(UnsupportedOperationException.class, () -> listing.files.clear());
        assertThrows(UnsupportedOperationException.class, () -> listing.desktopEntries.clear());
    }

    @Test
    public void emptyDirectoryStillPublishesCanonicalPath() throws Exception {
        final Source source = new Source(page(0, true));
        final FileDirectoryReader.Listing listing = FileDirectoryReader.read(REQUEST, null, source);
        assertEquals("/canonical", listing.path);
        assertTrue(listing.files.isEmpty());
        assertTrue(listing.desktopEntries.isEmpty());
        assertTrue(source.metadataReads.isEmpty());
    }

    @Test
    public void laterPageFailureDoesNotPublishPartialListing() {
        final IOException failure = new IOException("service disconnected");
        final Source source = new Source(page(1, false, file("first"))) {
            @Override
            public ShellFilePage page(final FileDirectoryReader.Request request,
                    final int offset, final int limit) throws IOException {
                if (offset > 0) {
                    throw failure;
                }
                return super.page(request, offset, limit);
            }
        };
        assertSame(failure, assertThrows(IOException.class,
                () -> FileDirectoryReader.read(REQUEST, null, source)));
        assertEquals(1, source.metadataReads.size());
    }

    @Test
    public void cancelledQueuedReadDoesNotCallSource() {
        final Source source = new Source();
        assertThrows(InterruptedIOException.class,
                () -> FileDirectoryReader.read(REQUEST, () -> true, source));
        assertTrue(source.requests.isEmpty());
    }

    @Test
    public void cancellationDuringPageCallSkipsItsMetadata() {
        final AtomicBoolean cancelled = new AtomicBoolean();
        final Source source = new Source(page(1, true, file("first"))) {
            @Override
            public ShellFilePage page(final FileDirectoryReader.Request request,
                    final int offset, final int limit) throws IOException {
                final ShellFilePage page = super.page(request, offset, limit);
                cancelled.set(true);
                return page;
            }
        };
        assertThrows(InterruptedIOException.class,
                () -> FileDirectoryReader.read(REQUEST, cancelled::get, source));
        assertTrue(source.metadataReads.isEmpty());
    }

    @Test
    public void cancellationDuringLastEmptyPageDoesNotPublish() {
        final AtomicBoolean cancelled = new AtomicBoolean();
        final Source source = new Source(page(1, false, file("first")), page(1, true)) {
            @Override
            public ShellFilePage page(final FileDirectoryReader.Request request,
                    final int offset, final int limit) throws IOException {
                final ShellFilePage page = super.page(request, offset, limit);
                if (page.complete) {
                    cancelled.set(true);
                }
                return page;
            }
        };
        assertThrows(InterruptedIOException.class,
                () -> FileDirectoryReader.read(REQUEST, cancelled::get, source));
        assertEquals(List.of(0, 1), source.offsets);
    }

    @Test
    public void cancellationDuringMetadataStopsFurtherEntriesAndPages() {
        final AtomicBoolean cancelled = new AtomicBoolean();
        final Source source = new Source(page(2, false, file("first"), file("second"))) {
            @Override
            public DesktopEntry desktopEntry(final ShellFileInfo file) {
                cancelled.set(true);
                return super.desktopEntry(file);
            }
        };
        assertThrows(InterruptedIOException.class,
                () -> FileDirectoryReader.read(REQUEST, cancelled::get, source));
        assertEquals(1, source.metadataReads.size());
        assertEquals(List.of(0), source.offsets);
    }

    @Test
    public void cancellationDuringLastMetadataDoesNotPublish() {
        final AtomicBoolean cancelled = new AtomicBoolean();
        final Source source = new Source(page(1, true, file("first"))) {
            @Override
            public DesktopEntry desktopEntry(final ShellFileInfo file) {
                cancelled.set(true);
                return super.desktopEntry(file);
            }
        };
        assertThrows(InterruptedIOException.class,
                () -> FileDirectoryReader.read(REQUEST, cancelled::get, source));
        assertEquals(1, source.metadataReads.size());
    }

    @Test
    public void workerInterruptionStopsBeforeBinderCall() {
        final Source source = new Source();
        try {
            Thread.currentThread().interrupt();
            assertThrows(InterruptedIOException.class,
                    () -> FileDirectoryReader.read(REQUEST, () -> false, source));
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(source.requests.isEmpty());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void unfinishedPageMustAdvanceOffset() {
        for (final int nextOffset : new int[] {-1, 0, 1}) {
            final Source source = new Source(page(1, false, file("first")), page(nextOffset, false));
            final IOException error = assertThrows(IOException.class,
                    () -> FileDirectoryReader.read(REQUEST, null, source));
            assertEquals("directory listing did not advance", error.getMessage());
            assertEquals(List.of(0, 1), source.offsets);
        }
    }

    @Test
    public void pagesCannotSilentlyMixDifferentDirectories() {
        final Source source = new Source(page(1, false, file("first")),
                new ShellFilePage("/other", "/", new ShellFileInfo[] {file("second")}, 2, true));
        final IOException error = assertThrows(IOException.class,
                () -> FileDirectoryReader.read(REQUEST, null, source));
        assertEquals("directory changed between listing pages", error.getMessage());
        assertEquals(1, source.metadataReads.size());
    }

    @Test
    public void missingPageOrPathIsAnExplicitReadFailure() {
        for (final ShellFilePage page : new ShellFilePage[] {
                null, new ShellFilePage(null, "", null, 0, true),
                new ShellFilePage("", "", null, 0, true)}) {
            final Source source = new Source(page);
            final IOException error = assertThrows(IOException.class,
                    () -> FileDirectoryReader.read(REQUEST, null, source));
            assertEquals("directory listing returned no path", error.getMessage());
        }
    }

    private static ShellFileInfo file(final String name) {
        return new ShellFileInfo("/canonical/" + name, name, "text/plain", "",
                0, 0, 3, 17, 2000, 2000, 0100600,
                false, false, true, true, false, false);
    }

    private static ShellFilePage page(final int offset, final boolean complete, final ShellFileInfo... files) {
        return new ShellFilePage("/canonical", "/", files, offset, complete);
    }

    private static class Source implements FileDirectoryReader.Source {
        final List<FileDirectoryReader.Request> requests = new ArrayList<>();
        final List<Integer> offsets = new ArrayList<>();
        final List<Integer> limits = new ArrayList<>();
        final List<ShellFileInfo> metadataReads = new ArrayList<>();
        final Map<String, DesktopEntry> metadata = new HashMap<>();
        final ShellFilePage[] pages;

        Source(final ShellFilePage... pages) {
            this.pages = pages;
        }

        @Override
        public ShellFilePage page(final FileDirectoryReader.Request request,
                final int offset, final int limit) throws IOException {
            requests.add(request);
            offsets.add(offset);
            limits.add(limit);
            return pages[requests.size() - 1];
        }

        @Override
        public DesktopEntry desktopEntry(final ShellFileInfo file) {
            metadataReads.add(file);
            return metadata.get(file.absolutePath);
        }
    }
}
