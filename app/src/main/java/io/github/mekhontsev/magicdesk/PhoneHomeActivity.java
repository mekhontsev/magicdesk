package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;

/** Primary HOME surface used while MagicDesk owns the HOME role. */
public final class PhoneHomeActivity extends Activity {
    private static final int ICON_SIZE_DP = 72;
    private static final int ACTION_WIDTH_DP = 220;
    private static final int ACTION_HEIGHT_DP = 52;
    private static final int ACTION_MARGIN_DP = 28;

    private Button mCloseDesktop;
    private boolean mClosing;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER);

        final FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.TRANSPARENT);

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

        root.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
        setContentView(
                root,
                new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
        refreshCloseAction();
        if (DesktopHomeRoleLease.snapshot() != null) {
            MagicDeskRuntime.start(this);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshCloseAction();
    }

    private void closeDesktop() {
        if (mClosing) {
            return;
        }
        final DesktopHomeRoleLease.State lease =
                DesktopHomeRoleLease.snapshot();
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
                    mClosing = false;
                    mCloseDesktop.setText(R.string.action_close_desktop);
                    refreshCloseAction();
                }));
    }

    private void refreshCloseAction() {
        if (mCloseDesktop == null || mClosing) {
            return;
        }
        mCloseDesktop.setEnabled(DesktopHomeRoleLease.snapshot() != null);
    }

    private int dp(final int value) {
        return Math.round(
                value * getResources().getDisplayMetrics().density);
    }
}
