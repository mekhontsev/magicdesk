package io.github.mekhontsev.magicdesk;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.IOException;
import java.lang.reflect.Method;

public final class DeviceSetupActivity extends Activity {
    private static final String TAG = "MagicDeskSetup";
    private static final String EXTRA_MANUAL = "manual_setup";
    private static final int REQUEST_NOTIFICATIONS = 1;

    private static final int COLOR_BACKGROUND = 0xFF090D14;
    private static final int COLOR_PANEL = 0xFF111827;
    private static final int COLOR_PANEL_ALT = 0xFF172033;
    private static final int COLOR_TEXT = 0xFFE5E7EB;
    private static final int COLOR_MUTED = 0xFF94A3B8;
    private static final int COLOR_CYAN = 0xFF22D3EE;
    private static final int COLOR_RED = 0xFFF43F5E;
    private static final int COLOR_AMBER = 0xFFF59E0B;

    private TextView mSummary;
    private TextView mDeviceValue;
    private TextView mRootValue;
    private TextView mFreeformValue;
    private TextView mResizableValue;
    private TextView mRestrictionsValue;
    private TextView mCornersValue;
    private TextView mRebootValue;
    private TextView mBuildValue;
    private Button mPrimaryAction;
    private Button mDiagnosticsAction;
    private Button mSecondaryAction;
    private Button mRestoreAction;
    private boolean mManual;
    private boolean mBusy;
    private boolean mContentCreated;
    private DeviceSetupManager.Audit mAudit;

    static Intent createLaunchIntent(final Context context) {
        return new Intent(context, DeviceSetupActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    static Intent createManualIntent(final Context context) {
        return new Intent(context, DeviceSetupActivity.class)
                .putExtra(EXTRA_MANUAL, true);
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mManual = getIntent().getBooleanExtra(EXTRA_MANUAL, false);
        if (mManual) {
            ensureSetupContent();
        }
        runAudit();
    }

    @Override
    protected void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        mManual = intent.getBooleanExtra(EXTRA_MANUAL, false);
        if (mManual) {
            ensureSetupContent();
        }
        runAudit();
    }

    private void ensureSetupContent() {
        if (mContentCreated) {
            return;
        }
        setContentView(createContentView());
        mContentCreated = true;
    }

    private View createContentView() {
        final FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(COLOR_BACKGROUND);

        final LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(20), dp(18), dp(20), dp(18));

        final LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);

        final TextView title = new TextView(this);
        title.setText(R.string.setup_title);
        title.setTextColor(COLOR_TEXT);
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title);

        final TextView subtitle = new TextView(this);
        subtitle.setText(R.string.setup_subtitle);
        subtitle.setTextColor(COLOR_MUTED);
        subtitle.setTextSize(14);
        final LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        subtitleParams.setMargins(0, dp(4), 0, 0);
        header.addView(subtitle, subtitleParams);
        page.addView(header);

        mSummary = new TextView(this);
        mSummary.setText(R.string.setup_status_checking);
        mSummary.setTextColor(COLOR_CYAN);
        mSummary.setTextSize(16);
        mSummary.setTypeface(Typeface.DEFAULT_BOLD);
        mSummary.setPadding(dp(12), dp(10), dp(12), dp(10));
        mSummary.setBackground(rounded(COLOR_PANEL_ALT, dp(6), COLOR_PANEL_ALT));
        final LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        summaryParams.setMargins(0, dp(18), 0, dp(10));
        page.addView(mSummary, summaryParams);

        final ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        final LinearLayout rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setBackgroundColor(COLOR_PANEL);
        mDeviceValue = addStatusRow(rows, R.string.setup_item_device);
        mRootValue = addStatusRow(rows, R.string.setup_item_root);
        mFreeformValue = addStatusRow(rows, R.string.setup_item_freeform);
        mResizableValue = addStatusRow(rows, R.string.setup_item_resizable);
        mRestrictionsValue = addStatusRow(rows, R.string.setup_item_desktop_eligibility);
        mCornersValue = addStatusRow(rows, R.string.setup_item_window_corners);
        mRebootValue = addStatusRow(rows, R.string.setup_item_reboot);

        final TextView buildLabel = new TextView(this);
        buildLabel.setText(R.string.setup_build_label);
        buildLabel.setTextColor(COLOR_MUTED);
        buildLabel.setTextSize(12);
        buildLabel.setPadding(dp(12), dp(12), dp(12), 0);
        rows.addView(buildLabel);

        mBuildValue = new TextView(this);
        mBuildValue.setTextColor(COLOR_MUTED);
        mBuildValue.setTextSize(11);
        mBuildValue.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        mBuildValue.setSingleLine(true);
        mBuildValue.setPadding(dp(12), dp(4), dp(12), dp(12));
        rows.addView(mBuildValue);

        scroll.addView(rows, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        final LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        page.addView(scroll, scrollParams);

        final LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setPadding(0, dp(12), 0, 0);

        mPrimaryAction = createActionButton(COLOR_CYAN);
        actions.addView(mPrimaryAction, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)));

        mDiagnosticsAction = createActionButton(COLOR_CYAN);
        mDiagnosticsAction.setText(R.string.action_diagnostics);
        mDiagnosticsAction.setOnClickListener(
                view -> startActivity(DiagnosticsActivity.createIntent(this)));
        final LinearLayout.LayoutParams diagnosticsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48));
        diagnosticsParams.setMargins(0, dp(8), 0, 0);
        actions.addView(mDiagnosticsAction, diagnosticsParams);

        final LinearLayout secondaryRow = new LinearLayout(this);
        secondaryRow.setOrientation(LinearLayout.HORIZONTAL);
        mSecondaryAction = createActionButton(COLOR_MUTED);
        secondaryRow.addView(mSecondaryAction, new LinearLayout.LayoutParams(
                0, dp(48), 1));
        mRestoreAction = createActionButton(COLOR_AMBER);
        final LinearLayout.LayoutParams restoreParams = new LinearLayout.LayoutParams(
                0, dp(48), 1);
        restoreParams.setMargins(dp(8), 0, 0, 0);
        secondaryRow.addView(mRestoreAction, restoreParams);
        final LinearLayout.LayoutParams secondaryParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        secondaryParams.setMargins(0, dp(8), 0, 0);
        actions.addView(secondaryRow, secondaryParams);
        page.addView(actions);
        mPrimaryAction.setText(R.string.setup_action_recheck);
        mSecondaryAction.setText(R.string.setup_action_exit);
        mRestoreAction.setVisibility(View.GONE);

        final FrameLayout.LayoutParams pageParams = new FrameLayout.LayoutParams(
                Math.min(getResources().getDisplayMetrics().widthPixels - dp(24), dp(720)),
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER_HORIZONTAL);
        root.addView(page, pageParams);
        return root;
    }

    private TextView addStatusRow(final LinearLayout parent, final int labelResId) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(11), dp(12), dp(11));

        final TextView label = new TextView(this);
        label.setText(labelResId);
        label.setTextColor(COLOR_TEXT);
        label.setTextSize(14);
        row.addView(label, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        final TextView value = new TextView(this);
        value.setText(R.string.setup_value_checking);
        value.setTextColor(COLOR_MUTED);
        value.setTextSize(13);
        value.setGravity(Gravity.END);
        value.setMaxLines(2);
        row.addView(value, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        parent.addView(row, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        final View divider = new View(this);
        divider.setBackgroundColor(COLOR_PANEL_ALT);
        parent.addView(divider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
        return value;
    }

    private Button createActionButton(final int accentColor) {
        final Button button = new Button(this);
        button.setAllCaps(false);
        button.setTextColor(Color.WHITE);
        button.setSingleLine(true);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setTextSize(14);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(dp(8), dp(6), dp(8), dp(6));
        button.setGravity(Gravity.CENTER);
        button.setBackground(rounded(COLOR_PANEL_ALT, dp(6), accentColor));
        return button;
    }

    private void runAudit() {
        if (mBusy) {
            return;
        }
        setBusy(true, R.string.setup_status_checking);
        new Thread(() -> {
            final DeviceSetupManager.Audit audit =
                    DeviceSetupManager.audit(getApplicationContext());
            runOnUiThread(() -> {
                if (isActivityUnavailable()) {
                    return;
                }
                mAudit = audit;
                if (!mManual && audit.canEnterMagicDesk() && audit.acknowledged) {
                    mBusy = false;
                    launchMagicDesk();
                    return;
                }
                if (!audit.canEnterMagicDesk()) {
                    DeviceSetupManager.revokeRuntimeAuthorization();
                    stopService(new Intent(this, KeyboardWatcherService.class));
                }
                ensureSetupContent();
                setBusy(false, 0);
                renderAudit(audit);
            });
        }, "MagicDeskSetupAudit").start();
    }

    private void renderAudit(final DeviceSetupManager.Audit audit) {
        setStatusValue(mDeviceValue,
                audit.compatibleDevice
                        ? getString(audit.verifiedDevice
                                        ? R.string.setup_value_supported
                                        : R.string.setup_value_supported_unverified,
                                audit.manufacturer,
                                audit.model)
                        : getString(R.string.setup_value_unsupported,
                                audit.manufacturer, audit.model),
                audit.compatibleDevice);
        setStatusValue(mRootValue,
                audit.rootAvailable
                        ? getString(R.string.setup_value_available)
                        : getString(R.string.setup_value_unavailable),
                audit.rootAvailable);
        setStatusValue(mFreeformValue,
                getString(audit.freeformEnabled
                        ? R.string.setup_value_enabled : R.string.setup_value_disabled),
                audit.freeformEnabled);
        setStatusValue(mResizableValue,
                getString(audit.resizableEnabled
                        ? R.string.setup_value_enabled : R.string.setup_value_disabled),
                audit.resizableEnabled);
        setStatusValue(mRestrictionsValue,
                getString(audit.restrictionsDisabled
                        ? R.string.setup_value_enabled : R.string.setup_value_disabled),
                audit.restrictionsDisabled);
        setStatusValue(mCornersValue,
                getString(audit.roundedCornersDisabled
                        ? R.string.setup_value_square : R.string.setup_value_rounded),
                audit.roundedCornersDisabled);
        setStatusValue(mRebootValue,
                getString(audit.rebootRequired
                        ? R.string.setup_value_required : R.string.setup_value_not_required),
                !audit.rebootRequired);
        mBuildValue.setText(getString(
                R.string.setup_build_value, audit.androidRelease, audit.fingerprint));

        mPrimaryAction.setVisibility(View.VISIBLE);
        mSecondaryAction.setVisibility(View.VISIBLE);
        mRestoreAction.setVisibility(
                audit.rootAvailable && audit.hasManagedChanges ? View.VISIBLE : View.GONE);
        mRestoreAction.setText(R.string.setup_action_restore);
        mRestoreAction.setOnClickListener(view -> confirmRestore());

        if (!audit.rootAvailable) {
            mSummary.setText(audit.rootError.isEmpty()
                    ? getString(R.string.setup_status_root_required)
                    : getString(R.string.setup_status_root_failed, audit.rootError));
            mSummary.setTextColor(COLOR_RED);
            mPrimaryAction.setText(R.string.setup_action_retry_root);
            mPrimaryAction.setOnClickListener(view -> runAudit());
            setCloseAction();
            return;
        }
        if (!audit.compatibleDevice) {
            mSummary.setText(R.string.setup_status_unsupported);
            mSummary.setTextColor(COLOR_RED);
            mPrimaryAction.setText(R.string.setup_action_recheck);
            mPrimaryAction.setOnClickListener(view -> runAudit());
            setCloseAction();
            return;
        }
        if (audit.rebootRequired
                && (audit.configurationReady || !audit.hasManagedChanges)) {
            mSummary.setText(R.string.setup_status_reboot_required);
            mSummary.setTextColor(COLOR_AMBER);
            mPrimaryAction.setText(R.string.setup_action_reboot_now);
            mPrimaryAction.setOnClickListener(view -> confirmReboot());
            mSecondaryAction.setText(R.string.setup_action_later);
            mSecondaryAction.setOnClickListener(view -> finishSetupScreen());
            return;
        }
        if (!audit.configurationReady) {
            mSummary.setText(R.string.setup_status_configuration_required);
            mSummary.setTextColor(COLOR_AMBER);
            mPrimaryAction.setText(R.string.setup_action_configure);
            mPrimaryAction.setOnClickListener(view -> configureDevice());
            setCloseAction();
            return;
        }

        mSummary.setText(audit.verifiedDevice
                ? R.string.setup_status_ready : R.string.setup_status_unverified);
        mSummary.setTextColor(audit.verifiedDevice ? COLOR_CYAN : COLOR_AMBER);
        mPrimaryAction.setText(mManual
                ? R.string.setup_action_done : R.string.setup_action_continue);
        mPrimaryAction.setOnClickListener(view -> {
            DeviceSetupManager.acknowledgeReadyConfiguration(this);
            if (mManual) {
                finish();
            } else {
                launchMagicDesk();
            }
        });
        mSecondaryAction.setText(R.string.setup_action_recheck);
        mSecondaryAction.setOnClickListener(view -> runAudit());
    }

    private void setCloseAction() {
        mSecondaryAction.setText(mManual
                ? android.R.string.cancel : R.string.setup_action_exit);
        mSecondaryAction.setOnClickListener(view -> finishSetupScreen());
    }

    private void configureDevice() {
        runOperation(
                R.string.setup_status_applying,
                () -> DeviceSetupManager.configure(getApplicationContext()));
    }

    private void confirmRestore() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.setup_restore_title)
                .setMessage(R.string.setup_restore_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.setup_action_restore,
                        (dialog, which) -> runOperation(
                                R.string.setup_status_restoring,
                                () -> DeviceSetupManager.restoreManagedChanges(
                                        getApplicationContext())))
                .show();
    }

    private void confirmReboot() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.setup_reboot_title)
                .setMessage(R.string.setup_reboot_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.setup_action_reboot_now,
                        (dialog, which) -> rebootDevice())
                .show();
    }

    private void rebootDevice() {
        setBusy(true, R.string.setup_status_rebooting);
        new Thread(() -> {
            try {
                DeviceSetupManager.reboot();
            } catch (IOException e) {
                Log.w(TAG, "reboot failed", e);
                runOnUiThread(() -> {
                    setBusy(false, 0);
                    showOperationError(e);
                    if (mAudit != null) {
                        renderAudit(mAudit);
                    }
                });
            }
        }, "MagicDeskSetupReboot").start();
    }

    private void runOperation(final int statusResId, final SetupOperation operation) {
        if (mBusy) {
            return;
        }
        setBusy(true, statusResId);
        new Thread(() -> {
            try {
                final DeviceSetupManager.Audit audit = operation.run();
                runOnUiThread(() -> {
                    if (isActivityUnavailable()) {
                        return;
                    }
                    mAudit = audit;
                    setBusy(false, 0);
                    if (!audit.canEnterMagicDesk()) {
                        DeviceSetupManager.revokeRuntimeAuthorization();
                        stopService(new Intent(this, KeyboardWatcherService.class));
                    }
                    renderAudit(audit);
                });
            } catch (IOException e) {
                Log.w(TAG, "setup operation failed", e);
                runOnUiThread(() -> {
                    if (isActivityUnavailable()) {
                        return;
                    }
                    setBusy(false, 0);
                    showOperationError(e);
                    runAudit();
                });
            }
        }, "MagicDeskSetupOperation").start();
    }

    private void showOperationError(final IOException error) {
        final String message = error.getMessage() == null
                ? error.getClass().getSimpleName() : error.getMessage();
        final String errorCode = "SETUP-001";
        CompatibilityDiagnostics.record(
                errorCode, "Device setup failed", message, error);
        new AlertDialog.Builder(this)
                .setTitle(R.string.setup_error_title)
                .setMessage(getString(
                        R.string.setup_error_with_code, message, errorCode))
                .setNeutralButton(R.string.action_diagnostics,
                        (dialog, which) -> startActivity(
                                DiagnosticsActivity.createIntent(this)))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private void setBusy(final boolean busy, final int statusResId) {
        mBusy = busy;
        if (!mContentCreated) {
            return;
        }
        if (statusResId != 0) {
            mSummary.setText(statusResId);
            mSummary.setTextColor(COLOR_CYAN);
        }
        mPrimaryAction.setEnabled(!busy);
        mDiagnosticsAction.setEnabled(!busy);
        mSecondaryAction.setEnabled(!busy);
        mRestoreAction.setEnabled(!busy);
    }

    private void setStatusValue(
            final TextView view, final String text, final boolean ready) {
        view.setText(text);
        view.setTextColor(ready ? COLOR_CYAN : COLOR_AMBER);
    }

    private void launchMagicDesk() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[] {Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS);
            return;
        }
        launchMagicDeskAfterPermission();
    }

    @Override
    public void onRequestPermissionsResult(
            final int requestCode,
            final String[] permissions,
            final int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATIONS) {
            launchMagicDeskAfterPermission();
        }
    }

    private void launchMagicDeskAfterPermission() {
        DeviceSetupManager.authorizeRuntime();
        final Intent target = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        final String action = getIntent().getStringExtra(MainActivity.EXTRA_ACTION);
        if (action != null) {
            target.putExtra(MainActivity.EXTRA_ACTION, action);
        }
        final ActivityOptions options = ActivityOptions.makeBasic();
        setLaunchWindowingMode(options, 1);
        startActivity(target, options.toBundle());
        finish();
    }

    private static void setLaunchWindowingMode(
            final ActivityOptions options, final int windowingMode) {
        try {
            final Method method = ActivityOptions.class.getMethod(
                    "setLaunchWindowingMode", Integer.TYPE);
            method.invoke(options, Integer.valueOf(windowingMode));
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "setLaunchWindowingMode unavailable", e);
        }
    }

    private void finishSetupScreen() {
        if (mManual) {
            finish();
        } else {
            finishAndRemoveTask();
        }
    }

    private boolean isActivityUnavailable() {
        return isFinishing() || isDestroyed();
    }

    private GradientDrawable rounded(
            final int color, final int radius, final int strokeColor) {
        final GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private int dp(final int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private interface SetupOperation {
        DeviceSetupManager.Audit run() throws IOException;
    }
}
