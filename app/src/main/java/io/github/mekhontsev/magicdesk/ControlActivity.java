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
    private Runnable mStartupRequest;
    private boolean mReturnToPanelAfterWirelessConnection;
    private boolean mWirelessConnectionUiAvailable;
    private int mOutputGeneration;
    private String mStatus;
    private DesktopDisplayInfo[] mDisplays = new DesktopDisplayInfo[0];
    private TaskRepository.Snapshot mTasks = new TaskRepository.Snapshot(java.util.List.of(), false, "not observed");
    private boolean mDisplayOperation;
    private final Runnable mSetupStatusListener = () -> runOnUiThread(() -> {
        if (!isActivityUnavailable()) refresh();
    });
    private int mCatalogGeneration;
    private final ShellAccess.StateListener mAccessListener = state -> runOnUiThread(() -> {
        if (!isActivityUnavailable() && mPanel != null) {
            refreshCatalog();
            refresh();
        }
    });

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
        ShellAccess.addStateListener(mAccessListener);
        DesktopSetupStatus.addListener(mSetupStatusListener);
        TermuxConnectionStatus.get().addListener(mSetupStatusListener);
    }

    @Override
    protected void onNewIntent(final Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    private void runStartupAudit(final Runnable request) {
        if (mStartupAuditRunning) {
            return;
        }
        mStartupAuditRunning = true;
        mStartupRequest = request;
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
        mStartupRequest = null;
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
        final Runnable request = mStartupRequest;
        mStartupRequest = null;
        DeviceSetupManager.authorizeRuntime(this);
        if (request != null) request.run();
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
        if (requestCode == TermuxIntegration.PERMISSION_REQUEST_CODE) {
            refresh();
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
        mStatus = getString(DesktopRuntimeBridge.hasWorkspaces()
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
        DesktopSetupStatus.refresh(this);
        DesktopAutomationEventJournal.record(
                "ui", "control_panel_shown", true, "phone");
        // Returning from a cancelled picker already leaves the panel visible.
        mReturnToPanelAfterWirelessConnection = false;
        MagicDeskRuntime.refreshNotification();
        mStatus = getString(DesktopRuntimeBridge.hasWorkspaces()
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
        ShellAccess.removeStateListener(mAccessListener);
        DesktopSetupStatus.removeListener(mSetupStatusListener);
        TermuxConnectionStatus.get().removeListener(mSetupStatusListener);
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

    static void refreshInputState() {
        final ControlActivity activity;
        synchronized (ControlActivity.class) { activity = sActive.get(); }
        if (activity != null && !activity.isActivityUnavailable()) {
            activity.runOnUiThread(activity::refresh);
        }
    }

    @Override
    public void startDesktop(final DesktopDisplayInfo display) {
        if (!RuntimeCapabilities.allowsDesktop(android.os.Build.VERSION.SDK_INT)) { return; }
        if (!DeviceSetupManager.isRuntimeAuthorized()) {
            runStartupAudit(() -> startDesktop(display));
            return;
        }
        if (!DisplayTableView.canStart(display, ShellAccess.isReady(),
                mDisplayOperation || DesktopOperations.isSessionTransitionInProgress(),
                RuntimeCapabilities.allowsDesktop(android.os.Build.VERSION.SDK_INT))) {
            return;
        }
        if (display.requiresPortableDesktop) { startPortableDesktop(display); return; }
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
            if (display != null && error == null) {
                mStatus = getString(R.string.display_created);
            } else {
                mStatus = error;
            }
            refreshCatalog();
            refresh();
        }));
    }

    @Override public void startPortableDesktop(final DesktopDisplayInfo output) {
        if (!RuntimeCapabilities.allowsDesktop(android.os.Build.VERSION.SDK_INT)) { return; }
        if (!DeviceSetupManager.isRuntimeAuthorized()) { runStartupAudit(() -> startPortableDesktop(output)); return; }
        if (mDisplayOperation || DesktopOperations.isSessionTransitionInProgress()) return;
        mDisplayOperation = true;
        refresh();
        DesktopPresentationLauncher.start(this, output, (source, error) -> {
            if (isActivityUnavailable()) return;
            mDisplayOperation = false;
            if (error != null) mStatus = error;
            refreshCatalog();
            refresh();
        });
    }

    @Override
    public void removeDisplay(final DesktopDisplayInfo display) {
        if (display == null || !display.canRemove() || mDisplayOperation) { return; }
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
        ++mOutputGeneration;
        final int generation = ++mCatalogGeneration;
        DisplayOperations.readDisplays((displays, error) -> runOnUiThread(() -> {
            if (generation != mCatalogGeneration || isActivityUnavailable()) { return; }
            mDisplays = displays;
            if (error != null) { mStatus = error; }
            if (mReturnToPanelAfterWirelessConnection && wirelessConnected()) {
                mReturnToPanelAfterWirelessConnection = false;
                PhoneControlPanelLauncher.open(this);
            }
            if (!ShellAccess.isReady()) {
                mTasks = new TaskRepository.Snapshot(java.util.List.of(), false, getString(R.string.capability_access_required));
                refresh();
                return;
            }
            TaskCommandQueue.execute(() -> {
                final TaskRepository.Snapshot tasks = TaskRepository.loadAllNow();
                runOnUiThread(() -> {
                    if (generation != mCatalogGeneration || isActivityUnavailable()) { return; }
                    mTasks = tasks;
                    refresh();
                });
            });
            refresh();
        }));
    }

    @Override
    public void openWirelessSettings() {
        if (!mWirelessConnectionUiAvailable
                || DesktopOperations.isSessionTransitionInProgress()
                || mDisplayOperation) {
            mStatus = getString(R.string.status_external_display_unavailable);
            refresh();
            return;
        }
        // Existing connections open for management, not a new-connection handoff.
        mReturnToPanelAfterWirelessConnection = !wirelessConnected();
        if (mProjection.openWirelessConnectionUi(this)) {
            if (mReturnToPanelAfterWirelessConnection) {
                mStatus = getString(R.string.status_wireless_display_connecting);
            }
        } else {
            mReturnToPanelAfterWirelessConnection = false;
            mStatus = getString(R.string.status_external_display_unavailable);
        }
        refresh();
    }

    private void setExternalOutputTiming(final DesktopDisplayInfo display,
            final DisplayProfileStore.Profile profile, final String outputTiming) {
        if (profile == null || display == null
                || !"wired".equals(display.source) || DesktopRuntimeBridge.hasWorkspace(display.id)) {
            return;
        }
        DisplayProfileStore.setOutputTiming(
                profile, outputTiming);
        DisplayProfileStore.save(profile);
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
                DesktopDisplayCatalog.require(displayId, display.uniqueId);
                if (DesktopRuntimeBridge.hasWorkspace(displayId)) { return; }
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
    public void closeDesktop(final DesktopDisplayInfo selected) {
        if (!ShellAccess.isReady()) {
            return;
        }
        DesktopDisplayTarget target = selected == null ? null
                : DesktopRuntimeBridge.getDesktopTarget(selected.id);
        if (target == null && selected != null) {
            final DesktopHomeRoleLease.State lease = DesktopHomeRoleLease.snapshot();
            target = lease == null ? null : lease.targetForDisplay(selected.id);
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
        PhoneTouchpadController.open();
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

    @Override public void controlDisplay(final DesktopDisplayInfo display) {
        selectInput(display.id);
    }

    @Override public void openApplications(final DesktopDisplayInfo display) {
        if (display == null) StartActivity.open(this);
        else StartActivity.open(this, display);
    }

    @Override public void openIndependentApplications(final DesktopDisplayInfo display) {
        IndependentApplicationsDialog.show(this, display, this::refreshCatalog);
    }

    @Override public void releaseInput() { selectInput(-1); }

    private void selectInput(final int displayId) {
        MagicDeskRuntime.selectInputDisplay(displayId, result -> runOnUiThread(() -> {
            if (!result.success) { mStatus = result.message; }
            refresh();
        }));
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

    @Override
    public void showAccessInfo() {
        IntegrationStatusDialogs.showAccess(this,
                !mDisplayOperation && !mSessionController.isOperationInProgress(),
                this::requestAccess, this::openSettings);
    }

    @Override
    public void showTermuxInfo() {
        IntegrationStatusDialogs.showTermux(this, this::openSettings);
    }

    @Override
    public void showDesktopInfo() {
        IntegrationStatusDialogs.showDesktop(this,
                !mDisplayOperation && !mSessionController.isOperationInProgress(), this::showAccessInfo);
    }

    private void requestAccess() {
        if (mDisplayOperation || mSessionController.isOperationInProgress()) { return; }
        if (RuntimeLimits.restartRequired(this)) {
            new android.app.AlertDialog.Builder(this)
                    .setMessage(R.string.access_restart_required)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.action_exit, (dialog, which) -> exitMagicDesk())
                    .show();
            return;
        }
        try {
            final ShellAccess.Snapshot access = ShellAccess.currentSnapshot();
            if (!access.backend.usesRoot() && !access.running) {
                ShellAccess.openManagerOrWebsite(this);
            } else {
                ShellAccess.requestPermission();
            }
        } catch (RuntimeException error) {
            mStatus = ShellAccess.usefulMessage(error);
        }
        refresh();
    }

    private void refresh() {
        if (mPanel == null) {
            return;
        }
        final java.util.Set<Integer> desktopDisplays = DesktopRuntimeBridge.workspaceDisplayIds();
        final boolean externalDesktopActive = desktopDisplays.stream().anyMatch(id -> id > 0);
        mPanel.render(new PhoneControlPanelController.State(
                mDisplays, mTasks, desktopDisplays, mDisplayOperation,
                mSessionController.isOperationInProgress(),
                externalDesktopActive,
                ShellAccess.isReady(),
                mPhoneUi.isPhoneScreenOff(this),
                ShellAccess.isReady() && mPhoneUi.isAvailable(),
                mProjection.supportsOutputConfiguration(),
                mWirelessConnectionUiAvailable,
                RuntimeLimits.restartRequired(this) ? getString(R.string.access_restart_required)
                        : mStatus,
                ShellAccess.currentSnapshot().accessLabel(), TermuxIntegration.inspect(this),
                RuntimeCapabilities.current(this)));
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

    @Override public void openOutputSettings(final DesktopDisplayInfo display) {
        final int generation = ++mOutputGeneration;
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
                    DisplayOutputDialog.show(this, modes, DisplayTableView.canConfigureOutput(
                            display, mProjection.supportsOutputConfiguration(), modes,
                            DesktopRuntimeBridge.workspaceDisplayIds(), ShellAccess.isReady(),
                            mDisplayOperation || DesktopOperations.isSessionTransitionInProgress()),
                            timing -> setExternalOutputTiming(display, profile, timing));
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
        return DesktopRuntimeBridge.hasWorkspaces();
    }

}
