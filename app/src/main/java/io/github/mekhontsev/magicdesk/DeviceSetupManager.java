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
    private static final int SETUP_VERSION = 1;

    private static final String KEY_APPROVED_VERSION = "approved_version";
    private static final String KEY_PENDING_BOOT_ID = "pending_boot_id";

    private static final String FREEFORM_SETTING = "enable_freeform_support";
    private static final String RESIZABLE_SETTING = "force_resizable_activities";
    private static final String RESTRICTIONS_PROPERTY =
            "persist.wm.debug.desktop_mode_enforce_device_restrictions";
    private static final String ROUNDED_CORNERS_PROPERTY =
            "persist.wm.debug.desktop_use_rounded_corners";
    private static final String VERIFIED_NX809J_FINGERPRINT =
            "REDMAGIC/NX809J-EEA/NX809J:16/"
                    + "BQ2A.250705.001-BP2A.250605.031.A3/"
                    + "20260204.221845:user/release-keys";

    private static final String ITEM_FREEFORM = "freeform";
    private static final String ITEM_RESIZABLE = "resizable";
    private static final String ITEM_RESTRICTIONS = "restrictions";
    private static final String ITEM_ROUNDED_CORNERS = "rounded_corners";

    private static final String ORIGINAL_PREFIX = "original_";
    private static final String OWNED_PREFIX = "owned_";
    private static final String VALUE_ABSENT = "__MAGICDESK_VALUE_ABSENT__";
    private static volatile boolean sRuntimeAuthorized;

    private DeviceSetupManager() {
    }

    static Audit audit(final Context context) {
        return audit(context, SessionProfile.load(context));
    }

    static Audit audit(final Context context, final SessionProfile sessionProfile) {
        final boolean compatibleDevice = isZteFamilyDevice()
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA;
        final boolean verifiedDevice =
                ("NX809J".equalsIgnoreCase(Build.MODEL)
                        || "NX809J".equalsIgnoreCase(Build.DEVICE))
                && VERIFIED_NX809J_FINGERPRINT.equals(Build.FINGERPRINT);
        final SharedPreferences preferences = preferences(context);

        Map<String, String> values = readUnprivilegedValues(context);
        boolean rootAvailable = false;
        String rootError = "";
        ShizukuAccess.Snapshot shizuku = new ShizukuAccess.Snapshot(
                false, false, false, -1, -1, "not requested");
        RuntimeAccess.Backend backend = RuntimeAccess.Backend.BASIC;
        final SessionProfile.PrivilegeMode requestedMode =
                sessionProfile == null
                        ? SessionProfile.PrivilegeMode.AUTO
                        : sessionProfile.privilegeMode;
        final boolean shouldProbeRoot =
                requestedMode == SessionProfile.PrivilegeMode.AUTO
                        || requestedMode == SessionProfile.PrivilegeMode.ROOT;
        if (shouldProbeRoot) {
            try {
                values = parseValues(
                        PrivilegedCommandRunner.runRootSetup(buildAuditCommand()));
                rootAvailable = "0".equals(values.get("UID"));
                if (!rootAvailable) {
                    rootError = "su did not return uid 0";
                }
            } catch (IOException e) {
                rootError = usefulMessage(e);
            }
            if (rootAvailable) {
                backend = RuntimeAccess.Backend.ROOT;
            }
        } else if (requestedMode == SessionProfile.PrivilegeMode.SHIZUKU) {
            shizuku = ShizukuAccess.inspect();
            rootError = shizuku.error;
            if (shizuku.running && shizuku.permissionGranted) {
                try {
                    final int serviceUid = ShizukuAccess.connectAndGetUid();
                    shizuku = new ShizukuAccess.Snapshot(
                            shizuku.installed,
                            true,
                            true,
                            serviceUid,
                            shizuku.version,
                            "");
                    backend = serviceUid == 0
                            ? RuntimeAccess.Backend.SHIZUKU_ROOT
                            : RuntimeAccess.Backend.SHIZUKU_SHELL;
                    rootError = "";
                } catch (IOException error) {
                    rootError = usefulMessage(error);
                    shizuku = new ShizukuAccess.Snapshot(
                            shizuku.installed,
                            true,
                            true,
                            shizuku.uid,
                            shizuku.version,
                            rootError);
                }
            }
        } else {
            rootError = "Root intentionally disabled by Basic mode";
        }
        if (requestedMode != SessionProfile.PrivilegeMode.SHIZUKU) {
            ShizukuAccess.disconnect();
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
        final boolean configurationReady = freeformEnabled
                && resizableEnabled
                && restrictionsDisabled
                && roundedCornersDisabled;
        final boolean acknowledged =
                preferences.getInt(KEY_APPROVED_VERSION, 0) >= SETUP_VERSION;

        if (!compatibleDevice) {
            CompatibilityDiagnostics.record(
                    "PLATFORM-001",
                    "Unsupported Android platform",
                    "MagicDesk requires ZTE/nubia Android 16+; found "
                            + Build.MANUFACTURER + " " + Build.MODEL
                            + " API " + Build.VERSION.SDK_INT);
        } else if (!verifiedDevice) {
            CompatibilityDiagnostics.record(
                    "PROFILE-001",
                    "Unverified ZTE/nubia firmware",
                    Build.FINGERPRINT);
        }
        if (shouldProbeRoot && !rootAvailable) {
            CompatibilityDiagnostics.record(
                    "ROOT-001",
                    "Root access is unavailable",
                    rootError);
        } else if (requestedMode == SessionProfile.PrivilegeMode.SHIZUKU
                && backend != RuntimeAccess.Backend.SHIZUKU_SHELL
                && backend != RuntimeAccess.Backend.SHIZUKU_ROOT) {
            CompatibilityDiagnostics.record(
                    "SHIZUKU-001",
                    "Shizuku runtime is unavailable",
                    rootError);
        }

        return new Audit(
                rootAvailable,
                rootError,
                shizuku,
                sessionProfile,
                backend,
                compatibleDevice,
                verifiedDevice,
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
                rebootRequired,
                acknowledged,
                hasManagedChanges(preferences));
    }

    static Audit configure(final Context context) throws IOException {
        return configure(context, SessionProfile.load(context));
    }

    static Audit configure(
            final Context context,
            final SessionProfile sessionProfile) throws IOException {
        final Audit before = audit(context, sessionProfile);
        requireRootAndCompatibility(before);

        final SharedPreferences preferences = preferences(context);
        final SharedPreferences.Editor originals = preferences.edit();
        final List<String> commands = new ArrayList<>();

        addGlobalSettingChange(
                preferences,
                originals,
                commands,
                ITEM_FREEFORM,
                FREEFORM_SETTING,
                before.freeformValue,
                before.freeformEnabled);
        addGlobalSettingChange(
                preferences,
                originals,
                commands,
                ITEM_RESIZABLE,
                RESIZABLE_SETTING,
                before.resizableValue,
                before.resizableEnabled);
        addPropertyChange(
                preferences,
                originals,
                commands,
                ITEM_RESTRICTIONS,
                RESTRICTIONS_PROPERTY,
                before.restrictionsValue,
                before.restrictionsDisabled);
        addPropertyChange(
                preferences,
                originals,
                commands,
                ITEM_ROUNDED_CORNERS,
                ROUNDED_CORNERS_PROPERTY,
                before.roundedCornersValue,
                before.roundedCornersDisabled);

        originals.putInt(KEY_APPROVED_VERSION, SETUP_VERSION);
        if (!commands.isEmpty()) {
            originals.putString(KEY_PENDING_BOOT_ID,
                    before.bootId.isEmpty() ? "unknown-current-boot" : before.bootId);
        }
        if (!originals.commit()) {
            throw new IOException("could not save setup state");
        }

        if (!commands.isEmpty()) {
            PrivilegedCommandRunner.runRootSetup(joinCommands(commands));
        }
        final Audit after = audit(context, sessionProfile);
        if (!after.configurationReady) {
            throw new IOException("one or more settings did not apply");
        }
        return after;
    }

    static Audit restoreManagedChanges(final Context context) throws IOException {
        final Audit before = audit(context);
        if (!before.rootAvailable) {
            throw new IOException(before.rootError.isEmpty()
                    ? "root access is required" : before.rootError);
        }

        final SharedPreferences preferences = preferences(context);
        final List<String> commands = new ArrayList<>();
        addGlobalSettingRestore(
                preferences, commands, ITEM_FREEFORM, FREEFORM_SETTING);
        addGlobalSettingRestore(
                preferences, commands, ITEM_RESIZABLE, RESIZABLE_SETTING);
        addPropertyRestore(
                preferences, commands, ITEM_RESTRICTIONS, RESTRICTIONS_PROPERTY);
        addPropertyRestore(
                preferences, commands, ITEM_ROUNDED_CORNERS, ROUNDED_CORNERS_PROPERTY);

        if (!commands.isEmpty()) {
            PrivilegedCommandRunner.runRootSetup(joinCommands(commands));
        }

        final SharedPreferences.Editor editor = preferences.edit()
                .remove(KEY_APPROVED_VERSION);
        clearManagedItem(editor, ITEM_FREEFORM);
        clearManagedItem(editor, ITEM_RESIZABLE);
        clearManagedItem(editor, ITEM_RESTRICTIONS);
        clearManagedItem(editor, ITEM_ROUNDED_CORNERS);
        if (!commands.isEmpty()) {
            editor.putString(KEY_PENDING_BOOT_ID,
                    before.bootId.isEmpty() ? "unknown-current-boot" : before.bootId);
        } else {
            editor.remove(KEY_PENDING_BOOT_ID);
        }
        if (!editor.commit()) {
            throw new IOException("could not save restored setup state");
        }
        return audit(context);
    }

    static void acknowledgeReadyConfiguration(final Context context) {
        preferences(context).edit()
                .putInt(KEY_APPROVED_VERSION, SETUP_VERSION)
                .apply();
    }

    static boolean isSetupAcknowledged(final Context context) {
        return preferences(context).getInt(KEY_APPROVED_VERSION, 0) >= SETUP_VERSION;
    }

    static void activateRuntime(final Context context, final Audit audit) {
        if (audit == null) {
            return;
        }
        RuntimeAccess.configure(audit.sessionProfile, audit.backend);
        if (audit.canEnterMagicDesk()) {
            reconcileRuntimeServices(context);
        } else {
            stopRuntimeServices(context);
        }
    }

    static void authorizeRuntime(final Context context) {
        sRuntimeAuthorized = true;
        reconcileRuntimeServices(context);
    }

    static void revokeRuntimeAuthorization(final Context context) {
        sRuntimeAuthorized = false;
        stopRuntimeServices(context);
    }

    static boolean isRuntimeAuthorized() {
        return sRuntimeAuthorized;
    }

    private static void reconcileRuntimeServices(final Context context) {
        if (context == null) {
            return;
        }
        if (sRuntimeAuthorized
                && RuntimeAccess.has(RuntimeAccess.Capability.GLOBAL_INPUT)) {
            KeyboardWatcherService.start(context.getApplicationContext());
        } else {
            stopRuntimeServices(context);
        }
    }

    private static void stopRuntimeServices(final Context context) {
        RootKeyboardShortcutWatcher.stop();
        ConsoleModeSwitcher.closeRootShell();
        if (context != null) {
            KeyboardWatcherService.stop(context.getApplicationContext());
        }
    }

    static void reboot() throws IOException {
        PrivilegedCommandRunner.runRootSetup("/system/bin/reboot");
    }

    private static void requireRootAndCompatibility(final Audit audit) throws IOException {
        if (!audit.rootAvailable) {
            throw new IOException(audit.rootError.isEmpty()
                    ? "root access is required" : audit.rootError);
        }
        if (!audit.compatibleDevice) {
            throw new IOException("requires a ZTE/nubia device with Android 16 or newer; "
                    + "found " + audit.manufacturer + " " + audit.model
                    + " on API " + Build.VERSION.SDK_INT);
        }
    }

    private static boolean isZteFamilyDevice() {
        return isZteName(Build.MANUFACTURER)
                || isZteName(Build.BRAND)
                || isZteName(Build.PRODUCT);
    }

    private static boolean isZteName(final String value) {
        if (value == null) {
            return false;
        }
        final String normalized = value.toLowerCase(java.util.Locale.US);
        return normalized.contains("zte")
                || normalized.contains("nubia")
                || normalized.contains("redmagic");
    }

    private static void addGlobalSettingChange(
            final SharedPreferences preferences,
            final SharedPreferences.Editor editor,
            final List<String> commands,
            final String item,
            final String setting,
            final String currentValue,
            final boolean alreadyConfigured) {
        if (alreadyConfigured) {
            return;
        }
        rememberOriginal(preferences, editor, item, currentValue);
        commands.add("/system/bin/settings put global " + setting + " 1");
    }

    private static void addPropertyChange(
            final SharedPreferences preferences,
            final SharedPreferences.Editor editor,
            final List<String> commands,
            final String item,
            final String property,
            final String currentValue,
            final boolean alreadyConfigured) {
        if (alreadyConfigured) {
            return;
        }
        rememberOriginal(preferences, editor, item, currentValue);
        commands.add("/system/bin/setprop " + property + " false");
    }

    private static void rememberOriginal(
            final SharedPreferences preferences,
            final SharedPreferences.Editor editor,
            final String item,
            final String value) {
        if (preferences.getBoolean(OWNED_PREFIX + item, false)) {
            return;
        }
        editor.putBoolean(OWNED_PREFIX + item, true);
        editor.putString(ORIGINAL_PREFIX + item,
                value.isEmpty() || "null".equals(value) ? VALUE_ABSENT : value);
    }

    private static void addGlobalSettingRestore(
            final SharedPreferences preferences,
            final List<String> commands,
            final String item,
            final String setting) {
        if (!preferences.getBoolean(OWNED_PREFIX + item, false)) {
            return;
        }
        final String original = preferences.getString(
                ORIGINAL_PREFIX + item, VALUE_ABSENT);
        if (VALUE_ABSENT.equals(original)) {
            commands.add("/system/bin/settings delete global " + setting);
        } else {
            commands.add("/system/bin/settings put global " + setting + " "
                    + shellQuote(original));
        }
    }

    private static void addPropertyRestore(
            final SharedPreferences preferences,
            final List<String> commands,
            final String item,
            final String property) {
        if (!preferences.getBoolean(OWNED_PREFIX + item, false)) {
            return;
        }
        final String original = preferences.getString(
                ORIGINAL_PREFIX + item, VALUE_ABSENT);
        commands.add("/system/bin/setprop " + property + " "
                + (VALUE_ABSENT.equals(original) ? "''" : shellQuote(original)));
    }

    private static void clearManagedItem(
            final SharedPreferences.Editor editor, final String item) {
        editor.remove(OWNED_PREFIX + item);
        editor.remove(ORIGINAL_PREFIX + item);
    }

    private static boolean hasManagedChanges(final SharedPreferences preferences) {
        return preferences.getBoolean(OWNED_PREFIX + ITEM_FREEFORM, false)
                || preferences.getBoolean(OWNED_PREFIX + ITEM_RESIZABLE, false)
                || preferences.getBoolean(OWNED_PREFIX + ITEM_RESTRICTIONS, false)
                || preferences.getBoolean(OWNED_PREFIX + ITEM_ROUNDED_CORNERS, false);
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

    private static String shellQuote(final String value) {
        return "'" + value.replace("'", "'\\''") + "'";
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
        final boolean rootAvailable;
        final String rootError;
        final ShizukuAccess.Snapshot shizuku;
        final SessionProfile sessionProfile;
        final RuntimeAccess.Backend backend;
        final boolean compatibleDevice;
        final boolean verifiedDevice;
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
        final boolean acknowledged;
        final boolean hasManagedChanges;

        Audit(
                final boolean rootAvailable,
                final String rootError,
                final ShizukuAccess.Snapshot shizuku,
                final SessionProfile sessionProfile,
                final RuntimeAccess.Backend backend,
                final boolean compatibleDevice,
                final boolean verifiedDevice,
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
                final boolean rebootRequired,
                final boolean acknowledged,
                final boolean hasManagedChanges) {
            this.rootAvailable = rootAvailable;
            this.rootError = rootError;
            this.shizuku = shizuku;
            this.sessionProfile = sessionProfile;
            this.backend = backend;
            this.compatibleDevice = compatibleDevice;
            this.verifiedDevice = verifiedDevice;
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
            this.acknowledged = acknowledged;
            this.hasManagedChanges = hasManagedChanges;
        }

        boolean canEnterMagicDesk() {
            final SessionProfile.PrivilegeMode requestedMode =
                    sessionProfile == null
                            ? SessionProfile.PrivilegeMode.AUTO
                            : sessionProfile.privilegeMode;
            if (requestedMode == SessionProfile.PrivilegeMode.ROOT
                    && backend != RuntimeAccess.Backend.ROOT) {
                return false;
            }
            if (requestedMode == SessionProfile.PrivilegeMode.SHIZUKU
                    && backend != RuntimeAccess.Backend.SHIZUKU_SHELL
                    && backend != RuntimeAccess.Backend.SHIZUKU_ROOT) {
                return false;
            }
            final boolean provisioningOptional =
                    backend == RuntimeAccess.Backend.BASIC
                            || backend == RuntimeAccess.Backend.SHIZUKU_SHELL
                            || backend == RuntimeAccess.Backend.SHIZUKU_ROOT;
            return compatibleDevice
                    && !rebootRequired
                    && (configurationReady || provisioningOptional);
        }

        boolean isDegradedRuntime() {
            return backend != RuntimeAccess.Backend.ROOT && !configurationReady;
        }
    }
}
