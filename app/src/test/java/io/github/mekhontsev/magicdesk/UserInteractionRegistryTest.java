package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class UserInteractionRegistryTest {
    private UserInteractionRequest prompt() throws Exception {
        return UserInteractionRequest.parse("dialog", new JSONObject().put("title", "Question").put("type", "text"));
    }

    @Test public void observationTimeoutDoesNotCancelAndAnswersAreNonConsuming() throws Exception {
        final var registry = new UserInteractionRegistry();
        final String id = registry.begin(prompt());
        assertEquals("pending", registry.result(id, 1).getString("state"));
        assertNotNull(registry.pending(id));
        final JSONObject answer = new JSONObject().put("text", "original");
        registry.complete(id, "completed", answer, "");
        answer.put("text", "mutated");
        registry.result(id, 0).getJSONObject("result").put("text", "mutated again");
        assertEquals("original", registry.result(id, 0).getJSONObject("result").getString("text"));
        assertEquals("completed", registry.result(id, 30000).getString("state"));
    }

    @Test public void FirstCompletionWinsAndCallbacksAreOutsideTheRegistryLock() throws Exception {
        final var registry = new UserInteractionRegistry();
        final String id = registry.begin(prompt());
        final var callbacks = new AtomicInteger();
        registry.whenFinished(id, () -> {
            assertFalse(Thread.holdsLock(registry));
            callbacks.incrementAndGet();
        });
        registry.complete(id, "completed", new JSONObject().put("confirmed", true), "");
        registry.complete(id, "cancelled", null, "");
        registry.complete(id, "expired", null, "");
        assertEquals(1, callbacks.get());
        assertEquals("completed", registry.result(id, 0).getString("state"));
        assertNull(registry.pending(id));
    }

    @Test public void eventWaitReceivesCompletionAndShutdownCancelsRemainingRequests() throws Exception {
        final var registry = new UserInteractionRegistry();
        final String id = registry.begin(prompt());
        final var worker = Executors.newSingleThreadExecutor();
        final var entered = new CountDownLatch(1);
        try {
            final var result = worker.submit(() -> { entered.countDown(); return registry.result(id, 30000); });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            registry.complete(id, "completed", new JSONObject().put("text", "answer"), "");
            assertEquals("completed", result.get(5, TimeUnit.SECONDS).getString("state"));
            final String pending = registry.begin(prompt());
            registry.close();
            assertEquals("cancelled", registry.result(pending, 0).getString("state"));
            assertEquals("completed", registry.result(id, 0).getString("state"));
            assertThrows(IllegalStateException.class, () -> registry.begin(prompt()));
        } finally {
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test public void interruptedWaitLeavesPendingAndRetainsInterrupt() throws Exception {
        final var registry = new UserInteractionRegistry();
        final String id = registry.begin(prompt());
        Thread.currentThread().interrupt();
        try {
            assertEquals("pending", registry.result(id, 30000).getString("state"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test public void retentionNeverEvictsPendingAndRejectsExcessOutstandingRequests() throws Exception {
        final var registry = new UserInteractionRegistry();
        final String live = registry.begin(prompt());
        final String first = registry.begin(prompt());
        registry.complete(first, "cancelled", null, "");
        for (int i = 0; i < 70; i++) {
            final String id = registry.begin(prompt());
            registry.complete(id, "completed", null, "");
        }
        assertEquals("not_found", registry.result(first, 0).getString("state"));
        assertNotNull(registry.pending(live));
        for (int i = 1; i < 16; i++) registry.begin(prompt());
        assertThrows(IllegalStateException.class, () -> registry.begin(prompt()));
        registry.close();
    }

    @Test public void missingIdentityIsNotAnAnswerAndInvalidWaitsAreRejected() throws Exception {
        final var registry = new UserInteractionRegistry();
        registry.complete("missing", "cancelled", null, "");
        assertEquals("not_found", registry.result("missing", 0).getString("state"));
        assertThrows(IllegalArgumentException.class, () -> registry.result("missing", -1));
        assertThrows(IllegalArgumentException.class, () -> registry.result("missing", 30001));
        assertThrows(IllegalArgumentException.class, () -> registry.complete("missing", "pending", null, ""));
    }

    @Test public void shownIsAnObservationNotCompletion() throws Exception {
        final var registry = new UserInteractionRegistry();
        final String id = registry.begin(prompt());
        assertFalse(registry.result(id, 0).getBoolean("presented"));
        registry.presented(id);
        assertTrue(registry.result(id, 0).getBoolean("presented"));
        assertEquals("pending", registry.result(id, 0).getString("state"));
    }

    @Test public void allCommandsHaveOneCatalogAndContentPermission() throws Exception {
        for (String name : Set.of("dialog.show", "notification.post", "interaction.result", "interaction.close")) {
            assertEquals(name, AutomationCommandArguments.command(name).getString("name"));
            assertFalse(new McpAccessPolicy(Set.of("control")).allows(name));
            assertTrue(new McpAccessPolicy(Set.of("content")).allows(name));
            final JSONObject schema = AutomationCommandArguments.command(name).getJSONObject("outputSchema");
            assertTrue(schema.getJSONObject("properties").getJSONObject("data")
                    .getJSONObject("properties").has("state"));
        }
    }

    @Test public void choicesAndNotificationActionsAreDeclarativeAndBounded() throws Exception {
        final JSONObject choice = new JSONObject().put("id", "work").put("label", "Work");
        final JSONObject args = new JSONObject().put("title", "Choose").put("type", "choice")
                .put("choices", new JSONArray().put(choice));
        assertEquals("work", UserInteractionRequest.parse("dialog", args).items().get(0).id());
        choice.put("command", "must not execute");
        assertThrows(IllegalArgumentException.class, () -> UserInteractionRequest.parse("dialog", args));
        choice.remove("command");
        args.getJSONArray("choices").put(choice);
        assertThrows(IllegalArgumentException.class, () -> UserInteractionRequest.parse("dialog", args));
        final JSONObject notification = new JSONObject().put("title", "Done")
                .put("actions", new JSONArray().put(new JSONObject().put("id", "open").put("label", "Open")));
        assertThrows(IllegalArgumentException.class, () -> UserInteractionRequest.parse("notification", notification));
        notification.put("actions", new JSONArray().put(new JSONObject().put("id", "answer")
                .put("label", "Answer").put("reply", true)));
        assertTrue(UserInteractionRequest.parse("notification", notification).items().get(0).reply());
    }

    @Test public void invalidDialogCombinationsAndLargeContentFailBeforeCreatingUi() throws Exception {
        final JSONObject args = new JSONObject().put("title", "Question").put("type", "confirm");
        args.put("initialText", "unexpected");
        assertThrows(IllegalArgumentException.class, () -> UserInteractionRequest.parse("dialog", args));
        args.remove("initialText"); args.put("multiple", false);
        assertThrows(IllegalArgumentException.class, () -> UserInteractionRequest.parse("dialog", args));
        args.remove("multiple"); args.put("lifetimeMillis", 0);
        assertThrows(IllegalArgumentException.class, () -> UserInteractionRequest.parse("dialog", args));
        args.remove("lifetimeMillis"); args.put("displayId", 2147483648L);
        assertThrows(IllegalArgumentException.class, () -> UserInteractionRequest.parse("dialog", args));
        args.remove("displayId"); args.put("message", "a".repeat(4097));
        assertThrows(IllegalArgumentException.class, () -> UserInteractionRequest.parse("dialog", args));
        args.remove("message"); args.put("title", "   ");
        assertThrows(IllegalArgumentException.class, () -> UserInteractionRequest.parse("dialog", args));
    }
}
