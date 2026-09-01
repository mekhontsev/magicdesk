package io.github.mekhontsev.magicdesk;

import android.util.Log;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

/** Decodes global shortcuts; external relay ownership lives elsewhere. */
final class KeyboardShortcutWatcher {
    private static final String TAG = "MagicDeskKeys";
    private static final String INPUT_EVENT_COMMAND =
            "/system/bin/getevent -lt";
    private static final long RESTART_DELAY_MS = 1_000L;

    private static final Object LOCK = new Object();
    private static final KeyboardShortcutStateMachine SHORTCUTS =
            new KeyboardShortcutStateMachine();
    private static boolean sRunning;
    private static ShellStreamHandle sInputStream;
    private static Thread sThread;
    private static long sGeneration;

    private KeyboardShortcutWatcher() {
    }

    static void start() {
        final long generation;
        synchronized (LOCK) {
            if (sRunning) {
                return;
            }
            sRunning = true;
            generation = ++sGeneration;
            sThread = new Thread(
                    () -> runLoop(generation),
                    "MagicDeskKeyWatcher");
            sThread.setDaemon(true);
            sThread.start();
        }
    }

    static void stop() {
        final ShellStreamHandle inputStream;
        final Thread thread;
        synchronized (LOCK) {
            sRunning = false;
            ++sGeneration;
            inputStream = sInputStream;
            thread = sThread;
            sInputStream = null;
            sThread = null;
            LOCK.notifyAll();
        }
        clearModifierState();
        closeQuietly(inputStream);
        if (thread != null) {
            thread.interrupt();
        }
    }

    static InputRelayRuntimeDiagnostics.BridgeSnapshot captureDiagnostics() {
        final boolean running;
        final long generation;
        synchronized (LOCK) {
            running = sRunning;
            generation = sGeneration;
        }
        return new InputRelayRuntimeDiagnostics.BridgeSnapshot(
                running,
                false,
                false,
                generation,
                "",
                running
                        ? "native keyboard relay not active"
                        : "not running");
    }

    static void handleBridgeLine(
            final String line,
            final Runnable resumeKeyboard) {
        if (line.startsWith("MAGICDESK_ALT_TAB_")) {
            handleGeteventLine(line, true);
            return;
        }
        if (!line.startsWith("MAGICDESK_SHORTCUT ")) {
            return;
        }
        final String action = line.substring(
                "MAGICDESK_SHORTCUT ".length());
        if ("CTRL_SPACE".equals(action)) {
            HardwareKeyboardLayoutController.toggle(resumeKeyboard);
            return;
        }
        dispatchShortcut(nativeAction(action));
    }

    static void clearModifierState() {
        if (SHORTCUTS.reset()) {
            DesktopOperations.cancelAltTab();
        }
    }

    private static void runLoop(final long generation) {
        while (isRunning(generation)) {
            InputBridgeDiagnostics.noteAttempt(-1);
            ShellStreamHandle inputStream = null;
            BufferedReader reader = null;
            try {
                inputStream = ShellAccess.openOwnedStream(
                        INPUT_EVENT_COMMAND);
                setInputStream(inputStream, generation);
                final InputStream input = inputStream.inputStream();
                Log.i(TAG, "passive input watcher started shell="
                        + ShellAccess.statusLabel());

                reader = new BufferedReader(new InputStreamReader(input));
                String line;
                while (isRunning(generation)
                        && (line = reader.readLine()) != null) {
                    handleGeteventLine(line, false);
                }
            } catch (IOException error) {
                if (isRunning(generation)) {
                    InputBridgeDiagnostics.noteFailure(error);
                    Log.w(TAG, "input watcher failed", error);
                    CompatibilityDiagnostics.record(
                            "INPUT-BRIDGE-001",
                            "The keyboard shortcut watcher stopped",
                            "shell=" + ShellAccess.statusLabel(),
                            error);
                }
            } finally {
                closeQuietly(reader);
                closeQuietly(inputStream);
                clearInputStream(inputStream);
                if (isCurrentGeneration(generation)) {
                    clearModifierState();
                }
            }

            if (isRunning(generation)) {
                sleepBeforeRestart();
            }
        }
        Log.i(TAG, "passive input watcher stopped");
    }

    private static void handleGeteventLine(
            final String line,
            final boolean fullShortcutMode) {
        dispatchShortcut(SHORTCUTS.accept(line, fullShortcutMode));
    }

    private static KeyboardShortcutStateMachine.Action nativeAction(
            final String action) {
        switch (action) {
            case "ESCAPE":
                return KeyboardShortcutStateMachine.Action.DISMISS;
            case "ALT_F4":
                return KeyboardShortcutStateMachine.Action.CLOSE;
            case "META_BACKSPACE":
                return KeyboardShortcutStateMachine.Action.BACK;
            case "META_L":
                return KeyboardShortcutStateMachine.Action.LOCK;
            case "META_N":
                return KeyboardShortcutStateMachine.Action.NOTIFICATIONS;
            case "META_Q":
                return KeyboardShortcutStateMachine.Action.SYSTEM;
            case "META_I":
                return KeyboardShortcutStateMachine.Action.SETTINGS;
            case "META_UP":
                return KeyboardShortcutStateMachine.Action.FULLSCREEN;
            case "META_DOWN":
                return KeyboardShortcutStateMachine.Action.RESTORE;
            case "META_LEFT":
                return KeyboardShortcutStateMachine.Action.SNAP_LEFT;
            case "META_RIGHT":
                return KeyboardShortcutStateMachine.Action.SNAP_RIGHT;
            case "META_D":
                return KeyboardShortcutStateMachine.Action.SHOW_DESKTOP;
            case "META_PRINT_SCREEN":
                return KeyboardShortcutStateMachine.Action.SCREENSHOT;
            case "META_SHIFT_PRINT_SCREEN":
                return KeyboardShortcutStateMachine.Action.SCREEN_RECORDING;
            case "META_SLASH":
                return KeyboardShortcutStateMachine.Action.SHORTCUT_HELP;
            default:
                return KeyboardShortcutStateMachine.Action.NONE;
        }
    }

    private static void dispatchShortcut(
            final KeyboardShortcutStateMachine.Action action) {
        switch (action) {
            case ALT_TAB_FORWARD:
                DesktopOperations.advanceAltTab(false);
                break;
            case ALT_TAB_REVERSE:
                DesktopOperations.advanceAltTab(true);
                break;
            case ALT_TAB_COMMIT:
                DesktopOperations.finishAltTab();
                break;
            case TOGGLE_LAYOUT:
                DesktopOperations.toggleHardwareKeyboardLayout();
                break;
            case DISMISS:
                MagicDeskRuntime.dismissTransientActivity();
                break;
            case CLOSE:
                DesktopOperations.manageActiveWindow(
                        DesktopTaskController.SHORTCUT_CLOSE);
                break;
            case BACK:
                DesktopOperations.sendSystemBack();
                break;
            case LOCK:
                DesktopOperations.lockDevice();
                break;
            case NOTIFICATIONS:
                DesktopOperations.toggleNotificationCenter();
                break;
            case SYSTEM:
                DesktopOperations.toggleSystemPanel();
                break;
            case SETTINGS:
                DesktopOperations.openSettings();
                break;
            case FULLSCREEN:
                DesktopOperations.manageActiveWindow(
                        DesktopTaskController.SHORTCUT_FULLSCREEN);
                break;
            case RESTORE:
                DesktopOperations.manageActiveWindow(
                        DesktopTaskController.SHORTCUT_RESTORE);
                break;
            case SNAP_LEFT:
                DesktopOperations.manageActiveWindow(
                        DesktopTaskController.SHORTCUT_SNAP_LEFT);
                break;
            case SNAP_RIGHT:
                DesktopOperations.manageActiveWindow(
                        DesktopTaskController.SHORTCUT_SNAP_RIGHT);
                break;
            case SHOW_DESKTOP:
                DesktopOperations.toggleDesktopWorkspace();
                break;
            case SCREENSHOT:
                DesktopOperations.captureScreenshot();
                break;
            case SCREEN_RECORDING:
                DisplayRecordingController.get().toggle();
                break;
            case SHORTCUT_HELP:
                DesktopOperations.showShortcutHelp();
                break;
            case NONE:
            default:
                break;
        }
    }

    private static boolean isRunning(final long generation) {
        synchronized (LOCK) {
            return sRunning && sGeneration == generation;
        }
    }

    private static boolean isCurrentGeneration(final long generation) {
        synchronized (LOCK) {
            return sGeneration == generation;
        }
    }

    private static void setInputStream(
            final ShellStreamHandle stream,
            final long generation) {
        synchronized (LOCK) {
            if (sRunning && sGeneration == generation) {
                sInputStream = stream;
            }
        }
    }

    private static void clearInputStream(
            final ShellStreamHandle stream) {
        synchronized (LOCK) {
            if (sInputStream == stream) {
                sInputStream = null;
            }
        }
    }

    private static void sleepBeforeRestart() {
        try {
            RuntimeDelays.pauseInterruptibly(
                    RuntimeDelays.Reason.SUPERVISOR_BACKOFF,
                    RESTART_DELAY_MS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(final Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // A disconnected helper has already stopped.
        }
    }
}
