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

final class KeyboardShortcutWatcher {
    interface RoutingListener {
        void onRoutingChanged(int displayId, boolean ready);
    }

    private static final String TAG = "MagicDeskKeys";
    private static final String INPUT_EVENT_COMMAND =
            "/system/bin/getevent -lt";
    private static final String KEYBOARD_HELPER =
            "libmagicdesk_keyboard_bridge.so";
    private static final String DUMPSYS_INPUT =
            "/system/bin/dumpsys input";
    private static final long RESTART_DELAY_MS = 1000L;

    private static final Object LOCK = new Object();
    private static final KeyboardShortcutStateMachine SHORTCUTS =
            new KeyboardShortcutStateMachine();
    private static boolean sRunning;
    private static ShellStreamHandle sInputStream;
    private static ShellInputRoutingHandle sInputRouting;
    private static Thread sThread;
    private static long sGeneration;
    private static boolean sFullShortcutMode;
    private static int sRoutingDisplayId = -1;
    private static RoutingListener sRoutingListener;

    private KeyboardShortcutWatcher() {
    }

    static void start(
            final int routingDisplayId,
            final RoutingListener routingListener) {
        final long generation;
        synchronized (LOCK) {
            if (sRunning) {
                return;
            }
            sRunning = true;
            sRoutingDisplayId = routingDisplayId;
            sRoutingListener = routingListener;
            generation = ++sGeneration;
            sThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    runLoop(routingDisplayId, generation);
                }
            }, "MagicDeskKeyWatcher");
            sThread.setDaemon(true);
            sThread.start();
        }
    }

    static void stop() {
        final ShellStreamHandle inputStream;
        final ShellInputRoutingHandle inputRouting;
        final Thread thread;
        final boolean cancelAltTab;
        final RoutingListener routingListener;
        final int routingDisplayId;
        final boolean routingWasReady;
        synchronized (LOCK) {
            sRunning = false;
            sGeneration++;
            cancelAltTab = SHORTCUTS.reset();
            inputStream = sInputStream;
            inputRouting = sInputRouting;
            routingListener = sRoutingListener;
            routingDisplayId = sRoutingDisplayId;
            routingWasReady = inputRouting != null;
            thread = sThread;
            sInputStream = null;
            sInputRouting = null;
            sThread = null;
            sFullShortcutMode = false;
            sRoutingDisplayId = -1;
            sRoutingListener = null;
            LOCK.notifyAll();
        }
        if (cancelAltTab) {
            ConsoleModeSwitcher.cancelAltTab();
        }
        if (routingWasReady && routingListener != null) {
            routingListener.onRoutingChanged(routingDisplayId, false);
        }
        closeQuietly(inputRouting);
        closeQuietly(inputStream);
        if (thread != null) {
            thread.interrupt();
        }
    }

    static boolean isRunning() {
        synchronized (LOCK) {
            return sRunning && (sInputStream != null
                    || sInputRouting != null);
        }
    }

    static boolean isFullShortcutMode() {
        synchronized (LOCK) {
            return isRunning() && sFullShortcutMode;
        }
    }

    static void refreshDesktopInputSources(
            final List<DesktopKeyboardDevice> keyboards) {
        final ShellStreamHandle inputStream;
        final ShellInputRoutingHandle inputRouting;
        synchronized (LOCK) {
            if (!sRunning || sRoutingDisplayId <= 0) {
                return;
            }
            inputStream = sInputStream;
            inputRouting = sInputRouting;
        }
        try {
            if (inputStream != null) {
                inputStream.writeLine(buildSourcesCommand(keyboards));
            }
            if (inputRouting != null) {
                inputRouting.refresh();
            }
        } catch (IOException error) {
            InputBridgeDiagnostics.noteSourceRefreshFailure(error);
            Log.w(TAG, "Could not refresh desktop input sources", error);
        }
    }

    static void refreshDesktopInputRouting() {
        final ShellInputRoutingHandle inputRouting;
        synchronized (LOCK) {
            if (!sRunning || sRoutingDisplayId <= 0) {
                return;
            }
            inputRouting = sInputRouting;
        }
        if (inputRouting == null) {
            return;
        }
        try {
            inputRouting.refresh();
        } catch (IOException error) {
            InputBridgeDiagnostics.noteSourceRefreshFailure(error);
            Log.w(TAG, "Could not refresh desktop input routing", error);
        }
    }

    private static void runLoop(
            final int routingDisplayId,
            final long generation) {
        while (isRunning(generation)) {
            InputBridgeDiagnostics.noteAttempt(routingDisplayId);
            ShellStreamHandle inputStream = null;
            BufferedReader reader = null;
            try {
                ShellAccess.cleanupInputRouting();
                if (routingDisplayId > 0) {
                    runDesktopSession(routingDisplayId, generation);
                    continue;
                }

                final boolean fullShortcutMode = false;
                inputStream = ShellAccess.openOwnedStream(
                        INPUT_EVENT_COMMAND);
                setInputStream(
                        inputStream, generation, fullShortcutMode);
                final InputStream input = inputStream.inputStream();
                Log.i(TAG, "input watcher started shell="
                        + ShellAccess.statusLabel()
                        + " full=" + fullShortcutMode
                        + " routingDisplay=" + routingDisplayId);

                reader = new BufferedReader(new InputStreamReader(input));
                String line;
                while (isRunning(generation) && (line = reader.readLine()) != null) {
                    handleGeteventLine(line, fullShortcutMode);
                }
            } catch (IOException e) {
                if (isRunning(generation)) {
                    InputBridgeDiagnostics.noteFailure(e);
                    Log.w(TAG, "input watcher failed", e);
                    CompatibilityDiagnostics.record(
                            "INPUT-BRIDGE-001",
                            "The keyboard shortcut watcher stopped",
                            "shell=" + ShellAccess.statusLabel()
                                    + " routingDisplay=" + routingDisplayId,
                            e);
                }
            } finally {
                closeQuietly(reader);
                closeQuietly(inputStream);
                clearInputStream(inputStream);
                clearModifierState();
            }

            if (isRunning(generation)) {
                sleepBeforeRestart();
            }
        }
        Log.i(TAG, "input watcher stopped");
    }

    private static void runDesktopSession(
            final int routingDisplayId,
            final long generation)
            throws IOException {
        ShellStreamHandle keyboardStream = null;
        ShellInputRoutingHandle inputRouting = null;
        BufferedReader keyboardReader = null;
        HardwareKeyboardLayoutController.LayoutSink layoutSink = null;
        try {
            final List<DesktopKeyboardDevice> keyboards =
                    DesktopInputDeviceDiscovery.findKeyboards(
                            ShellAccess.run(DUMPSYS_INPUT));
            if (keyboards.isEmpty()) {
                runPointerOnlySession(routingDisplayId, generation);
                return;
            }
            final int layoutCount =
                    HardwareKeyboardLayoutController.catalogLayoutCount();
            if (layoutCount == 0) {
                runPointerOnlySession(routingDisplayId, generation);
                return;
            }

            keyboardStream = ShellAccess.openOwnedStream(
                    buildKeyboardCommand(
                            keyboards, layoutCount));
            setInputStream(
                    keyboardStream, generation, false);
            keyboardReader = new BufferedReader(new InputStreamReader(
                    keyboardStream.inputStream()));
            waitForLine(
                    keyboardReader,
                    "MAGICDESK_KEYBOARD_READY",
                    "keyboard bridge");

            inputRouting = ShellAccess.openInputRouting(
                    routingDisplayId, layoutCount);
            if (inputRouting.virtualKeyboardCount() != layoutCount) {
                throw new IOException(
                        "virtual keyboard routing count mismatch");
            }
            setInputRouting(inputRouting, generation);

            final ShellStreamHandle activeKeyboardStream =
                    keyboardStream;
            layoutSink = index -> activeKeyboardStream.writeLine(
                    "layout " + index);
            HardwareKeyboardLayoutController.attachLayoutSink(
                    layoutSink);
            syncHardwareKeyboardLayout();
            keyboardStream.writeLine("start");
            waitForLine(
                    keyboardReader,
                    "MAGICDESK_KEYBOARD_STARTED",
                    "keyboard capture");
            setFullShortcutMode(true, generation);
            InputBridgeDiagnostics.noteReady(true);
            Log.i(TAG, "input watcher started shell="
                    + ShellAccess.statusLabel()
                    + " full=true routingDisplay=" + routingDisplayId
                    + " keyboards="
                    + inputRouting.keyboardAssociationCount()
                    + " associations="
                    + inputRouting.associationCount()
                    + " layouts=" + layoutCount);

            String line;
            while (isRunning(generation)
                    && (line = keyboardReader.readLine()) != null) {
                handleKeyboardBridgeLine(
                        line, activeKeyboardStream, generation);
            }
            if (isRunning(generation)) {
                throw new IOException(
                        "Keyboard bridge exited unexpectedly");
            }
        } finally {
            clearInputRouting(inputRouting);
            closeQuietly(inputRouting);
            closeQuietly(keyboardReader);
            closeQuietly(keyboardStream);
            clearInputStream(keyboardStream);
            HardwareKeyboardLayoutController.detachLayoutSink(
                    layoutSink);
            clearModifierState();
            if (isRunning(generation)) {
                Thread.interrupted();
            }
        }
    }

    private static void runPointerOnlySession(
            final int routingDisplayId,
            final long generation) throws IOException {
        ShellInputRoutingHandle inputRouting = null;
        try {
            inputRouting = ShellAccess.openInputRouting(routingDisplayId, 0);
            setInputRouting(inputRouting, generation);
            InputBridgeDiagnostics.noteReady(false);
            Log.i(TAG, "input watcher started shell="
                    + ShellAccess.statusLabel()
                    + " full=false routingDisplay=" + routingDisplayId
                    + " keyboards=0 associations="
                    + inputRouting.associationCount()
                    + " layouts=0");
            waitUntilStopped(generation);
        } finally {
            clearInputRouting(inputRouting);
            closeQuietly(inputRouting);
        }
    }

    private static void waitUntilStopped(final long generation) {
        synchronized (LOCK) {
            while (sRunning && sGeneration == generation) {
                try {
                    LOCK.wait();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private static String buildKeyboardCommand(
            final List<DesktopKeyboardDevice> keyboards,
            final int layoutCount)
            throws IOException {
        final File helper = new File(
                MagicDeskApplication.applicationContext()
                        .getApplicationInfo().nativeLibraryDir,
                KEYBOARD_HELPER);
        if (!helper.isFile()) {
            throw new IOException(
                    "packaged keyboard bridge is missing: " + helper);
        }
        final StringBuilder command =
                new StringBuilder("exec ")
                        .append(ShellCommandLine.quote(helper.getAbsolutePath()))
                        .append(" --layouts ")
                        .append(layoutCount);
        for (final DesktopKeyboardDevice keyboard : keyboards) {
            command.append(' ').append(ShellCommandLine.quote(keyboard.path));
        }
        return command.toString();
    }

    private static String buildSourcesCommand(
            final List<DesktopKeyboardDevice> keyboards) {
        final StringBuilder command = new StringBuilder("sources");
        for (final DesktopKeyboardDevice keyboard : keyboards) {
            command.append(' ').append(keyboard.path);
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

    private static void syncHardwareKeyboardLayout()
            throws IOException {
        final CountDownLatch complete = new CountDownLatch(1);
        HardwareKeyboardLayoutController.configureVirtualLayouts(
                complete::countDown);
        try {
            complete.await();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException(
                    "interrupted while applying the virtual keyboard layout",
                    error);
        }
    }

    private static void handleKeyboardBridgeLine(
            final String line,
            final ShellStreamHandle keyboardStream,
            final long generation) {
        if ("MAGICDESK_KEYBOARD_ACTIVITY".equals(line)) {
            MagicDeskRuntime.reactivatePointerOnNextMotion();
            return;
        }
        if (line.startsWith("MAGICDESK_ALT_TAB_ADVANCE ")
                || "MAGICDESK_ALT_TAB_COMMIT".equals(line)) {
            handleGeteventLine(line, true);
            return;
        }
        if (!line.startsWith("MAGICDESK_SHORTCUT ")) {
            if (line.contains("_ERROR")) {
                InputBridgeDiagnostics.noteBridgeAnomaly(line);
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
                    () -> resumeKeyboard(
                            keyboardStream, generation));
            return;
        }
        if ("ESCAPE".equals(action)) {
            MagicDeskRuntime.dismissTransientActivity();
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
        if ("META_L".equals(action)) {
            ConsoleModeSwitcher.lockDevice();
            return;
        }
        if ("META_N".equals(action)) {
            ConsoleModeSwitcher.toggleNotificationCenter();
            return;
        }
        if ("META_Q".equals(action)) {
            ConsoleModeSwitcher.toggleSystemPanel();
            return;
        }
        if ("META_I".equals(action)) {
            ConsoleModeSwitcher.openSettings();
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
            ConsoleModeSwitcher.toggleDesktopWorkspace();
            return;
        }
        if ("META_PRINT_SCREEN".equals(action)) {
            ConsoleModeSwitcher.captureScreenshot();
            return;
        }
        if ("META_SHIFT_PRINT_SCREEN".equals(action)) {
            DisplayRecordingController.get().toggle();
            return;
        }
        if ("META_SLASH".equals(action)) {
            ConsoleModeSwitcher.showShortcutHelp();
        }
    }

    private static void resumeKeyboard(
            final ShellStreamHandle keyboardStream,
            final long generation) {
        if (!isRunning(generation)) {
            return;
        }
        try {
            keyboardStream.writeLine("resume");
        } catch (IOException error) {
            Log.w(TAG, "cannot resume keyboard bridge", error);
            closeQuietly(keyboardStream);
        }
    }

    private static void handleGeteventLine(
            final String line,
            final boolean fullShortcutMode) {
        dispatchShortcut(SHORTCUTS.accept(line, fullShortcutMode));
    }

    private static void dispatchShortcut(
            final KeyboardShortcutStateMachine.Action action) {
        switch (action) {
            case ALT_TAB_FORWARD:
                ConsoleModeSwitcher.advanceAltTab(false);
                break;
            case ALT_TAB_REVERSE:
                ConsoleModeSwitcher.advanceAltTab(true);
                break;
            case ALT_TAB_COMMIT:
                ConsoleModeSwitcher.finishAltTab();
                break;
            case TOGGLE_LAYOUT:
                ConsoleModeSwitcher.toggleHardwareKeyboardLayout();
                break;
            case DISMISS:
                MagicDeskRuntime.dismissTransientActivity();
                break;
            case CLOSE:
                ConsoleModeSwitcher.manageActiveWindow(
                        DesktopTaskController.SHORTCUT_CLOSE);
                break;
            case BACK:
                ConsoleModeSwitcher.sendSystemBack();
                break;
            case LOCK:
                ConsoleModeSwitcher.lockDevice();
                break;
            case NOTIFICATIONS:
                ConsoleModeSwitcher.toggleNotificationCenter();
                break;
            case SYSTEM:
                ConsoleModeSwitcher.toggleSystemPanel();
                break;
            case SETTINGS:
                ConsoleModeSwitcher.openSettings();
                break;
            case FULLSCREEN:
                ConsoleModeSwitcher.manageActiveWindow(
                        DesktopTaskController.SHORTCUT_FULLSCREEN);
                break;
            case RESTORE:
                ConsoleModeSwitcher.manageActiveWindow(
                        DesktopTaskController.SHORTCUT_RESTORE);
                break;
            case SNAP_LEFT:
                ConsoleModeSwitcher.manageActiveWindow(
                        DesktopTaskController.SHORTCUT_SNAP_LEFT);
                break;
            case SNAP_RIGHT:
                ConsoleModeSwitcher.manageActiveWindow(
                        DesktopTaskController.SHORTCUT_SNAP_RIGHT);
                break;
            case SHOW_DESKTOP:
                ConsoleModeSwitcher.toggleDesktopWorkspace();
                break;
            case SCREENSHOT:
                ConsoleModeSwitcher.captureScreenshot();
                break;
            case SCREEN_RECORDING:
                DisplayRecordingController.get().toggle();
                break;
            case SHORTCUT_HELP:
                ConsoleModeSwitcher.showShortcutHelp();
                break;
            case NONE:
            default:
                break;
        }
    }

    private static void clearModifierState() {
        if (SHORTCUTS.reset()) {
            ConsoleModeSwitcher.cancelAltTab();
        }
    }

    private static boolean isRunning(final long generation) {
        synchronized (LOCK) {
            return sRunning && sGeneration == generation;
        }
    }

    private static void setInputStream(
            final ShellStreamHandle stream,
            final long generation,
            final boolean fullShortcutMode) {
        synchronized (LOCK) {
            if (sRunning && sGeneration == generation) {
                sInputStream = stream;
                sFullShortcutMode = fullShortcutMode;
            }
        }
    }

    private static void setInputRouting(
            final ShellInputRoutingHandle inputRouting,
            final long generation) {
        RoutingListener listener = null;
        int displayId = -1;
        synchronized (LOCK) {
            if (sRunning && sGeneration == generation) {
                sInputRouting = inputRouting;
                listener = sRoutingListener;
                displayId = sRoutingDisplayId;
            }
        }
        if (listener != null) {
            listener.onRoutingChanged(displayId, true);
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

    private static void clearInputStream(
            final ShellStreamHandle stream) {
        synchronized (LOCK) {
            if (sInputStream == stream) {
                sInputStream = null;
                if (sInputRouting == null) {
                    sFullShortcutMode = false;
                }
            }
        }
    }

    private static void clearInputRouting(
            final ShellInputRoutingHandle inputRouting) {
        RoutingListener listener = null;
        int displayId = -1;
        synchronized (LOCK) {
            if (sInputRouting == inputRouting) {
                sInputRouting = null;
                listener = sRoutingListener;
                displayId = sRoutingDisplayId;
                if (sInputStream == null) {
                    sFullShortcutMode = false;
                }
            }
        }
        if (listener != null) {
            listener.onRoutingChanged(displayId, false);
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
