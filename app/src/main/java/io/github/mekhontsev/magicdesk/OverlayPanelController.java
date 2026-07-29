package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

final class OverlayPanelController {
    private static final String TAG = "MagicDeskPanels";

    private final Context mApplicationContext;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final WindowManager mWindowManager;
    private final Rect mBounds = new Rect();
    private final Rect mPersistentBounds = new Rect();

    private View mVisiblePanel;
    private View mPersistentView;
    private View mTransientView;
    private boolean mAdded;
    private boolean mPersistentAdded;
    private boolean mTransientAdded;
    private final Runnable mTransientTimeout = this::hideTransient;

    OverlayPanelController(final Context context, final int displayId) {
        mApplicationContext = context.getApplicationContext();
        final DisplayManager displayManager = mApplicationContext.getSystemService(
                DisplayManager.class);
        final Display display = displayManager == null
                ? null : displayManager.getDisplay(displayId);
        if (display == null) {
            Log.w(TAG, "display not found: " + displayId);
            CompatibilityDiagnostics.record(
                    "OVERLAY-002",
                    "Cannot create desktop overlays",
                    "Display not found: " + displayId);
            mWindowManager = null;
            return;
        }
        Context windowContext = mApplicationContext.createDisplayContext(display);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowContext = windowContext.createWindowContext(
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        }
        mWindowManager = windowContext.getSystemService(WindowManager.class);
    }

    boolean show(final View panel, final int left, final int top,
            final int width, final int height, final boolean focusable,
            final String title) {
        if (panel == null || mWindowManager == null
                || !Settings.canDrawOverlays(mApplicationContext)) {
            return false;
        }
        hideAll();

        int flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        if (!focusable) {
            flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        }
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                width,
                height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = left;
        params.y = top;
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN;
        params.setTitle(title);

        panel.setVisibility(View.VISIBLE);
        panel.setOnTouchListener((target, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE
                    && target == mVisiblePanel
                    && !mPersistentBounds.contains(
                            Math.round(event.getRawX()), Math.round(event.getRawY()))) {
                hideAll();
            }
            return false;
        });
        try {
            mWindowManager.addView(panel, params);
            mVisiblePanel = panel;
            mAdded = true;
            mBounds.set(left, top, left + width, top + height);
            panel.postOnAnimation(() -> {
                if (!mAdded || mVisiblePanel != panel) {
                    return;
                }
                panel.invalidate();
                try {
                    mWindowManager.updateViewLayout(panel, params);
                } catch (RuntimeException e) {
                    Log.w(TAG, "failed to request panel frame " + title, e);
                    CompatibilityDiagnostics.record(
                            "OVERLAY-003",
                            "A desktop panel could not be redrawn",
                            "panel=" + title,
                            e);
                }
            });
            return true;
        } catch (RuntimeException e) {
            panel.setVisibility(View.GONE);
            mVisiblePanel = null;
            mAdded = false;
            mBounds.setEmpty();
            Log.w(TAG, "failed to show panel " + title, e);
            CompatibilityDiagnostics.record(
                    "OVERLAY-004",
                    "A desktop panel could not be shown",
                    "panel=" + title,
                    e);
            return false;
        }
    }

    boolean attachPersistent(final View view, final int left, final int top,
            final int width, final int height, final String title) {
        if (view == null || mWindowManager == null
                || !Settings.canDrawOverlays(mApplicationContext)) {
            return false;
        }
        detachPersistent();

        final int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                width,
                height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = left;
        params.y = top;
        params.setTitle(title);

        view.setVisibility(View.VISIBLE);
        try {
            mWindowManager.addView(view, params);
            mPersistentView = view;
            mPersistentAdded = true;
            mPersistentBounds.set(left, top, left + width, top + height);
            return true;
        } catch (RuntimeException e) {
            view.setVisibility(View.GONE);
            mPersistentView = null;
            mPersistentAdded = false;
            mPersistentBounds.setEmpty();
            Log.w(TAG, "failed to attach persistent overlay " + title, e);
            CompatibilityDiagnostics.record(
                    "OVERLAY-005",
                    "The MagicDesk taskbar could not be attached",
                    "overlay=" + title,
                    e);
            return false;
        }
    }

    boolean showTransient(final View view, final int left, final int top,
            final int width, final int height, final long durationMillis,
            final String title) {
        if (view == null || mWindowManager == null
                || !Settings.canDrawOverlays(mApplicationContext)) {
            return false;
        }
        hideTransient();

        final int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                width,
                height,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = left;
        params.y = top;
        params.setTitle(title);

        view.setVisibility(View.VISIBLE);
        try {
            mWindowManager.addView(view, params);
            mTransientView = view;
            mTransientAdded = true;
            if (durationMillis > 0) {
                mMainHandler.postDelayed(mTransientTimeout, durationMillis);
            }
            return true;
        } catch (RuntimeException e) {
            view.setVisibility(View.GONE);
            mTransientView = null;
            mTransientAdded = false;
            Log.w(TAG, "failed to show transient overlay " + title, e);
            CompatibilityDiagnostics.record(
                    "OVERLAY-006",
                    "A transient desktop message could not be shown",
                    "overlay=" + title,
                    e);
            return false;
        }
    }

    void hideTransient() {
        mMainHandler.removeCallbacks(mTransientTimeout);
        final View view = mTransientView;
        if (mTransientAdded && view != null && mWindowManager != null) {
            try {
                mWindowManager.removeViewImmediate(view);
            } catch (RuntimeException e) {
                Log.w(TAG, "failed to remove transient overlay", e);
            }
        }
        if (view != null) {
            view.setVisibility(View.GONE);
        }
        mTransientView = null;
        mTransientAdded = false;
    }

    void setPersistentVisible(final boolean visible) {
        if (mPersistentAdded && mPersistentView != null) {
            mPersistentView.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    void updatePersistentBounds(
            final int left,
            final int top,
            final int width,
            final int height) {
        if (!mPersistentAdded || mPersistentView == null
                || mWindowManager == null) {
            return;
        }
        final WindowManager.LayoutParams params =
                (WindowManager.LayoutParams) mPersistentView.getLayoutParams();
        params.x = left;
        params.y = top;
        params.width = width;
        params.height = height;
        try {
            mWindowManager.updateViewLayout(mPersistentView, params);
            mPersistentBounds.set(
                    left, top, left + width, top + height);
        } catch (RuntimeException e) {
            Log.w(TAG, "failed to update persistent overlay bounds", e);
            CompatibilityDiagnostics.record(
                    "OVERLAY-007",
                    "The MagicDesk taskbar could not be repositioned",
                    "bounds=" + left + "," + top + " "
                            + width + "x" + height,
                    e);
        }
    }

    void hide(final View panel) {
        if (panel != null && panel == mVisiblePanel) {
            hideAll();
        }
    }

    void hideAll() {
        hideTransient();
        final View panel = mVisiblePanel;
        if (mAdded && panel != null && mWindowManager != null) {
            try {
                mWindowManager.removeViewImmediate(panel);
            } catch (RuntimeException e) {
                Log.w(TAG, "failed to remove panel", e);
            }
        }
        if (panel != null) {
            panel.setVisibility(View.GONE);
        }
        mVisiblePanel = null;
        mAdded = false;
        mBounds.setEmpty();
    }

    void release() {
        hideAll();
        detachPersistent();
    }

    private void detachPersistent() {
        final View view = mPersistentView;
        if (mPersistentAdded && view != null && mWindowManager != null) {
            try {
                mWindowManager.removeViewImmediate(view);
            } catch (RuntimeException e) {
                Log.w(TAG, "failed to remove persistent overlay", e);
            }
        }
        if (view != null) {
            view.setVisibility(View.GONE);
        }
        mPersistentView = null;
        mPersistentAdded = false;
        mPersistentBounds.setEmpty();
    }

    boolean hasVisiblePanel() {
        return mAdded && mVisiblePanel != null;
    }

    boolean isVisible(final View panel) {
        return panel != null && panel == mVisiblePanel && mAdded;
    }

    boolean contains(final float x, final float y) {
        return hasVisiblePanel() && mBounds.contains(Math.round(x), Math.round(y));
    }
}
