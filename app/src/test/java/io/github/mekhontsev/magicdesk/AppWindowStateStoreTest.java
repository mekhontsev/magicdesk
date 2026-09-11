package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.util.Collections;

public final class AppWindowStateStoreTest {
    private static final DesktopWorkspaceRuntime WORKSPACE =
            new DesktopWorkspaceRuntime(DesktopDisplayTarget.wired(7));
    private static final AppReference APP = new AppProfile(0, 0)
            .reference(AppLaunchTarget.packageDefault("example.application"));

    @After
    public void restoreStorage() {
        AppWindowStateStore.clearPendingModeUpdatesForTests();
        DesktopStateStore.useStorageForTests(null);
    }

    @Test
    public void lateCloseDoesNotFlushReplacementSession() {
        final RecordingStorage storage = new RecordingStorage();
        DesktopStateStore.useStorageForTests(storage);
        AppWindowStateStore.beginSession(WORKSPACE, DesktopSessionPolicy.USER);
        final DesktopWorkspaceRuntime next =
                new DesktopWorkspaceRuntime(DesktopDisplayTarget.wired(7));
        AppWindowStateStore.beginSession(next, DesktopSessionPolicy.USER);
        AppWindowStateStore.rememberMode(APP, AppWindowState.Mode.FULLSCREEN);
        assertTrue(AppWindowStateStore.endSession(WORKSPACE));
        assertEquals(0, storage.writeCount);
        assertTrue(AppWindowStateStore.endSession(next));
        assertEquals(1, storage.writeCount);
    }

    @Test
    public void hostRecreationDoesNotResetIsolatedSessionBoundary() {
        final RecordingStorage storage = new RecordingStorage();
        DesktopStateStore.useStorageForTests(storage);
        AppWindowStateStore.rememberMode(APP, AppWindowState.Mode.WINDOWED);
        AppWindowStateStore.beginSession(WORKSPACE, DesktopSessionPolicy.ISOLATED_SELF_TEST);
        AppWindowStateStore.rememberMode(APP, AppWindowState.Mode.FULLSCREEN);
        AppWindowStateStore.beginSession(WORKSPACE, DesktopSessionPolicy.ISOLATED_SELF_TEST);
        assertTrue(AppWindowStateStore.endSession(WORKSPACE));
        assertEquals(AppWindowState.Mode.WINDOWED, AppWindowStateStore.load(APP).mode);
        assertEquals(1, storage.writeCount);
    }

    @Test
    public void pendingModeIsVisibleBeforePersistentCommit() {
        DesktopStateStore.useStorageForTests(memoryStorage());
        final AppReference stateKey = APP;
        assertTrue(AppWindowStateStore.rememberMode(
                stateKey, AppWindowState.Mode.WINDOWED));

        final AppWindowStateStore.PendingModeUpdate update =
                AppWindowStateStore.beginModeUpdate(
                        stateKey, AppWindowState.Mode.FULLSCREEN);

        assertEquals(
                AppWindowState.Mode.FULLSCREEN,
                AppWindowStateStore.load(stateKey).mode);
        assertTrue(AppWindowStateStore.commitModeUpdate(update));
        assertEquals(
                AppWindowState.Mode.FULLSCREEN,
                AppWindowStateStore.load(stateKey).mode);
    }

    @Test
    public void olderCompletionDoesNotHideNewerPendingMode() {
        DesktopStateStore.useStorageForTests(memoryStorage());
        final AppReference stateKey = APP;
        final AppWindowStateStore.PendingModeUpdate older =
                AppWindowStateStore.beginModeUpdate(
                        stateKey, AppWindowState.Mode.FULLSCREEN);
        final AppWindowStateStore.PendingModeUpdate newer =
                AppWindowStateStore.beginModeUpdate(
                        stateKey, AppWindowState.Mode.WINDOWED);

        assertTrue(AppWindowStateStore.commitModeUpdate(older));
        assertEquals(
                AppWindowState.Mode.WINDOWED,
                AppWindowStateStore.load(stateKey).mode);
        assertTrue(AppWindowStateStore.commitModeUpdate(newer));
        assertEquals(
                AppWindowState.Mode.WINDOWED,
                AppWindowStateStore.load(stateKey).mode);
    }

    @Test
    public void olderCompletionDoesNotReplaceNewerCommittedMode() {
        assertReorderedModeCommit(false);
    }

    @Test
    public void olderCompletionDoesNotReplaceNewerSessionMode() {
        assertReorderedModeCommit(true);
    }

    private static void assertReorderedModeCommit(final boolean session) {
        final RecordingStorage storage = new RecordingStorage();
        DesktopStateStore.useStorageForTests(storage);
        if (session) {
            AppWindowStateStore.beginSession(WORKSPACE, DesktopSessionPolicy.USER);
        }
        final AppReference key = APP;
        final AppWindowStateStore.PendingModeUpdate older =
                AppWindowStateStore.beginModeUpdate(
                        key, AppWindowState.Mode.FULLSCREEN);
        final AppWindowStateStore.PendingModeUpdate newer =
                AppWindowStateStore.beginModeUpdate(
                        key, AppWindowState.Mode.WINDOWED);

        assertTrue(AppWindowStateStore.commitModeUpdate(newer));
        assertTrue(AppWindowStateStore.commitModeUpdate(older));
        assertEquals(AppWindowState.Mode.WINDOWED,
                AppWindowStateStore.load(key).mode);
        assertTrue(AppWindowStateStore.endSession(WORKSPACE));
        DesktopStateStore.useStorageForTests(storage);
        assertEquals(AppWindowState.Mode.WINDOWED,
                AppWindowStateStore.load(key).mode);
        assertEquals(1, storage.writeCount);
    }

    @Test
    public void cancelledPendingModeRestoresPersistedChoice() {
        DesktopStateStore.useStorageForTests(memoryStorage());
        final AppReference stateKey = APP;
        assertTrue(AppWindowStateStore.rememberMode(
                stateKey, AppWindowState.Mode.WINDOWED));
        final AppWindowStateStore.PendingModeUpdate update =
                AppWindowStateStore.beginModeUpdate(
                        stateKey, AppWindowState.Mode.FULLSCREEN);

        AppWindowStateStore.cancelModeUpdate(update);

        assertEquals(
                AppWindowState.Mode.WINDOWED,
                AppWindowStateStore.load(stateKey).mode);
    }

    @Test
    public void desktopSessionFlushesCombinedWindowStateOnce() {
        final RecordingStorage storage = new RecordingStorage();
        DesktopStateStore.useStorageForTests(storage);
        final AppReference stateKey = APP;
        final RelativeWindowBounds bounds =
                new RelativeWindowBounds(2000, 1500, 6000, 7000);
        AppWindowStateStore.beginSession(WORKSPACE, DesktopSessionPolicy.USER);

        assertTrue(AppWindowStateStore.rememberWindowed(stateKey, bounds));
        assertTrue(AppWindowStateStore.rememberMode(
                stateKey, AppWindowState.Mode.FULLSCREEN));

        assertEquals(0, storage.writeCount);
        assertEquals(
                new AppWindowState(AppWindowState.Mode.FULLSCREEN, bounds),
                AppWindowStateStore.load(stateKey));
        assertTrue(AppWindowStateStore.endSession(WORKSPACE));
        assertEquals(1, storage.writeCount);

        DesktopStateStore.useStorageForTests(storage);
        assertEquals(
                new AppWindowState(AppWindowState.Mode.FULLSCREEN, bounds),
                AppWindowStateStore.load(stateKey));
    }

    @Test
    public void emptyDesktopSessionDoesNotWriteState() {
        final RecordingStorage storage = new RecordingStorage();
        DesktopStateStore.useStorageForTests(storage);
        AppWindowStateStore.beginSession(WORKSPACE, DesktopSessionPolicy.USER);

        assertTrue(AppWindowStateStore.endSession(WORKSPACE));

        assertEquals(0, storage.writeCount);
    }

    @Test
    public void isolatedSessionDiscardsOnlyItsWindowStateChanges() {
        final RecordingStorage storage = new RecordingStorage();
        DesktopStateStore.useStorageForTests(storage);
        final AppReference stateKey = APP;
        assertTrue(AppWindowStateStore.rememberMode(
                stateKey, AppWindowState.Mode.WINDOWED));
        final int writesBeforeTest = storage.writeCount;

        AppWindowStateStore.beginSession(
                WORKSPACE, DesktopSessionPolicy.ISOLATED_SELF_TEST);
        assertTrue(AppWindowStateStore.rememberMode(
                stateKey, AppWindowState.Mode.FULLSCREEN));
        assertEquals(
                AppWindowState.Mode.FULLSCREEN,
                AppWindowStateStore.load(stateKey).mode);

        assertTrue(AppWindowStateStore.endSession(WORKSPACE));
        assertEquals(writesBeforeTest, storage.writeCount);
        assertEquals(
                AppWindowState.Mode.WINDOWED,
                AppWindowStateStore.load(stateKey).mode);
    }

    @Test
    public void fullscreenModeKeepsLastWindowBounds() {
        DesktopStateStore.useStorageForTests(
                new DesktopStateStore.Storage() {
                    private String encoded = "";

                    @Override
                    public String read() {
                        return encoded;
                    }

                    @Override
                    public void write(final String value) {
                        encoded = value;
                    }
                });
        final RelativeWindowBounds bounds =
                new RelativeWindowBounds(2500, 5000, 5000, 4000);

        assertTrue(AppWindowStateStore.rememberWindowBounds(
                Collections.singletonMap(APP, bounds)));
        assertTrue(AppWindowStateStore.rememberMode(
                APP, AppWindowState.Mode.FULLSCREEN));
        final RelativeWindowBounds updatedBounds =
                new RelativeWindowBounds(7500, 1000, 4000, 6000);
        assertTrue(AppWindowStateStore.rememberWindowBounds(
                Collections.singletonMap(
                        APP, updatedBounds)));

        assertEquals(
                new AppWindowState(
                        AppWindowState.Mode.FULLSCREEN, updatedBounds),
                AppWindowStateStore.load(APP));
    }

    @Test
    public void builtInWindowsKeepIndependentBounds() {
        final String[] encoded = {""};
        final DesktopStateStore.Storage storage =
                new DesktopStateStore.Storage() {

                    @Override
                    public String read() {
                        return encoded[0];
                    }

                    @Override
                    public void write(final String value) {
                        encoded[0] = value;
                    }
                };
        DesktopStateStore.useStorageForTests(storage);
        final AppReference filesKey = new AppProfile(0, 0).reference(
                BuiltInDesktopAppCatalog.filesTarget());
        final AppReference consoleKey = new AppProfile(0, 0).reference(
                BuiltInDesktopAppCatalog.consoleTarget());
        final RelativeWindowBounds filesBounds =
                new RelativeWindowBounds(2000, 3000, 5000, 6000);
        final RelativeWindowBounds consoleBounds =
                new RelativeWindowBounds(4000, 1000, 4500, 7000);

        assertTrue(AppWindowStateStore.rememberWindowBounds(
                Collections.singletonMap(filesKey, filesBounds)));
        assertTrue(AppWindowStateStore.rememberWindowBounds(
                Collections.singletonMap(consoleKey, consoleBounds)));
        DesktopStateStore.useStorageForTests(storage);

        assertEquals(
                filesBounds,
                AppWindowStateStore.load(filesKey).windowBounds);
        assertEquals(
                consoleBounds,
                AppWindowStateStore.load(consoleKey).windowBounds);
    }

    private static DesktopStateStore.Storage memoryStorage() {
        return new RecordingStorage();
    }

    private static final class RecordingStorage
            implements DesktopStateStore.Storage {
        private String encoded = "";
        private int writeCount;

        @Override
        public String read() {
            return encoded;
        }

        @Override
        public void write(final String value) {
            encoded = value;
            writeCount++;
        }
    }
}
