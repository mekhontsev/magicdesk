package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Insets;
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
import android.view.WindowInsets;
import android.view.WindowInsetsAnimation;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.lang.ref.WeakReference;
import java.util.List;

/** Phone-side touch surface for every external MagicDesk display. */
public final class MagicDeskTouchpadActivity extends Activity {
    private static final String TAG = "MagicDeskTouchpad";
    private static final int WINDOWING_MODE_FULLSCREEN = 1;
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
    private boolean mPointerDragActive;
    private MirrorInputEditText mMirrorInput;
    private FrameLayout mContentContainer;
    private ImageButton mHelpButton;
    private ScrollView mHelpView;
    private OnBackInvokedCallback mBackCallback;

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
        DesktopShellActivity.setLaunchWindowingMode(
                options, WINDOWING_MODE_FULLSCREEN);
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

    static boolean restoreObservedMissing(
            final Context context,
            final int displayId) {
        if (!isRequested(displayId)) {
            return false;
        }
        open(context, displayId);
        return true;
    }

    static boolean bringRequestedTaskToFront(
            final Context context,
            final int displayId) {
        if (context == null || !isRequested(displayId)) {
            return false;
        }
        final ActivityManager activityManager =
                context.getSystemService(ActivityManager.class);
        if (activityManager == null) {
            return false;
        }
        final ComponentName expected = new ComponentName(
                context, MagicDeskTouchpadActivity.class);
        for (final ActivityManager.AppTask appTask
                : activityManager.getAppTasks()) {
            final ActivityManager.RecentTaskInfo taskInfo =
                    appTask.getTaskInfo();
            if (!expected.equals(taskInfo.topActivity)) {
                continue;
            }
            appTask.moveToFront();
            return true;
        }
        return false;
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
        mBackCallback = this::handleBack;
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                mBackCallback);
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
        recordAutomationVisibility(true);
        DesktopSelfTestPhoneUiObserver.noteTouchpadStarted(mTargetDisplayId);
        registerDisplayListener();
        finishIfTargetUnavailable();
    }

    @Override
    public void onWindowFocusChanged(final boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) {
            return;
        }
        if (hasTextInputProxy()) {
            showKeyboardIfReady();
        }
    }

    @Override
    protected void onStop() {
        finishPointerDrag();
        DesktopSelfTestPhoneUiObserver.noteTouchpadStopped(mTargetDisplayId);
        synchronized (STATE_LOCK) {
            if (sVisibleActivity.get() == this) {
                sVisibleActivity.clear();
            }
        }
        recordAutomationVisibility(false);
        unregisterDisplayListener();
        super.onStop();
    }

    private void recordAutomationVisibility(final boolean visible) {
        recordAutomationVisibility(visible, mTargetDisplayId);
    }

    private void recordAutomationVisibility(
            final boolean visible,
            final int displayId) {
        try {
            DesktopAutomationEventJournal.record(
                    "ui",
                    visible ? "touchpad_shown" : "touchpad_hidden",
                    true,
                    "display=" + displayId,
                    new org.json.JSONObject()
                            .put("displayId", displayId)
                            .put("visible", visible));
        } catch (org.json.JSONException ignored) {
            DesktopAutomationEventJournal.record(
                    "ui",
                    visible ? "touchpad_shown" : "touchpad_hidden",
                    true,
                    "display=" + displayId);
        }
    }

    @Override
    protected void onDestroy() {
        hidePhoneKeyboard();
        clearTextInputProxy();
        if (mBackCallback != null) {
            getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                    mBackCallback);
            mBackCallback = null;
        }
        super.onDestroy();
    }

    private View createContent() {
        final DesktopUiFactory ui = new DesktopUiFactory(this);
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setMotionEventSplittingEnabled(true);
        root.setBackgroundColor(DesktopUiFactory.COLOR_BACKGROUND);
        root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            final Insets bars = windowInsets.getInsets(
                    WindowInsets.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return windowInsets;
        });
        root.setWindowInsetsAnimationCallback(
                new WindowInsetsAnimation.Callback(
                        WindowInsetsAnimation.Callback
                                .DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                    @Override
                    public WindowInsets onProgress(
                            final WindowInsets insets,
                            final List<WindowInsetsAnimation>
                                    runningAnimations) {
                        return insets;
                    }

                    @Override
                    public void onEnd(
                            final WindowInsetsAnimation animation) {
                        if ((animation.getTypeMask()
                                & WindowInsets.Type.ime()) != 0
                                && !isKeyboardVisible()
                                && hasTextInputProxy()) {
                            clearTextInputProxy();
                        }
                    }
                });

        final LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(ui.dp(18), ui.dp(8), ui.dp(8), ui.dp(8));

        final ImageButton close = new ImageButton(this);
        close.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        close.setColorFilter(DesktopUiFactory.COLOR_TEXT);
        close.setBackgroundColor(Color.TRANSPARENT);
        close.setContentDescription(getString(R.string.action_close));
        close.setTooltipText(getString(R.string.action_close));
        close.setOnClickListener(view -> dismissFromUser());
        header.addView(close, new LinearLayout.LayoutParams(
                ui.dp(48), ui.dp(48)));

        final TextView title = new TextView(this);
        title.setText(R.string.touchpad_title);
        title.setTextColor(DesktopUiFactory.COLOR_TEXT);
        title.setTextSize(20);
        header.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        final ImageButton desktop = new ImageButton(this);
        desktop.setImageResource(R.drawable.ic_home_workspace);
        desktop.setColorFilter(DesktopUiFactory.COLOR_TEXT);
        desktop.setBackgroundColor(Color.TRANSPARENT);
        desktop.setContentDescription(
                getString(R.string.touchpad_present_desktop_workspace));
        desktop.setTooltipText(
                getString(R.string.touchpad_present_desktop_workspace));
        desktop.setOnClickListener(view -> {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            presentDesktopWorkspace();
        });
        header.addView(desktop, new LinearLayout.LayoutParams(
                ui.dp(48), ui.dp(48)));

        mHelpButton = new ImageButton(this);
        mHelpButton.setImageResource(android.R.drawable.ic_menu_help);
        mHelpButton.setColorFilter(DesktopUiFactory.COLOR_TEXT);
        mHelpButton.setBackgroundColor(Color.TRANSPARENT);
        mHelpButton.setContentDescription(
                getString(R.string.touchpad_help));
        mHelpButton.setTooltipText(getString(R.string.touchpad_help));
        mHelpButton.setOnClickListener(view -> {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            toggleHelp();
        });
        header.addView(mHelpButton, new LinearLayout.LayoutParams(
                ui.dp(48), ui.dp(48)));

        final ImageButton keyboard = new ImageButton(this);
        keyboard.setImageResource(R.drawable.ic_keyboard);
        keyboard.setColorFilter(DesktopUiFactory.COLOR_TEXT);
        keyboard.setBackgroundColor(Color.TRANSPARENT);
        keyboard.setContentDescription(
                getString(R.string.touchpad_toggle_keyboard));
        keyboard.setTooltipText(
                getString(R.string.touchpad_toggle_keyboard));
        keyboard.setOnClickListener(view -> {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            hideHelp();
            togglePhoneKeyboard();
        });
        header.addView(keyboard, new LinearLayout.LayoutParams(
                ui.dp(48), ui.dp(48)));
        root.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        mMirrorInput = new MirrorInputEditText(
                this,
                (action, text, arg1, arg2, arg3) ->
                        MagicDeskRuntime
                                .updateDesktopTextInput(
                                        mTargetDisplayId,
                                        action,
                                        text,
                                        arg1,
                                        arg2,
                                        arg3));
        root.addView(mMirrorInput, new LinearLayout.LayoutParams(1, 1));

        final TouchSurface touchSurface = new TouchSurface(this);
        touchSurface.setBackground(ui.rounded(
                DesktopUiFactory.COLOR_PANEL,
                ui.dp(8),
                DesktopUiFactory.COLOR_PANEL_ALT));
        mContentContainer = new FrameLayout(this);
        final FrameLayout.LayoutParams surfaceParams =
                new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT);
        surfaceParams.setMargins(
                ui.dp(12), 0, ui.dp(12), ui.dp(12));
        mContentContainer.addView(touchSurface, surfaceParams);
        root.addView(mContentContainer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1));
        return root;
    }

    private void presentDesktopWorkspace() {
        final DesktopDisplayTarget target =
                DesktopRuntimeBridge.getDesktopTarget(mTargetDisplayId);
        if (target != null && target.displayId == mTargetDisplayId) {
            DesktopOperations.presentDesktopWorkspace(target, null);
        }
    }

    private void toggleHelp() {
        if (mHelpView != null) {
            hideHelp();
            return;
        }
        if (mContentContainer == null) {
            return;
        }
        hidePhoneKeyboard();
        final DesktopUiFactory ui = new DesktopUiFactory(this);
        mHelpView = TouchpadHelpContent.create(this, ui);
        final FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        params.setMargins(ui.dp(12), 0, ui.dp(12), ui.dp(12));
        mContentContainer.addView(mHelpView, params);
        mHelpButton.setColorFilter(DesktopUiFactory.COLOR_CYAN);
    }

    private void hideHelp() {
        if (mHelpView == null) {
            return;
        }
        if (mContentContainer != null) {
            mContentContainer.removeView(mHelpView);
        }
        mHelpView = null;
        if (mHelpButton != null) {
            mHelpButton.setColorFilter(DesktopUiFactory.COLOR_TEXT);
        }
    }

    private void handleBack() {
        if (mHelpView != null) {
            hideHelp();
            return;
        }
        dismissFromUser();
    }

    private void showPhoneKeyboard() {
        if (mMirrorInput == null) {
            return;
        }
        MagicDeskRuntime.endDesktopTextInput(
                mTargetDisplayId);
        final boolean inputCaptured = MagicDeskRuntime
                .beginDesktopTextInput(mTargetDisplayId);
        Log.i(TAG, "phone keyboard requested display=" + mTargetDisplayId
                + " inputCaptured=" + inputCaptured
                + " windowFocus=" + hasWindowFocus());
        if (!inputCaptured) {
            MagicDeskRuntime.clickDesktopPointer(
                    mTargetDisplayId,
                    MotionEvent.BUTTON_PRIMARY);
            return;
        }
        bringTaskToFront();
        mMirrorInput.setKeyboardRequested(true);
        if (!mMirrorInput.requestFocus()) {
            clearTextInputProxy();
            return;
        }
        final InputMethodManager inputMethodManager =
                getSystemService(InputMethodManager.class);
        if (inputMethodManager == null) {
            clearTextInputProxy();
            return;
        }
        inputMethodManager.restartInput(mMirrorInput);
        showKeyboardIfReady();
    }

    private void bringTaskToFront() {
        try {
            if (!bringRequestedTaskToFront(this, mTargetDisplayId)) {
                Log.w(TAG, "touchpad task is unavailable");
            }
        } catch (SecurityException error) {
            Log.w(TAG, "cannot focus touchpad task", error);
        }
    }

    private void showKeyboardIfReady() {
        if (!hasTextInputProxy()
                || !hasWindowFocus()) {
            return;
        }
        Log.i(TAG, "show keyboard display=" + mTargetDisplayId);
        getWindow().getInsetsController().show(WindowInsets.Type.ime());
    }

    private void hidePhoneKeyboard() {
        Log.i(TAG, "hide keyboard display=" + mTargetDisplayId);
        getWindow().getInsetsController().hide(WindowInsets.Type.ime());
        if (hasTextInputProxy()) {
            final InputMethodManager inputMethodManager =
                    getSystemService(InputMethodManager.class);
            if (inputMethodManager != null) {
                inputMethodManager.hideSoftInputFromWindow(
                        mMirrorInput.getWindowToken(), 0);
            }
        }
        clearTextInputProxy();
    }

    private void togglePhoneKeyboard() {
        if (isKeyboardVisible()) {
            hidePhoneKeyboard();
        } else {
            showPhoneKeyboard();
        }
    }

    private void clearTextInputProxy() {
        final boolean hadTextInputProxy = hasTextInputProxy();
        if (mMirrorInput != null) {
            mMirrorInput.setKeyboardRequested(false);
        }
        if (hadTextInputProxy) {
            MagicDeskRuntime.endDesktopTextInput(
                    mTargetDisplayId);
        }
    }

    private boolean hasTextInputProxy() {
        return mMirrorInput != null && mMirrorInput.hasFocus();
    }

    private boolean isKeyboardVisible() {
        final WindowInsets insets = getWindow().getDecorView()
                .getRootWindowInsets();
        return insets != null
                && insets.isVisible(WindowInsets.Type.ime());
    }

    private void finishPointerDrag() {
        if (!mPointerDragActive) {
            return;
        }
        mPointerDragActive = false;
        MagicDeskRuntime.setDesktopPointerButtonPressed(
                mTargetDisplayId,
                MotionEvent.BUTTON_PRIMARY,
                false);
    }

    private void updateTargetDisplay(final Intent intent) {
        final int targetDisplayId = intent == null
                ? Display.INVALID_DISPLAY
                : intent.getIntExtra(
                        EXTRA_TARGET_DISPLAY_ID,
                        Display.INVALID_DISPLAY);
        if (targetDisplayId == mTargetDisplayId) {
            finishIfTargetUnavailable();
            return;
        }

        final int previousDisplayId = mTargetDisplayId;
        final boolean visible;
        synchronized (STATE_LOCK) {
            visible = sVisibleActivity.get() == this;
        }
        if (visible && previousDisplayId > Display.DEFAULT_DISPLAY) {
            recordAutomationVisibility(false, previousDisplayId);
            DesktopSelfTestPhoneUiObserver.noteTouchpadStopped(
                    previousDisplayId);
        }

        finishPointerDrag();
        mTargetDisplayId = targetDisplayId;

        if (visible && targetDisplayId > Display.DEFAULT_DISPLAY) {
            recordAutomationVisibility(true, targetDisplayId);
            DesktopSelfTestPhoneUiObserver.noteTouchpadStarted(
                    targetDisplayId);
        }
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
        boolean cleared = false;
        synchronized (STATE_LOCK) {
            if (sRequestedDisplayId == mTargetDisplayId) {
                sRequestedDisplayId = Display.INVALID_DISPLAY;
                cleared = true;
            }
        }
        if (cleared) {
            MagicDeskRuntime.setPhoneTouchpadRequested(false);
        }
    }

    private final class TouchSurface extends View {
        private final GestureDetector mGestureDetector;
        private final TouchpadPointerMotion mPointerMotion =
                new TouchpadPointerMotion();
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
                        finishPointerDrag();
                        stopPointerMotion();
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
                    if (mPointerDragActive) {
                        finishPointerDrag();
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
                    finishPointerDrag();
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
                            MagicDeskRuntime
                                    .scrollDesktopPointer(
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
            if (mLongPressHandled && !mPointerDragActive) {
                mPendingDragX += deltaX;
                mPendingDragY += deltaY;
                if (Math.hypot(mPendingDragX, mPendingDragY)
                        <= mTouchSlop) {
                    return;
                }
                if (!startPointerMotion(event)) {
                    return;
                }
                mPointerDragActive = MagicDeskRuntime
                        .setDesktopPointerButtonPressed(
                                mTargetDisplayId,
                                MotionEvent.BUTTON_PRIMARY,
                                true);
                reportInputResult("drag", mPointerDragActive);
                mPendingDragX = 0.0f;
                mPendingDragY = 0.0f;
                if (!mPointerDragActive) {
                    stopPointerMotion();
                    return;
                }
                return;
            }
            if (!mPointerMotion.isActive()) {
                if (mTravel <= mTouchSlop
                        || !startPointerMotion(event)) {
                    return;
                }
                return;
            }
            if (!mPointerMotion.move(currentX, currentY)) {
                return;
            }
            final boolean accepted = MagicDeskRuntime.moveDesktopPointer(
                            mTargetDisplayId,
                            mPointerMotion.deltaX(),
                            mPointerMotion.deltaY());
            reportInputResult("move", accepted);
            if (!accepted) {
                stopPointerMotion();
            }
        }

        private boolean startPointerMotion(final MotionEvent event) {
            mPointerMotion.start(
                    event.getX(),
                    event.getY(),
                    pointerScale());
            return true;
        }

        private void click(final int button) {
            performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            reportInputResult(
                    "click",
                    MagicDeskRuntime.clickDesktopPointer(
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
            stopPointerMotion();
            mUsedTwoFingers = false;
            mScrolling = false;
            mLongPressHandled = false;
            mTravel = 0.0f;
            mPendingScroll = 0.0f;
            mPendingDragX = 0.0f;
            mPendingDragY = 0.0f;
        }

        private void stopPointerMotion() {
            mPointerMotion.stop();
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
