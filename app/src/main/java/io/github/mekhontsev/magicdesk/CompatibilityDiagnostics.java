package io.github.mekhontsev.magicdesk;

import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

public final class CompatibilityDiagnostics {
    private static final Object LOCK = new Object();
    private static final String PREFS = "compatibility_diagnostics";
    private static final String PREF_EVENT_BUILD = "event_build";
    private static final String LEGACY_PREF_EVENT_VERSION = "event_version";
    private static final String EVENT_FILE = "compatibility-events.log";
    private static final long MAX_EVENT_FILE_BYTES = 128 * 1024;
    private static final int MAX_EVENT_DETAIL_CHARS = 2_000;
    private static final int MAX_REPORTED_EVENT_CHARS = 64_000;
    private static final int MAX_LOGCAT_CHARS = 48_000;
    private static volatile Context sApplicationContext;
    private static final Set<String> RECORDED_EVENT_SIGNATURES =
            new HashSet<>();

    private CompatibilityDiagnostics() {
    }

    static void initialize(final Context context) {
        if (context == null) {
            return;
        }
        sApplicationContext = context.getApplicationContext();
        clearEventsAfterAppUpdate(sApplicationContext);
        final Thread.UncaughtExceptionHandler previous =
                Thread.getDefaultUncaughtExceptionHandler();
        if (previous instanceof CrashHandler) {
            return;
        }
        Thread.setDefaultUncaughtExceptionHandler(new CrashHandler(previous));
    }

    public static void record(final String code, final String userMessage,
            final String technicalDetail) {
        record(code, userMessage, technicalDetail, null);
    }

    public static void record(final String code, final String userMessage,
            final String technicalDetail, final Throwable error) {
        final Context context = sApplicationContext;
        if (context == null) {
            return;
        }
        final String cleanedCode = cleanSingleLine(code, 80);
        final String cleanedMessage = cleanSingleLine(userMessage, 400);
        final String cleanedDetail = cleanMultiline(
                technicalDetail, MAX_EVENT_DETAIL_CHARS);
        final String signature = cleanedCode
                + '\n' + cleanedMessage
                + '\n' + cleanedDetail;

        synchronized (LOCK) {
            if (isDuplicate(signature)) {
                return;
            }
            final StringBuilder entry = new StringBuilder();
            entry.append(utcNow())
                    .append(" | ")
                    .append(cleanedCode)
                    .append(" | ")
                    .append(cleanedMessage);
            if (!TextUtils.isEmpty(cleanedDetail)) {
                entry.append(" | ").append(cleanedDetail);
            }
            if (error != null) {
                entry.append(" | ").append(cleanMultiline(
                        stackTrace(error), MAX_EVENT_DETAIL_CHARS));
            }
            entry.append('\n');
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

    static boolean isDuplicate(final String signature) {
        return !RECORDED_EVENT_SIGNATURES.add(signature);
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
        CaptureDiagnostics.appendReport(report, appContext);
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
            RECORDED_EVENT_SIGNATURES.clear();
            new File(context.getFilesDir(), EVENT_FILE).delete();
            new File(context.getFilesDir(), EVENT_FILE + ".previous").delete();
        }
    }

    private static void clearEventsAfterAppUpdate(final Context context) {
        final String build = BuildConfig.VERSION_NAME
                + ':' + BuildConfig.VERSION_CODE;
        final SharedPreferences preferences = context.getSharedPreferences(
                PREFS, Context.MODE_PRIVATE);
        if (build.equals(preferences.getString(PREF_EVENT_BUILD, null))) {
            return;
        }
        clearEvents(context);
        preferences.edit()
                .putString(PREF_EVENT_BUILD, build)
                .remove(LEGACY_PREF_EVENT_VERSION)
                .commit();
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
                .append("Platform driver: ")
                .append(audit.platform.name())
                .append(" (").append(audit.platform.id()).append(")\n")
                .append("Platform selection: ")
                .append(PlatformDrivers.selectionDetail()).append('\n')
                .append("Shizuku runtime: ")
                .append(ShellAccess.statusLabel()).append('\n')
                .append("Display target: ").append(profile.displayWireName()).append('\n')
                .append("System provisioning: ")
                .append(audit.configurationReady ? "ready" : "incomplete").append('\n')
                .append("Reboot pending: ").append(audit.rebootRequired).append('\n')
                .append("Phone rotation: auto=")
                .append(Settings.System.getInt(
                        context.getContentResolver(),
                        Settings.System.ACCELEROMETER_ROTATION,
                        -1))
                .append(", user=")
                .append(Settings.System.getInt(
                        context.getContentResolver(),
                        Settings.System.USER_ROTATION,
                        -1))
                .append("\n\n")
                .append("## Capability checks\n");
        appendCheck(report, "PLATFORM-001", audit.compatibleDevice,
                "Android 15 platform baseline",
                audit.platform.name() + "; "
                        + audit.manufacturer + " " + audit.model);
        appendCheck(report, "PROFILE-001",
                audit.firmwareSupport
                        != PlatformSupportLevel.UNVERIFIED,
                "Firmware compatibility profile",
                audit.platform.diagnostics().supportDetail(
                        PlatformDevice.current(), audit.firmwareSupport));
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
        if (audit.platform.windowing().restrictionsPropertyKey() != null
                || audit.platform.windowing()
                        .roundedCornersPropertyKey() != null) {
            appendCheck(report, "WM-ELIGIBILITY-001",
                    audit.restrictionsDisabled,
                    "Desktop-mode device restriction property",
                    expectedValue("false", audit.restrictionsValue));
            appendCheck(report, "WM-CORNERS-001",
                    audit.roundedCornersDisabled,
                    "Desktop rounded-corner property",
                    expectedValue("false", audit.roundedCornersValue));
        }
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
                        ? NativeDesktopController.backendDescription()
                        : privilegedTransactions
                                ? "direct WindowContainerTransaction fallback"
                        : taskControl
                                ? "privileged transaction backend unavailable"
                                : "Shizuku runtime unavailable");
        final PhoneHomeComponents phoneHome =
                PhoneHomeComponents.resolve(context);
        appendCheck(report, "LAUNCHER-001",
                phoneHome.hasPrimary(),
                "Phone launcher HOME activity",
                phoneHome.diagnosticDetail());
        report.append("Phone task guard: ")
                .append(PhoneTaskGuardDiagnostics.snapshot().reportLine())
                .append('\n');
        final boolean globalInput = ShellAccess.isReady()
                && audit.platform.features().externalInputBridge
                && DesktopRuntimeBridge.getActiveDesktopDisplayId() > 0;
        appendCheck(report, "SHORTCUTS-001",
                !globalInput || KeyboardShortcutWatcher.isFullShortcutMode(),
                "Global keyboard/input bridge",
                globalInput
                        ? (KeyboardShortcutWatcher.isFullShortcutMode()
                                ? "running" : "not running")
                        : audit.platform.features().externalInputBridge
                                ? "idle; an external desktop is required"
                                : "not required by the selected platform");
        report.append("Keyboard bridge runtime: ")
                .append(InputBridgeDiagnostics.snapshot().reportLine())
                .append('\n');
        final MagicDeskSettings.Values settings = MagicDeskSettings.load();
        report.append("MagicDesk settings: taskbarAutoHide=")
                .append(settings.taskbarAutoHide)
                .append(", openTouchpadAutomatically=")
                .append(settings.openTouchpadAutomatically)
                .append(", keepDesktopAwake=")
                .append(settings.keepDesktopAwake)
                .append(", openItemsWithSingleClick=")
                .append(settings.openFilesWithSingleClick)
                .append('\n');
        report.append("Desktop wake lock held: ")
                .append(MagicDeskRuntimeService
                        .isSessionWakeLockHeldIfRunning())
                .append('\n');
        final boolean shellRightClick = ShellAccess.isReady();
        final boolean mouseBridgeExpected =
                audit.platform.features().externalInputBridge
                        && shellRightClick
                        && DesktopRuntimeBridge
                                .getActiveDesktopDisplayId() > 0;
        final boolean mouseBridgeReady =
                MagicDeskRuntimeService
                        .isDesktopMouseBridgeReadyIfRunning();
        final String mouseBridgeDetail;
        if (!shellRightClick) {
            mouseBridgeDetail =
                    "Shizuku runtime unavailable";
        } else if (!audit.platform.features().externalInputBridge) {
            mouseBridgeDetail =
                    "not required by the selected platform";
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
        appendPlatformDetails(
                report, context, audit.platform);
        report.append('\n');
    }

    private static void appendPlatformDetails(
            final StringBuilder report,
            final Context context,
            final PlatformDriver platform) {
        final PlatformFeatures features = platform.features();
        report.append("Platform features: wired=")
                .append(features.wiredDesktop)
                .append(", wireless=").append(features.wirelessDesktop)
                .append(", externalInputBridge=")
                .append(features.externalInputBridge)
                .append(", internalAudioCapture=")
                .append(platform.audioCapture().isAvailable())
                .append(", absolutePointer=")
                .append(platform.pointer().isAvailable())
                .append(", outputControls=")
                .append(platform.projection().supportsOutputConfiguration())
                .append(", managedWired=")
                .append(platform.projection().ownsTransportLifecycle(
                        PlatformProjectionDriver.Transport.WIRED))
                .append(", managedWireless=")
                .append(platform.projection().ownsTransportLifecycle(
                        PlatformProjectionDriver.Transport.WIRELESS))
                .append(", phoneUi=")
                .append(platform.phoneUi().isAvailable())
                .append('\n');
        platform.diagnostics().appendCompatibilityReport(report, context);
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
            final Display.Mode[] supportedModes = display.getSupportedModes();
            if (supportedModes.length > 0) {
                report.append(" supportedModes=[");
                for (int index = 0; index < supportedModes.length; index++) {
                    if (index > 0) {
                        report.append(',');
                    }
                    final Display.Mode supportedMode = supportedModes[index];
                    report.append(supportedMode.getPhysicalWidth())
                            .append('x')
                            .append(supportedMode.getPhysicalHeight())
                            .append('@')
                            .append(Math.round(
                                    supportedMode.getRefreshRate()));
                }
                report.append(']');
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
        final String events = selectRecentRecordedEvents(
                readFile(new File(
                        context.getFilesDir(), EVENT_FILE + ".previous"))
                        + readFile(new File(
                                context.getFilesDir(), EVENT_FILE)),
                MAX_REPORTED_EVENT_CHARS);
        if (events.isEmpty()) {
            report.append("No recorded events\n");
        } else {
            report.append(events);
        }
        report.append('\n');
    }

    static String filterRecordedEvents(final String events) {
        return selectRecentRecordedEvents(events, Integer.MAX_VALUE);
    }

    static String selectRecentRecordedEvents(
            final String events,
            final int maxChars) {
        if (events == null || events.isEmpty() || maxChars <= 0) {
            return "";
        }
        final List<String> blocks = splitEventBlocks(events);
        final List<String> selected = new ArrayList<>();
        final Set<String> seen = new HashSet<>();
        int selectedChars = 0;
        for (int index = blocks.size() - 1; index >= 0; index--) {
            final String event = blocks.get(index);
            if (isStaticAuditEvent(event)) {
                continue;
            }
            final int timestampEnd = event.indexOf(" | ");
            final String signature = timestampEnd < 0
                    ? event : event.substring(timestampEnd + 3);
            if (!seen.add(signature)) {
                continue;
            }
            if (selectedChars + event.length() > maxChars) {
                break;
            }
            selected.add(event);
            selectedChars += event.length();
        }
        Collections.reverse(selected);
        final StringBuilder result = new StringBuilder(selectedChars);
        for (final String event : selected) {
            result.append(event);
        }
        return result.toString();
    }

    private static List<String> splitEventBlocks(final String events) {
        final List<String> blocks = new ArrayList<>();
        StringBuilder block = null;
        for (final String line : events.split("(?<=\\n)")) {
            if (isEventHeader(line)) {
                if (block != null) {
                    blocks.add(block.toString());
                }
                block = new StringBuilder(line);
            } else if (block != null) {
                block.append(line);
            }
        }
        if (block != null) {
            blocks.add(block.toString());
        }
        return blocks;
    }

    private static boolean isEventHeader(final String line) {
        final int firstSeparator = line.indexOf(" | ");
        return firstSeparator > 0
                && line.indexOf(" | ", firstSeparator + 3)
                        > firstSeparator + 3;
    }

    private static boolean isStaticAuditEvent(final String event) {
        return event.contains(" | PLATFORM-001 | ")
                || event.contains(" | PROFILE-001 | ")
                || event.contains(" | SHIZUKU-001 | ");
    }

    private static void appendMagicDeskLogcat(final StringBuilder report) {
        report.append("## Recent MagicDesk logcat\n");
        final String command = "/system/bin/logcat -d -v threadtime -t 600 "
                + "MagicDesk:V MagicDeskConsoleSwitcher:V MagicDeskFreeform:V "
                + "MagicDeskNativeDesktop:V MagicDeskNotifications:V MagicDeskPanels:V "
                + "MagicDeskProfiles:V MagicDeskRightButton:V MagicDeskKeys:V "
                + "MagicDeskSetup:V MagicDeskTaskReuse:V MagicDeskTasks:V "
                + "MagicDeskWallpaper:V MagicDeskWatcher:V MagicDeskRecording:V "
                + "MagicDeskPhoneDisplay:V '*:S'";
        final String output = runCommand(command, MAX_LOGCAT_CHARS);
        report.append(output.isEmpty() ? "No MagicDesk log entries available\n" : output);
        if (!output.endsWith("\n")) {
            report.append('\n');
        }
    }

    public static void appendCheck(final StringBuilder report, final String code,
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

    public static boolean hasPackage(final Context context, final String packageName) {
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

    private static String readFile(final File file) {
        if (!file.isFile()) {
            return "";
        }
        final StringBuilder value = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            final char[] buffer = new char[2_048];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                value.append(buffer, 0, read);
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
