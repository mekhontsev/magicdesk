package io.github.mekhontsev.magicdesk;

import android.util.Log;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class DesktopOperations {
    private static final String TAG = "MagicDeskDesktopOps";
    private static final String DESKTOP_TASK_RETURN_COMMAND =
            "io.github.mekhontsev.magicdesk.DesktopTaskReturnCommand";
    private static final String DEVICE_LOCK_COMMAND =
            "io.github.mekhontsev.magicdesk.DeviceLockCommand";
    private static final String SCREENSHOT_DIRECTORY =
            "/storage/emulated/0/Pictures/Screenshots";
    private static final SerializedDesktopOperationQueue OPERATIONS =
            new SerializedDesktopOperationQueue();
    private static final PlatformDriver PLATFORM = PlatformDrivers.current();
    private static final PlatformProjectionDriver PROJECTION =
            PLATFORM.projection();
    private static final PlatformPhoneUiDriver PHONE_UI = PLATFORM.phoneUi();
    private static final DesktopSessionTransitionCoordinator TRANSITIONS =
            new DesktopSessionTransitionCoordinator(
                    OPERATIONS,
                    PLATFORM.features(),
                    PROJECTION);

    private DesktopOperations() {
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
        OPERATIONS.execute(new Runnable() {
            @Override
            public void run() {
                boolean success = false;
                try {
                    final int desktopDisplayId = screenOff
                            ? DesktopRuntimeBridge
                                    .getActiveDesktopDisplayId()
                            : android.view.Display.INVALID_DISPLAY;
                    success = PHONE_UI.setPhoneScreenOff(
                            screenOff, desktopDisplayId);
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
        TRANSITIONS.showPreferredDesktop();
    }

    static void showWiredDesktop() {
        TRANSITIONS.showWiredDesktop();
    }

    static void showWiredDesktop(final DesktopSessionPolicy policy) {
        TRANSITIONS.showWiredDesktop(policy);
    }

    static void showDesktop(final DesktopDisplayTarget target) {
        TRANSITIONS.showDesktop(target);
    }

    static void showDesktop(
            final DesktopDisplayTarget target,
            final DesktopSessionPolicy policy) {
        TRANSITIONS.showDesktop(target, policy);
    }

    static void recoverDesktopSession(
            final DesktopDisplayTarget target,
            final DesktopSessionPolicy policy,
            final ResultCallback callback) {
        OPERATIONS.execute(() -> {
            boolean success = false;
            try {
                final DesktopHomeRoleLease.State lease =
                        DesktopHomeRoleLease.snapshot();
                if (lease != null
                        && lease.phase == DesktopHomeRoleLease.Phase.ACTIVE
                        && lease.matches(target)) {
                    success = DesktopSessionController.show(
                            target, policy).ready;
                }
            } catch (IOException | RuntimeException error) {
                Log.w(TAG, "Desktop session recovery failed", error);
                CompatibilityDiagnostics.record(
                        "DESKTOP-HOME-006",
                        "Could not recover the desktop Home session",
                        "display=" + (target == null ? -1 : target.displayId)
                                + " error=" + error.getMessage(),
                        error);
            }
            if (callback != null) {
                callback.onComplete(success);
            }
        });
    }

    static void closeDesktop(
            final DesktopDisplayTarget target,
            final boolean restorePhonePanel,
            final ResultCallback callback) {
        TRANSITIONS.closeDesktop(
                target,
                restorePhonePanel,
                callback == null ? null : callback::onComplete);
    }

    static void toggleDesktopWorkspace() {
        if (!MagicDeskRuntime.toggleDesktopWorkspace()) {
            showMagicDesk();
        }
    }

    static void probeExternalDisplay(
            final ExternalDisplayProbeCallback callback) {
        OPERATIONS.execute(() -> {
            final int wiredDisplayId =
                    ExternalDisplayController.findExternalDisplayId();
            final int wirelessDisplayId =
                    ExternalDisplayController.findWirelessDisplayId();
            PlatformProjectionDriver.ModeSelection selection = null;
            DisplayProfileStore.Profile displayProfile = null;
            if (wiredDisplayId > 0) {
                final android.content.Context context =
                        MagicDeskApplication.applicationContext();
                displayProfile = DisplayProfileController
                        .prepareExternalProfile(context, wiredDisplayId);
                selection = PROJECTION.readExternalDisplayModes(
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
        OPERATIONS.execute(() -> {
            PHONE_UI.setPhoneScreenOff(
                    false, android.view.Display.INVALID_DISPLAY);
            PhoneControlPanelLauncher.openOnPhoneWithShell();
        });
    }

    static void updateExternalTaskCaptionTarget(
            final DesktopDisplayTarget target) {
        TRANSITIONS.updateCaptionTransport(target);
    }

    static void returnDesktopTasksToPhone(
            final DesktopDisplayTarget target,
            final ResultCallback callback) {
        MagicDeskRuntime.disableExternalTaskMigrationProtection();
        OPERATIONS.execute(new Runnable() {
            @Override
            public void run() {
                boolean success = false;
                try {
                    final int displayId = target != null
                            && target.displayId > 0
                            ? target.displayId
                            : DesktopRuntimeBridge
                                    .getActiveDesktopDisplayId();
                    if (displayId <= 0) {
                        success = true;
                        return;
                    }
                    final String output = ShellAccess.run(
                            AppProcessCommand.run(
                                    DESKTOP_TASK_RETURN_COMMAND,
                                    Integer.toString(displayId))).trim();
                    success = output.contains("tasks-returned=");
                    if (!success) {
                        Log.w(TAG, "Desktop task return failed output=" + output);
                    }
                } catch (IOException error) {
                    Log.w(TAG, "Desktop task return failed", error);
                } finally {
                    if (!success) {
                        MagicDeskRuntime
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
        if (!MagicDeskRuntime.showStart()) {
            Log.w(TAG, "MagicDesk desktop is unavailable for Start");
        }
    }

    static void advanceAltTab(final boolean reverse) {
        if (!MagicDeskRuntime.advanceAltTab(reverse)) {
            Log.w(TAG, "MagicDesk desktop is unavailable for Alt+Tab");
        }
    }

    static void finishAltTab() {
        if (!MagicDeskRuntime.finishAltTab()) {
            Log.w(TAG, "MagicDesk desktop is unavailable for Alt+Tab completion");
        }
    }

    static void cancelAltTab() {
        MagicDeskRuntime.cancelAltTab();
    }

    static void sendSystemBack() {
        if (!MagicDeskRuntime.sendSystemBack()) {
            Log.w(TAG, "system Back shortcut unavailable");
        }
    }

    static void lockDevice() {
        if (!ShellAccess.isReady()) {
            Log.w(TAG, "device lock unavailable; shizuku="
                    + ShellAccess.statusLabel());
            return;
        }
        OPERATIONS.execute(new Runnable() {
            @Override
            public void run() {
                final String output = runShellCommand(
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
        if (!MagicDeskRuntime.handleActiveTaskShortcut(shortcut)) {
            Log.w(TAG, "window shortcut unavailable action=" + shortcut);
        }
    }

    static void showShortcutHelp() {
        if (!MagicDeskRuntime.toggleShortcutHelp()) {
            Log.w(TAG, "MagicDesk desktop is unavailable for shortcut help");
        }
    }

    static void toggleNotificationCenter() {
        if (!MagicDeskRuntime.toggleNotificationCenter()) {
            Log.w(TAG, "MagicDesk desktop is unavailable for notifications");
        }
    }

    static void toggleSystemPanel() {
        if (!MagicDeskRuntime.toggleSystemPanel()) {
            Log.w(TAG, "MagicDesk desktop is unavailable for system controls");
        }
    }

    static void openSettings() {
        if (!MagicDeskRuntime.openSettings()) {
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
        OPERATIONS.execute(new Runnable() {
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
        OPERATIONS.execute(action);
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
                    + "/system/bin/mkdir -p "
                    + ShellCommandLine.quote(SCREENSHOT_DIRECTORY)
                    + " && /system/bin/screencap " + displayArgument
                    + "-p " + ShellCommandLine.quote(path)
                    + " && /system/bin/test -s " + ShellCommandLine.quote(path)
                    + " && /system/bin/chmod 0664 " + ShellCommandLine.quote(path)
                    + " && /system/bin/am broadcast --user 0"
                    + " -a android.intent.action.MEDIA_SCANNER_SCAN_FILE"
                    + " -d " + ShellCommandLine.quote("file://" + path)
                    + " >/dev/null"
                    + " && echo "
                    + ShellCommandLine.quote("screenshot-saved=" + path);
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

    static String runShellCommand(final String command) {
        if (!ShellAccess.isReady()) {
            return "";
        }
        try {
            return ShellAccess.run(command);
        } catch (IOException error) {
            Log.w(TAG, "Desktop command failed: " + command, error);
            return "";
        }
    }
}
