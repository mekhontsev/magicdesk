package io.github.mekhontsev.magicdesk;

import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

final class ConsoleModeSwitcher {
    private static final String TAG = "MagicDeskConsoleSwitcher";
    private static final String AM = "/system/bin/am";
    private static final String CONSOLE_CONTROL_COMMAND =
            "io.github.mekhontsev.magicdesk.ConsoleControlCommand";
    private static final String CONSOLE_TASK_RETURN_COMMAND =
            "io.github.mekhontsev.magicdesk.ConsoleTaskReturnCommand";
    private static final String DEVICE_LOCK_COMMAND =
            "io.github.mekhontsev.magicdesk.DeviceLockCommand";
    private static final String SCREENSHOT_DIRECTORY =
            "/storage/emulated/0/Pictures/Screenshots";
    private static final long SHORTCUT_DEBOUNCE_MS = 300L;
    private static final ConsoleRootShell ROOT_SHELL = new ConsoleRootShell();
    private static final AtomicBoolean DESKTOP_START_IN_PROGRESS = new AtomicBoolean();
    private static String sLastShortcutName;
    private static long sLastShortcutTime;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(
            new ThreadFactory() {
                @Override
                public Thread newThread(final Runnable runnable) {
                    final Thread thread = new Thread(runnable, "MagicDeskConsoleSwitcher");
                    thread.setDaemon(true);
                    return thread;
                }
            });

    private ConsoleModeSwitcher() {
    }

    interface ResultCallback {
        void onComplete(boolean success);
    }

    interface TouchpadRestoreCallback {
        void onComplete(boolean touchpadMissing, boolean restored);
    }

    static void setPhoneScreenOff(final boolean screenOff,
            final ResultCallback callback) {
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                boolean success = false;
                try {
                    final boolean proxySuccess =
                            NubiaTouchpadController
                                    .setMirrorInputProxyEnabledInternal(
                                            !screenOff);
                    if (screenOff && !proxySuccess) {
                        Log.w(TAG, "phone screen will remain on because the Nubia input "
                                + "proxy could not be disabled");
                        CompatibilityDiagnostics.record(
                                "NUBIA-SCREEN-001",
                                "Could not disable the Nubia input proxy",
                                "The phone screen cannot be dimmed safely");
                        return;
                    }
                    final String command = "APK=$(/system/bin/pm path io.github.mekhontsev.magicdesk "
                            + "| /system/bin/cut -d: -f2- | /system/bin/head -n 1); "
                            + "CLASSPATH=\"$APK\" /system/bin/app_process / "
                            + CONSOLE_CONTROL_COMMAND + " phone-screen " + screenOff;
                    final String output = runRootCommand(command).trim();
                    success = proxySuccess
                            && output.contains("phone-screen=" + screenOff);
                    Log.i(TAG, "phone screen off=" + screenOff + " output=" + output);
                } finally {
                    closeRootShell();
                    if (callback != null) {
                        callback.onComplete(success);
                    }
                }
            }
        });
    }

    static void setMirrorInputProxyEnabled(final boolean enabled) {
        setMirrorInputProxyEnabled(enabled, null);
    }

    static void setMirrorInputProxyEnabled(
            final boolean enabled,
            final ResultCallback callback) {
        NubiaTouchpadController.setMirrorInputProxyEnabled(
                enabled, callback);
    }

    static void showMagicDesk() {
        showMagicDesk(-1);
    }

    static void showMagicDesk(final int knownConsoleDisplayId) {
        if (!markShortcut("magicdesk")) {
            return;
        }
        if (!DESKTOP_START_IN_PROGRESS.compareAndSet(false, true)) {
            Log.i(TAG, "MagicDesk activation is already in progress");
            return;
        }
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (RuntimeAccess.allowsShizukuCommands()
                            && !RuntimeAccess.allowsRootCommands()) {
                        ConsoleSessionController.showWithShizuku(
                                knownConsoleDisplayId);
                    } else {
                        ConsoleSessionController.showWithRoot();
                    }
                } finally {
                    DESKTOP_START_IN_PROGRESS.set(false);
                    closeRootShell();
                }
            }
        });
    }

    static void openTouchpad() {
        NubiaTouchpadController.open();
    }

    static boolean isTouchpadVisible() {
        return NubiaTouchpadController.isVisible();
    }

    static void restoreTouchpadIfMissing() {
        restoreTouchpadIfMissing(null);
    }

    static void restoreTouchpadIfMissing(
            final TouchpadRestoreCallback callback) {
        NubiaTouchpadController.restoreIfMissing(callback);
    }

    static void restorePrimaryPhoneHome() {
        NubiaTouchpadController.restorePrimaryPhoneHome();
    }

    static void setExternalTaskCaptionsEnabled(final boolean enabled) {
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    ConsoleSessionController
                            .setExternalTaskCaptionsEnabled(enabled);
                } finally {
                    closeRootShell();
                }
            }
        });
    }

    static void switchToMirror(final ResultCallback callback) {
        if (!DESKTOP_START_IN_PROGRESS.compareAndSet(false, true)) {
            Log.i(TAG, "Console mode transition is already in progress");
            if (callback != null) {
                callback.onComplete(false);
            }
            return;
        }
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                boolean success = false;
                try {
                    if (getActiveConsoleDisplayId() <= 0) {
                        success = true;
                    } else {
                        if (ConsoleDisplayController.requestMirrorMode()) {
                            success = ConsoleDisplayController.waitForConsoleStop();
                            if (!success) {
                                Log.w(TAG, "Console display remained active after Mirror request");
                            }
                        }
                    }
                    if (success) {
                        ConsoleSessionController
                                .setExternalTaskCaptionsEnabled(false);
                    }
                } finally {
                    DESKTOP_START_IN_PROGRESS.set(false);
                    closeRootShell();
                    if (callback != null) {
                        callback.onComplete(success);
                    }
                }
            }
        });
    }

    static void returnConsoleTasksToPhone(final ResultCallback callback) {
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                boolean success = false;
                try {
                    final int displayId = getActiveConsoleDisplayId();
                    if (displayId <= 0) {
                        success = true;
                        return;
                    }
                    final String output = runRootCommand(
                            appProcessCommand(CONSOLE_TASK_RETURN_COMMAND)
                                    + " " + displayId).trim();
                    success = output.contains("tasks-returned=");
                    if (!success) {
                        Log.w(TAG, "Console task return failed output=" + output);
                    }
                } finally {
                    closeRootShell();
                    if (callback != null) {
                        callback.onComplete(success);
                    }
                }
            }
        });
    }

    static void showMagicDeskStart() {
        if (!markShortcut("magicdesk-start")) {
            return;
        }
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    showMagicDeskStartInternal();
                } finally {
                    closeRootShell();
                }
            }
        });
    }

    static void advanceAltTab(final boolean reverse) {
        if (!DesktopRuntimeBridge.advanceAltTab(reverse)) {
            Log.w(TAG, "MagicDesk desktop is unavailable for Alt+Tab");
        }
    }

    static void finishAltTab() {
        if (!DesktopRuntimeBridge.finishAltTab()) {
            Log.w(TAG, "MagicDesk desktop is unavailable for Alt+Tab completion");
        }
    }

    static void cancelAltTab() {
        DesktopRuntimeBridge.cancelAltTab();
    }

    static void sendSystemBack() {
        if (!DesktopTaskController.sendSystemBack()) {
            Log.w(TAG, "system Back shortcut unavailable");
        }
    }

    static void lockDevice() {
        if (!markShortcut("lock-device")) {
            return;
        }
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    final String output =
                            runRootCommand(appProcessCommand(DEVICE_LOCK_COMMAND)).trim();
                    if (!output.contains("device-locked")) {
                        Log.w(TAG, "device lock shortcut failed output="
                                + output.replace('\n', ' '));
                    }
                } finally {
                    closeRootShell();
                }
            }
        });
    }

    static void manageActiveWindow(final int shortcut) {
        if (!markShortcut("window-" + shortcut)) {
            return;
        }
        if (!DesktopTaskController.handleActiveTaskShortcut(shortcut)) {
            Log.w(TAG, "window shortcut unavailable action=" + shortcut);
        }
    }

    static void showShortcutHelp() {
        if (!markShortcut("shortcut-help")) {
            return;
        }
        if (!DesktopRuntimeBridge.toggleShortcutHelp()) {
            Log.w(TAG, "MagicDesk desktop is unavailable for shortcut help");
        }
    }

    static void toggleNotificationCenter() {
        if (!markShortcut("notifications")) {
            return;
        }
        if (!DesktopRuntimeBridge.toggleNotificationCenter()) {
            Log.w(TAG, "MagicDesk desktop is unavailable for notifications");
        }
    }

    static void captureScreenshot() {
        if (!markShortcut("screenshot")) {
            return;
        }
        if (!RuntimeAccess.has(RuntimeAccess.Capability.SCREENSHOT)) {
            Log.w(TAG, "screenshot unavailable for backend="
                    + RuntimeAccess.backendName());
            return;
        }
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    captureScreenshotInternal();
                } finally {
                    closeRootShell();
                }
            }
        });
    }

    static void toggleHardwareKeyboardLayout() {
        if (markShortcut("keyboard-layout")) {
            HardwareKeyboardLayoutController.toggle();
        }
    }

    static void refreshHardwareKeyboardLayout() {
        HardwareKeyboardLayoutController.refresh();
    }

    static void executeSerialized(final Runnable action) {
        EXECUTOR.execute(action);
    }

    private static String shellQuote(final String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    static int getActiveConsoleDisplayId() {
        return ConsoleDisplayController.getActiveConsoleDisplayId();
    }

    private static void captureScreenshotInternal() {
        String path = null;
        try {
            final String physicalDisplayId =
                    ConsoleDisplayController.getExternalPhysicalDisplayId();
            final String fileName = "MagicDesk_"
                    + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
                            .format(new Date())
                    + ".png";
            path = SCREENSHOT_DIRECTORY + "/" + fileName;
            final String displayArgument = physicalDisplayId == null
                    ? "" : "-d " + physicalDisplayId + " ";
            final String command = "umask 002; "
                    + "/system/bin/mkdir -p " + shellQuote(SCREENSHOT_DIRECTORY)
                    + " && /system/bin/screencap " + displayArgument
                    + "-p " + shellQuote(path)
                    + " && /system/bin/test -s " + shellQuote(path)
                    + " && /system/bin/chmod 0664 " + shellQuote(path)
                    + " && /system/bin/am broadcast --user 0"
                    + " -a android.intent.action.MEDIA_SCANNER_SCAN_FILE"
                    + " -d " + shellQuote("file://" + path)
                    + " >/dev/null"
                    + " && echo " + shellQuote("screenshot-saved=" + path);
            final String output = PrivilegedCommandRunner.run(command).trim();
            if (!output.contains("screenshot-saved=" + path)) {
                throw new IOException(
                        "unexpected screenshot response: "
                                + output.replace('\n', ' '));
            }
            Log.i(TAG, "screenshot saved path=" + path
                    + " physicalDisplay=" + physicalDisplayId);
        } catch (IOException error) {
            Log.w(TAG, "screenshot failed path=" + path, error);
            CompatibilityDiagnostics.record(
                    "SCREENSHOT-001",
                    "Could not capture the external display",
                    error.getMessage());
        }
    }

    private static String appProcessCommand(final String mainClass) {
        return "APK=$(/system/bin/pm path io.github.mekhontsev.magicdesk "
                + "| /system/bin/cut -d: -f2- | /system/bin/head -n 1); "
                + "CLASSPATH=\"$APK\" /system/bin/app_process / " + mainClass;
    }

    private static void showMagicDeskStartInternal() {
        Log.i(TAG, "show MagicDesk Start overlay");
        runRootCommand(AM + " broadcast --receiver-foreground"
                + " -a io.github.mekhontsev.magicdesk.action.SHOW_START"
                + " -n io.github.mekhontsev.magicdesk/.DesktopCommandReceiver");
    }

    static void closeRootShell() {
        ROOT_SHELL.close();
    }

    private static synchronized boolean markShortcut(final String shortcutName) {
        final long now = SystemClock.uptimeMillis();
        if (shortcutName.equals(sLastShortcutName)
                && now - sLastShortcutTime < SHORTCUT_DEBOUNCE_MS) {
            Log.i(TAG, "debounced shortcut " + shortcutName);
            return false;
        }
        sLastShortcutName = shortcutName;
        sLastShortcutTime = now;
        return true;
    }

    static String runRootCommand(final String command) {
        return ROOT_SHELL.run(command);
    }
}
