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
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.List;

public final class MagicDeskRuntimeService extends Service {
    private static final String TAG = "MagicDeskWatcher";
    private static final String CHANNEL_ID = "magicdesk";
    private static final String ACTION_SHOW_MAGIC_DESK =
            "io.github.mekhontsev.magicdesk.action.SHOW_MAGIC_DESK";
    private static final String ACTION_OPEN_TOUCHPAD =
            "io.github.mekhontsev.magicdesk.action.OPEN_TOUCHPAD";
    private static final int NOTIFICATION_ID = 1;
    private static final int OPEN_TOUCHPAD_REQUEST_CODE = 1;
    private static final int SHOW_MAGIC_DESK_REQUEST_CODE = 2;
    private static final long LOCAL_DESKTOP_CLEANUP_DELAY_MILLIS = 500;
    private static final String CONSOLE_DISPLAY_STATE = "app_mirror_displayid";
    private static WeakReference<MagicDeskRuntimeService> sInstance =
            new WeakReference<>(null);

    private Handler mHandler;
    private RuntimeDisplayCoordinator mDisplayCoordinator;
    private RuntimeInputCoordinator mInputCoordinator;
    private boolean mHasHardwareKeyboard;
    private boolean mHasExternalMouse;
    private String mExternalInputDeviceSignature;
    private boolean mConsoleModeActive;
    private int mConsoleDisplayId;
    private boolean mConsoleExitRecoveryPending;
    private boolean mPhoneHomeRecoveryInFlight;
    private boolean mPhoneHomeRecoveryAgain;
    private boolean mKeyboardWatcherRunning;
    private ConsoleMouseBridge mConsoleMouseBridge;
    private DesktopTaskController mDesktopTasks;
    private BroadcastReceiver mConfigurationReceiver;
    private ContentObserver mConsoleModeObserver;
    private boolean mDestroyed;
    private boolean mInitialized;
    private boolean mLocalDesktopCleanupInFlight;
    private int mInputSourceRefreshGeneration;

    private final ShellAccess.StateListener mShellStateListener =
            snapshot -> {
                final Handler handler = mHandler;
                if (handler != null) {
                    handler.post(this::handleShellStateChanged);
                }
            };

    private final Runnable mPhoneHomeRecoveryRunnable =
            this::restorePrimaryPhoneHomeIfNeeded;
    private final Runnable mLocalDesktopCleanupRunnable =
            this::cleanupClosedLocalDesktop;

    public static void start(final Context context) {
        final Intent intent = new Intent(context, MagicDeskRuntimeService.class);
        context.startForegroundService(intent);
    }

    public static void stop(final Context context) {
        context.stopService(new Intent(context, MagicDeskRuntimeService.class));
    }

    static void refreshNotificationIfRunning() {
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

    static void refreshDesktopTasksIfRunning() {
        final MagicDeskRuntimeService service = sInstance.get();
        if (service == null || service.mDestroyed || service.mHandler == null) {
            return;
        }
        service.mHandler.post(service::updateDesktopTasks);
    }

    static void scheduleLocalDesktopCleanupIfRunning() {
        final MagicDeskRuntimeService service = sInstance.get();
        if (service == null || service.mDestroyed || service.mHandler == null) {
            return;
        }
        service.scheduleLocalDesktopCleanup();
    }

    static boolean isConsoleMouseBridgeReadyIfRunning() {
        final MagicDeskRuntimeService service = sInstance.get();
        return service != null
                && !service.mDestroyed
                && service.mConsoleMouseBridge != null
                && service.mConsoleMouseBridge.isReady();
    }

    static boolean capturePointerPositionIfRunning() {
        final MagicDeskRuntimeService service = sInstance.get();
        if (service == null
                || service.mDestroyed
                || service.mConsoleMouseBridge == null
                || !service.mConsoleMouseBridge.isReady()
                || !ShellAccess.capturePointerPosition()) {
            return false;
        }
        return true;
    }

    static void restorePointerPositionOnNextMotionIfRunning() {
        final MagicDeskRuntimeService service = sInstance.get();
        if (service == null || service.mDestroyed
                || service.mConsoleMouseBridge == null) {
            return;
        }
        service.mConsoleMouseBridge
                .restorePointerPositionIfDisplacedOnNextMotion();
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
        ShellAccess.addStateListener(mShellStateListener);
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
    }

    private void initialize() {
        if (mInitialized) {
            return;
        }
        mInitialized = true;
        mDesktopTasks = new DesktopTaskController(this, mHandler);
        mConsoleMouseBridge = new ConsoleMouseBridge(this);
        mInputCoordinator = new RuntimeInputCoordinator(
                this, mHandler, this::handleInputStateChanged);
        final RuntimeInputCoordinator.Snapshot inputState =
                mInputCoordinator.start();
        mHasHardwareKeyboard = inputState.hardwareKeyboard;
        mHasExternalMouse = inputState.externalMouse;
        mExternalInputDeviceSignature = inputState.deviceSignature;
        mDisplayCoordinator = new RuntimeDisplayCoordinator(
                this, mHandler, this::handleDisplayStateChanged);
        mDisplayCoordinator.start();
        mConsoleDisplayId = getConsoleDisplayId();
        mConsoleModeActive = mConsoleDisplayId > 0;
        registerConfigurationReceiver();
        registerConsoleModeObserver();
        if (ShellAccess.isReady()) {
            ConsoleModeSwitcher.setExternalTaskCaptionsEnabled(
                    mConsoleModeActive);
        } else {
            NubiaCaptionVisibilityManager.setEnabled(false);
        }
        updateKeyboardWatcher();
        updateConsoleMouseBridge();
        updateDesktopTasks();
        if (LocalDesktopSessionState.isCleanupPending(this)) {
            scheduleLocalDesktopCleanup();
        }
        RedmagicHardwareController.start(this);
        logInputState();
        Log.i(TAG, "started, hardwareKeyboard=" + mHasHardwareKeyboard
                + " externalMouse=" + mHasExternalMouse);
    }

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        startForeground(NOTIFICATION_ID, buildNotification());
        initialize();
        if (intent != null) {
            if (ACTION_SHOW_MAGIC_DESK.equals(intent.getAction())) {
                if (ShellAccess.isReady()) {
                    ConsoleModeSwitcher.showMagicDesk();
                } else {
                    startActivity(DeviceSetupActivity.createLaunchIntent(this));
                }
            } else if (ACTION_OPEN_TOUCHPAD.equals(intent.getAction())
                    && ShellAccess.isReady()) {
                ConsoleModeSwitcher.openTouchpad();
            }
        }
        updateKeyboardWatcher();
        updateConsoleMouseBridge();
        updateDesktopTasks();
        schedulePhoneHomeRecovery();
        return START_NOT_STICKY;
    }

    private void showStart() {
        if (DesktopRuntimeBridge.showStart()) {
            return;
        }
        final ActivityOptions options = ActivityOptions.makeBasic();
        final int displayId = Settings.Global.getInt(
                getContentResolver(), CONSOLE_DISPLAY_STATE, -1);
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
        if (mInputCoordinator != null) {
            mInputCoordinator.stop();
        }
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
            mHandler.removeCallbacks(mPhoneHomeRecoveryRunnable);
            mHandler.removeCallbacks(mLocalDesktopCleanupRunnable);
        }
        if (mDesktopTasks != null) {
            mDesktopTasks.destroy();
        }
        if (mConsoleMouseBridge != null) {
            mConsoleMouseBridge.stop();
        }
        KeyboardShortcutWatcher.stop();
        RedmagicHardwareController.stop();
        PhoneDisplayGuard.requestRestore();
        FreeformLaunchAnchorActivity.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(final Intent intent) {
        return null;
    }

    private void handleInputStateChanged(
            final RuntimeInputCoordinator.Snapshot inputState,
            final boolean keyboardChanged,
            final boolean mouseChanged,
            final boolean inputInventoryChanged) {
        if (!keyboardChanged && !mouseChanged && !inputInventoryChanged) {
            return;
        }
        mHasHardwareKeyboard = inputState.hardwareKeyboard;
        mHasExternalMouse = inputState.externalMouse;
        mExternalInputDeviceSignature = inputState.deviceSignature;
        Log.i(TAG, "hardwareKeyboard=" + mHasHardwareKeyboard
                + " externalMouse=" + mHasExternalMouse
                + " inputInventoryChanged=" + inputInventoryChanged);
        logInputState();
        if (keyboardChanged) {
            updateNotification();
        }
        if (mConsoleModeActive) {
            updateKeyboardWatcher();
            updateConsoleMouseBridge();
            refreshConsoleInputSources();
            return;
        }
        if (keyboardChanged) {
            updateKeyboardWatcher();
        } else if ((mouseChanged || inputInventoryChanged)
                && mKeyboardWatcherRunning) {
            restartKeyboardWatcher();
        }
        updateConsoleMouseBridge();
    }

    private void handleDisplayStateChanged(final boolean displayRemoved) {
        handleConsoleStateMaybeChanged();
        if (displayRemoved) {
            schedulePhoneHomeRecovery();
        }
    }

    private void registerConfigurationReceiver() {
        mConfigurationReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(final Context context, final Intent intent) {
                if (Intent.ACTION_CONFIGURATION_CHANGED.equals(intent.getAction())) {
                    if (mInputCoordinator != null) {
                        mInputCoordinator.scheduleRefresh();
                    }
                } else if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())
                        && PhoneDisplayGuard.isActive()) {
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
                handleConsoleStateMaybeChanged();
            }
        };
        getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(CONSOLE_DISPLAY_STATE),
                false,
                mConsoleModeObserver);
    }

    private void handleConsoleStateMaybeChanged() {
        final int consoleDisplayId = getConsoleDisplayId();
        final boolean consoleModeActive = consoleDisplayId > 0;
        if (consoleModeActive == mConsoleModeActive
                && consoleDisplayId == mConsoleDisplayId) {
            return;
        }
        final boolean wasConsoleModeActive = mConsoleModeActive;
        final boolean activeStateChanged = consoleModeActive != wasConsoleModeActive;
        mConsoleModeActive = consoleModeActive;
        mConsoleDisplayId = consoleDisplayId;
        ++mInputSourceRefreshGeneration;
        Log.i(TAG, "consoleMode=" + mConsoleModeActive
                + " display=" + mConsoleDisplayId);
        if (activeStateChanged) {
            restartKeyboardWatcher();
            if (consoleModeActive) {
                mConsoleExitRecoveryPending = false;
            }
        }
        updateConsoleMouseBridge();
        if (ShellAccess.isReady()) {
            ConsoleModeSwitcher.setExternalTaskCaptionsEnabled(consoleModeActive);
        }
        updateDesktopTasks();
        if (wasConsoleModeActive && !consoleModeActive
                && ShellAccess.isReady()) {
            ConsoleModeSwitcher.setPhoneScreenOff(false, null);
        }
        if (wasConsoleModeActive && !consoleModeActive) {
            mConsoleExitRecoveryPending = true;
            if (ShellAccess.isReady()) {
                schedulePhoneHomeRecovery();
            } else {
                PhoneHomeRecoveryController.restoreAfterConsoleExit(this);
                mConsoleExitRecoveryPending = false;
            }
        }
    }

    private void schedulePhoneHomeRecovery() {
        if (mDestroyed || mHandler == null || !ShellAccess.isReady()) {
            return;
        }
        mHandler.removeCallbacks(mPhoneHomeRecoveryRunnable);
        mHandler.post(mPhoneHomeRecoveryRunnable);
    }

    private void restorePrimaryPhoneHomeIfNeeded() {
        if (mDestroyed || !ShellAccess.isReady()) {
            return;
        }
        if (mPhoneHomeRecoveryInFlight) {
            mPhoneHomeRecoveryAgain = true;
            return;
        }
        mPhoneHomeRecoveryInFlight = true;
        final boolean includeStrandedDesktop =
                PhoneHomeRecoveryController.shouldRestoreStrandedDesktop(
                        mConsoleModeActive,
                        mConsoleExitRecoveryPending);
        PhoneHomeRecoveryController.restoreIfNeeded(
                includeStrandedDesktop,
                settled -> mHandler.post(() -> {
                    mPhoneHomeRecoveryInFlight = false;
                    if (!mDestroyed && settled
                            && includeStrandedDesktop) {
                        mConsoleExitRecoveryPending = false;
                    }
                    if (!mDestroyed && mPhoneHomeRecoveryAgain) {
                        mPhoneHomeRecoveryAgain = false;
                        schedulePhoneHomeRecovery();
                    }
                }));
    }

    private void updateKeyboardWatcher() {
        final boolean shouldRun = ShellAccess.isReady()
                && (mHasHardwareKeyboard
                        || (mConsoleModeActive
                                && mKeyboardWatcherRunning));
        if (shouldRun == mKeyboardWatcherRunning) {
            return;
        }

        if (shouldRun) {
            Log.i(TAG, "starting keyboard shortcut watcher");
            KeyboardShortcutWatcher.start(mConsoleModeActive);
        } else {
            Log.i(TAG, "stopping keyboard shortcut watcher");
            KeyboardShortcutWatcher.stop();
        }
        mKeyboardWatcherRunning = shouldRun;
    }

    private void restartKeyboardWatcher() {
        if (mKeyboardWatcherRunning) {
            KeyboardShortcutWatcher.stop();
            mKeyboardWatcherRunning = false;
        }
        updateKeyboardWatcher();
    }

    private void updateConsoleMouseBridge() {
        if (mConsoleMouseBridge == null) {
            return;
        }
        if (shouldRunConsoleMouseBridge()) {
            mConsoleMouseBridge.start();
        } else {
            mConsoleMouseBridge.stop();
        }
    }

    private boolean shouldRunConsoleMouseBridge() {
        return mConsoleModeActive
                && (mHasExternalMouse
                        || (mConsoleMouseBridge != null
                                && mConsoleMouseBridge.isRunning()))
                && ShellAccess.isReady();
    }

    private void refreshConsoleInputSources() {
        if (!mConsoleModeActive || !ShellAccess.isReady()) {
            return;
        }
        final int generation = ++mInputSourceRefreshGeneration;
        final Thread refreshThread = new Thread(() -> {
            try {
                final String inputDump = ShellAccess.run(
                        "/system/bin/dumpsys input");
                final List<ConsoleKeyboardDevice> keyboards =
                        ConsoleInputDeviceDiscovery.findKeyboards(inputDump);
                final List<ConsoleMouseDevice> mice =
                        ConsoleInputDeviceDiscovery.findMice(inputDump);
                mHandler.post(() -> {
                    if (mDestroyed || !mConsoleModeActive
                            || generation != mInputSourceRefreshGeneration) {
                        return;
                    }
                    KeyboardShortcutWatcher.refreshConsoleInputSources(
                            keyboards);
                    if (mConsoleMouseBridge != null) {
                        mConsoleMouseBridge.refreshSources(mice);
                    }
                });
            } catch (IOException error) {
                Log.w(TAG, "Could not refresh console input sources", error);
            }
        }, "MagicDeskInputRefresh");
        refreshThread.setDaemon(true);
        refreshThread.start();
    }

    private void handleShellStateChanged() {
        if (mDestroyed || !mInitialized) {
            return;
        }
        updateKeyboardWatcher();
        updateConsoleMouseBridge();
        updateDesktopTasks();
        if (ShellAccess.isReady()) {
            ConsoleModeSwitcher.setExternalTaskCaptionsEnabled(
                    mConsoleModeActive);
            RedmagicHardwareController.start(this);
            schedulePhoneHomeRecovery();
        } else {
            NubiaCaptionVisibilityManager.setEnabled(false);
            RedmagicHardwareController.stop();
        }
        updateNotification();
        DesktopRuntimeBridge.refreshConsoleControls();
    }

    private int getConsoleDisplayId() {
        try {
            final int displayId = Settings.Global.getInt(
                    getContentResolver(), CONSOLE_DISPLAY_STATE, -1);
            if (mDisplayCoordinator == null
                    || !mDisplayCoordinator.hasDisplay(displayId)) {
                return -1;
            }
            return displayId;
        } catch (RuntimeException e) {
            Log.w(TAG, "failed to read Console Mode state", e);
            return -1;
        }
    }

    private void updateDesktopTasks() {
        if (mDesktopTasks == null) {
            return;
        }
        final int displayId =
                DesktopRuntimeBridge.getActiveDesktopDisplayId();
        if (displayId >= 0
                && ShellAccess.isReady()) {
            mDesktopTasks.start(displayId);
        } else {
            mDesktopTasks.stop();
        }
    }

    private void scheduleLocalDesktopCleanup() {
        if (mDestroyed || mHandler == null) {
            return;
        }
        mHandler.removeCallbacks(mLocalDesktopCleanupRunnable);
        mHandler.postDelayed(
                mLocalDesktopCleanupRunnable,
                LOCAL_DESKTOP_CLEANUP_DELAY_MILLIS);
    }

    private void cleanupClosedLocalDesktop() {
        if (mDestroyed
                || mLocalDesktopCleanupInFlight
                || !LocalDesktopSessionState.isCleanupPending(this)
                || DesktopRuntimeBridge.getActiveDesktopDisplayId()
                        == android.view.Display.DEFAULT_DISPLAY) {
            return;
        }
        if (!ShellAccess.isReady()) {
            Log.w(TAG, "pending phone freeform cleanup requires shell task control");
            return;
        }
        mLocalDesktopCleanupInFlight = true;
        TaskRepository.recoverPhoneDesktopTasks(result ->
                mHandler.post(() -> {
                    mLocalDesktopCleanupInFlight = false;
                    if (mDestroyed) {
                        return;
                    }
                    if (result.success) {
                        LocalDesktopSessionState.clearCleanupPending(this);
                        Log.i(TAG, "recovered phone desktop tasks after local desktop");
                    } else {
                        Log.w(TAG, "phone desktop recovery failed: "
                                + result.message);
                        CompatibilityDiagnostics.record(
                                "NUBIA-HOME-003",
                                "Could not clean local desktop tasks before"
                                        + " returning to the phone launcher",
                                result.message);
                    }
                }));
    }

    private void logInputState() {
        if (mInputCoordinator != null) {
            mInputCoordinator.logState(TAG);
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
        final Intent showMagicDeskIntent =
                new Intent(this, MagicDeskRuntimeService.class)
                        .setAction(ACTION_SHOW_MAGIC_DESK);
        final PendingIntent showMagicDeskPendingIntent =
                PendingIntent.getForegroundService(
                        this,
                        SHOW_MAGIC_DESK_REQUEST_CODE,
                        showMagicDeskIntent,
                        pendingIntentFlags());
        final Intent openTouchpadIntent = new Intent(this, MagicDeskRuntimeService.class)
                .setAction(ACTION_OPEN_TOUCHPAD);
        final PendingIntent openTouchpadPendingIntent =
                PendingIntent.getForegroundService(
                        this,
                        OPEN_TOUCHPAD_REQUEST_CODE,
                        openTouchpadIntent,
                        pendingIntentFlags());
        final String text = mHasHardwareKeyboard
                ? getString(R.string.notification_hw_connected)
                : getString(R.string.notification_hw_disconnected);

        final Notification.Builder builder =
                new Notification.Builder(this, CHANNEL_ID);
        builder
                .setSmallIcon(R.drawable.ic_magicdesk)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(showMagicDeskPendingIntent);
        if (ShellAccess.isReady()) {
            builder.addAction(
                        R.drawable.ic_touchpad,
                        getString(R.string.notification_open_touchpad),
                        openTouchpadPendingIntent);
        }
        return builder.build();
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
