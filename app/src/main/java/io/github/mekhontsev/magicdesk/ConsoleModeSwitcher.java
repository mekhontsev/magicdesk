package io.github.mekhontsev.magicdesk;

import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
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
    private static final String WM = "/system/bin/wm";
    private static final String CONSOLE_CONTROL_COMMAND =
            "io.github.mekhontsev.magicdesk.ConsoleControlCommand";
    private static final String CONSOLE_DISPLAY_COMMAND =
            "io.github.mekhontsev.magicdesk.ConsoleDisplayCommand";
    private static final String CONSOLE_TASK_RETURN_COMMAND =
            "io.github.mekhontsev.magicdesk.ConsoleTaskReturnCommand";
    private static final String DEVICE_LOCK_COMMAND =
            "io.github.mekhontsev.magicdesk.DeviceLockCommand";
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
    private static final Pattern DESKTOP_HOME_TASK_ID_PATTERN =
            Pattern.compile("desktop-home-task-id=(\\d+)");
    private static final long SHORTCUT_DEBOUNCE_MS = 300L;
    private static final long CONSOLE_START_TIMEOUT_MS = 10_000L;
    private static final long LANDSCAPE_APPLY_TIMEOUT_MS = 2_000L;
    private static final long CONSOLE_STATE_POLL_MS = 100L;
    private static final RootShell ROOT_SHELL = new RootShell();
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
                        showMagicDeskWithShizuku(knownConsoleDisplayId);
                    } else {
                        showMagicDeskInternal();
                    }
                } finally {
                    DESKTOP_START_IN_PROGRESS.set(false);
                    closeRootShell();
                }
            }
        });
    }

    private static void showMagicDeskWithShizuku(final int displayId) {
        if (displayId <= 0) {
            Log.w(TAG, "cannot show MagicDesk with Shizuku: Console Mode is inactive");
            CompatibilityDiagnostics.record(
                    "SHIZUKU-CONSOLE-001",
                    "Cannot open MagicDesk on the external display",
                    "Start Nubia Console Mode before using the MagicDesk notification");
            return;
        }
        try {
            ensureLandscapeDisplayWithShizuku(displayId);
            final Boolean visibleTaskSnapshot =
                    DesktopTaskController.hasVisibleAppTaskSnapshot(displayId);
            final boolean restoreWindows =
                    visibleTaskSnapshot != null
                            && !visibleTaskSnapshot.booleanValue();
            final int desktopTaskId = findDesktopHomeTaskWithShizuku(displayId);
            if (desktopTaskId >= 0) {
                final String focusOutput = PrivilegedCommandRunner.run(
                        AM + " task focus " + desktopTaskId).trim();
                Log.i(TAG, "Shizuku MagicDesk focus task=" + desktopTaskId
                        + " output=" + focusOutput.replace('\n', ' '));
                if (restoreWindows) {
                    MainActivity.restoreLastVisibleWindowsIfRunning();
                }
                return;
            }
            final String output = PrivilegedCommandRunner.run(
                    AM + " start -W --display " + displayId
                            + " --windowingMode 1"
                            + " --activityType 2"
                            + " -f 0x18000000"
                            + " -a android.intent.action.MAIN"
                            + " -c android.intent.category.LAUNCHER"
                            + (restoreWindows
                                    ? " --es " + MainActivity.EXTRA_ACTION + " "
                                            + MainActivity.ACTION_RESTORE_WINDOWS
                                    : "")
                            + " -n io.github.mekhontsev.magicdesk/.DeviceSetupActivity")
                    .trim();
            if (output.startsWith("Error:")
                    || output.contains("Exception occurred while executing")) {
                throw new IOException(output);
            }
            Log.i(TAG, "Shizuku MagicDesk launch display=" + displayId
                    + " output=" + output.replace('\n', ' '));
        } catch (IOException error) {
            Log.w(TAG, "Shizuku MagicDesk launch failed", error);
            CompatibilityDiagnostics.record(
                    "SHIZUKU-CONSOLE-002",
                    "Could not open MagicDesk on the Console display",
                    error.getMessage());
        }
    }

    private static int findDesktopHomeTaskWithShizuku(final int displayId)
            throws IOException {
        final String output = PrivilegedCommandRunner.run(
                appProcessCommand(TASK_CONTROL_COMMAND)
                        + " desktop-home-task-id " + displayId);
        final Matcher matcher = DESKTOP_HOME_TASK_ID_PATTERN.matcher(output);
        if (!matcher.find()) {
            throw new IOException(
                    "could not query MagicDesk HOME task: " + output.trim());
        }
        return Integer.parseInt(matcher.group(1));
    }

    private static void ensureLandscapeDisplayWithShizuku(final int displayId)
            throws IOException {
        final String output = PrivilegedCommandRunner.run(
                WM + " size -d " + displayId);
        final Matcher matcher = Pattern.compile(
                "(?:Physical|Override) size: (\\d+)x(\\d+)")
                .matcher(output);
        int width = -1;
        int height = -1;
        while (matcher.find()) {
            width = Integer.parseInt(matcher.group(1));
            height = Integer.parseInt(matcher.group(2));
        }
        if (width <= 0 || height <= 0) {
            throw new IOException("could not read Console display size: "
                    + output.trim());
        }
        if (width < height) {
            PrivilegedCommandRunner.run(
                    WM + " size " + height + "x" + width
                            + " -d " + displayId);
        }
        PrivilegedCommandRunner.run(
                WM + " fixed-to-user-rotation -d " + displayId
                        + " enabled");
        PrivilegedCommandRunner.run(
                WM + " user-rotation -d " + displayId + " lock 0");
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
        final boolean desktopTaskReady =
                desktopReady && hasDesktopHomeTask(displayId);
        if (!startedConsoleMode && !desktopTaskReady) {
            setExternalTaskCaptionsEnabledInternal(true);
        }
        final Boolean visibleTaskSnapshot =
                DesktopTaskController.hasVisibleAppTaskSnapshot(displayId);
        final boolean restoreWindows = !(visibleTaskSnapshot != null
                ? visibleTaskSnapshot.booleanValue() : hasVisibleAppTask(displayId));
        if (!desktopTaskReady && !startedConsoleMode) {
            final String preparedTask = runRootCommand(
                    appProcessCommand(TASK_CONTROL_COMMAND)
                            + " prepare-desktop " + displayId).trim();
            Log.i(TAG, "prepared MagicDesk task: " + preparedTask.replace('\n', ' '));
        }
        Log.i(TAG, "show MagicDesk display=" + displayId
                + " restoreWindows=" + restoreWindows
                + " cachedVisibility=" + (visibleTaskSnapshot != null)
                + " desktopReady=" + desktopReady
                + " desktopTaskReady=" + desktopTaskReady);
        final boolean newDesktopTask = !desktopTaskReady;
        final String launchTaskFlags = newDesktopTask
                ? " -f 0x18000000"
                : " --activity-reorder-to-front --activity-single-top";
        // Keep Nubia's automatically migrated MagicDesk task alive while
        // bootstrapping the dedicated HOME task. It grants the root launch
        // access to the private Console display; the new desktop instance
        // removes the temporary standard task after it becomes ready.
        final String launchComponent = newDesktopTask
                ? "io.github.mekhontsev.magicdesk/.DeviceSetupActivity"
                : "io.github.mekhontsev.magicdesk/.DesktopActivity";
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
                + " -n " + launchComponent).trim();
        Log.i(TAG, "MagicDesk launch output=" + launchOutput.replace('\n', ' '));
        if (startedConsoleMode) {
            if (!waitForDesktopReady(displayId)) {
                Log.w(TAG, "new Console desktop task did not become ready display="
                        + displayId);
                return;
            }
            NubiaTouchpadController.refreshOrOpen();
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

    private static boolean hasDesktopHomeTask(final int displayId) {
        final String output = runRootCommand(
                appProcessCommand(TASK_CONTROL_COMMAND)
                        + " has-desktop-home " + displayId).trim();
        if (output.contains("desktop-home-task=true")) {
            return true;
        }
        if (!output.contains("desktop-home-task=false")) {
            Log.w(TAG, "cannot query Console desktop task output=" + output);
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

    static int getActiveConsoleDisplayId() {
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
        String path = null;
        try {
            final String physicalDisplayId = getExternalPhysicalDisplayId();
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

    private static String getExternalPhysicalDisplayId() throws IOException {
        final String output = PrivilegedCommandRunner.run(
                DISPLAY + " get-displays --type external");
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

    static String runRootCommand(final String command) {
        return ROOT_SHELL.run(command);
    }

    private static final class RootShell {
        private Process mProcess;
        private BufferedReader mReader;
        private BufferedWriter mWriter;
        private int mCommandId;

        synchronized String run(final String command) {
            if (!RuntimeAccess.allowsRootCommands()) {
                Log.d(TAG, "skip root command for backend="
                        + RuntimeAccess.backendName());
                return "";
            }
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
            if (!RuntimeAccess.allowsRootCommands()) {
                return false;
            }
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
