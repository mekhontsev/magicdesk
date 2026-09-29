package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

public final class ClipboardAutomationGatewayTest {
    private static final int LIMIT = 262_144;

    @Test
    public void textLimitCannotSplitASupplementaryCharacter() throws Exception {
        final String prefix = "a".repeat(LIMIT - 1);
        final String text = prefix + "\ud83d\ude80";
        final var result = ClipboardAutomationGateway.describeText(
                read(AndroidClipboardGateway.Access.AVAILABLE, text));
        assertTrue(result.success);
        assertEquals(prefix, result.data.getString("text"));
        assertEquals(text.length(), result.data.getInt("textLength"));
        assertTrue(result.data.getBoolean("truncated"));
        final String returned = result.data.getString("text");
        assertEquals(returned, new String(returned.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8));
    }

    @Test
    public void exactLimitPreservesWholeCharactersAndWhitespace() throws Exception {
        final String text = " \t" + "a".repeat(LIMIT - 5) + "\ud83d\ude80\n";
        assertEquals(LIMIT, text.length());
        final var result = ClipboardAutomationGateway.describeText(
                read(AndroidClipboardGateway.Access.AVAILABLE, text));
        assertEquals(text, result.data.getString("text"));
        assertFalse(result.data.getBoolean("truncated"));
        assertTrue(result.data.getBoolean("sensitive"));
    }

    @Test
    public void unavailableClipboardDoesNotExposePayloadText() throws Exception {
        for (final var access : List.of(AndroidClipboardGateway.Access.DENIED,
                AndroidClipboardGateway.Access.FAILED, AndroidClipboardGateway.Access.UNAVAILABLE)) {
            final var result = ClipboardAutomationGateway.describeText(read(access, "private"));
            assertFalse(result.success);
            assertTrue(result.retryable);
            assertEquals(access.wireName, result.observation.getString("access"));
            assertFalse(result.observation.has("text"));
            assertFalse(result.data.has("text"));
        }
    }

    @Test
    public void emptyClipboardIsAnExplicitSuccessfulResult() throws Exception {
        final var result = ClipboardAutomationGateway.describeText(
                read(AndroidClipboardGateway.Access.EMPTY, ""));
        assertTrue(result.success);
        assertEquals("", result.data.getString("text"));
        assertEquals(0, result.data.getInt("textLength"));
        assertFalse(result.data.getBoolean("truncated"));
    }

    @Test public void matchingReadAndDeadlineReleaseTheirListener() throws Exception {
        for (String expected : List.of("present", "absent")) {
            AtomicInteger closed = new AtomicInteger();
            var result = ClipboardAutomationGateway.awaitText(() -> read(AndroidClipboardGateway.Access.AVAILABLE, "present"),
                    listener -> closed::incrementAndGet, expected, 0);
            assertTrue(result.success);
            assertEquals(expected.equals("present"), result.data.getBoolean("matched"));
            assertEquals(1, closed.get());
        }
    }

    @Test public void changeDuringReadCannotBeLostBeforeWaiting() throws Exception {
        AtomicReference<Runnable> listener = new AtomicReference<>();
        AtomicInteger reads = new AtomicInteger(), closed = new AtomicInteger();
        var result = ClipboardAutomationGateway.awaitText(() -> {
            if (reads.incrementAndGet() == 1) {
                listener.get().run();
                return read(AndroidClipboardGateway.Access.AVAILABLE, "old");
            }
            return read(AndroidClipboardGateway.Access.AVAILABLE, "new");
        }, changed -> { listener.set(changed); return closed::incrementAndGet; }, "new", 1000);
        assertTrue(result.data.getBoolean("matched"));
        assertEquals(2, reads.get());
        assertEquals(1, closed.get());
    }

    @Test public void asynchronousChangeWakesObservationWithoutPolling() throws Exception {
        AtomicReference<Runnable> listener = new AtomicReference<>();
        AtomicReference<String> text = new AtomicReference<>("old");
        AtomicInteger reads = new AtomicInteger(), closed = new AtomicInteger();
        var readStarted = new CompletableFuture<Void>();
        var result = CompletableFuture.supplyAsync(() -> {
            try {
                return ClipboardAutomationGateway.awaitText(() -> {
                    reads.incrementAndGet();
                    String value = text.get();
                    readStarted.complete(null);
                    return read(AndroidClipboardGateway.Access.AVAILABLE, value);
                }, changed -> { listener.set(changed); return closed::incrementAndGet; }, "new", 2000);
            } catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
        });
        // EVENT_WAIT: initial read and callback-driven completion; expiry fails the test.
        readStarted.get(2, TimeUnit.SECONDS);
        text.set("new");
        listener.get().run();
        assertTrue(result.get(2, TimeUnit.SECONDS).data.getBoolean("matched"));
        assertEquals(2, reads.get());
        assertEquals(1, closed.get());
    }

    @Test public void deniedObservationDoesNotWaitOrExposeText() throws Exception {
        AtomicInteger closed = new AtomicInteger();
        var result = ClipboardAutomationGateway.awaitText(() -> read(AndroidClipboardGateway.Access.DENIED, "private"),
                listener -> closed::incrementAndGet, "private", 2000);
        assertFalse(result.success);
        assertFalse(result.data.has("text"));
        assertEquals(1, closed.get());
    }

    @Test public void clipboardWaitRemainsContentPermissionGated() throws Exception {
        AutomationCommandArguments.check("clipboard.read_text", new org.json.JSONObject()
                .put("expectedText", "sample").put("timeoutMillis", 1000));
        var observe = new McpAccessPolicy(java.util.Set.of("observe"));
        assertFalse(observe.allows("clipboard.read_text"));
    }

    private static AndroidClipboardGateway.TextReadResult read(
            final AndroidClipboardGateway.Access access, final String text) {
        return new AndroidClipboardGateway.TextReadResult(new AndroidClipboardGateway.Metadata(
                access, 1, List.of("text/plain"), true, "", -1, ""), text);
    }
}
