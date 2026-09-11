package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.junit.Test;

public final class FileSearchRequestTest {
    @Test
    public void cancellationBeforeStartReplyRejectsTheLateHandle() throws Exception {
        final FileSearchRequest request = new FileSearchRequest();
        assertNull(request.cancel());
        assertFalse(request.isActive());
        assertFalse(request.attach(handle(1L)));
        assertFalse(request.accepts(1L));
        assertFalse(request.finish(1L));
    }

    @Test
    public void cancellationAfterStartReplyOwnsExactlyOneHandle() throws Exception {
        final FileSearchRequest request = new FileSearchRequest();
        final ShellFileSearchHandle handle = handle(1L);
        assertTrue(request.attach(handle));
        assertSame(handle, request.cancel());
        assertNull(request.cancel());
        assertFalse(request.complete());
    }

    @Test
    public void completionBeforeStartReplyCannotRestartSearch() throws Exception {
        final FileSearchRequest request = new FileSearchRequest();
        assertTrue(request.accepts(7L));
        assertTrue(request.finish(7L));
        assertFalse(request.attach(handle(7L)));
        assertFalse(request.accepts(7L));
        assertFalse(request.complete());
    }

    @Test
    public void firstCallbackBindsTheRemoteId() throws Exception {
        final FileSearchRequest request = new FileSearchRequest();
        assertFalse(request.accepts(0L));
        assertTrue(request.accepts(2L));
        assertFalse(request.accepts(1L));
        assertFalse(request.finish(1L));
        assertFalse(request.attach(handle(1L)));
        assertTrue(request.attach(handle(2L)));
        assertTrue(request.finish(2L));
        assertNull(request.cancel());
    }

    @Test
    public void supersededSearchCannotModifyItsReplacement() throws Exception {
        final FileSearchRequest old = new FileSearchRequest();
        assertTrue(old.attach(handle(1L)));
        old.cancel();
        final FileSearchRequest current = new FileSearchRequest();
        assertTrue(current.attach(handle(1L)));
        assertFalse(old.accepts(1L));
        assertFalse(old.finish(1L));
        assertFalse(old.complete());
        assertTrue(current.isActive());
    }

    @Test
    public void cancellationUsesItsOriginalService() throws Exception {
        final Service old = new Service();
        final Service current = new Service();
        final var oldHandle = new ShellFileSearchHandle(1L, old);
        final var currentHandle = new ShellFileSearchHandle(1L, current);
        oldHandle.cancel();
        assertEquals(1L, old.cancelled);
        assertEquals(0L, current.cancelled);
        currentHandle.cancel();
        assertEquals(1L, current.cancelled);
    }

    @Test(expected = IOException.class)
    public void invalidStartReplyFailsExplicitly() throws Exception {
        handle(0L);
    }

    private static ShellFileSearchHandle handle(final long id) throws IOException {
        return new ShellFileSearchHandle(id, new Service());
    }

    private static final class Service extends IShellCommandService.Default {
        long cancelled;

        @Override
        public void cancelShellFileSearch(final long id) {
            cancelled = id;
        }
    }
}
