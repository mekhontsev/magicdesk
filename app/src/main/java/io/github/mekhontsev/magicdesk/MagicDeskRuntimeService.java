package io.github.mekhontsev.magicdesk;

import android.Manifest;
import android.app.ActivityOptions;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

public final class MagicDeskRuntimeService extends Service
        implements MagicDeskRuntimeBackend {
    private static final String TAG = "MagicDeskWatcher";
    private static final String CHANNEL_ID = "magicdesk";
    private static final int NOTIFICATION_ID = 1;
    private static final int OPEN_TOUCHPAD_REQUEST_CODE = 1;
    private static final int OPEN_CONTROL_PANEL_REQUEST_CODE = 2;
    private final PlatformDriver mPlatform = PlatformDrivers.current();
    private final PlatformPhoneUiDriver mPhoneUi = mPlatform.phoneUi();
    private final PlatformProjectionDriver mProjection =
            mPlatform.projection();

    private Handler mHandler;
    private RuntimeDesktopSessionCoordinator mDesktopSession;
    private RuntimeDisplayInputCoordinator mDisplayInput;
    private RuntimeDesktopTaskCoordinator mDesktopTaskRuntime;
    private RuntimeDisplayCoordinator mDisplayCoordinator;
    private boolean mToolsRequested;
    private DesktopSessionWakeLock mSessionWakeLock;
    private DesktopAdaptiveBrightnessController mAdaptiveBrightness;
    private MagicDeskMcpRuntime mMcpRuntime;
    private BroadcastReceiver mConfigurationReceiver;
    private volatile boolean mDestroyed;
    private boolean mInitialized;
    private String mOperationStatus;
    private boolean mKeepDesktopAwake;
    private boolean mDisableAdaptiveBrightness;

    private final ShellAccess.StateListener mShellStateListener =
            snapshot -> {
                postIfAlive(this::handleShellStateChanged);
            };

    @Override
    public boolean isAvailable() {
        return !mDestroyed;
    }

    @Override
    public boolean isDesktopRuntimeInitialized() {
        return !mDestroyed && mInitialized;
    }

    @Override
    public void refreshNotification() {
        if (mDestroyed) {
            return;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            updateNotification();
        } else {
            postIfAlive(this::updateNotification);
        }
    }

    @Override
    public void setOperationStatus(final String status) {
        postIfAlive(() -> {
            mOperationStatus = status;
            updateNotification();
        });
    }

    @Override
    public void refreshDesktopTasks() {
        if (mDesktopSession == null) {
            return;
        }
        postIfAlive(() -> {
            mDesktopSession.refreshOwnership();
            updateDesktopTasks();
        });
    }

    @Override
    public void refreshPlatformState() {
        postIfAlive(() -> {
            updateNotification();
            DesktopRuntimeBridge.refreshDesktopControls();
        });
    }

    @Override
    public void refreshSettings(final Runnable completion) {
        postIfAlive(() -> {
            refreshRuntimeSettings();
            if (completion != null) {
                completion.run();
            }
        });
    }

    @Override
    public boolean isSessionWakeLockHeld() {
        return !mDestroyed
                && mSessionWakeLock != null
                && mSessionWakeLock.isHeld();
    }

    @Override
    public void reconcileFailedDesktopLaunch(final int displayId) {
        if (mDesktopSession == null
                || displayId <= android.view.Display.DEFAULT_DISPLAY) {
            return;
        }
        postIfAlive(() -> mDesktopSession
                .reconcileFailedDesktopLaunch(displayId));
    }

    @Override
    public void scheduleLocalDesktopCleanup() {
        if (mDestroyed || mHandler == null || mDesktopSession == null) {
            return;
        }
        mDesktopSession.scheduleLocalDesktopCleanup();
    }

    @Override
    public boolean isPointerTransportReady() {
        return !mDestroyed
                && mDisplayInput != null
                && mDisplayInput.isMouseBridgeReady();
    }

    @Override
    public boolean isFullKeyboardShortcutMode() {
        return !mDestroyed
                && mDisplayInput != null
                && mDisplayInput.isFullShortcutMode();
    }

    @Override
    public DesktopPointerState getPointerState(
            final int displayId) {
        if (mDestroyed || mDisplayInput == null) {
            return null;
        }
        final PlatformSelection.Provider provider = mPlatform.selection()
                .provider(PlatformComponent.POINTER);
        return mDisplayInput.pointerState(
                displayId, provider == null ? null : provider.id);
    }

    @Override
    public DesktopInputDiagnostics.Snapshot
            captureInputDiagnostics() {
        if (mDestroyed || mDisplayInput == null) {
            return DesktopInputDiagnostics.Snapshot.unavailable();
        }
        final PlatformSelection.Provider provider = mPlatform.selection()
                .provider(PlatformComponent.POINTER);
        return mDisplayInput.captureDiagnostics(
                provider == null ? null : provider.id);
    }

    @Override public int inputDisplayId() {
        return mDisplayInput == null ? -1 : mDisplayInput.requestedDisplay();
    }

    @Override public int readyInputDisplayId() {
        return mDisplayInput == null ? -1 : mDisplayInput.readyDisplay();
    }
    @Override public boolean inputTransitioning() { return mDisplayInput != null && mDisplayInput.transitioning(); }
    @Override public String inputError() { return mDisplayInput == null ? "" : mDisplayInput.error(); }

    @Override public void selectInputDisplay(final int displayId,
            final TaskRepository.ActionCallback callback) {
        if (!postIfAlive(() -> {
            try {
                if (!ShellAccess.isReady()) { throw new IllegalStateException("privileged service is unavailable"); }
                if (DesktopOperations.isSessionTransitionInProgress()) {
                    throw new IllegalStateException("desktop transition is in progress");
                }
                if (displayId >= 0 && !mDisplayCoordinator.hasDisplay(displayId)) {
                    throw new IllegalArgumentException("input display is unavailable");
                }
                ensureInputRuntime();
                mDisplayInput.selectDisplay(displayId);
                callback.onComplete(new TaskRepository.ActionResult(true, "input target requested"));
            } catch (RuntimeException error) {
                callback.onComplete(new TaskRepository.ActionResult(false, error.getMessage()));
            }
        })) { callback.onComplete(new TaskRepository.ActionResult(false, "input runtime is closed")); }
    }

    @Override
    public void releaseDisplayInput(
            final int displayId, final Runnable completion) {
        final Runnable release = () -> {
            try {
                if (!mDestroyed && mDesktopSession != null && desktopDisplayId() == displayId) {
                    mDesktopSession.prepareDisplayRemoval(displayId);
                }
                if (!mDestroyed && mDisplayInput != null) {
                    mDisplayInput.releaseForSessionClose(displayId, completion);
                    return;
                }
            } catch (RuntimeException error) {
                CompatibilityDiagnostics.record("INPUT-CLOSE-001",
                        "Could not release desktop input", error.getMessage(), error);
            }
            completion.run();
        };
        if (mDestroyed || mHandler == null) {
            completion.run();
        } else if (Looper.myLooper() == Looper.getMainLooper()) {
            release.run();
        } else if (!mHandler.post(release)) {
            completion.run();
        }
    }

    @Override
    public boolean movePointer(
            final int displayId,
            final float deltaX,
            final float deltaY) {
        return !mDestroyed
                && mDisplayInput != null
                && mDisplayInput.movePointer(displayId, deltaX, deltaY);
    }

    @Override
    public boolean setPointerButtonPressed(
            final int displayId,
            final int button,
            final boolean pressed) {
        return !mDestroyed
                && mDisplayInput != null
                && mDisplayInput.setPointerButtonPressed(
                        displayId, button, pressed);
    }

    @Override
    public boolean clickPointer(
            final int displayId,
            final int button) {
        return !mDestroyed
                && mDisplayInput != null
                && mDisplayInput.clickPointer(displayId, button);
    }

    @Override
    public boolean scrollPointer(
            final int displayId,
            final float amount) {
        return !mDestroyed
                && mDisplayInput != null
                && mDisplayInput.scrollPointer(displayId, amount);
    }


    @Override
    public boolean showStart(final int displayId) {
        final DesktopWorkspaceRuntime workspace = DesktopRuntimeBridge.getWorkspaceRuntime(displayId);
        return workspace != null && postIfAlive(() -> showStartOnDesktop(workspace));
    }

    @Override
    public boolean toggleDesktopWorkspace(final int displayId) {
        return !mDestroyed && DesktopRuntimeBridge.toggleDesktopWorkspace(displayId);
    }

    @Override
    public boolean toggleDesktopWorkspace(
            final int displayId,
            final TaskRepository.ActionCallback callback) {
        return !mDestroyed
                && DesktopRuntimeBridge.toggleDesktopWorkspace(displayId, callback);
    }

    @Override
    public boolean restoreLastVisibleWindows(final int displayId) {
        return !mDestroyed && DesktopRuntimeBridge.restoreLastVisibleWindows(displayId);
    }

    @Override
    public boolean advanceAltTab(final int displayId, final boolean reverse) {
        return !mDestroyed && DesktopRuntimeBridge.advanceAltTab(displayId, reverse);
    }

    @Override
    public boolean finishAltTab(final int displayId) {
        return !mDestroyed && DesktopRuntimeBridge.finishAltTab(displayId);
    }

    @Override
    public boolean cancelAltTab(final int displayId) {
        return !mDestroyed && DesktopRuntimeBridge.cancelAltTab(displayId);
    }

    @Override
    public boolean toggleShortcutHelp(final int displayId) {
        return !mDestroyed && DesktopRuntimeBridge.toggleShortcutHelp(displayId);
    }

    @Override
    public boolean toggleNotificationCenter(final int displayId) {
        return !mDestroyed
                && DesktopRuntimeBridge.toggleNotificationCenter(displayId);
    }

    @Override
    public boolean toggleSystemPanel(final int displayId) {
        return !mDestroyed && DesktopRuntimeBridge.toggleSystemPanel(displayId);
    }

    @Override
    public boolean openSettings(final int displayId) {
        return !mDestroyed && DesktopRuntimeBridge.openSettings(displayId);
    }

    @Override
    public DesktopTaskRuntime desktopTasks() {
        return mDestroyed || mDesktopTaskRuntime == null
                ? null : mDesktopTaskRuntime.operations();
    }

    @Override
    public void prepareForStop(final Runnable completion) {
        releaseDesktopTaskSession(completion);
    }

    @Override
    public void releaseDesktopRuntime() {
        releaseDesktopTaskSession(() -> mHandler.post(() -> {
            // Observer teardown completes on its worker. Service/UI ownership
            // stays on main, and a newer authorization supersedes this release.
            if (mDestroyed || DeviceSetupManager.isRuntimeAuthorized() && ShellAccess.isReady()) { return; }
            destroyDesktopRuntime();
            refreshRuntimeSettings();
        }));
    }

    private void releaseDesktopTaskSession(final Runnable completion) {
        releaseTaskRuntime(null, completion);
    }

    @Override
    public void releaseDesktopWorkspace(final DesktopWorkspaceRuntime workspace,
            final Runnable completion) {
        if (workspace == null) {
            if (completion != null) { completion.run(); }
            return;
        }
        releaseTaskRuntime(workspace, completion);
    }

    private void releaseTaskRuntime(final DesktopWorkspaceRuntime workspace,
            final Runnable completion) {
        final Runnable finish = completion == null ? () -> { } : completion;
        final Handler handler = mHandler;
        if (mDestroyed || handler == null) {
            finish.run();
            return;
        }
        final Runnable release = () -> {
            if (!mDestroyed && mDesktopTaskRuntime != null) {
                if (workspace == null) {
                    mDesktopTaskRuntime.releaseSession(finish);
                } else {
                    mDesktopTaskRuntime.releaseWorkspace(workspace, finish);
                }
            } else {
                finish.run();
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            release.run();
        } else if (!handler.post(release)) {
            finish.run();
        }
    }

    @Override
    public DesktopTaskParkingRuntime desktopTaskParking() {
        return mDestroyed || mDesktopTaskRuntime == null
                ? null : mDesktopTaskRuntime.parking();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mDestroyed = false;
        mHandler = new Handler(Looper.getMainLooper());
        mSessionWakeLock = new DesktopSessionWakeLock(this);
        mMcpRuntime = new MagicDeskMcpRuntime(this);
        mDisplayCoordinator = new RuntimeDisplayCoordinator(
                this, mHandler, this::handleDisplayStateChanged);
        mDisplayCoordinator.start();
        MagicDeskRuntime.attach(this);
        ShellAccess.addStateListener(mShellStateListener);
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
    }

    private void initialize() {
        if (mInitialized) {
            return;
        }
        RuntimeCapabilities.requireDesktop();
        mInitialized = true;
        ensureInputRuntime();
        final MagicDeskSettings.Values settings = MagicDeskSettings.load();
        mKeepDesktopAwake = settings.keepDesktopAwake;
        mDisableAdaptiveBrightness =
                settings.disableAdaptiveBrightnessOnExternalDesktop;
        mAdaptiveBrightness =
                new DesktopAdaptiveBrightnessController(this);
        mDesktopSession = new RuntimeDesktopSessionCoordinator(
                this,
                mHandler,
                displayId -> mDisplayCoordinator.hasDisplay(displayId),
                new RuntimeDesktopSessionCoordinator.Listener() {
                    @Override
                    public void onOwnershipRefreshed(
                            final boolean changed) {
                        handleDesktopOwnershipRefreshed(changed);
                    }
                });
        mDesktopTaskRuntime = new RuntimeDesktopTaskCoordinator(
                this,
                mHandler,
                () -> {
                    reportDesktopPrepared();
                    mDesktopSession.onTaskStackChanged();
                },
                mDisplayInput::onDesktopPrepared);
        mDesktopSession.start();
        mDisplayInput.reconcileSoftwareKeyboardPolicy();
        registerConfigurationReceiver();
        if (ShellAccess.isReady()) {
            updatePlatformCaptionTarget();
        } else {
            mProjection.setCaptionTransport(
                    PlatformProjectionDriver.Transport.NONE);
        }
        mDisplayInput.reconcileRuntime(DesktopRuntimeBridge.getSessionSnapshot().inputDisplayId());
        updateDesktopTasks();
        mPlatform.startRuntime(this);
        mMcpRuntime.reconcile();
    }

    private void ensureInputRuntime() {
        if (mDisplayInput == null) {
            mDisplayInput = new RuntimeDisplayInputCoordinator(this, mHandler, () -> {
                updateNotification();
                ControlActivity.refreshInputState();
                MagicDeskTouchpadActivity.refreshInputControls();
            });
            mDisplayInput.start();
        }
    }

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        startForeground(NOTIFICATION_ID, buildNotification());
        if (MagicDeskRuntime.isToolsStart(intent)) {
            mToolsRequested = true;
            mMcpRuntime.reconcile();
            updateNotification();
            return START_NOT_STICKY;
        }
        // A direct service Intent must not crash independent services on an OS
        // that cannot host Desktop. Public Desktop entry points reject earlier.
        if (MagicDeskRuntime.isAutomationStart(intent)
                || !RuntimeCapabilities.supportsDesktop(android.os.Build.VERSION.SDK_INT)) {
            if (!MagicDeskMcpPreferences.isEnabled(this) && !mInitialized && !mToolsRequested) {
                stopSelf();
                return START_NOT_STICKY;
            }
            mMcpRuntime.reconcile();
            updateNotification();
            return START_NOT_STICKY;
        }
        if (!ShellAccess.isReady()
                && DesktopHomeRoleLease.snapshot() != null) {
            return START_NOT_STICKY;
        }
        initialize();
        mDisplayInput.reconcileRuntime(DesktopRuntimeBridge.getSessionSnapshot().inputDisplayId());
        updateDesktopTasks();
        mDesktopSession.schedulePhoneTaskRecovery();
        reportDesktopPrepared();
        return START_NOT_STICKY;
    }

    private void reportDesktopPrepared() {
        if (mDesktopTaskRuntime != null && mDesktopTaskRuntime.operations().isTaskObserverReady()) {
            MagicDeskRuntime.desktopRuntimePrepared();
        }
    }

    private void showStartOnDesktop(final DesktopWorkspaceRuntime workspace) {
        final int displayId = workspace.displayId;
        if (workspace.isClosed() || DesktopRuntimeBridge.getWorkspaceRuntime(displayId) != workspace) {
            return;
        }
        if (DesktopRuntimeBridge.showStart(displayId)) {
            return;
        }
        final DesktopDisplayTarget target =
                workspace.snapshot().target;
        if (target == null) {
            return;
        }
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(target.workspaceDisplayId);
        DesktopShellActivity.setLaunchWindowingMode(options, 5);
        startActivity(
                DesktopShellActivity.createShowStartIntent(this, target),
                options.toBundle());
    }

    @Override
    public void onDestroy() {
        mDestroyed = true;
        MagicDeskRuntime.detach(this);
        ShellAccess.removeStateListener(mShellStateListener);
        if (mDisplayCoordinator != null) {
            mDisplayCoordinator.stop();
        }
        destroyDesktopRuntime();
        if (mDisplayInput != null) {
            mDisplayInput.destroy();
            mDisplayInput = null;
        }
        ConsoleTerminalRegistry.closeAll();
        if (mMcpRuntime != null) {
            mMcpRuntime.close();
            mMcpRuntime = null;
        }
        AutomationCommandRuntime.closeCurrent();
        if (mHandler != null) {
            mHandler.removeCallbacksAndMessages(null);
        }
        super.onDestroy();
    }

    private void destroyDesktopRuntime() {
        if (!mInitialized) { return; }
        mInitialized = false;
        if (mConfigurationReceiver != null) {
            unregisterReceiver(mConfigurationReceiver);
            mConfigurationReceiver = null;
        }
        if (mHandler != null) {
            if (mDesktopSession != null) {
                mDesktopSession.destroy();
                mDesktopSession = null;
            }
        }
        if (mDesktopTaskRuntime != null) {
            mDesktopTaskRuntime.destroy();
            mDesktopTaskRuntime = null;
        }
        if (mDisplayInput != null) {
            mDisplayInput.setInputTarget(-1);
        }
        if (mSessionWakeLock != null) {
            mSessionWakeLock.release();
        }
        if (mAdaptiveBrightness != null) {
            mAdaptiveBrightness.release();
        }
        mPlatform.stopRuntime();
        mPhoneUi.requestPhoneScreenRestore();
    }

    @Override
    public IBinder onBind(final Intent intent) {
        return null;
    }

    private void handleDisplayStateChanged(
            final int displayId, final boolean displayRemoved) {
        if (mDesktopSession != null) {
            mDesktopSession.handleDisplayStateChanged(
                    displayId, displayRemoved);
        }
        if (mDisplayInput != null) {
            if (displayRemoved) { mDisplayInput.releaseForSessionClose(displayId, () -> { }); }
            mDisplayInput.reconcileSoftwareKeyboardPolicy();
        }
    }

    private void registerConfigurationReceiver() {
        mConfigurationReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(final Context context, final Intent intent) {
                if (Intent.ACTION_CONFIGURATION_CHANGED.equals(intent.getAction())) {
                    if (mDisplayInput != null) {
                        mDisplayInput.scheduleDeviceRefresh();
                    }
                } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())
                        && mPhoneUi.isPhoneScreenControlActive()) {
                    DesktopOperations.setPhoneScreenOff(false, null);
                }
            }
        };
        final IntentFilter filter =
                new IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(mConfigurationReceiver, filter);
    }

    private void handleShellStateChanged() {
        if (mDestroyed) {
            return;
        }
        if (mDisplayInput != null) {
            mDisplayInput.reconcileRuntime(DesktopRuntimeBridge.getSessionSnapshot().inputDisplayId());
        }
        if (!mInitialized) {
            if (ShellAccess.isReady()
                    && RuntimeCapabilities.supportsDesktop(android.os.Build.VERSION.SDK_INT)
                    && DesktopHomeRoleLease.snapshot() != null) {
                initialize();
            }
            return;
        }
        mDesktopSession.refreshOwnership();
        mDisplayInput.reconcileRuntime(DesktopRuntimeBridge.getSessionSnapshot().inputDisplayId());
        updateDesktopTasks();
        if (ShellAccess.isReady()) {
            updatePlatformCaptionTarget();
            mPlatform.startRuntime(this);
            mDesktopSession.onShellReady();
        } else {
            mProjection.setCaptionTransport(
                    PlatformProjectionDriver.Transport.NONE);
            mPlatform.stopRuntime();
        }
        updateNotification();
        DesktopRuntimeBridge.refreshDesktopControls();
    }

    private void handleDesktopOwnershipRefreshed(
            final boolean changed) {
        mDisplayInput.setInputTarget(DesktopRuntimeBridge.getSessionSnapshot().inputDisplayId());
        updateAdaptiveBrightness();
        if (!changed) {
            updateSessionWakeLock();
            return;
        }
        updateSessionWakeLock();
        Log.i(TAG, "ownsExternalDesktop=" + ownsExternalDesktop()
                + " desktopDisplay=" + desktopDisplayId());
        updateNotification();
        if (ShellAccess.isReady()) {
            updatePlatformCaptionTarget();
        }
    }

    private void updateSessionWakeLock() {
        if (mSessionWakeLock == null) {
            return;
        }
        mSessionWakeLock.reconcile(
                mKeepDesktopAwake,
                desktopDisplayId());
    }

    private void refreshRuntimeSettings() {
        final MagicDeskSettings.Values settings = MagicDeskSettings.load();
        mKeepDesktopAwake = settings.keepDesktopAwake;
        mDisableAdaptiveBrightness =
                settings.disableAdaptiveBrightnessOnExternalDesktop;
        updateSessionWakeLock();
        updateAdaptiveBrightness();
        if (mMcpRuntime != null) {
            mMcpRuntime.reconcile();
        }
        if (!mInitialized && !mToolsRequested && !MagicDeskMcpPreferences.isEnabled(this)
                && ConsoleTerminalRegistry.registeredCount() == 0) {
            stopSelf();
        }
    }

    private void updateAdaptiveBrightness() {
        if (mAdaptiveBrightness == null) {
            return;
        }
        mAdaptiveBrightness.reconcile(
                mDisableAdaptiveBrightness,
                DesktopRuntimeBridge.getDesktopTarget(desktopDisplayId()));
    }

    private void updatePlatformCaptionTarget() {
        DesktopOperations.updateExternalTaskCaptionTarget(
                DesktopRuntimeBridge.getDesktopTarget(desktopDisplayId()));
    }

    private boolean ownsExternalDesktop() {
        return mDesktopSession != null
                && mDesktopSession.ownsExternalDesktop();
    }

    private int desktopDisplayId() {
        return mDesktopSession == null
                ? android.view.Display.INVALID_DISPLAY
                : mDesktopSession.desktopDisplayId();
    }

    private void updateDesktopTasks() {
        if (mDesktopTaskRuntime == null) {
            return;
        }
        mDesktopTaskRuntime.reconcile(
                DesktopRuntimeBridge.getSessionSnapshot(),
                ShellAccess.isReady());
    }

    private void updateNotification() {
        if (mDestroyed) {
            return;
        }
        final NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED) {
            manager.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    private Notification buildNotification() {
        final PendingIntent openControlPanelPendingIntent =
                phoneActivityPendingIntent(
                        OPEN_CONTROL_PANEL_REQUEST_CODE,
                        ControlActivity.createLaunchIntent(this));
        final String text = mOperationStatus != null
                ? mOperationStatus
                : (!mInitialized
                        ? getString(mToolsRequested ? R.string.notification_tools_ready
                                : R.string.notification_automation_ready)
                        : mDisplayInput != null
                        && mDisplayInput.hasHardwareKeyboard()
                        ? getString(R.string.notification_hw_connected)
                        : getString(R.string.notification_hw_disconnected));

        final Notification.Builder builder =
                new Notification.Builder(this, CHANNEL_ID);
        builder
                .setSmallIcon(R.drawable.ic_magicdesk)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(openControlPanelPendingIntent);
        final int targetDisplayId = desktopDisplayId();
        if (ShellAccess.isReady()
                && PhoneTouchpadController.isSupported(targetDisplayId)) {
            builder.addAction(
                        R.drawable.ic_touchpad,
                        getString(R.string.notification_open_touchpad),
                        phoneActivityPendingIntent(
                                OPEN_TOUCHPAD_REQUEST_CODE,
                                MagicDeskTouchpadActivity.createLaunchIntent(
                                        this, targetDisplayId)));
        }
        return builder.build();
    }

    private PendingIntent phoneActivityPendingIntent(
            final int requestCode, final Intent intent) {
        // SystemUI must launch the Activity itself. A service intermediary
        // loses the notification's foreground-launch authorization.
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY);
        return PendingIntent.getActivity(
                this, requestCode, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE,
                options.toBundle());
    }

    private void createNotificationChannel() {
        final NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        final NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notification_channel_description));
        manager.createNotificationChannel(channel);
    }

    private boolean postIfAlive(final Runnable action) {
        final Handler handler = mHandler;
        if (mDestroyed || handler == null) {
            return false;
        }
        return handler.post(() -> {
            if (!mDestroyed) {
                action.run();
            }
        });
    }
}
