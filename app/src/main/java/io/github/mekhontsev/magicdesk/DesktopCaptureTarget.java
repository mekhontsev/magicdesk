package io.github.mekhontsev.magicdesk;

import java.io.IOException;

/** Resolves the active desktop to the physical compositor output to capture. */
final class DesktopCaptureTarget {
    final int desktopDisplayId;
    final String physicalDisplayId;

    private DesktopCaptureTarget(
            final int desktopDisplayId,
            final String physicalDisplayId) {
        this.desktopDisplayId = desktopDisplayId;
        this.physicalDisplayId = physicalDisplayId;
    }

    static DesktopCaptureTarget resolveActive() throws IOException {
        final int desktopDisplayId =
                DesktopRuntimeBridge.getActiveDesktopDisplayId();
        if (desktopDisplayId < 0) {
            throw new IOException("no active desktop display");
        }
        final DesktopDisplayTarget target =
                DesktopRuntimeBridge.getActiveDesktopTarget();
        if (target == null || target.displayId != desktopDisplayId) {
            throw new IOException("active desktop target is unavailable");
        }
        final int captureDisplayId;
        try {
            captureDisplayId = DesktopDisplayDrivers.forTarget(target)
                    .captureDisplayId(target);
        } catch (IllegalArgumentException | IllegalStateException error) {
            throw new IOException(error.getMessage(), error);
        }
        return new DesktopCaptureTarget(
                desktopDisplayId,
                ConsoleDisplayController.getPhysicalDisplayId(
                        captureDisplayId));
    }
}
