package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public final class PersistedUriPermissionsTest {
    private static final String URI = "content://provider/document/a";
    private final List<String> released = new ArrayList<>();
    private boolean rejectTake;
    private final PersistedUriPermissions permissions = new PersistedUriPermissions(
            new PersistedUriPermissions.Access() {
                @Override
                public void take(final String uri, final int flags) {
                    if (rejectTake) {
                        throw new SecurityException("denied");
                    }
                }

                @Override
                public void release(final String uri, final int flags) {
                    released.add(uri + ":" + flags);
                }
            });

    @Test
    public void consumingOneResultPreservesAnotherResultsReadAccess() {
        final var first = permissions.acquire(URI, 1);
        final var second = permissions.acquire(URI, 1);
        assertFalse(first.release());
        assertTrue(released.isEmpty());
        assertTrue(second.release());
        assertEquals(List.of(URI + ":1"), released);
        assertFalse(second.release());
    }

    @Test
    public void releasesOnlyFlagsThatNoOtherResultUses() {
        final var read = permissions.acquire(URI, 1);
        final var readWrite = permissions.acquire(URI, 3);
        assertTrue(readWrite.release());
        assertEquals(List.of(URI + ":2"), released);
        assertTrue(read.release());
        assertEquals(List.of(URI + ":2", URI + ":1"), released);
    }

    @Test
    public void failedAcquisitionDoesNotRetainPermissions() {
        final var first = permissions.acquire(URI, 1);
        rejectTake = true;
        try {
            permissions.acquire(URI, 3);
            fail("expected denied grant");
        } catch (SecurityException expected) {
            assertTrue(first.release());
            assertEquals(List.of(URI + ":1"), released);
        }
    }

    @Test
    public void differentUrisHaveIndependentOwners() {
        final var first = permissions.acquire(URI, 1);
        final var second = permissions.acquire(URI + "2", 1);
        assertTrue(first.release());
        assertTrue(second.release());
        assertEquals(List.of(URI + ":1", URI + "2:1"), released);
    }

    @Test
    public void lateDuplicateReleaseCannotRevokeReacquiredUri() {
        final var first = permissions.acquire(URI, 1);
        first.release();
        final var second = permissions.acquire(URI, 1);
        assertFalse(first.release());
        assertEquals(1, released.size());
        assertTrue(second.release());
    }
}
