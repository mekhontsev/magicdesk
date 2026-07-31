package io.github.mekhontsev.magicdesk;

import android.util.Log;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

final class KeyboardShortcutWatcher {
    private static final String TAG = "MagicDeskKeys";
    private static final String INPUT_BRIDGE_COMMAND =
            "io.github.mekhontsev.magicdesk.ConsoleInputBridgeCommand";
    private static final String SHIZUKU_ROUTING_COMMAND =
            "io.github.mekhontsev.magicdesk.ShizukuInputRoutingCommand";
    private static final String SHIZUKU_INPUT_COMMAND =
            "/system/bin/getevent -lt";
    private static final String SHIZUKU_KEYBOARD_HELPER =
            "libmagicdesk_keyboard_bridge.so";
    private static final String DUMPSYS_INPUT =
            "/system/bin/dumpsys input";
    private static final long RESTART_DELAY_MS = 1000L;
    private static final long HEARTBEAT_MILLIS = 1000L;

    private static final Object LOCK = new Object();
    private static boolean sRunning;
    private static boolean sCtrlDown;
    private static boolean sAltDown;
    private static boolean sAltTabActive;
    private static boolean sShiftDown;
    private static boolean sMetaDown;
    private static Process sProcess;
    private static ShizukuAccess.StreamHandle sShizukuStream;
    private static ShizukuAccess.StreamHandle sShizukuRoutingStream;
    private static Thread sThread;
    private static Thread sHeartbeatThread;
    private static long sGeneration;
    private static boolean sFullShortcutMode;

    private KeyboardShortcutWatcher() {
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
            }, "MagicDeskKeyWatcher");
            sThread.setDaemon(true);
            sThread.start();
        }
    }

    static void stop() {
        final Process process;
        final ShizukuAccess.StreamHandle shizukuStream;
        final ShizukuAccess.StreamHandle routingStream;
        final Thread thread;
        final Thread heartbeatThread;
        final boolean cancelAltTab;
        synchronized (LOCK) {
            sRunning = false;
            sGeneration++;
            cancelAltTab = sAltTabActive;
            clearModifierStateLocked();
            process = sProcess;
            shizukuStream = sShizukuStream;
            routingStream = sShizukuRoutingStream;
            thread = sThread;
            heartbeatThread = sHeartbeatThread;
            sProcess = null;
            sShizukuStream = null;
            sShizukuRoutingStream = null;
            sThread = null;
            sHeartbeatThread = null;
            sFullShortcutMode = false;
        }
        if (cancelAltTab) {
            ConsoleModeSwitcher.cancelAltTab();
        }
        if (process != null) {
            closeQuietly(process.getOutputStream());
        }
        closeQuietly(shizukuStream);
        closeQuietly(routingStream);
        if (heartbeatThread != null) {
            heartbeatThread.interrupt();
        }
        if (thread != null) {
            thread.interrupt();
        }
    }

    static boolean isRunning() {
        synchronized (LOCK) {
            return sRunning && (isProcessAlive(sProcess)
                    || sShizukuStream != null
                    || sShizukuRoutingStream != null);
        }
    }

    static boolean isFullShortcutMode() {
        synchronized (LOCK) {
            return isRunning() && sFullShortcutMode;
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
            final boolean useShizuku =
                    RuntimeAccess.allowsShizukuCommands()
                            && !RuntimeAccess.allowsRootCommands();
            Process process = null;
            ShizukuAccess.StreamHandle shizukuStream = null;
            BufferedReader reader = null;
            try {
                cleanupStaleInputRouting();
                if (useShizuku && consoleMode) {
                    runShizukuConsoleSession(generation);
                    continue;
                }

                final boolean fullShortcutMode =
                        RuntimeAccess.has(
                                RuntimeAccess.Capability.GLOBAL_INPUT)
                                && !useShizuku;
                final String command;
                if (useShizuku) {
                    command = SHIZUKU_INPUT_COMMAND;
                } else {
                    final String mode = fullShortcutMode && consoleMode
                            ? "console" : "shortcuts";
                    command = AppProcessCommand.exec(
                            INPUT_BRIDGE_COMMAND, mode);
                }
                final InputStream input;
                if (useShizuku) {
                    shizukuStream = ShizukuAccess.openStream(command);
                    setShizukuStream(
                            shizukuStream, generation, fullShortcutMode);
                    input = shizukuStream.inputStream();
                } else {
                    process = PrivilegedCommandRunner.start(command);
                    setProcess(process, generation, fullShortcutMode);
                    input = process.getInputStream();
                }
                Log.i(TAG, "input watcher started backend="
                        + RuntimeAccess.backendName()
                        + " full=" + fullShortcutMode
                        + " console=" + consoleMode);

                reader = new BufferedReader(new InputStreamReader(input));
                String line;
                while (isRunning(generation) && (line = reader.readLine()) != null) {
                    handleGeteventLine(line, fullShortcutMode);
                }
            } catch (IOException e) {
                if (isRunning(generation)) {
                    Log.w(TAG, "input watcher failed", e);
                    CompatibilityDiagnostics.record(
                            "INPUT-BRIDGE-001",
                            "The keyboard shortcut watcher stopped",
                            "backend=" + RuntimeAccess.backendName()
                                    + " consoleMode=" + consoleMode,
                            e);
                }
            } finally {
                closeQuietly(reader);
                if (process != null) {
                    process.destroy();
                }
                closeQuietly(shizukuStream);
                clearProcess(process);
                clearShizukuStream(shizukuStream);
                clearModifierState();
            }

            if (isRunning(generation)) {
                sleepBeforeRestart();
            }
        }
        Log.i(TAG, "input watcher stopped");
    }

    private static void cleanupStaleInputRouting()
            throws IOException {
        final String output = PrivilegedCommandRunner.run(
                AppProcessCommand.run(
                        SHIZUKU_ROUTING_COMMAND,
                        "cleanup-stale"));
        if (!output.contains(
                "MAGICDESK_SHIZUKU_ROUTING_CLEAN")) {
            throw new IOException(
                    "stale input routing cleanup failed: "
                            + output);
        }
    }

    private static void runShizukuConsoleSession(final long generation)
            throws IOException {
        ShizukuAccess.StreamHandle keyboardStream = null;
        ShizukuAccess.StreamHandle routingStream = null;
        BufferedReader keyboardReader = null;
        BufferedReader routingReader = null;
        Thread heartbeatThread = null;
        Thread routingMonitor = null;
        final AtomicBoolean sessionClosing = new AtomicBoolean();
        try {
            final List<ConsoleKeyboardDevice> keyboards =
                    ConsoleInputDeviceDiscovery.findKeyboards(
                            ShizukuAccess.run(DUMPSYS_INPUT));
            if (keyboards.isEmpty()) {
                throw new IOException(
                        "no external alphabetic keyboard was found");
            }

            keyboardStream = ShizukuAccess.openStream(
                    buildShizukuKeyboardCommand(keyboards));
            setShizukuStream(
                    keyboardStream, generation, false);
            keyboardReader = new BufferedReader(new InputStreamReader(
                    keyboardStream.inputStream()));
            waitForLine(
                    keyboardReader,
                    "MAGICDESK_SHIZUKU_KEYBOARD_READY",
                    "keyboard bridge");

            routingStream = ShizukuAccess.openStream(
                    AppProcessCommand.exec(SHIZUKU_ROUTING_COMMAND));
            setShizukuRoutingStream(routingStream, generation);
            routingReader = new BufferedReader(new InputStreamReader(
                    routingStream.inputStream()));
            final String routingReady = waitForLine(
                    routingReader,
                    "MAGICDESK_SHIZUKU_ROUTING_READY",
                    "input routing");
            final int routedKeyboards =
                    parseIntegerValue(routingReady, "keyboards");
            if (routedKeyboards < 2) {
                throw new IOException(
                        "virtual keyboard was not routed: "
                                + routingReady);
            }

            final ShizukuAccess.StreamHandle activeKeyboardStream =
                    keyboardStream;
            final ShizukuAccess.StreamHandle activeRoutingStream =
                    routingStream;
            final BufferedReader activeRoutingReader = routingReader;
            final Thread sessionThread = Thread.currentThread();
            routingMonitor = new Thread(
                    () -> monitorRouting(
                            activeRoutingReader,
                            activeKeyboardStream,
                            sessionThread,
                            sessionClosing,
                            generation),
                    "MagicDeskShizukuInputRouting");
            routingMonitor.setDaemon(true);
            routingMonitor.start();

            heartbeatThread = new Thread(
                    () -> sendShizukuHeartbeats(
                            activeKeyboardStream,
                            activeRoutingStream,
                            sessionThread,
                            sessionClosing,
                            generation),
                    "MagicDeskShizukuKeyboardHeartbeat");
            heartbeatThread.setDaemon(true);
            setHeartbeatThread(heartbeatThread, generation);
            heartbeatThread.start();

            syncHardwareKeyboardLayout();
            keyboardStream.writeLine("start");
            waitForLine(
                    keyboardReader,
                    "MAGICDESK_SHIZUKU_KEYBOARD_STARTED",
                    "keyboard capture");
            setFullShortcutMode(true, generation);
            Log.i(TAG, "input watcher started backend="
                    + RuntimeAccess.backendName()
                    + " full=true console=true"
                    + " keyboards=" + routedKeyboards);

            String line;
            while (isRunning(generation)
                    && (line = keyboardReader.readLine()) != null) {
                handleShizukuBridgeLine(
                        line, activeKeyboardStream, generation);
            }
            if (isRunning(generation)) {
                throw new IOException(
                        "Shizuku keyboard bridge exited unexpectedly");
            }
        } finally {
            sessionClosing.set(true);
            if (heartbeatThread != null) {
                heartbeatThread.interrupt();
            }
            closeQuietly(keyboardReader);
            closeQuietly(routingReader);
            closeQuietly(keyboardStream);
            closeQuietly(routingStream);
            clearHeartbeatThread(heartbeatThread);
            clearShizukuRoutingStream(routingStream);
            clearShizukuStream(keyboardStream);
            clearModifierState();
            if (routingMonitor != null) {
                routingMonitor.interrupt();
            }
            if (isRunning(generation)) {
                Thread.interrupted();
            }
        }
    }

    private static String buildShizukuKeyboardCommand(
            final List<ConsoleKeyboardDevice> keyboards)
            throws IOException {
        final File helper = new File(
                MagicDeskApplication.applicationContext()
                        .getApplicationInfo().nativeLibraryDir,
                SHIZUKU_KEYBOARD_HELPER);
        if (!helper.isFile()) {
            throw new IOException(
                    "packaged keyboard bridge is missing: " + helper);
        }
        final StringBuilder command =
                new StringBuilder("exec ")
                        .append(shellQuote(helper.getAbsolutePath()));
        for (final ConsoleKeyboardDevice keyboard : keyboards) {
            command.append(' ').append(shellQuote(keyboard.path));
        }
        return command.toString();
    }

    private static String waitForLine(
            final BufferedReader reader,
            final String expectedPrefix,
            final String component) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith(expectedPrefix)) {
                Log.i(TAG, line);
                return line;
            }
            if (line.contains("_ERROR")) {
                throw new IOException(component + " failed: " + line);
            }
            if (!line.isEmpty()) {
                Log.d(TAG, line);
            }
        }
        throw new IOException(component + " exited before becoming ready");
    }

    private static int parseIntegerValue(
            final String line,
            final String key) {
        final String prefix = key + "=";
        for (final String part : line.split("\\s+")) {
            if (!part.startsWith(prefix)) {
                continue;
            }
            try {
                return Integer.parseInt(
                        part.substring(prefix.length()));
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }

    private static void syncHardwareKeyboardLayout()
            throws IOException {
        final CountDownLatch complete = new CountDownLatch(1);
        HardwareKeyboardLayoutController.refresh(complete::countDown);
        try {
            complete.await();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException(
                    "interrupted while applying the virtual keyboard layout",
                    error);
        }
    }

    private static void monitorRouting(
            final BufferedReader reader,
            final ShizukuAccess.StreamHandle keyboardStream,
            final Thread sessionThread,
            final AtomicBoolean sessionClosing,
            final long generation) {
        try {
            String line;
            while (isRunning(generation)
                    && (line = reader.readLine()) != null) {
                if (!line.isEmpty()) {
                    Log.d(TAG, line);
                }
            }
        } catch (IOException error) {
            if (isRunning(generation)) {
                Log.w(TAG, "Shizuku input routing stopped", error);
            }
        } finally {
            if (!sessionClosing.get() && isRunning(generation)) {
                closeQuietly(keyboardStream);
                sessionThread.interrupt();
            }
        }
    }

    private static void sendShizukuHeartbeats(
            final ShizukuAccess.StreamHandle keyboardStream,
            final ShizukuAccess.StreamHandle routingStream,
            final Thread sessionThread,
            final AtomicBoolean sessionClosing,
            final long generation) {
        while (isRunning(generation)) {
            try {
                keyboardStream.writeLine("ping");
                routingStream.writeLine("ping");
                Thread.sleep(HEARTBEAT_MILLIS);
            } catch (IOException error) {
                if (!sessionClosing.get()
                        && isRunning(generation)) {
                    Log.w(TAG,
                            "Shizuku keyboard heartbeat failed",
                            error);
                    closeQuietly(keyboardStream);
                    closeQuietly(routingStream);
                    sessionThread.interrupt();
                }
                return;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void handleShizukuBridgeLine(
            final String line,
            final ShizukuAccess.StreamHandle keyboardStream,
            final long generation) {
        if (line.startsWith("MAGICDESK_ALT_TAB_ADVANCE ")
                || "MAGICDESK_ALT_TAB_COMMIT".equals(line)) {
            handleGeteventLine(line, true);
            return;
        }
        if (!line.startsWith("MAGICDESK_SHORTCUT ")) {
            if (line.contains("_ERROR")) {
                Log.w(TAG, line);
            } else if (!line.isEmpty()) {
                Log.d(TAG, line);
            }
            return;
        }

        final String action =
                line.substring("MAGICDESK_SHORTCUT ".length());
        if ("CTRL_SPACE".equals(action)) {
            Log.i(TAG, "Ctrl+Space");
            HardwareKeyboardLayoutController.toggle(
                    () -> resumeShizukuKeyboard(
                            keyboardStream, generation));
            return;
        }
        if ("ESCAPE".equals(action)) {
            DesktopTaskController.dismissTransientActivity();
            return;
        }
        if ("ALT_F4".equals(action)) {
            ConsoleModeSwitcher.manageActiveWindow(
                    DesktopTaskController.SHORTCUT_CLOSE);
            return;
        }
        if ("META_BACKSPACE".equals(action)) {
            ConsoleModeSwitcher.sendSystemBack();
            return;
        }
        if ("META_N".equals(action)) {
            ConsoleModeSwitcher.toggleNotificationCenter();
            return;
        }
        if ("META_UP".equals(action)) {
            ConsoleModeSwitcher.manageActiveWindow(
                    DesktopTaskController.SHORTCUT_FULLSCREEN);
            return;
        }
        if ("META_DOWN".equals(action)) {
            ConsoleModeSwitcher.manageActiveWindow(
                    DesktopTaskController.SHORTCUT_RESTORE);
            return;
        }
        if ("META_LEFT".equals(action)) {
            ConsoleModeSwitcher.manageActiveWindow(
                    DesktopTaskController.SHORTCUT_SNAP_LEFT);
            return;
        }
        if ("META_RIGHT".equals(action)) {
            ConsoleModeSwitcher.manageActiveWindow(
                    DesktopTaskController.SHORTCUT_SNAP_RIGHT);
            return;
        }
        if ("META_D".equals(action)) {
            ConsoleModeSwitcher.showMagicDesk();
            return;
        }
        if ("META_PRINT_SCREEN".equals(action)) {
            ConsoleModeSwitcher.captureScreenshot();
            return;
        }
        if ("META_SLASH".equals(action)) {
            ConsoleModeSwitcher.showShortcutHelp();
        }
    }

    private static void resumeShizukuKeyboard(
            final ShizukuAccess.StreamHandle keyboardStream,
            final long generation) {
        if (!isRunning(generation)) {
            return;
        }
        try {
            keyboardStream.writeLine("resume");
        } catch (IOException error) {
            Log.w(TAG, "cannot resume Shizuku keyboard", error);
            closeQuietly(keyboardStream);
        }
    }

    private static void handleGeteventLine(
            final String line,
            final boolean fullShortcutMode) {
        if (!fullShortcutMode
                && line.startsWith("MAGICDESK_")) {
            return;
        }
        if (line.startsWith("MAGICDESK_ALT_TAB_ADVANCE ")) {
            final boolean reverse = line.endsWith("reverse");
            synchronized (LOCK) {
                sAltTabActive = true;
            }
            Log.i(TAG, reverse
                    ? "Alt+Shift+Tab"
                    : "Alt+Tab");
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

        if ("KEY_SPACE".equals(keyName) && isCtrlOnlyDown()) {
            Log.i(TAG, "Ctrl+Space");
            ConsoleModeSwitcher.toggleHardwareKeyboardLayout();
            return;
        }

        if ("KEY_ESC".equals(keyName) && isNoModifierDown()) {
            DesktopTaskController.dismissTransientActivity();
            return;
        }

        if (!fullShortcutMode) {
            return;
        }

        if ("KEY_F4".equals(keyName) && isAltOnlyDown()) {
            Log.i(TAG, "Alt+F4");
            ConsoleModeSwitcher.manageActiveWindow(
                    DesktopTaskController.SHORTCUT_CLOSE);
            return;
        }

        if (isMetaOnlyDown()) {
            if ("KEY_BACKSPACE".equals(keyName)) {
                Log.i(TAG, "Meta+Backspace");
                ConsoleModeSwitcher.sendSystemBack();
                return;
            }
            if ("KEY_L".equals(keyName)) {
                Log.i(TAG, "Meta+L");
                ConsoleModeSwitcher.lockDevice();
                return;
            }
            if ("KEY_N".equals(keyName)) {
                Log.i(TAG, "Meta+N");
                ConsoleModeSwitcher.toggleNotificationCenter();
                return;
            }
            if ("KEY_UP".equals(keyName)) {
                Log.i(TAG, "Meta+Up");
                ConsoleModeSwitcher.manageActiveWindow(
                        DesktopTaskController.SHORTCUT_FULLSCREEN);
                return;
            }
            if ("KEY_DOWN".equals(keyName)) {
                Log.i(TAG, "Meta+Down");
                ConsoleModeSwitcher.manageActiveWindow(
                        DesktopTaskController.SHORTCUT_RESTORE);
                return;
            }
            if ("KEY_LEFT".equals(keyName)) {
                Log.i(TAG, "Meta+Left");
                ConsoleModeSwitcher.manageActiveWindow(
                        DesktopTaskController.SHORTCUT_SNAP_LEFT);
                return;
            }
            if ("KEY_RIGHT".equals(keyName)) {
                Log.i(TAG, "Meta+Right");
                ConsoleModeSwitcher.manageActiveWindow(
                        DesktopTaskController.SHORTCUT_SNAP_RIGHT);
                return;
            }
            if ("KEY_D".equals(keyName)) {
                Log.i(TAG, "Meta+D");
                ConsoleModeSwitcher.showMagicDesk();
                return;
            }
            if (isPrintScreenKey(keyName)) {
                Log.i(TAG, "Meta+PrintScreen");
                ConsoleModeSwitcher.captureScreenshot();
                return;
            }
            if ("KEY_SLASH".equals(keyName)) {
                Log.i(TAG, "Meta+Slash");
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
            return sCtrlDown && !sAltDown && !sShiftDown && !sMetaDown;
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
            Log.i(TAG, "Alt+Tab commit");
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

    private static void setProcess(
            final Process process,
            final long generation,
            final boolean fullShortcutMode) {
        synchronized (LOCK) {
            if (sRunning && sGeneration == generation) {
                sProcess = process;
                sFullShortcutMode = fullShortcutMode;
            }
        }
    }

    private static void setShizukuStream(
            final ShizukuAccess.StreamHandle stream,
            final long generation,
            final boolean fullShortcutMode) {
        synchronized (LOCK) {
            if (sRunning && sGeneration == generation) {
                sShizukuStream = stream;
                sFullShortcutMode = fullShortcutMode;
            }
        }
    }

    private static void setShizukuRoutingStream(
            final ShizukuAccess.StreamHandle stream,
            final long generation) {
        synchronized (LOCK) {
            if (sRunning && sGeneration == generation) {
                sShizukuRoutingStream = stream;
            }
        }
    }

    private static void setHeartbeatThread(
            final Thread thread,
            final long generation) {
        synchronized (LOCK) {
            if (sRunning && sGeneration == generation) {
                sHeartbeatThread = thread;
            }
        }
    }

    private static void setFullShortcutMode(
            final boolean enabled,
            final long generation) {
        synchronized (LOCK) {
            if (sRunning && sGeneration == generation) {
                sFullShortcutMode = enabled;
            }
        }
    }

    private static void clearProcess(final Process process) {
        synchronized (LOCK) {
            if (sProcess == process) {
                sProcess = null;
                if (sShizukuStream == null
                        && sShizukuRoutingStream == null) {
                    sFullShortcutMode = false;
                }
            }
        }
    }

    private static void clearShizukuStream(
            final ShizukuAccess.StreamHandle stream) {
        synchronized (LOCK) {
            if (sShizukuStream == stream) {
                sShizukuStream = null;
                if (sProcess == null
                        && sShizukuRoutingStream == null) {
                    sFullShortcutMode = false;
                }
            }
        }
    }

    private static void clearShizukuRoutingStream(
            final ShizukuAccess.StreamHandle stream) {
        synchronized (LOCK) {
            if (sShizukuRoutingStream == stream) {
                sShizukuRoutingStream = null;
                if (sProcess == null && sShizukuStream == null) {
                    sFullShortcutMode = false;
                }
            }
        }
    }

    private static void clearHeartbeatThread(final Thread thread) {
        synchronized (LOCK) {
            if (sHeartbeatThread == thread) {
                sHeartbeatThread = null;
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

    private static String shellQuote(final String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
