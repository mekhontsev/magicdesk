package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Owns the phone-side input barrier as a normal fullscreen application task. */
public final class DesktopSelfTestPhoneGuardActivity extends Activity {
    private static final String EXTRA_RUN_ID = "magicdesk_self_test_run_id";
    private static final Object STATE_LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static WeakReference<DesktopSelfTestPhoneGuardActivity> sActivity =
            new WeakReference<>(null);
    private static Operation sShowOperation;
    private static Operation sHideOperation;
    private static long sRequestedRunId;
    private static boolean sVisible;
    private static String sLastError = "";

    private long mRunId;
    private GuardView mGuardView;
    private boolean mAccepted;
    private boolean mReportedVisible;

    static boolean showAndWait(
            final Context context,
            final long runId,
            final long timeoutMillis) {
        if (context == null || runId <= 0L) {
            return false;
        }
        final Operation operation;
        synchronized (STATE_LOCK) {
            final DesktopSelfTestPhoneGuardActivity activity =
                    sActivity.get();
            if (sVisible && activity != null && activity.mRunId == runId) {
                return true;
            }
            sLastError = "";
            sRequestedRunId = runId;
            operation = new Operation(runId);
            sShowOperation = operation;
        }
        runOnMain(() -> launch(context.getApplicationContext(), runId));
        if (!operation.await(timeoutMillis)) {
            recordError("activity resume timed out");
            return false;
        }
        return operation.success;
    }

    static boolean hideAndWait(final long timeoutMillis) {
        final DesktopSelfTestPhoneGuardActivity activity;
        final Operation operation;
        synchronized (STATE_LOCK) {
            sRequestedRunId = 0L;
            activity = sActivity.get();
            if (activity == null) {
                sVisible = false;
                return true;
            }
            operation = new Operation(activity.mRunId);
            sHideOperation = operation;
        }
        runOnMain(activity::finishFromGuard);
        return operation.await(timeoutMillis) && operation.success;
    }

    static boolean isVisible() {
        synchronized (STATE_LOCK) {
            return sVisible && sActivity.get() != null;
        }
    }

    static String lastError() {
        synchronized (STATE_LOCK) {
            return sLastError;
        }
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mRunId = getIntent().getLongExtra(EXTRA_RUN_ID, 0L);
        if (mRunId <= 0L || mRunId != requestedRunId()) {
            recordError("stale phone guard launch");
            completeShow(mRunId, false);
            finishFromGuard();
            return;
        }
        mAccepted = true;
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        final WindowInsetsController insets = getWindow().getInsetsController();
        if (insets != null) {
            insets.setSystemBarsBehavior(
                    WindowInsetsController
                            .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            insets.hide(WindowInsets.Type.systemBars());
        }
        mGuardView = new GuardView(this, mRunId);
        setContentView(mGuardView);
        synchronized (STATE_LOCK) {
            sActivity = new WeakReference<>(this);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!mAccepted) {
            finishFromGuard();
            return;
        }
        synchronized (STATE_LOCK) {
            sActivity = new WeakReference<>(this);
            sVisible = true;
        }
        if (!mReportedVisible) {
            mReportedVisible = true;
            DesktopSelfTestPhoneInputGuard.noteWindowShown();
        }
        completeShow(mRunId, true);
    }

    @Override
    protected void onStop() {
        final boolean unexpected;
        synchronized (STATE_LOCK) {
            if (sActivity.get() == this) {
                unexpected = sVisible;
                sVisible = false;
            } else {
                unexpected = false;
            }
        }
        if (unexpected) {
            DesktopSelfTestPhoneInputGuard.noteWindowHidden();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        synchronized (STATE_LOCK) {
            if (sActivity.get() == this) {
                sActivity = new WeakReference<>(null);
                sVisible = false;
            }
            if (sRequestedRunId == mRunId) {
                sRequestedRunId = 0L;
            }
        }
        completeShow(mRunId, false);
        completeHide(mRunId, true);
        mGuardView = null;
        super.onDestroy();
    }

    @Override
    public boolean dispatchTouchEvent(final MotionEvent event) {
        if (mGuardView != null && !mGuardView.isCancelButtonEvent(event)) {
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

    private static void launch(final Context context, final long runId) {
        final Intent intent = new Intent(context,
                DesktopSelfTestPhoneGuardActivity.class)
                .setData(Uri.parse("magicdesk-self-test-guard:" + runId))
                .putExtra(EXTRA_RUN_ID, runId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TASK
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(Display.DEFAULT_DISPLAY);
        DesktopShellActivity.setLaunchWindowingMode(
                options, FrameworkTaskSnapshot.WINDOWING_MODE_FULLSCREEN);
        try {
            context.startActivity(intent, options.toBundle());
        } catch (RuntimeException error) {
            recordError(error.getClass().getSimpleName()
                    + (error.getMessage() == null
                            ? "" : ": " + error.getMessage()));
            completeShow(runId, false);
        }
    }

    private void finishFromGuard() {
        if (!isFinishing()) {
            finishAndRemoveTask();
            overridePendingTransition(0, 0);
        }
    }

    private static long requestedRunId() {
        synchronized (STATE_LOCK) {
            return sRequestedRunId;
        }
    }

    private static void completeShow(
            final long runId,
            final boolean success) {
        final Operation operation;
        synchronized (STATE_LOCK) {
            if (sShowOperation == null
                    || sShowOperation.runId != runId) {
                return;
            }
            operation = sShowOperation;
            sShowOperation = null;
        }
        if (operation != null) {
            operation.complete(success);
        }
    }

    private static void completeHide(
            final long runId,
            final boolean success) {
        final Operation operation;
        synchronized (STATE_LOCK) {
            if (sHideOperation == null
                    || sHideOperation.runId != runId) {
                return;
            }
            operation = sHideOperation;
            sHideOperation = null;
        }
        if (operation != null) {
            operation.complete(success);
        }
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
        private final long mRunId;
        private final TextView mMessage;
        private final Button mCancel;

        GuardView(final Context context, final long runId) {
            super(context);
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
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

            mMessage = new TextView(context);
            mMessage.setGravity(Gravity.CENTER);
            mMessage.setPadding(0, ui.dp(16), 0, 0);
            mMessage.setText(R.string.self_test_phone_guard_message);
            mMessage.setTextColor(DesktopUiFactory.COLOR_MUTED);
            mMessage.setTextSize(18);
            addView(mMessage, new LayoutParams(
                    LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

            mCancel = ui.actionButton(
                    R.string.self_test_phone_guard_cancel,
                    DesktopUiFactory.COLOR_RED);
            mCancel.setOnClickListener(ignored -> requestCancellation());
            final LayoutParams cancelParams = new LayoutParams(
                    ui.dp(200), ui.dp(52));
            cancelParams.setMargins(0, ui.dp(28), 0, 0);
            addView(mCancel, cancelParams);
        }

        boolean isCancelButtonEvent(final MotionEvent event) {
            if (event == null || mCancel.getVisibility() != VISIBLE) {
                return false;
            }
            return event.getX() >= mCancel.getLeft()
                    && event.getX() < mCancel.getRight()
                    && event.getY() >= mCancel.getTop()
                    && event.getY() < mCancel.getBottom();
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
        private final long runId;
        private final CountDownLatch mCompleted = new CountDownLatch(1);
        private volatile boolean success;

        Operation(final long runId) {
            this.runId = runId;
        }

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
