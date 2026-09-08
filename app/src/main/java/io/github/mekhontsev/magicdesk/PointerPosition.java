package io.github.mekhontsev.magicdesk;

/** Cursor observation whose display identity is supplied by its source, never by its caller. */
public final class PointerPosition {
    public final int displayId;
    public final int x;
    public final int y;

    public PointerPosition(final int displayId, final int x, final int y) {
        if (displayId < -1) {
            throw new IllegalArgumentException("invalid pointer display");
        }
        this.displayId = displayId;
        this.x = x;
        this.y = y;
    }

    public boolean belongsTo(final int targetDisplayId) {
        return displayId >= 0 && displayId == targetDisplayId;
    }

    String reportLabel() {
        return "display=" + (displayId < 0 ? "unknown" : displayId)
                + ", x=" + x + ", y=" + y;
    }
}
