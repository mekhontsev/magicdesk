package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import io.github.mekhontsev.magicdesk.hosted.HostedWindowConstraints;

/** Fits constrained client content inside its Android host, including the IME-safe area. */
final class HostedContentLayout extends FrameLayout {
    private HostedWindowConstraints constraints = HostedWindowConstraints.NONE;
    private float unitScale = 1;

    HostedContentLayout(Context context, View content) {
        super(context);
        addView(content, new LayoutParams(-1, -1, Gravity.CENTER));
    }

    void constraints(HostedWindowConstraints next, float scale) {
        if (constraints.equals(next) && unitScale == scale) return;
        constraints = next; unitScale = scale;
        requestLayout();
    }

    @Override protected void onMeasure(int width, int height) {
        super.onMeasure(width, height);
        var child = getChildAt(0);
        int availableWidth = getMeasuredWidth(), availableHeight = getMeasuredHeight();
        int w = constraints.maxWidth() == 0 ? availableWidth
                : Math.min(availableWidth, Math.max(1, Math.round(constraints.maxWidth() * unitScale)));
        int h = constraints.maxHeight() == 0 ? availableHeight
                : Math.min(availableHeight, Math.max(1, Math.round(constraints.maxHeight() * unitScale)));
        child.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
    }
}
