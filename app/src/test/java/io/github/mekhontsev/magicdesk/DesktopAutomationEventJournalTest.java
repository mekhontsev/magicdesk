package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.json.JSONObject;
import org.junit.Test;

public final class DesktopAutomationEventJournalTest {
    @Test
    public void capturedCursorAndEventsRemainOneSnapshotAfterAnotherWriterPublishes()
            throws Exception {
        final long first = DesktopAutomationEventJournal.record("test", "first", true, "");
        final var snapshot = DesktopAutomationEventJournal.snapshotWithCursor(first - 1, 256);
        DesktopAutomationEventJournal.record("test", "later", true, "");
        assertEquals(first, snapshot.latestId);
        assertEquals(1, snapshot.events.length());
        assertEquals(first, snapshot.events.getJSONObject(0).getLong("id"));
        assertTrue(DesktopAutomationEventJournal.latestId() > snapshot.latestId);
        snapshot.events.getJSONObject(0).put("operation", "changed copy");
        assertEquals("first", DesktopAutomationEventJournal.snapshot(first - 1, 256)
                .getJSONObject(0).getString("operation"));
    }

    @Test(timeout = 5000)
    public void concurrentWritersCannotPublishEventsBeyondTheCapturedCursor() throws Exception {
        final var running = new AtomicBoolean(true);
        final long before = DesktopAutomationEventJournal.record("test", "initial", true, "");
        final var writer = new Thread(() -> {
            while (running.get()) {
                DesktopAutomationEventJournal.record("test", "concurrent", true, "");
            }
        });
        writer.start();
        try {
            for (int index = 0; index < 100; index++) {
                final var snapshot = DesktopAutomationEventJournal.snapshotWithCursor(before - 1, 8);
                assertTrue(snapshot.events.length() > 0);
                assertTrue(snapshot.events.length() <= 8);
                final long last = snapshot.events.getJSONObject(snapshot.events.length() - 1)
                        .getLong("id");
                assertEquals(snapshot.latestId, last);
            }
        } finally {
            running.set(false);
            writer.join(1000);
            assertTrue(!writer.isAlive());
        }
    }

    @Test
    public void diagnosticTextLimitDoesNotSplitASurrogatePair() throws Exception {
        final String prefix = "x".repeat(999);
        final String text = prefix + "\ud83d\ude80";
        final long id = DesktopAutomationEventJournal.record(text, text, true, text);
        final var event = DesktopAutomationEventJournal.snapshot(id - 1, 1).getJSONObject(0);
        for (final String field : new String[] {"type", "operation", "detail"}) {
            assertEquals(prefix, event.getString(field));
        }
    }

    @Test
    public void diagnosticTextKeepsExactLimitAndExistingWhitespaceNormalization() throws Exception {
        final String text = "x".repeat(998) + "\ud83d\ude80";
        final long id = DesktopAutomationEventJournal.record(" test ", "a\u0000b\rc\nd", true,
                "  " + text + "  ");
        final var event = DesktopAutomationEventJournal.snapshot(id - 1, 1).getJSONObject(0);
        assertEquals(text, event.getString("detail"));
        assertEquals("test", event.getString("type"));
        assertEquals("a b c d", event.getString("operation"));
    }

    @Test
    public void readingEventsDoesNotExposeTheStoredNestedObjects() throws Exception {
        final var source = new JSONObject().put("nested", new JSONObject().put("value", "original"));
        final long id = DesktopAutomationEventJournal.record("test", "snapshot", true, "", source);
        source.getJSONObject("nested").put("value", "producer");
        final var first = DesktopAutomationEventJournal.snapshot(id - 1, 1)
                .getJSONObject(0).getJSONObject("data");
        assertEquals("original", first.getJSONObject("nested").getString("value"));
        first.getJSONObject("nested").put("value", "reader");
        final var second = DesktopAutomationEventJournal.snapshot(id - 1, 1)
                .getJSONObject(0).getJSONObject("data");
        assertEquals("original", second.getJSONObject("nested").getString("value"));
    }

    @Test(timeout = 5_000)
    public void cursorsAdvanceOnlyWhenAnEventIsPublished() throws Exception {
        final long before = DesktopAutomationEventJournal.latestId();
        final var copying = new CountDownLatch(1);
        final var releaseCopy = new CountDownLatch(1);
        final var data = new JSONObject() {
            @Override
            public String toString() {
                copying.countDown();
                try {
                    assertTrue(releaseCopy.await(2, TimeUnit.SECONDS));
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(error);
                }
                return "{}";
            }
        };
        final var slow = new FutureTask<>(() -> DesktopAutomationEventJournal.record(
                "test", "slow", true, "", data));
        final var thread = new Thread(slow);
        thread.start();
        try {
            assertTrue(copying.await(2, TimeUnit.SECONDS));
            assertEquals(before, DesktopAutomationEventJournal.latestId());
            final long fast = DesktopAutomationEventJournal.record(
                    "test", "fast", true, "");
            releaseCopy.countDown();
            final long last = slow.get(2, TimeUnit.SECONDS);
            assertTrue(last > fast);
            final var events = DesktopAutomationEventJournal.snapshot(before, 10);
            assertEquals(2, events.length());
            assertEquals(fast, events.getJSONObject(0).getLong("id"));
            assertEquals(last, events.getJSONObject(1).getLong("id"));
        } finally {
            releaseCopy.countDown();
            thread.join(2_000L);
        }
    }
}
