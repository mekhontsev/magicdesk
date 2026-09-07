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
        source.settings.relayPhysicalInput = true;
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
        assertEquals(Boolean.TRUE, decoded.settings.relayPhysicalInput);
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
        assertNull(MagicDeskSettings.Values.fromJson(defaults.toJson()).relayPhysicalInput);
        defaults.relayPhysicalInput = false;
        assertEquals(Boolean.FALSE, defaults.copy().relayPhysicalInput);
        assertEquals(Boolean.FALSE,
                MagicDeskSettings.Values.fromJson(defaults.toJson()).relayPhysicalInput);
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
                            state.taskbarPackages.add(packageName)));
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
                state.taskbarPackages.add("example.before")));
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
                    state.taskbarPackages.add("example.after");
                });
            });
            assertTrue(updating.await(2L, TimeUnit.SECONDS));
            assertFalse(mutated.await(100L, TimeUnit.MILLISECONDS));
            releaseRead.countDown();
            assertFalse(reload.get(2L, TimeUnit.SECONDS));
            assertTrue(update.get(2L, TimeUnit.SECONDS));

            final List<String> expected = List.of("example.before", "example.after");
            assertEquals(expected, DesktopStateStore.read(
                    state -> state.taskbarPackages, List.of()));
            assertEquals(expected, DesktopStateStore.decode(storage.read()).taskbarPackages);
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
                state.taskbarPackages.add("example.before")));

        assertTrue(DesktopStateStore.update(state -> {
            state.taskbarPackages.add("example.after");
            assertEquals(List.of("example.before"), DesktopStateStore.read(
                    published -> published.taskbarPackages, List.of()));
        }));
        assertEquals(List.of("example.before", "example.after"), DesktopStateStore.read(
                state -> state.taskbarPackages, List.of()));
    }

    @Test
    public void failedMutationAndEncodingLeavePublishedStateUnchanged() {
        DesktopStateStore.useStorageForTests(new MemoryStorage());
        assertTrue(DesktopStateStore.update(state ->
                state.taskbarPackages.add("example.before")));

        assertFalse(DesktopStateStore.update(state -> {
            state.taskbarPackages.clear();
            throw new IllegalArgumentException("invalid mutation");
        }));
        assertFalse(DesktopStateStore.update(state -> {
            state.taskbarPackages.clear();
            state.settings = null;
        }));
        assertEquals(List.of("example.before"), DesktopStateStore.read(
                state -> state.taskbarPackages, List.of()));
    }

    @Test
    public void reloadPublishesValidExternalStateButKeepsStateOnInvalidInput() throws Exception {
        final MemoryStorage storage = new MemoryStorage();
        DesktopStateStore.useStorageForTests(storage);
        assertTrue(DesktopStateStore.update(state ->
                state.taskbarPackages.add("example.before")));
        final DesktopStateStore.State external = new DesktopStateStore.State();
        external.taskbarPackages.add("example.external");
        storage.write(DesktopStateStore.encode(external));

        assertTrue(DesktopStateStore.reload());
        assertFalse(DesktopStateStore.reload());
        storage.write("{invalid");
        assertFalse(DesktopStateStore.reload());
        assertEquals(List.of("example.external"), DesktopStateStore.read(
                state -> state.taskbarPackages, List.of()));
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
