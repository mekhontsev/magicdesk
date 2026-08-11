package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.util.Collections;

public final class AppWindowStateStoreTest {
    @After
    public void restoreStorage() {
        DesktopStateStore.useStorageForTests(null);
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
}
