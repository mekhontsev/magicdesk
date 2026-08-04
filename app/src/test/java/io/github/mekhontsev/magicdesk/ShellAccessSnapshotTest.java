package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ShellAccessSnapshotTest {
    @Test
    public void shellServerWithPermissionIsReady() {
        assertTrue(snapshot(true, true, 2000, 11).isReady());
    }

    @Test
    public void unavailableOrOutdatedServerIsNotReady() {
        assertFalse(snapshot(false, true, 2000, 11).isReady());
        assertFalse(snapshot(true, false, 2000, 11).isReady());
        assertFalse(snapshot(true, true, 2000, 10).isReady());
    }

    private static ShellAccess.Snapshot snapshot(
            final boolean running,
            final boolean permissionGranted,
            final int uid,
            final int version) {
        return new ShellAccess.Snapshot(
                true,
                running,
                permissionGranted,
                uid,
                version,
                "");
    }
}
