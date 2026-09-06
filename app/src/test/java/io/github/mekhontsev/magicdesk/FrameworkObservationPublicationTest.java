package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

public final class FrameworkObservationPublicationTest {
    @Test
    public void callbackDuringRegistrationSupersedesTheInitialSnapshot() throws Exception {
        final var publications = new LatestOperationSerializer();
        final var initial = publications.supersede();
        final List<String> delivered = new ArrayList<>();
        final var callback = publications.supersede();
        assertTrue(publications.executeIfCurrent(callback, () -> delivered.add("callback")));
        assertFalse(publications.executeIfCurrent(initial, () -> delivered.add("initial")));
        assertEquals(List.of("callback"), delivered);
    }

    @Test
    public void initialSnapshotIsPublishedWhenRegistrationHadNoCallback() throws Exception {
        final var publications = new LatestOperationSerializer();
        final var initial = publications.supersede();
        final List<String> delivered = new ArrayList<>();
        assertTrue(publications.executeIfCurrent(initial, () -> delivered.add("initial")));
        final var callback = publications.supersede();
        assertTrue(publications.executeIfCurrent(callback, () -> delivered.add("callback")));
        assertEquals(List.of("initial", "callback"), delivered);
    }

    @Test
    public void callbacksCannotBeDeliveredInReverseCommitOrder() throws Exception {
        final var publications = new LatestOperationSerializer();
        final var first = publications.supersede();
        final List<String> commits = new ArrayList<>();
        final List<String> delivered = new ArrayList<>();
        final CountDownLatch committed = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final var workers = Executors.newFixedThreadPool(2);
        try {
            final var firstResult = workers.submit(() -> publications.executeIfCurrent(first, () -> {
                commits.add("first");
                committed.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                delivered.add("first");
            }));
            assertTrue(committed.await(5, TimeUnit.SECONDS));
            final var second = publications.supersede();
            final var secondResult = workers.submit(() -> publications.executeIfCurrent(second, () -> {
                commits.add("second");
                delivered.add("second");
            }));
            release.countDown();
            assertTrue(firstResult.get(5, TimeUnit.SECONDS));
            assertTrue(secondResult.get(5, TimeUnit.SECONDS));
            assertEquals(List.of("first", "second"), commits);
            assertEquals(commits, delivered);
        } finally {
            release.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void inFlightReadCannotPublishAfterClearCloseOrSameDisplayReconfigure() throws Exception {
        for (final String replacement : List.of("clear", "close", "same-display")) {
            final var publications = new LatestOperationSerializer();
            final var sampledConfiguration = publications.supersede();
            final CountDownLatch reading = new CountDownLatch(1);
            final CountDownLatch finishRead = new CountDownLatch(1);
            final List<String> delivered = new ArrayList<>();
            final var worker = Executors.newSingleThreadExecutor();
            try {
                final var sample = worker.submit(() -> {
                    reading.countDown();
                    assertTrue(finishRead.await(5, TimeUnit.SECONDS));
                    return publications.executeIfCurrent(sampledConfiguration,
                            () -> delivered.add("old display=0 sample"));
                });
                assertTrue(reading.await(5, TimeUnit.SECONDS));
                publications.invalidate();
                if (replacement.equals("same-display")) {
                    final var current = publications.supersede();
                    assertTrue(publications.executeIfCurrent(current,
                            () -> delivered.add("new display=0 sample")));
                }
                finishRead.countDown();
                assertFalse(sample.get(5, TimeUnit.SECONDS));
                assertEquals(replacement.equals("same-display")
                        ? List.of("new display=0 sample") : List.of(), delivered);
            } finally {
                finishRead.countDown();
                worker.shutdownNow();
                assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    public void taskAdapterKeepsConfigurationTicketsSeparateFromSampleWakeups() throws Exception {
        final String source = source("FrameworkTaskObservationSource");
        final String configure = body(source, "void configure(", "void clearConfiguration()");
        assertTrue(configure.contains("mConfiguration = mPublications.supersede()"));
        final String clear = body(source, "void clearConfiguration()", "void requestSample()");
        assertTrue(clear.contains("mPublications.invalidate()"));
        final String request = body(source, "void requestSample()", "public void close()");
        assertFalse(request.contains("mPublications"));
        assertTrue(body(source, "public void close()", "private void run()")
                .contains("mPublications.invalidate()"));
        final String run = body(source, "private void run()", "private void publishTaskStackChanges(");
        assertTrue(run.contains("configuration = mConfiguration"));
        assertTrue(run.indexOf("mPublications.executeIfCurrent(configuration")
                > run.indexOf("FrameworkTaskSnapshotSource.read("));
        assertTrue(run.contains("publishWindowChanges(configuration, displayId, taskSnapshots)"));
        assertTrue(run.contains("publishImmersiveChanges(configuration, displayId, taskSnapshots)"));
        assertTrue(source.contains("configuration == mConfiguration && displayId == mDisplayId"));
        assertTrue(source.contains("mPublications.executeIfCurrent(configuration, mListener::onTaskStackChanged)"));
        for (final String event : List.of("onImmersiveRequest", "onFreeformBoundsChanged",
                "onWindowingModeChanged")) {
            assertTrue(source.contains("mPublications.executeIfCurrent(configuration, () -> mListener." + event));
        }
    }

    @Test
    public void inputAdapterUsesOnePublicationOrderForRegistrationAndCallbacks() throws Exception {
        final String source = source("FrameworkInputWindowObservationSource");
        assertTrue(source.contains("publish(mPublications.supersede(), inputWindowHandles)"));
        final String start = body(source, "void start()", "public long checkpoint()");
        assertTrue(start.contains("mClosed || mRegistered || mStarting"));
        assertTrue(start.indexOf("initialPublication = mPublications.supersede()")
                < start.indexOf("mWindowInfosListener.register()"));
        assertTrue(start.contains("publish(initialPublication, initial.first)"));
        assertTrue(source.contains("mPublications.executeIfCurrent(publication, () -> publishCurrent(handles))"));
        final String publish = body(source, "private void publishCurrent(", "private static String usefulMessage(");
        assertTrue(publish.indexOf("mLatestSnapshot = snapshot")
                < publish.indexOf("mObservationListener.onInputWindowsChanged(snapshot)"));
        assertTrue(body(source, "public void close()", "FrameworkInputWindowState.Snapshot latestSnapshot()")
                .contains("mPublications.invalidate()"));
    }

    private static String source(final String name) throws Exception {
        return Files.readString(Path.of("src/main/java/io/github/mekhontsev/magicdesk/" + name + ".java"));
    }

    private static String body(final String source, final String start, final String end) {
        return source.substring(source.indexOf(start), source.indexOf(end));
    }
}
