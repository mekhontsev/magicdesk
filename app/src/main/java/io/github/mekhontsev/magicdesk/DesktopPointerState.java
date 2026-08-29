package io.github.mekhontsev.magicdesk;

import android.graphics.Point;

/** Immutable observation of the active desktop pointer pipeline. */
final class DesktopPointerState {
    final int displayId;
    final String provider;
    final boolean relayRequired;
    final boolean relayReady;
    final boolean routingReady;
    final Point position;

    DesktopPointerState(
            final int displayId,
            final String provider,
            final boolean relayRequired,
            final boolean relayReady,
            final boolean routingReady,
            final Point position) {
        this.displayId = displayId;
        this.provider = provider == null ? "android" : provider;
        this.relayRequired = relayRequired;
        this.relayReady = relayReady;
        this.routingReady = routingReady;
        this.position = position == null ? null : new Point(position);
    }
}
