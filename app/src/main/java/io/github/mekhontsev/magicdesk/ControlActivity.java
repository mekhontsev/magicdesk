package io.github.mekhontsev.magicdesk;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.content.pm.PackageManager;
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
    private static WeakReference<ControlActivity> sActive =
            new WeakReference<>(null);

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final PlatformProjectionDriver mProjection =
            PlatformDrivers.current().projection();
    private final PlatformPhoneUiDriver mPhoneUi =
            PlatformDrivers.current().phoneUi();

    private PhoneControlPanelController mPanel;
    private MagicDeskSessionController mSessionController;
    private DisplayManager mDisplayManager;
    private DisplayManager.DisplayListener mDisplayListener;
    private SessionProfile mSessionProfile;
    private boolean mStartupAuditRunning;
    private boolean mStartupPrepared;
    private boolean mReturnToPanelAfterWirelessConnection;
    private boolean mWirelessConnectionUiAvailable;
    private int mOutputGeneration;
    private DisplayProfileStore.Profile mExternalDisplayProfile;
    private PlatformProjectionDriver.ModeSelection mExternalModeSelection;
    private String mStatus;
    private DesktopDisplayInfo[] mDisplays = new DesktopDisplayInfo[0];
    private String mSelectedDisplayUniqueId = "";
    private boolean mDisplayOperation;
    private int mCatalogGeneration;

    static Intent createLaunchIntent(final android.content.Context context) {
        return new Intent(context, ControlActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MagicDeskRuntime.startTools(this);
        synchronized (ControlActivity.class) {
            sActive = new WeakReference<>(this);
        }
        mSessionProfile = SessionProfile.fromLaunchIntent(this, getIntent());
        initializeControlPanel();
    }

    @Override
    protected void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
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
                runOnUiThread(() -> {
                    if (isActivityUnavailable()) {
                        return;
                    }
                    mStartupAuditRunning = false;
                    mStartupPrepared = true;
                    continueStartup();
                });
            } catch (RuntimeException error) {
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
        startSelectedDesktop();
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
        mSelectedDisplayUniqueId = getPreferences(MODE_PRIVATE).getString("selected_display", "");
        mSessionController = new MagicDeskSessionController(this);
        mWirelessConnectionUiAvailable =
                mProjection.hasWirelessConnectionUi(this);
        mStatus = getString(DesktopRuntimeBridge.getActiveDesktopDisplayId() >= 0
                ? R.string.control_status_desktop_active
                : R.string.control_status_ready);
        setContentView(mPanel.createView());
        registerDisplayListener();
        refreshCatalog();
        refresh();
    }

    private boolean isActivityUnavailable() {
        return isFinishing() || isDestroyed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        DesktopAutomationEventJournal.record(
                "ui", "control_panel_shown", true, "phone");
        // Returning from a cancelled picker already leaves the panel visible.
        mReturnToPanelAfterWirelessConnection = false;
        MagicDeskRuntime.refreshNotification();
        mStatus = getString(DesktopRuntimeBridge.getActiveDesktopDisplayId() >= 0
                ? R.string.control_status_desktop_active
                : R.string.control_status_ready);
        if (mPanel != null) {
            refreshCatalog();
        }
        refresh();
    }

    @Override
    protected void onPause() {
        DesktopAutomationEventJournal.record(
                "ui", "control_panel_hidden", true, "phone");
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        synchronized (ControlActivity.class) {
            if (sActive.get() == this) {
                sActive.clear();
            }
        }
        mCatalogGeneration++;
        mOutputGeneration++;
        if (mDisplayManager != null && mDisplayListener != null) {
            mDisplayManager.unregisterDisplayListener(mDisplayListener);
        }
        mDisplayListener = null;
        mDisplayManager = null;
        super.onDestroy();
    }

    static boolean isControlPanelVisible() {
        final ControlActivity activity;
        synchronized (ControlActivity.class) {
            activity = sActive.get();
        }
        return activity != null
                && !activity.isActivityUnavailable()
                && activity.hasWindowFocus();
    }

    @Override
    public void selectDisplay(final DesktopDisplayInfo display) {
        if (mDisplayOperation || DesktopOperations.isSessionTransitionInProgress()) { return; }
        mSelectedDisplayUniqueId = display.uniqueId;
        getPreferences(MODE_PRIVATE).edit().putString("selected_display", display.uniqueId).apply();
        refreshSelectedOutput();
        refresh();
    }

    private DesktopDisplayInfo selectedDisplay() {
        for (final DesktopDisplayInfo display : mDisplays) {
            if (display.uniqueId.equals(mSelectedDisplayUniqueId)) { return display; }
        }
        return null;
    }

    @Override
    public void startSelectedDesktop() {
        if (!RuntimeCapabilities.supportsDesktop(android.os.Build.VERSION.SDK_INT)) {
            return;
        }
        if (!DeviceSetupManager.isRuntimeAuthorized()) {
            runStartupAudit();
            return;
        }
        final DesktopDisplayInfo display = selectedDisplay();
        final DesktopDisplayTarget active = DesktopRuntimeBridge.getActiveDesktopTarget();
        if (!DisplaySelectionView.canStart(display, active == null ? -1 : active.displayId,
                ShellAccess.isReady(), mDisplayOperation || DesktopOperations.isSessionTransitionInProgress(),
                android.os.Build.VERSION.SDK_INT)) {
            return;
        }
        if (mSessionController.presentDesktopWorkspace(display.target())) { return; }
        mStatus = getString(R.string.status_desktop_starting);
        refresh();
        DesktopOperations.showDesktop(display);
    }

    @Override
    public void createDisplay(final VirtualDisplaySpec spec, final boolean preview) {
        if (mDisplayOperation || !ShellAccess.isReady()) { return; }
        mDisplayOperation = true;
        mStatus = getString(R.string.display_creating);
        refresh();
        DisplayOperations.createDisplay(spec, preview, (display, error) -> runOnUiThread(() -> {
            if (isActivityUnavailable()) { return; }
            mDisplayOperation = false;
            if (display != null) {
                mSelectedDisplayUniqueId = display.uniqueId;
                getPreferences(MODE_PRIVATE).edit().putString("selected_display", display.uniqueId).apply();
                mStatus = getString(R.string.display_created);
            } else {
                mStatus = error;
            }
            refreshCatalog();
            refresh();
        }));
    }

    @Override
    public void removeDisplay(final DesktopDisplayInfo display) {
        if (display == null || !display.owned || mDisplayOperation) { return; }
        mDisplayOperation = true;
        mStatus = getString(R.string.display_removing);
        refresh();
        DesktopOperations.removeVirtualDisplay(display.id, display.uniqueId, success -> runOnUiThread(() -> {
            if (isActivityUnavailable()) { return; }
            mDisplayOperation = false;
            mStatus = getString(success ? R.string.display_removed : R.string.display_remove_failed);
            refreshCatalog();
            refresh();
        }));
    }

    private void refreshCatalog() {
        final int generation = ++mCatalogGeneration;
        DisplayOperations.readDisplays((displays, error) -> runOnUiThread(() -> {
            if (generation != mCatalogGeneration || isActivityUnavailable()) { return; }
            mDisplays = displays;
            // Browsing prepared displays must not switch the active session.
            if (selectedDisplay() == null && displays.length > 0) {
                mSelectedDisplayUniqueId = displays[0].uniqueId;
                final int active = DesktopRuntimeBridge.getActiveDesktopDisplayId();
                for (final DesktopDisplayInfo display : displays) {
                    if (display.id == active) { mSelectedDisplayUniqueId = display.uniqueId; }
                }
            }
            if (error != null) { mStatus = error; }
            if (mReturnToPanelAfterWirelessConnection && wirelessConnected()) {
                mReturnToPanelAfterWirelessConnection = false;
                for (final DesktopDisplayInfo display : displays) {
                    if ("wireless".equals(display.source)) {
                        mSelectedDisplayUniqueId = display.uniqueId;
                        break;
                    }
                }
                PhoneControlPanelLauncher.open(this);
            }
            refreshSelectedOutput();
            refresh();
        }));
    }

    @Override
    public void connectWirelessDisplay() {
        if (!mWirelessConnectionUiAvailable
                || hasDesktopSessionActiveOrStarting()
                || wirelessConnected()) {
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

    @Override
    public void setExternalOutputTiming(final String outputTiming) {
        final DesktopDisplayInfo display = selectedDisplay();
        if (mExternalDisplayProfile == null || display == null
                || !"wired".equals(display.source) || hasDesktopSessionActiveOrStarting()) {
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
        if (!profile.resetOutputModePending) {
            return;
        }
        final int displayId = display.id;
        DesktopOperations.executeSerialized(() -> {
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
            final DesktopHomeRoleLease.State lease =
                    DesktopHomeRoleLease.snapshot();
            if (lease != null
                    && lease.phase == DesktopHomeRoleLease.Phase.ACTIVE) {
                target = lease.target();
            }
        }
        if (target == null) {
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
        DesktopOperations.openTouchpad();
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
        DesktopOperations.setPhoneScreenOff(
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
        BuiltInWindowLauncher.launch(this, SettingsActivity.createIntent(this),
                BuiltInDesktopAppCatalog.settingsTarget(), error -> {
                    if (error != null) { mStatus = ShellAccess.usefulMessage(error); refresh(); }
                });
    }

    @Override
    public void openTool(final String name, final boolean selectedScreen) {
        final DesktopDisplayInfo selected = selectedScreen ? selectedDisplay() : null;
        if (selectedScreen && selected == null) { return; }
        final int displayId = selected == null ? Display.DEFAULT_DISPLAY : selected.id;
        final ToolLaunchTarget target = ToolLaunchTarget.resolve("auto", displayId,
                MagicDeskRuntime.activeDesktopDisplayId());
        if ("sessions".equals(name)) {
            TerminalSessionsDialog.show(this, target, selected == null ? null : selected.uniqueId);
            return;
        }
        ToolApplications.open(this, ToolApplications.intent(this, name), target,
                selected == null ? null : selected.uniqueId, error -> {
                    if (error != null) {
                        mStatus = ShellAccess.usefulMessage(error);
                        refresh();
                    }
                });
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
        final int activeDesktopDisplayId =
                DesktopRuntimeBridge.getActiveDesktopDisplayId();
        final DesktopDisplayTarget activeTarget =
                DesktopRuntimeBridge.getDesktopTarget(activeDesktopDisplayId);
        final DesktopHomeRoleLease.State homeLease =
                DesktopHomeRoleLease.snapshot();
        final boolean desktopSessionActive = homeLease != null
                && homeLease.phase == DesktopHomeRoleLease.Phase.ACTIVE;
        final boolean externalRuntimeDesktop = activeTarget != null
                && activeTarget.kind != DesktopDisplayTarget.Kind.PHONE;
        final boolean externalDesktopActive =
                externalRuntimeDesktop;
        mPanel.render(new PhoneControlPanelController.State(
                mDisplays, mSelectedDisplayUniqueId, activeDesktopDisplayId, mDisplayOperation,
                desktopSessionActive,
                mSessionController.isOperationInProgress(),
                externalDesktopActive,
                ShellAccess.isReady(),
                mPhoneUi.isPhoneScreenOff(this),
                ShellAccess.isReady() && mPhoneUi.isAvailable(),
                mProjection.supportsOutputConfiguration(),
                mExternalModeSelection,
                mWirelessConnectionUiAvailable,
                wirelessConnected(),
                mStatus,
                ShellAccess.statusLabel()));
    }

    private void registerDisplayListener() {
        mDisplayManager = getSystemService(DisplayManager.class);
        if (mDisplayManager == null) {
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
        refreshCatalog();
    }

    private boolean wirelessConnected() {
        for (final DesktopDisplayInfo display : mDisplays) {
            if ("wireless".equals(display.source)) { return true; }
        }
        return false;
    }

    private void refreshSelectedOutput() {
        final int generation = ++mOutputGeneration;
        final DesktopDisplayInfo display = selectedDisplay();
        mExternalDisplayProfile = null;
        mExternalModeSelection = null;
        if (display == null || !"wired".equals(display.source)
                || !mProjection.supportsOutputConfiguration() || !ShellAccess.isReady()) {
            return;
        }
        DesktopOperations.executeSerialized(() -> {
            try {
                DesktopDisplayCatalog.require(display.id, display.uniqueId);
                final DisplayProfileStore.Profile profile = DisplayProfileController.prepareExternalProfile(
                        getApplicationContext(), display.id);
                final PlatformProjectionDriver.ModeSelection modes = mProjection.readExternalDisplayModes(
                        getApplicationContext(), display.id, profile == null ? null : profile.outputTiming);
                runOnUiThread(() -> {
                    if (generation != mOutputGeneration || isActivityUnavailable()) { return; }
                    mExternalDisplayProfile = profile;
                    mExternalModeSelection = modes;
                    refresh();
                });
            } catch (java.io.IOException | RuntimeException error) {
                runOnUiThread(() -> {
                    if (generation == mOutputGeneration && !isActivityUnavailable()) {
                        mStatus = error.getMessage();
                        refresh();
                    }
                });
            }
        });
    }

    private boolean hasDesktopSessionActiveOrStarting() {
        return DesktopRuntimeBridge.getActiveDesktopTarget() != null;
    }

}
