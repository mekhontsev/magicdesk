package io.github.mekhontsev.magicdesk;

/** Activity placement is independent of authorization and Desktop lifetime. */
final class ToolLaunchTarget {
    final int displayId;
    final boolean desktop;

    private ToolLaunchTarget(final int displayId, final boolean desktop) {
        this.displayId = displayId;
        this.desktop = desktop;
    }

    static ToolLaunchTarget resolve(final String placement, final int requestedDisplay,
            final java.util.Set<Integer> desktops) {
        if (requestedDisplay < -1) {
            throw new IllegalArgumentException("invalid display id");
        }
        switch (placement) {
            case "auto":
                final int display = requestedDisplay >= 0 ? requestedDisplay
                        : defaultDisplay(desktops);
                return new ToolLaunchTarget(display, desktops.contains(display));
            case "desktop":
                final int desktop = requestedDisplay >= 0 ? requestedDisplay : defaultDisplay(desktops);
                if (!desktops.contains(desktop)) {
                    throw new IllegalStateException("no desktop session on the requested display");
                }
                return new ToolLaunchTarget(desktop, true);
            case "phone":
                if (requestedDisplay > 0) {
                    throw new IllegalArgumentException("phone placement requires display 0");
                }
                return new ToolLaunchTarget(0, false);
            case "display":
                if (requestedDisplay < 0) {
                    throw new IllegalArgumentException("display placement requires displayId");
                }
                return new ToolLaunchTarget(requestedDisplay, false);
            default:
                throw new IllegalArgumentException("unknown tool placement: " + placement);
        }
    }

    private static int defaultDisplay(final java.util.Set<Integer> desktops) {
        if (desktops.size() > 1) {
            throw new IllegalStateException("several Desktops are running; specify displayId");
        }
        return desktops.isEmpty() ? 0 : desktops.iterator().next();
    }

    void requireCurrent(final java.util.Set<Integer> desktops) {
        if (desktop && !desktops.contains(displayId)) {
            throw new IllegalStateException("the selected Desktop is no longer running");
        }
    }
}
