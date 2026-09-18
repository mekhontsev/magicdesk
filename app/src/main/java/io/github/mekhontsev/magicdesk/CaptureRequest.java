package io.github.mekhontsev.magicdesk;

/** Explicit source and source-pixel selection, independent of UI or automation. */
record CaptureRequest(Target target, int id, Region region) {
    enum Target { DISPLAY, TASK }

    CaptureRequest {
        if (target == null || id < 0) throw new IllegalArgumentException("invalid capture target");
    }

    Region regionFor(final int displayWidth, final int displayHeight) {
        if (displayWidth <= 0 || displayHeight <= 0) {
            throw new IllegalArgumentException("invalid capture dimensions");
        }
        final Region selected = region == null ? new Region(0, 0, displayWidth, displayHeight) : region;
        if (selected.right() > displayWidth || selected.bottom() > displayHeight) {
            throw new IllegalArgumentException("capture region is outside the source image");
        }
        return selected;
    }

    /** Left/top inclusive, right/bottom exclusive, in the selected source's pixels. */
    record Region(int left, int top, int right, int bottom) {
        Region {
            if (left < 0 || top < 0 || right <= left || bottom <= top) {
                throw new IllegalArgumentException("capture region must be a nonempty nonnegative rectangle");
            }
        }

        int width() { return right - left; }
        int height() { return bottom - top; }
    }
}
