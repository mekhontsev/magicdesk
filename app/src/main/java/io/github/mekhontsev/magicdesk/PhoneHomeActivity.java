package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

/** Phone-side HOME surface used while an external desktop owns HOME. */
public final class PhoneHomeActivity extends Activity {
    private static final int ICON_SIZE_DP = 72;
    private static final int ACTION_WIDTH_DP = 220;
    private static final int ACTION_HEIGHT_DP = 52;
    private static final int ACTION_MARGIN_DP = 28;

    private FrameLayout mRoot;
    private Button mCloseDesktop;
    private boolean mClosing;
    private final OnBackInvokedCallback mBackCallback = () -> {
        // HOME is the bottom of the phone task stack.
    };

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (DesktopHomeStartupGuard.shouldDiscardStaleHomeLaunch(
                getIntent())) {
            finishAndRemoveTask();
            return;
        }
        if (!hasActivePhoneHomeLease()) {
            finishAndRemoveTask();
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                mBackCallback);

        mRoot = new FrameLayout(this);
        setContentView(
                mRoot,
                new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
        showHome();
        MagicDeskRuntime.start(this);
    }

    @Override
    protected void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        if (!hasActivePhoneHomeLease()) {
            finishAndRemoveTask();
            return;
        }
        setIntent(intent);
        showHome();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!hasActivePhoneHomeLease()) {
            finishAndRemoveTask();
            return;
        }
        refreshCloseAction();
    }

    @Override
    protected void onDestroy() {
        getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
                mBackCallback);
        super.onDestroy();
    }

    private void showHome() {
        mRoot.removeAllViews();
        mRoot.setBackgroundColor(Color.TRANSPARENT);

        final LinearLayout content = new LinearLayout(this);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setOrientation(LinearLayout.VERTICAL);

        final ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher);
        icon.setContentDescription(getString(R.string.app_name));
        final int iconSize = dp(ICON_SIZE_DP);
        content.addView(icon, new LinearLayout.LayoutParams(
                iconSize,
                iconSize));

        final DesktopUiFactory ui = new DesktopUiFactory(this);
        mCloseDesktop = ui.actionButton(
                R.string.action_close_desktop,
                DesktopUiFactory.COLOR_RED);
        final LinearLayout.LayoutParams actionParams =
                new LinearLayout.LayoutParams(
                        dp(ACTION_WIDTH_DP),
                        dp(ACTION_HEIGHT_DP));
        actionParams.topMargin = dp(ACTION_MARGIN_DP);
        content.addView(mCloseDesktop, actionParams);
        mCloseDesktop.setOnClickListener(view -> closeDesktop());

        mRoot.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        refreshCloseAction();
    }

    private void closeDesktop() {
        if (mClosing) {
            return;
        }
        final DesktopHomeRoleLease.State lease = activeLease();
        if (lease == null) {
            refreshCloseAction();
            return;
        }
        mClosing = true;
        mCloseDesktop.setEnabled(false);
        mCloseDesktop.setText(R.string.status_desktop_closing);
        DesktopOperations.closeDesktop(
                lease.target(),
                false,
                success -> runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        return;
                    }
                    if (!hasActivePhoneHomeLease()) {
                        finishAndRemoveTask();
                        return;
                    }
                    mClosing = false;
                    mCloseDesktop.setText(R.string.action_close_desktop);
                    refreshCloseAction();
                }));
    }

    private void refreshCloseAction() {
        if (mCloseDesktop == null || mClosing) {
            return;
        }
        mCloseDesktop.setEnabled(hasActivePhoneHomeLease());
    }

    private static boolean hasActivePhoneHomeLease() {
        return DesktopHomeRoleLease.isActiveForSurface(
                DesktopHomeSurfaceRouter.Surface.PHONE);
    }

    private static DesktopHomeRoleLease.State activeLease() {
        final DesktopHomeRoleLease.State lease =
                DesktopHomeRoleLease.snapshot();
        return lease != null && lease.phase == DesktopHomeRoleLease.Phase.ACTIVE
                ? lease : null;
    }

    private int dp(final int value) {
        return Math.round(
                value * getResources().getDisplayMetrics().density);
    }
}
