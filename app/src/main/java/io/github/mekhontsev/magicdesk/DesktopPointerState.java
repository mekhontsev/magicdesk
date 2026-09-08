package io.github.mekhontsev.magicdesk;

/** Immutable observation of the active desktop pointer pipeline. */
final class DesktopPointerState {
    final int displayId;
    final String provider;
    final boolean relayRequired;
    final boolean relayReady;
    final boolean routingReady;
    final PointerPosition observation;

    DesktopPointerState(
            final int displayId,
            final String provider,
            final boolean relayRequired,
            final boolean relayReady,
            final boolean routingReady,
            final PointerPosition observation) {
        this.displayId = displayId;
        this.provider = provider == null ? "android" : provider;
        this.relayRequired = relayRequired;
        this.relayReady = relayReady;
        this.routingReady = routingReady;
        this.observation = observation;
    }

    PointerPosition positionOnDisplay() {
        return observation != null && observation.belongsTo(displayId)
                ? observation : null;
    }
}
