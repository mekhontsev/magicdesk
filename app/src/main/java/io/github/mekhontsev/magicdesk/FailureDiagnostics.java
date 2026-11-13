package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Bounded cause summaries that survive Binder and truncated diagnostic stacks. */
final class FailureDiagnostics {
    private static final int MAX_CAUSES = 32;

    private FailureDiagnostics() { }

    static IllegalStateException binderFailure(final String operation, final Throwable cause) {
        // Parcel retains the exception message, but not its Java cause chain.
        return new IllegalStateException(operation + ": " + describe(cause, 1200), cause);
    }

    static String describe(final Throwable error, final int limit) {
        final List<Throwable> causes = new ArrayList<>();
        final Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = error;
        while (current != null && causes.size() < MAX_CAUSES && seen.add(current)) {
            causes.add(current);
            current = current.getCause();
        }
        final StringBuilder result = new StringBuilder();
        if (current != null) result.append("[cause chain incomplete] ");
        // Deepest cause first: wrapper stacks and messages must not hide the rejection.
        for (int index = causes.size() - 1; index >= 0 && result.length() < limit; index--) {
            if (index != causes.size() - 1) result.append(" <- ");
            final Throwable cause = causes.get(index);
            result.append(cause.getClass().getName());
            final String message = cause.getMessage();
            if (message != null && !message.isEmpty()) {
                result.append(": ").append(BoundedText.prefix(message, limit)
                        .replace('\n', ' ').replace('\r', ' ').replace('\0', ' '));
            }
        }
        return bounded(result.toString(), limit);
    }

    static String bounded(final String text, final int limit) {
        if (text.length() <= limit) return text;
        return BoundedText.prefix(text, Math.max(0, limit - 3))
                + ".".repeat(Math.min(3, Math.max(0, limit)));
    }
}
