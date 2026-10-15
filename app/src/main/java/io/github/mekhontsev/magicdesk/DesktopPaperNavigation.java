package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.IBinder;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import java.util.function.IntConsumer;

/** Small chrome-owned input windows, independent of the taskbar reveal state. */
final class DesktopPaperNavigation {
    private final WindowManager windows;
    private final ImageButton[] buttons = new ImageButton[2];
    private final Rect[] applied = {new Rect(), new Rect()};
    private final boolean[] added = new boolean[2];
    private final int widthPx;
    private final int heightPx;
    private IntConsumer navigate;

    DesktopPaperNavigation(Context context, WindowManager windows) {
        this.windows = windows;
        final float density = context.getResources().getDisplayMetrics().density;
        widthPx = Math.max(1, Math.round(40 * density));
        heightPx = Math.max(1, Math.round(64 * density));
        for (int i = 0; i < buttons.length; i++) {
            final int direction = i == 0 ? -1 : 1;
            final var button = new ImageButton(context);
            buttons[i] = button;
            button.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            UiAppearance.icon(button, R.drawable.ic_chevron_right,
                    UiColor.ACCENT);
            UiAppearance.imageStates(button, UiColor.ACCENT);
            if (i == 0) button.setScaleX(-1);
            final var background = new android.graphics.drawable.StateListDrawable();
            final float radius = 20 * density;
            background.addState(new int[] {-android.R.attr.state_enabled},
                    UiAppearance.edgeTabPaint(context, UiColor.SURFACE_LOW, radius));
            background.addState(new int[] {android.R.attr.state_pressed},
                    UiAppearance.edgeTabPaint(context, UiColor.ACCENT_CONTAINER, radius));
            background.addState(new int[] {android.R.attr.state_hovered},
                    UiAppearance.edgeTabPaint(context, UiColor.ACCENT_CONTAINER, radius));
            background.addState(new int[0],
                    UiAppearance.edgeTabPaint(context, UiColor.SURFACE_HIGH, radius));
            button.setBackground(background);
            button.setPadding(Math.round(8 * density), Math.round(20 * density),
                    Math.round(8 * density), Math.round(20 * density));
            final String label = context.getString(i == 0 ? R.string.paper_previous : R.string.paper_next);
            button.setContentDescription(label);
            button.setTooltipText(label);
            button.setOnClickListener(view -> {
                if (navigate != null && button.isEnabled()) {
                    // Suppress another click until the existing task publication
                    // reports completion or failure of the queued navigation.
                    for (var peer : buttons) peer.setEnabled(false);
                    navigate.accept(direction);
                }
            });
        }
    }

    void update(IBinder token, Rect area, boolean previous, boolean next, IntConsumer navigate) {
        this.navigate = navigate;
        if (token == null || area == null || area.isEmpty()) { clear(); return; }
        final int width = Math.min(widthPx, area.width() / 2);
        final int height = Math.min(heightPx, area.height());
        if (width < 1 || height < 1) { clear(); return; }
        for (int i = 0; i < buttons.length; i++) {
            buttons[i].setEnabled(i == 0 ? previous : next);
            final int x = i == 0 ? area.left : area.right - width;
            final int y = area.top + (area.height() - height) / 2;
            final Rect frame = new Rect(x, y, x + width, y + height);
            if (added[i] && applied[i].equals(frame)) continue;
            final var params = new WindowManager.LayoutParams(width, height,
                    WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            params.token = token;
            params.gravity = Gravity.TOP | Gravity.LEFT;
            params.x = x; params.y = y;
            params.setFitInsetsTypes(0);
            params.setTitle("MagicDesk tiling navigation " + i);
            if (added[i]) windows.updateViewLayout(buttons[i], params);
            else { windows.addView(buttons[i], params); added[i] = true; }
            applied[i].set(frame);
        }
    }

    void clear() {
        navigate = null;
        for (int i = 0; i < buttons.length; i++) {
            if (added[i]) windows.removeViewImmediate(buttons[i]);
            added[i] = false;
            applied[i].setEmpty();
        }
    }
}
