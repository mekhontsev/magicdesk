package io.github.mekhontsev.magicdesk;

import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;

/** Retains both panes while navigation moves between a sidebar and a narrow top strip. */
final class UiAdaptivePane extends ViewGroup {
    private final LinearLayout navigation;
    private final View content;
    private final HorizontalScrollView horizontal;
    private final ScrollView vertical;
    private final int sidebar, strip, breakpoint;
    private boolean wide;

    UiAdaptivePane(LinearLayout navigation, View content) {
        super(content.getContext());
        this.navigation = navigation; this.content = content;
        sidebar = UiToolLayout.dp(getContext(), 176);
        strip = UiToolLayout.dp(getContext(), 48);
        breakpoint = UiToolLayout.dp(getContext(), 720);
        horizontal = new HorizontalScrollView(getContext());
        horizontal.setHorizontalScrollBarEnabled(false);
        vertical = new ScrollView(getContext());
        vertical.setFillViewport(true);
        vertical.addView(navigation, new ScrollView.LayoutParams(-1, -2));
        horizontal.addView(vertical, new HorizontalScrollView.LayoutParams(-2, -1));
        addView(horizontal); addView(content);
    }

    static boolean wide(int width, int breakpoint) { return width >= breakpoint; }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        wide = wide(width, breakpoint);
        navigation.setOrientation(wide ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        navigation.getLayoutParams().width = wide ? sidebar : LayoutParams.WRAP_CONTENT;
        vertical.getLayoutParams().width = wide ? sidebar : LayoutParams.WRAP_CONTENT;
        for (int i = 0; i < navigation.getChildCount(); i++) {
            var params = navigation.getChildAt(i).getLayoutParams();
            params.width = wide ? LayoutParams.MATCH_PARENT : LayoutParams.WRAP_CONTENT;
            params.height = LayoutParams.WRAP_CONTENT;
            navigation.getChildAt(i).setMinimumHeight(strip);
        }
        int navWidth = wide ? Math.min(sidebar, width) : width;
        horizontal.measure(MeasureSpec.makeMeasureSpec(navWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(wide ? height : Math.min(strip, height), MeasureSpec.EXACTLY));
        content.measure(MeasureSpec.makeMeasureSpec(wide ? Math.max(0, width - navWidth) : width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(wide ? height : Math.max(0, height - horizontal.getMeasuredHeight()), MeasureSpec.EXACTLY));
        setMeasuredDimension(width, height);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int width = r - l, height = b - t;
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        int navWidth = horizontal.getMeasuredWidth(), navHeight = horizontal.getMeasuredHeight();
        int navLeft = wide && rtl ? width - navWidth : 0;
        horizontal.layout(navLeft, 0, navLeft + navWidth, navHeight);
        content.layout(wide && !rtl ? navWidth : 0, wide ? 0 : navHeight,
                wide && rtl ? width - navWidth : width, height);
    }
}
