package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;
import io.github.mekhontsev.magicdesk.hosted.HostedMaximization;

/** Shared work-area geometry, independent of protocol and Android host lifetime. */
final class WindowMaximization {
    private WindowMaximization() { }
    static HostedMaximization observe(Rect bounds, Rect work) {
        return work == null ? HostedMaximization.NONE : HostedMaximization.of(
                bounds.left == work.left && bounds.right == work.right,
                bounds.top == work.top && bounds.bottom == work.bottom);
    }
    static Rect target(HostedMaximization axes, Rect restore, Rect work) {
        return new Rect(axes.horizontal ? work.left : restore.left, axes.vertical ? work.top : restore.top,
                axes.horizontal ? work.right : restore.right, axes.vertical ? work.bottom : restore.bottom);
    }
}
