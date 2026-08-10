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
    private static final String ACTION_SHOW_MAGIC_DESK =
            "io.github.mekhontsev.magicdesk.action.SHOW_MAGIC_DESK";
    private static final String ACTION_OPEN_TOUCHPAD =
            "io.github.mekhontsev.magicdesk.action.OPEN_TOUCHPAD";
    private static final int NOTIFICATION_ID = 1;
    private static final int OPEN_TOUCHPAD_REQUEST_CODE = 1;
    private static final int SHOW_MAGIC_DESK_REQUEST_CODE = 2;
    private static final long LOCAL_DESKTOP_CLEANUP_DELAY_MILLIS = 500;
    private static final long DISPLAY_REMOVAL_WATCHDOG_MILLIS = 2000;
    private static final String CONSOLE_DISPLAY_STATE = "app_mirror_displayid";
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
    private boolean mOwnsNubiaConsoleDesktop;
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
    private DesktopTaskController mDesktopTasks;
    private BroadcastReceiver mConfigurationReceiver;
    private ContentObserver mConsoleModeObserver;
    private boolean mDestroyed;
    private boolean mInitialized;
    private boolean mLocalDesktopCleanupInFlight;
    private int mInputSourceRefreshGeneration;
    private String mOperationStatus;
    private boolean mShowImeOverrideActive;
    private String mPreviousShowImeWithHardKeyboard;
    private int mLocalImePolicyDisplayId =
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
        return ShellAccess.injectPointerClick(displayId, button);
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

    static void setOnScreenKeyboardLocation(
            final Context context,
            final OnScreenKeyboardLocation location) {
        if (context == null) {
            return;
        }
        final OnScreenKeyboardLocation selected = location == null
                ? OnScreenKeyboardLocation.PHONE : location;
        DesktopPreferences.saveOnScreenKeyboardLocation(context, selected);
        final int activeDisplayId =
                DesktopRuntimeBridge.getActiveDesktopDisplayId();
        if (selected == OnScreenKeyboardLocation.PHONE
                && activeDisplayId > android.view.Display.DEFAULT_DISPLAY
                && DesktopRuntimeBridge.isDesktopKeyboardRequested(
                        activeDisplayId)) {
            DesktopRuntimeBridge.hideDesktopKeyboard(activeDisplayId);
        }
        MagicDeskTouchpadActivity.onKeyboardLocationChanged(selected);
        DesktopRuntimeBridge.refreshDesktopControls();
    }

    static boolean showDesktopKeyboardIfRunning(final int displayId) {
        final MagicDeskRuntimeService service = sInstance.get();
        if (service == null
                || service.mDestroyed
                || displayId != service.mOwnedDesktopDisplayId
                || !service.useLocalImePolicy(displayId)) {
            return false;
        }
        if (!ShellAccess.focusDisplayForInput(displayId)) {
            service.restoreDesktopImePolicy(displayId);
            return false;
        }
        if (DesktopRuntimeBridge.showDesktopKeyboard(displayId)) {
            return true;
        }
        ShellAccess.endMirrorTextInput(displayId);
        service.restoreDesktopImePolicy(displayId);
        return false;
    }

    static boolean focusDesktopInputIfRunning(final int displayId) {
        final MagicDeskRuntimeService service = sInstance.get();
        return service != null
                && !service.mDestroyed
                && displayId == service.mOwnedDesktopDisplayId
                && ShellAccess.focusDisplayForInput(displayId);
    }

    static void desktopKeyboardDismissedIfRunning(final int displayId) {
        final MagicDeskRuntimeService service = sInstance.get();
        if (service == null || service.mDestroyed) {
            return;
        }
        ShellAccess.endMirrorTextInput(displayId);
        service.restoreDesktopImePolicy(displayId);
        MagicDeskTouchpadActivity.onDesktopKeyboardDismissed(displayId);
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
        mDisplayCoordinator = new RuntimeDisplayCoordinator(
                this, mHandler, this::handleDisplayStateChanged);
        mDisplayCoordinator.start();
        mConsoleDisplayId = getConsoleDisplayId();
        mConsoleModeActive = mConsoleDisplayId > 0;
        refreshDesktopOwnership();
        updateShowImeOverride();
        registerConfigurationReceiver();
        registerConsoleModeObserver();
        if (ShellAccess.isReady()) {
            ConsoleModeSwitcher.updateExternalTaskCaptionTarget(
                    mOwnedDesktopDisplayId,
                    ownsNubiaConsoleDesktop());
        } else {
            NubiaCaptionVisibilityManager.setTransport(
                    NubiaCaptionVisibilityManager.Transport.NONE);
        }
        updateKeyboardWatcher();
        updateDesktopMouseBridge();
        updateDesktopTasks();
        if (LocalDesktopSessionState.isCleanupPending(this)) {
            maintainLocalDesktopNavigationGuard();
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
                final int desktopDisplayId =
                        DesktopRuntimeBridge.getActiveDesktopDisplayId();
                if (desktopDisplayId >= 0) {
                    ConsoleModeSwitcher.toggleDesktopWorkspace();
                    Log.i(TAG, "toggled existing desktop from notification"
                            + " display=" + desktopDisplayId);
                } else if (mConsoleModeActive) {
                    ConsoleModeSwitcher.showMagicDesk(mConsoleDisplayId);
                    Log.i(TAG, "restored console desktop from notification"
                            + " display=" + mConsoleDisplayId);
                } else if (ControlActivity.hasActiveInstance()) {
                    startActivity(ControlActivity.createLaunchIntent(this));
                    Log.i(TAG, "focused phone control panel from notification");
                } else if (ShellAccess.isReady()) {
                    startActivity(ControlActivity.createLaunchIntent(this));
                    Log.i(TAG, "opened phone control panel from notification");
                } else {
                    startActivity(DeviceSetupActivity.createLaunchIntent(this));
                }
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
            mHandler.removeCallbacks(mDisplayRemovalWatchdogRunnable);
            mHandler.removeCallbacks(mLocalDesktopCleanupRunnable);
        }
        if (mDesktopTasks != null) {
            mDesktopTasks.destroy();
        }
        if (mDesktopMouseBridge != null) {
            mDesktopMouseBridge.stop();
        }
        restoreDesktopImePolicy(mLocalImePolicyDisplayId);
        restoreShowImeOverride();
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
        if (ownsExternalDesktop()) {
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
        final DesktopDisplayTarget.Kind desktopKind = displayRemoved
                ? DesktopRuntimeBridge.getDesktopTargetKind(displayId)
                : null;
        final boolean activeDesktopRemoved = displayRemoved
                && DesktopRuntimeBridge.getActiveDesktopDisplayId()
                        == displayId;
        final boolean externalDesktopRemoved = isExternalDesktopRemoval(
                displayRemoved,
                displayId,
                mOwnedDesktopDisplayId,
                desktopKind,
                activeDesktopRemoved);
        if (displayRemoved) {
            restoreDesktopImePolicy(displayId);
            PhoneTouchpadController.release(displayId);
            DesktopRuntimeBridge.closeExternalDesktopSession(displayId);
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
            final DesktopDisplayTarget.Kind desktopKind,
            final boolean activeDesktopRemoved) {
        if (!displayRemoved
                || displayId <= android.view.Display.DEFAULT_DISPLAY) {
            return false;
        }
        if (desktopKind == DesktopDisplayTarget.Kind.SIMULATED) {
            return activeDesktopRemoved;
        }
        return displayId == ownedDesktopDisplayId || desktopKind != null;
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
            DesktopRuntimeBridge.closeExternalDesktopSession(
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
                || mConsoleExitRecoveryPending) {
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
        mAllowUnsettledDisplayRecovery = false;
        final boolean includeStrandedDesktop =
                PhoneHomeRecoveryController.shouldRestoreStrandedDesktop(
                        mConsoleModeActive,
                        mConsoleExitRecoveryPending);
        final int removedDisplayId = mRemovedDesktopDisplayId;
        final boolean localDesktopActive =
                DesktopRuntimeBridge.getActiveDesktopDisplayId()
                        == android.view.Display.DEFAULT_DISPLAY;
        PhoneHomeRecoveryController.restoreIfNeeded(
                includeStrandedDesktop,
                removedDisplayId,
                localDesktopActive,
                allowUnsettledDisplayRecovery,
                settled -> mHandler.post(() -> {
                    mPhoneHomeRecoveryInFlight = false;
                    final boolean restorePhonePanel =
                            mRestorePhonePanelAfterRecovery
                                    && mRemovedDesktopDisplayId
                                            == removedDisplayId;
                    if (!mDestroyed && settled
                            && mRemovedDesktopDisplayId == removedDisplayId) {
                        mRemovedDesktopDisplayId =
                                android.view.Display.INVALID_DISPLAY;
                        mHandler.removeCallbacks(
                                mDisplayRemovalWatchdogRunnable);
                    }
                    if (!mDestroyed && restorePhonePanel) {
                        mRestorePhonePanelAfterRecovery = false;
                        ConsoleModeSwitcher.restorePhoneAfterExternalDesktop();
                    }
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
        final int routingDisplayId = ownsExternalDesktop()
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
        return ownsExternalDesktop()
                && ShellAccess.isReady();
    }

    private void refreshDesktopInputSources() {
        if (!ownsExternalDesktop() || !ShellAccess.isReady()) {
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
                    if (mDestroyed || !ownsExternalDesktop()
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
            if (mLocalImePolicyDisplayId
                    != android.view.Display.INVALID_DISPLAY
                    && (mLocalImePolicyDisplayId != mOwnedDesktopDisplayId
                            || !DesktopRuntimeBridge
                                    .isDesktopKeyboardRequested(
                                            mLocalImePolicyDisplayId))) {
                restoreDesktopImePolicy(mLocalImePolicyDisplayId);
            }
            if (ownsNubiaConsoleDesktop()) {
                NubiaHostAssistPanelController.hideIfPresent();
            }
            ConsoleModeSwitcher.updateExternalTaskCaptionTarget(
                    mOwnedDesktopDisplayId,
                    ownsNubiaConsoleDesktop());
            RedmagicHardwareController.start(this);
            maintainLocalDesktopNavigationGuard();
            schedulePhoneHomeRecovery();
        } else {
            NubiaCaptionVisibilityManager.setTransport(
                    NubiaCaptionVisibilityManager.Transport.NONE);
            RedmagicHardwareController.stop();
        }
        updateNotification();
        DesktopRuntimeBridge.refreshDesktopControls();
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

    private void refreshDesktopOwnership() {
        final int desktopDisplayId =
                DesktopRuntimeBridge.getActiveDesktopDisplayId();
        final boolean ownsNubiaConsoleDesktop =
                desktopDisplayId > android.view.Display.DEFAULT_DISPLAY
                        && mConsoleModeActive
                        && desktopDisplayId == mConsoleDisplayId;
        if (desktopDisplayId == mOwnedDesktopDisplayId
                && ownsNubiaConsoleDesktop
                        == mOwnsNubiaConsoleDesktop) {
            return;
        }
        if (mLocalImePolicyDisplayId != android.view.Display.INVALID_DISPLAY
                && mLocalImePolicyDisplayId != desktopDisplayId) {
            restoreDesktopImePolicy(mLocalImePolicyDisplayId);
        }
        mOwnedDesktopDisplayId = desktopDisplayId;
        mOwnsNubiaConsoleDesktop = ownsNubiaConsoleDesktop;
        updateShowImeOverride();
        Log.i(TAG, "ownsExternalDesktop=" + ownsExternalDesktop()
                + " desktopDisplay=" + desktopDisplayId
                + " consoleDisplay=" + mConsoleDisplayId);
        if (ownsNubiaConsoleDesktop()) {
            NubiaHostAssistPanelController.hideIfPresent();
        }
        updateKeyboardWatcher();
        updateDesktopMouseBridge();
        if (ShellAccess.isReady()) {
            ConsoleModeSwitcher.updateExternalTaskCaptionTarget(
                    mOwnedDesktopDisplayId,
                    ownsNubiaConsoleDesktop());
        }
        if (ownsExternalDesktop()) {
            refreshDesktopInputSources();
        }
    }

    private boolean ownsNubiaConsoleDesktop() {
        return mOwnsNubiaConsoleDesktop;
    }

    private boolean ownsExternalDesktop() {
        return mOwnedDesktopDisplayId
                > android.view.Display.DEFAULT_DISPLAY;
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
            Log.w(TAG, "could not enable desktop IME policy", error);
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
            Log.w(TAG, "could not restore desktop IME policy", error);
            CompatibilityDiagnostics.record(
                    "INPUT-IME-002",
                    "Could not restore the on-screen keyboard policy",
                    error.getMessage(),
                    error);
        }
    }

    private boolean useLocalImePolicy(final int displayId) {
        if (!ShellAccess.isReady()
                || displayId <= android.view.Display.DEFAULT_DISPLAY) {
            return false;
        }
        try {
            final int actual = ShellAccess.setDisplayImePolicy(
                    displayId,
                    DisplayImePolicyController.LOCAL);
            if (actual != DisplayImePolicyController.LOCAL) {
                throw new IOException(
                        "requested local policy, actual=" + actual);
            }
            mLocalImePolicyDisplayId = displayId;
            Log.i(TAG, "desktop IME policy local display=" + displayId);
            return true;
        } catch (IOException error) {
            Log.w(TAG, "could not host IME on desktop display", error);
            CompatibilityDiagnostics.record(
                    "INPUT-IME-003",
                    "Could not show the on-screen keyboard on the desktop",
                    "display=" + displayId + " " + error.getMessage(),
                    error);
            return false;
        }
    }

    private void restoreDesktopImePolicy(final int displayId) {
        if (displayId == android.view.Display.INVALID_DISPLAY
                || displayId != mLocalImePolicyDisplayId) {
            return;
        }
        if (!ShellAccess.isReady()) {
            return;
        }
        if (mDisplayCoordinator != null
                && !mDisplayCoordinator.hasDisplay(displayId)) {
            mLocalImePolicyDisplayId =
                    android.view.Display.INVALID_DISPLAY;
            Log.i(TAG, "display removed with local IME policy display="
                    + displayId);
            return;
        }
        try {
            final int actual = ShellAccess.setDisplayImePolicy(
                    displayId,
                    DisplayImePolicyController.FALLBACK_TO_PHONE);
            if (actual != DisplayImePolicyController.FALLBACK_TO_PHONE) {
                throw new IOException(
                        "requested phone fallback policy, actual=" + actual);
            }
            mLocalImePolicyDisplayId =
                    android.view.Display.INVALID_DISPLAY;
            Log.i(TAG, "desktop IME policy restored display=" + displayId);
        } catch (IOException error) {
            Log.w(TAG, "could not restore display IME policy", error);
            CompatibilityDiagnostics.record(
                    "INPUT-IME-004",
                    "Could not restore the standard keyboard location",
                    "display=" + displayId + " " + error.getMessage(),
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
                                "NUBIA-HOME-003",
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
                    LocalDesktopSessionState.clearCleanupPending(this);
                    Log.i(TAG, "recovered phone desktop tasks after local desktop");
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
                        "NUBIA-HOME-005",
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
