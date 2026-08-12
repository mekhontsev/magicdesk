package io.github.mekhontsev.magicdesk;

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
    private static final String CONSOLE_TASK_RETURN_COMMAND =
            "io.github.mekhontsev.magicdesk.ConsoleTaskReturnCommand";
    private static final String DEVICE_LOCK_COMMAND =
            "io.github.mekhontsev.magicdesk.DeviceLockCommand";
    private static final String SCREENSHOT_DIRECTORY =
            "/storage/emulated/0/Pictures/Screenshots";
    private static final AtomicBoolean DESKTOP_START_IN_PROGRESS = new AtomicBoolean();

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

    interface ExternalDisplayProbeCallback {
        void onComplete(
                int wiredDisplayId,
                int wirelessDisplayId,
                DisplayProfileStore.Profile displayProfile,
                NubiaHdmiModeController.Selection modeSelection);
    }

    static void setPhoneScreenOff(final boolean screenOff,
            final ResultCallback callback) {
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                boolean success = false;
                final boolean pointerCaptured = MagicDeskRuntimeService
                        .capturePointerPositionIfRunning();
                if (pointerCaptured) {
                    MagicDeskRuntimeService
                            .restorePointerPositionOnNextMotionIfRunning();
                }
                try {
                    success = screenOff
                            ? PhoneDisplayGuard.enable()
                            : PhoneDisplayGuard.disable();
                    Log.i(TAG, "Shell phone display off="
                            + screenOff + " success=" + success);
                    if (!success) {
                        CompatibilityDiagnostics.record(
                                "NUBIA-SCREEN-002",
                                "Could not change the phone screen state",
                                "shizuku=" + ShellAccess.statusLabel()
                                        + " screenOff=" + screenOff);
                    }
                } finally {
                    if (callback != null) {
                        callback.onComplete(success);
                    }
                }
            }
        });
    }

    static void showMagicDesk() {
        showMagicDesk(-1);
    }

    static void showMagicDesk(final int knownConsoleDisplayId) {
        if (!DESKTOP_START_IN_PROGRESS.compareAndSet(false, true)) {
            Log.i(TAG, "MagicDesk activation is already in progress");
            return;
        }
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    showPreferredDesktop(knownConsoleDisplayId);
                } finally {
                    DESKTOP_START_IN_PROGRESS.set(false);
                }
            }
        });
    }

    static void showDesktop(final DesktopDisplayTarget target) {
        if (target == null
                || target.displayId <= android.view.Display.DEFAULT_DISPLAY
                || target.kind == DesktopDisplayTarget.Kind.WIRED) {
            throw new IllegalArgumentException(
                    "a prepared non-wired display target is required");
        }
        if (!DESKTOP_START_IN_PROGRESS.compareAndSet(false, true)) {
            Log.i(TAG, "MagicDesk activation is already in progress");
            return;
        }
        EXECUTOR.execute(() -> {
            try {
                showPreparedDesktop(target);
            } finally {
                DESKTOP_START_IN_PROGRESS.set(false);
            }
        });
    }

    static void closeDesktop(
            final int displayId,
            final DesktopDisplayTarget.Kind targetKind,
            final boolean restorePhonePanel,
            final ResultCallback callback) {
        if (displayId <= android.view.Display.DEFAULT_DISPLAY) {
            complete(callback, true);
            return;
        }
        if (targetKind == DesktopDisplayTarget.Kind.SIMULATED) {
            DesktopRuntimeBridge.closeDesktopSession(displayId);
            complete(callback, true);
            return;
        }
        if (targetKind == DesktopDisplayTarget.Kind.WIRELESS) {
            disconnectWirelessDisplay(success -> {
                if (success && restorePhonePanel) {
                    MagicDeskRuntimeService
                            .restorePhonePanelAfterExternalDesktopRemovalIfRunning(
                                    displayId);
                }
                complete(callback, success);
            });
            return;
        }
        if (restorePhonePanel) {
            switchToMirrorWithControlPanel(
                    success -> complete(callback, success));
        } else {
            switchToMirror(success -> complete(callback, success));
        }
    }

    private static void complete(
            final ResultCallback callback,
            final boolean success) {
        if (callback != null) {
            callback.onComplete(success);
        }
    }

    private static void showPreferredDesktop(
            final int knownConsoleDisplayId) {
        if (knownConsoleDisplayId > android.view.Display.DEFAULT_DISPLAY
                || ConsoleDisplayController.getActiveConsoleDisplayId()
                        > android.view.Display.DEFAULT_DISPLAY
                || ConsoleDisplayController.findExternalDisplayId()
                        > android.view.Display.DEFAULT_DISPLAY) {
            ConsoleSessionController.show(knownConsoleDisplayId);
            return;
        }
        final int wirelessDisplayId =
                ConsoleDisplayController.findWirelessDisplayId();
        if (wirelessDisplayId > android.view.Display.DEFAULT_DISPLAY) {
            showPreparedDesktop(
                    DesktopDisplayTarget.wireless(wirelessDisplayId));
            return;
        }
        ConsoleSessionController.show(knownConsoleDisplayId);
    }

    private static void showPreparedDesktop(
            final DesktopDisplayTarget target) {
        try {
            final DesktopSessionController.ShowResult result =
                    DesktopSessionController.show(target);
            if (result.ready && result.created) {
                PhoneTouchpadController.open(target.displayId);
            }
        } catch (IOException error) {
            Log.w(TAG, "Secondary desktop launch failed", error);
            CompatibilityDiagnostics.record(
                    "DESKTOP-LAUNCH-002",
                    "Could not open MagicDesk on the selected display",
                    "kind=" + target.kind
                            + " display=" + target.displayId
                            + " error=" + error.getMessage(),
                    error);
        }
    }

    static void toggleDesktopWorkspace() {
        if (!DesktopRuntimeBridge.toggleDesktopWorkspace()) {
            showMagicDesk();
        }
    }

    static void probeExternalDisplay(
            final ExternalDisplayProbeCallback callback) {
        EXECUTOR.execute(() -> {
            final int wiredDisplayId =
                    ConsoleDisplayController.findExternalDisplayId();
            final int wirelessDisplayId =
                    ConsoleDisplayController.findWirelessDisplayId();
            NubiaHdmiModeController.Selection selection = null;
            DisplayProfileStore.Profile displayProfile = null;
            if (wiredDisplayId > 0) {
                final android.content.Context context =
                        MagicDeskApplication.applicationContext();
                displayProfile = DisplayProfileController
                        .prepareExternalProfile(context, wiredDisplayId);
                selection = NubiaHdmiModeController.readSelection(
                        context,
                        wiredDisplayId,
                        displayProfile == null
                                ? null : displayProfile.outputTiming);
            }
            if (callback != null) {
                callback.onComplete(
                        wiredDisplayId,
                        wirelessDisplayId,
                        displayProfile,
                        selection);
            }
        });
    }

    static void openTouchpad() {
        PhoneTouchpadController.open();
    }

    static boolean isTouchpadVisible() {
        return PhoneTouchpadController.isVisible();
    }

    static void restoreTouchpadIfMissing() {
        restoreTouchpadIfMissing(null);
    }

    static void restoreTouchpadIfMissing(
            final TouchpadRestoreCallback callback) {
        PhoneTouchpadController.restoreIfMissing(callback);
    }

    static void restorePhoneAfterExternalDesktop() {
        EXECUTOR.execute(() -> {
            PhoneDisplayGuard.disable();
            PhoneControlPanelLauncher.openOnPhoneWithShell();
        });
    }

    static void restorePrimaryPhoneHome() {
        EXECUTOR.execute(() -> runConsoleCommand(
                PhoneHomeRecoveryController.primaryHomeCommand()));
    }

    static void updateExternalTaskCaptionTarget(
            final int displayId,
            final boolean nubiaWiredDesktop) {
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                final NubiaCaptionVisibilityManager.Transport transport;
                if (displayId <= android.view.Display.DEFAULT_DISPLAY) {
                    transport = NubiaCaptionVisibilityManager.Transport.NONE;
                } else if (nubiaWiredDesktop) {
                    transport = NubiaCaptionVisibilityManager.Transport.WIRED;
                } else if (ConsoleDisplayController.findWirelessDisplayId()
                        == displayId) {
                    transport = NubiaCaptionVisibilityManager.Transport.WIRELESS;
                } else {
                    transport = NubiaCaptionVisibilityManager.Transport.NONE;
                }
                ConsoleSessionController
                        .setExternalTaskCaptionTransport(transport);
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
                    if (success && ShellAccess.isReady()) {
                        ConsoleSessionController
                                .setExternalTaskCaptionTransport(
                                        NubiaCaptionVisibilityManager.Transport.NONE);
                    }
                } finally {
                    DESKTOP_START_IN_PROGRESS.set(false);
                    if (callback != null) {
                        callback.onComplete(success);
                    }
                }
            }
        });
    }

    static void switchToMirrorWithControlPanel(
            final ResultCallback callback) {
        ControlActivity.finishActiveForMirrorTransition();
        switchToMirror(success -> {
            PhoneControlPanelLauncher.openOnPhoneWithShell();
            if (callback != null) {
                callback.onComplete(success);
            }
        });
    }

    static void disconnectWirelessDisplay(
            final ResultCallback callback) {
        ControlActivity.finishActiveForMirrorTransition();
        disconnectWirelessDisplayTransport(callback);
    }

    private static void disconnectWirelessDisplayTransport(
            final ResultCallback callback) {
        if (!DESKTOP_START_IN_PROGRESS.compareAndSet(false, true)) {
            Log.i(TAG, "Desktop transition is already in progress");
            if (callback != null) {
                callback.onComplete(false);
            }
            return;
        }
        EXECUTOR.execute(() -> {
            boolean success = false;
            try {
                success = WirelessDisplayController.disconnect()
                        && ConsoleDisplayController
                                .waitForWirelessDisplayStop();
                if (!success) {
                    Log.w(TAG, "Wireless display remained connected");
                } else if (ShellAccess.isReady()) {
                    ConsoleSessionController.setExternalTaskCaptionTransport(
                            NubiaCaptionVisibilityManager.Transport.NONE);
                }
            } catch (IOException error) {
                Log.w(TAG, "Wireless display disconnect failed", error);
            } finally {
                DESKTOP_START_IN_PROGRESS.set(false);
                if (callback != null) {
                    callback.onComplete(success);
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
                    final String output = ShellAccess.run(
                            AppProcessCommand.run(
                                    CONSOLE_TASK_RETURN_COMMAND,
                                    Integer.toString(displayId))).trim();
                    success = output.contains("tasks-returned=");
                    if (!success) {
                        Log.w(TAG, "Console task return failed output=" + output);
                    }
                } catch (IOException error) {
                    Log.w(TAG, "Console task return failed", error);
                } finally {
                    if (callback != null) {
                        callback.onComplete(success);
                    }
                }
            }
        });
    }

    static void showMagicDeskStart() {
        Log.i(TAG, "show MagicDesk Start overlay");
        if (!DesktopRuntimeBridge.showStart()
                && !MagicDeskRuntimeService.showStartIfRunning()) {
            Log.w(TAG, "MagicDesk desktop is unavailable for Start");
        }
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
        if (!ShellAccess.isReady()) {
            Log.w(TAG, "device lock unavailable; shizuku="
                    + ShellAccess.statusLabel());
            return;
        }
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                final String output = runConsoleCommand(
                        AppProcessCommand.run(
                                DEVICE_LOCK_COMMAND)).trim();
                if (!output.contains("device-locked")) {
                    Log.w(TAG, "device lock shortcut failed output="
                            + output.replace('\n', ' '));
                }
            }
        });
    }

    static void manageActiveWindow(final int shortcut) {
        if (!DesktopTaskController.handleActiveTaskShortcut(shortcut)) {
            Log.w(TAG, "window shortcut unavailable action=" + shortcut);
        }
    }

    static void showShortcutHelp() {
        if (!DesktopRuntimeBridge.toggleShortcutHelp()) {
            Log.w(TAG, "MagicDesk desktop is unavailable for shortcut help");
        }
    }

    static void toggleNotificationCenter() {
        if (!DesktopRuntimeBridge.toggleNotificationCenter()) {
            Log.w(TAG, "MagicDesk desktop is unavailable for notifications");
        }
    }

    static void toggleSystemPanel() {
        if (!DesktopRuntimeBridge.toggleSystemPanel()) {
            Log.w(TAG, "MagicDesk desktop is unavailable for system controls");
        }
    }

    static void captureScreenshot() {
        if (!ShellAccess.isReady()) {
            Log.w(TAG, "screenshot unavailable; shizuku="
                    + ShellAccess.statusLabel());
            return;
        }
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                captureScreenshotInternal();
            }
        });
    }

    static void toggleHardwareKeyboardLayout() {
        HardwareKeyboardLayoutController.toggle();
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
            final int displayId =
                    DesktopRuntimeBridge.getActiveDesktopDisplayId();
            if (displayId < 0) {
                throw new IOException("no active desktop display");
            }
            final String physicalDisplayId = displayId == 0
                    ? null
                    : ConsoleDisplayController.getPhysicalDisplayId(displayId);
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
            final String output = ShellAccess.run(command).trim();
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
                    "Could not capture the desktop display",
                    error.getMessage());
        }
    }

    static String runConsoleCommand(final String command) {
        if (!ShellAccess.isReady()) {
            return "";
        }
        try {
            return ShellAccess.run(command);
        } catch (IOException error) {
            Log.w(TAG, "Console command failed: " + command, error);
            return "";
        }
    }
}
