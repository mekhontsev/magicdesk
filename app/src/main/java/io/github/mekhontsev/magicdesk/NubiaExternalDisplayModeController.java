package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.util.Log;
import android.view.Display;

import java.io.IOException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class NubiaExternalDisplayModeController {
    private static final String TAG = "MagicDeskDisplayMode";
    private static final String GETPROP = "/system/bin/getprop";
    private static final String SETPROP = "/system/bin/setprop";
    private static final String SETTINGS = "/system/bin/settings";
    private static final String DISPLAY = "/system/bin/cmd display";
    private static final String FIT_ALL_PROPERTY =
            "debug.nubia.fitalltodisplay";
    private static final String FIT_SETTING = "app_mirror_fit_status";
    private static final String SIZE_SETTING = "app_mirror_size_type";
    private static final Pattern DISPLAY_MODE_PATTERN = Pattern.compile(
            "(?:Boot|User preferred) display mode:\\s+"
                    + "(\\d+)\\s+(\\d+)\\s+([0-9]+(?:\\.[0-9]+)?)");

    private NubiaExternalDisplayModeController() {
    }

    static PreparedMode prepare(
            final Context context,
            final int physicalDisplayId) throws IOException {
        final ExternalDisplayLaunchSettings.Config config =
                ExternalDisplayLaunchSettings.load(context);
        final Display.Mode currentMode = physicalMode(context, physicalDisplayId);
        final PhysicalMode nativeMode = config.outputMode
                == ExternalDisplayLaunchSettings.OutputMode.NATIVE
                ? nativeMode(physicalDisplayId) : null;
        final int width = nativeMode != null
                ? nativeMode.width
                : currentMode == null ? 0 : currentMode.getPhysicalWidth();
        final int height = nativeMode != null
                ? nativeMode.height
                : currentMode == null ? 0 : currentMode.getPhysicalHeight();
        final int sizeType =
                ExternalDisplayLaunchSettings.resolveVendorSizeType(
                        config.outputMode, width, height);
        final String previousBypass = readBypass();
        final PreparedMode prepared = new PreparedMode(previousBypass);
        try {
            writeBypass("1");
            applyPhysicalMode(
                    config.outputMode, physicalDisplayId, nativeMode);
            ShellAccess.run(
                    SETTINGS + " put global " + FIT_SETTING + " "
                            + (config.fillDisplay ? "1" : "0"));
            if (sizeType != ExternalDisplayLaunchSettings.VENDOR_SIZE_UNCHANGED) {
                ShellAccess.run(
                        SETTINGS + " put global " + SIZE_SETTING + " "
                                + sizeType);
            }
            Log.i(TAG, "prepared Nubia output mode display=" + physicalDisplayId
                    + " physical=" + width + "x" + height
                    + (nativeMode == null ? "" : "@" + nativeMode.refreshRate)
                    + " fill=" + config.fillDisplay
                    + " output=" + config.outputMode
                    + " vendorSize=" + sizeType);
            return prepared;
        } catch (IOException | RuntimeException error) {
            prepared.close();
            throw error;
        }
    }

    private static Display.Mode physicalMode(
            final Context context, final int displayId) {
        if (context == null || displayId <= Display.DEFAULT_DISPLAY) {
            return null;
        }
        final DisplayManager manager =
                context.getSystemService(DisplayManager.class);
        final Display display = manager == null
                ? null : manager.getDisplay(displayId);
        return display == null ? null : display.getMode();
    }

    private static PhysicalMode nativeMode(final int displayId) {
        try {
            final String output = ShellAccess.run(
                    DISPLAY + " get-active-display-mode-at-start " + displayId);
            final PhysicalMode mode = parsePhysicalMode(output);
            if (mode != null) {
                return mode;
            }
            recordNativeModeFailure(
                    "Unexpected display-mode response: " + output.trim());
        } catch (IOException | RuntimeException error) {
            recordNativeModeFailure(error.getMessage());
        }
        return null;
    }

    private static void applyPhysicalMode(
            final ExternalDisplayLaunchSettings.OutputMode outputMode,
            final int displayId,
            final PhysicalMode nativeMode) throws IOException {
        if (outputMode != ExternalDisplayLaunchSettings.OutputMode.NATIVE) {
            ShellAccess.run(
                    DISPLAY + " clear-user-preferred-display-mode " + displayId);
            return;
        }
        if (nativeMode == null) {
            return;
        }
        ShellAccess.run(
                DISPLAY + " set-user-preferred-display-mode "
                        + nativeMode.width + " "
                        + nativeMode.height + " "
                        + String.format(Locale.ROOT, "%.5f", nativeMode.refreshRate)
                        + " " + displayId);
        final PhysicalMode observed = parsePhysicalMode(ShellAccess.run(
                DISPLAY + " get-user-preferred-display-mode " + displayId));
        if (!nativeMode.sameMode(observed)) {
            throw new IOException(
                    "native display mode was not accepted: expected="
                            + nativeMode + " observed=" + observed);
        }
    }

    static PhysicalMode parsePhysicalMode(final String output) {
        if (output == null) {
            return null;
        }
        final Matcher matcher = DISPLAY_MODE_PATTERN.matcher(output);
        if (!matcher.find()) {
            return null;
        }
        try {
            final int width = Integer.parseInt(matcher.group(1));
            final int height = Integer.parseInt(matcher.group(2));
            final float refreshRate = Float.parseFloat(matcher.group(3));
            if (width <= 0 || height <= 0 || refreshRate <= 0f) {
                return null;
            }
            return new PhysicalMode(width, height, refreshRate);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static void recordNativeModeFailure(final String detail) {
        Log.w(TAG, "Cannot determine the native external display mode: " + detail);
        CompatibilityDiagnostics.record(
                "NUBIA-DISPLAY-004",
                "Could not determine the native external display mode",
                detail);
    }

    private static String readBypass() throws IOException {
        final String value = ShellAccess.run(
                GETPROP + " " + FIT_ALL_PROPERTY).trim();
        if (isBooleanProperty(value)) {
            return value;
        }
        throw new IOException(
                "unexpected " + FIT_ALL_PROPERTY + " value: " + value);
    }

    private static void writeBypass(final String value) throws IOException {
        if (!isBooleanProperty(value)) {
            throw new IllegalArgumentException("invalid fit bypass value");
        }
        ShellAccess.run(
                SETPROP + " " + FIT_ALL_PROPERTY + " '" + value + "'");
        final String observed = ShellAccess.run(
                GETPROP + " " + FIT_ALL_PROPERTY).trim();
        if (!value.equals(observed)) {
            throw new IOException(
                    "could not update " + FIT_ALL_PROPERTY
                            + ": expected=" + printable(value)
                            + " observed=" + printable(observed));
        }
    }

    static boolean isBooleanProperty(final String value) {
        return value != null
                && (value.isEmpty()
                        || "0".equals(value)
                        || "1".equals(value)
                        || "false".equals(value)
                        || "true".equals(value));
    }

    private static String printable(final String value) {
        return value.isEmpty() ? "<empty>" : value;
    }

    static final class PreparedMode implements AutoCloseable {
        private final String mPreviousBypass;
        private boolean mClosed;

        PreparedMode(final String previousBypass) {
            mPreviousBypass = previousBypass;
        }

        @Override
        public void close() {
            if (mClosed) {
                return;
            }
            mClosed = true;
            try {
                writeBypass(mPreviousBypass);
            } catch (IOException error) {
                Log.w(TAG, "Cannot restore Nubia display-fit bypass", error);
                CompatibilityDiagnostics.record(
                        "NUBIA-DISPLAY-002",
                        "Could not restore the external display compatibility flag",
                        error.getMessage(),
                        error);
            }
        }
    }

    static final class PhysicalMode {
        final int width;
        final int height;
        final float refreshRate;

        PhysicalMode(
                final int width,
                final int height,
                final float refreshRate) {
            this.width = width;
            this.height = height;
            this.refreshRate = refreshRate;
        }

        boolean sameMode(final PhysicalMode other) {
            return other != null
                    && width == other.width
                    && height == other.height
                    && Math.abs(refreshRate - other.refreshRate) < 0.01f;
        }

        @Override
        public String toString() {
            return width + "x" + height + "@" + refreshRate;
        }
    }
}
