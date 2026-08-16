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

public final class ConsoleModeSwitcher {
    private static final String TAG = "MagicDeskConsoleSwitcher";
    private static final String CONSOLE_TASK_RETURN_COMMAND =
            "io.github.mekhontsev.magicdesk.ConsoleTaskReturnCommand";
    private static final String DEVICE_LOCK_COMMAND =
            "io.github.mekhontsev.magicdesk.DeviceLockCommand";
    private static final String SCREENSHOT_DIRECTORY =
            "/storage/emulated/0/Pictures/Screenshots";
    private static final AtomicBoolean DESKTOP_START_IN_PROGRESS = new AtomicBoolean();
    private static final AtomicBoolean DESKTOP_CLOSE_IN_PROGRESS = new AtomicBoolean();

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
                PlatformProjectionDriver.ModeSelection modeSelection);
    }

    static void setPhoneScreenOff(final boolean screenOff,
            final ResultCallback callback) {
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                boolean success = false;
                final boolean pointerCaptured = MagicDeskRuntime
                        .capturePointerPosition();
                if (pointerCaptured) {
                    MagicDeskRuntime
                            .restorePointerPositionOnNextMotion();
                }
                try {
                    success = PlatformDrivers.current().phoneUi()
                            .setPhoneScreenOff(screenOff);
                    Log.i(TAG, "Shell phone display off="
                            + screenOff + " success=" + success);
                    if (!success) {
                        CompatibilityDiagnostics.record(
                                "PHONE-SCREEN-002",
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
        if (DESKTOP_CLOSE_IN_PROGRESS.get()) {
            Log.i(TAG, "Desktop close is already in progress");
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
                    showPreferredDesktop(knownConsoleDisplayId);
                } finally {
                    DESKTOP_START_IN_PROGRESS.set(false);
                }
            }
        });
    }

    static void showDesktop(final DesktopDisplayTarget target) {
        if (target == null
                || target.displayId <= android.view.Display.DEFAULT_DISPLAY) {
            throw new IllegalArgumentException(
                    "a prepared external display target is required");
        }
        if (!DesktopDisplayDrivers.isSupported(target.kind)) {
            throw new IllegalStateException(
                    "display target is unsupported by the current platform");
        }
        if (DESKTOP_CLOSE_IN_PROGRESS.get()
                || !DESKTOP_START_IN_PROGRESS.compareAndSet(false, true)) {
            Log.i(TAG, "MagicDesk activation is already in progress");
            return;
        }
        EXECUTOR.execute(() -> {
            try {
                DesktopDisplayDrivers.forTarget(target)
                        .show(null, target.displayId);
            } finally {
                DESKTOP_START_IN_PROGRESS.set(false);
            }
        });
    }

    static void closeDesktop(
            final DesktopDisplayTarget target,
            final boolean restorePhonePanel,
            final ResultCallback callback) {
        if (target == null) {
            complete(callback, false);
            return;
        }
        if (!DESKTOP_CLOSE_IN_PROGRESS.compareAndSet(false, true)) {
            Log.i(TAG, "Desktop close is already in progress");
            complete(callback, false);
            return;
        }
        DesktopTaskController.disableExternalTaskMigrationProtection();
        final Runnable close = () -> {
            try {
                DesktopDisplayDrivers.forTarget(target).close(
                        target,
                        restorePhonePanel,
                        success -> finishDesktopClose(
                                callback, success));
            } catch (RuntimeException error) {
                Log.w(TAG, "Desktop close failed", error);
                finishDesktopClose(callback, false);
            }
        };
        if (restorePhonePanel
                && target.displayId > android.view.Display.DEFAULT_DISPLAY) {
            DesktopTaskParkingController.park(target, parked -> {
                if (!parked) {
                    Log.w(TAG, "Desktop close continues after partial task parking");
                }
                close.run();
            });
        } else {
            close.run();
        }
    }

    private static void finishDesktopClose(
            final ResultCallback callback,
            final boolean success) {
        DESKTOP_CLOSE_IN_PROGRESS.set(false);
        if (!success) {
            DesktopTaskController.restoreExternalTaskMigrationProtection();
        }
        complete(callback, success);
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
        final boolean wiredSupported = DesktopDisplayDrivers.isSupported(
                DesktopDisplayTarget.Kind.WIRED);
        final boolean wirelessSupported = DesktopDisplayDrivers.isSupported(
                DesktopDisplayTarget.Kind.WIRELESS);
        if (wiredSupported
                && (knownConsoleDisplayId > android.view.Display.DEFAULT_DISPLAY
                || PlatformDrivers.current().projection()
                        .activeDesktopDisplayId(
                                MagicDeskApplication.applicationContext())
                        > android.view.Display.DEFAULT_DISPLAY
                || ConsoleDisplayController.findExternalDisplayId()
                        > android.view.Display.DEFAULT_DISPLAY)) {
            DesktopDisplayDrivers
                    .forKind(DesktopDisplayTarget.Kind.WIRED)
                    .show(null, knownConsoleDisplayId);
            return;
        }
        final int wirelessDisplayId =
                ConsoleDisplayController.findWirelessDisplayId();
        if (wirelessSupported
                && wirelessDisplayId > android.view.Display.DEFAULT_DISPLAY) {
            DesktopDisplayDrivers
                    .forKind(DesktopDisplayTarget.Kind.WIRELESS)
                    .show(null, wirelessDisplayId);
            return;
        }
        Log.i(TAG, "No connected external display is available");
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
            PlatformProjectionDriver.ModeSelection selection = null;
            DisplayProfileStore.Profile displayProfile = null;
            if (wiredDisplayId > 0) {
                final android.content.Context context =
                        MagicDeskApplication.applicationContext();
                displayProfile = DisplayProfileController
                        .prepareExternalProfile(context, wiredDisplayId);
                selection = PlatformDrivers.current().projection()
                        .readExternalDisplayModes(
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
            PlatformDrivers.current().phoneUi().setPhoneScreenOff(false);
            PhoneControlPanelLauncher.openOnPhoneWithShell();
        });
    }

    static void restorePrimaryPhoneHome() {
        EXECUTOR.execute(() -> runConsoleCommand(
                PhoneHomeRecoveryController.primaryHomeCommand()));
    }

    static void updateExternalTaskCaptionTarget(
            final int displayId,
            final boolean wiredDesktop) {
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                final PlatformProjectionDriver.Transport transport;
                if (displayId <= android.view.Display.DEFAULT_DISPLAY) {
                    transport = PlatformProjectionDriver.Transport.NONE;
                } else if (wiredDesktop) {
                    transport = PlatformProjectionDriver.Transport.WIRED;
                } else if (ConsoleDisplayController.findWirelessDisplayId()
                        == displayId) {
                    transport = PlatformProjectionDriver.Transport.WIRELESS;
                } else {
                    transport = PlatformProjectionDriver.Transport.NONE;
                }
                PlatformDrivers.current().projection()
                        .setCaptionTransport(transport);
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
        EXECUTOR.execute(() -> {
                    boolean success = false;
                    try {
                        if (getActiveConsoleDisplayId() <= 0) {
                            success = true;
                        } else if (PlatformDrivers.current().projection()
                                .requestMirrorMode()) {
                            success = PlatformDrivers.current().projection()
                                    .waitForDesktopStop(
                                            MagicDeskApplication
                                                    .applicationContext());
                            if (!success) {
                                Log.w(TAG,
                                        "Console display remained active after Mirror request");
                            }
                        }
                        if (success && ShellAccess.isReady()) {
                            PlatformDrivers.current().projection()
                                    .setCaptionTransport(
                                            PlatformProjectionDriver
                                                    .Transport.NONE);
                        }
                    } finally {
                        DESKTOP_START_IN_PROGRESS.set(false);
                        if (callback != null) {
                            callback.onComplete(success);
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

    static void returnConsoleTasksToPhone(
            final DesktopDisplayTarget target,
            final ResultCallback callback) {
        DesktopTaskController.disableExternalTaskMigrationProtection();
        EXECUTOR.execute(new Runnable() {
            @Override
            public void run() {
                boolean success = false;
                try {
                    final int displayId = target != null
                            && target.displayId > 0
                            ? target.displayId : getActiveConsoleDisplayId();
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
                    if (!success) {
                        DesktopTaskController
                                .restoreExternalTaskMigrationProtection();
                    }
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
                && !MagicDeskRuntime.showStart()) {
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

    static void openSettings() {
        if (!DesktopRuntimeBridge.openSettings()) {
            Log.w(TAG, "MagicDesk desktop is unavailable for settings");
        }
    }

    static void captureScreenshot() {
        if (!ShellAccess.isReady()) {
            Log.w(TAG, "screenshot unavailable; shizuku="
                    + ShellAccess.statusLabel());
            CaptureDiagnostics.recordScreenshot(
                    false,
                    "Shizuku unavailable: " + ShellAccess.statusLabel());
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

    public static void executeSerialized(final Runnable action) {
        EXECUTOR.execute(action);
    }

    private static String shellQuote(final String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    static int getActiveConsoleDisplayId() {
        return PlatformDrivers.current().projection()
                .activeDesktopDisplayId(
                        MagicDeskApplication.applicationContext());
    }

    private static void captureScreenshotInternal() {
        String path = null;
        DesktopCaptureTarget capture = null;
        try {
            capture = DesktopCaptureTarget.resolveActive();
            final String physicalDisplayId = capture.desktopDisplayId == 0
                    ? null : capture.physicalDisplayId;
            final String fileName = "MagicDesk_"
                    + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
                            .format(new Date())
                    + ".png";
            path = SCREENSHOT_DIRECTORY + "/" + fileName;
            final String displayArgument = physicalDisplayId == null
                    ? "" : "-d " + physicalDisplayId + " ";
            Log.i(TAG, "screenshot capture starting path=" + path
                    + " " + capture.diagnosticDetail());
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
            CaptureDiagnostics.recordScreenshot(
                    true, capture.diagnosticDetail());
        } catch (IOException | RuntimeException error) {
            Log.w(TAG, "screenshot failed path=" + path, error);
            final String detail = (capture == null
                    ? "capture target unavailable"
                    : capture.diagnosticDetail())
                    + ", error=" + error.getMessage();
            CaptureDiagnostics.recordScreenshot(false, detail);
            CompatibilityDiagnostics.record(
                    "SCREENSHOT-001",
                    "Could not capture the desktop display",
                    (capture == null ? "capture target unavailable"
                            : capture.diagnosticDetail())
                            + ", path=" + path
                            + ", error=" + error.getMessage(),
                    error);
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
