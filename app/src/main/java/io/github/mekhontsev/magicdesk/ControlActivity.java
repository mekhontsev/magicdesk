package io.github.mekhontsev.magicdesk;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.hardware.display.DeviceProductInfo;
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
    private final PlatformProjectionDriver mProjection =
            PlatformDrivers.current().projection();
    private final PlatformPhoneUiDriver mPhoneUi =
            PlatformDrivers.current().phoneUi();

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
    private boolean mReturnToPanelAfterWirelessConnection;
    private boolean mWirelessConnectionUiAvailable;
    private int mDisplayProbeGeneration;
    private int mWiredDisplayId = Display.INVALID_DISPLAY;
    private int mWirelessDisplayId = Display.INVALID_DISPLAY;
    private DisplayProfileStore.Profile mExternalDisplayProfile;
    private PlatformProjectionDriver.ModeSelection mExternalModeSelection;
    private String mExternalDisplaySummary;
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
            try {
                final DeviceSetupManager.Audit audit = DeviceSetupManager.audit(
                        getApplicationContext(), mSessionProfile);
                if (!audit.canEnterMagicDesk()) {
                    runOnUiThread(this::openDeviceSetupAfterFailedAudit);
                    return;
                }
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
            } catch (java.io.IOException | RuntimeException error) {
                CompatibilityDiagnostics.record(
                        "SETUP-002",
                        "MagicDesk startup audit failed",
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
        mWirelessConnectionUiAvailable =
                mProjection.hasWirelessConnectionUi(this);
        mStatus = getString(isExternalDesktopActive()
                ? R.string.control_status_console_active
                : R.string.control_status_ready);
        setContentView(mPanel.createView());
        if (mProjection.observedSettingKeys().length > 0
                || mPhoneUi.observedSettingKeys().length > 0) {
            registerConsoleStateObserver();
        }
        if (DesktopDisplayDrivers.isExternalDesktopSupported()) {
            registerDisplayListener();
        }
        MagicDeskRuntime.start(this);
        if (DesktopDisplayDrivers.isExternalDesktopSupported()) {
            scheduleExternalDisplayProbe(false, 0L);
        } else {
            mExternalDisplayState =
                    PhoneControlPanelController.ExternalDisplayState.DISCONNECTED;
        }
        refresh();
    }

    private boolean isActivityUnavailable() {
        return isFinishing() || isDestroyed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Returning from a cancelled picker already leaves the panel visible.
        mReturnToPanelAfterWirelessConnection = false;
        MagicDeskRuntime.refreshNotification();
        mStatus = getString(isExternalDesktopActive()
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
        final int displayId = currentDisplayId();
        final DesktopDisplayTarget target = displayId == Display.DEFAULT_DISPLAY
                ? DesktopDisplayTarget.phone()
                : DesktopRuntimeBridge.getDesktopTarget(displayId);
        if (target == null) {
            mStatus = getString(R.string.status_external_display_unavailable);
            refresh();
            return;
        }
        if (!DesktopDisplayDrivers.isSupported(target.kind)) {
            mStatus = getString(R.string.status_external_display_unavailable);
            refresh();
            return;
        }
        DesktopDisplayDrivers.forTarget(target).show(this, displayId);
    }

    @Override
    public void showExternalDesktop() {
        if (!ShellAccess.isReady()
                || !DesktopDisplayDrivers.isExternalDesktopSupported()) {
            mStatus = getString(R.string.status_external_display_unavailable);
            refresh();
            return;
        }
        final int consoleDisplayId =
                mProjection.activeDesktopDisplayId(this);
        if (consoleDisplayId > Display.DEFAULT_DISPLAY) {
            mStatus = getString(R.string.status_console_starting);
            refresh();
            ConsoleModeSwitcher.showMagicDesk(consoleDisplayId);
            return;
        }
        final int activeDesktopDisplayId =
                DesktopRuntimeBridge.getActiveDesktopDisplayId();
        if (activeDesktopDisplayId > Display.DEFAULT_DISPLAY) {
            final DesktopDisplayTarget target =
                    DesktopRuntimeBridge.getDesktopTarget(
                            activeDesktopDisplayId);
            if (target != null) {
                mStatus = getString(R.string.status_console_starting);
                refresh();
                ConsoleModeSwitcher.showDesktop(target);
                return;
            }
        }
        if (mWiredDisplayId > Display.DEFAULT_DISPLAY) {
            mStatus = getString(R.string.status_external_display_checking);
            scheduleExternalDisplayProbe(true, 0L);
            return;
        }
        if (isDisplayConnected(mWirelessDisplayId)) {
            launchWirelessDesktop(mWirelessDisplayId);
            return;
        }
        mStatus = getString(R.string.status_external_display_unavailable);
        refresh();
    }

    @Override
    public void connectWirelessDisplay() {
        if (!mWirelessConnectionUiAvailable
                || isExternalDesktopActive()
                || isDisplayConnected(mWirelessDisplayId)) {
            mStatus = getString(R.string.status_external_display_unavailable);
            refresh();
            return;
        }
        mReturnToPanelAfterWirelessConnection = true;
        if (mProjection.openWirelessConnectionUi(this)) {
            mStatus = getString(R.string.status_wireless_display_connecting);
        } else {
            mReturnToPanelAfterWirelessConnection = false;
            mStatus = getString(R.string.status_external_display_unavailable);
        }
        refresh();
    }

    private void launchWirelessDesktop(final int displayId) {
        mExternalDisplayProfile = null;
        mExternalModeSelection = null;
        mExternalDisplaySummary = describeExternalDisplay(displayId, null);
        mStatus = getString(R.string.status_wireless_desktop_starting);
        refresh();
        ConsoleModeSwitcher.showDesktop(
                DesktopDisplayTarget.wireless(displayId));
    }

    private void startExternalDesktopAfterProbe() {
        mStatus = getString(R.string.status_console_starting);
        refresh();
        if (mWiredDisplayId > Display.DEFAULT_DISPLAY) {
            ConsoleModeSwitcher.showMagicDesk();
        } else if (mWirelessDisplayId > Display.DEFAULT_DISPLAY) {
            ConsoleModeSwitcher.showDesktop(
                    DesktopDisplayTarget.wireless(mWirelessDisplayId));
        }
    }

    @Override
    public void setFillExternalDisplay(final boolean enabled) {
        if (mExternalDisplayProfile == null) {
            return;
        }
        mExternalDisplayProfile.fillDisplay = enabled;
        DisplayProfileStore.save(mExternalDisplayProfile);
        refresh();
    }

    @Override
    public void setExternalOutputTiming(final String outputTiming) {
        if (mExternalDisplayProfile == null) {
            return;
        }
        final DisplayProfileStore.Profile profile = mExternalDisplayProfile;
        DisplayProfileStore.setOutputTiming(
                profile, outputTiming);
        DisplayProfileStore.save(profile);
        if (mExternalModeSelection != null) {
            mExternalModeSelection =
                    mExternalModeSelection.withPreferredTiming(
                            profile.outputTiming);
        }
        refresh();
        if (!profile.resetOutputModePending
                || mWiredDisplayId <= Display.DEFAULT_DISPLAY) {
            return;
        }
        final int displayId = mWiredDisplayId;
        ConsoleModeSwitcher.executeSerialized(() -> {
            // Release MagicDesk's previous explicit mode immediately. A later
            // SmartCast choice must not be erased when the desktop starts.
            if (!profile.resetOutputModePending
                    || profile.outputTiming != null) {
                return;
            }
            try {
                mProjection.releaseExternalDisplayMode(displayId);
                profile.resetOutputModePending = false;
                DisplayProfileStore.save(profile);
            } catch (final java.io.IOException ignored) {
                // prepareExternalDisplay() retries while the display exists.
            }
            runOnUiThread(this::refresh);
        });
    }

    @Override
    public void closeDesktop() {
        if (!ShellAccess.isReady()) {
            return;
        }
        DesktopDisplayTarget target =
                DesktopRuntimeBridge.getActiveDesktopTarget();
        if (target == null) {
            final int displayId = mProjection.activeDesktopDisplayId(this);
            if (displayId > Display.DEFAULT_DISPLAY) {
                // A platform-owned wired session may be active before its
                // desktop activity has registered the complete target.
                target = DesktopDisplayTarget.wired(displayId);
            }
        }
        if (target == null
                || target.displayId <= Display.DEFAULT_DISPLAY) {
            mStatus = getString(R.string.status_external_display_unavailable);
            refresh();
            return;
        }
        mSessionController.closeDesktop(target);
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
                !mPhoneUi.isPhoneScreenOff(this);
        mStatus = getString(R.string.status_phone_screen_applying);
        refresh();
        ConsoleModeSwitcher.setPhoneScreenOff(
                screenOff,
                success -> runOnUiThread(() -> {
                    if (isActivityUnavailable()) {
                        return;
                    }
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
                                "PHONE-SCREEN-001",
                                mStatus,
                                "Control panel phone screen command");
                    }
                    refresh();
                }));
    }

    @Override
    public void openSettings() {
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(currentDisplayId());
        startActivity(SettingsActivity.createIntent(this), options.toBundle());
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
                mProjection.activeDesktopDisplayId(this);
        final boolean consoleModeActive =
                consoleDisplayId > Display.DEFAULT_DISPLAY;
        final int activeDesktopDisplayId =
                DesktopRuntimeBridge.getActiveDesktopDisplayId();
        final DesktopDisplayTarget activeTarget =
                DesktopRuntimeBridge.getDesktopTarget(activeDesktopDisplayId);
        final boolean externalRuntimeDesktop = activeTarget != null
                && (activeTarget.kind == DesktopDisplayTarget.Kind.WIRED
                        || activeTarget.kind
                                == DesktopDisplayTarget.Kind.WIRELESS);
        final boolean externalDesktopActive =
                consoleModeActive
                        || externalRuntimeDesktop;
        final int externalDesktopDisplayId = consoleModeActive
                ? consoleDisplayId : activeDesktopDisplayId;
        mPanel.render(new PhoneControlPanelController.State(
                externalDesktopActive,
                consoleModeActive,
                activeDesktopDisplayId > Display.DEFAULT_DISPLAY
                        && DesktopRuntimeBridge.isDesktopReadyOnDisplay(
                                activeDesktopDisplayId),
                ShellAccess.isReady()
                        && DesktopDisplayDrivers.isExternalDesktopSupported(),
                mPhoneUi.isPhoneScreenOff(this),
                ShellAccess.isReady() && mPhoneUi.isAvailable(),
                PlatformDrivers.current().pointer().isAvailable(),
                mProjection.supportsOutputConfiguration(),
                mExternalDisplayProfile == null
                        || mExternalDisplayProfile.fillDisplay,
                mExternalModeSelection,
                mExternalDisplaySummary,
                mExternalDisplayState,
                mWiredDisplayId > Display.DEFAULT_DISPLAY,
                mWirelessConnectionUiAvailable,
                mWirelessDisplayId > Display.DEFAULT_DISPLAY,
                mStatus,
                ShellAccess.statusLabel(),
                currentDisplayId(),
                externalDesktopDisplayId));
    }

    private void registerConsoleStateObserver() {
        mConsoleStateObserver = new ContentObserver(
                mMainHandler) {
            @Override
            public void onChange(final boolean selfChange) {
                mStatus = getString(isExternalDesktopActive()
                        ? R.string.control_status_console_active
                        : R.string.control_status_ready);
                scheduleExternalDisplayProbe(
                        false, DISPLAY_PROBE_SETTLE_MILLIS);
                refresh();
            }
        };
        for (final String setting : mProjection.observedSettingKeys()) {
            getContentResolver().registerContentObserver(
                    android.provider.Settings.Global.getUriFor(setting),
                    false,
                    mConsoleStateObserver);
        }
        for (final String setting : mPhoneUi.observedSettingKeys()) {
            getContentResolver().registerContentObserver(
                    android.provider.Settings.Global.getUriFor(setting),
                    false,
                    mConsoleStateObserver);
        }
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
        if (!ShellAccess.isReady()
                || !DesktopDisplayDrivers.isExternalDesktopSupported()) {
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
        ConsoleModeSwitcher.probeExternalDisplay((
                wiredDisplayId, wirelessDisplayId, profile, selection) ->
                runOnUiThread(() -> finishExternalDisplayProbe(
                        generation,
                        wiredDisplayId,
                        wirelessDisplayId,
                        profile,
                        selection)));
    }

    private void finishExternalDisplayProbe(
            final int generation,
            final int wiredDisplayId,
            final int wirelessDisplayId,
            final DisplayProfileStore.Profile displayProfile,
            final PlatformProjectionDriver.ModeSelection selection) {
        if (generation != mDisplayProbeGeneration
                || isActivityUnavailable()) {
            return;
        }
        mWiredDisplayId = wiredDisplayId;
        mWirelessDisplayId = wirelessDisplayId;
        final boolean wiredConnected =
                wiredDisplayId > Display.DEFAULT_DISPLAY;
        final boolean wirelessConnected =
                wirelessDisplayId > Display.DEFAULT_DISPLAY;
        final boolean connected = wiredConnected || wirelessConnected;
        final int activeDesktopDisplayId =
                DesktopRuntimeBridge.getActiveDesktopDisplayId();
        final int selectedDisplayId = wirelessConnected
                && activeDesktopDisplayId == wirelessDisplayId
                ? wirelessDisplayId
                : wiredConnected ? wiredDisplayId : wirelessDisplayId;
        mExternalDisplayProfile = selectedDisplayId == wiredDisplayId
                ? displayProfile : null;
        mExternalModeSelection = selectedDisplayId == wiredDisplayId
                ? selection : null;
        mExternalDisplaySummary = connected
                ? describeExternalDisplay(
                        selectedDisplayId, mExternalModeSelection)
                : null;
        mExternalDisplayState = connected
                ? PhoneControlPanelController.ExternalDisplayState.CONNECTED
                : PhoneControlPanelController.ExternalDisplayState.DISCONNECTED;
        final boolean shouldStart = mStartExternalDesktopAfterProbe;
        mStartExternalDesktopAfterProbe = false;
        final boolean shouldReturnToPanel =
                mReturnToPanelAfterWirelessConnection && wirelessConnected;
        if (shouldReturnToPanel) {
            mReturnToPanelAfterWirelessConnection = false;
            mStatus = getString(R.string.control_status_ready);
            refresh();
            PhoneControlPanelLauncher.open(this);
            return;
        }
        if (shouldStart && connected) {
            startExternalDesktopAfterProbe();
            return;
        }
        if (shouldStart) {
            mStatus = getString(R.string.status_external_display_unavailable);
        }
        refresh();
    }

    private boolean isDisplayConnected(final int displayId) {
        return displayId > Display.DEFAULT_DISPLAY
                && mDisplayManager != null
                && mDisplayManager.getDisplay(displayId) != null;
    }

    private boolean isExternalDesktopActive() {
        if (mProjection.activeDesktopDisplayId(this)
                > Display.DEFAULT_DISPLAY) {
            return true;
        }
        final int displayId =
                DesktopRuntimeBridge.getActiveDesktopDisplayId();
        final DesktopDisplayTarget target =
                DesktopRuntimeBridge.getDesktopTarget(displayId);
        return target != null
                && (target.kind == DesktopDisplayTarget.Kind.WIRED
                        || target.kind
                                == DesktopDisplayTarget.Kind.WIRELESS);
    }

    private String describeExternalDisplay(
            final int displayId,
            final PlatformProjectionDriver.ModeSelection selection) {
        String name = null;
        final Display display = mDisplayManager == null
                ? null : mDisplayManager.getDisplay(displayId);
        if (display != null) {
            final DeviceProductInfo productInfo =
                    display.getDeviceProductInfo();
            if (productInfo != null && productInfo.getName() != null) {
                name = productInfo.getName().toString().trim();
            }
            if (name == null || name.isEmpty()) {
                name = display.getName();
            }
        }
        final PlatformProjectionDriver.Mode mode = selection == null
                ? null : selection.current;
        if (name == null || name.isEmpty()) {
            return mode == null ? null : mode.displayLabel;
        }
        return mode == null ? name : name + " | " + mode.displayLabel;
    }

    private int currentDisplayId() {
        final Display display = getDisplay();
        return display == null
                ? Display.DEFAULT_DISPLAY : display.getDisplayId();
    }
}
