package io.github.mekhontsev.magicdesk;

/** Display-pixel selection, independent of its origin (UI, accessibility, or automation). */
record DisplayCaptureRequest(int displayId, Region region) {
    DisplayCaptureRequest {
        if (displayId < 0) throw new IllegalArgumentException("invalid display id");
    }

    Region regionFor(final int displayWidth, final int displayHeight) {
        if (displayWidth <= 0 || displayHeight <= 0) {
            throw new IllegalArgumentException("invalid display dimensions");
        }
        final Region selected = region == null ? new Region(0, 0, displayWidth, displayHeight) : region;
        if (selected.right() > displayWidth || selected.bottom() > displayHeight) {
            throw new IllegalArgumentException("capture region is outside the display");
        }
        return selected;
    }

    /** Left/top inclusive, right/bottom exclusive, at the display's current rotation. */
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
