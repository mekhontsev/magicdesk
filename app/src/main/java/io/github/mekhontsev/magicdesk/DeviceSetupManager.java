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
    private static final int SETUP_VERSION = 2;

    private static final String KEY_APPROVED_VERSION = "approved_version";
    private static final String KEY_PENDING_BOOT_ID = "pending_boot_id";

    private static final String FREEFORM_SETTING = "enable_freeform_support";
    private static final String RESIZABLE_SETTING = "force_resizable_activities";
    private static final String RESTRICTIONS_PROPERTY =
            NubiaDesktopPropertyManager.Property.DEVICE_RESTRICTIONS.key;
    private static final String ROUNDED_CORNERS_PROPERTY =
            NubiaDesktopPropertyManager.Property.ROUNDED_CORNERS.key;
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
    private DeviceSetupManager() {
    }

    static Audit audit(final Context context) {
        return audit(context, SessionProfile.load(context));
    }

    static Audit audit(final Context context, final SessionProfile sessionProfile) {
        final boolean compatibleDevice = isZteFamilyDevice();
        final boolean verifiedDevice =
                ("NX809J".equalsIgnoreCase(Build.MODEL)
                        || "NX809J".equalsIgnoreCase(Build.DEVICE))
                && VERIFIED_NX809J_FINGERPRINT.equals(Build.FINGERPRINT);
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
                if (serviceUid != ShellAccess.SHELL_UID) {
                    throw new IOException(
                            "Shizuku must run as shell UID 2000; found UID "
                                    + serviceUid);
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
        final boolean configurationReady = isFullWindowingConfigurationReady(
                freeformEnabled,
                resizableEnabled,
                restrictionsDisabled,
                roundedCornersDisabled);
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
        if (!shellReady) {
            CompatibilityDiagnostics.record(
                    "SHIZUKU-001",
                    "Shizuku runtime is unavailable",
                    runtimeError);
        }

        return new Audit(
                runtimeError,
                shellState,
                sessionProfile,
                shellReady,
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
                    "requires a ZTE/nubia device with Android 16 or newer");
        }

        final SharedPreferences preferences = preferences(context);
        final SharedPreferences.Editor originals = preferences.edit();
        final List<String> commands = new ArrayList<>();
        final List<NubiaDesktopPropertyManager.Property> properties =
                new ArrayList<>();
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
        addNubiaPropertyChange(
                preferences,
                originals,
                properties,
                ITEM_RESTRICTIONS,
                NubiaDesktopPropertyManager.Property.DEVICE_RESTRICTIONS,
                before.restrictionsValue,
                before.restrictionsDisabled);
        addNubiaPropertyChange(
                preferences,
                originals,
                properties,
                ITEM_ROUNDED_CORNERS,
                NubiaDesktopPropertyManager.Property.ROUNDED_CORNERS,
                before.roundedCornersValue,
                before.roundedCornersDisabled);
        if (!commands.isEmpty() || !properties.isEmpty()) {
            originals.putString(
                    KEY_PENDING_BOOT_ID,
                    before.bootId.isEmpty()
                            ? "unknown-current-boot" : before.bootId);
        }
        if (!originals.commit()) {
            throw new IOException("could not save Shizuku setup state");
        }
        if (!commands.isEmpty()) {
            ShellAccess.run(joinCommands(commands));
        }
        for (final NubiaDesktopPropertyManager.Property property : properties) {
            NubiaDesktopPropertyManager.write(property, "false");
        }
        final Audit after = audit(context, sessionProfile);
        if (!after.configurationReady) {
            throw new IOException(
                    "Shizuku setup could not fully provision desktop windowing");
        }
        if (!preferences.edit()
                .putInt(KEY_APPROVED_VERSION, SETUP_VERSION)
                .commit()) {
            throw new IOException("could not confirm Shizuku setup state");
        }
        return audit(context, sessionProfile);
    }

    static Audit restoreManagedChanges(
            final Context context,
            final SessionProfile sessionProfile) throws IOException {
        final Audit before = audit(context, sessionProfile);
        if (!before.shellReady) {
            throw new IOException(
                    "running Shizuku shell access is required");
        }
        final SharedPreferences preferences = preferences(context);
        final List<String> commands = new ArrayList<>();
        final List<PropertyRestore> propertyRestores = new ArrayList<>();
        addGlobalSettingRestore(
                preferences, commands, ITEM_FREEFORM, FREEFORM_SETTING);
        addGlobalSettingRestore(
                preferences, commands, ITEM_RESIZABLE, RESIZABLE_SETTING);
        addNubiaPropertyRestore(
                preferences,
                propertyRestores,
                ITEM_RESTRICTIONS,
                NubiaDesktopPropertyManager.Property.DEVICE_RESTRICTIONS);
        addNubiaPropertyRestore(
                preferences,
                propertyRestores,
                ITEM_ROUNDED_CORNERS,
                NubiaDesktopPropertyManager.Property.ROUNDED_CORNERS);
        if (!commands.isEmpty()) {
            ShellAccess.run(joinCommands(commands));
        }
        for (final PropertyRestore restore : propertyRestores) {
            NubiaDesktopPropertyManager.write(restore.property, restore.value);
        }

        final SharedPreferences.Editor editor = preferences.edit()
                .remove(KEY_APPROVED_VERSION);
        clearManagedItem(editor, ITEM_FREEFORM);
        clearManagedItem(editor, ITEM_RESIZABLE);
        clearManagedItem(editor, ITEM_RESTRICTIONS);
        clearManagedItem(editor, ITEM_ROUNDED_CORNERS);
        if (!commands.isEmpty() || !propertyRestores.isEmpty()) {
            editor.putString(
                    KEY_PENDING_BOOT_ID,
                    before.bootId.isEmpty()
                            ? "unknown-current-boot" : before.bootId);
        } else {
            editor.remove(KEY_PENDING_BOOT_ID);
        }
        if (!editor.commit()) {
            throw new IOException(
                    "could not save restored Shizuku setup state");
        }
        return audit(context, sessionProfile);
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

    private static void addNubiaPropertyChange(
            final SharedPreferences preferences,
            final SharedPreferences.Editor editor,
            final List<NubiaDesktopPropertyManager.Property> properties,
            final String item,
            final NubiaDesktopPropertyManager.Property property,
            final String currentValue,
            final boolean alreadyConfigured) throws IOException {
        if (alreadyConfigured) {
            return;
        }
        if (!NubiaDesktopPropertyManager.isBooleanOrEmpty(currentValue)) {
            throw new IOException(
                    "unexpected value for " + property.key + ": " + currentValue);
        }
        rememberOriginal(preferences, editor, item, currentValue);
        properties.add(property);
    }

    private static void addNubiaPropertyRestore(
            final SharedPreferences preferences,
            final List<PropertyRestore> restores,
            final String item,
            final NubiaDesktopPropertyManager.Property property)
            throws IOException {
        if (!preferences.getBoolean(OWNED_PREFIX + item, false)) {
            return;
        }
        final String original = preferences.getString(
                ORIGINAL_PREFIX + item, VALUE_ABSENT);
        final String value = VALUE_ABSENT.equals(original) ? "" : original;
        if (!NubiaDesktopPropertyManager.isBooleanOrEmpty(value)) {
            throw new IOException(
                    "cannot safely restore " + property.key
                            + ": unexpected saved value " + value);
        }
        restores.add(new PropertyRestore(property, value));
    }

    private static void clearManagedItem(
            final SharedPreferences.Editor editor, final String item) {
        editor.remove(OWNED_PREFIX + item);
        editor.remove(ORIGINAL_PREFIX + item);
    }

    private static boolean hasManagedChanges(final SharedPreferences preferences) {
        return hasManagedWindowingChanges(preferences)
                || preferences.getBoolean(OWNED_PREFIX + ITEM_RESTRICTIONS, false)
                || preferences.getBoolean(OWNED_PREFIX + ITEM_ROUNDED_CORNERS, false);
    }

    private static boolean hasManagedWindowingChanges(
            final SharedPreferences preferences) {
        return preferences.getBoolean(OWNED_PREFIX + ITEM_FREEFORM, false)
                || preferences.getBoolean(OWNED_PREFIX + ITEM_RESIZABLE, false);
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

    private static final class PropertyRestore {
        final NubiaDesktopPropertyManager.Property property;
        final String value;

        PropertyRestore(
                final NubiaDesktopPropertyManager.Property property,
                final String value) {
            this.property = property;
            this.value = value;
        }
    }

    static final class Audit {
        final String runtimeError;
        final ShellAccess.Snapshot shellState;
        final SessionProfile sessionProfile;
        final boolean shellReady;
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
                final String runtimeError,
                final ShellAccess.Snapshot shellState,
                final SessionProfile sessionProfile,
                final boolean shellReady,
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
            this.runtimeError = runtimeError;
            this.shellState = shellState;
            this.sessionProfile = sessionProfile;
            this.shellReady = shellReady;
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

    static boolean isFullWindowingConfigurationReady(
            final boolean freeformEnabled,
            final boolean resizableEnabled,
            final boolean restrictionsDisabled,
            final boolean roundedCornersDisabled) {
        return hasRequiredWindowingSettings(freeformEnabled, resizableEnabled)
                && restrictionsDisabled
                && roundedCornersDisabled;
    }
}
