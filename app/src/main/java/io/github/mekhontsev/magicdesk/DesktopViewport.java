package io.github.mekhontsev.magicdesk;

import android.graphics.Insets;
import android.graphics.Rect;
import android.view.WindowInsets;
import android.view.WindowMetrics;

import java.util.Objects;

/**
 * Display-relative desktop geometry after persistent desktop obstructions
 * have been reserved.
 *
 * <p>The phone desktop reserves the system bars and places its taskbar above
 * persistent navigation. A dedicated external desktop owns the full display.</p>
 */
final class DesktopViewport {
    private final int mDisplayLeft;
    private final int mDisplayTop;
    private final int mDisplayRight;
    private final int mDisplayBottom;
    private final int mContentLeft;
    private final int mContentTop;
    private final int mContentRight;
    private final int mContentBottom;

    static DesktopViewport fromPhoneDesktopWindowMetrics(
            final WindowMetrics metrics) {
        if (metrics == null) {
            return new DesktopViewport(new Rect(0, 0, 1, 1), 0, 0, 0, 0);
        }
        return fromPhoneDesktopWindowMetrics(
                metrics, metrics.getWindowInsets());
    }

    static DesktopViewport fromDisplayBounds(final Rect bounds) {
        return new DesktopViewport(bounds, 0, 0, 0, 0);
    }

    static DesktopViewport fromPhoneDesktopWindowMetrics(
            final WindowMetrics metrics,
            final WindowInsets windowInsets) {
        if (metrics == null) {
            return new DesktopViewport(new Rect(0, 0, 1, 1), 0, 0, 0, 0);
        }
        // Ignoring visibility keeps desktop geometry stable when an app
        // transiently hides or reveals either system bar.
        final Insets insets = windowInsets == null
                ? Insets.NONE
                : windowInsets.getInsetsIgnoringVisibility(
                        WindowInsets.Type.systemBars());
        return new DesktopViewport(
                metrics.getBounds(),
                insets.left,
                insets.top,
                insets.right,
                insets.bottom);
    }

    DesktopViewport(
            final Rect displayBounds,
            final int insetLeft,
            final int insetTop,
            final int insetRight,
            final int insetBottom) {
        this(
                displayBounds == null ? 0 : displayBounds.left,
                displayBounds == null ? 0 : displayBounds.top,
                displayBounds == null ? 1 : displayBounds.right,
                displayBounds == null ? 1 : displayBounds.bottom,
                insetLeft,
                insetTop,
                insetRight,
                insetBottom);
    }

    DesktopViewport(
            final int displayLeft,
            final int displayTop,
            final int displayRight,
            final int displayBottom,
            final int insetLeft,
            final int insetTop,
            final int insetRight,
            final int insetBottom) {
        final boolean valid = displayRight > displayLeft
                && displayBottom > displayTop;
        mDisplayLeft = valid ? displayLeft : 0;
        mDisplayTop = valid ? displayTop : 0;
        mDisplayRight = valid ? displayRight : 1;
        mDisplayBottom = valid ? displayBottom : 1;
        final int displayWidth = mDisplayRight - mDisplayLeft;
        final int displayHeight = mDisplayBottom - mDisplayTop;
        final int left = clampInset(insetLeft, displayWidth - 1);
        final int top = clampInset(insetTop, displayHeight - 1);
        final int right = clampInset(
                insetRight, displayWidth - left - 1);
        final int bottom = clampInset(
                insetBottom, displayHeight - top - 1);
        mContentLeft = mDisplayLeft + left;
        mContentTop = mDisplayTop + top;
        mContentRight = mDisplayRight - right;
        mContentBottom = mDisplayBottom - bottom;
    }

    Rect displayBounds() {
        return new Rect(
                mDisplayLeft, mDisplayTop, mDisplayRight, mDisplayBottom);
    }

    Rect contentBounds() {
        return new Rect(
                mContentLeft, mContentTop, mContentRight, mContentBottom);
    }

    Rect taskbarBounds(final int requestedHeight) {
        final int height = Math.max(
                1, Math.min(requestedHeight, contentHeight()));
        return new Rect(
                mContentLeft,
                mContentBottom - height,
                mContentRight,
                mContentBottom);
    }

    Rect taskbarSurfaceBounds(final int requestedHeight) {
        final Rect bounds = taskbarBounds(requestedHeight);
        bounds.bottom = mDisplayBottom;
        return bounds;
    }

    Rect workAreaBounds(final int taskbarHeight) {
        final int taskbarTop = taskbarTop(taskbarHeight);
        return new Rect(
                mContentLeft,
                mContentTop,
                mContentRight,
                Math.max(mContentTop + 1, taskbarTop));
    }

    int contentLeft() {
        return mContentLeft;
    }

    int contentTop() {
        return mContentTop;
    }

    int contentRight() {
        return mContentRight;
    }

    int contentBottom() {
        return mContentBottom;
    }

    int contentWidth() {
        return mContentRight - mContentLeft;
    }

    int contentHeight() {
        return mContentBottom - mContentTop;
    }

    int taskbarTop(final int requestedHeight) {
        final int height = Math.max(
                1, Math.min(requestedHeight, contentHeight()));
        return mContentBottom - height;
    }

    int insetLeft() {
        return mContentLeft - mDisplayLeft;
    }

    int insetTop() {
        return mContentTop - mDisplayTop;
    }

    int insetRight() {
        return mDisplayRight - mContentRight;
    }

    int insetBottom() {
        return mDisplayBottom - mContentBottom;
    }

    @Override
    public boolean equals(final Object candidate) {
        if (this == candidate) {
            return true;
        }
        if (!(candidate instanceof DesktopViewport)) {
            return false;
        }
        final DesktopViewport other = (DesktopViewport) candidate;
        return mDisplayLeft == other.mDisplayLeft
                && mDisplayTop == other.mDisplayTop
                && mDisplayRight == other.mDisplayRight
                && mDisplayBottom == other.mDisplayBottom
                && mContentLeft == other.mContentLeft
                && mContentTop == other.mContentTop
                && mContentRight == other.mContentRight
                && mContentBottom == other.mContentBottom;
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                Integer.valueOf(mDisplayLeft),
                Integer.valueOf(mDisplayTop),
                Integer.valueOf(mDisplayRight),
                Integer.valueOf(mDisplayBottom),
                Integer.valueOf(mContentLeft),
                Integer.valueOf(mContentTop),
                Integer.valueOf(mContentRight),
                Integer.valueOf(mContentBottom));
    }

    @Override
    public String toString() {
        return "DesktopViewport{display=["
                + mDisplayLeft + "," + mDisplayTop + "]["
                + mDisplayRight + "," + mDisplayBottom + "]"
                + ", content=["
                + mContentLeft + "," + mContentTop + "]["
                + mContentRight + "," + mContentBottom + "]}";
    }

    private static int clampInset(final int value, final int maximum) {
        return Math.max(0, Math.min(value, Math.max(0, maximum)));
    }
}
