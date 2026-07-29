package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

final class RedmagicHardwareController {
    enum FanMode {
        SYSTEM,
        OFF,
        AUTO,
        LEVEL_1,
        LEVEL_2,
        LEVEL_3,
        LEVEL_4,
        LEVEL_5
    }

    enum PumpMode {
        SYSTEM,
        OFF,
        SLOW,
        MEDIUM,
        FAST
    }

    interface Listener {
        void onHardwareStateChanged(RedmagicHardwareSnapshot snapshot);
    }

    interface ResultCallback {
        void onComplete(boolean success);
    }

    private static final String TAG = "MagicDeskHardware";
    private static final String PREFS = "magicdesk_redmagic_hardware";
    private static final String LEGACY_OWNER_ACTIVE = "owner_active";
    private static final String OWNER_FAN_ACTIVE = "owner_fan_active";
    private static final String OWNER_PUMP_ACTIVE = "owner_pump_active";
    private static final String BASELINE_FAN_ENABLE = "baseline_fan_enable";
    private static final String BASELINE_FAN_LEVEL = "baseline_fan_level";
    private static final String BASELINE_PUMP_ENABLE = "baseline_pump_enable";
    private static final String BASELINE_PUMP_FREQUENCY =
            "baseline_pump_frequency";
    private static final String BASELINE_PUMP_SPEED = "baseline_pump_speed";
    private static final long POLL_SECONDS = 4;

    private static final String FAN_ENABLE =
            "/sys/kernel/fan/fan_enable";
    private static final String FAN_LEVEL =
            "/sys/kernel/fan/fan_speed_level";
    private static final String FAN_RPM =
            "/sys/kernel/fan/fan_speed_count";
    private static final String PUMP_ENABLE =
            "/proc/driver/micropump/enable";
    private static final String PUMP_FREQUENCY =
            "/proc/driver/micropump/freq";
    private static final String PUMP_SPEED =
            "/proc/driver/micropump/speed";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ConsoleRootShell ROOT_SHELL = new ConsoleRootShell();
    private static final Object CONTROL_LOCK = new Object();
    private static final Set<Listener> LISTENERS =
            new CopyOnWriteArraySet<>();

    private static ScheduledExecutorService sExecutor;
    private static Context sContext;
    private static volatile RedmagicHardwareSnapshot sSnapshot =
            RedmagicHardwareSnapshot.UNAVAILABLE;
    private static volatile FanMode sFanMode = FanMode.SYSTEM;
    private static volatile PumpMode sPumpMode = PumpMode.SYSTEM;
    private static volatile boolean sStopping;
    private static int sAppliedAutoLevel = -1;

    private RedmagicHardwareController() {
    }

    static synchronized void start(final Context context) {
        if (!RuntimeAccess.has(RuntimeAccess.Capability.HARDWARE_CONTROL)
                || sExecutor != null) {
            return;
        }
        sContext = context.getApplicationContext();
        sStopping = false;
        sExecutor = Executors.newSingleThreadScheduledExecutor(
                new ThreadFactory() {
                    @Override
                    public Thread newThread(final Runnable runnable) {
                        final Thread thread =
                                new Thread(runnable, "MagicDeskHardware");
                        thread.setDaemon(true);
                        return thread;
                    }
                });
        sExecutor.execute(RedmagicHardwareController::recoverBaselineIfNeeded);
        sExecutor.scheduleWithFixedDelay(
                RedmagicHardwareController::pollInternal,
                0, POLL_SECONDS, TimeUnit.SECONDS);
    }

    static synchronized void stop() {
        sStopping = true;
        if (sExecutor != null) {
            sExecutor.shutdownNow();
            sExecutor = null;
        }
        synchronized (CONTROL_LOCK) {
            if (sContext != null && RuntimeAccess.allowsRootCommands()
                    && (isFanOwned() || isPumpOwned())
                    && !restoreBaselineIfOwned()) {
                Log.w(TAG, "hardware state remains owned after runtime stop");
            }
        }
        sFanMode = FanMode.SYSTEM;
        sPumpMode = PumpMode.SYSTEM;
        sAppliedAutoLevel = -1;
        ROOT_SHELL.close();
        sSnapshot = RedmagicHardwareSnapshot.UNAVAILABLE;
        notifyListeners();
        sContext = null;
    }

    static void addListener(final Listener listener) {
        if (listener == null) {
            return;
        }
        LISTENERS.add(listener);
        MAIN.post(() -> listener.onHardwareStateChanged(sSnapshot));
    }

    static void removeListener(final Listener listener) {
        LISTENERS.remove(listener);
    }

    static RedmagicHardwareSnapshot snapshot() {
        return sSnapshot;
    }

    static FanMode fanMode() {
        return sFanMode;
    }

    static PumpMode pumpMode() {
        return sPumpMode;
    }

    static void setFanMode(final FanMode mode, final ResultCallback callback) {
        final ScheduledExecutorService executor = sExecutor;
        if (executor == null || mode == null) {
            complete(callback, false);
            return;
        }
        executor.execute(() -> {
            final RedmagicHardwareSnapshot snapshot = readSnapshot();
            final boolean success;
            synchronized (CONTROL_LOCK) {
                success = !sStopping && applyFanMode(mode, snapshot);
            }
            complete(callback, success);
        });
    }

    static void setPumpMode(final PumpMode mode, final ResultCallback callback) {
        final ScheduledExecutorService executor = sExecutor;
        if (executor == null || mode == null) {
            complete(callback, false);
            return;
        }
        executor.execute(() -> {
            final RedmagicHardwareSnapshot snapshot = readSnapshot();
            final boolean success;
            synchronized (CONTROL_LOCK) {
                success = !sStopping && applyPumpMode(mode, snapshot);
            }
            complete(callback, success);
        });
    }

    static void restoreChangedState(final ResultCallback callback) {
        final ScheduledExecutorService executor = sExecutor;
        if (executor == null) {
            complete(callback, true);
            return;
        }
        executor.execute(() -> {
            final boolean success;
            synchronized (CONTROL_LOCK) {
                success = restoreBaselineIfOwned();
            }
            if (success) {
                sFanMode = FanMode.SYSTEM;
                sPumpMode = PumpMode.SYSTEM;
                sAppliedAutoLevel = -1;
                pollInternal();
            }
            complete(callback, success);
        });
    }

    private static void recoverBaselineIfNeeded() {
        synchronized (CONTROL_LOCK) {
            migrateLegacyOwnership();
            if (isFanOwned() || isPumpOwned()) {
                Log.w(TAG,
                        "recovering hardware state left by an interrupted session");
                restoreBaselineIfOwned();
            }
        }
    }

    private static void pollInternal() {
        final RedmagicHardwareSnapshot snapshot = readSnapshot();
        sSnapshot = snapshot;
        if (sFanMode == FanMode.AUTO && snapshot.fanAvailable) {
            final int currentLevel = sAppliedAutoLevel >= 0
                    ? sAppliedAutoLevel
                    : (snapshot.fanEnabled == 1 ? snapshot.fanLevel : 0);
            final int targetLevel = RedmagicFanCurve.levelFor(
                    snapshot.controlTemperatureMilliCelsius(), currentLevel);
            synchronized (CONTROL_LOCK) {
                if (!sStopping && RedmagicFanCurve.needsApply(
                                targetLevel,
                                sAppliedAutoLevel,
                                snapshot.fanEnabled,
                                snapshot.fanLevel)
                        && applyFanLevel(targetLevel)) {
                    sAppliedAutoLevel = targetLevel;
                    sSnapshot = readSnapshot();
                }
            }
        }
        notifyListeners();
    }

    private static boolean applyFanMode(
            final FanMode mode,
            final RedmagicHardwareSnapshot snapshot) {
        if (!snapshot.fanAvailable) {
            return false;
        }
        final boolean success;
        if (mode == FanMode.SYSTEM) {
            success = restoreOwnedFanState();
        } else if (!captureFanBaseline(snapshot)) {
            success = false;
        } else if (mode == FanMode.OFF) {
            success = writeInteger(FAN_ENABLE, 0);
        } else if (mode == FanMode.AUTO) {
            final int level = RedmagicFanCurve.levelFor(
                    snapshot.controlTemperatureMilliCelsius(),
                    snapshot.fanEnabled == 1 ? snapshot.fanLevel : 0);
            success = applyFanLevel(level);
            if (success) {
                sAppliedAutoLevel = level;
            }
        } else {
            success = applyFanLevel(
                    mode.ordinal() - FanMode.LEVEL_1.ordinal() + 1);
        }
        if (success) {
            sFanMode = mode;
            if (mode != FanMode.AUTO) {
                sAppliedAutoLevel = -1;
            }
            pollInternal();
        }
        return success;
    }

    private static boolean applyPumpMode(
            final PumpMode mode,
            final RedmagicHardwareSnapshot snapshot) {
        if (!snapshot.pumpAvailable) {
            return false;
        }
        final boolean success;
        if (mode == PumpMode.SYSTEM) {
            success = restoreOwnedPumpState();
        } else if (!capturePumpBaseline(snapshot)) {
            success = false;
        } else if (mode == PumpMode.OFF) {
            success = writeInteger(PUMP_ENABLE, 0);
        } else {
            final int speed = mode == PumpMode.SLOW
                    ? 40 : (mode == PumpMode.MEDIUM ? 60 : 80);
            success = writeInteger(PUMP_FREQUENCY, 4)
                    && writeInteger(PUMP_SPEED, speed)
                    && writeInteger(PUMP_ENABLE, 1);
        }
        if (success) {
            sPumpMode = mode;
            pollInternal();
        }
        return success;
    }

    private static RedmagicHardwareSnapshot readSnapshot() {
        if (!RuntimeAccess.allowsRootCommands()) {
            return RedmagicHardwareSnapshot.UNAVAILABLE;
        }
        final String command =
                readNodeCommand("fan_enable", FAN_ENABLE)
                + readNodeCommand("fan_level", FAN_LEVEL)
                + readNodeCommand("fan_rpm", FAN_RPM)
                + readNodeCommand("pump_enable", PUMP_ENABLE)
                + readNodeCommand("pump_frequency", PUMP_FREQUENCY)
                + readNodeCommand("pump_speed", PUMP_SPEED)
                + "for z in /sys/class/thermal/thermal_zone*; do "
                + "[ -r \"$z/type\" ] && [ -r \"$z/temp\" ] || continue; "
                + "t=$(tr -d '\\r\\n' < \"$z/type\"); "
                + "v=$(tr -d '\\r\\n' < \"$z/temp\"); "
                + "printf 'thermal=%s|%s\\n' \"$t\" \"$v\"; done";
        return RedmagicHardwareSnapshot.parse(ROOT_SHELL.run(command));
    }

    private static String readNodeCommand(
            final String key, final String path) {
        return "if [ -r " + path + " ]; then "
                + "v=$(tr -d '\\r\\n' < " + path + "); "
                + "printf 'node." + key + "=%s\\n' \"$v\"; fi; ";
    }

    private static boolean captureFanBaseline(
            final RedmagicHardwareSnapshot snapshot) {
        final SharedPreferences preferences = preferences();
        if (preferences.getBoolean(OWNER_FAN_ACTIVE, false)) {
            return true;
        }
        return preferences.edit()
                .putInt(BASELINE_FAN_ENABLE, snapshot.fanEnabled)
                .putInt(BASELINE_FAN_LEVEL, snapshot.fanLevel)
                .putBoolean(OWNER_FAN_ACTIVE, true)
                .commit();
    }

    private static boolean capturePumpBaseline(
            final RedmagicHardwareSnapshot snapshot) {
        final SharedPreferences preferences = preferences();
        if (preferences.getBoolean(OWNER_PUMP_ACTIVE, false)) {
            return true;
        }
        return preferences.edit()
                .putInt(BASELINE_PUMP_ENABLE, snapshot.pumpEnabled)
                .putInt(BASELINE_PUMP_FREQUENCY, snapshot.pumpFrequency)
                .putInt(BASELINE_PUMP_SPEED, snapshot.pumpSpeed)
                .putBoolean(OWNER_PUMP_ACTIVE, true)
                .commit();
    }

    private static boolean restoreBaselineIfOwned() {
        migrateLegacyOwnership();
        final boolean fan = restoreOwnedFanState();
        final boolean pump = restoreOwnedPumpState();
        return fan && pump;
    }

    private static boolean restoreOwnedFanState() {
        if (!isFanOwned()) {
            return true;
        }
        if (!restoreFanBaseline()) {
            CompatibilityDiagnostics.record(
                    "REDMAGIC-HW-RESTORE-001",
                    "Could not restore REDMAGIC fan state",
                    "fan=false");
            return false;
        }
        return preferences().edit()
                .remove(OWNER_FAN_ACTIVE)
                .remove(BASELINE_FAN_ENABLE)
                .remove(BASELINE_FAN_LEVEL)
                .remove(LEGACY_OWNER_ACTIVE)
                .commit();
    }

    private static boolean restoreOwnedPumpState() {
        if (!isPumpOwned()) {
            return true;
        }
        if (!restorePumpBaseline()) {
            CompatibilityDiagnostics.record(
                    "REDMAGIC-HW-RESTORE-001",
                    "Could not restore REDMAGIC pump state",
                    "pump=false");
            return false;
        }
        return preferences().edit()
                .remove(OWNER_PUMP_ACTIVE)
                .remove(BASELINE_PUMP_ENABLE)
                .remove(BASELINE_PUMP_FREQUENCY)
                .remove(BASELINE_PUMP_SPEED)
                .remove(LEGACY_OWNER_ACTIVE)
                .commit();
    }

    private static boolean isFanOwned() {
        return preferences().getBoolean(OWNER_FAN_ACTIVE, false);
    }

    private static boolean isPumpOwned() {
        return preferences().getBoolean(OWNER_PUMP_ACTIVE, false);
    }

    private static void migrateLegacyOwnership() {
        final SharedPreferences preferences = preferences();
        if (!preferences.getBoolean(LEGACY_OWNER_ACTIVE, false)) {
            return;
        }
        final SharedPreferences.Editor editor = preferences.edit();
        if (preferences.contains(BASELINE_FAN_ENABLE)) {
            editor.putBoolean(OWNER_FAN_ACTIVE, true);
        }
        if (preferences.contains(BASELINE_PUMP_ENABLE)) {
            editor.putBoolean(OWNER_PUMP_ACTIVE, true);
        }
        editor.remove(LEGACY_OWNER_ACTIVE).commit();
    }

    private static boolean restoreFanBaseline() {
        final SharedPreferences preferences = preferences();
        if (!preferences.contains(BASELINE_FAN_ENABLE)) {
            return true;
        }
        final int enabled = preferences.getInt(BASELINE_FAN_ENABLE, 0);
        final int level = preferences.getInt(BASELINE_FAN_LEVEL, 0);
        return writeInteger(FAN_LEVEL, level)
                && writeInteger(FAN_ENABLE, enabled);
    }

    private static boolean restorePumpBaseline() {
        final SharedPreferences preferences = preferences();
        if (!preferences.contains(BASELINE_PUMP_ENABLE)) {
            return true;
        }
        final int enabled = preferences.getInt(BASELINE_PUMP_ENABLE, 0);
        final int frequency = preferences.getInt(
                BASELINE_PUMP_FREQUENCY, 4);
        final int speed = preferences.getInt(BASELINE_PUMP_SPEED, 80);
        return writeInteger(PUMP_FREQUENCY, frequency)
                && writeInteger(PUMP_SPEED, speed)
                && writeInteger(PUMP_ENABLE, enabled);
    }

    private static boolean applyFanLevel(final int level) {
        if (level < 0 || level > 5) {
            return false;
        }
        if (level == 0) {
            return writeInteger(FAN_ENABLE, 0);
        }
        return writeInteger(FAN_LEVEL, level)
                && writeInteger(FAN_ENABLE, 1);
    }

    private static boolean writeInteger(final String path, final int value) {
        if (!isKnownWritablePath(path)) {
            return false;
        }
        final String output = ROOT_SHELL.run(
                "printf '%d' " + value + " > " + path
                        + " && printf 'write=ok\\n'");
        final boolean success = output.contains("write=ok");
        if (!success) {
            CompatibilityDiagnostics.record(
                    "REDMAGIC-HW-WRITE-001",
                    "A REDMAGIC hardware control write failed",
                    "path=" + path + " value=" + value);
        }
        return success;
    }

    private static boolean isKnownWritablePath(final String path) {
        return FAN_ENABLE.equals(path) || FAN_LEVEL.equals(path)
                || PUMP_ENABLE.equals(path)
                || PUMP_FREQUENCY.equals(path)
                || PUMP_SPEED.equals(path);
    }

    private static SharedPreferences preferences() {
        if (sContext == null) {
            throw new IllegalStateException("hardware controller is not started");
        }
        return sContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static void notifyListeners() {
        final RedmagicHardwareSnapshot snapshot = sSnapshot;
        MAIN.post(() -> {
            for (final Listener listener : LISTENERS) {
                listener.onHardwareStateChanged(snapshot);
            }
        });
    }

    private static void complete(
            final ResultCallback callback, final boolean success) {
        if (callback != null) {
            MAIN.post(() -> callback.onComplete(success));
        }
    }
}
