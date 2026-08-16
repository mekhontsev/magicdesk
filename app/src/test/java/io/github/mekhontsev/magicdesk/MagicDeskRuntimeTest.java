package io.github.mekhontsev.magicdesk;

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
        assertFalse(MagicDeskRuntime.isSessionWakeLockHeld());
        assertFalse(MagicDeskRuntime.isDesktopMouseBridgeReady());
        assertFalse(MagicDeskRuntime.capturePointerPosition());
        assertNull(MagicDeskRuntime.getDesktopPointerPosition(7));
        assertFalse(MagicDeskRuntime.showStart());
    }

    @Test
    public void activeBackendReceivesRuntimeOperations() {
        mAttached = new FakeBackend(true);
        MagicDeskRuntime.attach(mAttached);

        MagicDeskRuntime.refreshDesktopTasks();

        assertTrue(MagicDeskRuntime.showStart());
        assertTrue(mAttached.desktopTasksRefreshed);
        assertTrue(mAttached.startShown);
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
        private boolean startShown;

        FakeBackend(final boolean available) {
            mAvailable = available;
        }

        @Override
        public boolean isAvailable() {
            return mAvailable;
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
        public void refreshSettings() {
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
        public boolean capturePointerPosition() {
            return true;
        }

        @Override
        public void restorePointerPositionOnNextMotion() {
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
        public DesktopTaskRuntime desktopTasks() {
            return null;
        }
    }
}
