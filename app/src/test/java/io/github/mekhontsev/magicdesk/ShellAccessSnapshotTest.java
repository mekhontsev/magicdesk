package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import android.os.RemoteException;

import org.junit.Test;

public final class ShellAccessSnapshotTest {
    @Test public void accessDescribesConnectedUidNotStartupTransport() {
        for (ShellBackend backend : ShellBackend.values()) {
            assertEquals("root", new ShellAccess.Snapshot(backend, false, true, true, 0, 13, "").accessLabel());
            assertEquals("shell", new ShellAccess.Snapshot(backend, false, true, true, 2000, 13, "").accessLabel());
            assertEquals("none", new ShellAccess.Snapshot(backend, true, true, true, -1, 13, "disconnected").accessLabel());
            assertEquals("none", new ShellAccess.Snapshot(backend, true, true, false, 0, 13, "denied").accessLabel());
        }
    }

    @Test
    public void shellServerWithPermissionIsReady() {
        assertTrue(snapshot(true, true, 2000, 11).isReady());
    }

    @Test
    public void compatibleBinderDoesNotRequireTheSelectedManagerToBeInstalled() {
        assertTrue(new ShellAccess.Snapshot(ShellBackend.SHIZUKU, false, true, true, 2000, 13, "").isReady());
    }

    @Test
    public void unavailableOrOutdatedServerIsNotReady() {
        assertFalse(snapshot(false, true, 2000, 11).isReady());
        assertFalse(snapshot(true, false, 2000, 11).isReady());
        assertFalse(new ShellAccess.Snapshot(ShellBackend.SHIZUKU, true, true, false, -1, 10,
                "Shizuku API 11 or newer is required").isReady());
    }

    @Test
    public void onlyBinderTransportErrorsInvalidateCommandService() {
        assertTrue(ShellAccess.isServiceTransportFailure(
                new RemoteException("binder failed")));
        assertFalse(ShellAccess.isServiceTransportFailure(
                new IllegalStateException("operation rejected")));
    }

    @Test
    public void commandServiceReconnectNotifiesUnchangedRuntime() {
        final ShellAccess.Snapshot previous = snapshot(
                true, true, 2000, 13);
        final ShellAccess.Snapshot current = snapshot(
                true, true, 2000, 13);
        assertFalse(ShellAccess.shouldNotifyStateListeners(
                previous, current, false));
        assertTrue(ShellAccess.shouldNotifyStateListeners(
                previous, current, true));
    }

    private static ShellAccess.Snapshot snapshot(
            final boolean running,
            final boolean permissionGranted,
            final int uid,
            final int version) {
        return new ShellAccess.Snapshot(
                ShellBackend.SHIZUKU,
                true,
                running,
                permissionGranted,
                uid,
                version,
                "");
    }
}
