package io.github.mekhontsev.magicdesk;

/** Reads WMShell transition state and bounded queue details from the SystemUI dump. */
final class WmShellTransitionStateParser {
    private static final int MAX_SECTION_DETAIL = 768;

    enum State {
        UNAVAILABLE,
        BUSY,
        IDLE
    }

    private WmShellTransitionStateParser() {
    }

    static Snapshot parse(final String output) {
        if (output == null || output.isEmpty()) {
            return Snapshot.unavailable("empty SystemUI dump");
        }
        boolean shellTransitions = false;
        boolean pendingSeen = false;
        boolean pendingIdle = false;
        boolean readySeen = false;
        boolean readyIdle = false;
        boolean tracksSeen = false;
        boolean tracksIdle = true;
        final StringBuilder pending = new StringBuilder();
        final StringBuilder ready = new StringBuilder();
        final StringBuilder tracks = new StringBuilder();
        Section section = Section.NONE;
        for (final String line : output.split("\\r?\\n")) {
            final String value = line.trim();
            if (!shellTransitions) {
                shellTransitions = "ShellTransitions".equals(value);
                continue;
            }
            if ("AppResourceProvider".equals(value)
                    || "SurfaceControlRegistry".equals(value)) {
                break;
            }
            if ("Pending Transitions:".equals(value)) {
                pendingSeen = true;
                section = Section.PENDING;
                continue;
            }
            if ("Ready-during-sync Transitions:".equals(value)) {
                readySeen = true;
                section = Section.READY;
                continue;
            }
            if ("Tracks:".equals(value)) {
                section = Section.TRACKS;
                continue;
            }
            if (value.isEmpty()) {
                continue;
            }
            if (section == Section.PENDING) {
                if (pending.length() == 0) {
                    pendingIdle = "none".equals(value);
                } else {
                    pendingIdle &= "none".equals(value);
                }
                appendDetail(pending, value);
            } else if (section == Section.READY) {
                if (ready.length() == 0) {
                    readyIdle = "none".equals(value);
                } else {
                    readyIdle &= "none".equals(value);
                }
                appendDetail(ready, value);
            } else if (section == Section.TRACKS) {
                appendDetail(tracks, value);
                if (value.startsWith("active=")) {
                    tracksSeen = true;
                    tracksIdle &= "active=null".equals(value);
                }
            }
        }
        final String detail = "pending=[" + pending + "], ready=[" + ready
                + "], tracks=[" + tracks + ']';
        if (!shellTransitions || !pendingSeen || !readySeen || !tracksSeen
                || pending.length() == 0 || ready.length() == 0) {
            return Snapshot.unavailable("incomplete ShellTransitions dump; " + detail);
        }
        return new Snapshot(pendingIdle && readyIdle && tracksIdle
                ? State.IDLE : State.BUSY, detail);
    }

    private static void appendDetail(final StringBuilder output, final String value) {
        if (output.length() >= MAX_SECTION_DETAIL) {
            return;
        }
        if (output.length() != 0) {
            output.append("; ");
        }
        final int remaining = Math.max(0, MAX_SECTION_DETAIL - output.length());
        output.append(value, 0, Math.min(value.length(), remaining));
        if (output.length() >= MAX_SECTION_DETAIL) {
            output.append("...");
        }
    }

    static final class Snapshot {
        final State state;
        final String detail;

        private Snapshot(final State state, final String detail) {
            this.state = state;
            this.detail = detail;
        }

        static Snapshot unavailable(final String detail) {
            return new Snapshot(State.UNAVAILABLE, detail);
        }

        @Override
        public String toString() {
            return state + "; " + detail;
        }
    }

    private enum Section {
        NONE,
        PENDING,
        READY,
        TRACKS
    }
}
