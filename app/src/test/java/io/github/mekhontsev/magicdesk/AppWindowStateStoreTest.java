package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.util.Collections;

public final class AppWindowStateStoreTest {
    @After
    public void restoreStorage() {
        AppWindowStateStore.clearPendingModeUpdatesForTests();
        DesktopStateStore.useStorageForTests(null);
    }

    @Test
    public void pendingModeIsVisibleBeforePersistentCommit() {
        DesktopStateStore.useStorageForTests(memoryStorage());
        final String stateKey = "example.application";
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
        final String stateKey = "example.application";
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
    public void cancelledPendingModeRestoresPersistedChoice() {
        DesktopStateStore.useStorageForTests(memoryStorage());
        final String stateKey = "example.application";
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
                Collections.singletonMap("example.application", bounds)));
        assertTrue(AppWindowStateStore.rememberMode(
                "example.application", AppWindowState.Mode.FULLSCREEN));
        final RelativeWindowBounds updatedBounds =
                new RelativeWindowBounds(7500, 1000, 4000, 6000);
        assertTrue(AppWindowStateStore.rememberWindowBounds(
                Collections.singletonMap(
                        "example.application", updatedBounds)));

        assertEquals(
                new AppWindowState(
                        AppWindowState.Mode.FULLSCREEN, updatedBounds),
                AppWindowStateStore.load("example.application"));
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
        final String filesKey = BuiltInDesktopAppCatalog.appIdentityKey(
                BuiltInDesktopAppCatalog.filesTarget());
        final String consoleKey = BuiltInDesktopAppCatalog.appIdentityKey(
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
        return new DesktopStateStore.Storage() {
            private String encoded = "";

            @Override
            public String read() {
                return encoded;
            }

            @Override
            public void write(final String value) {
                encoded = value;
            }
        };
    }
}
