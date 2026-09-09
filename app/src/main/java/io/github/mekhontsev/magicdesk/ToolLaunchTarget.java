package io.github.mekhontsev.magicdesk;

/** Placement is independent of shell authorization and terminal session identity. */
final class ToolLaunchTarget {
    final int displayId;
    final boolean desktop;

    private ToolLaunchTarget(final int displayId, final boolean desktop) {
        this.displayId = displayId;
        this.desktop = desktop;
    }

    static ToolLaunchTarget resolve(final String placement, final int requestedDisplay,
            final int activeDesktop) {
        switch (placement) {
            case "auto":
                final int display = requestedDisplay >= 0 ? requestedDisplay
                        : activeDesktop >= 0 ? activeDesktop : 0;
                return new ToolLaunchTarget(display, display == activeDesktop);
            case "desktop":
                if (activeDesktop < 0 || requestedDisplay >= 0 && requestedDisplay != activeDesktop) {
                    throw new IllegalStateException("no desktop session on the requested display");
                }
                return new ToolLaunchTarget(activeDesktop, true);
            case "phone":
                if (requestedDisplay > 0) {
                    throw new IllegalArgumentException("phone placement requires display 0");
                }
                return ordinary(0, activeDesktop);
            case "display":
                if (requestedDisplay < 0) {
                    throw new IllegalArgumentException("display placement requires displayId");
                }
                return ordinary(requestedDisplay, activeDesktop);
            default:
                throw new IllegalArgumentException("unknown tool placement: " + placement);
        }
    }

    private static ToolLaunchTarget ordinary(final int display, final int activeDesktop) {
        if (display == activeDesktop) {
            throw new IllegalStateException("display belongs to Desktop; use desktop placement");
        }
        return new ToolLaunchTarget(display, false);
    }
}
