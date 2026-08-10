package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class NubiaHdmiModeController {
    static final int VENDOR_SIZE_UNCHANGED = -1;
    static final int VENDOR_SIZE_1080 = 0;
    static final int VENDOR_SIZE_1440 = 1;
    static final int VENDOR_SIZE_2160 = 2;

    private static final String TAG = "MagicDeskHdmiMode";
    static final String EDID_MODES =
            "/sys/kernel/lcd_enhance/edid_modes";
    private static final String HPD = "/sys/kernel/lcd_enhance/hpd";
    private static final String DISPLAY_COMMAND =
            "io.github.mekhontsev.magicdesk.ConsoleDisplayCommand";
    private static final long MODE_TIMEOUT_MS = 10_000L;
    private static final long MODE_POLL_MS = 100L;
    private static final Pattern MODE_PATTERN = Pattern.compile(
            "^(\\d+)x(\\d+)\\s+(\\d+)\\s+(\\d+)$");
    private static final Object PROBE_LOCK = new Object();
    private static volatile String sPermanentReadFailure;

    private NubiaHdmiModeController() {
    }

    static Selection readSelection(
            final Context context,
            final int displayId,
            final String preferredTiming) {
        final Selection publicSelection =
                publicSelection(context, displayId, preferredTiming);
        final String cachedFailure = sPermanentReadFailure;
        if (cachedFailure != null) {
            return fallbackSelection(publicSelection, cachedFailure);
        }
        try {
            final String output = ShellAccess.run(
                    "/system/bin/cat " + EDID_MODES);
            final Selection selection = select(
                    preferredTiming, parseModes(output));
            return selection != null
                    ? selection
                    : fallbackSelection(
                            publicSelection, "vendor mode list is empty");
        } catch (IOException | RuntimeException error) {
            final String detail = usefulMessage(error);
            if (isPermanentReadFailure(detail)) {
                synchronized (PROBE_LOCK) {
                    if (sPermanentReadFailure == null) {
                        sPermanentReadFailure = detail;
                    }
                }
            }
            Log.w(TAG, "Nubia HDMI mode list is unavailable", error);
            CompatibilityDiagnostics.record(
                    "NUBIA-DISPLAY-005",
                    "Could not read the external display mode list",
                    detail,
                    error);
            return fallbackSelection(publicSelection, detail);
        }
    }

    private static Selection fallbackSelection(
            final Selection publicSelection,
            final String detail) {
        return publicSelection != null
                ? publicSelection
                : systemSelection((Mode) null, detail);
    }

    static int applyIfNeeded(
            final Context context,
            final int displayId,
            final Selection selection) throws IOException {
        if (selection == null || selection.target == null
                || !selection.configurable
                || selection.target.sameTiming(selection.current)) {
            return displayId;
        }

        final Mode target = selection.target;
        if (selection.controlPath == ControlPath.SYSTEM) {
            try {
                ShellAccess.run("/system/bin/cmd display "
                        + "set-user-preferred-display-mode "
                        + target.width + " " + target.height + " "
                        + target.refreshRate + " " + displayId);
                final int settledDisplayId = waitForMode(context, target);
                if (settledDisplayId <= Display.DEFAULT_DISPLAY) {
                    throw new IOException(
                            "display mode did not settle at " + target);
                }
                Log.i(TAG, "applied Android display mode " + target
                        + " display=" + displayId + "->" + settledDisplayId);
                return settledDisplayId;
            } catch (IOException | RuntimeException error) {
                clearSystemModePreference(displayId, error);
                throw error;
            }
        }

        ShellAccess.run("/system/bin/printf '%s' '"
                + target.width + " " + target.height + " "
                + target.refreshRate + " " + target.pictureAspect
                + "' > " + EDID_MODES);
        SystemClock.sleep(200L);

        boolean hpdLow = false;
        try {
            final String refreshOutput = ShellAccess.run(
                    AppProcessCommand.run(
                            DISPLAY_COMMAND, "refresh -1")).trim();
            if (!refreshOutput.contains("display-command=refresh")) {
                throw new IOException(
                        "Nubia display refresh was rejected: " + refreshOutput);
            }
            ShellAccess.run("/system/bin/printf 0 > " + HPD);
            hpdLow = true;
            SystemClock.sleep(800L);
            ShellAccess.run("/system/bin/printf 1 > " + HPD);
            hpdLow = false;
        } finally {
            if (hpdLow) {
                try {
                    ShellAccess.run("/system/bin/printf 1 > " + HPD);
                } catch (IOException recoveryError) {
                    Log.e(TAG, "Could not restore HDMI HPD", recoveryError);
                }
            }
        }

        final int settledDisplayId = waitForMode(context, target);
        if (settledDisplayId <= Display.DEFAULT_DISPLAY) {
            throw new IOException(
                    "HDMI mode did not settle at " + target);
        }
        Log.i(TAG, "applied HDMI mode " + target
                + " display=" + displayId + "->" + settledDisplayId);
        return settledDisplayId;
    }

    static List<Mode> parseModes(final String output) {
        final ArrayList<Mode> modes = new ArrayList<>();
        if (output == null) {
            return modes;
        }
        for (final String line : output.split("\\r?\\n")) {
            final Matcher matcher = MODE_PATTERN.matcher(line.trim());
            if (!matcher.matches()) {
                continue;
            }
            try {
                final Mode mode = new Mode(
                        Integer.parseInt(matcher.group(1)),
                        Integer.parseInt(matcher.group(2)),
                        Integer.parseInt(matcher.group(3)),
                        Integer.parseInt(matcher.group(4)));
                if (mode.isValid()) {
                    modes.add(mode);
                }
            } catch (NumberFormatException ignored) {
                // Ignore malformed vendor node entries.
            }
        }
        return modes;
    }

    static Selection select(
            final String preferredTiming,
            final List<Mode> modes) {
        if (modes == null || modes.isEmpty()) {
            return null;
        }
        final Mode current = modes.get(0);
        final Mode nativeMode = bestNativeResolution(normalizeModes(modes));
        final List<Mode> availableModes = normalizeModes(
                consoleCompatibleModes(nativeMode, modes));
        Mode target = findMode(availableModes, preferredTiming);
        if (target == null) {
            target = bestNativeResolution(availableModes);
        }
        return new Selection(current, target, availableModes);
    }

    static int resolveVendorSizeType(
            final int physicalWidth,
            final int physicalHeight) {
        final int longSide = Math.max(physicalWidth, physicalHeight);
        final int shortSide = Math.min(physicalWidth, physicalHeight);
        if (longSide == 1920 && shortSide == 1080) {
            return VENDOR_SIZE_1080;
        }
        if (longSide == 2560 && shortSide == 1440) {
            return VENDOR_SIZE_1440;
        }
        if (longSide >= 3840 && shortSide == 2160) {
            return VENDOR_SIZE_2160;
        }
        return VENDOR_SIZE_UNCHANGED;
    }

    private static List<Mode> consoleCompatibleModes(
            final Mode nativeMode,
            final List<Mode> modes) {
        final ArrayList<Mode> compatible = new ArrayList<>();
        for (final Mode mode : modes) {
            if (mode.sameResolution(nativeMode)
                    || resolveVendorSizeType(mode.width, mode.height)
                            != VENDOR_SIZE_UNCHANGED) {
                compatible.add(mode);
            }
        }
        return compatible;
    }

    private static Selection publicSelection(
            final Context context,
            final int displayId,
            final String preferredTiming) {
        final DisplayManager manager = context == null
                ? null : context.getSystemService(DisplayManager.class);
        final Display display = manager == null
                ? null : manager.getDisplay(displayId);
        final Display.Mode displayMode = display == null
                ? null : display.getMode();
        if (displayMode == null) {
            return null;
        }
        final Mode current = fromDisplayMode(displayMode);
        final ArrayList<Mode> modes = new ArrayList<>();
        for (final Display.Mode mode : display.getSupportedModes()) {
            final Mode converted = fromDisplayMode(mode);
            if (converted.isValid()) {
                modes.add(converted);
            }
        }
        if (modes.isEmpty()) {
            modes.add(current);
        }
        return systemModeSelection(current, modes, preferredTiming);
    }

    static Selection systemModeSelection(
            final Mode current,
            final List<Mode> modes,
            final String preferredTiming) {
        final List<Mode> availableModes = normalizeModes(modes);
        Mode target = findMode(availableModes, preferredTiming);
        if (target == null) {
            target = current;
        }
        return new Selection(
                current,
                target,
                availableModes,
                true,
                "Android DisplayManager",
                ControlPath.SYSTEM);
    }

    private static Mode fromDisplayMode(final Display.Mode mode) {
        return new Mode(
                mode.getPhysicalWidth(),
                mode.getPhysicalHeight(),
                Math.max(1, Math.round(mode.getRefreshRate())),
                0);
    }

    private static void clearSystemModePreference(
            final int displayId,
            final Throwable originalError) {
        try {
            ShellAccess.run("/system/bin/cmd display "
                    + "clear-user-preferred-display-mode " + displayId);
        } catch (IOException | RuntimeException cleanupError) {
            originalError.addSuppressed(cleanupError);
        }
    }

    static Selection systemSelection(
            final Mode mode,
            final String detail) {
        final List<Mode> modes = mode == null
                ? Collections.emptyList()
                : Collections.singletonList(mode);
        return new Selection(mode, mode, modes, false, detail);
    }

    private static boolean isPermanentReadFailure(final String detail) {
        return detail.contains("Permission denied")
                || detail.contains("No such file or directory");
    }

    private static String usefulMessage(final Throwable error) {
        final String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message.trim();
    }

    private static List<Mode> normalizeModes(final List<Mode> modes) {
        final Map<String, Mode> uniqueModes = new LinkedHashMap<>();
        for (final Mode mode : modes) {
            final String timing = mode.timingKey();
            final Mode existing = uniqueModes.get(timing);
            if (existing == null
                    || mode.pictureAspect > existing.pictureAspect) {
                uniqueModes.put(timing, mode);
            }
        }
        final ArrayList<Mode> sorted = new ArrayList<>(uniqueModes.values());
        Collections.sort(sorted, new Comparator<Mode>() {
            @Override
            public int compare(final Mode left, final Mode right) {
                int result = Integer.compare(right.height, left.height);
                if (result == 0) {
                    result = Integer.compare(right.width, left.width);
                }
                if (result == 0) {
                    result = Integer.compare(
                            right.refreshRate, left.refreshRate);
                }
                return result;
            }
        });
        return Collections.unmodifiableList(sorted);
    }

    private static Mode findMode(
            final List<Mode> modes,
            final String timing) {
        if (timing == null || timing.isEmpty()) {
            return null;
        }
        for (final Mode mode : modes) {
            if (timing.equals(mode.timingKey())) {
                return mode;
            }
        }
        return null;
    }

    private static Mode bestNativeResolution(final List<Mode> modes) {
        int maxHeight = 0;
        for (final Mode mode : modes) {
            maxHeight = Math.max(maxHeight, mode.height);
        }

        boolean hasNonCinemaMode = false;
        for (final Mode mode : modes) {
            if (mode.height == maxHeight && mode.pictureAspect != 4) {
                hasNonCinemaMode = true;
                break;
            }
        }

        Mode best = null;
        for (final Mode mode : modes) {
            if (mode.height != maxHeight
                    || (hasNonCinemaMode && mode.pictureAspect == 4)) {
                continue;
            }
            if (best == null
                    || mode.width > best.width
                    || (mode.width == best.width
                            && mode.refreshRate > best.refreshRate)
                    || (mode.width == best.width
                            && mode.refreshRate == best.refreshRate
                            && mode.pictureAspect > best.pictureAspect)) {
                best = mode;
            }
        }
        return best;
    }

    private static int waitForMode(
            final Context context,
            final Mode expected) {
        final DisplayManager manager =
                context.getSystemService(DisplayManager.class);
        final long deadline = SystemClock.uptimeMillis() + MODE_TIMEOUT_MS;
        int stableDisplayId = -1;
        int stableSamples = 0;
        while (SystemClock.uptimeMillis() < deadline) {
            if (manager != null) {
                final int displayId =
                        ConsoleDisplayController.findExternalDisplayId();
                final Display display = displayId <= Display.DEFAULT_DISPLAY
                        ? null : manager.getDisplay(displayId);
                final Display.Mode mode = display == null
                        ? null : display.getMode();
                if (mode != null
                        && mode.getPhysicalWidth() == expected.width
                        && mode.getPhysicalHeight() == expected.height
                        && Math.abs(mode.getRefreshRate()
                                - expected.refreshRate) < 0.1f) {
                    if (displayId == stableDisplayId) {
                        stableSamples++;
                    } else {
                        stableDisplayId = displayId;
                        stableSamples = 1;
                    }
                    if (stableSamples >= 3) {
                        return displayId;
                    }
                } else {
                    stableDisplayId = -1;
                    stableSamples = 0;
                }
            }
            SystemClock.sleep(MODE_POLL_MS);
        }
        return -1;
    }

    static final class Selection {
        final Mode current;
        final Mode target;
        final List<Mode> availableModes;
        final boolean configurable;
        final String detail;
        final ControlPath controlPath;

        Selection(
                final Mode current,
                final Mode target,
                final List<Mode> availableModes) {
            this(
                    current,
                    target,
                    availableModes,
                    true,
                    "",
                    ControlPath.VENDOR);
        }

        Selection(
                final Mode current,
                final Mode target,
                final List<Mode> availableModes,
                final boolean configurable,
                final String detail) {
            this(
                    current,
                    target,
                    availableModes,
                    configurable,
                    detail,
                    ControlPath.NONE);
        }

        Selection(
                final Mode current,
                final Mode target,
                final List<Mode> availableModes,
                final boolean configurable,
                final String detail,
                final ControlPath controlPath) {
            this.current = current;
            this.target = target;
            this.availableModes = availableModes;
            this.configurable = configurable;
            this.detail = detail == null ? "" : detail;
            this.controlPath = controlPath;
        }

        Selection withPreferredTiming(final String preferredTiming) {
            if (!configurable) {
                return this;
            }
            Mode preferred = findMode(availableModes, preferredTiming);
            if (preferred == null) {
                preferred = controlPath == ControlPath.SYSTEM
                        ? current : bestNativeResolution(availableModes);
            }
            return new Selection(
                    current,
                    preferred,
                    availableModes,
                    true,
                    detail,
                    controlPath);
        }
    }

    private enum ControlPath {
        SYSTEM,
        VENDOR,
        NONE
    }

    static final class Mode {
        final int width;
        final int height;
        final int refreshRate;
        final int pictureAspect;

        Mode(
                final int width,
                final int height,
                final int refreshRate,
                final int pictureAspect) {
            this.width = width;
            this.height = height;
            this.refreshRate = refreshRate;
            this.pictureAspect = pictureAspect;
        }

        boolean isValid() {
            return width > 0 && height > 0 && refreshRate > 0
                    && pictureAspect >= 0;
        }

        boolean sameTiming(final Mode other) {
            return other != null
                    && width == other.width
                    && height == other.height
                    && refreshRate == other.refreshRate
                    && pictureAspect == other.pictureAspect;
        }

        boolean sameResolution(final Mode other) {
            return other != null
                    && width == other.width
                    && height == other.height;
        }

        String timingKey() {
            return width + "x" + height + "@" + refreshRate;
        }

        String displayLabel() {
            return width + " x " + height + " @ " + refreshRate + " Hz";
        }

        @Override
        public String toString() {
            return width + "x" + height + "@" + refreshRate
                    + " aspect=" + pictureAspect;
        }
    }
}
