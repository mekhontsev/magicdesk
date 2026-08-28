package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.SystemClock;
import android.view.Display;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Detects system transition performance sessions orphaned by display removal. */
final class WindowTransitionHealthDiagnostics {
    private static final long POLL_MILLIS = 50L;
    private static final long STABLE_IDLE_MILLIS = 150L;
    private static final String DUMPSYS_COMMAND =
            "/system/bin/dumpsys window | /system/bin/toybox sed -n "
                    + "'/SystemPerformanceHinter:/,$p' "
                    + "| /system/bin/toybox head -n 120";

    private WindowTransitionHealthDiagnostics() {
    }

    static Snapshot capture(final Context context) {
        if (context == null) {
            return Snapshot.unavailable("application context is unavailable");
        }
        final DisplayManager displayManager =
                context.getSystemService(DisplayManager.class);
        if (displayManager == null) {
            return Snapshot.unavailable("DisplayManager is unavailable");
        }
        final Set<Integer> liveDisplayIds = new HashSet<>();
        for (final Display display : displayManager.getDisplays()) {
            liveDisplayIds.add(Integer.valueOf(display.getDisplayId()));
        }
        try {
            return parse(ShellAccess.run(DUMPSYS_COMMAND), liveDisplayIds);
        } catch (IOException | RuntimeException error) {
            return Snapshot.unavailable(ShellAccess.usefulMessage(error));
        }
    }

    /** Waits until WMShell owns no transition work for a display. */
    static IdleResult awaitDisplayIdle(
            final Context context,
            final int displayId,
            final long timeoutMillis) {
        if (context == null || displayId < Display.DEFAULT_DISPLAY
                || timeoutMillis <= 0L) {
            throw new IllegalArgumentException(
                    "display teardown wait arguments are invalid");
        }
        final long deadline = SystemClock.uptimeMillis() + timeoutMillis;
        long stableSince = -1L;
        String lastDetail = "transition state was not sampled";
        do {
            final long now = SystemClock.uptimeMillis();
            final Snapshot snapshot = capture(context);
            if (!snapshot.available) {
                return IdleResult.blocked(
                        "system transition inspection failed: "
                                + snapshot.error);
            }
            if (snapshot.hasTransitionForDisplay(displayId)) {
                stableSince = -1L;
                lastDetail = snapshot.transitionDetail(displayId);
            } else if (stableSince < 0L) {
                stableSince = SystemClock.uptimeMillis();
                lastDetail = "waiting for stable transition quiescence";
            } else if (SystemClock.uptimeMillis() - stableSince
                    >= STABLE_IDLE_MILLIS) {
                return IdleResult.idle();
            }
            BoundedStateAwaiter.pause(
                    BoundedStateAwaiter.Reason.TRANSITION_HEALTH,
                    Math.min(POLL_MILLIS,
                            Math.max(1L,
                                    deadline - SystemClock.uptimeMillis())));
        } while (SystemClock.uptimeMillis() < deadline);
        return IdleResult.blocked(lastDetail);
    }

    static Snapshot parse(
            final String dumpsys,
            final Set<Integer> liveDisplayIds) {
        if (dumpsys == null || liveDisplayIds == null) {
            throw new IllegalArgumentException("transition health input is required");
        }
        final List<Session> sessions = new ArrayList<>();
        boolean sectionFound = false;
        for (final String sourceLine : dumpsys.split("\\r?\\n")) {
            final String line = sourceLine.trim();
            if (line.startsWith("SystemPerformanceHinter:")) {
                sectionFound = true;
                continue;
            }
            if (!sectionFound || !line.startsWith("reason=")) {
                continue;
            }
            final String reason = value(line, "reason");
            final int flags = intValue(line, "flags", -1);
            final int displayId = intValue(line, "display", -1);
            if (!reason.isEmpty() && displayId >= 0) {
                sessions.add(new Session(reason, flags, displayId));
            }
        }
        if (!sectionFound) {
            return Snapshot.unavailable(
                    "SystemPerformanceHinter section is unavailable");
        }
        final List<Session> stale = new ArrayList<>();
        for (final Session session : sessions) {
            if ("Transition".equals(session.reason)
                    && !liveDisplayIds.contains(
                            Integer.valueOf(session.displayId))) {
                stale.add(session);
            }
        }
        return new Snapshot(
                true,
                "",
                sessions,
                stale,
                new HashSet<>(liveDisplayIds));
    }

    static void appendReport(
            final StringBuilder report,
            final Context context) {
        final Snapshot snapshot = capture(context);
        report.append("## Window transition runtime\n");
        if (!snapshot.available) {
            report.append("Inspection unavailable: ")
                    .append(snapshot.error)
                    .append("\n\n");
            return;
        }
        report.append("Active performance sessions: ")
                .append(snapshot.sessions.size())
                .append('\n')
                .append("Live displays: ")
                .append(snapshot.liveDisplayIds)
                .append('\n');
        if (snapshot.staleTransitions.isEmpty()) {
            report.append("Stale display transition sessions: none\n\n");
            return;
        }
        report.append("Stale display transition sessions: ")
                .append(snapshot.staleTransitions.size())
                .append('\n');
        for (final Session session : snapshot.staleTransitions) {
            report.append("- reason=")
                    .append(session.reason)
                    .append(" flags=")
                    .append(session.flags)
                    .append(" display=")
                    .append(session.displayId)
                    .append('\n');
        }
        report.append("Recovery: restart system_server or reboot the device. ")
                .append("Restarting SystemUI may help, but is not reliable ")
                .append("after display removal.\n\n");
    }

    private static String value(final String line, final String key) {
        final String prefix = key + '=';
        for (final String part : line.split("\\s+")) {
            if (part.startsWith(prefix)) {
                return part.substring(prefix.length());
            }
        }
        return "";
    }

    private static int intValue(
            final String line,
            final String key,
            final int fallback) {
        final String value = value(line, key);
        try {
            return value.isEmpty() ? fallback : Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    static final class Session {
        final String reason;
        final int flags;
        final int displayId;

        Session(final String reason, final int flags, final int displayId) {
            this.reason = reason;
            this.flags = flags;
            this.displayId = displayId;
        }

        String key() {
            return reason + ':' + flags + ':' + displayId;
        }
    }

    static final class Snapshot {
        final boolean available;
        final String error;
        final List<Session> sessions;
        final List<Session> staleTransitions;
        final Set<Integer> liveDisplayIds;
        Snapshot(
                final boolean available,
                final String error,
                final List<Session> sessions,
                final List<Session> staleTransitions,
                final Set<Integer> liveDisplayIds) {
            this.available = available;
            this.error = error;
            this.sessions = Collections.unmodifiableList(
                    new ArrayList<>(sessions));
            this.staleTransitions = Collections.unmodifiableList(
                    new ArrayList<>(staleTransitions));
            this.liveDisplayIds = Collections.unmodifiableSet(
                    new HashSet<>(liveDisplayIds));
        }

        static Snapshot unavailable(final String error) {
            return new Snapshot(
                    false,
                    error == null ? "unknown error" : error,
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptySet());
        }

        boolean hasStaleTransitions() {
            return !staleTransitions.isEmpty();
        }

        boolean hasTransitionForDisplay(final int displayId) {
            for (final Session session : sessions) {
                if ("Transition".equals(session.reason)
                        && session.displayId == displayId) {
                    return true;
                }
            }
            return false;
        }

        String transitionDetail(final int displayId) {
            final StringBuilder detail = new StringBuilder();
            for (final Session session : sessions) {
                if (!"Transition".equals(session.reason)
                        || session.displayId != displayId) {
                    continue;
                }
                if (detail.length() > 0) {
                    detail.append(", ");
                }
                detail.append("flags=").append(session.flags);
            }
            return detail.length() == 0
                    ? "no system transition for display " + displayId
                    : "system transition active on display " + displayId
                            + " (" + detail + ')';
        }

        String staleDetail() {
            return staleDetail(staleTransitionCounts());
        }

        Map<String, Integer> staleTransitionCounts() {
            final Map<String, Integer> counts = new LinkedHashMap<>();
            for (final Session session : staleTransitions) {
                final String key = session.key();
                counts.put(key, Integer.valueOf(
                        counts.getOrDefault(key, Integer.valueOf(0)).intValue()
                                + 1));
            }
            return counts;
        }

        Map<String, Integer> staleTransitionCountsAfter(
                final Map<String, Integer> baseline) {
            final Map<String, Integer> counts = staleTransitionCounts();
            if (baseline == null || baseline.isEmpty()) {
                return counts;
            }
            for (final Map.Entry<String, Integer> entry
                    : baseline.entrySet()) {
                final int remaining = counts.getOrDefault(
                        entry.getKey(), Integer.valueOf(0)).intValue()
                        - Math.max(0, entry.getValue().intValue());
                if (remaining > 0) {
                    counts.put(entry.getKey(), Integer.valueOf(remaining));
                } else {
                    counts.remove(entry.getKey());
                }
            }
            return counts;
        }

        String staleDetail(final Map<String, Integer> includedCounts) {
            final StringBuilder detail = new StringBuilder();
            final Map<String, Integer> remainingCounts = includedCounts == null
                    ? Collections.emptyMap()
                    : new LinkedHashMap<>(includedCounts);
            for (final Session session : staleTransitions) {
                final int count = remainingCounts.getOrDefault(
                        session.key(), Integer.valueOf(0)).intValue();
                if (count <= 0) {
                    continue;
                }
                if (detail.length() > 0) {
                    detail.append(", ");
                }
                detail.append("display=")
                        .append(session.displayId)
                        .append(" flags=")
                        .append(session.flags)
                        .append(count > 1 ? " count=" + count : "");
                remainingCounts.remove(session.key());
            }
            return detail.toString();
        }
    }

    static final class IdleResult {
        final boolean idle;
        final String detail;

        private IdleResult(final boolean idle, final String detail) {
            this.idle = idle;
            this.detail = detail;
        }

        static IdleResult idle() {
            return new IdleResult(true, "idle");
        }

        static IdleResult blocked(final String detail) {
            return new IdleResult(false,
                    detail == null ? "transition state is unknown" : detail);
        }
    }
}
