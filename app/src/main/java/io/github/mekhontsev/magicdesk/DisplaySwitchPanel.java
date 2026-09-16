package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** A bounded, non-focusable picker; showing it never changes an application's focus. */
final class DisplaySwitchPanel implements AutoCloseable {
    private final List<TextView> rows = new ArrayList<>();
    private final ScrollView scroll;
    private final WindowManager windows;
    private final PopupWindow popup;
    private final View content;

    DisplaySwitchPanel(int outputId, Activity fallback, List<String> labels) {
        final Context overlay = DesktopShortcutService.switcherContext(outputId);
        final Context context = overlay != null ? overlay : fallback;
        if (context == null) throw new IllegalStateException("Display switcher host is unavailable");
        final float density = context.getResources().getDisplayMetrics().density;
        final int padding = Math.round(12 * density);
        final LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(padding, padding, padding, padding);
        final GradientDrawable background = new GradientDrawable();
        background.setColor(DesktopUiFactory.COLOR_PANEL);
        background.setCornerRadius(8 * density);
        background.setStroke(Math.max(1, Math.round(density)), DesktopUiFactory.COLOR_MUTED);
        list.setBackground(background);
        for (String label : labels) {
            final TextView row = new TextView(context);
            row.setText(label);
            row.setTextSize(14);
            row.setTextColor(DesktopUiFactory.COLOR_TEXT);
            row.setPadding(padding, padding, padding, padding);
            list.addView(row, new LinearLayout.LayoutParams(-1, -2));
            rows.add(row);
        }
        scroll = new ScrollView(context);
        scroll.addView(list);
        content = scroll;
        final var metrics = context.getResources().getDisplayMetrics();
        final int width = Math.min(Math.round(440 * density), metrics.widthPixels - padding * 2);
        final int height = Math.min(Math.round((labels.size() * 72 + 24) * density),
                Math.round(metrics.heightPixels * 0.7f));
        if (overlay != null) {
            windows = context.getSystemService(WindowManager.class);
            popup = null;
            final WindowManager.LayoutParams params = new WindowManager.LayoutParams(width, height,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.CENTER;
            params.setTitle("MagicDesk display switcher");
            windows.addView(content, params);
        } else {
            windows = null;
            popup = new PopupWindow(content, width, height, false);
            popup.setTouchable(false);
            popup.setClippingEnabled(true);
            popup.showAtLocation(fallback.getWindow().getDecorView(), Gravity.CENTER, 0, 0);
        }
    }

    void select(int index) {
        for (int i = 0; i < rows.size(); i++) {
            rows.get(i).setBackgroundColor(i == index ? DesktopUiFactory.COLOR_CYAN : android.graphics.Color.TRANSPARENT);
            rows.get(i).setTextColor(i == index ? DesktopUiFactory.COLOR_BACKGROUND : DesktopUiFactory.COLOR_TEXT);
        }
        final TextView row = rows.get(index);
        scroll.post(() -> row.requestRectangleOnScreen(
                new android.graphics.Rect(0, 0, row.getWidth(), row.getHeight()), true));
    }

    @Override public void close() {
        if (popup != null) popup.dismiss();
        else if (content.isAttachedToWindow()) {
            try { windows.removeViewImmediate(content); }
            catch (IllegalArgumentException removedWithDisplay) { /* Android already removed its display token. */ }
        }
    }
}
