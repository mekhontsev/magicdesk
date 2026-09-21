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
