package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Owns the phone-side input barrier without participating in task lifecycle. */
final class DesktopSelfTestPhoneGuardWindow {
    private static final Object STATE_LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static WindowManager sWindowManager;
    // Process-level overlay ownership; hideOnMain clears this reference.
    @SuppressLint("StaticFieldLeak")
    private static GuardView sView;
    private static String sLastError = "";

    private DesktopSelfTestPhoneGuardWindow() {
    }

    static boolean showAndWait(
            final Context context,
            final long runId,
            final long timeoutMillis) {
        if (context == null || runId <= 0L) {
            return false;
        }
        synchronized (STATE_LOCK) {
            if (isVisibleLocked()) {
                return sView.runId() == runId;
            }
        }
        final Operation operation = new Operation();
        runOnMain(() -> showOnMain(
                context.getApplicationContext(), runId, operation));
        if (!operation.await(timeoutMillis)) {
            synchronized (STATE_LOCK) {
                sLastError = "window attachment timed out";
            }
            return false;
        }
        return operation.success;
    }

    static boolean hideAndWait(final long timeoutMillis) {
        final Operation operation = new Operation();
        runOnMain(() -> hideOnMain(operation));
        return operation.await(timeoutMillis) && operation.success;
    }

    static boolean isVisible() {
        synchronized (STATE_LOCK) {
            return isVisibleLocked();
        }
    }

    static String lastError() {
        synchronized (STATE_LOCK) {
            return sLastError;
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private static void showOnMain(
            final Context applicationContext,
            final long runId,
            final Operation operation) {
        synchronized (STATE_LOCK) {
            sLastError = "";
            if (isVisibleLocked()) {
                operation.complete(true);
                return;
            }
        }
        try {
            final DisplayManager displayManager = applicationContext
                    .getSystemService(DisplayManager.class);
            final Display display = displayManager == null
                    ? null : displayManager.getDisplay(Display.DEFAULT_DISPLAY);
            if (display == null) {
                recordError("phone display is unavailable");
                operation.complete(false);
                return;
            }
            final Context windowContext = applicationContext
                    .createDisplayContext(display)
                    .createWindowContext(
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                            null);
            final WindowManager windowManager =
                    windowContext.getSystemService(WindowManager.class);
            if (windowManager == null) {
                recordError("phone WindowManager is unavailable");
                operation.complete(false);
                return;
            }
            final GuardView view = new GuardView(
                    windowContext, runId, operation);
            final WindowManager.LayoutParams params =
                    new WindowManager.LayoutParams(
                            WindowManager.LayoutParams.MATCH_PARENT,
                            WindowManager.LayoutParams.MATCH_PARENT,
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                    | WindowManager.LayoutParams
                                            .FLAG_LAYOUT_NO_LIMITS
                                    | WindowManager.LayoutParams
                                            .FLAG_ALT_FOCUSABLE_IM
                                    | WindowManager.LayoutParams
                                            .FLAG_KEEP_SCREEN_ON,
                            PixelFormat.OPAQUE);
            params.gravity = Gravity.TOP | Gravity.START;
            params.setFitInsetsTypes(0);
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams
                    .LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            params.setTitle("MagicDesk self-test phone guard");
            synchronized (STATE_LOCK) {
                sWindowManager = windowManager;
                sView = view;
            }
            windowManager.addView(view, params);
        } catch (RuntimeException error) {
            synchronized (STATE_LOCK) {
                sWindowManager = null;
                sView = null;
            }
            recordError(error.getClass().getSimpleName()
                    + (error.getMessage() == null
                            ? "" : ": " + error.getMessage()));
            operation.complete(false);
        }
    }

    private static void hideOnMain(final Operation operation) {
        final WindowManager windowManager;
        final GuardView view;
        synchronized (STATE_LOCK) {
            windowManager = sWindowManager;
            view = sView;
            sWindowManager = null;
            sView = null;
        }
        if (windowManager == null || view == null) {
            operation.complete(true);
            return;
        }
        try {
            windowManager.removeViewImmediate(view);
            operation.complete(true);
        } catch (RuntimeException error) {
            operation.complete(false);
        }
    }

    private static boolean isVisibleLocked() {
        return sWindowManager != null && sView != null
                && sView.isAttachedToWindow();
    }

    private static void recordError(final String error) {
        synchronized (STATE_LOCK) {
            sLastError = error == null ? "" : error;
        }
    }

    private static void runOnMain(final Runnable operation) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            operation.run();
        } else {
            MAIN.post(operation);
        }
    }

    private static final class GuardView extends LinearLayout {
        private final Operation mAttached;
        private final long mRunId;
        private final TextView mMessage;
        private final Button mCancel;

        GuardView(
                final Context context,
                final long runId,
                final Operation attached) {
            super(context);
            mAttached = attached;
            mRunId = runId;
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER);
            final DesktopUiFactory ui = new DesktopUiFactory(context);
            setPadding(ui.dp(32), ui.dp(32), ui.dp(32), ui.dp(32));
            setBackgroundColor(Color.rgb(9, 13, 20));
            setFocusable(true);
            setFocusableInTouchMode(true);

            final TextView title = new TextView(context);
            title.setGravity(Gravity.CENTER);
            title.setText(R.string.app_name);
            title.setTextColor(DesktopUiFactory.COLOR_TEXT);
            title.setTextSize(24);
            addView(title, new LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT));

            mMessage = new TextView(context);
            mMessage.setGravity(Gravity.CENTER);
            mMessage.setPadding(0, ui.dp(16), 0, 0);
            mMessage.setText(R.string.self_test_phone_guard_message);
            mMessage.setTextColor(DesktopUiFactory.COLOR_MUTED);
            mMessage.setTextSize(18);
            addView(mMessage, new LayoutParams(
                    LayoutParams.MATCH_PARENT,
                    LayoutParams.WRAP_CONTENT));

            mCancel = ui.actionButton(
                    R.string.self_test_phone_guard_cancel,
                    DesktopUiFactory.COLOR_RED);
            mCancel.setOnClickListener(ignored -> requestCancellation());
            final LayoutParams cancelParams = new LayoutParams(
                    ui.dp(200), ui.dp(52));
            cancelParams.setMargins(0, ui.dp(28), 0, 0);
            addView(mCancel, cancelParams);
        }

        @Override
        public boolean dispatchTouchEvent(final MotionEvent event) {
            if (!isCancelButtonEvent(event)) {
                DesktopSelfTestPhoneInputGuard.recordTouch(event);
            }
            super.dispatchTouchEvent(event);
            return true;
        }

        @Override
        public boolean dispatchKeyEvent(final KeyEvent event) {
            DesktopSelfTestPhoneInputGuard.recordKey(event);
            return true;
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            DesktopSelfTestPhoneInputGuard.noteWindowShown();
            mAttached.complete(true);
        }

        @Override
        protected void onDetachedFromWindow() {
            DesktopSelfTestPhoneInputGuard.noteWindowHidden();
            super.onDetachedFromWindow();
        }

        private boolean isCancelButtonEvent(final MotionEvent event) {
            if (event == null || mCancel.getVisibility() != VISIBLE) {
                return false;
            }
            return event.getX() >= mCancel.getLeft()
                    && event.getX() < mCancel.getRight()
                    && event.getY() >= mCancel.getTop()
                    && event.getY() < mCancel.getBottom();
        }

        private long runId() {
            return mRunId;
        }

        private void requestCancellation() {
            final DesktopSelfTestRunState.CancellationStatus status =
                    DesktopSelfTestRunState.requestCancellation(mRunId);
            if (status != DesktopSelfTestRunState.CancellationStatus.ACCEPTED
                    && status != DesktopSelfTestRunState.CancellationStatus
                            .ALREADY_REQUESTED) {
                return;
            }
            mCancel.setEnabled(false);
            mCancel.setText(R.string.self_test_phone_guard_stopping);
            mMessage.setText(R.string.self_test_phone_guard_stopping_message);
        }
    }

    private static final class Operation {
        private final CountDownLatch mCompleted = new CountDownLatch(1);
        private volatile boolean success;

        synchronized void complete(final boolean value) {
            if (mCompleted.getCount() == 0L) {
                return;
            }
            success = value;
            mCompleted.countDown();
        }

        boolean await(final long timeoutMillis) {
            try {
                return mCompleted.await(timeoutMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
