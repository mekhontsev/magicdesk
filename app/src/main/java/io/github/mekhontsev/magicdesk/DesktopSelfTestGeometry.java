package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;
import android.view.Surface;

/** Derives deterministic self-test coordinates from the active desktop viewport. */
final class DesktopSelfTestGeometry {
    private static final int BASE_DENSITY_DPI = 160;
    private static final int NATIVE_CAPTION_HEIGHT_DP = 40;
    // Keeps all three native caption buttons present on high-density displays.
    private static final int CAPTION_CONTROLS_WINDOW_WIDTH_DP = 340;

    final Rect displayBounds;
    final Rect workArea;
    final int densityDpi;
    private final int displayRotation;
    private final int minimumWindowWidth;
    private final int minimumWindowHeight;

    DesktopSelfTestGeometry(
            final Rect displayBounds,
            final Rect workArea,
            final int densityDpi) {
        this(displayBounds, workArea, densityDpi, Surface.ROTATION_0);
    }

    DesktopSelfTestGeometry(
            final Rect displayBounds,
            final Rect workArea,
            final int densityDpi,
            final int displayRotation) {
        this(displayBounds, workArea, densityDpi, displayRotation, 1, 1);
    }

    private DesktopSelfTestGeometry(
            final Rect displayBounds,
            final Rect workArea,
            final int densityDpi,
            final int displayRotation,
            final int minimumWindowWidth,
            final int minimumWindowHeight) {
        if (!hasArea(displayBounds)
                || !hasArea(workArea)
                || workArea.left < displayBounds.left
                || workArea.top < displayBounds.top
                || workArea.right > displayBounds.right
                || workArea.bottom > displayBounds.bottom
                || densityDpi <= 0
                || displayRotation < Surface.ROTATION_0
                || displayRotation > Surface.ROTATION_270) {
            throw new IllegalArgumentException("invalid self-test geometry");
        }
        this.displayBounds = copy(displayBounds);
        this.workArea = copy(workArea);
        this.densityDpi = densityDpi;
        this.displayRotation = displayRotation;
        this.minimumWindowWidth = Math.max(1, minimumWindowWidth);
        this.minimumWindowHeight = Math.max(1, minimumWindowHeight);
    }

    Rect primaryWindow() {
        return relativeBounds(0.08f, 0.10f, 0.50f, 0.70f, false);
    }

    Rect browserWindow() {
        // Matches the wide window in which the Firefox fullscreen restore
        // regression was captured, while remaining relative to the work area.
        return relativeBounds(0.14f, 0.14f, 0.86f, 0.88f, false);
    }

    Rect leftWindow() {
        return relativeBounds(0.04f, 0.09f, 0.48f, 0.84f, false);
    }

    Rect rightWindow() {
        return relativeBounds(0.52f, 0.09f, 0.96f, 0.84f, true);
    }

    Rect captionControlsWindow(final boolean rightAnchored) {
        final Rect base = rightAnchored ? rightWindow() : leftWindow();
        final int desiredWidth = Math.min(
                width(workArea),
                Math.max(width(base),
                        scaleFrom160Dpi(CAPTION_CONTROLS_WINDOW_WIDTH_DP)));
        final int right = rightAnchored
                ? base.right : Math.min(workArea.right, base.left + desiredWidth);
        final int left = rightAnchored
                ? Math.max(workArea.left, right - desiredWidth) : base.left;
        return rect(left, base.top, right, base.bottom);
    }

    Rect captionRenderSample(final Rect windowBounds) {
        if (!hasArea(windowBounds)) {
            throw new IllegalArgumentException("invalid caption window bounds");
        }
        final int captionHeight = Math.min(
                height(windowBounds),
                scaleFrom160Dpi(NATIVE_CAPTION_HEIGHT_DP));
        final int verticalInset = Math.max(1, captionHeight / 6);
        return rect(
                windowBounds.left + width(windowBounds) / 2,
                windowBounds.top + verticalInset,
                windowBounds.right - 1,
                windowBounds.top + captionHeight - verticalInset);
    }

    DesktopSelfTestGeometry withObservedWindow(final Rect bounds) {
        if (!containsWindow(bounds)) {
            throw new IllegalArgumentException(
                    "observed window is outside the desktop work area");
        }
        return new DesktopSelfTestGeometry(
                displayBounds,
                workArea,
                densityDpi,
                displayRotation,
                Math.max(minimumWindowWidth, width(bounds)),
                Math.max(minimumWindowHeight, height(bounds)));
    }

    DesktopSelfTestGeometry withViewport(
            final Rect displayBounds,
            final Rect workArea) {
        return new DesktopSelfTestGeometry(
                displayBounds,
                workArea,
                densityDpi,
                displayRotation,
                minimumWindowWidth,
                minimumWindowHeight);
    }

    /** Converts InputDispatcher's natural-orientation frame to display space. */
    Rect inputFrame(final TaskInputWindowParser.Frame frame) {
        if (frame == null) {
            throw new IllegalArgumentException("input frame is unavailable");
        }
        final int naturalWidth = (displayRotation & 1) == 0
                ? width(displayBounds) : height(displayBounds);
        final int naturalHeight = (displayRotation & 1) == 0
                ? height(displayBounds) : width(displayBounds);
        final Rect rotated;
        switch (displayRotation) {
            case Surface.ROTATION_90:
                rotated = rect(
                        frame.top,
                        naturalWidth - frame.right,
                        frame.bottom,
                        naturalWidth - frame.left);
                break;
            case Surface.ROTATION_180:
                rotated = rect(
                        naturalWidth - frame.right,
                        naturalHeight - frame.bottom,
                        naturalWidth - frame.left,
                        naturalHeight - frame.top);
                break;
            case Surface.ROTATION_270:
                rotated = rect(
                        naturalHeight - frame.bottom,
                        frame.left,
                        naturalHeight - frame.top,
                        frame.right);
                break;
            case Surface.ROTATION_0:
            default:
                rotated = rect(
                        frame.left, frame.top, frame.right, frame.bottom);
                break;
        }
        return rect(
                rotated.left + displayBounds.left,
                rotated.top + displayBounds.top,
                rotated.right + displayBounds.left,
                rotated.bottom + displayBounds.top);
    }

    boolean containsWindow(final Rect bounds) {
        return hasArea(bounds)
                && bounds.left >= workArea.left
                && bounds.top >= workArea.top
                && bounds.right <= workArea.right
                && bounds.bottom <= workArea.bottom;
    }

    int scaleFrom160Dpi(final int pixels) {
        return Math.max(1, Math.round(
                pixels * densityDpi / (float) BASE_DENSITY_DPI));
    }

    boolean isSnapped(final Rect bounds, final boolean left) {
        if (!hasArea(bounds)) {
            return false;
        }
        final int midpoint = (workArea.left + workArea.right) / 2;
        final int tolerance = Math.max(16, width(workArea) / 10);
        return left
                ? bounds.left <= workArea.left + tolerance
                        && Math.abs(bounds.right - midpoint) <= tolerance
                : bounds.right >= workArea.right - tolerance
                        && Math.abs(bounds.left - midpoint) <= tolerance;
    }

    boolean isNativeSideBySide(final Rect left, final Rect right) {
        if (!hasArea(left) || !hasArea(right)) {
            return false;
        }
        final int tolerance = placementAlignmentTolerance();
        final int midpoint = (workArea.left + workArea.right) / 2;
        final int expectedWidth = Math.max(
                width(workArea) / 2, minimumWindowWidth);
        return isSnapped(left, true)
                && isSnapped(right, false)
                && left.left < right.left
                && Math.abs(width(left) - expectedWidth) <= tolerance
                && Math.abs(width(right) - expectedWidth) <= tolerance
                && Math.abs(left.top - right.top) <= tolerance
                && Math.abs(left.bottom - right.bottom) <= tolerance;
    }

    int placementAlignmentTolerance() {
        return Math.max(16, width(workArea) / 20);
    }

    @Override
    public String toString() {
        return "display=" + format(displayBounds)
                + ", work=" + format(workArea)
                + ", density=" + densityDpi;
    }

    private Rect relativeBounds(
            final float leftFraction,
            final float topFraction,
            final float rightFraction,
            final float bottomFraction,
            final boolean rightAnchored) {
        final int width = width(workArea);
        final int height = height(workArea);
        int left = workArea.left + Math.round(width * leftFraction);
        int top = workArea.top + Math.round(height * topFraction);
        int right = workArea.left + Math.round(width * rightFraction);
        int bottom = workArea.top + Math.round(height * bottomFraction);
        final int desiredWidth = Math.min(
                width,
                Math.max(right - left, minimumWindowWidth));
        final int desiredHeight = Math.min(
                height,
                Math.max(bottom - top, minimumWindowHeight));
        if (rightAnchored) {
            left = right - desiredWidth;
        } else {
            right = left + desiredWidth;
        }
        bottom = top + desiredHeight;
        if (left < workArea.left) {
            right += workArea.left - left;
            left = workArea.left;
        }
        if (right > workArea.right) {
            left -= right - workArea.right;
            right = workArea.right;
        }
        if (bottom > workArea.bottom) {
            top -= bottom - workArea.bottom;
            bottom = workArea.bottom;
        }
        if (top < workArea.top) {
            top = workArea.top;
        }
        return rect(left, top, right, bottom);
    }

    private static Rect copy(final Rect source) {
        return rect(source.left, source.top, source.right, source.bottom);
    }

    private static Rect rect(
            final int left,
            final int top,
            final int right,
            final int bottom) {
        final Rect bounds = new Rect();
        bounds.left = left;
        bounds.top = top;
        bounds.right = right;
        bounds.bottom = bottom;
        return bounds;
    }

    private static boolean hasArea(final Rect bounds) {
        return bounds != null
                && bounds.right > bounds.left
                && bounds.bottom > bounds.top;
    }

    private static int width(final Rect bounds) {
        return bounds.right - bounds.left;
    }

    private static int height(final Rect bounds) {
        return bounds.bottom - bounds.top;
    }

    static Rect toRect(final TaskStackParser.Bounds bounds) {
        return bounds == null ? null : rect(
                bounds.left, bounds.top, bounds.right, bounds.bottom);
    }

    static boolean matches(
            final TaskStackParser.Bounds actual,
            final Rect expected) {
        return actual != null
                && expected != null
                && actual.left == expected.left
                && actual.top == expected.top
                && actual.right == expected.right
                && actual.bottom == expected.bottom;
    }

    static String format(final TaskStackParser.Bounds bounds) {
        return bounds == null ? "unavailable" : "[" + bounds.left + ","
                + bounds.top + "][" + bounds.right + "," + bounds.bottom + "]";
    }

    static String format(final Rect bounds) {
        return bounds == null ? "unavailable" : "[" + bounds.left + ","
                + bounds.top + "][" + bounds.right + "," + bounds.bottom + "]";
    }
}
