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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DesktopStateStoreTest {
    private static AppReference app(final String name) {
        return new AppProfile(0, 0).reference(AppLaunchTarget.packageDefault(name));
    }

    @After
    public void restoreStorage() {
        DesktopStateStore.useStorageForTests(null);
    }

    @Test
    public void resetCompatibilityPersistsWithoutReplacingOtherState() throws Exception {
        final MemoryStorage storage = new MemoryStorage();
        DesktopStateStore.useStorageForTests(storage);
        assertTrue(DesktopStateStore.update(state -> {
            state.taskbarApps.add(app("example.application"));
            state.appPresentations.put(app("example.application").application,
                    new AppPresentationProfile(125));
            state.settings.keepDesktopAwake = true;
            state.settings.compatibility.put(DesktopCompatibilityPolicy.Option.FOCUS_REPAIR, false);
        }));
        assertTrue(MagicDeskSettings.resetCompatibilityOptions());
        DesktopStateStore.useStorageForTests(storage);
        assertTrue(MagicDeskSettings.load().compatibility.isEmpty());
        assertTrue(MagicDeskSettings.load().keepDesktopAwake);
        assertTrue(DesktopStateStore.read(state -> state.taskbarApps.contains(app("example.application")), false));
        assertTrue(DesktopStateStore.read(state -> state.appPresentations.containsKey(
                app("example.application").application), false));
    }

    @Test
    public void stateRoundTripPreservesDesktopConfiguration() throws Exception {
        final DesktopStateStore.State source = new DesktopStateStore.State();
        source.taskbarApps.add(app("example.application"));
        source.desktopPlacements.put(
                "file:Example.desktop",
                new GlobalDesktopPlacement(7500, 2500, 1, 2));
        source.appWindows.put(
                app("example.application"),
                new AppWindowState(
                        AppWindowState.Mode.FULLSCREEN,
                        new RelativeWindowBounds(8000, 1000, 4000, 6000)));
        source.appWindows.put(
                app("example.bounds"),
                new AppWindowState(
                        null,
                        new RelativeWindowBounds(1000, 2000, 3000, 4000)));
        source.appPresentations.put(
                app("example.application").application,
                new AppPresentationProfile(125));
        source.settings.taskbarAutoHide = true;
        source.settings.keepDesktopAwake = true;
        source.settings.disableAdaptiveBrightnessOnExternalDesktop = true;
        source.settings.openTouchpadAutomatically = false;
        source.settings.compatibility.put(DesktopCompatibilityPolicy.Option.FOCUS_REPAIR, true);
        source.settings.openFilesWithSingleClick = true;
        source.settings.termuxX11StartupCommand =
                "termux-x11 :2 -xstartup \"openbox-session\"";

        final DisplayProfileStore.Profile profile =
                new DisplayProfileStore.Profile("display:primary");
        profile.dpi = 160;
        profile.dpiExplicit = true;
        profile.outputTiming = "2560x1440@120";
        profile.resetOutputModePending = true;
        source.displayProfiles.put(profile.key, profile);

        final DesktopStateStore.State decoded = DesktopStateStore.decode(
                DesktopStateStore.encode(source));

        assertEquals(source.taskbarApps, decoded.taskbarApps);
        assertEquals(
                new GlobalDesktopPlacement(7500, 2500, 1, 2),
                decoded.desktopPlacements.get("file:Example.desktop"));
        assertEquals(
                new AppWindowState(
                        AppWindowState.Mode.FULLSCREEN,
                        new RelativeWindowBounds(8000, 1000, 4000, 6000)),
                decoded.appWindows.get(app("example.application")));
        assertEquals(
                new AppWindowState(
                        null,
                        new RelativeWindowBounds(1000, 2000, 3000, 4000)),
                decoded.appWindows.get(app("example.bounds")));
        assertEquals(
                125,
                decoded.appPresentations.get(
                        app("example.application").application).scalePercent);
        assertTrue(decoded.settings.taskbarAutoHide);
        assertTrue(decoded.settings.keepDesktopAwake);
        assertTrue(decoded.settings.disableAdaptiveBrightnessOnExternalDesktop);
        assertFalse(decoded.settings.openTouchpadAutomatically);
        assertEquals(Boolean.TRUE, decoded.settings.compatibility.get(DesktopCompatibilityPolicy.Option.FOCUS_REPAIR));
        assertTrue(decoded.settings.openFilesWithSingleClick);
        assertEquals(
                source.settings.termuxX11StartupCommand,
                decoded.settings.termuxX11StartupCommand);
        final DisplayProfileStore.Profile decodedProfile =
                decoded.displayProfiles.get("display:primary");
        assertEquals(160, decodedProfile.dpi);
        assertTrue(decodedProfile.dpiExplicit);
        assertEquals("2560x1440@120", decodedProfile.outputTiming);
        assertTrue(decodedProfile.resetOutputModePending);
    }

    @Test
    public void inputRelayPreferencePreservesUnsetAndExplicitOff() throws Exception {
        final MagicDeskSettings.Values defaults = MagicDeskSettings.Values.defaults();
        assertNull(MagicDeskSettings.Values.fromJson(defaults.toJson()).compatibility.get(DesktopCompatibilityPolicy.Option.FOCUS_REPAIR));
        defaults.compatibility.put(DesktopCompatibilityPolicy.Option.FOCUS_REPAIR, false);
        assertEquals(Boolean.FALSE, defaults.copy().compatibility.get(DesktopCompatibilityPolicy.Option.FOCUS_REPAIR));
        assertEquals(Boolean.FALSE,
                MagicDeskSettings.Values.fromJson(defaults.toJson()).compatibility.get(DesktopCompatibilityPolicy.Option.FOCUS_REPAIR));
    }

    @Test
    public void invalidEntriesAreIgnored() throws Exception {
        final DesktopStateStore.State decoded = DesktopStateStore.decode(
                "{\"format\":" + DesktopStateStore.FORMAT + ","
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

        assertTrue(decoded.taskbarApps.isEmpty());
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

    @Test(expected = org.json.JSONException.class)
    public void previousFormatIsRejected() throws Exception {
        DesktopStateStore.decode("{\"format\":1}");
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
                    assertTrue(start.await(5L, TimeUnit.SECONDS));
                    assertTrue(DesktopStateStore.update(state ->
                            state.taskbarApps.add(app(packageName))));
                } catch (Throwable error) {
                    failures.add(error);
                } finally {
                    complete.countDown();
                }
            }).start();
        }

        start.countDown();
        assertTrue(complete.await(10L, TimeUnit.SECONDS));

        assertTrue(failures.toString(), failures.isEmpty());
        final List<AppReference> packages = DesktopStateStore.read(
                state -> new ArrayList<>(state.taskbarApps),
                Collections.emptyList());
        assertEquals(workerCount, packages.size());
    }

    @Test
    public void failedWriteRollsBackInMemoryState() {
        final MemoryStorage storage = new MemoryStorage();
        DesktopStateStore.useStorageForTests(storage);
        assertTrue(DesktopStateStore.update(state ->
                state.taskbarApps.add(app("example.before"))));
        storage.failWrites = true;

        assertFalse(DesktopStateStore.update(state -> {
            state.taskbarApps.clear();
            state.taskbarApps.add(app("example.after"));
        }));

        assertEquals(
                Collections.singletonList(app("example.before")),
                DesktopStateStore.read(
                        state -> new ArrayList<>(state.taskbarApps),
                        Collections.emptyList()));
    }

    @Test
    public void reloadSerializesReadAndPublicationWithLocalSave() throws Exception {
        final MemoryStorage storage = new MemoryStorage();
        final AtomicBoolean holdNextRead = new AtomicBoolean();
        final CountDownLatch reading = new CountDownLatch(1);
        final CountDownLatch releaseRead = new CountDownLatch(1);
        DesktopStateStore.useStorageForTests(new DesktopStateStore.Storage() {
            @Override
            public String read() throws IOException {
                final String encoded = storage.read();
                if (holdNextRead.compareAndSet(true, false)) {
                    reading.countDown();
                    try {
                        if (!releaseRead.await(2L, TimeUnit.SECONDS)) {
                            throw new IOException("test read was not released");
                        }
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IOException(error);
                    }
                }
                return encoded;
            }

            @Override
            public void write(final String encoded) throws IOException {
                storage.write(encoded);
            }
        });
        assertTrue(DesktopStateStore.update(state ->
                state.taskbarApps.add(app("example.before"))));
        holdNextRead.set(true);
        final ExecutorService worker = Executors.newFixedThreadPool(2);
        final CountDownLatch updating = new CountDownLatch(1);
        final CountDownLatch mutated = new CountDownLatch(1);
        try {
            final Future<Boolean> reload = worker.submit(DesktopStateStore::reload);
            assertTrue(reading.await(2L, TimeUnit.SECONDS));
            final Future<Boolean> update = worker.submit(() -> {
                updating.countDown();
                return DesktopStateStore.update(state -> {
                    mutated.countDown();
                    state.taskbarApps.add(app("example.after"));
                });
            });
            assertTrue(updating.await(2L, TimeUnit.SECONDS));
            assertFalse(mutated.await(100L, TimeUnit.MILLISECONDS));
            releaseRead.countDown();
            assertFalse(reload.get(2L, TimeUnit.SECONDS));
            assertTrue(update.get(2L, TimeUnit.SECONDS));

            final List<AppReference> expected = List.of(app("example.before"), app("example.after"));
            assertEquals(expected, DesktopStateStore.read(
                    state -> state.taskbarApps, List.of()));
            assertEquals(expected, DesktopStateStore.decode(storage.read()).taskbarApps);
        } finally {
            releaseRead.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(2L, TimeUnit.SECONDS));
        }
    }

    @Test
    public void uncommittedMutationDoesNotChangePublishedState() {
        DesktopStateStore.useStorageForTests(new MemoryStorage());
        assertTrue(DesktopStateStore.update(state ->
                state.taskbarApps.add(app("example.before"))));

        assertTrue(DesktopStateStore.update(state -> {
            state.taskbarApps.add(app("example.after"));
            assertEquals(List.of(app("example.before")), DesktopStateStore.read(
                    published -> published.taskbarApps, List.of()));
        }));
        assertEquals(List.of(app("example.before"), app("example.after")), DesktopStateStore.read(
                state -> state.taskbarApps, List.of()));
    }

    @Test
    public void failedMutationAndEncodingLeavePublishedStateUnchanged() {
        DesktopStateStore.useStorageForTests(new MemoryStorage());
        assertTrue(DesktopStateStore.update(state ->
                state.taskbarApps.add(app("example.before"))));

        assertFalse(DesktopStateStore.update(state -> {
            state.taskbarApps.clear();
            throw new IllegalArgumentException("invalid mutation");
        }));
        assertFalse(DesktopStateStore.update(state -> {
            state.taskbarApps.clear();
            state.settings = null;
        }));
        assertEquals(List.of(app("example.before")), DesktopStateStore.read(
                state -> state.taskbarApps, List.of()));
    }

    @Test
    public void reloadPublishesValidExternalStateButKeepsStateOnInvalidInput() throws Exception {
        final MemoryStorage storage = new MemoryStorage();
        DesktopStateStore.useStorageForTests(storage);
        assertTrue(DesktopStateStore.update(state ->
                state.taskbarApps.add(app("example.before"))));
        final DesktopStateStore.State external = new DesktopStateStore.State();
        external.taskbarApps.add(app("example.external"));
        storage.write(DesktopStateStore.encode(external));

        assertTrue(DesktopStateStore.reload());
        assertFalse(DesktopStateStore.reload());
        storage.write("{invalid");
        assertFalse(DesktopStateStore.reload());
        assertEquals(List.of(app("example.external")), DesktopStateStore.read(
                state -> state.taskbarApps, List.of()));
    }

    @Test
    public void profileCopiesDoNotExposeStoredMutableState() {
        final DisplayProfileStore.Profile source =
                new DisplayProfileStore.Profile("display:copy");
        source.dpi = 160;
        source.outputTiming = "1920x1080@60";
        source.resetOutputModePending = true;

        final DisplayProfileStore.Profile copy = DisplayProfileStore.copy(source);
        copy.dpi = 240;
        copy.outputTiming = null;
        copy.resetOutputModePending = false;

        assertEquals(160, source.dpi);
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
                state.taskbarApps.add(app("example.saved"))));

        DesktopStateStore.read(state -> {
            state.taskbarApps.clear();
            return null;
        }, null);

        assertEquals(
                Collections.singletonList(app("example.saved")),
                DesktopStateStore.read(
                        state -> new ArrayList<>(state.taskbarApps),
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
