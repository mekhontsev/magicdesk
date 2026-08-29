package io.github.mekhontsev.magicdesk;

import android.graphics.Point;

/** Immutable, explicitly requested snapshot of the live input relays. */
final class InputRelayRuntimeDiagnostics {
    static final class BridgeSnapshot {
        final boolean running;
        final boolean ready;
        final boolean capture;
        final long generation;
        final String nativeStats;
        final String statsError;

        BridgeSnapshot(
                final boolean running,
                final boolean ready,
                final boolean capture,
                final long generation,
                final String nativeStats,
                final String statsError) {
            this.running = running;
            this.ready = ready;
            this.capture = capture;
            this.generation = generation;
            this.nativeStats = clean(nativeStats);
            this.statsError = clean(statsError);
        }

        String reportLine() {
            final StringBuilder line = new StringBuilder()
                    .append("running=").append(running)
                    .append(", ready=").append(ready)
                    .append(", capture=").append(capture)
                    .append(", generation=").append(generation);
            if (!nativeStats.isEmpty()) {
                line.append(", ").append(nativeStats);
            }
            if (!statsError.isEmpty()) {
                line.append(", statsError=").append(statsError);
            }
            return line.toString();
        }
    }

    static final class Snapshot {
        final int displayId;
        final BridgeSnapshot mouse;
        final BridgeSnapshot keyboard;
        final String pointerProvider;
        final boolean pointerRelayRequired;
        final boolean pointerRelayReady;
        final boolean pointerRoutingReady;
        final Point pointerPosition;

        Snapshot(
                final int displayId,
                final BridgeSnapshot mouse,
                final BridgeSnapshot keyboard,
                final DesktopPointerState pointer) {
            this.displayId = displayId;
            this.mouse = mouse;
            this.keyboard = keyboard;
            pointerProvider = pointer == null
                    ? "unknown" : pointer.provider;
            pointerRelayRequired = pointer != null
                    && pointer.relayRequired;
            pointerRelayReady = pointer != null && pointer.relayReady;
            pointerRoutingReady = pointer != null && pointer.routingReady;
            pointerPosition = pointer == null || pointer.position == null
                    ? null : new Point(pointer.position);
        }

        static Snapshot unavailable() {
            final BridgeSnapshot unavailable = new BridgeSnapshot(
                    false, false, false, -1, "", "runtime unavailable");
            return new Snapshot(-1, unavailable, unavailable, null);
        }
    }

    private InputRelayRuntimeDiagnostics() {
    }

    private static String clean(final String value) {
        final String normalized = value == null
                ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
        return normalized.length() <= 1_000
                ? normalized : normalized.substring(0, 1_000);
    }
}
