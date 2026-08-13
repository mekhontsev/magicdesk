package io.github.mekhontsev.magicdesk;

/** Identifies either an Android logical display or a SurfaceFlinger output. */
final class DisplayCaptureSource {
    final int logicalDisplayId;
    final String physicalDisplayId;

    private DisplayCaptureSource(
            final int logicalDisplayId,
            final String physicalDisplayId) {
        if (logicalDisplayId < 0) {
            throw new IllegalArgumentException("invalid logical display id");
        }
        if (physicalDisplayId != null
                && !physicalDisplayId.matches("[0-9]+")) {
            throw new IllegalArgumentException("invalid physical display id");
        }
        this.logicalDisplayId = logicalDisplayId;
        this.physicalDisplayId = physicalDisplayId;
    }

    static DisplayCaptureSource logical(final int displayId) {
        return new DisplayCaptureSource(displayId, null);
    }

    static DisplayCaptureSource physical(
            final int logicalDisplayId,
            final String physicalDisplayId) {
        if (physicalDisplayId == null || physicalDisplayId.isEmpty()) {
            throw new IllegalArgumentException("physical display id is required");
        }
        return new DisplayCaptureSource(logicalDisplayId, physicalDisplayId);
    }

    static DisplayCaptureSource parse(final String value) {
        if (value == null || value.length() < 3 || value.charAt(1) != ':') {
            throw new IllegalArgumentException("invalid display capture source");
        }
        if (value.charAt(0) == 'l') {
            return logical(Integer.parseInt(value.substring(2)));
        }
        if (value.charAt(0) == 'p') {
            final String[] ids = value.substring(2).split(",", -1);
            if (ids.length != 2) {
                throw new IllegalArgumentException(
                        "invalid physical display capture source");
            }
            return physical(Integer.parseInt(ids[0]), ids[1]);
        }
        throw new IllegalArgumentException("invalid display capture source");
    }

    boolean isPhysical() {
        return physicalDisplayId != null;
    }

    String commandArgument() {
        return isPhysical()
                ? "p:" + logicalDisplayId + "," + physicalDisplayId
                : "l:" + logicalDisplayId;
    }
}
