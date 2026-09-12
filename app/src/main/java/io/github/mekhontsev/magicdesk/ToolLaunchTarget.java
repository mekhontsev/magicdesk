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
                return ordinary(0, desktops);
            case "display":
                if (requestedDisplay < 0) {
                    throw new IllegalArgumentException("display placement requires displayId");
                }
                return ordinary(requestedDisplay, desktops);
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

    private static ToolLaunchTarget ordinary(final int display, final java.util.Set<Integer> desktops) {
        if (desktops.contains(display)) {
            throw new IllegalStateException("display belongs to Desktop; use desktop placement");
        }
        return new ToolLaunchTarget(display, false);
    }

    void requireCurrent(final java.util.Set<Integer> desktops) {
        if (desktop != desktops.contains(displayId)) {
            throw new IllegalStateException("display ownership changed; select the launch destination again");
        }
    }
}
