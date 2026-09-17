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
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Collections;
import java.util.List;

final class InputMethodMenuController {
    private static final String TAG = "MagicDeskInputMethod";

    private final DesktopShellActivity mActivity;
    private final DesktopUiFactory mUi;
    private ScrollView mPanel;
    private LinearLayout mContent;
    private LinearLayout mHardware;
    private int mGeneration;

    InputMethodMenuController(
            final DesktopShellActivity activity,
            final DesktopUiFactory ui) {
        mActivity = activity;
        mUi = ui;
    }

    void toggle(final View anchor) {
        final DesktopPanelWindowController panels = mActivity.panels();
        if (panels == null) {
            return;
        }
        if (panels.isRequested(mPanel)) {
            mGeneration++;
            panels.hide(mPanel);
            return;
        }
        final InputMethodManager manager = mActivity.getSystemService(
                InputMethodManager.class);
        final List<InputMethodInfo> methods = manager == null
                ? Collections.emptyList()
                : manager.getEnabledInputMethodList();
        ensurePanel();
        final boolean loadHardware = ShellAccess.isReady();
        if (loadHardware) {
            mContent.removeAllViews();
            final TextView loading = new TextView(mActivity);
            loading.setText(R.string.keyboard_loading);
            loading.setTextColor(DesktopUiFactory.COLOR_MUTED);
            loading.setPadding(dp(8, 6), dp(12, 8), dp(8, 6), dp(12, 8));
            mContent.addView(loading);
        } else populate(methods);
        show(panels, anchor);
        final int generation = ++mGeneration;
        if (loadHardware) HardwareKeyboardLayoutController.load((layouts, error) ->
                mActivity.runOnUiThread(() -> {
                    if (generation != mGeneration || mActivity.isActivityUnavailable()
                            || !panels.isRequested(mPanel)) return;
                    populate(methods);
                    populateHardware(layouts, error);
                    show(panels, anchor);
                }));
    }

    void release() {
        mGeneration++;
        mPanel = null;
        mContent = null;
        mHardware = null;
    }

    private void ensurePanel() {
        if (mPanel != null) {
            return;
        }
        mPanel = new ScrollView(mActivity);
        mContent = new LinearLayout(mActivity);
        mContent.setOrientation(LinearLayout.VERTICAL);
        mContent.setPadding(dp(8, 6), dp(8, 6), dp(8, 6), dp(8, 6));
        mPanel.addView(mContent, new ScrollView.LayoutParams(-1, -2));
        mPanel.setBackground(mUi.rounded(
                DesktopUiFactory.COLOR_PANEL,
                dp(8, 6),
                DesktopUiFactory.COLOR_CYAN));
        mPanel.setClickable(true);
    }

    private void populate(final List<InputMethodInfo> methods) {
        mContent.removeAllViews();
        mHardware = new LinearLayout(mActivity);
        mHardware.setOrientation(LinearLayout.VERTICAL);
        mContent.addView(mHardware, new LinearLayout.LayoutParams(-1, -2));
        mContent.addView(mUi.menuHeader(mActivity.getString(R.string.keyboard_input_methods),
                android.text.TextUtils.TruncateAt.END));
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
            mContent.addView(button, params);
            mActivity.registerAutomationUiElement(button,
                    "keyboard.ime." + DesktopAutomationUiRegistry.identitySegment(id),
                    "menu_item", label);
        }
    }

    private void populateHardware(HardwareKeyboardLayouts layouts, Throwable error) {
        mHardware.removeAllViews();
        if (error == null && layouts.physicalDevices() == 0) return;
        mHardware.addView(mUi.menuHeader(mActivity.getString(R.string.keyboard_hardware_layouts),
                android.text.TextUtils.TruncateAt.END));
        if (error != null || layouts.choices().isEmpty()) {
            final TextView unavailable = new TextView(mActivity);
            unavailable.setText(error == null ? mActivity.getString(R.string.state_unavailable)
                    : ShellAccess.usefulMessage(error));
            unavailable.setTextColor(DesktopUiFactory.COLOR_MUTED);
            unavailable.setPadding(dp(8, 6), dp(4, 3), dp(8, 6), dp(8, 6));
            mHardware.addView(unavailable);
            return;
        }
        for (final HardwareKeyboardLayouts.Choice choice : layouts.choices()) {
            final Button button = mUi.menuItem(choice.selected() ? "\u2713 " + choice.label() : choice.label(), choice.selected()
                    ? DesktopUiFactory.COLOR_CYAN : DesktopUiFactory.COLOR_PANEL_ALT);
            button.setSelected(choice.selected());
            button.setTooltipText(choice.label());
            button.setOnClickListener(view -> {
                mActivity.hideAllPanels();
                HardwareKeyboardLayoutController.select(choice.descriptor(), errorResult ->
                        mActivity.runOnUiThread(() -> {
                            if (mActivity.isActivityUnavailable()) return;
                            if (errorResult != null) Toast.makeText(mActivity,
                                    ShellAccess.usefulMessage(errorResult), Toast.LENGTH_LONG).show();
                            mActivity.taskbar().updateKeyboardLayout();
                        }));
            });
            mHardware.addView(button, new LinearLayout.LayoutParams(-1, mUi.menuItemHeight()));
            mActivity.registerAutomationUiElement(button,
                    "keyboard.layout." + DesktopAutomationUiRegistry.identitySegment(choice.descriptor()),
                    "menu_item", choice.label());
        }
    }

    private void show(
            final DesktopPanelWindowController panels,
            final View anchor) {
        final int width = Math.min(dp(320, 260), Math.max(1, mActivity.getDesktopAreaWidth() - dp(16, 12)));
        final int maxHeight = Math.max(1, mActivity.getDesktopAreaHeight()
                - mActivity.getTaskbarHeight() - dp(16, 12));
        mPanel.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(
                        maxHeight, View.MeasureSpec.AT_MOST));
        final int[] location = new int[2];
        anchor.getLocationOnScreen(location);
        final int height = Math.min(maxHeight, mPanel.getMeasuredHeight());
        final int left = Math.max(
                mActivity.getDesktopAreaLeft(),
                location[0] + anchor.getWidth() - width);
        final int top = Math.max(
                mActivity.getDesktopAreaTop(), location[1] - height);
        panels.show(mPanel, left, top, width, height,
                false, false, "MagicDesk input methods");
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
                HardwareKeyboardLayoutController.refresh();
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
