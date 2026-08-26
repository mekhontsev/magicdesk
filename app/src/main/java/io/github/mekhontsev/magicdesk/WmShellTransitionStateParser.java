package io.github.mekhontsev.magicdesk;

/** Reads the public WMShell transition state from the SystemUI dump. */
final class WmShellTransitionStateParser {
    enum State {
        UNAVAILABLE,
        BUSY,
        IDLE
    }

    private WmShellTransitionStateParser() {
    }

    static State parse(final String output) {
        if (output == null || output.isEmpty()) {
            return State.UNAVAILABLE;
        }
        boolean shellTransitions = false;
        boolean pendingSeen = false;
        boolean pendingIdle = false;
        boolean readySeen = false;
        boolean readyIdle = false;
        boolean tracksSeen = false;
        boolean tracksIdle = true;
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
                pendingIdle = "none".equals(value);
                section = Section.NONE;
            } else if (section == Section.READY) {
                readyIdle = "none".equals(value);
                section = Section.NONE;
            } else if (section == Section.TRACKS
                    && value.startsWith("active=")) {
                tracksSeen = true;
                tracksIdle &= "active=null".equals(value);
            }
        }
        if (!shellTransitions || !pendingSeen || !readySeen || !tracksSeen) {
            return State.UNAVAILABLE;
        }
        return pendingIdle && readyIdle && tracksIdle
                ? State.IDLE : State.BUSY;
    }

    private enum Section {
        NONE,
        PENDING,
        READY,
        TRACKS
    }
}
