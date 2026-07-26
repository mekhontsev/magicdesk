package io.github.mekhontsev.magicdesk;

import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ConsoleModeSwitcher {
    private static final String TAG = "MagicDeskConsoleSwitcher";
    private static final String SETTINGS = "/system/bin/settings";
    private static final String AM = "/system/bin/am";
    private static final String PM = "/system/bin/pm";
    private static final String WM = "/system/bin/wm";
    private static final String NUBIA_MIRROR_INPUT_SERVICE =
            "cn.nubia.keymapcenter/.mirror.MirrorInputService";
    private static final String NUBIA_MIRROR_INPUT_ACTIVITY =
            "cn.nubia.keymapcenter/.mirror.MirrorInputActivity";
    private static final String NUBIA_MIRROR_INPUT_ACTION =
            "cn.nubia.keymapcenter.intent.action.MIRROR_INPUT";
    private static final String PRIMARY_PHONE_HOME =
            "com.zte.mifavor.launcher/"
                    + "com.android.launcher3.uioverrides.QuickstepLauncher";
    private static final String HARDWARE_LAYOUT_STATE = "magicdesk_hardware_keyboard_layout";
    private static final String HARDWARE_LAYOUT_LABEL_STATE =
            "magicdesk_hardware_keyboard_layout_label";
    private static final String HARDWARE_LAYOUT_NAME_STATE =
            "magicdesk_hardware_keyboard_layout_name";
    private static final String HARDWARE_LAYOUT_COMMAND =
            "io.github.mekhontsev.magicdesk.HardwareKeyboardLayoutCommand";
    private static final String CONSOLE_CONTROL_COMMAND =
            "io.github.mekhontsev.magicdesk.ConsoleControlCommand";
    private static final String CONSOLE_DISPLAY_COMMAND =
            "io.github.mekhontsev.magicdesk.ConsoleDisplayCommand";
    private static final String CONSOLE_TASK_RETURN_COMMAND =
            "io.github.mekhontsev.magicdesk.ConsoleTaskReturnCommand";
    private static final String DEVICE_LOCK_COMMAND =
            "io.github.mekhontsev.magicdesk.DeviceLockCommand";
    private static final String MOUSE_VIEWPORT_COMMAND =
            "io.github.mekhontsev.magicdesk.MouseViewportCommand";
    private static final String SURFACE_FLINGER_OPTION_COMMAND =
            "io.github.mekhontsev.magicdesk.SurfaceFlingerOptionCommand";
    private static final String TASK_CONTROL_COMMAND =
            "io.github.mekhontsev.magicdesk.TaskControlCommand";
    private static final String DISPLAY = "/system/bin/cmd display";
    private static final String SCREENSHOT_DIRECTORY =
            "/storage/emulated/0/Pictures/Screenshots";
    private static final Pattern DISPLAY_REAL_SIZE_PATTERN =
            Pattern.compile("Display id (\\d+): .* real (\\d+) x (\\d+),");
    private static final Pattern EXTERNAL_PHYSICAL_DISPLAY_PATTERN =
            Pattern.compile("type EXTERNAL,.*?uniqueId \"local:([0-9]+)\"");
    private static final long SHORTCUT_DEBOUNCE_MS = 300L;
    private static final long CONSOLE_START_TIMEOUT_MS = 10_000L;
    private static final long LANDSCAPE_APPLY_TIMEOUT_MS = 2_000L;
    private static final long TOUCHPAD_TRANSITION_TIMEOUT_MS = 2_000L;
    private static final long CONSOLE_STATE_POLL_MS = 100L;
    private static final RootShell ROOT_SHELL = new RootShell();
    private static final AtomicBoolean DESKTOP_START_IN_PROGRESS = new AtomicBoolean();
    private static final AtomicBoolean TOUCHPAD_OPEN_IN_PROGRESS = new AtomicBoolean();
    private static final AtomicBoolean HARDWARE_LAYOUT_REFRESH_IN_PROGRESS =
            new AtomicBoolean();
    private static String sLastShortcutName;
    private static long sLastShortcutTime;
    private static Boolean sMirrorInputProxyEnabled;

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
                            setMirrorInputProxyEnabledInternal(!screenOff);
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

    static void setMirrorInputProxyEnabled(final boolean enabled,
            final ResultCallback callback) {
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                boolean success = false;
                try {
                    success = setMirrorInputProxyEnabledInternal(enabled);
                } finally {
                    closeRootShell();
                    if (callback != null) {
                        callback.onComplete(success);
                    }
                }
            }
        });
    }

    static void showMagicDesk() {
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
                    showMagicDeskInternal();
                } finally {
                    DESKTOP_START_IN_PROGRESS.set(false);
                    closeRootShell();
                }
            }
        });
    }

    static void openTouchpad() {
        if (!TOUCHPAD_OPEN_IN_PROGRESS.compareAndSet(false, true)) {
            Log.i(TAG, "Nubia touchpad activation is already in progress");
            return;
        }
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (getActiveConsoleDisplayId() <= 0) {
                        Log.w(TAG, "cannot open Nubia touchpad: Console mode is inactive");
                        CompatibilityDiagnostics.record(
                                "NUBIA-TOUCHPAD-001",
                                "Cannot open the Nubia touchpad",
                                "Console mode is inactive");
                        return;
                    }
                    requestTouchpad();
                } finally {
                    TOUCHPAD_OPEN_IN_PROGRESS.set(false);
                    closeRootShell();
                }
            }
        });
    }

    static boolean isTouchpadVisible() {
        try {
            return getActiveConsoleDisplayId() > 0 && isTouchpadActivityPresent();
        } finally {
            closeRootShell();
        }
    }

    static void restoreTouchpadIfMissing() {
        restoreTouchpadIfMissing(null);
    }

    static void restoreTouchpadIfMissing(final TouchpadRestoreCallback callback) {
        if (!TOUCHPAD_OPEN_IN_PROGRESS.compareAndSet(false, true)) {
            Log.i(TAG, "Nubia touchpad activation is already in progress");
            if (callback != null) {
                callback.onComplete(false, false);
            }
            return;
        }
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                boolean touchpadMissing = false;
                boolean restored = false;
                try {
                    if (getActiveConsoleDisplayId() <= 0) {
                        return;
                    }
                    if (isTouchpadActivityPresent()) {
                        Log.i(TAG, "Nubia touchpad remained visible after desktop transition");
                        return;
                    }
                    touchpadMissing = true;
                    Log.i(TAG, "restore Nubia touchpad after desktop transition");
                    restored = requestTouchpad();
                } finally {
                    TOUCHPAD_OPEN_IN_PROGRESS.set(false);
                    closeRootShell();
                    if (callback != null) {
                        callback.onComplete(touchpadMissing, restored);
                    }
                }
            }
        });
    }

    static void restorePrimaryPhoneHome() {
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    runRootCommand(AM + " start --display 0"
                            + " --activity-clear-top --activity-single-top"
                            + " -a android.intent.action.MAIN"
                            + " -c android.intent.category.HOME"
                            + " -n " + PRIMARY_PHONE_HOME);
                } finally {
                    closeRootShell();
                }
            }
        });
    }

    static void setExternalTaskCaptionsEnabled(final boolean enabled) {
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    setExternalTaskCaptionsEnabledInternal(enabled);
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
                        final String output = runRootCommand(
                                appProcessCommand(CONSOLE_DISPLAY_COMMAND)
                                        + " mirror 0").trim();
                        if (!output.contains("display-command=mirror")) {
                            Log.w(TAG, "Mirror mode request failed output=" + output);
                        } else {
                            success = waitForConsoleStop();
                            if (!success) {
                                Log.w(TAG, "Console display remained active after Mirror request");
                            }
                        }
                    }
                    if (success) {
                        setExternalTaskCaptionsEnabledInternal(false);
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
        if (!MainActivity.advanceAltTabIfRunning(reverse)) {
            Log.w(TAG, "MagicDesk desktop is unavailable for Alt+Tab");
        }
    }

    static void finishAltTab() {
        if (!MainActivity.finishAltTabIfRunning()) {
            Log.w(TAG, "MagicDesk desktop is unavailable for Alt+Tab completion");
        }
    }

    static void cancelAltTab() {
        MainActivity.cancelAltTabIfRunning();
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
        if (!MainActivity.toggleShortcutHelpIfRunning()) {
            Log.w(TAG, "MagicDesk desktop is unavailable for shortcut help");
        }
    }

    static void toggleNotificationCenter() {
        if (!markShortcut("notifications")) {
            return;
        }
        if (!MainActivity.toggleNotificationCenterIfRunning()) {
            Log.w(TAG, "MagicDesk desktop is unavailable for notifications");
        }
    }

    static void captureScreenshot() {
        if (!markShortcut("screenshot")) {
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
        if (!markShortcut("keyboard-layout")) {
            return;
        }
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    applyHardwareKeyboardLayout("next");
                } finally {
                    closeRootShell();
                }
            }
        });
    }

    static void refreshHardwareKeyboardLayout() {
        if (!HARDWARE_LAYOUT_REFRESH_IN_PROGRESS.compareAndSet(false, true)) {
            Log.d(TAG, "hardware keyboard layout refresh already pending");
            return;
        }
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    applyHardwareKeyboardLayout("sync");
                } finally {
                    HARDWARE_LAYOUT_REFRESH_IN_PROGRESS.set(false);
                    closeRootShell();
                }
            }
        });
    }

    private static void applyHardwareKeyboardLayout(final String mode) {
        final String command = "CURRENT=$(" + SETTINGS + " get global "
                + HARDWARE_LAYOUT_STATE + "); "
                + "APK=$(/system/bin/pm path io.github.mekhontsev.magicdesk "
                + "| /system/bin/cut -d: -f2- | /system/bin/head -n 1); "
                + "CLASSPATH=\"$APK\" /system/bin/app_process / "
                + HARDWARE_LAYOUT_COMMAND + " " + mode + " \"$CURRENT\"";
        final String output = runRootCommand(command).trim();
        final String descriptor = parseOutputValue(output, "descriptor");
        final String code = parseOutputValue(output, "code");
        final String name64 = parseOutputValue(output, "name64");
        if (descriptor == null || code == null || name64 == null) {
            Log.w(TAG, "hardware keyboard layout command failed output=" + output);
            return;
        }

        final String name;
        try {
            name = new String(Base64.decode(name64, Base64.DEFAULT), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "invalid hardware keyboard layout name output=" + output, e);
            return;
        }
        runRootCommand(SETTINGS + " put global " + HARDWARE_LAYOUT_LABEL_STATE
                + " " + shellQuote(code));
        runRootCommand(SETTINGS + " put global " + HARDWARE_LAYOUT_NAME_STATE
                + " " + shellQuote(name));
        runRootCommand(SETTINGS + " put global " + HARDWARE_LAYOUT_STATE
                + " " + shellQuote(descriptor));
        Log.i(TAG, "hardware keyboard " + output.replace('\n', ' '));
    }

    private static String parseOutputValue(final String output, final String key) {
        final String prefix = key + "=";
        for (final String line : output.split("\\r?\\n")) {
            if (line.startsWith(prefix)) {
                final String value = line.substring(prefix.length()).trim();
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }

    private static String shellQuote(final String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    private static void showMagicDeskInternal() {
        int displayId = getActiveConsoleDisplayId();
        boolean startedConsoleMode = false;
        if (displayId <= 0) {
            final int externalDisplayId = getExternalDisplayId();
            if (externalDisplayId <= 0) {
                Log.w(TAG, "cannot start Console mode: no physical external display");
                CompatibilityDiagnostics.record(
                        "NUBIA-CONSOLE-001",
                        "Cannot start Console mode",
                        "No physical external display was reported");
                return;
            }
            Log.i(TAG, "request Console mode on physical display=" + externalDisplayId);
            if (!requestConsoleMode(externalDisplayId)) {
                return;
            }
            displayId = waitForConsoleDisplay();
            if (displayId <= 0) {
                Log.w(TAG, "Console mode did not create an app mirror display");
                CompatibilityDiagnostics.record(
                        "NUBIA-CONSOLE-002",
                        "Console mode did not start",
                        "The firmware did not create app_mirror_displayid within "
                                + CONSOLE_START_TIMEOUT_MS + " ms");
                return;
            }
            startedConsoleMode = true;
        }

        ensureLandscapeDisplay(displayId);
        final boolean desktopReady = MainActivity.isDesktopReadyOnDisplay(displayId);
        if (!startedConsoleMode && !desktopReady) {
            setExternalTaskCaptionsEnabledInternal(true);
        }
        final Boolean visibleTaskSnapshot =
                DesktopTaskController.hasVisibleAppTaskSnapshot(displayId);
        final boolean restoreWindows = !(visibleTaskSnapshot != null
                ? visibleTaskSnapshot.booleanValue() : hasVisibleAppTask(displayId));
        if (!desktopReady && !startedConsoleMode) {
            final String preparedTask = runRootCommand(
                    appProcessCommand(TASK_CONTROL_COMMAND)
                            + " prepare-desktop " + displayId).trim();
            Log.i(TAG, "prepared MagicDesk task: " + preparedTask.replace('\n', ' '));
        }
        Log.i(TAG, "show MagicDesk display=" + displayId
                + " restoreWindows=" + restoreWindows
                + " cachedVisibility=" + (visibleTaskSnapshot != null)
                + " desktopReady=" + desktopReady);
        final String launchTaskFlags = startedConsoleMode
                ? " -f 0x18000000"
                : " --activity-reorder-to-front --activity-single-top";
        final String launchOutput = runRootCommand(AM + " start -W --display " + displayId
                + " --windowingMode 1"
                + " --activityType 2"
                + launchTaskFlags
                + " -a android.intent.action.MAIN"
                + " -c android.intent.category.LAUNCHER"
                + (restoreWindows
                        ? " --es " + MainActivity.EXTRA_ACTION + " "
                                + MainActivity.ACTION_RESTORE_WINDOWS
                        : "")
                + " -n io.github.mekhontsev.magicdesk/.DesktopActivity").trim();
        Log.i(TAG, "MagicDesk launch output=" + launchOutput.replace('\n', ' '));
        if (startedConsoleMode) {
            if (!waitForDesktopReady(displayId)) {
                Log.w(TAG, "new Console desktop task did not become ready display="
                        + displayId);
                return;
            }
            refreshOrOpenTouchpad();
        }
    }

    private static boolean waitForDesktopReady(final int displayId) {
        final long deadline = SystemClock.uptimeMillis() + CONSOLE_START_TIMEOUT_MS;
        while (SystemClock.uptimeMillis() < deadline) {
            if (!displayExists(displayId)) {
                return false;
            }
            final String output = runRootCommand(
                    appProcessCommand(TASK_CONTROL_COMMAND)
                            + " has-desktop-home " + displayId).trim();
            if (output.contains("desktop-home-task=true")) {
                Log.i(TAG, "Console desktop task ready display=" + displayId);
                return true;
            }
            if (!output.contains("desktop-home-task=false")) {
                Log.w(TAG, "cannot query Console desktop task output=" + output);
            }
            SystemClock.sleep(CONSOLE_STATE_POLL_MS);
        }
        return false;
    }

    private static boolean hasVisibleAppTask(final int displayId) {
        final String output = runRootCommand(
                appProcessCommand(TASK_CONTROL_COMMAND)
                        + " has-visible-app " + displayId).trim();
        if (output.contains("visible-app-task=true")) {
            return true;
        }
        if (!output.contains("visible-app-task=false")) {
            Log.w(TAG, "cannot query visible app task output=" + output);
            return true;
        }
        return false;
    }

    private static boolean setExternalTaskCaptionsEnabledInternal(final boolean enabled) {
        final String operation = enabled ? "enable-captions" : "restore-privacy";
        final String output = runRootCommand(
                appProcessCommand(SURFACE_FLINGER_OPTION_COMMAND)
                        + " " + operation).trim();
        final String expected = "external-task-captions="
                + (enabled ? "enabled" : "restored");
        final boolean success = output.contains(expected);
        if (success) {
            Log.i(TAG, output.replace('\n', ' '));
        } else {
            Log.w(TAG, "cannot update external task caption policy output=" + output);
        }
        return success;
    }

    private static void ensureLandscapeDisplay(final int displayId) {
        final int[] size = getDisplaySize(displayId);
        if (size == null) {
            Log.w(TAG, "cannot read Console display size display=" + displayId);
            return;
        }
        if (size[0] >= size[1]) {
            Log.i(TAG, "Console display is landscape display=" + displayId
                    + " size=" + size[0] + "x" + size[1]);
            return;
        }

        final int targetWidth = size[1];
        final int targetHeight = size[0];
        Log.i(TAG, "force Console display landscape display=" + displayId
                + " size=" + size[0] + "x" + size[1]
                + " target=" + targetWidth + "x" + targetHeight);
        runRootCommand(WM + " size " + targetWidth + "x" + targetHeight
                + " -d " + displayId);

        final long deadline = SystemClock.uptimeMillis() + LANDSCAPE_APPLY_TIMEOUT_MS;
        while (SystemClock.uptimeMillis() < deadline) {
            final int[] currentSize = getDisplaySize(displayId);
            if (currentSize != null
                    && currentSize[0] == targetWidth
                    && currentSize[1] == targetHeight) {
                Log.i(TAG, "Console display landscape applied display=" + displayId);
                return;
            }
            SystemClock.sleep(CONSOLE_STATE_POLL_MS);
        }
        Log.w(TAG, "Console display landscape was not applied display=" + displayId
                + " target=" + targetWidth + "x" + targetHeight);
        CompatibilityDiagnostics.record(
                "NUBIA-DISPLAY-001",
                "Console display stayed in the wrong orientation",
                "display=" + displayId + " target="
                        + targetWidth + "x" + targetHeight);
    }

    private static int[] getDisplaySize(final int displayId) {
        final String output = runRootCommand(DISPLAY + " get-displays");
        for (final String line : output.split("\\r?\\n")) {
            final Matcher matcher = DISPLAY_REAL_SIZE_PATTERN.matcher(line);
            if (!matcher.find()) {
                continue;
            }
            try {
                if (Integer.parseInt(matcher.group(1)) == displayId) {
                    return new int[] {
                            Integer.parseInt(matcher.group(2)),
                            Integer.parseInt(matcher.group(3))
                    };
                }
            } catch (NumberFormatException ignored) {
                // Continue past malformed vendor diagnostics.
            }
        }
        return null;
    }

    private static boolean requestConsoleMode(final int externalDisplayId) {
        final String output = runRootCommand(appProcessCommand(CONSOLE_DISPLAY_COMMAND)
                + " expand " + externalDisplayId).trim();
        if (!output.contains("display-command=expand")) {
            Log.w(TAG, "Console mode request failed output=" + output);
            CompatibilityDiagnostics.record(
                    "NUBIA-CONSOLE-003",
                    "The firmware rejected the Console mode request",
                    output);
            return false;
        }
        return true;
    }

    private static boolean requestTouchpad() {
        if (!setMirrorInputProxyEnabledInternal(true)) {
            Log.w(TAG, "cannot open Nubia touchpad: input proxy could not be enabled");
            return false;
        }
        runRootCommand(PM + " enable --user 0 " + NUBIA_MIRROR_INPUT_ACTIVITY);
        runRootCommand(nubiaTouchpadServiceCommand("close_touch_panel"));
        if (!waitForTouchpadActivity(false)) {
            Log.w(TAG, "Nubia touchpad did not close before restart");
            return false;
        }
        final String output = runRootCommand(appProcessCommand(CONSOLE_DISPLAY_COMMAND)
                + " touchpad 0").trim();
        if (!output.contains("display-command=touchpad")) {
            Log.w(TAG, "Nubia touchpad command failed output=" + output);
            return false;
        }
        if (!waitForTouchpadActivity(true)) {
            Log.w(TAG, "Nubia touchpad activity did not appear");
            return false;
        }
        Log.i(TAG, "Nubia touchpad opened on phone display");
        return true;
    }

    private static boolean refreshOrOpenTouchpad() {
        if (!setMirrorInputProxyEnabledInternal(true)) {
            Log.w(TAG, "cannot refresh Nubia touchpad: input proxy could not be enabled");
            return false;
        }
        runRootCommand(PM + " enable --user 0 " + NUBIA_MIRROR_INPUT_ACTIVITY);
        if (!isTouchpadActivityPresent()) {
            return requestTouchpad();
        }
        final String output = runRootCommand(appProcessCommand(CONSOLE_DISPLAY_COMMAND)
                + " touchpad 0").trim();
        final boolean touchpadReady = output.contains("display-command=touchpad");
        final String viewportOutput = runRootCommand(
                appProcessCommand(MOUSE_VIEWPORT_COMMAND)).trim();
        final boolean viewportUpdated = viewportOutput.contains("mouse-viewport=updated");
        final boolean success = touchpadReady && viewportUpdated;
        if (success) {
            Log.i(TAG, "Nubia touchpad retained with refreshed viewport after Console startup");
        } else {
            Log.w(TAG, "Nubia touchpad refresh failed touchpadOutput=" + output
                    + " viewportOutput=" + viewportOutput);
        }
        return success;
    }

    private static boolean setMirrorInputProxyEnabledInternal(final boolean enabled) {
        if (sMirrorInputProxyEnabled != null
                && sMirrorInputProxyEnabled.booleanValue() == enabled) {
            return true;
        }
        if (!enabled) {
            runRootCommand(nubiaTouchpadServiceCommand("close_input_panel"));
        }
        final String output = runRootCommand(
                appProcessCommand(CONSOLE_CONTROL_COMMAND)
                        + " mirror-input-service " + enabled).trim();
        final boolean success = output.contains("mirror-input-service=" + enabled);
        if (!success) {
            Log.w(TAG, "cannot set Nubia mirror input service enabled=" + enabled
                    + " output=" + output);
            return false;
        }
        sMirrorInputProxyEnabled = Boolean.valueOf(enabled);
        if (!enabled) {
            runRootCommand(AM + " stop-service --user 0 -n "
                    + NUBIA_MIRROR_INPUT_SERVICE + " || true");
        }
        Log.i(TAG, "Nubia mirror input proxy enabled=" + enabled);
        return true;
    }

    private static String nubiaTouchpadServiceCommand(final String reason) {
        return AM + " start-service --user 0"
                + " -a " + NUBIA_MIRROR_INPUT_ACTION
                + " -n " + NUBIA_MIRROR_INPUT_SERVICE
                + " --es reason " + reason;
    }

    private static boolean waitForTouchpadActivity(final boolean expectedPresent) {
        final long deadline = SystemClock.uptimeMillis() + TOUCHPAD_TRANSITION_TIMEOUT_MS;
        do {
            if (isTouchpadActivityPresent() == expectedPresent) {
                return true;
            }
            SystemClock.sleep(CONSOLE_STATE_POLL_MS);
        } while (SystemClock.uptimeMillis() < deadline);
        return false;
    }

    private static boolean isTouchpadActivityPresent() {
        final String output = runRootCommand(
                "/system/bin/dumpsys activity activities"
                        + " | /system/bin/grep -F -m 1 "
                        + shellQuote(NUBIA_MIRROR_INPUT_ACTIVITY));
        return output.contains(NUBIA_MIRROR_INPUT_ACTIVITY);
    }

    private static int waitForConsoleDisplay() {
        final long deadline = SystemClock.uptimeMillis() + CONSOLE_START_TIMEOUT_MS;
        while (SystemClock.uptimeMillis() < deadline) {
            final int displayId = getActiveConsoleDisplayId();
            if (displayId > 0) {
                return displayId;
            }
            SystemClock.sleep(CONSOLE_STATE_POLL_MS);
        }
        return -1;
    }

    private static boolean waitForConsoleStop() {
        final long deadline = SystemClock.uptimeMillis() + CONSOLE_START_TIMEOUT_MS;
        while (SystemClock.uptimeMillis() < deadline) {
            if (getActiveConsoleDisplayId() <= 0) {
                return true;
            }
            SystemClock.sleep(CONSOLE_STATE_POLL_MS);
        }
        return false;
    }

    private static int getActiveConsoleDisplayId() {
        final int displayId = getMirrorDisplayId();
        return displayId > 0 && displayExists(displayId) ? displayId : -1;
    }

    private static boolean displayExists(final int displayId) {
        final String output = runRootCommand(DISPLAY + " get-displays --ids-only");
        for (final String line : output.split("\\r?\\n")) {
            if (line.trim().equals(Integer.toString(displayId))) {
                return true;
            }
        }
        return false;
    }

    private static int getExternalDisplayId() {
        final String output = runRootCommand(
                DISPLAY + " get-displays --ids-only --type external");
        for (final String line : output.split("\\r?\\n")) {
            try {
                final int displayId = Integer.parseInt(line.trim());
                if (displayId > 0) {
                    return displayId;
                }
            } catch (NumberFormatException ignored) {
                // Continue past command diagnostics and unsupported display entries.
            }
        }
        return -1;
    }

    private static void captureScreenshotInternal() {
        final String physicalDisplayId = getExternalPhysicalDisplayId();
        final String fileName = "MagicDesk_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
                        .format(new Date())
                + ".png";
        final String path = SCREENSHOT_DIRECTORY + "/" + fileName;
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
        final String output = runRootCommand(command).trim();
        if (output.contains("screenshot-saved=" + path)) {
            Log.i(TAG, "screenshot saved path=" + path
                    + " physicalDisplay=" + physicalDisplayId);
        } else {
            Log.w(TAG, "screenshot failed path=" + path
                    + " physicalDisplay=" + physicalDisplayId
                    + " output=" + output.replace('\n', ' '));
        }
    }

    private static String getExternalPhysicalDisplayId() {
        final String output = runRootCommand(DISPLAY + " get-displays --type external");
        final Matcher matcher = EXTERNAL_PHYSICAL_DISPLAY_PATTERN.matcher(output);
        return matcher.find() ? matcher.group(1) : null;
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

    private static int getMirrorDisplayId() {
        final String output = runRootCommand(SETTINGS + " get global app_mirror_displayid");
        try {
            return Integer.parseInt(output.trim());
        } catch (NumberFormatException e) {
            Log.w(TAG, "bad app_mirror_displayid: " + output);
            return -1;
        }
    }

    private static String runRootCommand(final String command) {
        return ROOT_SHELL.run(command);
    }

    private static final class RootShell {
        private Process mProcess;
        private BufferedReader mReader;
        private BufferedWriter mWriter;
        private int mCommandId;

        synchronized String run(final String command) {
            if (!ensureStarted()) {
                return "";
            }

            final String marker = "__MAGICDESK_EXIT_" + (++mCommandId) + "__";
            final StringBuilder output = new StringBuilder();
            try {
                mWriter.write(command);
                mWriter.newLine();
                mWriter.write("echo " + marker + "$?");
                mWriter.newLine();
                mWriter.flush();

                String line;
                while ((line = mReader.readLine()) != null) {
                    if (line.startsWith(marker)) {
                        final String exitCodeText = line.substring(marker.length()).trim();
                        if (!"0".equals(exitCodeText)) {
                            Log.w(TAG, "root command failed code=" + exitCodeText
                                    + " cmd=" + command + " output=" + output);
                            CompatibilityDiagnostics.record(
                                    "ROOT-COMMAND-001",
                                    "A MagicDesk root command failed",
                                    "exit=" + exitCodeText + " command=" + command
                                            + " output=" + output);
                        }
                        return output.toString();
                    }
                    output.append(line).append('\n');
                }
                Log.w(TAG, "root shell closed cmd=" + command + " output=" + output);
                CompatibilityDiagnostics.record(
                        "ROOT-SHELL-002",
                        "Root shell closed unexpectedly",
                        "command=" + command + " output=" + output);
            } catch (IOException e) {
                Log.w(TAG, "root shell io error cmd=" + command + " output=" + output, e);
                CompatibilityDiagnostics.record(
                        "ROOT-SHELL-003",
                        "Root shell communication failed",
                        "command=" + command + " output=" + output,
                        e);
            }

            close();
            return output.toString();
        }

        private boolean ensureStarted() {
            if (mProcess != null && mProcess.isAlive() && mReader != null && mWriter != null) {
                return true;
            }

            close();
            try {
                mProcess = new ProcessBuilder("su").redirectErrorStream(true).start();
                mReader = new BufferedReader(new InputStreamReader(mProcess.getInputStream()));
                mWriter = new BufferedWriter(new OutputStreamWriter(mProcess.getOutputStream()));
                Log.i(TAG, "root shell started");
                return true;
            } catch (IOException e) {
                Log.w(TAG, "failed to start root shell", e);
                CompatibilityDiagnostics.record(
                        "ROOT-SHELL-001",
                        "Root shell is unavailable",
                        "",
                        e);
                close();
                return false;
            }
        }

        synchronized void close() {
            closeQuietly(mWriter);
            closeQuietly(mReader);
            if (mProcess != null) {
                mProcess.destroy();
            }
            mWriter = null;
            mReader = null;
            mProcess = null;
        }

        private void closeQuietly(final java.io.Closeable closeable) {
            if (closeable == null) {
                return;
            }
            try {
                closeable.close();
            } catch (IOException e) {
                // Ignore close failures while recovering the shell.
            }
        }
    }
}
