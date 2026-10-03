package io.github.mekhontsev.magicdesk;

import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

/** A readable content width inside a full-width page, measured against its actual host. */
final class UiContentColumn extends FrameLayout {
    private final int maximumWidthDp;

    UiContentColumn(View content, int maximumWidthDp) {
        super(content.getContext());
        this.maximumWidthDp = maximumWidthDp;
        addView(content, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL));
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int maximumWidth = Math.round(maximumWidthDp * getResources().getDisplayMetrics().density);
        int available = MeasureSpec.getMode(widthSpec) == MeasureSpec.UNSPECIFIED
                ? maximumWidth : MeasureSpec.getSize(widthSpec);
        getChildAt(0).getLayoutParams().width = Math.min(maximumWidth, available);
        super.onMeasure(widthSpec, heightSpec);
    }
}
