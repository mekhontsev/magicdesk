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

import java.lang.ref.WeakReference;

public final class MagicDeskRuntimeService extends Service {
    private static final String TAG = "MagicDeskWatcher";
    private static final String CHANNEL_ID = "magicdesk";
    private static final String ACTION_OPEN_CONTROL_PANEL =
            "io.github.mekhontsev.magicdesk.action.OPEN_CONTROL_PANEL";
    private static final String ACTION_OPEN_TOUCHPAD =
            "io.github.mekhontsev.magicdesk.action.OPEN_TOUCHPAD";
    private static final int NOTIFICATION_ID = 1;
    private static final int OPEN_TOUCHPAD_REQUEST_CODE = 1;
    private static final int OPEN_CONTROL_PANEL_REQUEST_CODE = 2;
    private static WeakReference<MagicDeskRuntimeService> sInstance =
            new WeakReference<>(null);

    private final PlatformDriver mPlatform = PlatformDrivers.current();
    private final PlatformPhoneUiDriver mPhoneUi = mPlatform.phoneUi();
    private final PlatformProjectionDriver mProjection =
            mPlatform.projection();
    private final PlatformWindowingDriver mWindowing = mPlatform.windowing();

    private Handler mHandler;
    private RuntimeDesktopSessionCoordinator mDesktopSession;
    private RuntimeDesktopInputCoordinator mDesktopInput;
    private RuntimeDisplayCoordinator mDisplayCoordinator;
    private DesktopSessionWakeLock mSessionWakeLock;
    private DesktopTaskController mDesktopTasks;
    private BroadcastReceiver mConfigurationReceiver;
    private ContentObserver mConsoleModeObserver;
    private boolean mDestroyed;
    private boolean mInitialized;
    private String mOperationStatus;
    private boolean mKeepDesktopAwake;

    private final ShellAccess.StateListener mShellStateListener =
            snapshot -> {
                final Handler handler = mHandler;
                if (handler != null) {
                    handler.post(this::handleShellStateChanged);
                }
            };

    public static void start(final Context context) {
        final Intent intent = new Intent(context, MagicDeskRuntimeService.class);
        context.startForegroundService(intent);
    }

    public static void stop(final Context context) {
        context.stopService(new Intent(context, MagicDeskRuntimeService.class));
    }

    public static void refreshNotificationIfRunning() {
        final MagicDeskRuntimeService service = sInstance.get();
        if (service == null || service.mDestroyed) {
            return;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            service.updateNotification();
        } else if (service.mHandler != null) {
            service.mHandler.post(service::updateNotification);
        }
    }

    static void setOperationStatusIfRunning(final String status) {
        final MagicDeskRuntimeService service = sInstance.get();
        if (service == null || service.mDestroyed || service.mHandler == null) {
            return;
        }
        service.mHandler.post(() -> {
            service.mOperationStatus = status;
            service.updateNotification();
        });
    }

    static void refreshDesktopTasksIfRunning() {
        final MagicDeskRuntimeService service = sInstance.get();
        if (service == null || service.mDestroyed || service.mHandler == null
                || service.mDesktopSession == null) {
            return;
        }
        service.mHandler.post(() -> {
            service.mDesktopSession.refreshOwnership();
            service.updateDesktopTasks();
        });
    }

    static void refreshSettingsIfRunning() {
        final MagicDeskRuntimeService service = sInstance.get();
        if (service == null || service.mDestroyed || service.mHandler == null) {
            return;
        }
        service.mHandler.post(service::refreshRuntimeSettings);
    }

    static boolean isSessionWakeLockHeldIfRunning() {
        final MagicDeskRuntimeService service = sInstance.get();
        return service != null
                && !service.mDestroyed
                && service.mSessionWakeLock != null
                && service.mSessionWakeLock.isHeld();
    }

    static void reconcileFailedDesktopLaunchIfRunning(final int displayId) {
        final MagicDeskRuntimeService service = sInstance.get();
        if (service == null || service.mDestroyed || service.mHandler == null
                || service.mDesktopSession == null
                || displayId <= android.view.Display.DEFAULT_DISPLAY) {
            return;
        }
        service.mHandler.post(() -> service.mDesktopSession
                .reconcileFailedDesktopLaunch(displayId));
    }

    static void restorePhonePanelAfterExternalDesktopRemovalIfRunning(
            final int displayId) {
        final MagicDeskRuntimeService service = sInstance.get();
        if (service == null || service.mDestroyed || service.mHandler == null
                || service.mDesktopSession == null
                || displayId <= android.view.Display.DEFAULT_DISPLAY) {
            return;
        }
        service.runOnHandler(() -> service.mDesktopSession
                .restorePhonePanelAfterExternalDesktopRemoval(displayId));
    }

    static void scheduleLocalDesktopCleanupIfRunning() {
        final MagicDeskRuntimeService service = sInstance.get();
        if (service == null || service.mDestroyed || service.mHandler == null
                || service.mDesktopSession == null) {
            return;
        }
        service.mDesktopSession.scheduleLocalDesktopCleanup();
    }

    static boolean isDesktopMouseBridgeReadyIfRunning() {
        final MagicDeskRuntimeService service = sInstance.get();
        return service != null
                && !service.mDestroyed
                && service.mDesktopInput != null
                && service.mDesktopInput.isMouseBridgeReady();
    }

    static boolean capturePointerPositionIfRunning() {
        final MagicDeskRuntimeService service = sInstance.get();
        return service != null
                && !service.mDestroyed
                && service.mDesktopInput != null
                && service.mDesktopInput.capturePointerPosition();
    }

    static void restorePointerPositionOnNextMotionIfRunning() {
        final MagicDeskRuntimeService service = sInstance.get();
        if (service == null || service.mDestroyed
                || service.mDesktopInput == null) {
            return;
        }
        service.mDesktopInput.restorePointerPositionOnNextMotion();
    }

    static Point getDesktopPointerPositionIfRunning(
            final int displayId) {
        final MagicDeskRuntimeService service = sInstance.get();
        return service != null
                && !service.mDestroyed
                && service.mDesktopInput != null
                ? service.mDesktopInput.getPointerPosition(displayId) : null;
    }

    static boolean updateDesktopPointerPositionIfRunning(
            final int displayId,
            final int x,
            final int y,
            final int action,
            final long downTime) {
        final MagicDeskRuntimeService service = sInstance.get();
        return service != null
                && !service.mDestroyed
                && service.mDesktopInput != null
                && service.mDesktopInput.updatePointerPosition(
                        displayId, x, y, action, downTime);
    }

    static boolean activateDesktopPointerIfRunning(final int displayId) {
        final MagicDeskRuntimeService service = sInstance.get();
        return service != null
                && !service.mDestroyed
                && service.mDesktopInput != null
                && service.mDesktopInput.activatePointer(displayId);
    }

    static boolean clickDesktopPointerIfRunning(
            final int displayId,
            final int button) {
        final MagicDeskRuntimeService service = sInstance.get();
        return service != null
                && !service.mDestroyed
                && service.mDesktopInput != null
                && service.mDesktopInput.clickPointer(displayId, button);
    }

    static boolean scrollDesktopPointerIfRunning(
            final int displayId,
            final float amount) {
        final MagicDeskRuntimeService service = sInstance.get();
        return service != null
                && !service.mDestroyed
                && service.mDesktopInput != null
                && service.mDesktopInput.scrollPointer(displayId, amount);
    }

    static boolean updateDesktopTextInputIfRunning(
            final int displayId,
            final int action,
            final String text,
            final int arg1,
            final int arg2,
            final int arg3) {
        final MagicDeskRuntimeService service = sInstance.get();
        return service != null
                && !service.mDestroyed
                && service.mDesktopInput != null
                && service.mDesktopInput.updateTextInput(
                        displayId, action, text, arg1, arg2, arg3);
    }

    static boolean beginDesktopTextInputIfRunning(final int displayId) {
        final MagicDeskRuntimeService service = sInstance.get();
        return service != null
                && !service.mDestroyed
                && service.mDesktopInput != null
                && service.mDesktopInput.beginTextInput(displayId);
    }

    static void endDesktopTextInputIfRunning(final int displayId) {
        final MagicDeskRuntimeService service = sInstance.get();
        if (service == null
                || service.mDestroyed
                || service.mDesktopInput == null) {
            return;
        }
        service.mDesktopInput.endTextInput(displayId);
    }

    static boolean showStartIfRunning() {
        final MagicDeskRuntimeService service = sInstance.get();
        if (service == null || service.mDestroyed || service.mHandler == null) {
            return false;
        }
        service.mHandler.post(service::showStart);
        return true;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mDestroyed = false;
        sInstance = new WeakReference<>(this);
        mHandler = new Handler(Looper.getMainLooper());
        mSessionWakeLock = new DesktopSessionWakeLock(this);
        ShellAccess.addStateListener(mShellStateListener);
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
    }

    private void initialize() {
        if (mInitialized) {
            return;
        }
        mInitialized = true;
        mDesktopTasks = new DesktopTaskController(
                this,
                mHandler,
                this::handleTaskStackChanged,
                mWindowing,
                mPhoneUi);
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

    private void showStart() {
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
        if (sInstance.get() == this) {
            sInstance = new WeakReference<>(null);
        }
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
        if (mDesktopTasks != null) {
            mDesktopTasks.destroy();
        }
        if (mDesktopInput != null) {
            mDesktopInput.destroy();
        }
        if (mSessionWakeLock != null) {
            mSessionWakeLock.release();
        }
        mPlatform.stopRuntime();
        mPhoneUi.requestPhoneScreenRestore();
        super.onDestroy();
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
                        mDesktopInput.scheduleDeviceRefresh();
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

    private void handleTaskStackChanged() {
        if (mDesktopSession != null) {
            mDesktopSession.onTaskStackChanged();
        }
    }

    private void runOnHandler(final Runnable runnable) {
        if (Looper.myLooper() == mHandler.getLooper()) {
            runnable.run();
        } else {
            mHandler.post(runnable);
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
        if (mDesktopTasks == null) {
            return;
        }
        final int displayId =
                DesktopRuntimeBridge.getActiveDesktopDisplayId();
        if (displayId >= 0 && ShellAccess.isReady()) {
            mDesktopTasks.setTaskWatcherEnabled(true);
            mDesktopTasks.start(displayId);
        } else {
            mDesktopTasks.stop();
            mDesktopTasks.setTaskWatcherEnabled(ShellAccess.isReady());
        }
    }

    private void updateNotification() {
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
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(android.view.Display.DEFAULT_DISPLAY);
        startActivity(
                ControlActivity.createLaunchIntent(this),
                options.toBundle());
        Log.i(TAG, "opened phone control panel from notification");
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
}
