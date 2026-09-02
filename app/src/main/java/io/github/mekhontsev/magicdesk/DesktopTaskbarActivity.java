package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Display;
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
    private int mDisplayId = Display.INVALID_DISPLAY;
    private boolean mPresented = true;
    private boolean mEdgeHidden;

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
        mDisplayId = getDisplay() == null
                ? Display.INVALID_DISPLAY : getDisplay().getDisplayId();
        DesktopTaskbarHost.registerActivity(mDisplayId, this);
    }

    @Override
    protected void onDestroy() {
        DesktopTaskbarHost.unregisterActivity(mDisplayId, this);
        detachTaskbar();
        mRoot = null;
        super.onDestroy();
    }

    void attachTaskbar(final View taskbar) {
        if (mRoot == null || taskbar == null || mTaskbar == taskbar) {
            applyPresentation();
            return;
        }
        detachTaskbar();
        final ViewGroup parent = taskbar.getParent() instanceof ViewGroup
                ? (ViewGroup) taskbar.getParent() : null;
        if (parent != null) {
            parent.removeView(taskbar);
        }
        mTaskbar = taskbar;
        mRoot.addView(taskbar, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        applyPresentation();
    }

    void detachTaskbar() {
        if (mTaskbar != null) {
            final ViewGroup parent = mTaskbar.getParent() instanceof ViewGroup
                    ? (ViewGroup) mTaskbar.getParent() : null;
            if (parent != null) {
                parent.removeView(mTaskbar);
            }
        }
        mTaskbar = null;
    }

    void setPresentation(
            final boolean presented,
            final boolean edgeHidden) {
        mPresented = presented;
        mEdgeHidden = edgeHidden;
        applyPresentation();
    }

    private void applyPresentation() {
        if (mTaskbar != null) {
            mTaskbar.setAlpha(mPresented && !mEdgeHidden ? 1f : 0f);
            mTaskbar.setVisibility(mPresented ? View.VISIBLE : View.INVISIBLE);
        }
        if (mPresented) {
            getWindow().clearFlags(
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        } else {
            getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        }
    }
}
