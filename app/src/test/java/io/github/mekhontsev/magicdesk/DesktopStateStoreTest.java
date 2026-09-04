package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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
        source.taskbarPackages.add("example.application");
        source.desktopPlacements.put(
                "file:Example.desktop",
                new GlobalDesktopPlacement(7500, 2500, 1, 2));
        source.appWindows.put(
                "example.application",
                new AppWindowState(
                        AppWindowState.Mode.FULLSCREEN,
                        new RelativeWindowBounds(8000, 1000, 4000, 6000)));
        source.appWindows.put(
                "example.bounds",
                new AppWindowState(
                        null,
                        new RelativeWindowBounds(1000, 2000, 3000, 4000)));
        source.appPresentations.put(
                "example.application",
                new AppPresentationProfile(125));
        source.settings.taskbarAutoHide = true;
        source.settings.keepDesktopAwake = true;
        source.settings.disableAdaptiveBrightnessOnExternalDesktop = true;
        source.settings.openTouchpadAutomatically = false;
        source.settings.openFilesWithSingleClick = true;
        source.settings.termuxX11StartupCommand =
                "termux-x11 :2 -xstartup \"openbox-session\"";

        final DisplayProfileStore.Profile profile =
                new DisplayProfileStore.Profile("display:primary");
        profile.dpi = 160;
        profile.dpiExplicit = true;
        profile.fillDisplay = false;
        profile.outputTiming = "2560x1440@120";
        profile.resetOutputModePending = true;
        source.displayProfiles.put(profile.key, profile);

        final DesktopStateStore.State decoded = DesktopStateStore.decode(
                DesktopStateStore.encode(source));

        assertEquals(source.taskbarPackages, decoded.taskbarPackages);
        assertEquals(
                new GlobalDesktopPlacement(7500, 2500, 1, 2),
                decoded.desktopPlacements.get("file:Example.desktop"));
        assertEquals(
                new AppWindowState(
                        AppWindowState.Mode.FULLSCREEN,
                        new RelativeWindowBounds(8000, 1000, 4000, 6000)),
                decoded.appWindows.get("example.application"));
        assertEquals(
                new AppWindowState(
                        null,
                        new RelativeWindowBounds(1000, 2000, 3000, 4000)),
                decoded.appWindows.get("example.bounds"));
        assertEquals(
                125,
                decoded.appPresentations.get(
                        "example.application").scalePercent);
        assertTrue(decoded.settings.taskbarAutoHide);
        assertTrue(decoded.settings.keepDesktopAwake);
        assertTrue(decoded.settings.disableAdaptiveBrightnessOnExternalDesktop);
        assertFalse(decoded.settings.openTouchpadAutomatically);
        assertTrue(decoded.settings.openFilesWithSingleClick);
        assertEquals(
                source.settings.termuxX11StartupCommand,
                decoded.settings.termuxX11StartupCommand);
        final DisplayProfileStore.Profile decodedProfile =
                decoded.displayProfiles.get("display:primary");
        assertEquals(160, decodedProfile.dpi);
        assertTrue(decodedProfile.dpiExplicit);
        assertFalse(decodedProfile.fillDisplay);
        assertEquals("2560x1440@120", decodedProfile.outputTiming);
        assertTrue(decodedProfile.resetOutputModePending);
    }

    @Test
    public void invalidEntriesAreIgnored() throws Exception {
        final DesktopStateStore.State decoded = DesktopStateStore.decode(
                "{\"format\":1,"
                        + "\"taskbar\":[\"\",\"bad package\"],"
                        + "\"desktopPlacements\":{"
                        + "\"bad\":[-1,0,1,1]},"
                        + "\"appWindows\":{"
                        + "\"bad package\":{\"mode\":\"windowed\"}},"
                        + "\"appPresentations\":{"
                        + "\"bad package\":100,"
                        + "\"" + BuildConfig.APPLICATION_ID + "\":100,"
                        + "\"example.too.small\":49,"
                        + "\"example.too.large\":201},"
                        + "\"displayProfiles\":{\"wrong-key\":{"
                        + "\"key\":\"display:primary\"}}}" );

        assertTrue(decoded.taskbarPackages.isEmpty());
        assertTrue(decoded.desktopPlacements.isEmpty());
        assertTrue(decoded.appWindows.isEmpty());
        assertTrue(decoded.appPresentations.isEmpty());
        assertFalse(decoded.displayProfiles.containsKey("wrong-key"));
        assertTrue(decoded.settings.openTouchpadAutomatically);
        assertFalse(
                decoded.settings.disableAdaptiveBrightnessOnExternalDesktop);
        assertFalse(decoded.settings.openFilesWithSingleClick);
        assertEquals(
                TermuxX11StartupCommand.DEFAULT,
                decoded.settings.termuxX11StartupCommand);
    }

    @Test
    public void obsoleteAppShortcutsAndPlacementsAreIgnored()
            throws Exception {
        final DesktopStateStore.State decoded = DesktopStateStore.decode(
                "{\"format\":1,"
                        + "\"shortcuts\":[{\"package\":"
                        + "\"example.application\"}],"
                        + "\"desktopPlacements\":{"
                        + "\"app:example.application\":[1,2,1,1],"
                        + "\"file:Example.desktop\":[3,4,1,1]}}" );

        final String encoded = DesktopStateStore.encode(decoded);
        assertFalse(encoded.contains("shortcuts"));
        assertFalse(decoded.desktopPlacements.containsKey(
                "app:example.application"));
        assertTrue(decoded.desktopPlacements.containsKey(
                "file:Example.desktop"));
    }

    @Test
    public void obsoleteWorkspaceTargetIsIgnored() throws Exception {
        final DesktopStateStore.State decoded = DesktopStateStore.decode(
                "{\"format\":1,\"workspaceTarget\":{"
                        + "\"package\":\"example.workspace\"}}");

        assertFalse(DesktopStateStore.encode(decoded)
                .contains("workspaceTarget"));
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
                new DisplayProfileStore.Profile("display:copy");
        source.dpi = 160;
        source.fillDisplay = false;
        source.outputTiming = "1920x1080@60";
        source.resetOutputModePending = true;

        final DisplayProfileStore.Profile copy = DisplayProfileStore.copy(source);
        copy.dpi = 240;
        copy.fillDisplay = true;
        copy.outputTiming = null;
        copy.resetOutputModePending = false;

        assertEquals(160, source.dpi);
        assertFalse(source.fillDisplay);
        assertEquals("1920x1080@60", source.outputTiming);
        assertTrue(source.resetOutputModePending);
    }

    @Test
    public void systemOutputResetRemainsPendingUntilConsumed() {
        final DisplayProfileStore.Profile profile =
                new DisplayProfileStore.Profile("display:one");
        profile.outputTiming = "1920x1080@60";

        DisplayProfileStore.setOutputTiming(profile, null);
        DisplayProfileStore.setOutputTiming(profile, null);

        assertNull(profile.outputTiming);
        assertTrue(profile.resetOutputModePending);

        DisplayProfileStore.setOutputTiming(profile, "2560x1440@60");

        assertEquals("2560x1440@60", profile.outputTiming);
        assertFalse(profile.resetOutputModePending);
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

    @Test
    public void loadingDefaultProfileDoesNotPersistIt() {
        final MemoryStorage storage = new MemoryStorage();
        DesktopStateStore.useStorageForTests(storage);

        final DisplayProfileStore.Profile profile =
                DisplayProfileStore.load("display:test", 160);

        assertEquals("display:test", profile.key);
        assertEquals(160, profile.dpi);
        assertTrue(storage.encoded.isEmpty());
        assertTrue(DisplayProfileStore.save(profile));
        assertFalse(storage.encoded.isEmpty());
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
