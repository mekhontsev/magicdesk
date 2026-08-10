package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimation;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;

import java.util.List;

/** Hosts the system IME on a secondary display while forwarding its input. */
final class DesktopImeOverlay {
    private static final String TAG = "MagicDeskDesktopIme";

    interface InsetsListener {
        void onImeInsetsChanged(boolean visible, int bottomInset);
    }

    private final Context mWindowContext;
    private final WindowManager mWindowManager;
    private MirrorInputEditText mInput;
    private InsetsListener mInsetsListener;
    private Runnable mOnDismissed;
    private boolean mAdded;
    private boolean mImeWasVisible;
    private boolean mPublishedImeVisible;
    private int mPublishedImeBottomInset;

    DesktopImeOverlay(final Context context, final int displayId) {
        final DisplayManager displayManager = context.getSystemService(
                DisplayManager.class);
        final Display display = displayManager == null
                ? null : displayManager.getDisplay(displayId);
        if (display == null) {
            mWindowContext = null;
            mWindowManager = null;
            return;
        }
        mWindowContext = context.getApplicationContext()
                .createDisplayContext(display)
                .createWindowContext(
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        null);
        mWindowManager = mWindowContext.getSystemService(WindowManager.class);
    }

    boolean show(
            final MirrorInputEditText.Dispatcher dispatcher,
            final InsetsListener insetsListener,
            final Runnable onDismissed) {
        if (mWindowContext == null || mWindowManager == null) {
            return false;
        }
        if (isRequested()) {
            return true;
        }
        hide(false);
        final MirrorInputEditText input = new MirrorInputEditText(
                mWindowContext, dispatcher);
        input.setKeyboardRequested(true);
        input.setOnApplyWindowInsetsListener((view, insets) -> {
            observeInsets(insets, true);
            return insets;
        });
        input.setWindowInsetsAnimationCallback(
                new WindowInsetsAnimation.Callback(
                        WindowInsetsAnimation.Callback
                                .DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                    @Override
                    public WindowInsets onProgress(
                            final WindowInsets insets,
                            final List<WindowInsetsAnimation> animations) {
                        observeInsets(insets, false);
                        return insets;
                    }

                    @Override
                    public void onEnd(
                            final WindowInsetsAnimation animation) {
                        if ((animation.getTypeMask()
                                & WindowInsets.Type.ime()) == 0) {
                            return;
                        }
                        input.post(() -> finishImeAnimation(input));
                    }
                });

        final int flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        // A display-sized host receives the IME's actual bottom inset.
        final WindowManager.LayoutParams params =
                new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                        flags,
                        PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.softInputMode =
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                        | WindowManager.LayoutParams
                                .SOFT_INPUT_STATE_ALWAYS_VISIBLE;
        params.setTitle("MagicDesk desktop keyboard");
        try {
            mWindowManager.addView(input, params);
            mInput = input;
            mInsetsListener = insetsListener;
            mOnDismissed = onDismissed;
            mAdded = true;
            mImeWasVisible = false;
            mPublishedImeVisible = false;
            mPublishedImeBottomInset = 0;
            if (!input.requestFocus()) {
                hide(false);
                return false;
            }
            final InputMethodManager manager = mWindowContext.getSystemService(
                    InputMethodManager.class);
            if (manager == null) {
                hide(false);
                return false;
            }
            manager.restartInput(input);
            input.post(() -> {
                if (!mAdded || mInput != input) {
                    return;
                }
                manager.showSoftInput(
                        input, InputMethodManager.SHOW_IMPLICIT);
                if (input.getWindowInsetsController() != null) {
                    input.getWindowInsetsController().show(
                            WindowInsets.Type.ime());
                }
            });
            return true;
        } catch (RuntimeException error) {
            Log.w(TAG, "could not show desktop keyboard target", error);
            hide(false);
            return false;
        }
    }

    boolean isRequested() {
        return mAdded && mInput != null;
    }

    void hide() {
        hide(true);
    }

    void release() {
        hide(true);
    }

    private void observeInsets(
            final WindowInsets insets,
            final boolean publish) {
        if (!mAdded || insets == null) {
            return;
        }
        if (insets.isVisible(WindowInsets.Type.ime())) {
            mImeWasVisible = true;
        }
        if (publish) {
            publishImeInsets(insets);
        }
    }

    private void finishImeAnimation(final MirrorInputEditText input) {
        if (!mAdded || mInput != input || !mImeWasVisible) {
            return;
        }
        final WindowInsets insets = input.getRootWindowInsets();
        if (insets == null) {
            return;
        }
        if (!insets.isVisible(WindowInsets.Type.ime())) {
            hide(true);
            return;
        }
        publishImeInsets(insets);
    }

    private void publishImeInsets(final WindowInsets insets) {
        if (!mAdded || insets == null) {
            return;
        }
        final boolean visible =
                insets.isVisible(WindowInsets.Type.ime());
        final int bottomInset = visible
                ? Math.max(0, insets.getInsets(
                        WindowInsets.Type.ime()).bottom)
                : 0;
        if (mPublishedImeVisible == visible
                && mPublishedImeBottomInset == bottomInset) {
            return;
        }
        mPublishedImeVisible = visible;
        mPublishedImeBottomInset = bottomInset;
        final InsetsListener listener = mInsetsListener;
        if (listener != null) {
            listener.onImeInsetsChanged(visible, bottomInset);
        }
    }

    private void hide(final boolean notify) {
        final MirrorInputEditText input = mInput;
        final InsetsListener insetsListener = mInsetsListener;
        final Runnable onDismissed = mOnDismissed;
        mInput = null;
        mInsetsListener = null;
        mOnDismissed = null;
        mImeWasVisible = false;
        mPublishedImeVisible = false;
        mPublishedImeBottomInset = 0;
        if (input != null) {
            if (input.getWindowInsetsController() != null) {
                input.getWindowInsetsController().hide(
                        WindowInsets.Type.ime());
            }
            final InputMethodManager manager = input.getContext()
                    .getSystemService(InputMethodManager.class);
            if (manager != null) {
                manager.hideSoftInputFromWindow(input.getWindowToken(), 0);
            }
            input.setKeyboardRequested(false);
        }
        if (mAdded && input != null && mWindowManager != null) {
            try {
                mWindowManager.removeViewImmediate(input);
            } catch (RuntimeException error) {
                Log.w(TAG, "could not remove desktop keyboard target", error);
            }
        }
        mAdded = false;
        if (insetsListener != null) {
            insetsListener.onImeInsetsChanged(false, 0);
        }
        if (notify && onDismissed != null) {
            onDismissed.run();
        }
    }
}
