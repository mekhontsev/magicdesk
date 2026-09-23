package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;

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
import android.widget.ImageButton;
import java.util.ArrayList;
import java.util.List;

/** A bounded, non-focusable picker; showing it never changes an application's focus. */
final class DisplaySwitchPanel implements AutoCloseable {
    private final List<TextView> rows = new ArrayList<>();
    private final ScrollView scroll;
    private final WindowManager windows;
    private final PopupWindow popup;
    private final View content;
    private final DesktopPanelWindowController panels;
    private final DesktopUiFactory ui;

    DisplaySwitchPanel(int outputId, Activity fallback, List<String> labels,
            DesktopShellActivity pointerHost, java.util.function.IntConsumer choose, Runnable cancel) {
        final Context overlay = pointerHost == null ? DesktopShortcutService.switcherContext(outputId) : null;
        final Context context = pointerHost != null ? pointerHost : overlay != null ? overlay : fallback;
        if (context == null) throw new IllegalStateException("Display switcher host is unavailable");
        ui = new DesktopUiFactory(context);
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
        if (pointerHost != null) {
            final LinearLayout header = new LinearLayout(context);
            header.setGravity(Gravity.CENTER_VERTICAL);
            final TextView title = new TextView(context);
            title.setText(R.string.display_switch);
            title.setTextColor(DesktopUiFactory.COLOR_TEXT);
            title.setTextSize(16);
            header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
            final ImageButton close = ui.menuIconButton(
                    R.drawable.ic_close, R.string.action_close);
            close.setOnClickListener(view -> cancel.run());
            header.addView(close, new LinearLayout.LayoutParams(Math.round(48 * density), Math.round(48 * density)));
            pointerHost.registerAutomationUiElement(close, "display_switch.close", "button",
                    context.getString(R.string.action_close));
            list.addView(header);
        }
        for (String label : labels) {
            final TextView row = new TextView(context);
            row.setText(label);
            row.setTextSize(14);
            row.setTextColor(DesktopUiFactory.COLOR_TEXT);
            row.setPadding(padding, padding, padding, padding);
            if (pointerHost != null) {
                final int index = rows.size();
                row.setOnClickListener(view -> choose.accept(index));
                pointerHost.registerAutomationUiElement(row, "display_switch.choice." + index,
                        "button", label);
            }
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
        if (pointerHost != null) {
            windows = null;
            popup = null;
            panels = pointerHost.panels();
            final Rect area = pointerHost.getDesktopPanelAreaBounds();
            final int panelWidth = Math.min(width, Math.max(1, area.width() - padding * 2));
            final int panelHeight = Math.min(height + Math.round(48 * density), Math.max(1,
                    area.height() - padding * 2));
            content.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View view) { }
                @Override public void onViewDetachedFromWindow(View view) { cancel.run(); }
            });
            if (panels == null || !panels.show(content,
                    ShellPanelPlacement.centered(panelWidth, panelHeight),
                    false, false, "MagicDesk display switcher")) {
                throw new IllegalStateException("Display switcher panel is unavailable");
            }
        } else if (overlay != null) {
            panels = null;
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
            panels = null;
            windows = null;
            popup = new PopupWindow(content, width, height, false);
            popup.setTouchable(false);
            popup.setClippingEnabled(true);
            popup.showAtLocation(fallback.getWindow().getDecorView(), Gravity.CENTER, 0, 0);
        }
    }

    void select(int index) {
        for (int i = 0; i < rows.size(); i++) {
            if (panels == null) rows.get(i).setBackgroundColor(
                    i == index ? DesktopUiFactory.COLOR_CYAN : android.graphics.Color.TRANSPARENT);
            else rows.get(i).setBackground(ui.interactiveRounded(i == index
                    ? DesktopUiFactory.COLOR_CYAN : DesktopUiFactory.COLOR_PANEL, ui.dp(4), DesktopUiFactory.COLOR_CYAN));
            rows.get(i).setSelected(i == index);
            rows.get(i).setTextColor(i == index ? DesktopUiFactory.COLOR_BACKGROUND : DesktopUiFactory.COLOR_TEXT);
        }
        final TextView row = rows.get(index);
        scroll.post(() -> row.requestRectangleOnScreen(
                new android.graphics.Rect(0, 0, row.getWidth(), row.getHeight()), true));
    }

    @Override public void close() {
        if (panels != null) panels.hide(content);
        else if (popup != null) popup.dismiss();
        else if (content.isAttachedToWindow()) {
            try { windows.removeViewImmediate(content); }
            catch (IllegalArgumentException removedWithDisplay) { /* Android already removed its display token. */ }
        }
    }
}
