package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

/** Hosts the taskbar as a fullscreen task inside its bounded desktop plane. */
public final class DesktopTaskbarActivity extends Activity {
    private static final String CLASS_NAME =
            BuildConfig.APPLICATION_ID + ".DesktopTaskbarActivity";
    static final ComponentName COMPONENT = new ComponentName(
            BuildConfig.APPLICATION_ID, CLASS_NAME);

    private FrameLayout mRoot;
    private View mTaskbar;
    private WindowManager mWindowManager;
    private TaskbarPanel mTaskbarPanel;
    private boolean mTaskbarPanelAdded;
    private int mTaskbarPanelHeight;
    private int mTaskbarPanelLayoutGeneration;
    private int mDisplayId = Display.INVALID_DISPLAY;
    private boolean mPresented = true;
    private boolean mEdgeHidden;
    private int mEdgeHeight = 1;

    static Intent createIntent(final int displayId) {
        return new Intent()
                .setComponent(COMPONENT)
                .setData(Uri.parse("magicdesk-desktop-taskbar:" + displayId))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                        | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION);
    }

    static boolean isTaskbarComponent(final ComponentName component) {
        return COMPONENT.equals(component);
    }

    static boolean isTaskbarTask(final TaskRepository.TaskEntry task) {
        if (task == null
                || !BuildConfig.APPLICATION_ID.equals(task.packageName)) {
            return false;
        }
        return isTaskbarComponentName(task.componentName)
                || isTaskbarComponentName(task.topActivityName);
    }

    static boolean isTaskbarComponentName(final String componentName) {
        return (BuildConfig.APPLICATION_ID + "/" + CLASS_NAME)
                        .equals(componentName)
                || (BuildConfig.APPLICATION_ID + "/.DesktopTaskbarActivity")
                        .equals(componentName);
    }

    IBinder activityToken() {
        return FrameworkActivityInputApi.requireActivityToken(this);
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);

        mRoot = new FrameLayout(this);
        mRoot.setBackgroundColor(Color.TRANSPARENT);
        mRoot.setImportantForAccessibility(
                View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        setContentView(mRoot);
        mWindowManager = getWindowManager();
        mRoot.addOnAttachStateChangeListener(
                new View.OnAttachStateChangeListener() {
                    @Override
                    public void onViewAttachedToWindow(final View view) {
                        applyPresentation();
                    }

                    @Override
                    public void onViewDetachedFromWindow(final View view) {
                        removeTaskbarPanel();
                    }
                });
        mDisplayId = getDisplay() == null
                ? Display.INVALID_DISPLAY : getDisplay().getDisplayId();
        DesktopTaskbarHost.registerActivity(mDisplayId, this);
    }

    @Override
    protected void onDestroy() {
        DesktopTaskbarHost.unregisterActivity(mDisplayId, this);
        detachTaskbar();
        removeTaskbarPanel();
        mWindowManager = null;
        mRoot = null;
        super.onDestroy();
    }

    void attachTaskbar(final View taskbar) {
        if (mRoot == null || taskbar == null) {
            applyPresentation();
            return;
        }
        if (mTaskbar == taskbar) {
            applyPresentation();
            return;
        }
        detachTaskbar();
        final ViewGroup parent = taskbar.getParent() instanceof ViewGroup
                ? (ViewGroup) taskbar.getParent() : null;
        if (parent != null) {
            parent.removeView(taskbar);
        }
        if (mTaskbarPanel == null) {
            mTaskbarPanel = new TaskbarPanel();
            mTaskbarPanel.setBackgroundColor(Color.TRANSPARENT);
            mTaskbarPanel.setImportantForAccessibility(
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        mTaskbar = taskbar;
        mTaskbarPanel.addView(taskbar, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        applyPresentation();
    }

    void detachTaskbar() {
        removeTaskbarPanel();
        if (mTaskbar != null) {
            final ViewGroup parent = mTaskbar.getParent() instanceof ViewGroup
                    ? (ViewGroup) mTaskbar.getParent() : null;
            if (parent != null) {
                parent.removeView(mTaskbar);
            }
        }
        mTaskbar = null;
        mTaskbarPanel = null;
    }

    void setPresentation(
            final boolean presented,
            final boolean edgeHidden,
            final int edgeHeight) {
        mPresented = presented;
        mEdgeHidden = edgeHidden;
        mEdgeHeight = Math.max(1, edgeHeight);
        applyPresentation();
    }

    private void applyPresentation() {
        if (mTaskbar != null) {
            mTaskbar.setAlpha(mPresented && !mEdgeHidden ? 1f : 0f);
            mTaskbar.setVisibility(mPresented ? View.VISIBLE : View.INVISIBLE);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        updateTaskbarPanel(resolvePanelHeight(
                mPresented, mEdgeHidden, mEdgeHeight));
    }

    static int resolvePanelHeight(
            final boolean presented,
            final boolean edgeHidden,
            final int edgeHeight) {
        if (!presented) {
            return 0;
        }
        return edgeHidden
                ? Math.max(1, edgeHeight)
                : WindowManager.LayoutParams.MATCH_PARENT;
    }

    private void updateTaskbarPanel(final int height) {
        if (height == 0) {
            removeTaskbarPanel();
            return;
        }
        if (mWindowManager == null
                || mRoot == null
                || mRoot.getWindowToken() == null
                || mTaskbarPanel == null
                || (mTaskbarPanelAdded
                        && mTaskbarPanelHeight == height)) {
            return;
        }
        final WindowManager.LayoutParams params = createPanelParams(height);
        if (mTaskbarPanelAdded) {
            mWindowManager.updateViewLayout(mTaskbarPanel, params);
        } else {
            mWindowManager.addView(mTaskbarPanel, params);
            mTaskbarPanelAdded = true;
        }
        mTaskbarPanelHeight = height;
        awaitPanelLayoutCommit(mTaskbarPanel);
    }

    private void awaitPanelLayoutCommit(final TaskbarPanel panel) {
        final int generation = ++mTaskbarPanelLayoutGeneration;
        panel.getViewTreeObserver().registerFrameCommitCallback(() ->
                panel.post(() -> {
                    if (generation != mTaskbarPanelLayoutGeneration
                            || !mTaskbarPanelAdded
                            || mTaskbarPanel != panel) {
                        return;
                    }
                    DesktopTaskbarHost.notifyPanelLayoutCommitted(
                            mDisplayId, this);
                }));
        panel.invalidate();
    }

    private WindowManager.LayoutParams createPanelParams(final int height) {
        final WindowManager.LayoutParams params =
                new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        height,
                        WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                        PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.LEFT | Gravity.BOTTOM;
        params.token = mRoot.getWindowToken();
        params.setTitle("MagicDesk taskbar panel");
        return params;
    }

    private void removeTaskbarPanel() {
        mTaskbarPanelLayoutGeneration++;
        if (mTaskbarPanelAdded && mTaskbarPanel != null
                && mWindowManager != null) {
            mWindowManager.removeViewImmediate(mTaskbarPanel);
        }
        mTaskbarPanelAdded = false;
        mTaskbarPanelHeight = 0;
    }

    private final class TaskbarPanel extends FrameLayout {
        private boolean mHiddenEdgeTouchSequence;

        TaskbarPanel() {
            super(DesktopTaskbarActivity.this);
        }

        @Override
        public boolean dispatchGenericMotionEvent(final MotionEvent event) {
            DesktopTaskbarHost.dispatchEdgeInput(mDisplayId, event);
            return super.dispatchGenericMotionEvent(event);
        }

        @Override
        public boolean dispatchTouchEvent(final MotionEvent event) {
            final int action = event.getActionMasked();
            boolean consumeHiddenSequence = mHiddenEdgeTouchSequence;
            if (action == MotionEvent.ACTION_DOWN && mEdgeHidden) {
                mHiddenEdgeTouchSequence = true;
                consumeHiddenSequence = true;
            }
            DesktopTaskbarHost.dispatchEdgeInput(mDisplayId, event);
            if (!consumeHiddenSequence) {
                return super.dispatchTouchEvent(event);
            }
            if (action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_CANCEL) {
                mHiddenEdgeTouchSequence = false;
            }
            return true;
        }
    }
}
