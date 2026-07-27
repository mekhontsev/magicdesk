package io.github.mekhontsev.magicdesk;

import android.util.Log;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;

final class RootKeyboardShortcutWatcher {
    private static final String TAG = "MagicDeskRootKeys";
    private static final String INPUT_BRIDGE_COMMAND =
            "io.github.mekhontsev.magicdesk.ConsoleInputBridgeCommand";
    private static final long RESTART_DELAY_MS = 1000L;

    private static final Object LOCK = new Object();
    private static boolean sRunning;
    private static boolean sCtrlDown;
    private static boolean sAltDown;
    private static boolean sAltTabActive;
    private static boolean sShiftDown;
    private static boolean sMetaDown;
    private static Process sProcess;
    private static Thread sThread;
    private static long sGeneration;

    private RootKeyboardShortcutWatcher() {
    }

    static void start(final boolean consoleMode) {
        final long generation;
        synchronized (LOCK) {
            if (sRunning) {
                return;
            }
            sRunning = true;
            generation = ++sGeneration;
            sThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    runLoop(consoleMode, generation);
                }
            }, "MagicDeskRootKeyWatcher");
            sThread.setDaemon(true);
            sThread.start();
        }
    }

    static void stop() {
        final Process process;
        final Thread thread;
        final boolean cancelAltTab;
        synchronized (LOCK) {
            sRunning = false;
            sGeneration++;
            cancelAltTab = sAltTabActive;
            clearModifierStateLocked();
            process = sProcess;
            thread = sThread;
            sProcess = null;
            sThread = null;
        }
        if (cancelAltTab) {
            ConsoleModeSwitcher.cancelAltTab();
        }
        if (process != null) {
            closeQuietly(process.getOutputStream());
        }
        if (thread != null) {
            thread.interrupt();
        }
    }

    static boolean isRunning() {
        synchronized (LOCK) {
            return sRunning && isProcessAlive(sProcess);
        }
    }

    private static boolean isProcessAlive(final Process process) {
        if (process == null) {
            return false;
        }
        try {
            process.exitValue();
            return false;
        } catch (IllegalThreadStateException e) {
            return true;
        }
    }

    private static void runLoop(final boolean consoleMode, final long generation) {
        while (isRunning(generation)) {
            Process process = null;
            BufferedReader reader = null;
            try {
                final String mode = consoleMode ? " console" : " shortcuts";
                final String command = "APK=$(/system/bin/pm path io.github.mekhontsev.magicdesk "
                        + "| /system/bin/cut -d: -f2- | /system/bin/head -n 1); "
                        + "export CLASSPATH=\"$APK\"; "
                        + "exec /system/bin/app_process / " + INPUT_BRIDGE_COMMAND + mode;
                process = PrivilegedCommandRunner.start(command);
                setProcess(process, generation);
                Log.i(TAG, "root input bridge started console=" + consoleMode);

                reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;
                while (isRunning(generation) && (line = reader.readLine()) != null) {
                    handleGeteventLine(line);
                }
            } catch (IOException e) {
                if (isRunning(generation)) {
                    Log.w(TAG, "root input bridge failed", e);
                    CompatibilityDiagnostics.record(
                            "INPUT-BRIDGE-001",
                            "The root keyboard and mouse bridge stopped",
                            "consoleMode=" + consoleMode,
                            e);
                }
            } finally {
                closeQuietly(reader);
                if (process != null) {
                    process.destroy();
                }
                clearProcess(process);
                clearModifierState();
            }

            if (isRunning(generation)) {
                sleepBeforeRestart();
            }
        }
        Log.i(TAG, "root input bridge stopped");
    }

    private static void handleGeteventLine(final String line) {
        if (line.startsWith("MAGICDESK_ALT_TAB_ADVANCE ")) {
            final boolean reverse = line.endsWith("reverse");
            synchronized (LOCK) {
                sAltTabActive = true;
            }
            Log.i(TAG, reverse
                    ? "root Alt+Shift+Tab"
                    : "root Alt+Tab");
            ConsoleModeSwitcher.advanceAltTab(reverse);
            return;
        }
        if ("MAGICDESK_ALT_TAB_COMMIT".equals(line)) {
            finishAltTabIfActive();
            return;
        }
        if (line.indexOf(" EV_KEY ") < 0) {
            return;
        }

        final String keyName = parseKeyName(line);
        if (keyName == null) {
            return;
        }

        final int action = parseKeyAction(line);
        if (action < 0) {
            return;
        }
        if (action == 2) {
            return;
        }

        if (isMetaKey(keyName)) {
            setMetaDown(action == 1);
            return;
        }
        if (isCtrlKey(keyName)) {
            setCtrlDown(action == 1);
            return;
        }
        if (isAltKey(keyName)) {
            synchronized (LOCK) {
                sAltDown = action == 1;
            }
            if (action == 0) {
                finishAltTabIfActive();
            }
            return;
        }
        if (isShiftKey(keyName)) {
            setShiftDown(action == 1);
            return;
        }

        if (action != 1) {
            return;
        }

        if ("KEY_ESC".equals(keyName) && isNoModifierDown()) {
            DesktopTaskController.dismissTransientActivity();
            return;
        }

        if ("KEY_SPACE".equals(keyName) && isCtrlOnlyDown()) {
            Log.i(TAG, "root Ctrl+Space");
            ConsoleModeSwitcher.toggleHardwareKeyboardLayout();
            return;
        }

        if ("KEY_F4".equals(keyName) && isAltOnlyDown()) {
            Log.i(TAG, "root Alt+F4");
            ConsoleModeSwitcher.manageActiveWindow(
                    DesktopTaskController.SHORTCUT_CLOSE);
            return;
        }

        if (isMetaOnlyDown()) {
            if ("KEY_BACKSPACE".equals(keyName)) {
                Log.i(TAG, "root Meta+Backspace");
                ConsoleModeSwitcher.sendSystemBack();
                return;
            }
            if ("KEY_L".equals(keyName)) {
                Log.i(TAG, "root Meta+L");
                ConsoleModeSwitcher.lockDevice();
                return;
            }
            if ("KEY_N".equals(keyName)) {
                Log.i(TAG, "root Meta+N");
                ConsoleModeSwitcher.toggleNotificationCenter();
                return;
            }
            if ("KEY_UP".equals(keyName)) {
                Log.i(TAG, "root Meta+Up");
                ConsoleModeSwitcher.manageActiveWindow(
                        DesktopTaskController.SHORTCUT_FULLSCREEN);
                return;
            }
            if ("KEY_DOWN".equals(keyName)) {
                Log.i(TAG, "root Meta+Down");
                ConsoleModeSwitcher.manageActiveWindow(
                        DesktopTaskController.SHORTCUT_RESTORE);
                return;
            }
            if ("KEY_LEFT".equals(keyName)) {
                Log.i(TAG, "root Meta+Left");
                ConsoleModeSwitcher.manageActiveWindow(
                        DesktopTaskController.SHORTCUT_SNAP_LEFT);
                return;
            }
            if ("KEY_RIGHT".equals(keyName)) {
                Log.i(TAG, "root Meta+Right");
                ConsoleModeSwitcher.manageActiveWindow(
                        DesktopTaskController.SHORTCUT_SNAP_RIGHT);
                return;
            }
            if ("KEY_D".equals(keyName)) {
                Log.i(TAG, "root Meta+D");
                ConsoleModeSwitcher.showMagicDesk();
                return;
            }
            if (isPrintScreenKey(keyName)) {
                Log.i(TAG, "root Meta+PrintScreen");
                ConsoleModeSwitcher.captureScreenshot();
                return;
            }
            if ("KEY_SLASH".equals(keyName)) {
                Log.i(TAG, "root Meta+Slash");
                ConsoleModeSwitcher.showShortcutHelp();
            }
            return;
        }

    }

    private static String parseKeyName(final String line) {
        final String[] parts = line.trim().split("\\s+");
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].startsWith("KEY_")) {
                return parts[i];
            }
        }
        return null;
    }

    private static int parseKeyAction(final String line) {
        if (line.endsWith(" DOWN") || line.indexOf(" DOWN") >= 0) {
            return 1;
        }
        if (line.endsWith(" UP") || line.indexOf(" UP") >= 0) {
            return 0;
        }
        if (line.endsWith(" REPEAT") || line.indexOf(" REPEAT") >= 0) {
            return 2;
        }
        return -1;
    }

    private static boolean isCtrlKey(final String keyName) {
        return "KEY_LEFTCTRL".equals(keyName) || "KEY_RIGHTCTRL".equals(keyName);
    }

    private static boolean isAltKey(final String keyName) {
        return "KEY_LEFTALT".equals(keyName) || "KEY_RIGHTALT".equals(keyName);
    }

    private static boolean isShiftKey(final String keyName) {
        return "KEY_LEFTSHIFT".equals(keyName) || "KEY_RIGHTSHIFT".equals(keyName);
    }

    private static boolean isMetaKey(final String keyName) {
        return "KEY_LEFTMETA".equals(keyName) || "KEY_RIGHTMETA".equals(keyName);
    }

    private static boolean isPrintScreenKey(final String keyName) {
        return "KEY_SYSRQ".equals(keyName) || "KEY_PRINT".equals(keyName);
    }

    private static boolean isCtrlOnlyDown() {
        synchronized (LOCK) {
            return sCtrlDown && !sAltDown && !sShiftDown;
        }
    }

    private static boolean isAltOnlyDown() {
        synchronized (LOCK) {
            return sAltDown && !sCtrlDown && !sShiftDown && !sMetaDown;
        }
    }

    private static boolean isMetaOnlyDown() {
        synchronized (LOCK) {
            return sMetaDown && !sCtrlDown && !sAltDown && !sShiftDown;
        }
    }

    private static boolean isNoModifierDown() {
        synchronized (LOCK) {
            return !sCtrlDown && !sAltDown && !sShiftDown && !sMetaDown;
        }
    }

    private static void setMetaDown(final boolean down) {
        synchronized (LOCK) {
            sMetaDown = down;
        }
    }

    private static void setCtrlDown(final boolean down) {
        synchronized (LOCK) {
            sCtrlDown = down;
        }
    }

    private static void setShiftDown(final boolean down) {
        synchronized (LOCK) {
            sShiftDown = down;
        }
    }

    private static void finishAltTabIfActive() {
        final boolean finishAltTab;
        synchronized (LOCK) {
            finishAltTab = sAltTabActive;
            sAltTabActive = false;
        }
        if (finishAltTab) {
            Log.i(TAG, "root Alt+Tab commit");
            ConsoleModeSwitcher.finishAltTab();
        }
    }

    private static void clearModifierState() {
        final boolean cancelAltTab;
        synchronized (LOCK) {
            cancelAltTab = sAltTabActive;
            clearModifierStateLocked();
        }
        if (cancelAltTab) {
            ConsoleModeSwitcher.cancelAltTab();
        }
    }

    private static void clearModifierStateLocked() {
        sCtrlDown = false;
        sAltDown = false;
        sAltTabActive = false;
        sShiftDown = false;
        sMetaDown = false;
    }

    private static boolean isRunning(final long generation) {
        synchronized (LOCK) {
            return sRunning && sGeneration == generation;
        }
    }

    private static void setProcess(final Process process, final long generation) {
        synchronized (LOCK) {
            if (sRunning && sGeneration == generation) {
                sProcess = process;
            }
        }
    }

    private static void clearProcess(final Process process) {
        synchronized (LOCK) {
            if (sProcess == process) {
                sProcess = null;
            }
        }
    }

    private static void sleepBeforeRestart() {
        try {
            Thread.sleep(RESTART_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(final Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException e) {
            // Ignore close failures while stopping or restarting getevent.
        }
    }
}
