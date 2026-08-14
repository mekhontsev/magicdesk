package io.github.mekhontsev.magicdesk.platform.nubia;

import io.github.mekhontsev.magicdesk.ShellAccess;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class ChargeSeparationController {
    static final String SETTING = "charge_separation_switch";

    private static final String SYSTEM_PACKAGE = "cn.zte.chargeseparation";
    private static final int MINIMUM_BATTERY_PERCENT = 20;
    private static final ExecutorService EXECUTOR =
            Executors.newSingleThreadExecutor(runnable -> {
                final Thread thread =
                        new Thread(runnable, "MagicDeskChargeSeparation");
                thread.setDaemon(true);
                return thread;
            });

    interface Listener {
        void onChargeSeparationChanged(State state);
    }

    interface ResultCallback {
        void onComplete(boolean success, String message);
    }

    static final class State {
        final boolean supported;
        final boolean plugged;
        final int batteryPercent;
        final boolean enabled;

        State(
                final boolean supported,
                final boolean plugged,
                final int batteryPercent,
                final boolean enabled) {
            this.supported = supported;
            this.plugged = plugged;
            this.batteryPercent = batteryPercent;
            this.enabled = enabled;
        }

        boolean canChange() {
            if (!supported || !ShellAccess.isReady()) {
                return false;
            }
            return enabled || canEnable(
                    supported, true, plugged, batteryPercent);
        }
    }

    private final Context mContext;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final Listener mListener;
    private final boolean mSupported;
    private ContentObserver mSettingObserver;
    private BroadcastReceiver mBatteryReceiver;
    private State mState;
    private boolean mStarted;
    private boolean mWritePending;

    ChargeSeparationController(
            final Context context,
            final Listener listener) {
        mContext = context.getApplicationContext();
        mListener = listener;
        mSupported = isSupported(mContext);
        mState = new State(
                mSupported,
                false,
                -1,
                readEnabled());
    }

    void start() {
        if (mStarted) {
            return;
        }
        mStarted = true;
        if (!mSupported) {
            dispatchState();
            return;
        }

        mSettingObserver = new ContentObserver(mMainHandler) {
            @Override
            public void onChange(final boolean selfChange) {
                refreshSetting();
            }
        };
        mContext.getContentResolver().registerContentObserver(
                Settings.Global.getUriFor(SETTING),
                false,
                mSettingObserver);

        mBatteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(
                    final Context context,
                    final Intent intent) {
                refreshBattery(intent);
            }
        };
        final Intent battery = mContext.registerReceiver(
                mBatteryReceiver,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                Context.RECEIVER_NOT_EXPORTED);
        if (battery != null) {
            refreshBattery(battery);
        } else {
            dispatchState();
        }
    }

    void stop() {
        mStarted = false;
        if (mSettingObserver != null) {
            mContext.getContentResolver().unregisterContentObserver(
                    mSettingObserver);
            mSettingObserver = null;
        }
        if (mBatteryReceiver != null) {
            try {
                mContext.unregisterReceiver(mBatteryReceiver);
            } catch (IllegalArgumentException ignored) {
                // The receiver may already be detached during teardown.
            }
            mBatteryReceiver = null;
        }
    }

    State state() {
        return mState;
    }

    void setEnabled(
            final boolean enabled,
            final ResultCallback callback) {
        if (mWritePending) {
            complete(callback, false, "A charge mode change is already pending");
            return;
        }
        if (enabled && !canEnable(
                mState.supported,
                ShellAccess.isReady(),
                mState.plugged,
                mState.batteryPercent)) {
            complete(callback, false,
                    "Connect power and charge the battery to at least 20%");
            return;
        }
        if (!enabled && !mState.canChange()) {
            complete(callback, false,
                    "The selected runtime cannot change bypass charging");
            return;
        }

        mWritePending = true;
        dispatchState();
        EXECUTOR.execute(() -> {
            boolean success = false;
            String message = "";
            try {
                ShellAccess.run(
                        "/system/bin/settings put global "
                                + SETTING + " " + (enabled ? "1" : "0"));
                success = readEnabled() == enabled;
                if (!success) {
                    message = "The system did not accept the requested mode";
                }
            } catch (IOException | RuntimeException error) {
                message = error.getMessage() == null
                        ? error.getClass().getSimpleName()
                        : error.getMessage();
            }
            final boolean result = success;
            final String resultMessage = message;
            mMainHandler.post(() -> {
                mWritePending = false;
                refreshSetting();
                complete(callback, result, resultMessage);
            });
        });
    }

    boolean isWritePending() {
        return mWritePending;
    }

    static boolean canEnable(
            final boolean supported,
            final boolean privileged,
            final boolean plugged,
            final int batteryPercent) {
        return supported
                && privileged
                && plugged
                && batteryPercent >= MINIMUM_BATTERY_PERCENT;
    }

    static boolean isSupported(final Context context) {
        try {
            final ApplicationInfo application =
                    context.getPackageManager().getApplicationInfo(
                            SYSTEM_PACKAGE,
                            PackageManager.ApplicationInfoFlags.of(
                                    PackageManager.MATCH_SYSTEM_ONLY));
            return (application.flags
                    & (ApplicationInfo.FLAG_SYSTEM
                    | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0;
        } catch (PackageManager.NameNotFoundException
                | RuntimeException ignored) {
            return false;
        }
    }

    private void refreshBattery(final Intent battery) {
        final int level = battery.getIntExtra(
                BatteryManager.EXTRA_LEVEL, -1);
        final int scale = battery.getIntExtra(
                BatteryManager.EXTRA_SCALE, 100);
        final int percent = level < 0 || scale <= 0
                ? -1
                : Math.max(
                        0,
                        Math.min(
                                100,
                                Math.round(level * 100f / scale)));
        mState = new State(
                mSupported,
                battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0,
                percent,
                readEnabled());
        dispatchState();
    }

    private void refreshSetting() {
        mState = new State(
                mSupported,
                mState.plugged,
                mState.batteryPercent,
                readEnabled());
        dispatchState();
    }

    private boolean readEnabled() {
        try {
            return Settings.Global.getInt(
                    mContext.getContentResolver(), SETTING, 0) == 1;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void dispatchState() {
        if (mStarted && mListener != null) {
            mListener.onChargeSeparationChanged(mState);
        }
    }

    private static void complete(
            final ResultCallback callback,
            final boolean success,
            final String message) {
        if (callback != null) {
            callback.onComplete(success, message);
        }
    }
}
