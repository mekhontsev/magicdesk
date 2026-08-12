package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class DeviceSetupManager {
    private static final String PREFS = "magicdesk_device_setup";
    private static final String MAGICDESK_PACKAGE =
            "io.github.mekhontsev.magicdesk";

    private static final String KEY_PENDING_BOOT_ID = "pending_boot_id";

    private static final String FREEFORM_SETTING = "enable_freeform_support";
    private static final String RESIZABLE_SETTING = "force_resizable_activities";
    private static final String RESTRICTIONS_PROPERTY =
            NubiaDesktopPropertyManager.Property.DEVICE_RESTRICTIONS.key;
    private static final String ROUNDED_CORNERS_PROPERTY =
            NubiaDesktopPropertyManager.Property.ROUNDED_CORNERS.key;
    private DeviceSetupManager() {
    }

    static Audit audit(final Context context) {
        return audit(context, SessionProfile.load(context));
    }

    static Audit audit(final Context context, final SessionProfile sessionProfile) {
        final PlatformDevice device = PlatformDevice.current();
        final PlatformDriver platform = PlatformDrivers.current();
        final boolean compatibleDevice = platform.supports(device);
        final PlatformSupportLevel firmwareSupport =
                platform.supportLevel(device);
        final SharedPreferences preferences = preferences(context);

        Map<String, String> values = readUnprivilegedValues(context);
        String runtimeError = "";
        boolean shellReady = false;
        int shizukuUid = -1;
        ShellAccess.Snapshot shellState = ShellAccess.refresh();
        runtimeError = shellState.error;
        if (shellState.isReady()) {
            try {
                final int serviceUid = ShellAccess.connectAndGetUid();
                shizukuUid = serviceUid;
                if (!ShellAccess.isSupportedServiceUid(serviceUid)) {
                    throw new IOException(
                            "Shizuku service UID is unsupported: " + serviceUid);
                }
                values = parseValues(ShellAccess.run(buildAuditCommand()));
                shellReady = true;
                shellState = new ShellAccess.Snapshot(
                        shellState.installed,
                        true,
                        true,
                        serviceUid,
                        shellState.version,
                        "");
                runtimeError = "";
            } catch (IOException error) {
                runtimeError = usefulMessage(error);
                shellState = new ShellAccess.Snapshot(
                        shellState.installed,
                        true,
                        true,
                        shizukuUid,
                        shellState.version,
                        runtimeError);
            }
        }
        final String bootId = value(values, "BOOT_ID");
        boolean rebootRequired = false;
        final String pendingBootId = preferences.getString(KEY_PENDING_BOOT_ID, "");
        if (!pendingBootId.isEmpty()) {
            if (!bootId.isEmpty() && !pendingBootId.equals(bootId)) {
                preferences.edit().remove(KEY_PENDING_BOOT_ID).apply();
            } else {
                rebootRequired = true;
            }
        }

        final String freeformValue = value(values, "FREEFORM");
        final String resizableValue = value(values, "RESIZABLE");
        final String restrictionsValue = value(values, "RESTRICTIONS");
        final String roundedCornersValue = value(values, "ROUNDED");
        final boolean freeformEnabled = "1".equals(freeformValue);
        final boolean resizableEnabled = "1".equals(resizableValue);
        final boolean restrictionsDisabled = "false".equals(restrictionsValue);
        final boolean roundedCornersDisabled = "false".equals(roundedCornersValue);
        final boolean configurationReady = platform.windowing().isReady(
                freeformEnabled,
                resizableEnabled,
                restrictionsDisabled,
                roundedCornersDisabled);
        return new Audit(
                runtimeError,
                shellState,
                sessionProfile,
                shellReady,
                compatibleDevice,
                firmwareSupport,
                platform,
                Build.MANUFACTURER,
                Build.MODEL,
                Build.VERSION.RELEASE,
                Build.FINGERPRINT,
                bootId,
                freeformValue,
                resizableValue,
                restrictionsValue,
                roundedCornersValue,
                freeformEnabled,
                resizableEnabled,
                restrictionsDisabled,
                roundedCornersDisabled,
                configurationReady,
                rebootRequired);
    }

    static Audit configure(
            final Context context,
            final SessionProfile sessionProfile) throws IOException {
        final Audit before = audit(context, sessionProfile);
        if (!before.shellReady) {
            throw new IOException(
                    "running Shizuku shell access is required");
        }
        if (!before.compatibleDevice) {
            throw new IOException(
                    "requires a supported Android 16 platform");
        }

        final SharedPreferences preferences = preferences(context);
        final List<String> commands = new ArrayList<>();
        addGlobalSettingChange(
                commands,
                FREEFORM_SETTING,
                before.freeformEnabled);
        addGlobalSettingChange(
                commands,
                RESIZABLE_SETTING,
                before.resizableEnabled);
        final boolean vendorChangeRequired =
                before.platform.features().vendorWindowingProperties
                        && (!before.restrictionsDisabled
                                || !before.roundedCornersDisabled);
        if (!commands.isEmpty() || vendorChangeRequired) {
            savePendingReboot(preferences, before.bootId);
        }
        if (!commands.isEmpty()) {
            ShellAccess.run(joinCommands(commands));
        }
        before.platform.windowing().configure(
                before.restrictionsDisabled,
                before.roundedCornersDisabled);
        final Audit after = audit(context, sessionProfile);
        if (!after.configurationReady) {
            throw new IOException(
                    "Shizuku setup could not fully provision desktop windowing");
        }
        return audit(context, sessionProfile);
    }

    static Audit restoreDefaults(
            final Context context,
            final SessionProfile sessionProfile) throws IOException {
        final Audit before = audit(context, sessionProfile);
        if (!before.shellReady) {
            throw new IOException(
                    "running Shizuku shell access is required");
        }
        if (!before.compatibleDevice) {
            throw new IOException(
                    "requires a supported Android 16 platform");
        }

        DeviceSetupRuntimeController.revoke(context);
        final PhoneDesktopTaskRecovery.Result taskRecovery =
                PhoneDesktopTaskRecovery.recoverBlocking();
        if (!taskRecovery.success) {
            CompatibilityDiagnostics.record(
                    "PLATFORM-DEFAULTS-001",
                    "Phone desktop task cleanup was incomplete",
                    taskRecovery.message);
        }
        final boolean systemNavigationRestored =
                LocalDesktopNavigationController.releaseBlocking();
        if (!systemNavigationRestored) {
            CompatibilityDiagnostics.record(
                    "PLATFORM-DEFAULTS-002",
                    "System navigation restoration was incomplete",
                    "Local desktop navigation guard release failed");
        }
        ShellAccess.run(defaultsCommand());
        before.platform.windowing().restoreDefaults();
        final SharedPreferences preferences = preferences(context);
        if (!preferences.edit().clear().commit()) {
            throw new IOException("could not clear MagicDesk setup state");
        }
        savePendingReboot(preferences, before.bootId);
        if (taskRecovery.success && systemNavigationRestored) {
            LocalDesktopSessionState.clearCleanupPending(context);
        }
        return audit(context, sessionProfile);
    }

    static String defaultsCommand() {
        return "/system/bin/settings delete global " + FREEFORM_SETTING
                + " && /system/bin/settings delete global " + RESIZABLE_SETTING
                + " && /system/bin/wm size reset -d 0"
                + " && /system/bin/wm density reset -d 0"
                + " && /system/bin/wm scaling auto -d 0";
    }

    static void ensureOverlayPermission(final Context context) throws IOException {
        if (Settings.canDrawOverlays(context)) {
            return;
        }
        if (!MAGICDESK_PACKAGE.equals(context.getPackageName())) {
            throw new IOException("unexpected MagicDesk package name");
        }
        ShellAccess.run(overlayPermissionCommand());
        if (!Settings.canDrawOverlays(context)) {
            throw new IOException(
                    "Android did not apply the display-over-apps permission");
        }
    }

    static String overlayPermissionCommand() {
        return "/system/bin/cmd appops set " + MAGICDESK_PACKAGE
                + " SYSTEM_ALERT_WINDOW allow";
    }

    static void activateRuntime(final Context context, final Audit audit) {
        DeviceSetupRuntimeController.activate(context, audit);
    }

    static void authorizeRuntime(final Context context) {
        DeviceSetupRuntimeController.authorize(context);
    }

    static void revokeRuntimeAuthorization(final Context context) {
        DeviceSetupRuntimeController.revoke(context);
    }

    static boolean isRuntimeAuthorized() {
        return DeviceSetupRuntimeController.isAuthorized();
    }

    static void reboot() throws IOException {
        ShellAccess.run("/system/bin/svc power reboot");
    }

    private static void addGlobalSettingChange(
            final List<String> commands,
            final String setting,
            final boolean alreadyConfigured) {
        if (alreadyConfigured) {
            return;
        }
        commands.add("/system/bin/settings put global " + setting + " 1");
    }

    private static void savePendingReboot(
            final SharedPreferences preferences,
            final String bootId) throws IOException {
        if (!preferences.edit().putString(
                KEY_PENDING_BOOT_ID,
                bootId.isEmpty() ? "unknown-current-boot" : bootId)
                .commit()) {
            throw new IOException("could not save pending reboot state");
        }
    }

    private static String buildAuditCommand() {
        return "printf 'MAGIC_UID='; /system/bin/id -u; "
                + "printf 'MAGIC_BOOT_ID='; /system/bin/cat "
                + "/proc/sys/kernel/random/boot_id; "
                + "printf 'MAGIC_FREEFORM='; /system/bin/settings get global "
                + FREEFORM_SETTING + "; "
                + "printf 'MAGIC_RESIZABLE='; /system/bin/settings get global "
                + RESIZABLE_SETTING + "; "
                + "printf 'MAGIC_RESTRICTIONS='; /system/bin/getprop "
                + RESTRICTIONS_PROPERTY + "; "
                + "printf 'MAGIC_ROUNDED='; /system/bin/getprop "
                + ROUNDED_CORNERS_PROPERTY;
    }

    private static Map<String, String> readUnprivilegedValues(final Context context) {
        final Map<String, String> values = new HashMap<>();
        values.put("UID", Integer.toString(android.os.Process.myUid()));
        values.put("BOOT_ID", readFirstLine("/proc/sys/kernel/random/boot_id"));
        values.put("FREEFORM", Settings.Global.getString(
                context.getContentResolver(), FREEFORM_SETTING));
        values.put("RESIZABLE", Settings.Global.getString(
                context.getContentResolver(), RESIZABLE_SETTING));
        values.put("RESTRICTIONS", runLocalCommand(
                "/system/bin/getprop", RESTRICTIONS_PROPERTY));
        values.put("ROUNDED", runLocalCommand(
                "/system/bin/getprop", ROUNDED_CORNERS_PROPERTY));
        return values;
    }

    private static String readFirstLine(final String path) {
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            final String line = reader.readLine();
            return line == null ? "" : line.trim();
        } catch (IOException | SecurityException e) {
            return "";
        }
    }

    private static String runLocalCommand(final String executable, final String argument) {
        Process process = null;
        try {
            process = new ProcessBuilder(executable, argument)
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                final String line = reader.readLine();
                process.waitFor();
                return line == null ? "" : line.trim();
            }
        } catch (IOException e) {
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static String joinCommands(final List<String> commands) {
        final StringBuilder result = new StringBuilder();
        for (final String command : commands) {
            if (result.length() > 0) {
                result.append(" && ");
            }
            result.append(command);
        }
        return result.toString();
    }

    private static Map<String, String> parseValues(final String output) {
        final Map<String, String> values = new HashMap<>();
        for (final String line : output.split("\\r?\\n")) {
            if (!line.startsWith("MAGIC_")) {
                continue;
            }
            final int separator = line.indexOf('=');
            if (separator <= 6) {
                continue;
            }
            values.put(line.substring(6, separator),
                    line.substring(separator + 1).trim());
        }
        return values;
    }

    private static String value(final Map<String, String> values, final String key) {
        final String value = values.get(key);
        return value == null ? "" : value;
    }

    private static String usefulMessage(final IOException error) {
        final String message = error.getMessage();
        return message == null || message.isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    private static SharedPreferences preferences(final Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static final class Audit {
        final String runtimeError;
        final ShellAccess.Snapshot shellState;
        final SessionProfile sessionProfile;
        final boolean shellReady;
        final boolean compatibleDevice;
        final PlatformSupportLevel firmwareSupport;
        final PlatformDriver platform;
        final String manufacturer;
        final String model;
        final String androidRelease;
        final String fingerprint;
        final String bootId;
        final String freeformValue;
        final String resizableValue;
        final String restrictionsValue;
        final String roundedCornersValue;
        final boolean freeformEnabled;
        final boolean resizableEnabled;
        final boolean restrictionsDisabled;
        final boolean roundedCornersDisabled;
        final boolean configurationReady;
        final boolean rebootRequired;

        Audit(
                final String runtimeError,
                final ShellAccess.Snapshot shellState,
                final SessionProfile sessionProfile,
                final boolean shellReady,
                final boolean compatibleDevice,
                final PlatformSupportLevel firmwareSupport,
                final PlatformDriver platform,
                final String manufacturer,
                final String model,
                final String androidRelease,
                final String fingerprint,
                final String bootId,
                final String freeformValue,
                final String resizableValue,
                final String restrictionsValue,
                final String roundedCornersValue,
                final boolean freeformEnabled,
                final boolean resizableEnabled,
                final boolean restrictionsDisabled,
                final boolean roundedCornersDisabled,
                final boolean configurationReady,
                final boolean rebootRequired) {
            this.runtimeError = runtimeError;
            this.shellState = shellState;
            this.sessionProfile = sessionProfile;
            this.shellReady = shellReady;
            this.compatibleDevice = compatibleDevice;
            this.firmwareSupport = firmwareSupport;
            this.platform = platform;
            this.manufacturer = manufacturer;
            this.model = model;
            this.androidRelease = androidRelease;
            this.fingerprint = fingerprint;
            this.bootId = bootId;
            this.freeformValue = freeformValue;
            this.resizableValue = resizableValue;
            this.restrictionsValue = restrictionsValue;
            this.roundedCornersValue = roundedCornersValue;
            this.freeformEnabled = freeformEnabled;
            this.resizableEnabled = resizableEnabled;
            this.restrictionsDisabled = restrictionsDisabled;
            this.roundedCornersDisabled = roundedCornersDisabled;
            this.configurationReady = configurationReady;
            this.rebootRequired = rebootRequired;
        }

        boolean canEnterMagicDesk() {
            return compatibleDevice
                    && shellReady
                    && !rebootRequired
                    && configurationReady;
        }

    }

    static boolean hasRequiredWindowingSettings(
            final boolean freeformEnabled,
            final boolean resizableEnabled) {
        return freeformEnabled && resizableEnabled;
    }

}
