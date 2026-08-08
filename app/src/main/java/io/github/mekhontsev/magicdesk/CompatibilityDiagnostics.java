package io.github.mekhontsev.magicdesk;

import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Display;
import android.view.InputDevice;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

final class CompatibilityDiagnostics {
    private static final Object LOCK = new Object();
    private static final String EVENT_FILE = "compatibility-events.log";
    private static final long MAX_EVENT_FILE_BYTES = 128 * 1024;
    private static final int MAX_EVENT_DETAIL_CHARS = 2_000;
    private static final int MAX_LOGCAT_CHARS = 48_000;
    private static final long DUPLICATE_EVENT_WINDOW_MILLIS = 30_000;
    private static volatile Context sApplicationContext;
    private static String sLastEventSignature = "";
    private static long sLastEventTime;

    private CompatibilityDiagnostics() {
    }

    static void initialize(final Context context) {
        if (context == null) {
            return;
        }
        sApplicationContext = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        if (previous instanceof CrashHandler) {
            return;
        }
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(previous));
    }

    static void record(final String code, final String userMessage,
            final String technicalDetail) {
        record(code, userMessage, technicalDetail, null);
    }

    static void record(final String code, final String userMessage,
            final String technicalDetail, final Throwable error) {
        final Context context = sApplicationContext;
        if (context == null) {
            return;
        }
        final StringBuilder entry = new StringBuilder();
        entry.append(utcNow())
                .append(" | ")
                .append(cleanSingleLine(code, 80))
                .append(" | ")
                .append(cleanSingleLine(userMessage, 400));
        if (!TextUtils.isEmpty(technicalDetail)) {
            entry.append(" | ").append(cleanMultiline(
                    technicalDetail, MAX_EVENT_DETAIL_CHARS));
        }
        if (error != null) {
            entry.append(" | ").append(cleanMultiline(
                    stackTrace(error), MAX_EVENT_DETAIL_CHARS));
        }
        entry.append('\n');

        synchronized (LOCK) {
            final long now = System.currentTimeMillis();
            final String signature = cleanSingleLine(code, 80)
                    + '\n' + cleanSingleLine(userMessage, 400)
                    + '\n' + cleanMultiline(technicalDetail, MAX_EVENT_DETAIL_CHARS);
            if (signature.equals(sLastEventSignature)
                    && now - sLastEventTime < DUPLICATE_EVENT_WINDOW_MILLIS) {
                return;
            }
            sLastEventSignature = signature;
            sLastEventTime = now;
            final File file = new File(context.getFilesDir(), EVENT_FILE);
            if (file.length() > MAX_EVENT_FILE_BYTES) {
                final File previousFile =
                        new File(context.getFilesDir(), EVENT_FILE + ".previous");
                if (previousFile.exists()) {
                    previousFile.delete();
                }
                file.renameTo(previousFile);
            }
            try (OutputStreamWriter writer = new OutputStreamWriter(
                    new FileOutputStream(file, true), StandardCharsets.UTF_8)) {
                writer.write(entry.toString());
            } catch (IOException ignored) {
                // Diagnostics must never become a second failure path.
            }
        }
    }

    static String buildReport(final Context context) {
        final Context appContext = context.getApplicationContext();
        final DeviceSetupManager.Audit audit =
                DeviceSetupManager.audit(appContext, SessionProfile.load(appContext));
        final StringBuilder report = new StringBuilder(24_000);
        report.append("# MagicDesk compatibility report\n\n")
                .append("Report format: 1\n")
                .append("Generated UTC: ").append(utcNow()).append('\n')
                .append("App: ").append(appVersion(appContext)).append('\n')
                .append("Package: ").append(appContext.getPackageName()).append('\n')
                .append('\n');

        appendDevice(report);
        appendCompatibility(report, appContext, audit);
        appendShizukuProbe(report, audit);
        DesktopSelfTestResult.appendLastResult(report, appContext);
        appendDisplays(report, appContext);
        appendInputDevices(report);
        appendEvents(report, appContext);
        appendMagicDeskLogcat(report);
        report.append("\n## Privacy note\n")
                .append("This report omits notification contents, user files, account data, ")
                .append("and the installed-app list. It may contain Android package names ")
                .append("and task/display identifiers from MagicDesk error logs.\n");
        return report.toString();
    }

    static void clearEvents(final Context context) {
        synchronized (LOCK) {
            new File(context.getFilesDir(), EVENT_FILE).delete();
            new File(context.getFilesDir(), EVENT_FILE + ".previous").delete();
        }
    }

    private static void appendDevice(final StringBuilder report) {
        report.append("## Device\n")
                .append("Manufacturer: ").append(Build.MANUFACTURER).append('\n')
                .append("Brand: ").append(Build.BRAND).append('\n')
                .append("Model: ").append(Build.MODEL).append('\n')
                .append("Device: ").append(Build.DEVICE).append('\n')
                .append("Product: ").append(Build.PRODUCT).append('\n')
                .append("Android: ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
                .append("Security patch: ").append(Build.VERSION.SECURITY_PATCH).append('\n')
                .append("Build ID: ").append(Build.DISPLAY).append('\n')
                .append("Incremental: ").append(Build.VERSION.INCREMENTAL).append('\n')
                .append("Fingerprint: ").append(Build.FINGERPRINT).append('\n')
                .append("ABIs: ").append(String.join(", ", Build.SUPPORTED_ABIS)).append("\n\n");
    }

    private static void appendCompatibility(final StringBuilder report,
            final Context context, final DeviceSetupManager.Audit audit) {
        final SessionProfile profile = audit.sessionProfile == null
                ? SessionProfile.load(context) : audit.sessionProfile;
        report.append("## Runtime profile\n")
                .append("Shizuku runtime: ")
                .append(ShellAccess.statusLabel()).append('\n')
                .append("Display target: ").append(profile.displayWireName()).append('\n')
                .append("System provisioning: ")
                .append(audit.configurationReady ? "ready" : "incomplete").append('\n')
                .append("Reboot pending: ").append(audit.rebootRequired).append("\n\n")
                .append("## Capability checks\n");
        appendCheck(report, "PLATFORM-001", audit.compatibleDevice,
                "ZTE/nubia device running Android 16 or newer",
                audit.manufacturer + " " + audit.model);
        appendCheck(report, "PROFILE-001",
                audit.firmwareSupport
                        != DeviceSetupManager.FirmwareSupport.UNVERIFIED,
                "Firmware compatibility profile",
                firmwareSupportDetail(audit.firmwareSupport));
        final boolean shellReady = audit.shellReady;
        appendCheck(report, "SHIZUKU-001",
                shellReady,
                "Shizuku command service",
                shellReady
                        ? "API " + audit.shellState.version
                                + ", service uid=" + audit.shellState.uid
                        : audit.runtimeError);
        appendCheck(report, "WM-FREEFORM-001", audit.freeformEnabled,
                "Freeform support setting",
                expectedValue("1", audit.freeformValue));
        appendCheck(report, "WM-RESIZE-001", audit.resizableEnabled,
                "Force resizable activities setting",
                expectedValue("1", audit.resizableValue));
        appendCheck(report, "WM-ELIGIBILITY-001", audit.restrictionsDisabled,
                "Desktop-mode device restriction property",
                expectedValue("false", audit.restrictionsValue));
        appendCheck(report, "WM-CORNERS-001", audit.roundedCornersDisabled,
                "Desktop rounded-corner property",
                expectedValue("false", audit.roundedCornersValue));
        appendCheck(report, "OVERLAY-001", Settings.canDrawOverlays(context),
                "Application overlays", Settings.canDrawOverlays(context)
                        ? "granted" : "not granted");

        final NotificationManager notifications =
                context.getSystemService(NotificationManager.class);
        final boolean listenerGranted = notifications != null
                && notifications.isNotificationListenerAccessGranted(
                        DesktopNotificationListenerService.getComponentName(context));
        appendCheck(report, "NOTIFICATIONS-001", listenerGranted,
                "Notification-listener access",
                listenerGranted ? "granted" : "optional permission not granted");
        final DesktopNotificationListenerService.Snapshot notificationSnapshot =
                DesktopNotificationListenerService.getSnapshot();
        appendCheck(report, "NOTIFICATIONS-005", notificationSnapshot.connected,
                "Notification-listener binding",
                notificationSnapshot.connected ? "connected"
                        : TextUtils.isEmpty(notificationSnapshot.connectionIssueCode)
                                ? "not connected"
                                : notificationSnapshot.connectionIssueCode);
        final boolean taskControl = ShellAccess.isReady();
        final boolean nativeDesktopRequired =
                taskControl && audit.configurationReady;
        final boolean privilegedTransactions =
                ShellAccess.isReady();
        final boolean nativeDesktopAvailable =
                nativeDesktopRequired && NativeDesktopController.isAvailable();
        appendCheck(report, "NATIVE-DESKTOP-001",
                !nativeDesktopRequired
                        || nativeDesktopAvailable
                        || privilegedTransactions,
                "Desktop task transition backend",
                nativeDesktopAvailable
                        ? "wmshell-passthrough desktopmode"
                        : privilegedTransactions
                                ? "direct WindowContainerTransaction fallback"
                        : taskControl
                                ? "privileged transaction backend unavailable"
                                : "Shizuku runtime unavailable");
        appendCheck(report, "NUBIA-INPUT-001",
                hasPackage(context, "cn.nubia.keymapcenter"),
                "Nubia mirror input package", "cn.nubia.keymapcenter");
        appendCheck(report, "NUBIA-LAUNCHER-001",
                hasPackage(context, "com.zte.mifavor.launcher"),
                "ZTE launcher package", "com.zte.mifavor.launcher");
        final boolean globalInput = ShellAccess.isReady();
        appendCheck(report, "SHORTCUTS-001",
                !globalInput || KeyboardShortcutWatcher.isFullShortcutMode(),
                "Global keyboard/input bridge",
                globalInput
                        ? (KeyboardShortcutWatcher.isFullShortcutMode()
                                ? "running" : "not running")
                        : "Shizuku runtime unavailable");
        final boolean shellRightClick = ShellAccess.isReady();
        final boolean mouseBridgeExpected =
                shellRightClick
                        && DesktopRuntimeBridge
                                .getActiveDesktopDisplayId() > 0;
        final boolean mouseBridgeReady =
                MagicDeskRuntimeService
                        .isDesktopMouseBridgeReadyIfRunning();
        final String mouseBridgeDetail;
        if (!shellRightClick) {
            mouseBridgeDetail =
                    "Shizuku runtime unavailable";
        } else if (!mouseBridgeExpected) {
            mouseBridgeDetail =
                    "idle; an external desktop is required";
        } else {
            mouseBridgeDetail =
                    mouseBridgeReady ? "running" : "not running";
        }
        appendCheck(report, "INPUT-MOUSE-001",
                !mouseBridgeExpected
                        || mouseBridgeReady,
                "Global right-click bridge",
                mouseBridgeDetail);
        report.append("Shell command access: ")
                .append(ShellAccess.isReady()).append('\n');
        report.append("REDMAGIC charge separation: package=")
                .append(ChargeSeparationController.isSupported(context))
                .append(", enabled=")
                .append(Settings.Global.getInt(
                        context.getContentResolver(),
                        ChargeSeparationController.SETTING,
                        0) == 1)
                .append('\n');
        final RedmagicHardwareSnapshot hardware =
                RedmagicHardwareController.snapshot();
        report.append("REDMAGIC hardware: fan=")
                .append(hardware.fanAvailable)
                .append(" enabled=").append(hardware.fanEnabled)
                .append(", pump=").append(hardware.pumpAvailable)
                .append(" enabled=").append(hardware.pumpEnabled)
                .append(" speed=").append(hardware.pumpSpeed)
                .append(", cpuMilliC=").append(hardware.cpuMilliCelsius)
                .append(", gpuMilliC=").append(hardware.gpuMilliCelsius)
                .append(", skinMilliC=").append(hardware.skinMilliCelsius)
                .append(", batteryMilliC=")
                .append(hardware.batteryMilliCelsius)
                .append('\n');
        report.append("Console display setting: ")
                .append(Settings.Global.getString(
                        context.getContentResolver(), "app_mirror_displayid"))
                .append('\n');
        final ExternalDisplayLaunchSettings.Config displayConfig =
                ExternalDisplayLaunchSettings.load(context);
        report.append("External display launch: fill=")
                .append(displayConfig.fillDisplay)
                .append(", output=")
                .append(displayConfig.outputTiming == null
                        ? "native" : displayConfig.outputTiming)
                .append('\n')
                .append("Nubia projection settings: fit=")
                .append(Settings.Global.getString(
                        context.getContentResolver(), "app_mirror_fit_status"))
                .append(", sizeType=")
                .append(Settings.Global.getString(
                        context.getContentResolver(), "app_mirror_size_type"))
                .append(", support=")
                .append(Settings.Global.getString(
                        context.getContentResolver(), "nb_app_mirror_support_fit"))
                .append(", current=")
                .append(Settings.Global.getString(
                        context.getContentResolver(), "nb_app_mirror_now_fit"))
                .append("\n\n");
    }

    private static void appendDisplays(final StringBuilder report, final Context context) {
        report.append("## Displays\n");
        final DisplayManager manager = context.getSystemService(DisplayManager.class);
        if (manager == null) {
            report.append("DisplayManager unavailable\n\n");
            return;
        }
        final Display[] displays = manager.getDisplays();
        if (displays.length == 0) {
            report.append("No displays reported\n\n");
            return;
        }
        for (final Display display : displays) {
            final android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
            display.getRealMetrics(metrics);
            final Display.Mode mode = display.getMode();
            report.append("- id=").append(display.getDisplayId())
                    .append(" name=").append(cleanSingleLine(display.getName(), 120))
                    .append(" flags=0x").append(Integer.toHexString(display.getFlags()))
                    .append(" state=").append(display.getState())
                    .append(" size=").append(metrics.widthPixels)
                    .append('x').append(metrics.heightPixels)
                    .append(" dpi=").append(metrics.densityDpi)
                    .append(" rotation=").append(display.getRotation());
            if (mode != null) {
                report.append(" mode=")
                        .append(mode.getPhysicalWidth())
                        .append('x')
                        .append(mode.getPhysicalHeight())
                        .append('@')
                        .append(Math.round(mode.getRefreshRate()));
            }
            report
                    .append('\n');
        }
        report.append('\n');
    }

    private static void appendShizukuProbe(
            final StringBuilder report,
            final DeviceSetupManager.Audit audit) {
        if (!audit.shellReady) {
            return;
        }
        report.append("## Shizuku capability probe\n");
        try {
            report.append(ShellAccess.probeCapabilities());
        } catch (IOException | RuntimeException error) {
            report.append("probe=failed | ")
                    .append(cleanSingleLine(
                            error.getMessage() == null
                                    ? error.getClass().getSimpleName()
                                    : error.getMessage(),
                            600))
                    .append('\n');
        }
        report.append('\n');
    }

    private static void appendInputDevices(final StringBuilder report) {
        report.append("## External input devices\n");
        boolean found = false;
        for (final int id : InputDevice.getDeviceIds()) {
            final InputDevice device = InputDevice.getDevice(id);
            if (device == null || (!device.isExternal() && !device.isVirtual())) {
                continue;
            }
            found = true;
            report.append("- id=").append(id)
                    .append(" name=").append(cleanSingleLine(device.getName(), 160))
                    .append(" external=").append(device.isExternal())
                    .append(" virtual=").append(device.isVirtual())
                    .append(" keyboardType=").append(device.getKeyboardType())
                    .append(" sources=0x")
                    .append(Integer.toHexString(device.getSources()))
                    .append('\n');
        }
        if (!found) {
            report.append("None reported\n");
        }
        report.append('\n');
    }

    private static void appendEvents(final StringBuilder report, final Context context) {
        report.append("## Recorded compatibility events\n");
        final String previous = filterStaticAuditEvents(readFile(
                new File(context.getFilesDir(), EVENT_FILE + ".previous"), 32_000));
        final String current = filterStaticAuditEvents(
                readFile(new File(context.getFilesDir(), EVENT_FILE), 64_000));
        if (previous.isEmpty() && current.isEmpty()) {
            report.append("No recorded events\n");
        } else {
            if (!previous.isEmpty()) {
                report.append(previous);
            }
            if (!current.isEmpty()) {
                report.append(current);
            }
        }
        report.append('\n');
    }

    static String filterStaticAuditEvents(final String events) {
        if (events == null || events.isEmpty()) {
            return "";
        }
        final StringBuilder filtered = new StringBuilder(events.length());
        for (final String line : events.split("(?<=\\n)")) {
            if (line.contains(" | PLATFORM-001 | ")
                    || line.contains(" | PROFILE-001 | ")
                    || line.contains(" | SHIZUKU-001 | ")) {
                continue;
            }
            filtered.append(line);
        }
        return filtered.toString();
    }

    private static void appendMagicDeskLogcat(final StringBuilder report) {
        report.append("## Recent MagicDesk logcat\n");
        final String command = "/system/bin/logcat -d -v threadtime -t 600 "
                + "MagicDesk:V MagicDeskConsoleSwitcher:V MagicDeskFreeform:V "
                + "MagicDeskNativeDesktop:V MagicDeskNotifications:V MagicDeskPanels:V "
                + "MagicDeskProfiles:V MagicDeskRightButton:V MagicDeskKeys:V "
                + "MagicDeskSetup:V MagicDeskTaskReuse:V MagicDeskTasks:V "
                + "MagicDeskWallpaper:V MagicDeskWatcher:V '*:S'";
        final String output = runCommand(command, MAX_LOGCAT_CHARS);
        report.append(output.isEmpty() ? "No MagicDesk log entries available\n" : output);
        if (!output.endsWith("\n")) {
            report.append('\n');
        }
    }

    private static void appendCheck(final StringBuilder report, final String code,
            final boolean passed, final String label, final String detail) {
        report.append(passed ? "PASS" : "WARN")
                .append(" [").append(code).append("] ")
                .append(label).append(": ")
                .append(cleanSingleLine(detail, 500))
                .append('\n');
    }

    private static String expectedValue(final String expected, final String actual) {
        return "expected=" + expected + ", actual="
                + (TextUtils.isEmpty(actual) ? "<empty>" : actual);
    }

    private static String firmwareSupportDetail(
            final DeviceSetupManager.FirmwareSupport support) {
        switch (support) {
            case MAINTAINER_VERIFIED:
                return "maintainer-verified REDMAGIC 11 Pro / NX809J / "
                        + "20260204.221845";
            case COMMUNITY_TESTED:
                return "community-tested REDMAGIC 11 Pro / NX809J-UN / "
                        + "20260625.022314";
            case UNVERIFIED:
            default:
                return "unverified model or firmware; capability probing is required";
        }
    }

    private static boolean hasPackage(final Context context, final String packageName) {
        try {
            context.getPackageManager().getApplicationInfo(
                    packageName, PackageManager.MATCH_DISABLED_COMPONENTS);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static String appVersion(final Context context) {
        try {
            final PackageInfo info = context.getPackageManager().getPackageInfo(
                    context.getPackageName(), 0);
            return info.versionName + " (" + info.getLongVersionCode() + ")";
        } catch (PackageManager.NameNotFoundException e) {
            return "unknown";
        }
    }

    private static String readFile(final File file, final int maxChars) {
        if (!file.isFile()) {
            return "";
        }
        final StringBuilder value = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            final char[] buffer = new char[2_048];
            int read;
            while ((read = reader.read(buffer)) >= 0 && value.length() < maxChars) {
                value.append(buffer, 0, Math.min(read, maxChars - value.length()));
            }
        } catch (IOException ignored) {
            return "";
        }
        return value.toString();
    }

    private static String runCommand(final String command, final int maxChars) {
        try {
            final String output = ShellAccess.run(command);
            return output.length() <= maxChars
                    ? output : output.substring(0, maxChars);
        } catch (IOException e) {
            return "Probe failed: " + cleanSingleLine(e.getMessage(), 500) + '\n';
        }
    }

    private static String cleanSingleLine(final String value, final int maxLength) {
        if (value == null) {
            return "";
        }
        return cleanMultiline(value, maxLength)
                .replace('\n', ' ')
                .replace('\r', ' ')
                .trim();
    }

    private static String cleanMultiline(final String value, final int maxLength) {
        if (value == null) {
            return "";
        }
        final String normalized = value.replace("\u0000", "");
        return normalized.length() <= maxLength
                ? normalized : normalized.substring(0, maxLength) + "...";
    }

    private static String stackTrace(final Throwable error) {
        final StringWriter value = new StringWriter();
        error.printStackTrace(new PrintWriter(value));
        return value.toString();
    }

    private static String utcNow() {
        final SimpleDateFormat format =
                new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    private static final class CrashHandler implements Thread.UncaughtExceptionHandler {
        private final Thread.UncaughtExceptionHandler mPrevious;

        CrashHandler(final Thread.UncaughtExceptionHandler previous) {
            mPrevious = previous;
        }

        @Override
        public void uncaughtException(final Thread thread, final Throwable error) {
            record("CRASH-001", "MagicDesk terminated unexpectedly",
                    "thread=" + (thread == null ? "unknown" : thread.getName()), error);
            if (mPrevious != null) {
                mPrevious.uncaughtException(thread, error);
            }
        }
    }
}
