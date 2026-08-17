package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.LinearLayout;

import java.util.Collections;
import java.util.List;

final class InputMethodMenuController {
    private static final String TAG = "MagicDeskInputMethod";

    private final DesktopShellActivity mActivity;
    private final DesktopUiFactory mUi;
    private LinearLayout mPanel;

    InputMethodMenuController(
            final DesktopShellActivity activity,
            final DesktopUiFactory ui) {
        mActivity = activity;
        mUi = ui;
    }

    void toggle(final View anchor) {
        final OverlayPanelController overlays = mActivity.overlayPanels();
        if (overlays == null) {
            return;
        }
        if (overlays.isVisible(mPanel)) {
            overlays.hide(mPanel);
            return;
        }
        final InputMethodManager manager = mActivity.getSystemService(
                InputMethodManager.class);
        final List<InputMethodInfo> methods = manager == null
                ? Collections.emptyList()
                : manager.getEnabledInputMethodList();
        if (methods.isEmpty()) {
            return;
        }
        ensurePanel();
        populate(methods);
        show(overlays, anchor);
    }

    void release() {
        mPanel = null;
    }

    private void ensurePanel() {
        if (mPanel != null) {
            return;
        }
        mPanel = new LinearLayout(mActivity);
        mPanel.setOrientation(LinearLayout.VERTICAL);
        mPanel.setPadding(dp(8, 6), dp(8, 6), dp(8, 6), dp(8, 6));
        mPanel.setBackground(mUi.rounded(
                DesktopUiFactory.COLOR_PANEL,
                dp(8, 6),
                DesktopUiFactory.COLOR_CYAN));
        mPanel.setClickable(true);
    }

    private void populate(final List<InputMethodInfo> methods) {
        mPanel.removeAllViews();
        final String current = Settings.Secure.getString(
                mActivity.getContentResolver(),
                Settings.Secure.DEFAULT_INPUT_METHOD);
        for (final InputMethodInfo method : methods) {
            final String id = method.getId();
            final CharSequence label = method.loadLabel(
                    mActivity.getPackageManager());
            final boolean selected = id.equals(current);
            final Button button = mUi.actionButton(
                    selected ? "\u2713 " + label : label.toString(),
                    selected ? DesktopUiFactory.COLOR_CYAN
                            : DesktopUiFactory.COLOR_PANEL_ALT);
            button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            button.setOnClickListener(view -> select(id));
            final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(44, 36));
            params.setMargins(0, dp(2, 1), 0, dp(2, 1));
            mPanel.addView(button, params);
        }
    }

    private void show(
            final OverlayPanelController overlays,
            final View anchor) {
        final int width = dp(280, 220);
        mPanel.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(
                        mActivity.getDesktopAreaHeight(), View.MeasureSpec.AT_MOST));
        final int[] location = new int[2];
        anchor.getLocationOnScreen(location);
        final int height = mPanel.getMeasuredHeight();
        final int left = Math.max(
                mActivity.getDesktopAreaLeft(),
                location[0] + anchor.getWidth() - width);
        final int top = Math.max(
                mActivity.getDesktopAreaTop(), location[1] - height);
        overlays.show(mPanel, left, top, width, height,
                false, "MagicDesk input methods");
    }

    private void select(final String id) {
        mActivity.hideAllPanels();
        if (ComponentName.unflattenFromString(id) == null) {
            return;
        }
        new Thread(() -> {
            try {
                ShellAccess.run("/system/bin/ime set "
                        + ShellCommandLine.quote(id));
                HardwareKeyboardLayoutController.configureVirtualLayouts(null);
            } catch (Exception error) {
                Log.w(TAG, "Could not select input method", error);
            }
        }, TAG).start();
    }

    private int dp(final int normal, final int compact) {
        return mUi.desktopDp(normal, compact,
                mActivity.isCompactDesktopPreview());
    }
}
