package io.github.mekhontsev.magicdesk;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;

public final class DeviceSetupActivity extends Activity {
    private static final String TAG = "MagicDeskSetup";
    private static final String EXTRA_MANUAL = "manual_setup";
    private static final int REQUEST_NOTIFICATIONS = 1;
    private static final int COLOR_CYAN = DesktopUiFactory.COLOR_CYAN;
    private static final int COLOR_RED = DesktopUiFactory.COLOR_RED;
    private static final int COLOR_AMBER = DesktopUiFactory.COLOR_AMBER;

    private DeviceSetupView mSetupView;
    private boolean mManual;
    private boolean mBusy;
    private boolean mContentCreated;
    private boolean mAwaitingOverlayPermission;
    private DeviceSetupManager.Audit mAudit;
    private SessionProfile mSessionProfile;
    private final ShellAccess.StateListener mShellStateListener =
            state -> handleShellStateChanged();

    static Intent createLaunchIntent(final Context context) {
        return new Intent(context, DeviceSetupActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    static Intent createManualIntent(final Context context) {
        return new Intent(context, DeviceSetupActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
                .putExtra(EXTRA_MANUAL, true);
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mSessionProfile = SessionProfile.fromLaunchIntent(this, getIntent());
        mManual = getIntent().getBooleanExtra(EXTRA_MANUAL, false);
        mSetupView = new DeviceSetupView(this);
        if (mManual) {
            ensureSetupContent();
        }
        ShellAccess.addStateListener(mShellStateListener);
        runAudit();
    }

    @Override
    protected void onDestroy() {
        ShellAccess.removeStateListener(mShellStateListener);
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        mSessionProfile = SessionProfile.fromLaunchIntent(this, intent);
        mManual = intent.getBooleanExtra(EXTRA_MANUAL, false);
        if (mManual) {
            ensureSetupContent();
        }
        if (mContentCreated) {
            mSetupView.setDetailed(mManual);
        }
        runAudit();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mContentCreated && !mBusy) {
            runAudit();
        }
    }

    private void ensureSetupContent() {
        if (mContentCreated) {
            return;
        }
        setContentView(mSetupView.create());
        mContentCreated = true;
        mSetupView.setDetailed(mManual);
    }

    void showDisplayTargetChooser(final View ignored) {
        final SessionProfile.DisplayTarget[] targets = {
                SessionProfile.DisplayTarget.AUTO,
                SessionProfile.DisplayTarget.PRIMARY,
                SessionProfile.DisplayTarget.CURRENT,
                SessionProfile.DisplayTarget.EXTERNAL
        };
        final String[] labels = {
                getString(R.string.setup_display_auto),
                getString(R.string.setup_display_primary),
                getString(R.string.setup_display_current),
                getString(R.string.setup_display_external)
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.setup_choose_display_target)
                .setSingleChoiceItems(
                        labels,
                        indexOf(targets, mSessionProfile.displayTarget),
                        (dialog, which) -> {
                            mSessionProfile =
                                    mSessionProfile.withDisplayTarget(targets[which]);
                            mSessionProfile.save(this);
                            dialog.dismiss();
                            renderProfileSelection();
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static <T> int indexOf(final T[] values, final T target) {
        for (int index = 0; index < values.length; index++) {
            if (values[index] == target) {
                return index;
            }
        }
        return 0;
    }

    private void runAudit() {
        if (mBusy) {
            return;
        }
        setBusy(true, R.string.setup_status_checking);
        new Thread(() -> {
            final DeviceSetupManager.Audit audit =
                    DeviceSetupManager.audit(getApplicationContext(), mSessionProfile);
            runOnUiThread(() -> {
                if (isActivityUnavailable()) {
                    return;
                }
                DeviceSetupManager.activateRuntime(this, audit);
                mAudit = audit;
                if (!mManual && audit.canEnterMagicDesk()) {
                    mBusy = false;
                    startMagicDesk();
                    return;
                }
                if (!audit.canEnterMagicDesk()) {
                    DeviceSetupManager.revokeRuntimeAuthorization(this);
                }
                ensureSetupContent();
                setBusy(false, 0);
                renderAudit(audit);
            });
        }, "MagicDeskSetupAudit").start();
    }

    private void renderAudit(final DeviceSetupManager.Audit audit) {
        mSetupView.setDetailed(mManual);
        renderProfileSelection();
        setStatusValue(mSetupView.deviceValue(),
                audit.compatibleDevice
                        ? getString(deviceSupportLabel(audit.firmwareSupport),
                                audit.manufacturer, audit.model)
                        : getString(R.string.setup_value_unsupported,
                                audit.manufacturer, audit.model),
                audit.compatibleDevice);
        setStatusValue(
                mSetupView.shizukuValue(),
                getString(audit.shellReady
                        ? R.string.setup_value_available
                        : R.string.setup_value_unavailable),
                audit.shellReady);
        final boolean overlaysGranted = Settings.canDrawOverlays(this);
        setStatusValue(
                mSetupView.overlayValue(),
                getString(overlaysGranted
                        ? R.string.setup_value_allowed
                        : R.string.setup_value_permission_required),
                overlaysGranted);
        setStatusValue(mSetupView.restrictionsValue(),
                getString(audit.restrictionsDisabled
                        ? R.string.setup_value_enabled : R.string.setup_value_disabled),
                audit.restrictionsDisabled);
        setStatusValue(mSetupView.cornersValue(),
                getString(audit.roundedCornersDisabled
                        ? R.string.setup_value_square : R.string.setup_value_rounded),
                audit.roundedCornersDisabled);
        setStatusValue(mSetupView.rebootValue(),
                getString(audit.rebootRequired
                        ? R.string.setup_value_required : R.string.setup_value_not_required),
                !audit.rebootRequired);
        mSetupView.buildValue().setText(getString(
                R.string.setup_build_value, audit.androidRelease, audit.fingerprint));

        mSetupView.primaryAction().setVisibility(View.VISIBLE);
        mSetupView.setSecondaryActionsVisible(
                mManual || audit.shellReady,
                audit.shellReady);
        mSetupView.restoreAction().setText(R.string.setup_action_restore);
        mSetupView.restoreAction().setEnabled(audit.shellReady);
        mSetupView.restoreAction().setOnClickListener(view -> confirmRestore());

        if (!audit.shellState.running) {
            mSetupView.summary().setText(audit.shellState.installed
                    ? R.string.setup_status_shizuku_stopped
                    : R.string.setup_status_shizuku_not_installed);
            mSetupView.summary().setTextColor(COLOR_AMBER);
            mSetupView.primaryAction().setText(audit.shellState.installed
                    ? R.string.setup_action_open_shizuku
                    : R.string.setup_action_get_shizuku);
            mSetupView.primaryAction().setOnClickListener(
                    view -> ShellAccess.openManagerOrWebsite(this));
            setCloseAction();
            return;
        }
        if (!audit.shellState.permissionGranted) {
            mSetupView.summary().setText(R.string.setup_status_shizuku_permission);
            mSetupView.summary().setTextColor(COLOR_AMBER);
            mSetupView.primaryAction().setText(R.string.setup_action_allow_shizuku);
            mSetupView.primaryAction().setOnClickListener(
                    view -> requestShizukuPermission());
            setCloseAction();
            return;
        }
        if (!audit.shellReady) {
            mSetupView.summary().setText(getString(
                    R.string.setup_status_shizuku_failed,
                    audit.runtimeError));
            mSetupView.summary().setTextColor(COLOR_RED);
            mSetupView.primaryAction().setText(R.string.setup_action_recheck);
            mSetupView.primaryAction().setOnClickListener(view -> runAudit());
            setCloseAction();
            return;
        }
        if (!audit.compatibleDevice) {
            mSetupView.summary().setText(R.string.setup_status_unsupported);
            mSetupView.summary().setTextColor(COLOR_RED);
            mSetupView.primaryAction().setText(R.string.setup_action_recheck);
            mSetupView.primaryAction().setOnClickListener(view -> runAudit());
            setCloseAction();
            return;
        }
        if (audit.rebootRequired) {
            mSetupView.summary().setText(R.string.setup_status_reboot_required);
            mSetupView.summary().setTextColor(COLOR_AMBER);
            mSetupView.primaryAction().setText(R.string.setup_action_reboot_now);
            mSetupView.primaryAction().setOnClickListener(view -> confirmReboot());
            mSetupView.secondaryAction().setText(R.string.setup_action_later);
            mSetupView.secondaryAction().setOnClickListener(view -> finishSetupScreen());
            return;
        }
        if (!audit.configurationReady && audit.shellReady) {
            mSetupView.summary().setText(
                    R.string.setup_status_configuration_required);
            mSetupView.summary().setTextColor(COLOR_AMBER);
            mSetupView.primaryAction().setText(
                    R.string.setup_action_configure);
            mSetupView.primaryAction().setOnClickListener(
                    view -> configureDevice());
            setCloseAction();
            return;
        }
        if (mAwaitingOverlayPermission && overlaysGranted) {
            mAwaitingOverlayPermission = false;
            startMagicDesk();
            return;
        }
        mAwaitingOverlayPermission = false;

        mSetupView.summary().setText(mManual
                ? getString(
                        R.string.setup_status_shizuku_ready,
                        audit.shellState.uid)
                : getString(R.string.setup_status_ready));
        mSetupView.summary().setTextColor(COLOR_CYAN);
        mSetupView.primaryAction().setText(mManual
                ? R.string.setup_action_done : R.string.setup_action_continue);
        mSetupView.primaryAction().setOnClickListener(view -> startMagicDesk());
        mSetupView.secondaryAction().setText(R.string.setup_action_recheck);
        mSetupView.secondaryAction().setOnClickListener(view -> runAudit());
    }

    private void setCloseAction() {
        mSetupView.secondaryAction().setText(mManual
                ? android.R.string.cancel : R.string.setup_action_exit);
        mSetupView.secondaryAction().setOnClickListener(view -> finishSetupScreen());
    }

    private void renderProfileSelection() {
        if (mSetupView.displayTargetValue() != null) {
            mSetupView.displayTargetValue().setText(displayTargetLabel(
                    mSessionProfile.displayTarget));
            mSetupView.displayTargetValue().setTextColor(COLOR_CYAN);
        }
    }

    private static int deviceSupportLabel(
            final DeviceSetupManager.FirmwareSupport support) {
        switch (support) {
            case MAINTAINER_VERIFIED:
                return R.string.setup_value_supported;
            case COMMUNITY_TESTED:
                return R.string.setup_value_supported_community;
            case UNVERIFIED:
            default:
                return R.string.setup_value_supported_unverified;
        }
    }

    private int displayTargetLabel(final SessionProfile.DisplayTarget target) {
        switch (target) {
            case PRIMARY:
                return R.string.setup_display_primary;
            case CURRENT:
                return R.string.setup_display_current;
            case EXTERNAL:
                return R.string.setup_display_external;
            case AUTO:
            default:
                return R.string.setup_display_auto;
        }
    }

    private void configureDevice() {
        runOperation(
                R.string.setup_status_applying,
                () -> DeviceSetupManager.configure(
                        getApplicationContext(), mSessionProfile));
    }

    private void startMagicDesk() {
        if (mBusy) {
            return;
        }
        if (Settings.canDrawOverlays(this)) {
            continueFromSetup();
            return;
        }
        setBusy(true, R.string.setup_status_starting);
        new Thread(() -> {
            try {
                DeviceSetupManager.ensureOverlayPermission(
                        getApplicationContext());
                runOnUiThread(() -> {
                    if (isActivityUnavailable()) {
                        return;
                    }
                    setBusy(false, 0);
                    continueFromSetup();
                });
            } catch (IOException error) {
                Log.w(TAG, "automatic overlay provisioning failed", error);
                runOnUiThread(() -> {
                    if (isActivityUnavailable()) {
                        return;
                    }
                    ensureSetupContent();
                    setBusy(false, 0);
                    if (mAudit != null) {
                        renderAudit(mAudit);
                    }
                    showOverlayPermissionError(error);
                });
            }
        }, "MagicDeskOverlaySetup").start();
    }

    private void continueFromSetup() {
        if (mManual) {
            DeviceSetupManager.authorizeRuntime(this);
            final int currentDisplayId = currentDisplayId();
            if (resolveLaunchDisplayId() == currentDisplayId
                    && DesktopRuntimeBridge.recreateShellOnDisplay(
                            currentDisplayId)) {
                finish();
            } else {
                launchMagicDesk();
            }
        } else {
            launchMagicDesk();
        }
    }

    private void openOverlayPermission() {
        mAwaitingOverlayPermission = true;
        final Intent intent = new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        try {
            startActivity(intent);
        } catch (RuntimeException error) {
            Log.w(TAG, "could not open overlay permission settings", error);
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION));
        }
    }

    private void showOverlayPermissionError(final IOException error) {
        final String message = error.getMessage() == null
                ? error.getClass().getSimpleName() : error.getMessage();
        final String errorCode = "OVERLAY-002";
        CompatibilityDiagnostics.record(
                errorCode,
                "Desktop overlay permission provisioning failed",
                message,
                error);
        new AlertDialog.Builder(this)
                .setTitle(R.string.setup_overlay_error_title)
                .setMessage(getString(
                        R.string.setup_overlay_error_message,
                        message,
                        errorCode))
                .setNeutralButton(R.string.action_diagnostics,
                        (dialog, which) -> startActivity(
                                DiagnosticsActivity.createIntent(this)))
                .setPositiveButton(R.string.setup_action_open_settings,
                        (dialog, which) -> openOverlayPermission())
                .show();
    }

    private void requestShizukuPermission() {
        try {
            ShellAccess.requestPermission();
        } catch (RuntimeException error) {
            Log.w(TAG, "could not request Shizuku permission", error);
            Toast.makeText(
                    this,
                    getString(
                            R.string.setup_status_shizuku_failed,
                            error.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void handleShellStateChanged() {
        runOnUiThread(() -> {
            if (!isActivityUnavailable() && mSessionProfile != null) {
                runAudit();
            }
        });
    }

    private void confirmRestore() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.setup_restore_title)
                .setMessage(R.string.setup_restore_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.setup_action_restore,
                        (dialog, which) -> runOperation(
                                R.string.setup_status_restoring,
                                () -> DeviceSetupManager.restoreNubiaDefaults(
                                        getApplicationContext(),
                                        mSessionProfile)))
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
                    DeviceSetupManager.activateRuntime(this, audit);
                    mAudit = audit;
                    setBusy(false, 0);
                    if (!audit.canEnterMagicDesk()) {
                        DeviceSetupManager.revokeRuntimeAuthorization(this);
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
            mSetupView.summary().setText(statusResId);
            mSetupView.summary().setTextColor(COLOR_CYAN);
        }
        mSetupView.primaryAction().setEnabled(!busy);
        mSetupView.diagnosticsAction().setEnabled(!busy);
        mSetupView.secondaryAction().setEnabled(!busy);
        mSetupView.restoreAction().setEnabled(!busy);
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
        DeviceSetupManager.authorizeRuntime(this);
        final int currentDisplayId = currentDisplayId();
        final boolean phoneControl =
                currentDisplayId == Display.DEFAULT_DISPLAY;
        final int launchDisplayId = phoneControl
                ? Display.DEFAULT_DISPLAY : resolveLaunchDisplayId();
        final Class<?> activityClass = phoneControl
                ? ControlActivity.class : DesktopActivity.class;
        final Intent target = new Intent(this, activityClass)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (launchDisplayId != currentDisplayId) {
            target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        }
        mSessionProfile.writeToIntent(target);
        final String action = getIntent().getStringExtra(DesktopShellActivity.EXTRA_ACTION);
        if (action != null) {
            target.putExtra(DesktopShellActivity.EXTRA_ACTION, action);
        }
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(launchDisplayId);
        startActivity(target, options.toBundle());
        finishAndRemoveTask();
    }

    private int currentDisplayId() {
        return getDisplay() == null
                ? Display.DEFAULT_DISPLAY : getDisplay().getDisplayId();
    }

    private int resolveLaunchDisplayId() {
        final int currentDisplayId = currentDisplayId();
        switch (mSessionProfile.displayTarget) {
            case PRIMARY:
                return Display.DEFAULT_DISPLAY;
            case CURRENT:
                return currentDisplayId;
            case EXTERNAL: {
                final int externalDisplayId = activeExternalDisplayId();
                if (externalDisplayId > Display.DEFAULT_DISPLAY) {
                    return externalDisplayId;
                }
                Toast.makeText(
                        this,
                        R.string.setup_status_external_unavailable,
                        Toast.LENGTH_LONG).show();
                return currentDisplayId;
            }
            case AUTO:
            default: {
                final int consoleDisplayId = activeConsoleDisplayId();
                return consoleDisplayId > Display.DEFAULT_DISPLAY
                        ? consoleDisplayId : currentDisplayId;
            }
        }
    }

    private int activeExternalDisplayId() {
        final int consoleDisplayId = activeConsoleDisplayId();
        if (consoleDisplayId > Display.DEFAULT_DISPLAY) {
            return consoleDisplayId;
        }
        final android.hardware.display.DisplayManager displayManager =
                getSystemService(android.hardware.display.DisplayManager.class);
        if (displayManager == null) {
            return -1;
        }
        final Display[] displays = displayManager.getDisplays(
                android.hardware.display.DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        for (final Display display : displays) {
            if (display != null && display.getDisplayId() > Display.DEFAULT_DISPLAY) {
                return display.getDisplayId();
            }
        }
        return -1;
    }

    private int activeConsoleDisplayId() {
        final int configured = Settings.Global.getInt(
                getContentResolver(), "app_mirror_displayid", -1);
        final android.hardware.display.DisplayManager displayManager =
                getSystemService(android.hardware.display.DisplayManager.class);
        if (configured > Display.DEFAULT_DISPLAY
                && displayManager != null
                && displayManager.getDisplay(configured) != null) {
            return configured;
        }
        return -1;
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

    private interface SetupOperation {
        DeviceSetupManager.Audit run() throws IOException;
    }
}
