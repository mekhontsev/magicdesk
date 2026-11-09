package io.github.mekhontsev.magicdesk;

import android.graphics.Color;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;

/** Foreground-only window policy; no service, privileged access or global power settings. */
final class BlackScreenMode {
    private final Window window;
    private final View panel;
    private final FrameLayout root;
    private final View black;
    private WindowManager.LayoutParams previous;
    private int visibleBars;
    private int barsBehavior;
    private int volumeKey = KeyEvent.KEYCODE_UNKNOWN;

    BlackScreenMode(Window window, View panel) {
        this.window = window;
        this.panel = panel;
        root = new FrameLayout(panel.getContext());
        root.addView(panel, new FrameLayout.LayoutParams(-1, -1));
        black = new View(panel.getContext());
        black.setBackgroundColor(Color.BLACK);
        black.setVisibility(View.GONE);
        root.addView(black, new FrameLayout.LayoutParams(-1, -1));
    }

    View view() { return root; }
    boolean active() { return previous != null; }

    void confirmFrame(java.util.concurrent.CompletableFuture<Boolean> result) {
        var observer = root.getViewTreeObserver();
        var listener = new android.view.ViewTreeObserver.OnDrawListener() {
            @Override public void onDraw() {
                // Window attributes have been submitted by traversal. Remove listeners after drawing.
                root.post(() -> {
                    if (active() && root.hasWindowFocus()) result.complete(true);
                    else result.completeExceptionally(new IllegalStateException("Black-screen window lost focus"));
                });
            }
        };
        observer.addOnDrawListener(listener);
        result.whenComplete((unused, error) -> root.post(() -> {
            if (observer.isAlive()) observer.removeOnDrawListener(listener);
        }));
        root.invalidate();
    }

    void enter() {
        if (active()) return;
        previous = new WindowManager.LayoutParams();
        previous.copyFrom(window.getAttributes());
        var insets = root.getRootWindowInsets();
        visibleBars = 0;
        for (int type : new int[] {WindowInsets.Type.statusBars(), WindowInsets.Type.navigationBars(),
                WindowInsets.Type.captionBar()}) {
            if (insets == null || insets.isVisible(type)) visibleBars |= type;
        }
        var controller = window.getInsetsController();
        barsBehavior = controller == null ? WindowInsetsController.BEHAVIOR_DEFAULT : controller.getSystemBarsBehavior();
        var attributes = window.getAttributes();
        attributes.flags |= WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        attributes.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF;
        attributes.buttonBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF;
        attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        window.setAttributes(attributes);
        panel.setVisibility(View.INVISIBLE);
        black.setVisibility(View.VISIBLE);
        hideBars();
    }

    void hideBars() {
        if (!active()) return;
        var controller = window.getInsetsController();
        if (controller != null) {
            controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            controller.hide(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
        }
    }

    void exit() {
        if (!active()) return;
        var attributes = window.getAttributes();
        // Restore only this mode's fields, retaining unrelated window changes.
        int keepOn = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        attributes.flags = (attributes.flags & ~keepOn) | (previous.flags & keepOn);
        attributes.screenBrightness = previous.screenBrightness;
        attributes.buttonBrightness = previous.buttonBrightness;
        attributes.layoutInDisplayCutoutMode = previous.layoutInDisplayCutoutMode;
        previous = null;
        volumeKey = KeyEvent.KEYCODE_UNKNOWN;
        window.setAttributes(attributes);
        var controller = window.getInsetsController();
        if (controller != null) {
            controller.setSystemBarsBehavior(barsBehavior);
            controller.hide(WindowInsets.Type.systemBars() & ~visibleBars);
            controller.show(visibleBars);
        }
        black.setVisibility(View.GONE);
        panel.setVisibility(View.VISIBLE);
    }

    boolean dispatchKeyEvent(KeyEvent event) {
        if (!active()) return false;
        int key = event.getKeyCode();
        if (key != KeyEvent.KEYCODE_VOLUME_UP && key != KeyEvent.KEYCODE_VOLUME_DOWN
                && key != KeyEvent.KEYCODE_VOLUME_MUTE) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            volumeKey = key;
        } else if (event.getAction() == KeyEvent.ACTION_UP && volumeKey == key) {
            volumeKey = KeyEvent.KEYCODE_UNKNOWN;
            // Keep both halves of the key press in this window, without adjusting volume.
            if (!event.isCanceled()) exit();
        }
        return true;
    }
}
