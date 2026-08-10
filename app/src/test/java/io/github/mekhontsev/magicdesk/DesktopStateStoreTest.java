package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public final class DesktopStateStoreTest {
    @After
    public void restoreStorage() {
        DesktopStateStore.useStorageForTests(null);
    }

    @Test
    public void stateRoundTripPreservesDesktopConfiguration() throws Exception {
        final DesktopStateStore.State source = new DesktopStateStore.State();
        source.content.shortcuts.add(AppLaunchTarget.explicit(
                "example.application",
                "example.application.MainActivity",
                "android.intent.action.MAIN"));
        source.content.workspaceTarget =
                AppLaunchTarget.packageDefault("example.workspace");
        source.taskbarPackages.add("example.application");

        final DisplayProfileStore.Profile profile =
                new DisplayProfileStore.Profile("monitor:primary");
        profile.dpi = 160;
        profile.dpiExplicit = true;
        profile.workspaceBounds = new Rect();
        profile.workspaceBounds.left = 10;
        profile.workspaceBounds.top = 20;
        profile.workspaceBounds.right = 1010;
        profile.workspaceBounds.bottom = 720;
        profile.workspaceBoundsTarget = "example.workspace";
        profile.placements.put(
                "app:example.application",
                new DesktopPlacement(2, 3, 1, 2));
        source.displayProfiles.put(profile.monitorKey, profile);
        source.displayAliases.put("display:3", profile.monitorKey);

        final DesktopStateStore.State decoded = DesktopStateStore.decode(
                DesktopStateStore.encode(source));

        assertEquals(source.content.shortcuts, decoded.content.shortcuts);
        assertEquals(
                source.content.workspaceTarget,
                decoded.content.workspaceTarget);
        assertEquals(source.taskbarPackages, decoded.taskbarPackages);
        assertEquals("monitor:primary", decoded.displayAliases.get("display:3"));
        final DisplayProfileStore.Profile decodedProfile =
                decoded.displayProfiles.get("monitor:primary");
        assertEquals(160, decodedProfile.dpi);
        assertTrue(decodedProfile.dpiExplicit);
        assertEquals(10, decodedProfile.workspaceBounds.left);
        assertEquals(20, decodedProfile.workspaceBounds.top);
        assertEquals(1010, decodedProfile.workspaceBounds.right);
        assertEquals(720, decodedProfile.workspaceBounds.bottom);
        assertEquals(
                "example.workspace", decodedProfile.workspaceBoundsTarget);
        assertEquals(
                new DesktopPlacement(2, 3, 1, 2),
                decodedProfile.placements.get("app:example.application"));
    }

    @Test
    public void invalidEntriesAreIgnored() throws Exception {
        final DesktopStateStore.State decoded = DesktopStateStore.decode(
                "{\"format\":1,"
                        + "\"shortcuts\":[{\"package\":\"not a package\"}],"
                        + "\"taskbar\":[\"\",\"bad package\"],"
                        + "\"displayProfiles\":{\"wrong-key\":{"
                        + "\"monitor\":\"monitor:primary\"}},"
                        + "\"displayAliases\":{\"display:3\":\"\"}}" );

        assertTrue(decoded.content.shortcuts.isEmpty());
        assertNull(decoded.content.workspaceTarget);
        assertTrue(decoded.taskbarPackages.isEmpty());
        assertFalse(decoded.displayProfiles.containsKey("wrong-key"));
        assertTrue(decoded.displayAliases.isEmpty());
    }

    @Test
    public void concurrentUpdatesDoNotLoseState() throws Exception {
        final MemoryStorage storage = new MemoryStorage();
        DesktopStateStore.useStorageForTests(storage);
        final int workerCount = 8;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch complete = new CountDownLatch(workerCount);
        final List<Throwable> failures = Collections.synchronizedList(
                new ArrayList<>());
        for (int index = 0; index < workerCount; index++) {
            final String packageName = "example.application" + index;
            new Thread(() -> {
                try {
                    start.await();
                    assertTrue(DesktopStateStore.update(state ->
                            state.taskbarPackages.add(packageName)));
                } catch (Throwable error) {
                    failures.add(error);
                } finally {
                    complete.countDown();
                }
            }).start();
        }

        start.countDown();
        complete.await();

        assertTrue(failures.toString(), failures.isEmpty());
        final List<String> packages = DesktopStateStore.read(
                state -> new ArrayList<>(state.taskbarPackages),
                Collections.emptyList());
        assertEquals(workerCount, packages.size());
    }

    @Test
    public void failedWriteRollsBackInMemoryState() {
        final MemoryStorage storage = new MemoryStorage();
        DesktopStateStore.useStorageForTests(storage);
        assertTrue(DesktopStateStore.update(state ->
                state.taskbarPackages.add("example.before")));
        storage.failWrites = true;

        assertFalse(DesktopStateStore.update(state -> {
            state.taskbarPackages.clear();
            state.taskbarPackages.add("example.after");
        }));

        assertEquals(
                Collections.singletonList("example.before"),
                DesktopStateStore.read(
                        state -> new ArrayList<>(state.taskbarPackages),
                        Collections.emptyList()));
    }

    @Test
    public void profileCopiesDoNotExposeStoredMutableState() {
        final DisplayProfileStore.Profile source =
                new DisplayProfileStore.Profile("monitor:copy");
        source.dpi = 160;
        source.placements.put(
                "app:example", new DesktopPlacement(1, 2, 1, 1));

        final DisplayProfileStore.Profile copy = DisplayProfileStore.copy(source);
        copy.dpi = 240;
        copy.placements.clear();

        assertEquals(160, source.dpi);
        assertEquals(1, source.placements.size());
    }

    @Test
    public void readsCannotMutateStoredState() {
        DesktopStateStore.useStorageForTests(new MemoryStorage());
        assertTrue(DesktopStateStore.update(state ->
                state.taskbarPackages.add("example.saved")));

        DesktopStateStore.read(state -> {
            state.taskbarPackages.clear();
            return null;
        }, null);

        assertEquals(
                Collections.singletonList("example.saved"),
                DesktopStateStore.read(
                        state -> new ArrayList<>(state.taskbarPackages),
                        Collections.emptyList()));
    }

    private static final class MemoryStorage
            implements DesktopStateStore.Storage {
        String encoded = "";
        boolean failWrites;

        @Override
        public synchronized String read() {
            return encoded;
        }

        @Override
        public synchronized void write(final String value) throws IOException {
            if (failWrites) {
                throw new IOException("write failed");
            }
            encoded = value;
        }
    }
}
