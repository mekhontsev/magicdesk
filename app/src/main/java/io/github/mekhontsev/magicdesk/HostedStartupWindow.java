package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.Intent;
import io.github.mekhontsev.magicdesk.hosted.HostedWindowLayout;

/** Temporary client geometry, deliberately separate from the application's persisted presentation. */
final class HostedStartupWindow {
    private static final String WIDTH = "hosted_startup_width", HEIGHT = "hosted_startup_height",
            FLOATING = "hosted_startup_floating", SCALE = "hosted_startup_scale";
    static void bind(Intent intent, HostedWindowLayout layout, float scale, boolean floating) {
        var size = layout.constraints().size(layout.width(), layout.height());
        intent.putExtra(WIDTH, Math.max(1, Math.round(size.width() * scale)))
                .putExtra(HEIGHT, Math.max(1, Math.round(size.height() * scale)))
                .putExtra(FLOATING, floating).putExtra(SCALE, scale);
    }
    static boolean temporary(Activity activity) { return activity.getIntent().hasExtra(WIDTH); }
    static float scale(Activity activity, float fallback) { return activity.getIntent().getFloatExtra(SCALE, fallback); }
    static void prepare(Activity activity) {
        if (activity.getIntent().getBooleanExtra(FLOATING, false)) activity.setTheme(R.style.HostedStartupTheme);
    }
    static void layout(Activity activity) {
        if (!activity.getIntent().getBooleanExtra(FLOATING, false)) return;
        var metrics = activity.getWindowManager().getMaximumWindowMetrics();
        var bounds = metrics.getBounds();
        var bars = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
        activity.getWindow().setLayout(Math.min(activity.getIntent().getIntExtra(WIDTH, 1), bounds.width() - bars.left - bars.right),
                Math.min(activity.getIntent().getIntExtra(HEIGHT, 1), bounds.height() - bars.top - bars.bottom));
    }
    private HostedStartupWindow() { }
}
