package io.github.mekhontsev.magicdesk;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;

import java.lang.ref.WeakReference;

public final class ControlActivity extends Activity
        implements PhoneControlPanelController.Actions,
        MagicDeskSessionHost {
    private static final int REQUEST_NOTIFICATIONS = 1;
    private static final long DISPLAY_PROBE_SETTLE_MILLIS = 200L;
    private static WeakReference<ControlActivity> sActive =
            new WeakReference<>(null);

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final Runnable mDisplayProbe = this::startExternalDisplayProbe;

    private PhoneControlPanelController mPanel;
    private MagicDeskSessionController mSessionController;
    private ContentObserver mConsoleStateObserver;
    private DisplayManager mDisplayManager;
    private DisplayManager.DisplayListener mDisplayListener;
    private SessionProfile mSessionProfile;
    private PhoneControlPanelController.ExternalDisplayState
            mExternalDisplayState =
                    PhoneControlPanelController.ExternalDisplayState.CHECKING;
    private boolean mStartupAuditRunning;
    private boolean mStartupPrepared;
    private boolean mStartExternalDesktopAfterProbe;
    private int mDisplayProbeGeneration;
    private String mStatus;

    static Intent createLaunchIntent(final android.content.Context context) {
        return new Intent(context, ControlActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        synchronized (ControlActivity.class) {
            sActive = new WeakReference<>(this);
        }
        mSessionProfile = SessionProfile.fromLaunchIntent(this, getIntent());
        if (DeviceSetupManager.isRuntimeAuthorized()) {
            initializeControlPanel();
            return;
        }
        runStartupAudit();
    }

    private void runStartupAudit() {
        if (mStartupAuditRunning) {
            return;
        }
        mStartupAuditRunning = true;
        new Thread(() -> {
            final DeviceSetupManager.Audit audit = DeviceSetupManager.audit(
                    getApplicationContext(), mSessionProfile);
            if (!audit.canEnterMagicDesk()) {
                runOnUiThread(this::openDeviceSetupAfterFailedAudit);
                return;
            }
            try {
                DeviceSetupManager.ensureOverlayPermission(
                        getApplicationContext());
                runOnUiThread(() -> {
                    if (isActivityUnavailable()) {
                        return;
                    }
                    mStartupAuditRunning = false;
                    mStartupPrepared = true;
                    continueStartup();
                });
            } catch (java.io.IOException error) {
                CompatibilityDiagnostics.record(
                        "OVERLAY-002",
                        "Desktop overlay permission provisioning failed",
                        error.getMessage() == null
                                ? error.getClass().getSimpleName()
                                : error.getMessage(),
                        error);
                runOnUiThread(this::openDeviceSetupAfterFailedAudit);
            }
        }, "MagicDeskStartupAudit").start();
    }

    private void openDeviceSetupAfterFailedAudit() {
        if (isActivityUnavailable()) {
            return;
        }
        mStartupAuditRunning = false;
        DeviceSetupManager.revokeRuntimeAuthorization(this);
        final Intent setupIntent = DeviceSetupActivity.createLaunchIntent(this);
        mSessionProfile.writeToIntent(setupIntent);
        startActivity(setupIntent);
        finish();
    }

    private void continueStartup() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[] {Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS);
            return;
        }
        finishStartup();
    }

    private void finishStartup() {
        if (!mStartupPrepared || isActivityUnavailable()) {
            return;
        }
        mStartupPrepared = false;
        DeviceSetupManager.authorizeRuntime(this);
        initializeControlPanel();
    }

    @Override
    public void onRequestPermissionsResult(
            final int requestCode,
            final String[] permissions,
            final int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_NOTIFICATIONS) {
            finishStartup();
        }
    }

    private void initializeControlPanel() {
        if (mPanel != null) {
            return;
        }
        final DesktopUiFactory ui = new DesktopUiFactory(this);
        mPanel = new PhoneControlPanelController(this, ui, this);
        mSessionController = new MagicDeskSessionController(this);
        mStatus = getString(ConsoleModeState.isActive(this)
                ? R.string.control_status_console_active
                : R.string.control_status_ready);
        setContentView(mPanel.createView());
        registerConsoleStateObserver();
        registerDisplayListener();
        MagicDeskRuntimeService.start(this);
        scheduleExternalDisplayProbe(false, 0L);
        refresh();
    }

    private boolean isActivityUnavailable() {
        return isFinishing() || isDestroyed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        MagicDeskRuntimeService.refreshNotificationIfRunning();
        mStatus = getString(ConsoleModeState.isActive(this)
                ? R.string.control_status_console_active
                : R.string.control_status_ready);
        if (mPanel != null) {
            scheduleExternalDisplayProbe(false, 0L);
        }
        refresh();
    }

    @Override
    protected void onDestroy() {
        synchronized (ControlActivity.class) {
            if (sActive.get() == this) {
                sActive.clear();
            }
        }
        if (mConsoleStateObserver != null) {
            getContentResolver().unregisterContentObserver(
                    mConsoleStateObserver);
            mConsoleStateObserver = null;
        }
        mDisplayProbeGeneration++;
        mMainHandler.removeCallbacks(mDisplayProbe);
        if (mDisplayManager != null && mDisplayListener != null) {
            mDisplayManager.unregisterDisplayListener(mDisplayListener);
        }
        mDisplayListener = null;
        mDisplayManager = null;
        super.onDestroy();
    }

    static void finishActiveForMirrorTransition() {
        final ControlActivity activity;
        synchronized (ControlActivity.class) {
            activity = sActive.get();
        }
        if (activity == null || activity.isActivityUnavailable()) {
            return;
        }
        activity.runOnUiThread(activity::finishAndRemoveTask);
    }

    @Override
    public void openDesktopHere() {
        mStatus = getString(R.string.status_desktop_opening);
        refresh();
        DesktopActivity.launchOnDisplay(this, currentDisplayId());
    }

    @Override
    public void showExternalDesktop() {
        if (!ShellAccess.isReady()) {
            return;
        }
        final int consoleDisplayId =
                ConsoleModeState.activeDisplayId(this);
        if (consoleDisplayId > Display.DEFAULT_DISPLAY) {
            mStatus = getString(R.string.status_console_starting);
            refresh();
            ConsoleModeSwitcher.showMagicDesk(consoleDisplayId);
            return;
        }
        mStatus = getString(R.string.status_external_display_checking);
        scheduleExternalDisplayProbe(true, 0L);
    }

    private void startExternalDesktopAfterProbe() {
        mStatus = getString(R.string.status_console_starting);
        refresh();
        ConsoleModeSwitcher.showMagicDesk();
    }

    @Override
    public void setFillExternalDisplay(final boolean enabled) {
        ExternalDisplayLaunchSettings.setFillDisplay(this, enabled);
        refresh();
    }

    @Override
    public void setExternalOutputMode(
            final ExternalDisplayLaunchSettings.OutputMode outputMode) {
        ExternalDisplayLaunchSettings.setOutputMode(this, outputMode);
        refresh();
    }

    @Override
    public void switchToMirror() {
        if (!ShellAccess.isReady()
                || !ConsoleModeState.isActive(this)) {
            return;
        }
        mStatus = getString(R.string.status_mirror_switching);
        refresh();
        ConsoleModeSwitcher.switchToMirror(
                success -> runOnUiThread(() -> {
                    mStatus = getString(success
                            ? R.string.status_mirror_active
                            : R.string.status_mirror_failed);
                    if (!success) {
                        CompatibilityDiagnostics.record(
                                "NUBIA-CONSOLE-001",
                                mStatus,
                                "Control panel mirror transition");
                    }
                    refresh();
                }));
    }

    @Override
    public void openTouchpad() {
        mStatus = getString(R.string.status_touchpad_opening);
        refresh();
        ConsoleModeSwitcher.openTouchpad();
    }

    @Override
    public void togglePhoneScreen() {
        if (!ShellAccess.isReady()) {
            return;
        }
        final boolean screenOff =
                !ConsoleModeState.isPhoneScreenOff(this);
        mStatus = getString(R.string.status_phone_screen_applying);
        refresh();
        ConsoleModeSwitcher.setPhoneScreenOff(
                screenOff,
                success -> runOnUiThread(() -> {
                    final int result;
                    if (!success) {
                        result = R.string.status_phone_screen_failed;
                    } else if (screenOff) {
                        result = R.string.status_phone_screen_off;
                    } else {
                        result = R.string.status_phone_screen_on;
                    }
                    mStatus = getString(result);
                    if (!success) {
                        CompatibilityDiagnostics.record(
                                "NUBIA-SCREEN-001",
                                mStatus,
                                "Control panel phone screen command");
                    }
                    refresh();
                }));
    }

    @Override
    public void openDeviceSetup() {
        startActivity(DeviceSetupActivity.createManualIntent(this));
    }

    @Override
    public void openDiagnostics() {
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(currentDisplayId());
        startActivity(
                DiagnosticsActivity.createIntent(this),
                options.toBundle());
    }

    @Override
    public void exitMagicDesk() {
        mSessionController.exit();
    }

    @Override
    public Activity sessionActivity() {
        return this;
    }

    @Override
    public void showSessionStatus(final String message) {
        mStatus = message;
        refresh();
    }

    @Override
    public void showSessionError(
            final String code,
            final String message,
            final Throwable error) {
        CompatibilityDiagnostics.record(code, message, "", error);
        mStatus = message + " [" + code + "]";
        refresh();
    }

    private void refresh() {
        if (mPanel == null) {
            return;
        }
        final int consoleDisplayId =
                ConsoleModeState.activeDisplayId(this);
        final boolean consoleActive =
                consoleDisplayId > Display.DEFAULT_DISPLAY;
        final ExternalDisplayLaunchSettings.Config displayConfig =
                ExternalDisplayLaunchSettings.load(this);
        mPanel.render(new PhoneControlPanelController.State(
                consoleActive,
                consoleActive
                        && DesktopRuntimeBridge.isDesktopReadyOnDisplay(
                                consoleDisplayId),
                ShellAccess.isReady(),
                ConsoleModeState.isPhoneScreenOff(this),
                ShellAccess.isReady(),
                displayConfig.fillDisplay,
                displayConfig.outputMode,
                mExternalDisplayState,
                mStatus,
                ShellAccess.statusLabel(),
                currentDisplayId(),
                consoleDisplayId));
    }

    private void registerConsoleStateObserver() {
        mConsoleStateObserver = new ContentObserver(
                mMainHandler) {
            @Override
            public void onChange(final boolean selfChange) {
                mStatus = getString(ConsoleModeState.isActive(
                        ControlActivity.this)
                        ? R.string.control_status_console_active
                        : R.string.control_status_ready);
                scheduleExternalDisplayProbe(
                        false, DISPLAY_PROBE_SETTLE_MILLIS);
                refresh();
            }
        };
        getContentResolver().registerContentObserver(
                android.provider.Settings.Global.getUriFor(
                        ConsoleModeState.DISPLAY_ID_SETTING),
                false,
                mConsoleStateObserver);
        getContentResolver().registerContentObserver(
                android.provider.Settings.Global.getUriFor(
                        ConsoleModeState.PHONE_SCREEN_OFF_SETTING),
                false,
                mConsoleStateObserver);
    }

    private void registerDisplayListener() {
        mDisplayManager = getSystemService(DisplayManager.class);
        if (mDisplayManager == null) {
            mExternalDisplayState =
                    PhoneControlPanelController.ExternalDisplayState.DISCONNECTED;
            return;
        }
        mDisplayListener = new DisplayManager.DisplayListener() {
            @Override
            public void onDisplayAdded(final int displayId) {
                onExternalDisplayEvent(displayId);
            }

            @Override
            public void onDisplayRemoved(final int displayId) {
                onExternalDisplayEvent(displayId);
            }

            @Override
            public void onDisplayChanged(final int displayId) {
                onExternalDisplayEvent(displayId);
            }
        };
        mDisplayManager.registerDisplayListener(
                mDisplayListener, mMainHandler);
    }

    private void onExternalDisplayEvent(final int displayId) {
        if (displayId > Display.DEFAULT_DISPLAY) {
            scheduleExternalDisplayProbe(
                    false, DISPLAY_PROBE_SETTLE_MILLIS);
        }
    }

    private void scheduleExternalDisplayProbe(
            final boolean startWhenConnected,
            final long delayMillis) {
        if (startWhenConnected) {
            mStartExternalDesktopAfterProbe = true;
        }
        if (!ShellAccess.isReady()) {
            mExternalDisplayState =
                    PhoneControlPanelController.ExternalDisplayState.DISCONNECTED;
            if (mStartExternalDesktopAfterProbe) {
                mStartExternalDesktopAfterProbe = false;
                mStatus = getString(
                        R.string.status_external_display_unavailable);
            }
            refresh();
            return;
        }
        mExternalDisplayState =
                PhoneControlPanelController.ExternalDisplayState.CHECKING;
        mDisplayProbeGeneration++;
        mMainHandler.removeCallbacks(mDisplayProbe);
        mMainHandler.postDelayed(mDisplayProbe, delayMillis);
        refresh();
    }

    private void startExternalDisplayProbe() {
        final int generation = mDisplayProbeGeneration;
        ConsoleModeSwitcher.probeExternalDisplay(connected ->
                runOnUiThread(() -> finishExternalDisplayProbe(
                        generation, connected)));
    }

    private void finishExternalDisplayProbe(
            final int generation,
            final boolean connected) {
        if (generation != mDisplayProbeGeneration
                || isActivityUnavailable()) {
            return;
        }
        mExternalDisplayState = connected
                ? PhoneControlPanelController.ExternalDisplayState.CONNECTED
                : PhoneControlPanelController.ExternalDisplayState.DISCONNECTED;
        final boolean shouldStart = mStartExternalDesktopAfterProbe;
        mStartExternalDesktopAfterProbe = false;
        if (shouldStart && connected) {
            startExternalDesktopAfterProbe();
            return;
        }
        if (shouldStart) {
            mStatus = getString(R.string.status_external_display_unavailable);
        }
        refresh();
    }

    private int currentDisplayId() {
        final Display display = getDisplay();
        return display == null
                ? Display.DEFAULT_DISPLAY : display.getDisplayId();
    }
}
