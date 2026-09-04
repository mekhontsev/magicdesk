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

/** Supplies one standard application token for all persistent desktop chrome. */
public final class DesktopChromeActivity extends Activity {
    private static final String EXTRA_DISPLAY_ID =
            "magicdesk_chrome_display_id";
    private static final String CLASS_NAME =
            BuildConfig.APPLICATION_ID + ".DesktopChromeActivity";
    static final ComponentName COMPONENT = new ComponentName(
            BuildConfig.APPLICATION_ID, CLASS_NAME);

    private FrameLayout mRoot;
    private View mTaskbar;
    private WindowManager mWindowManager;
    private TaskbarPanel mTaskbarPanel;
    private View mSurfaceTraversalFence;
    private boolean mSurfaceTraversalFenceAdded;
    private boolean mTaskbarPanelAdded;
    private int mTaskbarPanelHeight;
    private int mTaskbarHeight = 1;
    private int mSurfaceHeight = 1;
    private int mDisplayId = Display.INVALID_DISPLAY;
    private boolean mPresented = true;
    private boolean mEdgeHidden;
    private int mEdgeHeight = 1;

    static Intent createIntent(final int displayId) {
        return new Intent()
                .setComponent(COMPONENT)
                .setData(Uri.parse("magicdesk-desktop-chrome:" + displayId))
                .putExtra(EXTRA_DISPLAY_ID, displayId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                        | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION);
    }

    static boolean isChromeComponent(final ComponentName component) {
        return COMPONENT.equals(component);
    }

    static boolean isChromeComponentName(final String componentName) {
        return (BuildConfig.APPLICATION_ID + "/" + CLASS_NAME)
                        .equals(componentName)
                || (BuildConfig.APPLICATION_ID + "/.DesktopChromeActivity")
                        .equals(componentName);
    }

    IBinder activityToken() {
        return FrameworkActivityInputApi.requireActivityToken(this);
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final int requestedDisplayId = getIntent().getIntExtra(
                EXTRA_DISPLAY_ID, Display.INVALID_DISPLAY);
        final Display display = getDisplay();
        mDisplayId = display == null
                ? Display.INVALID_DISPLAY : display.getDisplayId();
        if (requestedDisplayId == Display.INVALID_DISPLAY
                || requestedDisplayId != mDisplayId) {
            finishAndRemoveTask();
            overridePendingTransition(0, 0);
            return;
        }
        // Child application windows own all chrome input and geometry. The
        // transparent fullscreen base must not become an input sink.
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setNavigationBarContrastEnforced(false);
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
                        registerChromeHost(view.getWindowToken());
                        applyPresentation();
                    }

                    @Override
                    public void onViewDetachedFromWindow(final View view) {
                        DesktopPanelWindowController.unregisterActivity(
                                mDisplayId, DesktopChromeActivity.this);
                        removeTaskbarPanel();
                    }
                });
        DesktopTaskbarHost.registerActivity(mDisplayId, this);
    }

    @Override
    protected void onDestroy() {
        DesktopPanelWindowController.unregisterActivity(mDisplayId, this);
        DesktopTaskbarHost.unregisterActivity(mDisplayId, this);
        removeSurfaceTraversalFence();
        detachTaskbar();
        removeTaskbarPanel();
        mWindowManager = null;
        mRoot = null;
        super.onDestroy();
    }

    private void registerChromeHost(final IBinder windowToken) {
        if (mDisplayId == Display.INVALID_DISPLAY || windowToken == null) {
            return;
        }
        DesktopPanelWindowController.registerActivity(
                mDisplayId, this, mWindowManager, windowToken);
        MagicDeskRuntime.configureDesktopActivityInput(
                mDisplayId, activityToken());
    }

    void attachTaskbar(
            final View taskbar,
            final int taskbarHeight,
            final int surfaceHeight) {
        mTaskbarHeight = Math.max(1, taskbarHeight);
        mSurfaceHeight = Math.max(mTaskbarHeight, surfaceHeight);
        if (mRoot == null || taskbar == null) {
            applyPresentation();
            return;
        }
        if (mTaskbar == taskbar) {
            updateTaskbarLayout();
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
            mTaskbarPanel.setBackgroundColor(DesktopUiFactory.COLOR_PANEL);
            mTaskbarPanel.setImportantForAccessibility(
                    View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        mTaskbar = taskbar;
        mTaskbarPanel.addView(taskbar, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                mTaskbarHeight,
                Gravity.TOP));
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

    boolean runAfterSurfaceTraversalFence(final Runnable action) {
        if (action == null || mWindowManager == null || mRoot == null
                || mRoot.getWindowToken() == null) {
            return false;
        }
        removeSurfaceTraversalFence();
        final View fence = new View(this);
        fence.setBackgroundColor(Color.TRANSPARENT);
        final WindowManager.LayoutParams params =
                new WindowManager.LayoutParams(
                        1,
                        1,
                        WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                        PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.token = mRoot.getWindowToken();
        params.setFitInsetsTypes(0);
        params.setTitle("MagicDesk task activation fence");
        try {
            mWindowManager.addView(fence, params);
            mSurfaceTraversalFence = fence;
            mSurfaceTraversalFenceAdded = true;
            fence.postOnAnimation(() -> {
                if (!mSurfaceTraversalFenceAdded
                        || mSurfaceTraversalFence != fence) {
                    return;
                }
                try {
                    action.run();
                } finally {
                    fence.postOnAnimation(() -> {
                        if (mSurfaceTraversalFence == fence) {
                            removeSurfaceTraversalFence();
                        }
                    });
                }
            });
            return true;
        } catch (RuntimeException error) {
            mSurfaceTraversalFence = null;
            mSurfaceTraversalFenceAdded = false;
            return false;
        }
    }

    private void applyPresentation() {
        updateTaskbarLayout();
        if (mTaskbar != null) {
            mTaskbar.setAlpha(mPresented && !mEdgeHidden ? 1f : 0f);
            mTaskbar.setVisibility(mPresented ? View.VISIBLE : View.INVISIBLE);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        updateTaskbarPanel(resolvePanelHeight(
                mPresented, mEdgeHidden, mEdgeHeight, mSurfaceHeight));
    }

    private void updateTaskbarLayout() {
        if (mTaskbar == null) {
            return;
        }
        final ViewGroup.LayoutParams current = mTaskbar.getLayoutParams();
        if (!(current instanceof FrameLayout.LayoutParams)) {
            return;
        }
        final FrameLayout.LayoutParams params =
                (FrameLayout.LayoutParams) current;
        if (params.width != FrameLayout.LayoutParams.MATCH_PARENT
                || params.height != mTaskbarHeight
                || params.gravity != Gravity.TOP) {
            params.width = FrameLayout.LayoutParams.MATCH_PARENT;
            params.height = mTaskbarHeight;
            params.gravity = Gravity.TOP;
            mTaskbar.setLayoutParams(params);
        }
    }

    static int resolvePanelHeight(
            final boolean presented,
            final boolean edgeHidden,
            final int edgeHeight,
            final int surfaceHeight) {
        if (!presented) {
            return 0;
        }
        return edgeHidden
                ? Math.max(1, edgeHeight)
                : Math.max(1, surfaceHeight);
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
        // DesktopLayoutController already provides physical display geometry.
        // Applying bars or IME insets again would move this attached window
        // whenever another focused task changes system-bar visibility.
        params.setFitInsetsTypes(0);
        params.softInputMode =
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
        params.setTitle("MagicDesk taskbar panel");
        return params;
    }

    private void removeTaskbarPanel() {
        if (mTaskbarPanelAdded && mTaskbarPanel != null
                && mWindowManager != null) {
            mWindowManager.removeViewImmediate(mTaskbarPanel);
        }
        mTaskbarPanelAdded = false;
        mTaskbarPanelHeight = 0;
    }

    private void removeSurfaceTraversalFence() {
        final View fence = mSurfaceTraversalFence;
        if (mSurfaceTraversalFenceAdded && fence != null
                && mWindowManager != null) {
            try {
                mWindowManager.removeViewImmediate(fence);
            } catch (RuntimeException ignored) {
                // The activity teardown may already have removed the window.
            }
        }
        mSurfaceTraversalFence = null;
        mSurfaceTraversalFenceAdded = false;
    }

    private final class TaskbarPanel extends FrameLayout {
        private boolean mHiddenEdgeTouchSequence;

        TaskbarPanel() {
            super(DesktopChromeActivity.this);
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
