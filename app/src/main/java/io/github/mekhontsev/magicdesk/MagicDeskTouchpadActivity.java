package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;

/** Phone-side touch surface used when Nubia has no touchpad for the display. */
public final class MagicDeskTouchpadActivity extends Activity {
    private static final String TAG = "MagicDeskTouchpad";
    private static final String EXTRA_TARGET_DISPLAY_ID =
            "io.github.mekhontsev.magicdesk.extra.TOUCHPAD_DISPLAY_ID";
    private static final float BASE_POINTER_SCALE = 1.0f;
    private static final float POINTER_SPEED_STEP = 0.1f;
    private static final Object STATE_LOCK = new Object();
    private static WeakReference<MagicDeskTouchpadActivity> sVisibleActivity =
            new WeakReference<>(null);
    private static int sRequestedDisplayId = Display.INVALID_DISPLAY;

    private DisplayManager mDisplayManager;
    private DisplayManager.DisplayListener mDisplayListener;
    private int mTargetDisplayId = Display.INVALID_DISPLAY;
    private boolean mPrimaryButtonPressed;

    static void open(final Context context, final int displayId) {
        if (context == null || displayId <= Display.DEFAULT_DISPLAY) {
            return;
        }
        synchronized (STATE_LOCK) {
            sRequestedDisplayId = displayId;
        }
        final Intent intent = new Intent(
                context, MagicDeskTouchpadActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_TARGET_DISPLAY_ID, displayId);
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(Display.DEFAULT_DISPLAY);
        context.startActivity(intent, options.toBundle());
    }

    static boolean isRequested(final int displayId) {
        synchronized (STATE_LOCK) {
            return sRequestedDisplayId == displayId;
        }
    }

    static boolean isVisible(final int displayId) {
        synchronized (STATE_LOCK) {
            final MagicDeskTouchpadActivity activity = sVisibleActivity.get();
            return activity != null
                    && !activity.isFinishing()
                    && !activity.isDestroyed()
                    && activity.mTargetDisplayId == displayId;
        }
    }

    static boolean restoreIfRequested(
            final Context context,
            final int displayId) {
        if (!isRequested(displayId)) {
            return false;
        }
        if (!isVisible(displayId)) {
            open(context, displayId);
        }
        return true;
    }

    static void release(final int displayId) {
        final MagicDeskTouchpadActivity activity;
        synchronized (STATE_LOCK) {
            if (sRequestedDisplayId == displayId) {
                sRequestedDisplayId = Display.INVALID_DISPLAY;
            }
            activity = sVisibleActivity.get();
        }
        if (activity != null && activity.mTargetDisplayId == displayId) {
            activity.runOnUiThread(activity::finish);
        }
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mDisplayManager = getSystemService(DisplayManager.class);
        updateTargetDisplay(getIntent());
        setContentView(createContent());
    }

    @Override
    protected void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        updateTargetDisplay(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        synchronized (STATE_LOCK) {
            sVisibleActivity = new WeakReference<>(this);
        }
        registerDisplayListener();
        finishIfTargetUnavailable();
    }

    @Override
    protected void onStop() {
        releasePrimaryButton();
        synchronized (STATE_LOCK) {
            if (sVisibleActivity.get() == this) {
                sVisibleActivity.clear();
            }
        }
        unregisterDisplayListener();
        super.onStop();
    }

    private View createContent() {
        final DesktopUiFactory ui = new DesktopUiFactory(this);
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setMotionEventSplittingEnabled(true);
        root.setBackgroundColor(DesktopUiFactory.COLOR_BACKGROUND);

        final LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(ui.dp(18), ui.dp(8), ui.dp(8), ui.dp(8));

        final TextView title = new TextView(this);
        title.setText(R.string.touchpad_title);
        title.setTextColor(DesktopUiFactory.COLOR_TEXT);
        title.setTextSize(20);
        header.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        final ImageButton keyboard = new ImageButton(this);
        keyboard.setImageResource(R.drawable.ic_keyboard);
        keyboard.setColorFilter(DesktopUiFactory.COLOR_TEXT);
        keyboard.setBackgroundColor(Color.TRANSPARENT);
        keyboard.setContentDescription(
                getString(R.string.touchpad_show_keyboard));
        keyboard.setTooltipText(
                getString(R.string.touchpad_show_keyboard));
        keyboard.setOnClickListener(view -> {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            MagicDeskRuntimeService.requestDesktopKeyboardIfRunning(
                    mTargetDisplayId);
        });
        header.addView(keyboard, new LinearLayout.LayoutParams(
                ui.dp(48), ui.dp(48)));

        final ImageButton close = new ImageButton(this);
        close.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        close.setColorFilter(DesktopUiFactory.COLOR_TEXT);
        close.setBackgroundColor(Color.TRANSPARENT);
        close.setContentDescription(getString(R.string.action_close));
        close.setTooltipText(getString(R.string.action_close));
        close.setOnClickListener(view -> dismissFromUser());
        header.addView(close, new LinearLayout.LayoutParams(
                ui.dp(48), ui.dp(48)));
        root.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final TouchSurface touchSurface = new TouchSurface(this);
        touchSurface.setBackground(ui.rounded(
                DesktopUiFactory.COLOR_PANEL,
                ui.dp(8),
                DesktopUiFactory.COLOR_PANEL_ALT));
        final LinearLayout.LayoutParams surfaceParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        0,
                        1);
        surfaceParams.setMargins(
                ui.dp(12), 0, ui.dp(12), ui.dp(12));
        root.addView(touchSurface, surfaceParams);
        return root;
    }

    private void releasePrimaryButton() {
        if (!mPrimaryButtonPressed) {
            return;
        }
        mPrimaryButtonPressed = false;
        MagicDeskRuntimeService.setDesktopPrimaryButtonPressedIfRunning(
                mTargetDisplayId, false);
    }

    private void updateTargetDisplay(final Intent intent) {
        mTargetDisplayId = intent == null
                ? Display.INVALID_DISPLAY
                : intent.getIntExtra(
                        EXTRA_TARGET_DISPLAY_ID,
                        Display.INVALID_DISPLAY);
        finishIfTargetUnavailable();
    }

    private void registerDisplayListener() {
        if (mDisplayManager == null || mDisplayListener != null) {
            return;
        }
        mDisplayListener = new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(final int displayId) {
            }

            @Override
            public void onDisplayRemoved(final int displayId) {
                if (displayId == mTargetDisplayId) {
                    finish();
                }
            }

            @Override
            public void onDisplayChanged(final int displayId) {
                if (displayId == mTargetDisplayId) {
                    finishIfTargetUnavailable();
                }
            }
        };
        mDisplayManager.registerDisplayListener(mDisplayListener, null);
    }

    private void unregisterDisplayListener() {
        if (mDisplayManager != null && mDisplayListener != null) {
            mDisplayManager.unregisterDisplayListener(mDisplayListener);
        }
        mDisplayListener = null;
    }

    private void finishIfTargetUnavailable() {
        if (mTargetDisplayId <= Display.DEFAULT_DISPLAY
                || mDisplayManager == null
                || mDisplayManager.getDisplay(mTargetDisplayId) == null) {
            clearRequestedDisplay();
            finish();
        }
    }

    private void dismissFromUser() {
        clearRequestedDisplay();
        finish();
    }

    private void clearRequestedDisplay() {
        synchronized (STATE_LOCK) {
            if (sRequestedDisplayId == mTargetDisplayId) {
                sRequestedDisplayId = Display.INVALID_DISPLAY;
            }
        }
    }

    private final class TouchSurface extends View {
        private final GestureDetector mGestureDetector;
        private final float mTouchSlop;
        private float mLastX;
        private float mLastY;
        private float mTravel;
        private float mPendingScroll;
        private float mPendingDragX;
        private float mPendingDragY;
        private boolean mUsedTwoFingers;
        private boolean mScrolling;
        private boolean mLongPressHandled;
        private boolean mInputResultLogged;

        TouchSurface(final Context context) {
            super(context);
            mTouchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
            mGestureDetector = new GestureDetector(
                    context,
                    new GestureDetector.SimpleOnGestureListener() {
                        @Override
                        public boolean onDown(final MotionEvent event) {
                            return true;
                        }

                        @Override
                        public void onLongPress(final MotionEvent event) {
                            if (!mUsedTwoFingers && !mScrolling) {
                                mLongPressHandled = true;
                                mPendingDragX = 0.0f;
                                mPendingDragY = 0.0f;
                                performHapticFeedback(
                                        HapticFeedbackConstants.CONFIRM);
                            }
                        }
                    });
            setClickable(true);
        }

        @Override
        public boolean onTouchEvent(final MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    mLastX = event.getX();
                    mLastY = event.getY();
                    mTravel = 0.0f;
                    mPendingScroll = 0.0f;
                    mPendingDragX = 0.0f;
                    mPendingDragY = 0.0f;
                    mUsedTwoFingers = false;
                    mScrolling = false;
                    mLongPressHandled = false;
                    mGestureDetector.onTouchEvent(event);
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    if (event.getPointerCount() >= 2) {
                        mUsedTwoFingers = true;
                        mLastX = averageX(event);
                        mLastY = averageY(event);
                    }
                    mGestureDetector.onTouchEvent(event);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    handleMove(event);
                    mGestureDetector.onTouchEvent(event);
                    return true;
                case MotionEvent.ACTION_POINTER_UP:
                    mGestureDetector.onTouchEvent(event);
                    return true;
                case MotionEvent.ACTION_UP:
                    mGestureDetector.onTouchEvent(event);
                    if (mPrimaryButtonPressed) {
                        releasePrimaryButton();
                    } else if (mUsedTwoFingers) {
                        if (!mScrolling) {
                            click(MotionEvent.BUTTON_SECONDARY);
                        }
                    } else if (mLongPressHandled) {
                        click(MotionEvent.BUTTON_SECONDARY);
                    } else if (!mLongPressHandled && mTravel <= mTouchSlop) {
                        performClick();
                    }
                    resetGesture();
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    mGestureDetector.onTouchEvent(event);
                    releasePrimaryButton();
                    resetGesture();
                    return true;
                default:
                    return super.onTouchEvent(event);
            }
        }

        @Override
        public boolean performClick() {
            super.performClick();
            click(MotionEvent.BUTTON_PRIMARY);
            return true;
        }

        private void handleMove(final MotionEvent event) {
            if (mUsedTwoFingers) {
                if (event.getPointerCount() < 2) {
                    return;
                }
                final float currentX = averageX(event);
                final float currentY = averageY(event);
                final float deltaX = currentX - mLastX;
                final float deltaY = currentY - mLastY;
                mTravel += (float) Math.hypot(deltaX, deltaY);
                mPendingScroll -= deltaY;
                if (mScrolling || mTravel > mTouchSlop) {
                    mScrolling = true;
                    final float scrollStep = Math.max(1.0f, mTouchSlop * 2.0f);
                    reportInputResult(
                            "scroll",
                            MagicDeskRuntimeService
                                    .scrollDesktopPointerIfRunning(
                                            mTargetDisplayId,
                                            mPendingScroll / scrollStep));
                    mPendingScroll = 0.0f;
                }
                mLastX = currentX;
                mLastY = currentY;
                return;
            }
            final float currentX = event.getX();
            final float currentY = event.getY();
            final float deltaX = currentX - mLastX;
            final float deltaY = currentY - mLastY;
            mTravel += (float) Math.hypot(deltaX, deltaY);
            mLastX = currentX;
            mLastY = currentY;
            float moveX = deltaX;
            float moveY = deltaY;
            if (mLongPressHandled && !mPrimaryButtonPressed) {
                mPendingDragX += deltaX;
                mPendingDragY += deltaY;
                if (Math.hypot(mPendingDragX, mPendingDragY)
                        <= mTouchSlop) {
                    return;
                }
                mPrimaryButtonPressed = MagicDeskRuntimeService
                        .setDesktopPrimaryButtonPressedIfRunning(
                                mTargetDisplayId, true);
                reportInputResult("drag", mPrimaryButtonPressed);
                moveX = mPendingDragX;
                moveY = mPendingDragY;
                mPendingDragX = 0.0f;
                mPendingDragY = 0.0f;
            }
            final float scale = pointerScale();
            reportInputResult(
                    "move",
                    MagicDeskRuntimeService.moveDesktopPointerIfRunning(
                            mTargetDisplayId,
                            moveX * scale,
                            moveY * scale));
        }

        private void click(final int button) {
            performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            reportInputResult(
                    "click",
                    MagicDeskRuntimeService.clickDesktopPointerIfRunning(
                            mTargetDisplayId, button));
        }

        private void reportInputResult(
                final String operation,
                final boolean accepted) {
            if (mInputResultLogged) {
                return;
            }
            mInputResultLogged = true;
            Log.i(TAG, "pointer input operation=" + operation
                    + " accepted=" + accepted
                    + " display=" + mTargetDisplayId);
        }

        private void resetGesture() {
            mUsedTwoFingers = false;
            mScrolling = false;
            mLongPressHandled = false;
            mTravel = 0.0f;
            mPendingScroll = 0.0f;
            mPendingDragX = 0.0f;
            mPendingDragY = 0.0f;
        }

        private float pointerScale() {
            final int speed = Settings.System.getInt(
                    getContentResolver(), "pointer_speed", 0);
            return Math.max(
                    0.3f,
                    BASE_POINTER_SCALE + speed * POINTER_SPEED_STEP);
        }

        private float averageX(final MotionEvent event) {
            float result = 0.0f;
            for (int index = 0; index < event.getPointerCount(); index++) {
                result += event.getX(index);
            }
            return result / event.getPointerCount();
        }

        private float averageY(final MotionEvent event) {
            float result = 0.0f;
            for (int index = 0; index < event.getPointerCount(); index++) {
                result += event.getY(index);
            }
            return result / event.getPointerCount();
        }
    }
}
