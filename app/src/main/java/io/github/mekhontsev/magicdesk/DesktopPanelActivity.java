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
import android.view.WindowManager;
import android.widget.FrameLayout;

/** Supplies an application token for desktop-owned panel and dialog windows. */
public final class DesktopPanelActivity extends Activity {
    private static final String EXTRA_DISPLAY_ID =
            "magicdesk_panel_display_id";
    private static final String CLASS_NAME =
            BuildConfig.APPLICATION_ID + ".DesktopPanelActivity";
    static final ComponentName COMPONENT = new ComponentName(
            BuildConfig.APPLICATION_ID, CLASS_NAME);

    private FrameLayout mRoot;
    private int mDisplayId = Display.INVALID_DISPLAY;
    private boolean mRegistered;

    static Intent createIntent(final int displayId) {
        return new Intent()
                .setComponent(COMPONENT)
                .setData(Uri.parse("magicdesk-desktop-panels:" + displayId))
                .putExtra(EXTRA_DISPLAY_ID, displayId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                        | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION);
    }

    static boolean isPanelComponent(final ComponentName component) {
        return COMPONENT.equals(component);
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
            DesktopPanelWindowController.rejectActivity(
                    requestedDisplayId, mDisplayId, this);
            return;
        }
        getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarContrastEnforced(false);
        // Child application windows own all input. The transparent base must
        // neither focus nor create a fullscreen input sink over other apps.
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);

        mRoot = new FrameLayout(this);
        mRoot.setBackgroundColor(Color.TRANSPARENT);
        mRoot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        mRoot.addOnAttachStateChangeListener(
                new View.OnAttachStateChangeListener() {
                    @Override
                    public void onViewAttachedToWindow(final View view) {
                        registerHost(view.getWindowToken());
                    }

                    @Override
                    public void onViewDetachedFromWindow(final View view) {
                        unregisterHost();
                    }
                });
        setContentView(mRoot);
    }

    @Override
    protected void onDestroy() {
        unregisterHost();
        mRoot = null;
        super.onDestroy();
    }

    void finishFromController() {
        if (!isFinishing()) {
            finishAndRemoveTask();
            overridePendingTransition(0, 0);
        }
    }

    private void registerHost(final IBinder windowToken) {
        if (mRegistered || mDisplayId == Display.INVALID_DISPLAY
                || windowToken == null) {
            return;
        }
        mRegistered = true;
        DesktopPanelWindowController.registerActivity(
                mDisplayId, this, getWindowManager(), windowToken);
        MagicDeskRuntime.configureDesktopActivityInput(
                mDisplayId, FrameworkActivityInputApi.requireActivityToken(this));
    }

    private void unregisterHost() {
        if (!mRegistered) {
            return;
        }
        mRegistered = false;
        DesktopPanelWindowController.unregisterActivity(mDisplayId, this);
    }
}
