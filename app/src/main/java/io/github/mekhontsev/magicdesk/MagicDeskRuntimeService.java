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
import android.database.ContentObserver;
import android.graphics.Point;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

public final class MagicDeskRuntimeService extends Service
        implements MagicDeskRuntimeBackend {
    private static final String TAG = "MagicDeskWatcher";
    private static final String CHANNEL_ID = "magicdesk";
    private static final String ACTION_OPEN_CONTROL_PANEL =
            "io.github.mekhontsev.magicdesk.action.OPEN_CONTROL_PANEL";
    private static final String ACTION_OPEN_TOUCHPAD =
            "io.github.mekhontsev.magicdesk.action.OPEN_TOUCHPAD";
    private static final int NOTIFICATION_ID = 1;
    private static final int OPEN_TOUCHPAD_REQUEST_CODE = 1;
    private static final int OPEN_CONTROL_PANEL_REQUEST_CODE = 2;
    private final PlatformDriver mPlatform = PlatformDrivers.current();
    private final PlatformPhoneUiDriver mPhoneUi = mPlatform.phoneUi();
    private final PlatformProjectionDriver mProjection =
            mPlatform.projection();
    private final PlatformWindowingDriver mWindowing = mPlatform.windowing();

    private Handler mHandler;
    private RuntimeDesktopSessionCoordinator mDesktopSession;
    private RuntimeDesktopInputCoordinator mDesktopInput;
    private RuntimeDesktopTaskCoordinator mDesktopTaskRuntime;
    private RuntimeDisplayCoordinator mDisplayCoordinator;
    private DesktopSessionWakeLock mSessionWakeLock;
    private MagicDeskMcpRuntime mMcpRuntime;
    private BroadcastReceiver mConfigurationReceiver;
    private ContentObserver mConsoleModeObserver;
    private volatile boolean mDestroyed;
    private boolean mInitialized;
    private String mOperationStatus;
    private boolean mKeepDesktopAwake;

    private final ShellAccess.StateListener mShellStateListener =
            snapshot -> {
                postIfAlive(this::handleShellStateChanged);
            };

    @Override
    public boolean isAvailable() {
        return !mDestroyed;
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
    public boolean isDesktopMouseBridgeReady() {
        return !mDestroyed
                && mDesktopInput != null
                && mDesktopInput.isMouseBridgeReady();
    }

    @Override
    public boolean capturePointerPosition() {
        return !mDestroyed
                && mDesktopInput != null
                && mDesktopInput.capturePointerPosition();
    }

    @Override
    public void restorePointerPositionOnNextMotion() {
        if (mDestroyed || mDesktopInput == null) {
            return;
        }
        mDesktopInput.restorePointerPositionOnNextMotion();
    }

    @Override
    public void reactivatePointerOnNextMotion() {
        if (mDestroyed || mDesktopInput == null) {
            return;
        }
        mDesktopInput.reactivatePointerOnNextMotion();
    }

    @Override
    public void preparePhysicalPointerHandoff(final int displayId) {
        if (mDestroyed || mDesktopInput == null) {
            return;
        }
        mDesktopInput.preparePhysicalPointerHandoff(displayId);
    }

    @Override
    public boolean prepareDesktopDisplayRemoval(
            final int displayId) {
        if (mDestroyed || mDesktopInput == null
                || mDesktopSession == null
                || !mDesktopSession.prepareDisplayRemoval(displayId)) {
            return false;
        }
        if (mDesktopInput.suspendMouseBridgeForDisplayRemoval(displayId)) {
            return true;
        }
        mDesktopSession.cancelDisplayRemoval(displayId);
        return false;
    }

    @Override
    public void cancelDesktopDisplayRemoval(final int displayId) {
        if (mDestroyed) {
            return;
        }
        if (mDesktopSession != null) {
            mDesktopSession.cancelDisplayRemoval(displayId);
        }
        if (mDesktopInput != null) {
            mDesktopInput.cancelMouseBridgeDisplayRemoval(displayId);
        }
    }

    @Override
    public Point getDesktopPointerPosition(final int displayId) {
        return !mDestroyed && mDesktopInput != null
                ? mDesktopInput.getPointerPosition(displayId) : null;
    }

    @Override
    public boolean updateDesktopPointerPosition(
            final int displayId,
            final int x,
            final int y,
            final int action,
            final long downTime) {
        return !mDestroyed
                && mDesktopInput != null
                && mDesktopInput.updatePointerPosition(
                        displayId, x, y, action, downTime);
    }

    @Override
    public boolean activateDesktopPointer(final int displayId) {
        return !mDestroyed
                && mDesktopInput != null
                && mDesktopInput.activatePointer(displayId);
    }

    @Override
    public boolean clickDesktopPointer(
            final int displayId,
            final int button) {
        return !mDestroyed
                && mDesktopInput != null
                && mDesktopInput.clickPointer(displayId, button);
    }

    @Override
    public boolean scrollDesktopPointer(
            final int displayId,
            final float amount) {
        return !mDestroyed
                && mDesktopInput != null
                && mDesktopInput.scrollPointer(displayId, amount);
    }

    @Override
    public boolean updateDesktopTextInput(
            final int displayId,
            final int action,
            final String text,
            final int arg1,
            final int arg2,
            final int arg3) {
        return !mDestroyed
                && mDesktopInput != null
                && mDesktopInput.updateTextInput(
                        displayId, action, text, arg1, arg2, arg3);
    }

    @Override
    public boolean beginDesktopTextInput(final int displayId) {
        return !mDestroyed
                && mDesktopInput != null
                && mDesktopInput.beginTextInput(displayId);
    }

    @Override
    public void endDesktopTextInput(final int displayId) {
        if (mDestroyed || mDesktopInput == null) {
            return;
        }
        mDesktopInput.endTextInput(displayId);
    }

    @Override
    public boolean showStart() {
        return postIfAlive(this::showStartOnDesktop);
    }

    @Override
    public boolean toggleDesktopWorkspace() {
        return !mDestroyed && DesktopRuntimeBridge.toggleDesktopWorkspace();
    }

    @Override
    public boolean restoreLastVisibleWindows() {
        return !mDestroyed && DesktopRuntimeBridge.restoreLastVisibleWindows();
    }

    @Override
    public boolean advanceAltTab(final boolean reverse) {
        return !mDestroyed && DesktopRuntimeBridge.advanceAltTab(reverse);
    }

    @Override
    public boolean finishAltTab() {
        return !mDestroyed && DesktopRuntimeBridge.finishAltTab();
    }

    @Override
    public boolean cancelAltTab() {
        return !mDestroyed && DesktopRuntimeBridge.cancelAltTab();
    }

    @Override
    public boolean toggleShortcutHelp() {
        return !mDestroyed && DesktopRuntimeBridge.toggleShortcutHelp();
    }

    @Override
    public boolean toggleNotificationCenter() {
        return !mDestroyed
                && DesktopRuntimeBridge.toggleNotificationCenter();
    }

    @Override
    public boolean toggleSystemPanel() {
        return !mDestroyed && DesktopRuntimeBridge.toggleSystemPanel();
    }

    @Override
    public boolean openSettings() {
        return !mDestroyed && DesktopRuntimeBridge.openSettings();
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
    public void releaseDesktopTaskSession(final Runnable completion) {
        final Runnable finish = completion == null ? () -> { } : completion;
        final Handler handler = mHandler;
        if (mDestroyed || handler == null) {
            finish.run();
            return;
        }
        final Runnable release = () -> {
            if (!mDestroyed && mDesktopTaskRuntime != null) {
                mDesktopTaskRuntime.releaseSession(finish);
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
        MagicDeskRuntime.attach(this);
        ShellAccess.addStateListener(mShellStateListener);
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
    }

    private void initialize() {
        if (mInitialized) {
            return;
        }
        mInitialized = true;
        mDesktopInput = new RuntimeDesktopInputCoordinator(
                this,
                mHandler,
                mPlatform.features(),
                mPhoneUi,
                this::updateNotification);
        mDesktopInput.start();
        mKeepDesktopAwake = MagicDeskSettings.load().keepDesktopAwake;
        mDisplayCoordinator = new RuntimeDisplayCoordinator(
                this, mHandler, this::handleDisplayStateChanged);
        mDesktopSession = new RuntimeDesktopSessionCoordinator(
                this,
                mHandler,
                mProjection,
                displayId -> mDisplayCoordinator.hasDisplay(displayId),
                new RuntimeDesktopSessionCoordinator.Listener() {
                    @Override
                    public void onOwnershipRefreshed(
                            final boolean changed) {
                        handleDesktopOwnershipRefreshed(changed);
                    }

                    @Override
                    public void onConsoleModeChanged() {
                        mDesktopInput.onConsoleModeChanged();
                        updateDesktopTasks();
                    }
                });
        mDesktopTaskRuntime = new RuntimeDesktopTaskCoordinator(
                this,
                mHandler,
                mWindowing,
                mPhoneUi,
                mDesktopSession::onTaskStackChanged);
        mDesktopSession.start();
        mDisplayCoordinator.start();
        mDesktopInput.reconcileSoftwareKeyboardPolicy();
        registerConfigurationReceiver();
        if (mProjection.observedSettingKeys().length > 0) {
            registerConsoleModeObserver();
        }
        if (ShellAccess.isReady()) {
            updatePlatformCaptionTarget();
        } else {
            mProjection.setCaptionTransport(
                    PlatformProjectionDriver.Transport.NONE);
        }
        mDesktopInput.reconcileRuntime(desktopDisplayId());
        updateDesktopTasks();
        mPlatform.startRuntime(this);
        mMcpRuntime.reconcile();
    }

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        startForeground(NOTIFICATION_ID, buildNotification());
        initialize();
        if (intent != null) {
            if (ACTION_OPEN_CONTROL_PANEL.equals(intent.getAction())) {
                openPhoneControlPanel();
            } else if (ACTION_OPEN_TOUCHPAD.equals(intent.getAction())
                    && ShellAccess.isReady()) {
                ConsoleModeSwitcher.openTouchpad();
            }
        }
        mDesktopInput.reconcileRuntime(desktopDisplayId());
        updateDesktopTasks();
        mDesktopSession.schedulePhoneHomeRecovery();
        return START_NOT_STICKY;
    }

    private void showStartOnDesktop() {
        if (DesktopRuntimeBridge.showStart()) {
            return;
        }
        final ActivityOptions options = ActivityOptions.makeBasic();
        final int displayId = mProjection.activeDesktopDisplayId(this);
        if (displayId > 0) {
            options.setLaunchDisplayId(displayId);
        }
        DesktopShellActivity.setLaunchWindowingMode(options, 5);
        startActivity(
                DesktopShellActivity.createShowStartIntent(this),
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
        if (mConfigurationReceiver != null) {
            unregisterReceiver(mConfigurationReceiver);
            mConfigurationReceiver = null;
        }
        if (mConsoleModeObserver != null) {
            getContentResolver().unregisterContentObserver(mConsoleModeObserver);
            mConsoleModeObserver = null;
        }
        if (mHandler != null) {
            if (mDesktopSession != null) {
                mDesktopSession.destroy();
            }
        }
        if (mDesktopTaskRuntime != null) {
            mDesktopTaskRuntime.destroy();
        }
        if (mDesktopInput != null) {
            mDesktopInput.destroy();
        }
        if (mSessionWakeLock != null) {
            mSessionWakeLock.release();
        }
        if (mMcpRuntime != null) {
            mMcpRuntime.close();
            mMcpRuntime = null;
        }
        mPlatform.stopRuntime();
        mPhoneUi.requestPhoneScreenRestore();
        if (mHandler != null) {
            mHandler.removeCallbacksAndMessages(null);
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(final Intent intent) {
        return null;
    }

    private void handleDisplayStateChanged(
            final int displayId, final boolean displayRemoved) {
        if (displayRemoved && mDesktopInput != null) {
            mDesktopInput.onDesktopDisplayRemoved(displayId);
        }
        if (mDesktopSession != null) {
            mDesktopSession.handleDisplayStateChanged(
                    displayId, displayRemoved);
        }
        if (mDesktopInput != null) {
            mDesktopInput.reconcileSoftwareKeyboardPolicy();
        }
    }

    private void registerConfigurationReceiver() {
        mConfigurationReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(final Context context, final Intent intent) {
                if (Intent.ACTION_CONFIGURATION_CHANGED.equals(intent.getAction())) {
                    if (mDesktopInput != null) {
                        mDesktopInput.onConfigurationChanged();
                    }
                } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())
                        && mPhoneUi.isPhoneScreenControlActive()) {
                    ConsoleModeSwitcher.setPhoneScreenOff(false, null);
                }
            }
        };
        final IntentFilter filter =
                new IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(mConfigurationReceiver, filter);
    }

    private void registerConsoleModeObserver() {
        mConsoleModeObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(final boolean selfChange) {
                if (mDesktopSession != null) {
                    mDesktopSession.handleConsoleStateMaybeChanged();
                }
            }
        };
        for (final String setting : mProjection.observedSettingKeys()) {
            getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(setting),
                    false,
                    mConsoleModeObserver);
        }
    }

    private void handleShellStateChanged() {
        if (mDestroyed || !mInitialized) {
            return;
        }
        mDesktopSession.refreshOwnership();
        mDesktopInput.reconcileRuntime(desktopDisplayId());
        updateDesktopTasks();
        if (ShellAccess.isReady()) {
            if (mDesktopSession.ownsConsoleDesktop()) {
                mPhoneUi.hideExternalAssistPanel();
            }
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
        mDesktopInput.setDesktopDisplay(desktopDisplayId(), changed);
        if (!changed) {
            updateSessionWakeLock();
            return;
        }
        updateSessionWakeLock();
        Log.i(TAG, "ownsExternalDesktop=" + ownsExternalDesktop()
                + " desktopDisplay=" + desktopDisplayId()
                + " consoleDisplay=" + mDesktopSession.consoleDisplayId());
        if (mDesktopSession.ownsConsoleDesktop()) {
            mPhoneUi.hideExternalAssistPanel();
        }
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
        mKeepDesktopAwake = MagicDeskSettings.load().keepDesktopAwake;
        updateSessionWakeLock();
        if (mMcpRuntime != null) {
            mMcpRuntime.reconcile();
        }
    }

    private void updatePlatformCaptionTarget() {
        ConsoleModeSwitcher.updateExternalTaskCaptionTarget(
                desktopDisplayId(),
                mDesktopSession.ownsConsoleDesktop());
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
        final Intent openControlPanelIntent =
                new Intent(this, MagicDeskRuntimeService.class)
                        .setAction(ACTION_OPEN_CONTROL_PANEL);
        final PendingIntent openControlPanelPendingIntent =
                PendingIntent.getForegroundService(
                        this,
                        OPEN_CONTROL_PANEL_REQUEST_CODE,
                        openControlPanelIntent,
                        pendingIntentFlags());
        final Intent openTouchpadIntent = new Intent(this, MagicDeskRuntimeService.class)
                .setAction(ACTION_OPEN_TOUCHPAD);
        final PendingIntent openTouchpadPendingIntent =
                PendingIntent.getForegroundService(
                        this,
                        OPEN_TOUCHPAD_REQUEST_CODE,
                        openTouchpadIntent,
                        pendingIntentFlags());
        final String text = mOperationStatus != null
                ? mOperationStatus
                : (mDesktopInput != null
                        && mDesktopInput.hasHardwareKeyboard()
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
        if (ShellAccess.isReady()) {
            builder.addAction(
                        R.drawable.ic_touchpad,
                        getString(R.string.notification_open_touchpad),
                        openTouchpadPendingIntent);
        }
        return builder.build();
    }

    private void openPhoneControlPanel() {
        if (PhoneControlPanelLauncher.openWithAndroidApi(this)) {
            Log.i(TAG, "opened phone control panel from notification");
        }
    }

    private static int pendingIntentFlags() {
        return PendingIntent.FLAG_UPDATE_CURRENT
                | PendingIntent.FLAG_IMMUTABLE;
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
