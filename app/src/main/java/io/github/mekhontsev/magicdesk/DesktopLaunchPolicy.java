package io.github.mekhontsev.magicdesk;

/** One presentation decision for apps, built-ins, Intents and published shortcuts. */
final class DesktopLaunchPolicy {
    final DesktopLaunchMode mode;
    final RelativeWindowBounds bounds;
    final boolean explicitWindowed;

    private DesktopLaunchPolicy(DesktopLaunchMode mode, RelativeWindowBounds bounds,
            boolean explicitWindowed) {
        this.mode = mode;
        this.bounds = bounds;
        this.explicitWindowed = explicitWindowed;
    }

    /**
     * Windowed launch stays available when the application only asks to hide system bars.
     * Immersive requests keep their freeform bounds in the existing observer path
     * (docs/fullscreen-transitions.md); games and unresizable activities still need
     * their fullscreen plane.
     */
    static boolean prefersWindowed(boolean canFloat, String fullscreenReason) {
        return canFloat && !AppItem.FULLSCREEN_REASON_GAME.equals(fullscreenReason)
                && !AppItem.FULLSCREEN_REASON_UNRESIZEABLE.equals(fullscreenReason);
    }

    static DesktopLaunchPolicy resolve(DesktopLaunchPresentation request, AppWindowState saved,
            DesktopLaunchMode existingMode, boolean windowingAvailable, boolean prefersWindow,
            boolean phoneWorkspace, boolean phoneFullscreenDefault) {
        DesktopLaunchMode mode = request.mode;
        boolean explicitWindowed = mode == DesktopLaunchMode.WINDOWED;
        if (mode == DesktopLaunchMode.AUTO) {
            if (request.instancePolicy == DesktopTaskInstancePolicy.REUSE_EXISTING
                    && existingMode != null && existingMode != DesktopLaunchMode.AUTO) {
                mode = existingMode;
            } else if (saved != null && saved.shouldLaunchWindowed() && windowingAvailable) {
                mode = DesktopLaunchMode.WINDOWED;
                explicitWindowed = true;
            } else if (saved != null && saved.mode == AppWindowState.Mode.FULLSCREEN) {
                mode = DesktopLaunchMode.FULLSCREEN;
            } else {
                mode = windowingAvailable && prefersWindow && !(phoneWorkspace && phoneFullscreenDefault)
                        ? DesktopLaunchMode.WINDOWED : DesktopLaunchMode.FULLSCREEN;
            }
        }
        return new DesktopLaunchPolicy(mode,
                request.bounds != null ? request.bounds : saved == null ? null : saved.windowBounds,
                explicitWindowed);
    }
}
