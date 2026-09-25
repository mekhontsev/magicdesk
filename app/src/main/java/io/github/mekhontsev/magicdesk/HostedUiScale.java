package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.view.WindowInsets;
import android.view.WindowManager;

/** Initial toolkit scale, independent of client size hints and rendered buffers. */
final class HostedUiScale {
    private HostedUiScale() { }

    static int resolve(Context context) {
        var resources = context.getResources();
        int density = resources.getConfiguration().densityDpi;
        if (context.isUiContext()) {
            var metrics = context.getSystemService(WindowManager.class).getCurrentWindowMetrics();
            var bounds = metrics.getBounds();
            // Use the host offer, not its IME-resized View or the client's constrained size.
            var insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            return resolve(density, Math.max(1, bounds.width() - insets.left - insets.right),
                    Math.max(1, bounds.height() - insets.top - insets.bottom));
        }
        var display = resources.getDisplayMetrics();
        return resolve(density, Math.max(1, display.widthPixels), Math.max(1, display.heightPixels));
    }

    static int resolve(int density, int width, int height) {
        if (density <= 0 || width <= 0 || height <= 0)
            throw new IllegalArgumentException("Invalid hosted display geometry");
        int desired = Math.max(1, Math.min(8, Math.round(density / 160f)));
        int fitting = Math.min(Math.min(width, height) / 600, Math.max(width, height) / 800);
        return Math.max(1, Math.min(desired, fitting));
    }
}
