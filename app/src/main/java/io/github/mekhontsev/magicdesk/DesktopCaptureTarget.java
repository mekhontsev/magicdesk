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
        final DisplayCaptureSource source;
        try {
            source = sourceForWorkspace(
                    desktopDisplayId);
        } catch (IllegalArgumentException | IllegalStateException error) {
            throw new IOException(error.getMessage(), error);
        }
        final String physicalDisplayId = source.isPhysical()
                ? source.physicalDisplayId
                : ExternalDisplayController.getPhysicalDisplayId(
                        source.logicalDisplayId);
        return new DesktopCaptureTarget(
                desktopDisplayId,
                physicalDisplayId);
    }

    static DisplayCaptureSource sourceForWorkspace(final int workspaceDisplayId) {
        final DesktopDisplayTarget target = workspaceDisplayId == 0
                ? DesktopDisplayTarget.phone()
                : DesktopRuntimeBridge.getDesktopTarget(workspaceDisplayId);
        if (target == null) {
            throw new IllegalStateException("desktop workspace is unavailable: " + workspaceDisplayId);
        }
        return sourceFor(target);
    }

    static DisplayCaptureSource sourceFor(final DesktopDisplayTarget target) {
        return DisplayCaptureSource.logical(target.workspaceDisplayId);
    }

    String diagnosticDetail() {
        return "desktopDisplay=" + desktopDisplayId
                + ", physicalDisplay=" + physicalDisplayId;
    }
}
