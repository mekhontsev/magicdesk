package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Point;

import org.junit.After;
import org.junit.Test;

public final class MagicDeskRuntimeTest {
    private FakeBackend mAttached;

    @After
    public void detachBackend() {
        MagicDeskRuntime.detach(mAttached);
    }

    @Test
    public void missingBackendUsesSafeDefaults() {
        final boolean[] parkingResult = {true};
        final boolean[] desktopReleaseCompleted = {false};

        MagicDeskRuntime.parkDesktopTasks(
                DesktopDisplayTarget.wired(7),
                success -> parkingResult[0] = success);
        MagicDeskRuntime.releaseDesktopTaskSession(
                () -> desktopReleaseCompleted[0] = true);

        assertFalse(MagicDeskRuntime.isSessionWakeLockHeld());
        assertFalse(MagicDeskRuntime.isDesktopMouseBridgeReady());
        assertFalse(MagicDeskRuntime.capturePointerPosition());
        assertNull(MagicDeskRuntime.getDesktopPointerPosition(7));
        assertFalse(MagicDeskRuntime.showStart());
        assertFalse(MagicDeskRuntime.toggleDesktopWorkspace());
        assertFalse(MagicDeskRuntime.restoreLastVisibleWindows());
        assertFalse(MagicDeskRuntime.advanceAltTab(false));
        assertFalse(MagicDeskRuntime.finishAltTab());
        assertFalse(MagicDeskRuntime.cancelAltTab());
        assertFalse(MagicDeskRuntime.toggleShortcutHelp());
        assertFalse(MagicDeskRuntime.toggleNotificationCenter());
        assertFalse(MagicDeskRuntime.toggleSystemPanel());
        assertFalse(MagicDeskRuntime.openSettings());
        assertFalse(parkingResult[0]);
        assertTrue(desktopReleaseCompleted[0]);
    }

    @Test
    public void activeBackendReceivesRuntimeOperations() {
        mAttached = new FakeBackend(true);
        MagicDeskRuntime.attach(mAttached);

        MagicDeskRuntime.refreshDesktopTasks();
        MagicDeskRuntime.refreshPlatformState();
        MagicDeskRuntime.refreshSettings(
                () -> mAttached.settingsRefreshCompleted = true);
        MagicDeskRuntime.releaseDesktopTaskSession(
                () -> mAttached.desktopReleaseCompleted = true);
        MagicDeskRuntime.reactivatePointerOnNextMotion();
        assertTrue(MagicDeskRuntime.prepareDesktopDisplayRemoval(7));
        MagicDeskRuntime.cancelDesktopDisplayRemoval(7);
        MagicDeskRuntime.preserveDesktopTasks(7);
        MagicDeskRuntime.clearParkedDesktopTasks();

        assertTrue(MagicDeskRuntime.showStart());
        assertTrue(MagicDeskRuntime.toggleDesktopWorkspace());
        assertTrue(MagicDeskRuntime.restoreLastVisibleWindows());
        assertTrue(MagicDeskRuntime.advanceAltTab(true));
        assertTrue(MagicDeskRuntime.finishAltTab());
        assertTrue(MagicDeskRuntime.cancelAltTab());
        assertTrue(MagicDeskRuntime.toggleShortcutHelp());
        assertTrue(MagicDeskRuntime.toggleNotificationCenter());
        assertTrue(MagicDeskRuntime.toggleSystemPanel());
        assertTrue(MagicDeskRuntime.openSettings());
        assertTrue(mAttached.desktopTasksRefreshed);
        assertTrue(mAttached.platformStateRefreshed);
        assertTrue(mAttached.settingsRefreshed);
        assertTrue(mAttached.settingsRefreshCompleted);
        assertTrue(mAttached.desktopSessionReleased);
        assertTrue(mAttached.desktopReleaseCompleted);
        assertTrue(mAttached.pointerReactivationRequested);
        assertEquals(7, mAttached.pointerSuspensionDisplayId);
        assertEquals(7, mAttached.pointerSuspensionCancelledDisplayId);
        assertEquals(7, mAttached.preservedDesktopDisplayId);
        assertTrue(mAttached.parkingCleared);
        assertTrue(mAttached.startShown);
        assertEquals(0x1ff, mAttached.uiCommands);
    }

    @Test
    public void inactiveBackendIsNotUsed() {
        mAttached = new FakeBackend(false);
        MagicDeskRuntime.attach(mAttached);

        MagicDeskRuntime.refreshDesktopTasks();

        assertFalse(MagicDeskRuntime.showStart());
        assertFalse(mAttached.desktopTasksRefreshed);
        assertFalse(mAttached.startShown);
    }

    @Test
    public void staleDetachDoesNotRemoveReplacementBackend() {
        final FakeBackend stale = new FakeBackend(true);
        mAttached = new FakeBackend(true);
        MagicDeskRuntime.attach(stale);
        MagicDeskRuntime.attach(mAttached);

        MagicDeskRuntime.detach(stale);

        assertTrue(MagicDeskRuntime.showStart());
        assertTrue(mAttached.startShown);
        assertFalse(stale.startShown);
    }

    private static final class FakeBackend
            implements MagicDeskRuntimeBackend {
        private final boolean mAvailable;
        private boolean desktopTasksRefreshed;
        private boolean platformStateRefreshed;
        private boolean settingsRefreshed;
        private boolean settingsRefreshCompleted;
        private boolean desktopSessionReleased;
        private boolean desktopReleaseCompleted;
        private boolean pointerReactivationRequested;
        private int pointerSuspensionDisplayId = -1;
        private int pointerSuspensionCancelledDisplayId = -1;
        private int preservedDesktopDisplayId = -1;
        private boolean parkingCleared;
        private boolean startShown;
        private int uiCommands;
        private final DesktopTaskParkingRuntime mParking =
                new DesktopTaskParkingRuntime() {
                    @Override
                    public void park(
                            final DesktopDisplayTarget source,
                            final ResultCallback callback) {
                        if (callback != null) {
                            callback.onComplete(true);
                        }
                    }

                    @Override
                    public void preserve(final int displayId) {
                        preservedDesktopDisplayId = displayId;
                    }

                    @Override
                    public void restoreWhenReady(
                            final DesktopDisplayTarget target) {
                    }

                    @Override
                    public void onDesktopHostReady(final int displayId) {
                    }

                    @Override
                    public void clear() {
                        parkingCleared = true;
                    }
                };

        FakeBackend(final boolean available) {
            mAvailable = available;
        }

        @Override
        public boolean isAvailable() {
            return mAvailable;
        }

        @Override
        public boolean isDesktopRuntimeInitialized() {
            return true;
        }

        @Override
        public void prepareForStop(final Runnable completion) {
            completion.run();
        }

        @Override
        public void releaseDesktopTaskSession(final Runnable completion) {
            desktopSessionReleased = true;
            completion.run();
        }

        @Override
        public void refreshNotification() {
        }

        @Override
        public void setOperationStatus(final String status) {
        }

        @Override
        public void refreshDesktopTasks() {
            desktopTasksRefreshed = true;
        }

        @Override
        public void refreshPlatformState() {
            platformStateRefreshed = true;
        }

        @Override
        public void refreshSettings(final Runnable completion) {
            settingsRefreshed = true;
            if (completion != null) {
                completion.run();
            }
        }

        @Override
        public boolean isSessionWakeLockHeld() {
            return true;
        }

        @Override
        public void reconcileFailedDesktopLaunch(final int displayId) {
        }

        @Override
        public void scheduleLocalDesktopCleanup() {
        }

        @Override
        public boolean isDesktopMouseBridgeReady() {
            return true;
        }

        @Override
        public DesktopPointerState getDesktopPointerState(
                final int displayId) {
            return new DesktopPointerState(
                    displayId, "test", true, true, true,
                    new Point(10, 20));
        }

        @Override
        public InputRelayRuntimeDiagnostics.Snapshot
                captureInputRelayDiagnostics() {
            return InputRelayRuntimeDiagnostics.Snapshot.unavailable();
        }

        @Override
        public boolean capturePointerPosition() {
            return true;
        }

        @Override
        public void restorePointerPositionOnNextMotion() {
        }

        @Override
        public void reactivatePointerOnNextMotion() {
            pointerReactivationRequested = true;
        }

        @Override
        public boolean prepareDesktopDisplayRemoval(
                final int displayId) {
            pointerSuspensionDisplayId = displayId;
            return true;
        }

        @Override
        public void cancelDesktopDisplayRemoval(final int displayId) {
            pointerSuspensionCancelledDisplayId = displayId;
        }

        @Override
        public Point getDesktopPointerPosition(final int displayId) {
            return new Point(10, 20);
        }

        @Override
        public boolean updateDesktopPointerPosition(
                final int displayId,
                final int x,
                final int y,
                final int action,
                final long downTime) {
            return true;
        }

        @Override
        public boolean activateDesktopPointer(final int displayId) {
            return true;
        }

        @Override
        public boolean clickDesktopPointer(
                final int displayId, final int button) {
            return true;
        }

        @Override
        public boolean scrollDesktopPointer(
                final int displayId, final float amount) {
            return true;
        }

        @Override
        public boolean updateDesktopTextInput(
                final int displayId,
                final int action,
                final String text,
                final int arg1,
                final int arg2,
                final int arg3) {
            return true;
        }

        @Override
        public boolean beginDesktopTextInput(final int displayId) {
            return true;
        }

        @Override
        public void endDesktopTextInput(final int displayId) {
        }

        @Override
        public boolean showStart() {
            startShown = true;
            return true;
        }

        @Override
        public boolean toggleDesktopWorkspace() {
            uiCommands |= 1;
            return true;
        }

        @Override
        public boolean toggleDesktopWorkspace(
                final TaskRepository.ActionCallback callback) {
            uiCommands |= 1;
            if (callback != null) {
                callback.onComplete(
                        new TaskRepository.ActionResult(true, "completed"));
            }
            return true;
        }

        @Override
        public boolean restoreLastVisibleWindows() {
            uiCommands |= 256;
            return true;
        }

        @Override
        public boolean advanceAltTab(final boolean reverse) {
            if (reverse) {
                uiCommands |= 2;
            }
            return true;
        }

        @Override
        public boolean finishAltTab() {
            uiCommands |= 4;
            return true;
        }

        @Override
        public boolean cancelAltTab() {
            uiCommands |= 8;
            return true;
        }

        @Override
        public boolean toggleShortcutHelp() {
            uiCommands |= 16;
            return true;
        }

        @Override
        public boolean toggleNotificationCenter() {
            uiCommands |= 32;
            return true;
        }

        @Override
        public boolean toggleSystemPanel() {
            uiCommands |= 64;
            return true;
        }

        @Override
        public boolean openSettings() {
            uiCommands |= 128;
            return true;
        }

        @Override
        public DesktopTaskRuntime desktopTasks() {
            return null;
        }

        @Override
        public DesktopTaskParkingRuntime desktopTaskParking() {
            return mParking;
        }
    }
}
