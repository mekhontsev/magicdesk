package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import java.io.IOException;

import org.junit.Test;

public final class ShellFileOperationHandleTest {
    @Test
    public void reusedIdStillCancelsOnlyItsOriginalService() throws Exception {
        final Service old = new Service();
        final Service current = new Service();
        final var oldOperation = new ShellFileOperationHandle(1L, old);
        final var currentOperation = new ShellFileOperationHandle(1L, current);

        oldOperation.cancel();
        assertEquals(1L, old.cancelledId);
        assertEquals(0L, current.cancelledId);
        currentOperation.cancel();
        assertEquals(1L, current.cancelledId);
    }

    @Test(expected = IOException.class)
    public void invalidStartReplyFailsInsteadOfLeavingOperationPending()
            throws Exception {
        new ShellFileOperationHandle(-1L, new Service());
    }

    private static final class Service extends IShizukuCommandService.Default {
        long cancelledId;

        @Override
        public void cancelShellFileOperation(final long id) {
            cancelledId = id;
        }
    }
}
