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
    private static final String ACTION_OPEN_CONTROL_PANEL =
            "io.github.mekhontsev.magicdesk.action.OPEN_CONTROL_PANEL";
    private static final String ACTION_OPEN_TOUCHPAD =
            "io.github.mekhontsev.magicdesk.action.OPEN_TOUCHPAD";
    private static final int NOTIFICATION_ID = 1;
    private static final int OPEN_TOUCHPAD_REQUEST_CODE = 1;
    private static final int OPEN_CONTROL_PANEL_REQUEST_CODE = 2;
    private static final long LOCAL_DESKTOP_CLEANUP_DELAY_MILLIS = 500;
    private static final long DISPLAY_REMOVAL_WATCHDOG_MILLIS = 2000;
    private static final String SETTINGS = "/system/bin/settings";
    private static final String SHOW_IME_WITH_HARD_KEYBOARD =
            "show_ime_with_hard_keyboard";
    private static WeakReference<MagicDeskRuntimeService> sInstance =
            new WeakReference<>(null);

    private Handler mHandler;
    private RuntimeDisplayCoordinator mDisplayCoordinator;
    private RuntimeInputCoordinator mInputCoordinator;
    private boolean mHasHardwareKeyboard;
    private boolean mHasExternalMouse;
    private String mExternalInputDeviceSignature;
    private boolean mConsoleModeActive;
    private int mOwnedDesktopDisplayId = android.view.Display.INVALID_DISPLAY;
    private boolean mOwnsConsoleDesktop;
    private int mConsoleDisplayId;
    private boolean mConsoleExitRecoveryPending;
    private boolean mPhoneHomeRecoveryInFlight;
    private boolean mPhoneHomeRecoveryAgain;
    private boolean mAllowUnsettledDisplayRecovery;
    private int mRemovedDesktopDisplayId = android.view.Display.INVALID_DISPLAY;
    private boolean mRestorePhonePanelAfterRecovery;
    private boolean mKeyboardWatcherRunning;
    private int mKeyboardRoutingDisplayId = android.view.Display.INVALID_DISPLAY;
    private DesktopMouseBridge mDesktopMouseBridge;
    private DesktopSessionWakeLock mSessionWakeLock;
    private DesktopTaskController mDesktopTasks;
    private BroadcastReceiver mConfigurationReceiver;
    private ContentObserver mConsoleModeObserver;
    private boolean mDestroyed;
    private boolean mInitialized;
    private boolean mLocalDesktopCleanupInFlight;
    private boolean mLocalDesktopExitRecoveryPending;
    private int mInputSourceRefreshGeneration;
    private String mOperationStatus;
    private boolean mShowImeOverrideActive;
    private boolean mKeepDesktopAwake;
    private String mPreviousShowImeWithHardKeyboard;
    private int mPhoneImePolicyDisplayId =
            android.view.Display.INVALID_DISPLAY;

    private final ShellAccess.StateListener mShellStateListener =
            snapshot -> {
                final Handler handler = mHandler;
                if (handler != null) {
                    handler.post(this::handleShellStateChanged);
                }
            };

    private final Runnable mPhoneHomeRecoveryRunnable =
            this::restorePrimaryPhoneHomeIfNeeded;
    private final Runnable mDisplayRemovalWatchdogRunnable = () -> {
        if (mRemovedDesktopDisplayId
                > android.view.Display.DEFAULT_DISPLAY) {
            schedulePhoneHomeRecovery(true);
        }
    };
    private final Runnable mLocalDesktopCleanupRunnable =
            this::cleanupClosedLocalDesktop;

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
        if (service == null || service.mDestroyed || service.mHandler == null) {
            return;
        }
        service.mHandler.post(() -> {
            service.refreshDesktopOwnership();
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
                || displayId <= android.view.Display.DEFAULT_DISPLAY) {
            return;
        }
        service.mHandler.post(() -> {
            if (!service.mDestroyed
                    && service.mDisplayCoordinator != null
                    && !service.mDisplayCoordinator.hasDisplay(displayId)) {
                service.mRemovedDesktopDisplayId = displayId;
                service.scheduleDisplayRemovalWatchdog();
                service.schedulePhoneHomeRecovery();
            }
        });
    }

    static void restorePhonePanelAfterExternalDesktopRemovalIfRunning(
            final int displayId) {
        final MagicDeskRuntimeService service = sInstance.get();
        if (service == null || service.mDestroyed || service.mHandler == null
                || displayId <= android.view.Display.DEFAULT_DISPLAY) {
            return;
        }
        service.runOnHandler(() -> {
            service.mRemovedDesktopDisplayId = displayId;
            service.mRestorePhonePanelAfterRecovery = true;
            service.scheduleDisplayRemovalWatchdog();
            service.schedulePhoneHomeRecovery();
        });
    }

    static void scheduleLocalDesktopCleanupIfRunning() {
        final MagicDeskRuntimeService service = sInstance.get();
        if (service == null || service.mDestroyed || service.mHandler == null) {
            return;
        }
        service.scheduleLocalDesktopCleanup();
    }

    static boolean isDesktopMouseBridgeReadyIfRunning() {
        final MagicDeskRuntimeService service = sInstance.get();
        return service != null
                && !service.mDestroyed
                && service.mDesktopMouseBridge != null
                && service.mDesktopMouseBridge.isReady();
    }

    static boolean capturePointerPositionIfRunning() {
        final MagicDeskRuntimeService service = sInstance.get();
        if (service == null
                || service.mDestroyed
                || service.mDesktopMouseBridge == null
                || !service.mDesktopMouseBridge.isReady()
                || !ShellAccess.capturePointerPosition()) {
            return false;
        }
        return true;
    }

    static void restorePointerPositionOnNextMotionIfRunning() {
        final MagicDeskRuntimeService service = sInstance.get();
        if (service == null || service.mDestroyed
                || service.mDesktopMouseBridge == null) {
            return;
        }
        service.mDesktopMouseBridge
                .restorePointerPositionIfDisplacedOnNextMotion();
    }

    static Point getDesktopPointerPositionIfRunning(
            final int displayId) {
        final MagicDeskRuntimeService service = sInstance.get();
        return service != null
                && !service.mDestroyed
                && displayId == service.mOwnedDesktopDisplayId
                ? ShellAccess.getMousePosition(displayId) : null;
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
                && displayId == service.mOwnedDesktopDisplayId
                && ShellAccess.updateMousePosition(
                        displayId, x, y, action, downTime);
    }

    static boolean activateDesktopPointerIfRunning(final int displayId) {
        final MagicDeskRuntimeService service = sInstance.get();
        return service != null
                && !service.mDestroyed
                && displayId == service.mOwnedDesktopDisplayId
                && service.mDesktopMouseBridge != null
                && service.mDesktopMouseBridge.activatePointer();
    }

    static boolean clickDesktopPointerIfRunning(
            final int displayId,
            final int button) {
        final MagicDeskRuntimeService service = sInstance.get();
        if (service == null
                || service.mDestroyed
                || displayId != service.mOwnedDesktopDisplayId
                || service.mDesktopMouseBridge == null) {
            return false;
        }
        final boolean injected = ShellAccess.injectPointerClick(
                displayId, button);
        if (injected
                && button == android.view.MotionEvent.BUTTON_PRIMARY) {
            endDesktopTextInputIfRunning(displayId);
            beginDesktopTextInputIfRunning(displayId);
        }
        return injected;
    }

    static boolean scrollDesktopPointerIfRunning(
            final int displayId,
            final float amount) {
        final MagicDeskRuntimeService service = sInstance.get();
        return service != null
                && !service.mDestroyed
                && displayId == service.mOwnedDesktopDisplayId
                && service.mDesktopMouseBridge != null
                && service.mDesktopMouseBridge.scrollPointer(amount);
    }

    static boolean updateDesktopTextInputIfRunning(
            final int displayId,
            final int action,
            final String text,
            final int arg1,
            final int arg2,
            final int arg3) {
        final MagicDeskRuntimeService service = sInstance.get();
        if (service == null
                || service.mDestroyed
                || displayId != service.mOwnedDesktopDisplayId) {
            return false;
        }
        if (DesktopRuntimeBridge.dispatchOverlayTextInput(
                displayId, action, text, arg1, arg2, arg3)) {
            return true;
        }
        return ShellAccess.updateMirrorTextInput(
                displayId, action, text, arg1, arg2, arg3);
    }

    static boolean beginDesktopTextInputIfRunning(final int displayId) {
        final MagicDeskRuntimeService service = sInstance.get();
        if (service == null
                || service.mDestroyed
                || displayId != service.mOwnedDesktopDisplayId) {
            return false;
        }
        return DesktopRuntimeBridge.hasOverlayTextInput(displayId)
                || ShellAccess.beginMirrorTextInput(displayId);
    }

    static void endDesktopTextInputIfRunning(final int displayId) {
        final MagicDeskRuntimeService service = sInstance.get();
        if (service == null
                || service.mDestroyed
                || displayId != service.mOwnedDesktopDisplayId) {
            return;
        }
        ShellAccess.endMirrorTextInput(displayId);
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
                this, mHandler, this::handleTaskStackChanged);
        mDesktopMouseBridge = new DesktopMouseBridge(this);
        mInputCoordinator = new RuntimeInputCoordinator(
                this, mHandler, this::handleInputStateChanged);
        final RuntimeInputCoordinator.Snapshot inputState =
                mInputCoordinator.start();
        mHasHardwareKeyboard = inputState.hardwareKeyboard;
        mHasExternalMouse = inputState.externalMouse;
        mExternalInputDeviceSignature = inputState.deviceSignature;
        mKeepDesktopAwake = MagicDeskSettings.load().keepDesktopAwake;
        mDisplayCoordinator = new RuntimeDisplayCoordinator(
                this, mHandler, this::handleDisplayStateChanged);
        mDisplayCoordinator.start();
        mConsoleDisplayId = getConsoleDisplayId();
        mConsoleModeActive = mConsoleDisplayId > 0;
        refreshDesktopOwnership();
        updateShowImeOverride();
        registerConfigurationReceiver();
        if (PlatformDrivers.current().projection()
                .observedSettingKeys().length > 0) {
            registerConsoleModeObserver();
        }
        if (ShellAccess.isReady()) {
            updatePlatformCaptionTarget();
        } else {
            PlatformDrivers.current().projection().setCaptionTransport(
                    PlatformProjectionDriver.Transport.NONE);
        }
        updateKeyboardWatcher();
        updateDesktopMouseBridge();
        updateDesktopTasks();
        if (LocalDesktopSessionState.isCleanupPending(this)) {
            maintainLocalDesktopNavigationGuard();
            scheduleLocalDesktopCleanup();
        }
        PlatformDrivers.current().startRuntime(this);
        logInputState();
        Log.i(TAG, "started, hardwareKeyboard=" + mHasHardwareKeyboard
                + " externalMouse=" + mHasExternalMouse);
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
        updateKeyboardWatcher();
        updateDesktopMouseBridge();
        updateDesktopTasks();
        schedulePhoneHomeRecovery();
        return START_NOT_STICKY;
    }

    private void showStart() {
        if (DesktopRuntimeBridge.showStart()) {
            return;
        }
        final ActivityOptions options = ActivityOptions.makeBasic();
        final int displayId = PlatformDrivers.current().projection()
                .activeDesktopDisplayId(this);
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
            mHandler.removeCallbacks(mDisplayRemovalWatchdogRunnable);
            mHandler.removeCallbacks(mLocalDesktopCleanupRunnable);
        }
        if (mDesktopTasks != null) {
            mDesktopTasks.destroy();
        }
        if (mDesktopMouseBridge != null) {
            mDesktopMouseBridge.stop();
        }
        if (mSessionWakeLock != null) {
            mSessionWakeLock.release();
        }
        restoreShowImeOverride();
        KeyboardShortcutWatcher.stop();
        PlatformDrivers.current().stopRuntime();
        PlatformDrivers.current().phoneUi().requestPhoneScreenRestore();
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
        if (requiresExternalInputBridge()) {
            if (mHasHardwareKeyboard
                    && !KeyboardShortcutWatcher.isFullShortcutMode()) {
                restartKeyboardWatcher();
            } else {
                updateKeyboardWatcher();
            }
            updateDesktopMouseBridge();
            refreshDesktopInputSources();
            return;
        }
        if (keyboardChanged) {
            updateKeyboardWatcher();
        } else if ((mouseChanged || inputInventoryChanged)
                && mKeyboardWatcherRunning) {
            restartKeyboardWatcher();
        }
        updateDesktopMouseBridge();
    }

    private void handleDisplayStateChanged(
            final int displayId, final boolean displayRemoved) {
        final DesktopDisplayTarget desktopTarget = displayRemoved
                ? DesktopRuntimeBridge.getDesktopTarget(displayId)
                : null;
        final boolean activeDesktopRemoved = displayRemoved
                && DesktopRuntimeBridge.getActiveDesktopDisplayId()
                        == displayId;
        final boolean externalDesktopRemoved = isExternalDesktopRemoval(
                displayRemoved,
                displayId,
                mOwnedDesktopDisplayId,
                desktopTarget,
                activeDesktopRemoved);
        if (displayRemoved) {
            PhoneTouchpadController.release(displayId);
            DesktopRuntimeBridge.closeDesktopSession(displayId);
            if (externalDesktopRemoved) {
                mRemovedDesktopDisplayId = displayId;
                mRestorePhonePanelAfterRecovery = true;
                scheduleDisplayRemovalWatchdog();
            }
        }
        handleConsoleStateMaybeChanged();
        refreshDesktopOwnership();
        updateShowImeOverride();
        if (displayRemoved) {
            schedulePhoneHomeRecovery();
        }
    }

    static boolean isExternalDesktopRemoval(
            final boolean displayRemoved,
            final int displayId,
            final int ownedDesktopDisplayId,
            final DesktopDisplayTarget desktopTarget,
            final boolean activeDesktopRemoved) {
        if (!displayRemoved
                || displayId <= android.view.Display.DEFAULT_DISPLAY) {
            return false;
        }
        if (desktopTarget == null) {
            return displayId == ownedDesktopDisplayId;
        }
        return DesktopDisplayDrivers.forTarget(desktopTarget)
                .isSessionDisplayRemoval(
                        desktopTarget,
                        displayId,
                        activeDesktopRemoved);
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
                        && PlatformDrivers.current().phoneUi()
                                .isPhoneScreenControlActive()) {
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
        for (final String setting : PlatformDrivers.current().projection()
                .observedSettingKeys()) {
            getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(setting),
                    false,
                    mConsoleModeObserver);
        }
    }

    private void handleConsoleStateMaybeChanged() {
        final int consoleDisplayId = getConsoleDisplayId();
        final boolean consoleModeActive = consoleDisplayId > 0;
        if (consoleModeActive == mConsoleModeActive
                && consoleDisplayId == mConsoleDisplayId) {
            refreshDesktopOwnership();
            return;
        }
        final boolean wasConsoleModeActive = mConsoleModeActive;
        final int previousConsoleDisplayId = mConsoleDisplayId;
        final boolean activeStateChanged = consoleModeActive != wasConsoleModeActive;
        mConsoleModeActive = consoleModeActive;
        mConsoleDisplayId = consoleDisplayId;
        refreshDesktopOwnership();
        ++mInputSourceRefreshGeneration;
        Log.i(TAG, "consoleMode=" + mConsoleModeActive
                + " display=" + mConsoleDisplayId);
        if (activeStateChanged) {
            if (consoleModeActive) {
                mConsoleExitRecoveryPending = false;
            }
        }
        if (wasConsoleModeActive && !consoleModeActive) {
            DesktopRuntimeBridge.closeDesktopSession(
                    previousConsoleDisplayId);
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
        schedulePhoneHomeRecovery(false);
    }

    private void schedulePhoneHomeRecovery(
            final boolean allowUnsettledDisplayRecovery) {
        if (mDestroyed || mHandler == null || !ShellAccess.isReady()) {
            return;
        }
        mAllowUnsettledDisplayRecovery |=
                allowUnsettledDisplayRecovery;
        mHandler.removeCallbacks(mPhoneHomeRecoveryRunnable);
        mHandler.post(mPhoneHomeRecoveryRunnable);
    }

    private void handleTaskStackChanged() {
        if (mRemovedDesktopDisplayId > android.view.Display.DEFAULT_DISPLAY
                || mConsoleExitRecoveryPending
                || mLocalDesktopExitRecoveryPending) {
            schedulePhoneHomeRecovery();
        }
    }

    private void scheduleDisplayRemovalWatchdog() {
        if (mDestroyed || mHandler == null) {
            return;
        }
        mHandler.removeCallbacks(mDisplayRemovalWatchdogRunnable);
        mHandler.postDelayed(
                mDisplayRemovalWatchdogRunnable,
                DISPLAY_REMOVAL_WATCHDOG_MILLIS);
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
        final boolean allowUnsettledDisplayRecovery =
                mAllowUnsettledDisplayRecovery;
        final boolean includeStrandedDesktop =
                PhoneHomeRecoveryController.shouldRestoreStrandedDesktop(
                        mConsoleModeActive,
                        mConsoleExitRecoveryPending)
                        || mLocalDesktopExitRecoveryPending;
        final boolean localDesktopExitRecoveryPending =
                mLocalDesktopExitRecoveryPending;
        final int removedDisplayId = mRemovedDesktopDisplayId;
        final boolean localDesktopActive =
                DesktopRuntimeBridge.isLocalDesktopActiveOrStarting();
        PhoneHomeRecoveryController.restoreIfNeeded(
                includeStrandedDesktop,
                removedDisplayId,
                localDesktopActive,
                allowUnsettledDisplayRecovery,
                settled -> mHandler.post(() -> {
                    mPhoneHomeRecoveryInFlight = false;
                    final boolean recoveryComplete =
                            isPhoneRecoveryComplete(
                                    settled, mPhoneHomeRecoveryAgain);
                    final boolean restorePhonePanel =
                            mRestorePhonePanelAfterRecovery
                                    && mRemovedDesktopDisplayId
                                            == removedDisplayId
                                    && recoveryComplete;
                    if (!mDestroyed && recoveryComplete
                            && mRemovedDesktopDisplayId == removedDisplayId) {
                        mRemovedDesktopDisplayId =
                                android.view.Display.INVALID_DISPLAY;
                        mAllowUnsettledDisplayRecovery = false;
                        mHandler.removeCallbacks(
                                mDisplayRemovalWatchdogRunnable);
                    }
                    if (!mDestroyed && restorePhonePanel) {
                        mRestorePhonePanelAfterRecovery = false;
                        ConsoleModeSwitcher.restorePhoneAfterExternalDesktop();
                    }
                    if (!mDestroyed && recoveryComplete
                            && includeStrandedDesktop) {
                        mConsoleExitRecoveryPending = false;
                    }
                    if (!mDestroyed && recoveryComplete
                            && localDesktopExitRecoveryPending
                            && mLocalDesktopExitRecoveryPending) {
                        if (!DesktopRuntimeBridge
                                .isLocalDesktopActiveOrStarting()) {
                            LocalDesktopSessionState.clearCleanupPending(this);
                        }
                        mLocalDesktopExitRecoveryPending = false;
                    }
                    if (!mDestroyed && mPhoneHomeRecoveryAgain) {
                        mPhoneHomeRecoveryAgain = false;
                        schedulePhoneHomeRecovery();
                    }
                }));
    }

    static boolean isPhoneRecoveryComplete(
            final boolean settled,
            final boolean rerunQueued) {
        return settled && !rerunQueued;
    }

    private void runOnHandler(final Runnable runnable) {
        if (Looper.myLooper() == mHandler.getLooper()) {
            runnable.run();
        } else {
            mHandler.post(runnable);
        }
    }

    private void updateKeyboardWatcher() {
        final int routingDisplayId = requiresExternalInputBridge()
                ? mOwnedDesktopDisplayId
                : android.view.Display.INVALID_DISPLAY;
        final boolean shouldRun = ShellAccess.isReady()
                && (mHasHardwareKeyboard
                        || routingDisplayId
                                > android.view.Display.DEFAULT_DISPLAY);
        if (mKeyboardWatcherRunning
                && mKeyboardRoutingDisplayId != routingDisplayId) {
            KeyboardShortcutWatcher.stop();
            mKeyboardWatcherRunning = false;
            mKeyboardRoutingDisplayId = android.view.Display.INVALID_DISPLAY;
        }
        if (shouldRun == mKeyboardWatcherRunning) {
            return;
        }

        if (shouldRun) {
            Log.i(TAG, "starting keyboard shortcut watcher display="
                    + routingDisplayId);
            KeyboardShortcutWatcher.start(routingDisplayId);
            mKeyboardRoutingDisplayId = routingDisplayId;
        } else {
            Log.i(TAG, "stopping keyboard shortcut watcher");
            KeyboardShortcutWatcher.stop();
            mKeyboardRoutingDisplayId = android.view.Display.INVALID_DISPLAY;
        }
        mKeyboardWatcherRunning = shouldRun;
    }

    private void restartKeyboardWatcher() {
        if (mKeyboardWatcherRunning) {
            KeyboardShortcutWatcher.stop();
            mKeyboardWatcherRunning = false;
            mKeyboardRoutingDisplayId = android.view.Display.INVALID_DISPLAY;
        }
        updateKeyboardWatcher();
    }

    private void updateDesktopMouseBridge() {
        if (mDesktopMouseBridge == null) {
            return;
        }
        if (shouldRunDesktopMouseBridge()) {
            mDesktopMouseBridge.start();
        } else {
            mDesktopMouseBridge.stop();
        }
    }

    private boolean shouldRunDesktopMouseBridge() {
        return requiresExternalInputBridge()
                && ShellAccess.isReady();
    }

    private void refreshDesktopInputSources() {
        if (!requiresExternalInputBridge() || !ShellAccess.isReady()) {
            return;
        }
        final int generation = ++mInputSourceRefreshGeneration;
        final Thread refreshThread = new Thread(() -> {
            try {
                final String inputDump = ShellAccess.run(
                        "/system/bin/dumpsys input");
                final List<DesktopKeyboardDevice> keyboards =
                        DesktopInputDeviceDiscovery.findKeyboards(inputDump);
                final List<DesktopMouseDevice> mice =
                        DesktopInputDeviceDiscovery.findMice(inputDump);
                mHandler.post(() -> {
                    if (mDestroyed || !requiresExternalInputBridge()
                            || generation != mInputSourceRefreshGeneration) {
                        return;
                    }
                    KeyboardShortcutWatcher.refreshDesktopInputSources(
                            keyboards);
                    if (mDesktopMouseBridge != null) {
                        mDesktopMouseBridge.refreshSources(mice);
                    }
                });
            } catch (IOException error) {
                InputBridgeDiagnostics.noteSourceRefreshFailure(error);
                Log.w(TAG, "Could not refresh desktop input sources", error);
            }
        }, "MagicDeskInputRefresh");
        refreshThread.setDaemon(true);
        refreshThread.start();
    }

    private void handleShellStateChanged() {
        if (mDestroyed || !mInitialized) {
            return;
        }
        refreshDesktopOwnership();
        updateShowImeOverride();
        updateKeyboardWatcher();
        updateDesktopMouseBridge();
        updateDesktopTasks();
        if (ShellAccess.isReady()) {
            if (ownsConsoleDesktop()) {
                PlatformDrivers.current().phoneUi()
                        .hideExternalAssistPanel();
            }
            updatePlatformCaptionTarget();
            PlatformDrivers.current().startRuntime(this);
            maintainLocalDesktopNavigationGuard();
            schedulePhoneHomeRecovery();
        } else {
            PlatformDrivers.current().projection().setCaptionTransport(
                    PlatformProjectionDriver.Transport.NONE);
            PlatformDrivers.current().stopRuntime();
        }
        updateNotification();
        DesktopRuntimeBridge.refreshDesktopControls();
    }

    private int getConsoleDisplayId() {
        final int displayId = PlatformDrivers.current().projection()
                .activeDesktopDisplayId(this);
        if (mDisplayCoordinator == null
                || !mDisplayCoordinator.hasDisplay(displayId)) {
            return -1;
        }
        return displayId;
    }

    private void refreshDesktopOwnership() {
        final int desktopDisplayId =
                DesktopRuntimeBridge.getActiveDesktopDisplayId();
        final boolean ownsConsoleDesktop =
                desktopDisplayId > android.view.Display.DEFAULT_DISPLAY
                        && mConsoleModeActive
                        && desktopDisplayId == mConsoleDisplayId;
        if (desktopDisplayId == mOwnedDesktopDisplayId
                && ownsConsoleDesktop == mOwnsConsoleDesktop) {
            updateExternalImePolicy();
            updateSessionWakeLock();
            return;
        }
        mOwnedDesktopDisplayId = desktopDisplayId;
        mOwnsConsoleDesktop = ownsConsoleDesktop;
        updateShowImeOverride();
        updateExternalImePolicy();
        updateSessionWakeLock();
        Log.i(TAG, "ownsExternalDesktop=" + ownsExternalDesktop()
                + " desktopDisplay=" + desktopDisplayId
                + " consoleDisplay=" + mConsoleDisplayId);
        if (ownsConsoleDesktop()) {
            PlatformDrivers.current().phoneUi().hideExternalAssistPanel();
        }
        updateKeyboardWatcher();
        updateDesktopMouseBridge();
        if (ShellAccess.isReady()) {
            updatePlatformCaptionTarget();
        }
        if (ownsExternalDesktop()) {
            refreshDesktopInputSources();
        }
    }

    private void updateSessionWakeLock() {
        if (mSessionWakeLock == null) {
            return;
        }
        mSessionWakeLock.reconcile(
                mKeepDesktopAwake,
                mOwnedDesktopDisplayId);
    }

    private void refreshRuntimeSettings() {
        mKeepDesktopAwake = MagicDeskSettings.load().keepDesktopAwake;
        updateSessionWakeLock();
    }

    private boolean ownsConsoleDesktop() {
        return mOwnsConsoleDesktop;
    }

    private void updatePlatformCaptionTarget() {
        ConsoleModeSwitcher.updateExternalTaskCaptionTarget(
                mOwnedDesktopDisplayId,
                ownsConsoleDesktop());
    }

    private boolean ownsExternalDesktop() {
        return mOwnedDesktopDisplayId
                > android.view.Display.DEFAULT_DISPLAY;
    }

    private boolean requiresExternalInputBridge() {
        return ownsExternalDesktop()
                && PlatformDrivers.current().features().externalInputBridge;
    }

    private void updateShowImeOverride() {
        final boolean shouldBeActive = ownsExternalDesktop()
                && ShellAccess.isReady();
        if (shouldBeActive == mShowImeOverrideActive) {
            return;
        }
        if (!shouldBeActive) {
            restoreShowImeOverride();
            return;
        }
        try {
            final String previous = ShellAccess.run(
                    SETTINGS + " get secure "
                            + SHOW_IME_WITH_HARD_KEYBOARD).trim();
            ShellAccess.run(
                    SETTINGS + " put secure "
                            + SHOW_IME_WITH_HARD_KEYBOARD + " 1");
            mPreviousShowImeWithHardKeyboard =
                    "0".equals(previous) || "1".equals(previous)
                            ? previous : null;
            mShowImeOverrideActive = true;
            Log.i(TAG, "software keyboard enabled for external desktop");
        } catch (IOException error) {
            Log.w(TAG, "could not enable phone keyboard policy", error);
            CompatibilityDiagnostics.record(
                    "INPUT-IME-001",
                    "Could not enable the on-screen keyboard with hardware input",
                    error.getMessage(),
                    error);
        }
    }

    private void restoreShowImeOverride() {
        if (!mShowImeOverrideActive) {
            return;
        }
        try {
            final String command =
                    mPreviousShowImeWithHardKeyboard == null
                            ? SETTINGS + " delete secure "
                                    + SHOW_IME_WITH_HARD_KEYBOARD
                            : SETTINGS + " put secure "
                                    + SHOW_IME_WITH_HARD_KEYBOARD + " "
                                    + mPreviousShowImeWithHardKeyboard;
            ShellAccess.run(command);
            mShowImeOverrideActive = false;
            mPreviousShowImeWithHardKeyboard = null;
            Log.i(TAG, "software keyboard policy restored");
        } catch (IOException error) {
            Log.w(TAG, "could not restore phone keyboard policy", error);
            CompatibilityDiagnostics.record(
                    "INPUT-IME-002",
                    "Could not restore the on-screen keyboard policy",
                    error.getMessage(),
                    error);
        }
    }

    private void updateExternalImePolicy() {
        if (!ownsExternalDesktop()
                || !PlatformDrivers.current().phoneUi()
                        .usesMirrorInputPanel()) {
            mPhoneImePolicyDisplayId =
                    android.view.Display.INVALID_DISPLAY;
            return;
        }
        if (!ShellAccess.isReady()
                || mPhoneImePolicyDisplayId == mOwnedDesktopDisplayId) {
            return;
        }
        try {
            if (!ShellAccess.routeImeToPhone(mOwnedDesktopDisplayId)) {
                throw new IOException("the phone fallback was not applied");
            }
            mPhoneImePolicyDisplayId = mOwnedDesktopDisplayId;
            Log.i(TAG, "IME routed to phone for desktop display="
                    + mOwnedDesktopDisplayId);
        } catch (IOException error) {
            Log.w(TAG, "could not route the IME to the phone", error);
            CompatibilityDiagnostics.record(
                    "INPUT-IME-003",
                    "Could not keep the on-screen keyboard on the phone",
                    "display=" + mOwnedDesktopDisplayId + " "
                            + error.getMessage(),
                    error);
        }
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
                || mLocalDesktopExitRecoveryPending
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
        final long generation =
                LocalDesktopNavigationController.currentGeneration();
        LocalDesktopNavigationController.cleanupClosedSession(
                generation,
                (completed, success, message) -> {
                    mLocalDesktopCleanupInFlight = false;
                    if (mDestroyed) {
                        return;
                    }
                    if (!success) {
                        Log.w(TAG, "phone desktop recovery failed: " + message);
                        CompatibilityDiagnostics.record(
                                "PHONE-HOME-003",
                                "Could not clean local desktop tasks before"
                                        + " returning to the phone launcher",
                                message);
                        return;
                    }
                    if (!completed) {
                        if (DesktopRuntimeBridge.getActiveDesktopDisplayId()
                                != android.view.Display.DEFAULT_DISPLAY) {
                            scheduleLocalDesktopCleanup();
                        }
                        return;
                    }
                    mLocalDesktopExitRecoveryPending = true;
                    schedulePhoneHomeRecovery();
                    Log.i(TAG, "recovered phone desktop tasks; restoring Home"
                            + " after local desktop");
                });
    }

    private void maintainLocalDesktopNavigationGuard() {
        if (!ShellAccess.isReady()
                || !LocalDesktopSessionState.isCleanupPending(this)
                || DesktopRuntimeBridge.getActiveDesktopDisplayId()
                        != android.view.Display.DEFAULT_DISPLAY) {
            return;
        }
        LocalDesktopNavigationController.acquire(
                (generation, success, message) -> {
            if (!success) {
                CompatibilityDiagnostics.record(
                        "PHONE-HOME-005",
                        "Could not maintain the local desktop navigation guard",
                        message);
            }
        });
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
                : (mHasHardwareKeyboard
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
