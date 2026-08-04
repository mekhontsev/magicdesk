package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

final class RedmagicHardwareController {
    enum FanMode {
        SYSTEM,
        OFF,
        AUTO,
        EXTREME
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
    private static final String OWNER_VENDOR_FAN_ACTIVE =
            "owner_vendor_fan_active";
    private static final String OWNER_VENDOR_PUMP_ACTIVE =
            "owner_vendor_pump_active";
    private static final String BASELINE_VENDOR_FAN_MANUAL =
            "baseline_vendor_fan_manual";
    private static final String BASELINE_VENDOR_FAN_MODE =
            "baseline_vendor_fan_mode";
    private static final String BASELINE_VENDOR_PUMP_MAIN =
            "baseline_vendor_pump_main";
    private static final String BASELINE_VENDOR_PUMP_FLOW =
            "baseline_vendor_pump_flow";
    private static final String ABSENT_SETTING = "__magicdesk_absent__";
    private static final long POLL_SECONDS = 4;

    private static final String VENDOR_FAN_MANUAL = "fan_state_of_manual";
    private static final String VENDOR_FAN_MODE = "fan_state_of_mode";
    private static final String VENDOR_FAN_EFFECTIVE = "game_fan_off_on";
    private static final String VENDOR_PUMP_MAIN =
            "liquid_cooling_main_switch";
    private static final String VENDOR_PUMP_FLOW =
            "liquid_cooling_flow_speed_mode";
    private static final String VENDOR_PUMP_EFFECTIVE =
            "liquid_cooling_off_on";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object CONTROL_LOCK = new Object();
    private static final Set<Listener> LISTENERS =
            new CopyOnWriteArraySet<>();
    private static final Set<Listener> MONITORING_LISTENERS =
            new CopyOnWriteArraySet<>();

    private static ScheduledExecutorService sExecutor;
    private static ScheduledFuture<?> sPollTask;
    private static Context sContext;
    private static volatile RedmagicHardwareSnapshot sSnapshot =
            RedmagicHardwareSnapshot.UNAVAILABLE;
    private static volatile FanMode sFanMode = FanMode.SYSTEM;
    private static volatile PumpMode sPumpMode = PumpMode.SYSTEM;
    private static volatile boolean sStopping;
    // May be set while a previous executor is still completing asynchronous stop.
    private static Context sRequestedContext;

    private RedmagicHardwareController() {
    }

    static synchronized void start(final Context context) {
        if (!ShellAccess.isReady()) {
            return;
        }
        sRequestedContext = context.getApplicationContext();
        if (sStopping || sExecutor != null) {
            return;
        }
        startRequestedContextLocked();
    }

    private static void startRequestedContextLocked() {
        sContext = sRequestedContext;
        if (sContext == null) {
            return;
        }
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
        if (canControlHardware()) {
            sExecutor.execute(
                    RedmagicHardwareController::recoverBaselineIfNeeded);
        }
        sExecutor.execute(RedmagicHardwareController::pollInternal);
        updatePollingLocked(POLL_SECONDS);
    }

    static synchronized void stop() {
        sRequestedContext = null;
        if (sStopping) {
            return;
        }
        sStopping = true;
        cancelPollingLocked();
        final ScheduledExecutorService executor = sExecutor;
        if (executor == null) {
            clearStoppedState();
            sStopping = false;
            return;
        }
        executor.execute(() -> {
            synchronized (CONTROL_LOCK) {
                if (sContext != null && canControlHardware()
                        && hasOwnedState()
                        && !restoreBaselineIfOwned()) {
                    Log.w(TAG, "hardware state remains owned after runtime stop");
                }
            }
            synchronized (RedmagicHardwareController.class) {
                if (sExecutor == executor) {
                    sExecutor = null;
                    clearStoppedState();
                    sStopping = false;
                    if (sRequestedContext != null
                            && ShellAccess.isReady()) {
                        startRequestedContextLocked();
                    }
                }
            }
        });
        executor.shutdown();
    }

    private static void clearStoppedState() {
        sFanMode = FanMode.SYSTEM;
        sPumpMode = PumpMode.SYSTEM;
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
        setMonitoringEnabled(listener, false);
        LISTENERS.remove(listener);
    }

    static synchronized void setMonitoringEnabled(
            final Listener listener,
            final boolean enabled) {
        if (listener == null) {
            return;
        }
        final boolean changed = enabled
                ? MONITORING_LISTENERS.add(listener)
                : MONITORING_LISTENERS.remove(listener);
        if (!changed) {
            return;
        }
        updatePollingLocked(enabled ? 0L : POLL_SECONDS);
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
        if (executor == null || executor.isShutdown() || sStopping || mode == null
                || !canControlHardware()) {
            complete(callback, false);
            return;
        }
        try {
            executor.execute(() -> {
                final boolean success;
                synchronized (CONTROL_LOCK) {
                    success = !sStopping && applyFanMode(mode);
                }
                complete(callback, success);
            });
        } catch (RejectedExecutionException error) {
            complete(callback, false);
        }
    }

    static void setPumpMode(final PumpMode mode, final ResultCallback callback) {
        final ScheduledExecutorService executor = sExecutor;
        if (executor == null || executor.isShutdown() || sStopping || mode == null
                || !canControlHardware()) {
            complete(callback, false);
            return;
        }
        try {
            executor.execute(() -> {
                final boolean success;
                synchronized (CONTROL_LOCK) {
                    success = !sStopping && applyPumpMode(mode);
                }
                complete(callback, success);
            });
        } catch (RejectedExecutionException error) {
            complete(callback, false);
        }
    }

    static void restoreChangedState(final ResultCallback callback) {
        final ScheduledExecutorService executor = sExecutor;
        if (executor == null || executor.isShutdown() || sStopping) {
            complete(callback, true);
            return;
        }
        try {
            executor.execute(() -> {
                final boolean success;
                synchronized (CONTROL_LOCK) {
                    success = restoreBaselineIfOwned();
                }
                if (success) {
                    sFanMode = FanMode.SYSTEM;
                    sPumpMode = PumpMode.SYSTEM;
                    pollInternal();
                }
                complete(callback, success);
            });
        } catch (RejectedExecutionException error) {
            complete(callback, false);
        }
    }

    private static void recoverBaselineIfNeeded() {
        synchronized (CONTROL_LOCK) {
            if (hasOwnedState()) {
                Log.w(TAG,
                        "recovering hardware state left by an interrupted session");
                restoreBaselineIfOwned();
            }
        }
    }

    private static void pollInternal() {
        final RedmagicHardwareSnapshot snapshot = readSnapshot();
        sSnapshot = snapshot;
        notifyListeners();
    }

    private static void updatePollingLocked(final long initialDelaySeconds) {
        if (sExecutor == null || sExecutor.isShutdown()
                || MONITORING_LISTENERS.isEmpty()) {
            cancelPollingLocked();
            return;
        }
        if (sPollTask != null && !sPollTask.isDone()) {
            return;
        }
        sPollTask = sExecutor.scheduleWithFixedDelay(
                RedmagicHardwareController::pollInternal,
                initialDelaySeconds,
                POLL_SECONDS,
                TimeUnit.SECONDS);
    }

    private static void cancelPollingLocked() {
        if (sPollTask != null) {
            sPollTask.cancel(false);
            sPollTask = null;
        }
    }

    private static boolean applyFanMode(final FanMode mode) {
        return applyVendorFanMode(mode);
    }

    private static boolean applyPumpMode(final PumpMode mode) {
        return applyVendorPumpMode(mode);
    }

    private static boolean applyVendorFanMode(final FanMode mode) {
        final boolean success;
        if (mode == FanMode.SYSTEM) {
            success = restoreVendorFanState();
        } else if (!captureVendorFanBaseline()) {
            success = false;
        } else if (mode == FanMode.OFF) {
            success = writeVendorSettings(
                    settingPut(VENDOR_FAN_MANUAL, "0"),
                    settingEquals(VENDOR_FAN_MANUAL, "0"));
        } else if (mode == FanMode.AUTO) {
            success = writeVendorSettings(
                    settingPut(VENDOR_FAN_MODE, "1")
                            + settingPut(VENDOR_FAN_MANUAL, "1"),
                    settingEquals(VENDOR_FAN_MODE, "1")
                            + settingEquals(VENDOR_FAN_MANUAL, "1"));
        } else {
            success = writeVendorSettings(
                    settingPut(VENDOR_FAN_MODE, "0")
                            + settingPut(VENDOR_FAN_MANUAL, "1"),
                    settingEquals(VENDOR_FAN_MODE, "0")
                            + settingEquals(VENDOR_FAN_MANUAL, "1"));
        }
        if (success) {
            sFanMode = mode;
            notifyListeners();
        }
        return success;
    }

    private static boolean applyVendorPumpMode(final PumpMode mode) {
        final boolean success;
        if (mode == PumpMode.SYSTEM) {
            success = restoreVendorPumpState();
        } else if (!captureVendorPumpBaseline()) {
            success = false;
        } else if (mode == PumpMode.OFF) {
            success = writeVendorSettings(
                    settingPut(VENDOR_PUMP_MAIN, "0"),
                    settingEquals(VENDOR_PUMP_MAIN, "0"));
        } else {
            final String flow = mode == PumpMode.SLOW
                    ? "low" : (mode == PumpMode.MEDIUM ? "mid" : "fast");
            success = writeVendorSettings(
                    settingPut(VENDOR_PUMP_FLOW, flow)
                            + settingPut(VENDOR_PUMP_MAIN, "1"),
                    settingEquals(VENDOR_PUMP_FLOW, flow)
                            + settingEquals(VENDOR_PUMP_MAIN, "1"));
        }
        if (success) {
            sPumpMode = mode;
            notifyListeners();
        }
        return success;
    }

    private static RedmagicHardwareSnapshot readSnapshot() {
        if (!ShellAccess.isReady()) {
            return RedmagicHardwareSnapshot.UNAVAILABLE;
        }
        String monitoringCommand =
                "for z in /sys/class/thermal/thermal_zone*; do "
                + "[ -r \"$z/type\" ] && [ -r \"$z/temp\" ] || continue; "
                + "t=$(tr -d '\\r\\n' < \"$z/type\"); "
                + "v=$(tr -d '\\r\\n' < \"$z/temp\"); "
                + "printf 'thermal=%s|%s\\n' \"$t\" \"$v\"; done";
        monitoringCommand += vendorMonitoringCommand();
        try {
            return RedmagicHardwareSnapshot.parse(
                    ShellAccess.run(monitoringCommand));
        } catch (IOException error) {
            Log.w(TAG, "Shell thermal read failed", error);
            CompatibilityDiagnostics.record(
                    "REDMAGIC-HW-MONITOR-001",
                    "Could not read REDMAGIC thermal sensors",
                    "shell=" + ShellAccess.statusLabel(),
                    error);
            return RedmagicHardwareSnapshot.UNAVAILABLE;
        }
    }

    private static String vendorMonitoringCommand() {
        return "; fe=$(/system/bin/settings get global "
                + VENDOR_FAN_EFFECTIVE + "); "
                + "case \"$fe\" in 0|1) "
                + "printf 'node.fan_enable=%s\\n' \"$fe\";; esac; "
                + "pe=$(/system/bin/settings get system "
                + VENDOR_PUMP_EFFECTIVE + "); "
                + "case \"$pe\" in 0|1) "
                + "printf 'node.pump_enable=%s\\n' \"$pe\"; "
                + "pf=$(/system/bin/settings get system "
                + VENDOR_PUMP_FLOW + "); "
                + "case \"$pf\" in low) ps=60;; mid) ps=70;; *) ps=80;; esac; "
                + "printf 'node.pump_speed=%s\\n' "
                + "\"$ps\";; esac; ";
    }

    private static boolean captureVendorFanBaseline() {
        final SharedPreferences preferences = preferences();
        if (preferences.getBoolean(OWNER_VENDOR_FAN_ACTIVE, false)) {
            return true;
        }
        final String output = readVendorSettings(
                VENDOR_FAN_MANUAL, VENDOR_FAN_MODE);
        final String manual = settingFromOutput(output, VENDOR_FAN_MANUAL);
        final String mode = settingFromOutput(output, VENDOR_FAN_MODE);
        if (manual == null || mode == null) {
            return false;
        }
        return preferences.edit()
                .putString(BASELINE_VENDOR_FAN_MANUAL, manual)
                .putString(BASELINE_VENDOR_FAN_MODE, mode)
                .putBoolean(OWNER_VENDOR_FAN_ACTIVE, true)
                .commit();
    }

    private static boolean captureVendorPumpBaseline() {
        final SharedPreferences preferences = preferences();
        if (preferences.getBoolean(OWNER_VENDOR_PUMP_ACTIVE, false)) {
            return true;
        }
        final String output = readVendorSettings(
                VENDOR_PUMP_MAIN, VENDOR_PUMP_FLOW);
        final String main = settingFromOutput(output, VENDOR_PUMP_MAIN);
        final String flow = settingFromOutput(output, VENDOR_PUMP_FLOW);
        if (main == null || flow == null) {
            return false;
        }
        return preferences.edit()
                .putString(BASELINE_VENDOR_PUMP_MAIN, main)
                .putString(BASELINE_VENDOR_PUMP_FLOW, flow)
                .putBoolean(OWNER_VENDOR_PUMP_ACTIVE, true)
                .commit();
    }

    private static boolean restoreBaselineIfOwned() {
        final boolean vendorFan = !ShellAccess.isReady()
                || restoreVendorFanState();
        final boolean vendorPump = !ShellAccess.isReady()
                || restoreVendorPumpState();
        return vendorFan && vendorPump;
    }

    private static boolean restoreVendorFanState() {
        final SharedPreferences preferences = preferences();
        if (!preferences.getBoolean(OWNER_VENDOR_FAN_ACTIVE, false)) {
            return true;
        }
        if (!preferences.contains(BASELINE_VENDOR_FAN_MANUAL)
                || !preferences.contains(BASELINE_VENDOR_FAN_MODE)) {
            return false;
        }
        final String manual = preferences.getString(
                BASELINE_VENDOR_FAN_MANUAL, ABSENT_SETTING);
        final String mode = preferences.getString(
                BASELINE_VENDOR_FAN_MODE, ABSENT_SETTING);
        final boolean success = writeVendorSettings(
                settingPut(VENDOR_FAN_MANUAL, "0")
                        + restoreSetting(VENDOR_FAN_MODE, mode)
                        + restoreSetting(VENDOR_FAN_MANUAL, manual),
                settingEquals(VENDOR_FAN_MODE, expectedSetting(mode))
                        + settingEquals(
                                VENDOR_FAN_MANUAL,
                                expectedSetting(manual)));
        if (!success) {
            recordVendorRestoreFailure("fan");
            return false;
        }
        return preferences.edit()
                .remove(OWNER_VENDOR_FAN_ACTIVE)
                .remove(BASELINE_VENDOR_FAN_MANUAL)
                .remove(BASELINE_VENDOR_FAN_MODE)
                .commit();
    }

    private static boolean restoreVendorPumpState() {
        final SharedPreferences preferences = preferences();
        if (!preferences.getBoolean(OWNER_VENDOR_PUMP_ACTIVE, false)) {
            return true;
        }
        if (!preferences.contains(BASELINE_VENDOR_PUMP_MAIN)
                || !preferences.contains(BASELINE_VENDOR_PUMP_FLOW)) {
            return false;
        }
        final String main = preferences.getString(
                BASELINE_VENDOR_PUMP_MAIN, ABSENT_SETTING);
        final String flow = preferences.getString(
                BASELINE_VENDOR_PUMP_FLOW, ABSENT_SETTING);
        final boolean success = writeVendorSettings(
                settingPut(VENDOR_PUMP_MAIN, "0")
                        + restoreSetting(VENDOR_PUMP_FLOW, flow)
                        + restoreSetting(VENDOR_PUMP_MAIN, main),
                settingEquals(VENDOR_PUMP_FLOW, expectedSetting(flow))
                        + settingEquals(
                                VENDOR_PUMP_MAIN,
                                expectedSetting(main)));
        if (!success) {
            recordVendorRestoreFailure("pump");
            return false;
        }
        return preferences.edit()
                .remove(OWNER_VENDOR_PUMP_ACTIVE)
                .remove(BASELINE_VENDOR_PUMP_MAIN)
                .remove(BASELINE_VENDOR_PUMP_FLOW)
                .commit();
    }

    private static boolean hasOwnedState() {
        final SharedPreferences preferences = preferences();
        return ShellAccess.isReady()
                && (preferences.getBoolean(
                                OWNER_VENDOR_FAN_ACTIVE, false)
                        || preferences.getBoolean(
                                OWNER_VENDOR_PUMP_ACTIVE, false));
    }

    private static boolean canControlHardware() {
        return ShellAccess.isReady();
    }

    private static String readVendorSettings(
            final String first,
            final String second) {
        if (!isKnownVendorSetting(first) || !isKnownVendorSetting(second)) {
            return null;
        }
        final String command =
                "printf 'setting." + first + "=%s\\n' \"$("
                        + "/system/bin/settings get system " + first
                        + ")\"; "
                        + "printf 'setting." + second + "=%s\\n' \"$("
                        + "/system/bin/settings get system " + second
                        + ")\"";
        try {
            return ShellAccess.run(command);
        } catch (IOException error) {
            Log.w(TAG, "vendor hardware settings read failed", error);
            CompatibilityDiagnostics.record(
                    "REDMAGIC-HW-VENDOR-READ-001",
                    "Could not read REDMAGIC hardware settings",
                    "shell=" + ShellAccess.statusLabel(),
                    error);
            return null;
        }
    }

    private static String settingFromOutput(
            final String output,
            final String key) {
        if (output == null || !isKnownVendorSetting(key)) {
            return null;
        }
        final String prefix = "setting." + key + "=";
        for (final String line : output.split("\\r?\\n")) {
            if (!line.startsWith(prefix)) {
                continue;
            }
            final String value = line.substring(prefix.length()).trim();
            return value.isEmpty() || "null".equals(value)
                    ? ABSENT_SETTING : value;
        }
        return null;
    }

    private static boolean writeVendorSettings(
            final String writes,
            final String verification) {
        try {
            final String output = ShellAccess.run(
                    writes + verification
                            + "printf 'write=ok\\n'");
            if (output.contains("write=ok")) {
                return true;
            }
        } catch (IOException error) {
            Log.w(TAG, "vendor hardware settings write failed", error);
            CompatibilityDiagnostics.record(
                    "REDMAGIC-HW-VENDOR-WRITE-001",
                    "A REDMAGIC vendor hardware request failed",
                    "shell=" + ShellAccess.statusLabel(),
                    error);
            return false;
        }
        CompatibilityDiagnostics.record(
                "REDMAGIC-HW-VENDOR-WRITE-001",
                "A REDMAGIC vendor hardware request failed",
                "shell=" + ShellAccess.statusLabel());
        return false;
    }

    private static String settingPut(
            final String key,
            final String value) {
        if (!isKnownVendorSetting(key)) {
            throw new IllegalArgumentException("unknown vendor setting");
        }
        return "/system/bin/settings put system " + key + " "
                + shellQuote(value) + " && ";
    }

    private static String restoreSetting(
            final String key,
            final String value) {
        if (!isKnownVendorSetting(key)) {
            throw new IllegalArgumentException("unknown vendor setting");
        }
        if (ABSENT_SETTING.equals(value)) {
            return "/system/bin/settings delete system " + key + " && ";
        }
        return settingPut(key, value);
    }

    private static String settingEquals(
            final String key,
            final String value) {
        if (!isKnownVendorSetting(key)) {
            throw new IllegalArgumentException("unknown vendor setting");
        }
        return "[ \"$(/system/bin/settings get system " + key
                + ")\" = " + shellQuote(value) + " ] && ";
    }

    private static String expectedSetting(final String value) {
        return ABSENT_SETTING.equals(value) ? "null" : value;
    }

    private static boolean isKnownVendorSetting(final String key) {
        return VENDOR_FAN_MANUAL.equals(key)
                || VENDOR_FAN_MODE.equals(key)
                || VENDOR_PUMP_MAIN.equals(key)
                || VENDOR_PUMP_FLOW.equals(key);
    }

    private static String shellQuote(final String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static void recordVendorRestoreFailure(final String component) {
        CompatibilityDiagnostics.record(
                "REDMAGIC-HW-RESTORE-001",
                "Could not restore REDMAGIC vendor hardware state",
                "component=" + component
                        + " shell=" + ShellAccess.statusLabel());
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
