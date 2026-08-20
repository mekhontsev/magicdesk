package io.github.mekhontsev.magicdesk;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

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
    private static final Map<String, Session> SESSIONS =
            new LinkedHashMap<>();

    private DesktopExecSessionTracker() {
    }

    static synchronized String begin(
            final DesktopLaunchRequest request) {
        if (request == null || request.exec == null) {
            return "";
        }
        final String target = request.androidLaunch == null
                || request.androidLaunch.target == null
                ? "" : request.androidLaunch.target.packageName;
        final int hash = Objects.hash(
                request.exec.backend.wireName,
                request.exec.command,
                request.exec.workingDirectory,
                target,
                request.name);
        final String id = request.exec.backend.wireName + "."
                + Integer.toUnsignedString(hash, 16);
        SESSIONS.remove(id);
        SESSIONS.put(id, new Session(id, State.PREPARING));
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
        Session latest = null;
        for (final Session session : SESSIONS.values()) {
            if (session.state == State.RUNNING) {
                active++;
            }
            latest = session;
        }
        return "tracked=" + SESSIONS.size()
                + ", active=" + active
                + (latest == null
                        ? ""
                        : ", last=" + latest.id + ":"
                                + latest.state.name().toLowerCase());
    }

    private static void update(final String id, final State state) {
        if (id == null || id.isEmpty()) {
            return;
        }
        final Session previous = SESSIONS.remove(id);
        if (previous == null) {
            return;
        }
        SESSIONS.put(id, new Session(previous.id, state));
    }

    private static void trim() {
        while (SESSIONS.size() > MAX_SESSIONS) {
            final String oldest = SESSIONS.keySet().iterator().next();
            SESSIONS.remove(oldest);
        }
    }

    private static final class Session {
        final String id;
        final State state;

        Session(
                final String id,
                final State state) {
            this.id = id;
            this.state = state;
        }
    }
}
