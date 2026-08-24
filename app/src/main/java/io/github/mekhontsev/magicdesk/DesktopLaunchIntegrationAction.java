package io.github.mekhontsev.magicdesk;

/** One optional application action contributed by a launch integration. */
final class DesktopLaunchIntegrationAction {
    final String id;
    final int labelResource;
    final boolean enabled;

    DesktopLaunchIntegrationAction(
            final String id,
            final int labelResource,
            final boolean enabled) {
        this.id = id;
        this.labelResource = labelResource;
        this.enabled = enabled;
    }
}
