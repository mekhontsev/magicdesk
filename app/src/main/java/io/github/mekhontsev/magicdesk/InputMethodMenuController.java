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
import android.widget.TextView;

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
        if (DesktopScreenPolicy.isExternalDesktop(
                mActivity.getCurrentDisplayId())) {
            addKeyboardLocationControls();
        }
    }

    private void addKeyboardLocationControls() {
        final TextView title = new TextView(mActivity);
        title.setText(R.string.on_screen_keyboard_location);
        title.setTextColor(DesktopUiFactory.COLOR_MUTED);
        title.setTextSize(12);
        final LinearLayout.LayoutParams titleParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        titleParams.setMargins(0, dp(8, 6), 0, dp(3, 2));
        mPanel.addView(title, titleParams);

        final OnScreenKeyboardLocation location =
                DesktopPreferences.onScreenKeyboardLocation(mActivity);
        final LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        final Button phone = mUi.actionButton(
                (location == OnScreenKeyboardLocation.PHONE
                        ? "\u2713 " : "")
                        + mActivity.getString(
                                R.string.keyboard_location_phone),
                DesktopUiFactory.COLOR_CYAN);
        phone.setOnClickListener(view -> selectLocation(
                OnScreenKeyboardLocation.PHONE));
        final Button desktop = mUi.actionButton(
                (location == OnScreenKeyboardLocation.DESKTOP
                        ? "\u2713 " : "")
                        + mActivity.getString(
                                R.string.keyboard_location_desktop),
                DesktopUiFactory.COLOR_CYAN);
        desktop.setEnabled(ShellAccess.isReady());
        desktop.setOnClickListener(view -> selectLocation(
                OnScreenKeyboardLocation.DESKTOP));
        row.addView(phone, new LinearLayout.LayoutParams(
                0, dp(40, 34), 1));
        final LinearLayout.LayoutParams desktopParams =
                new LinearLayout.LayoutParams(0, dp(40, 34), 1);
        desktopParams.setMargins(dp(4, 3), 0, 0, 0);
        row.addView(desktop, desktopParams);
        mPanel.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
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
                ShellAccess.run("/system/bin/ime set " + shellQuote(id));
                HardwareKeyboardLayoutController.configureVirtualLayouts(null);
            } catch (Exception error) {
                Log.w(TAG, "Could not select input method", error);
            }
        }, TAG).start();
    }

    private void selectLocation(
            final OnScreenKeyboardLocation location) {
        mActivity.hideAllPanels();
        MagicDeskRuntimeService.setOnScreenKeyboardLocation(
                mActivity, location);
    }

    static String shellQuote(final String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private int dp(final int normal, final int compact) {
        return mUi.desktopDp(normal, compact,
                mActivity.isCompactDesktopPreview());
    }
}
