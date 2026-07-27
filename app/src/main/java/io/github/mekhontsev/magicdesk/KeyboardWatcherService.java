package io.github.mekhontsev.magicdesk;

import android.Manifest;
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
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.hardware.display.DisplayManager;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.InputDevice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class KeyboardWatcherService extends Service
        implements InputManager.InputDeviceListener, DisplayManager.DisplayListener {
    private static final String TAG = "MagicDeskWatcher";
    private static final String CHANNEL_ID = "magicdesk";
    private static final String ACTION_SHOW_MAGIC_DESK =
            "io.github.mekhontsev.magicdesk.action.SHOW_MAGIC_DESK";
    private static final String ACTION_OPEN_TOUCHPAD =
            "io.github.mekhontsev.magicdesk.action.OPEN_TOUCHPAD";
    private static final String ACTION_STOP =
            "io.github.mekhontsev.magicdesk.action.STOP_KEYBOARD_WATCHER";
    private static final int NOTIFICATION_ID = 1;
    private static final int OPEN_TOUCHPAD_REQUEST_CODE = 1;
    private static final int SHOW_MAGIC_DESK_REQUEST_CODE = 2;
    private static final long DEVICE_CHANGE_DEBOUNCE_MILLIS = 600;
    private static final long MIRROR_INPUT_RETRY_MILLIS = 1_000;
    private static final String CONSOLE_DISPLAY_STATE = "app_mirror_displayid";
    private static final String PHONE_SCREEN_OFF_STATE = "nubia_screen_off_tp";

    private Handler mHandler;
    private DisplayManager mDisplayManager;
    private InputManager mInputManager;
    private boolean mHasHardwareKeyboard;
    private boolean mHasExternalMouse;
    private String mExternalInputDeviceSignature;
    private boolean mConsoleModeActive;
    private int mConsoleDisplayId;
    private boolean mRootWatcherRunning;
    private DesktopTaskController mDesktopTasks;
    private BroadcastReceiver mConfigurationReceiver;
    private ContentObserver mConsoleModeObserver;
    private Boolean mMirrorInputProxyEnabled;
    private boolean mDestroyed;
    private boolean mInitialized;

    private final Runnable mDeviceChangeRunnable = this::handleDeviceStateMaybeChanged;
    private final Runnable mMirrorInputRetryRunnable = this::syncMirrorInputProxyState;

    public static void start(final Context context) {
        final Intent intent = new Intent(context, KeyboardWatcherService.class);
        startForegroundService(context, intent);
    }

    public static void stop(final Context context) {
        final Intent intent = new Intent(context, KeyboardWatcherService.class)
                .setAction(ACTION_STOP);
        startForegroundService(context, intent);
    }

    private static void startForegroundService(final Context context, final Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mHandler = new Handler(Looper.getMainLooper());
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
    }

    private void initialize() {
        if (mInitialized) {
            return;
        }
        mInitialized = true;
        mDesktopTasks = new DesktopTaskController(this, mHandler);
        mDisplayManager = getSystemService(DisplayManager.class);
        mInputManager = getSystemService(InputManager.class);
        mHasHardwareKeyboard = hasHardwareKeyboard();
        mHasExternalMouse = hasExternalMouse();
        mExternalInputDeviceSignature = getExternalInputDeviceSignature();
        mConsoleDisplayId = getConsoleDisplayId();
        mConsoleModeActive = mConsoleDisplayId > 0;
        if (mInputManager != null) {
            mInputManager.registerInputDeviceListener(this, mHandler);
        }
        if (mDisplayManager != null) {
            mDisplayManager.registerDisplayListener(this, mHandler);
        }
        registerConfigurationReceiver();
        registerConsoleModeObserver();
        if (mConsoleModeActive
                && RuntimeAccess.has(RuntimeAccess.Capability.CONSOLE_CONTROL)) {
            ConsoleModeSwitcher.setExternalTaskCaptionsEnabled(true);
        }
        syncMirrorInputProxyState();
        updateRootWatcher();
        updateDesktopTasks();
        logInputState();
        Log.i(TAG, "started, hardwareKeyboard=" + mHasHardwareKeyboard
                + " externalMouse=" + mHasExternalMouse);
    }

    @Override
    public int onStartCommand(final Intent intent, final int flags, final int startId) {
        startForeground(NOTIFICATION_ID, buildNotification());
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        initialize();
        if (intent != null) {
            if (ACTION_SHOW_MAGIC_DESK.equals(intent.getAction())
                    && RuntimeAccess.has(RuntimeAccess.Capability.CONSOLE_CONTROL)) {
                ConsoleModeSwitcher.showMagicDesk();
            } else if (ACTION_OPEN_TOUCHPAD.equals(intent.getAction())
                    && RuntimeAccess.has(RuntimeAccess.Capability.CONSOLE_CONTROL)) {
                ConsoleModeSwitcher.openTouchpad();
            }
        }
        syncMirrorInputProxyState();
        updateRootWatcher();
        updateDesktopTasks();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        mDestroyed = true;
        if (mInputManager != null) {
            mInputManager.unregisterInputDeviceListener(this);
        }
        if (mDisplayManager != null) {
            mDisplayManager.unregisterDisplayListener(this);
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
            mHandler.removeCallbacks(mDeviceChangeRunnable);
            mHandler.removeCallbacks(mMirrorInputRetryRunnable);
        }
        if (mDesktopTasks != null) {
            mDesktopTasks.stop();
        }
        RootKeyboardShortcutWatcher.stop();
        ConsoleModeSwitcher.closeRootShell();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(final Intent intent) {
        return null;
    }

    @Override
    public void onInputDeviceAdded(final int deviceId) {
        scheduleDeviceStateCheck();
    }

    @Override
    public void onInputDeviceRemoved(final int deviceId) {
        scheduleDeviceStateCheck();
    }

    @Override
    public void onInputDeviceChanged(final int deviceId) {
        scheduleDeviceStateCheck();
    }

    @Override
    public void onDisplayAdded(final int displayId) {
        handleConsoleStateMaybeChanged();
    }

    @Override
    public void onDisplayRemoved(final int displayId) {
        handleConsoleStateMaybeChanged();
    }

    @Override
    public void onDisplayChanged(final int displayId) {
        handleConsoleStateMaybeChanged();
    }

    private void scheduleDeviceStateCheck() {
        mHandler.removeCallbacks(mDeviceChangeRunnable);
        mHandler.postDelayed(mDeviceChangeRunnable, DEVICE_CHANGE_DEBOUNCE_MILLIS);
    }

    private void handleDeviceStateMaybeChanged() {
        final boolean hasHardwareKeyboard = hasHardwareKeyboard();
        final boolean hasExternalMouse = hasExternalMouse();
        final boolean keyboardChanged = hasHardwareKeyboard != mHasHardwareKeyboard;
        final boolean mouseChanged = hasExternalMouse != mHasExternalMouse;
        final String inputDeviceSignature = getExternalInputDeviceSignature();
        final boolean inputInventoryChanged =
                !inputDeviceSignature.equals(mExternalInputDeviceSignature);
        if (!keyboardChanged && !mouseChanged && !inputInventoryChanged) {
            return;
        }
        mHasHardwareKeyboard = hasHardwareKeyboard;
        mHasExternalMouse = hasExternalMouse;
        mExternalInputDeviceSignature = inputDeviceSignature;
        Log.i(TAG, "hardwareKeyboard=" + mHasHardwareKeyboard
                + " externalMouse=" + mHasExternalMouse
                + " inputInventoryChanged=" + inputInventoryChanged);
        logInputState();
        if (keyboardChanged) {
            updateNotification();
            updateRootWatcher();
        } else if ((mouseChanged || inputInventoryChanged) && mRootWatcherRunning) {
            restartRootWatcher();
        }
    }

    private boolean hasHardwareKeyboard() {
        return hasExternalAlphabeticKeyboardDevice() || hasConfiguredHardKeyboard();
    }

    private boolean hasExternalAlphabeticKeyboardDevice() {
        for (final int deviceId : InputDevice.getDeviceIds()) {
            final InputDevice device = InputDevice.getDevice(deviceId);
            if (isExternalAlphabeticKeyboard(device)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasExternalMouse() {
        for (final int deviceId : InputDevice.getDeviceIds()) {
            final InputDevice device = InputDevice.getDevice(deviceId);
            if (device == null || device.isVirtual() || !device.isExternal()) {
                continue;
            }
            if ((device.getSources() & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) {
                return true;
            }
        }
        return false;
    }

    private String getExternalInputDeviceSignature() {
        final List<String> devices = new ArrayList<>();
        for (final int deviceId : InputDevice.getDeviceIds()) {
            final InputDevice device = InputDevice.getDevice(deviceId);
            if (device == null || device.isVirtual() || !device.isExternal()) {
                continue;
            }
            devices.add(deviceId + ":" + device.getDescriptor()
                    + ":" + device.getVendorId()
                    + ":" + device.getProductId()
                    + ":" + device.getSources()
                    + ":" + device.getKeyboardType());
        }
        Collections.sort(devices);
        return devices.toString();
    }

    private boolean hasConfiguredHardKeyboard() {
        final Configuration configuration = getResources().getConfiguration();
        return configuration.keyboard == Configuration.KEYBOARD_QWERTY
                && configuration.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO;
    }

    private static boolean isExternalAlphabeticKeyboard(final InputDevice device) {
        if (device == null || device.isVirtual() || !device.isExternal()) {
            return false;
        }
        final boolean hasKeyboardSource =
                (device.getSources() & InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD;
        return hasKeyboardSource
                && device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC;
    }

    private void registerConfigurationReceiver() {
        mConfigurationReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(final Context context, final Intent intent) {
                if (Intent.ACTION_CONFIGURATION_CHANGED.equals(intent.getAction())) {
                    scheduleDeviceStateCheck();
                }
            }
        };
        registerReceiver(mConfigurationReceiver,
                new IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED));
    }

    private void registerConsoleModeObserver() {
        mConsoleModeObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(final boolean selfChange) {
                handleConsoleStateMaybeChanged();
                syncMirrorInputProxyState();
            }
        };
        getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(CONSOLE_DISPLAY_STATE),
                false,
                mConsoleModeObserver);
        getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(PHONE_SCREEN_OFF_STATE),
                false,
                mConsoleModeObserver);
    }

    private void syncMirrorInputProxyState() {
        if (mDestroyed) {
            return;
        }
        if (!RuntimeAccess.has(RuntimeAccess.Capability.PHONE_SCREEN_CONTROL)) {
            mMirrorInputProxyEnabled = null;
            mHandler.removeCallbacks(mMirrorInputRetryRunnable);
            return;
        }
        final boolean enabled = !mConsoleModeActive || !isPhoneScreenOff();
        if (mMirrorInputProxyEnabled != null
                && mMirrorInputProxyEnabled.booleanValue() == enabled) {
            return;
        }
        mMirrorInputProxyEnabled = Boolean.valueOf(enabled);
        ConsoleModeSwitcher.setMirrorInputProxyEnabled(enabled,
                new ConsoleModeSwitcher.ResultCallback() {
                    @Override
                    public void onComplete(final boolean success) {
                        if (success || mDestroyed || mHandler == null) {
                            return;
                        }
                        mHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (mDestroyed) {
                                    return;
                                }
                                if (mMirrorInputProxyEnabled != null
                                        && mMirrorInputProxyEnabled.booleanValue()
                                        == enabled) {
                                    mMirrorInputProxyEnabled = null;
                                    mHandler.postDelayed(
                                            mMirrorInputRetryRunnable,
                                            MIRROR_INPUT_RETRY_MILLIS);
                                }
                            }
                        });
                    }
                });
    }

    private boolean isPhoneScreenOff() {
        try {
            return Settings.Global.getInt(
                    getContentResolver(), PHONE_SCREEN_OFF_STATE, 0) == 1;
        } catch (RuntimeException e) {
            Log.w(TAG, "failed to read phone screen state", e);
            return false;
        }
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
        Log.i(TAG, "consoleMode=" + mConsoleModeActive
                + " display=" + mConsoleDisplayId);
        if (activeStateChanged) {
            restartRootWatcher();
        }
        if (RuntimeAccess.has(RuntimeAccess.Capability.CONSOLE_CONTROL)) {
            ConsoleModeSwitcher.setExternalTaskCaptionsEnabled(consoleModeActive);
        }
        updateDesktopTasks();
        if (wasConsoleModeActive && !consoleModeActive
                && RuntimeAccess.has(
                        RuntimeAccess.Capability.PHONE_SCREEN_CONTROL)) {
            ConsoleModeSwitcher.setPhoneScreenOff(false, null);
        }
    }

    private void updateRootWatcher() {
        final boolean shouldRun = mHasHardwareKeyboard
                && RuntimeAccess.has(RuntimeAccess.Capability.GLOBAL_INPUT);
        if (shouldRun == mRootWatcherRunning) {
            return;
        }

        if (shouldRun) {
            Log.i(TAG, "starting root keyboard watcher");
            RootKeyboardShortcutWatcher.start(mConsoleModeActive);
        } else {
            Log.i(TAG, "stopping root keyboard watcher");
            RootKeyboardShortcutWatcher.stop();
        }
        mRootWatcherRunning = shouldRun;
    }

    private void restartRootWatcher() {
        if (!mRootWatcherRunning) {
            updateRootWatcher();
            return;
        }
        RootKeyboardShortcutWatcher.stop();
        RootKeyboardShortcutWatcher.start(mConsoleModeActive);
    }

    private int getConsoleDisplayId() {
        try {
            final int displayId = Settings.Global.getInt(
                    getContentResolver(), CONSOLE_DISPLAY_STATE, -1);
            if (displayId <= 0 || mDisplayManager == null
                    || mDisplayManager.getDisplay(displayId) == null) {
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
        final int displayId = mConsoleDisplayId;
        if (displayId > 0
                && RuntimeAccess.has(RuntimeAccess.Capability.EXACT_TASKS)) {
            mDesktopTasks.start(displayId);
        } else {
            mDesktopTasks.stop();
        }
    }

    private void logInputState() {
        final Configuration configuration = getResources().getConfiguration();
        Log.i(TAG, "config keyboard=" + configuration.keyboard
                + " hardKeyboardHidden=" + configuration.hardKeyboardHidden
                + " keyboardHidden=" + configuration.keyboardHidden);
        for (final int deviceId : InputDevice.getDeviceIds()) {
            final InputDevice device = InputDevice.getDevice(deviceId);
            if (device == null) {
                continue;
            }
            Log.i(TAG, "device id=" + deviceId
                    + " name=" + device.getName()
                    + " external=" + device.isExternal()
                    + " virtual=" + device.isVirtual()
                    + " sources=0x" + Integer.toHexString(device.getSources())
                    + " keyboardType=" + device.getKeyboardType());
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
                new Intent(this, KeyboardWatcherService.class)
                        .setAction(ACTION_SHOW_MAGIC_DESK);
        final PendingIntent showMagicDeskPendingIntent =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? PendingIntent.getForegroundService(
                                this,
                                SHOW_MAGIC_DESK_REQUEST_CODE,
                                showMagicDeskIntent,
                                pendingIntentFlags())
                        : PendingIntent.getService(
                                this,
                                SHOW_MAGIC_DESK_REQUEST_CODE,
                                showMagicDeskIntent,
                                pendingIntentFlags());
        final Intent openTouchpadIntent = new Intent(this, KeyboardWatcherService.class)
                .setAction(ACTION_OPEN_TOUCHPAD);
        final PendingIntent openTouchpadPendingIntent =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? PendingIntent.getForegroundService(
                                this,
                                OPEN_TOUCHPAD_REQUEST_CODE,
                                openTouchpadIntent,
                                pendingIntentFlags())
                        : PendingIntent.getService(
                                this,
                                OPEN_TOUCHPAD_REQUEST_CODE,
                                openTouchpadIntent,
                                pendingIntentFlags());
        final String text = mHasHardwareKeyboard
                ? getString(R.string.notification_hw_connected)
                : getString(R.string.notification_hw_disconnected);

        final Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(R.drawable.ic_magicdesk)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(showMagicDeskPendingIntent)
                .addAction(
                        R.drawable.ic_touchpad,
                        getString(R.string.notification_open_touchpad),
                        openTouchpadPendingIntent)
                .build();
    }

    private static int pendingIntentFlags() {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        return flags;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
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
