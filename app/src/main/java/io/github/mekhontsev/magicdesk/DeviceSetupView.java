package io.github.mekhontsev.magicdesk;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

final class DeviceSetupView {
    private final DeviceSetupActivity mActivity;
    private final DesktopUiFactory mUi;

    private TextView mTitle;
    private TextView mSummary;
    private LinearLayout mDetails;
    private LinearLayout mSecondaryRow;
    private TextView mDisplayTargetValue;
    private TextView mDeviceValue;
    private TextView mShizukuValue;
    private TextView mOverlayValue;
    private TextView mRestrictionsValue;
    private TextView mCornersValue;
    private TextView mRebootValue;
    private TextView mBuildValue;
    private Button mPrimaryAction;
    private Button mDiagnosticsAction;
    private Button mSecondaryAction;
    private Button mRestoreAction;
    private TextView mRestoreNote;

    DeviceSetupView(final DeviceSetupActivity activity) {
        mActivity = activity;
        mUi = new DesktopUiFactory(activity);
    }

    View create() {
        final FrameLayout root = new FrameLayout(mActivity);
        root.setBackgroundColor(DesktopUiFactory.COLOR_BACKGROUND);

        final LinearLayout page = new LinearLayout(mActivity);
        page.setOrientation(LinearLayout.VERTICAL);
        final Display display = mActivity.getDisplay();
        final int displayId = display == null
                ? Display.DEFAULT_DISPLAY : display.getDisplayId();
        final int bottomPadding = dp(18)
                + (displayId == Display.DEFAULT_DISPLAY
                        ? 0 : dp(DesktopShellActivity.TASKBAR_HEIGHT_DP));
        page.setPadding(
                dp(20),
                dp(18),
                dp(20),
                bottomPadding);

        final LinearLayout header = new LinearLayout(mActivity);
        header.setOrientation(LinearLayout.VERTICAL);

        mTitle = new TextView(mActivity);
        mTitle.setText(R.string.setup_title);
        mTitle.setTextColor(DesktopUiFactory.COLOR_TEXT);
        mTitle.setTextSize(24);
        mTitle.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(mTitle);

        page.addView(header);

        mSummary = new TextView(mActivity);
        mSummary.setText(R.string.setup_status_checking);
        mSummary.setTextColor(DesktopUiFactory.COLOR_CYAN);
        mSummary.setTextSize(16);
        mSummary.setTypeface(Typeface.DEFAULT_BOLD);
        mSummary.setPadding(dp(12), dp(10), dp(12), dp(10));
        mSummary.setBackground(mUi.rounded(
                DesktopUiFactory.COLOR_PANEL_ALT,
                dp(6),
                DesktopUiFactory.COLOR_PANEL_ALT));
        final LinearLayout.LayoutParams summaryParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        summaryParams.setMargins(0, dp(18), 0, dp(10));
        page.addView(mSummary, summaryParams);

        mDetails = new LinearLayout(mActivity);
        mDetails.setOrientation(LinearLayout.VERTICAL);
        mDetails.setBackgroundColor(DesktopUiFactory.COLOR_PANEL);
        mDisplayTargetValue =
                addStatusRow(mDetails, R.string.setup_item_display_target);
        makeProfileValueInteractive(
                mDisplayTargetValue,
                mActivity::showDisplayTargetChooser);
        mDeviceValue = addStatusRow(mDetails, R.string.setup_item_device);
        mShizukuValue = addStatusRow(mDetails, R.string.setup_item_shizuku);
        mOverlayValue = addStatusRow(mDetails, R.string.setup_item_overlays);
        mRestrictionsValue = addStatusRow(
                mDetails, R.string.setup_item_desktop_eligibility);
        mCornersValue =
                addStatusRow(mDetails, R.string.setup_item_window_corners);
        mRebootValue = addStatusRow(mDetails, R.string.setup_item_reboot);

        final TextView buildLabel = new TextView(mActivity);
        buildLabel.setText(R.string.setup_build_label);
        buildLabel.setTextColor(DesktopUiFactory.COLOR_MUTED);
        buildLabel.setTextSize(12);
        buildLabel.setPadding(dp(12), dp(12), dp(12), 0);
        mDetails.addView(buildLabel);

        mBuildValue = new TextView(mActivity);
        mBuildValue.setTextColor(DesktopUiFactory.COLOR_MUTED);
        mBuildValue.setTextSize(11);
        mBuildValue.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        mBuildValue.setSingleLine(true);
        mBuildValue.setPadding(dp(12), dp(4), dp(12), dp(12));
        mDetails.addView(mBuildValue);

        page.addView(mDetails, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final LinearLayout actions = new LinearLayout(mActivity);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setPadding(0, dp(12), 0, 0);

        mPrimaryAction = createActionButton(
                DesktopUiFactory.COLOR_CYAN);
        actions.addView(mPrimaryAction, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

        mDiagnosticsAction = createActionButton(
                DesktopUiFactory.COLOR_CYAN);
        mDiagnosticsAction.setText(R.string.action_diagnostics);
        mDiagnosticsAction.setOnClickListener(view ->
                mActivity.startActivity(
                        DiagnosticsActivity.createIntent(mActivity)));
        final LinearLayout.LayoutParams diagnosticsParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        diagnosticsParams.setMargins(0, dp(8), 0, 0);
        actions.addView(mDiagnosticsAction, diagnosticsParams);

        mRestoreNote = new TextView(mActivity);
        mRestoreNote.setText(R.string.setup_restore_uninstall_note);
        mRestoreNote.setTextColor(DesktopUiFactory.COLOR_MUTED);
        mRestoreNote.setTextSize(13);
        final LinearLayout.LayoutParams restoreNoteParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        restoreNoteParams.setMargins(dp(4), dp(8), dp(4), 0);
        actions.addView(mRestoreNote, restoreNoteParams);

        mSecondaryRow = new LinearLayout(mActivity);
        mSecondaryRow.setOrientation(LinearLayout.HORIZONTAL);
        mSecondaryAction = createActionButton(
                DesktopUiFactory.COLOR_MUTED);
        mSecondaryRow.addView(mSecondaryAction,
                new LinearLayout.LayoutParams(0, dp(48), 1));
        mRestoreAction = createActionButton(
                DesktopUiFactory.COLOR_AMBER);
        final LinearLayout.LayoutParams restoreParams =
                new LinearLayout.LayoutParams(0, dp(48), 1);
        restoreParams.setMargins(dp(8), 0, 0, 0);
        mSecondaryRow.addView(mRestoreAction, restoreParams);
        final LinearLayout.LayoutParams secondaryParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        secondaryParams.setMargins(0, dp(8), 0, 0);
        actions.addView(mSecondaryRow, secondaryParams);
        page.addView(actions);

        mPrimaryAction.setText(R.string.setup_action_recheck);
        mSecondaryAction.setText(R.string.setup_action_exit);
        mRestoreNote.setVisibility(View.GONE);
        mRestoreAction.setVisibility(View.GONE);

        final ScrollView scroll = new ScrollView(mActivity);
        scroll.setFillViewport(true);
        scroll.addView(page, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        final FrameLayout.LayoutParams scrollParams =
                new FrameLayout.LayoutParams(
                        Math.min(
                                mActivity.getResources()
                                        .getDisplayMetrics().widthPixels
                                        - dp(24),
                                dp(720)),
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        Gravity.CENTER_HORIZONTAL);
        root.addView(scroll, scrollParams);
        SystemBarInsets.addToPadding(root);
        return root;
    }

    void setDetailed(final boolean detailed) {
        mTitle.setText(detailed
                ? R.string.setup_title : R.string.setup_title_onboarding);
        mDetails.setVisibility(detailed ? View.VISIBLE : View.GONE);
        mDiagnosticsAction.setVisibility(detailed ? View.VISIBLE : View.GONE);
    }

    void setSecondaryActionsVisible(
            final boolean secondaryVisible,
            final boolean restoreVisible) {
        mSecondaryRow.setVisibility(
                secondaryVisible ? View.VISIBLE : View.GONE);
        mSecondaryAction.setVisibility(
                secondaryVisible ? View.VISIBLE : View.GONE);
        mRestoreAction.setVisibility(
                restoreVisible ? View.VISIBLE : View.GONE);
        mRestoreNote.setVisibility(
                restoreVisible ? View.VISIBLE : View.GONE);
    }

    TextView summary() {
        return mSummary;
    }

    TextView displayTargetValue() {
        return mDisplayTargetValue;
    }

    TextView deviceValue() {
        return mDeviceValue;
    }

    TextView shizukuValue() {
        return mShizukuValue;
    }

    TextView overlayValue() {
        return mOverlayValue;
    }

    TextView restrictionsValue() {
        return mRestrictionsValue;
    }

    TextView cornersValue() {
        return mCornersValue;
    }

    TextView rebootValue() {
        return mRebootValue;
    }

    TextView buildValue() {
        return mBuildValue;
    }

    Button primaryAction() {
        return mPrimaryAction;
    }

    Button diagnosticsAction() {
        return mDiagnosticsAction;
    }

    Button secondaryAction() {
        return mSecondaryAction;
    }

    Button restoreAction() {
        return mRestoreAction;
    }

    private TextView addStatusRow(
            final LinearLayout parent,
            final int labelResId) {
        final LinearLayout row = new LinearLayout(mActivity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(8), dp(12), dp(8));

        final TextView label = new TextView(mActivity);
        label.setText(labelResId);
        label.setTextColor(DesktopUiFactory.COLOR_TEXT);
        label.setTextSize(14);
        row.addView(label, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        final TextView value = new TextView(mActivity);
        value.setText(R.string.setup_value_checking);
        value.setTextColor(DesktopUiFactory.COLOR_MUTED);
        value.setTextSize(13);
        value.setGravity(Gravity.END);
        value.setMaxLines(2);
        row.addView(value, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        parent.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final View divider = new View(mActivity);
        divider.setBackgroundColor(DesktopUiFactory.COLOR_PANEL_ALT);
        parent.addView(divider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
        return value;
    }

    private Button createActionButton(final int accentColor) {
        final Button button = new Button(mActivity);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setSingleLine(true);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setTextSize(14);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(8), dp(6), dp(8), dp(6));
        button.setGravity(Gravity.CENTER);
        button.setBackground(mUi.rounded(
                DesktopUiFactory.COLOR_PANEL_ALT,
                dp(6),
                accentColor));
        return button;
    }

    private void makeProfileValueInteractive(
            final TextView value,
            final View.OnClickListener listener) {
        value.setClickable(true);
        value.setFocusable(true);
        value.setPadding(dp(8), dp(4), dp(8), dp(4));
        value.setBackground(mUi.rounded(
                DesktopUiFactory.COLOR_PANEL_ALT,
                dp(5),
                DesktopUiFactory.COLOR_PANEL_ALT));
        value.setOnClickListener(listener);
    }

    private int dp(final int value) {
        return mUi.dp(value);
    }
}
