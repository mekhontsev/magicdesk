package io.github.mekhontsev.magicdesk;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Joins removal retries without repeating session cleanup or releasing a new display. */
final class DisplayRemovalRequests {
    private record Identity(int displayId, String uniqueId) { }

    private final Map<Identity, CompletableFuture<Boolean>> requests = new HashMap<>();

    CompletableFuture<Boolean> submit(int displayId, String uniqueId,
            DesktopDisplayInfo[] displays, Consumer<Consumer<Boolean>> start) throws IOException {
        validate(displayId, uniqueId);
        final Identity identity = new Identity(displayId, uniqueId);
        final CompletableFuture<Boolean> result;
        synchronized (requests) {
            // Release can return before Android publishes removal. Keep its receipt
            // only while that exact display is still present; never keep a history.
            requests.entrySet().removeIf(entry -> entry.getValue().isDone()
                    && !contains(displays, entry.getKey()));
            final DesktopDisplayInfo current = find(displays, displayId);
            if (current != null && !uniqueId.equals(current.uniqueId)) {
                throw new IOException("display identity changed: " + displayId);
            }
            final CompletableFuture<Boolean> pending = requests.get(identity);
            if (pending != null) return pending;
            if (current == null) return CompletableFuture.completedFuture(true);
            if (!current.canRemove()) {
                throw new IOException("display is not owned by MagicDesk: " + displayId);
            }
            result = new CompletableFuture<>();
            requests.put(identity, result);
        }
        result.thenAccept(success -> {
            if (!success) {
                synchronized (requests) { requests.remove(identity, result); }
            }
        });
        try {
            start.accept(result::complete);
        } catch (RuntimeException error) {
            result.complete(false);
            throw error;
        }
        return result;
    }

    static void validate(int displayId, String uniqueId) {
        if (displayId <= 0 || uniqueId == null || uniqueId.isBlank()) {
            throw new IllegalArgumentException("a non-default display ID and exact uniqueId are required");
        }
    }

    private static boolean contains(DesktopDisplayInfo[] displays, Identity identity) {
        final DesktopDisplayInfo display = find(displays, identity.displayId);
        return display != null && identity.uniqueId.equals(display.uniqueId);
    }

    private static DesktopDisplayInfo find(DesktopDisplayInfo[] displays, int id) {
        for (final DesktopDisplayInfo display : displays) {
            if (display.id == id) return display;
        }
        return null;
    }
}
