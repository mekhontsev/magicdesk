package io.github.mekhontsev.magicdesk;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Bounded observational state for commands delegated by launch requests. */
final class DesktopExecSessionTracker {
    enum State {
        PREPARING,
        RUNNING,
        DELEGATED,
        FINISHED,
        FAILED
    }

    private static final int MAX_SESSIONS = 32;
    private static final Map<String, State> SESSIONS =
            new LinkedHashMap<>();
    private static long sNextId;

    private DesktopExecSessionTracker() {
    }

    static synchronized String begin(
            final DesktopLaunchRequest request) {
        if (request == null || request.exec == null) {
            return "";
        }
        // Each execution has its own completion callback, even for identical commands.
        final String id = request.exec.backend.wireName + "."
                + Long.toUnsignedString(++sNextId, 16);
        SESSIONS.put(id, State.PREPARING);
        trim();
        return id;
    }

    static synchronized void running(final String id) {
        update(id, State.RUNNING);
    }

    static synchronized void delegated(final String id) {
        update(id, State.DELEGATED);
    }

    static synchronized void finished(final String id) {
        update(id, State.FINISHED);
    }

    static synchronized void failed(final String id) {
        update(id, State.FAILED);
    }

    static synchronized String diagnostics() {
        int active = 0;
        Map.Entry<String, State> latest = null;
        for (final Map.Entry<String, State> session : SESSIONS.entrySet()) {
            if (session.getValue() == State.RUNNING) {
                active++;
            }
            latest = session;
        }
        return "tracked=" + SESSIONS.size()
                + ", active=" + active
                + (latest == null
                        ? ""
                        : ", last=" + latest.getKey() + ":"
                                + latest.getValue().name().toLowerCase(Locale.ROOT));
    }

    private static void update(final String id, final State state) {
        if (id == null || id.isEmpty()) {
            return;
        }
        final State previous = SESSIONS.get(id);
        if (previous == null || previous == State.FINISHED
                || previous == State.FAILED || previous == State.DELEGATED) {
            return;
        }
        SESSIONS.remove(id);
        SESSIONS.put(id, state);
    }

    private static void trim() {
        while (SESSIONS.size() > MAX_SESSIONS) {
            final String oldest = SESSIONS.keySet().iterator().next();
            SESSIONS.remove(oldest);
        }
    }

}
