package io.github.mekhontsev.magicdesk.platform.nubia;

import io.github.mekhontsev.magicdesk.AppProcessCommand;
import io.github.mekhontsev.magicdesk.CompatibilityDiagnostics;
import io.github.mekhontsev.magicdesk.ConsoleDisplayController;
import io.github.mekhontsev.magicdesk.ShellAccess;
import io.github.mekhontsev.magicdesk.SocDisplayModeBackend;
import io.github.mekhontsev.magicdesk.SocDisplayModeBackends;
import io.github.mekhontsev.magicdesk.display.DisplayTiming;
import io.github.mekhontsev.magicdesk.display.DisplayTimingPolicy;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
            "io.github.mekhontsev.magicdesk.platform.nubia.ConsoleDisplayCommand";
    private static final long MODE_TIMEOUT_MS = 10_000L;
    private static final long MODE_POLL_MS = 100L;
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
            return fallbackSelection(
                    preferredTiming, publicSelection, cachedFailure);
        }
        try {
            final String output = ShellAccess.run(
                    "/system/bin/cat " + EDID_MODES);
            final Selection selection = select(
                    preferredTiming, parseModes(output));
            return selection != null
                    ? selection
                    : fallbackSelection(
                            preferredTiming,
                            publicSelection,
                            "vendor mode list is empty");
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
            return fallbackSelection(
                    preferredTiming, publicSelection, detail);
        }
    }

    private static Selection fallbackSelection(
            final String preferredTiming,
            final Selection publicSelection,
            final String detail) {
        try {
            final Selection soc = readSocSelection(preferredTiming);
            if (soc != null && addsTiming(soc, publicSelection)) {
                return soc;
            }
        } catch (IOException | RuntimeException error) {
            Log.i(TAG, "SoC display mode fallback is unavailable", error);
        }
        return publicSelection != null
                ? publicSelection
                : systemSelection((Mode) null, detail);
    }

    static boolean addsTiming(
            final Selection candidate,
            final Selection baseline) {
        if (candidate == null) {
            return false;
        }
        if (baseline == null) {
            return !candidate.availableModes.isEmpty();
        }
        for (Mode candidateMode : candidate.availableModes) {
            if (findMode(
                    baseline.availableModes,
                    candidateMode.timingKey()) == null) {
                return true;
            }
        }
        return false;
    }

    private static Selection readSocSelection(
            final String preferredTiming) throws IOException {
        final SocDisplayModeBackend.Snapshot snapshot =
                SocDisplayModeBackends.queryExternal();
        if (snapshot == null
                || !snapshot.connected
                || snapshot.modes.isEmpty()) {
            return null;
        }
        final List<Mode> modes = new ArrayList<>();
        for (final SocDisplayModeBackend.Mode config : snapshot.modes) {
            final Mode mode = new Mode(
                    config.width,
                    config.height,
                    config.refreshRate,
                    0);
            if (mode.isValid()) {
                modes.add(mode);
            }
        }
        final SocDisplayModeBackend.Mode activeConfig =
                snapshot.active();
        final Mode current = activeConfig == null
                ? null : new Mode(
                        activeConfig.width,
                        activeConfig.height,
                        activeConfig.refreshRate,
                        0);
        return socSelection(
                snapshot.backendId,
                snapshot.backendName,
                current,
                modes,
                preferredTiming);
    }

    static Selection socSelection(
            final String backendId,
            final String backendName,
            final Mode reportedCurrent,
            final List<Mode> modes,
            final String preferredTiming) {
        final List<Mode> availableModes = normalizeModes(modes);
        if (availableModes.isEmpty()) {
            return null;
        }
        Mode current = reportedCurrent == null
                ? null : findMode(
                        availableModes, reportedCurrent.timingKey());
        if (current == null) {
            current = availableModes.get(0);
        }
        Mode target = findMode(availableModes, preferredTiming);
        if (target == null) {
            target = bestNativeResolution(availableModes);
        }
        return new Selection(
                current,
                target,
                availableModes,
                true,
                backendName,
                ControlPath.SOC,
                false,
                backendId);
    }

    static int applyIfNeeded(
            final Context context,
            final int displayId,
            final Selection selection) throws IOException {
        if (selection != null && selection.isSystemDefaultRequested()) {
            // System/native means MagicDesk does not own the output mode.
            return displayId;
        }
        if (selection == null || selection.target == null
                || !selection.configurable
                || selection.target.sameTiming(selection.current)) {
            return displayId;
        }
        return applyMode(context, displayId, selection);
    }

    static void clearSystemModePreference(final int displayId)
            throws IOException {
        ShellAccess.run("/system/bin/cmd display "
                + "clear-user-preferred-display-mode " + displayId);
        Log.i(TAG, "released Android display mode preference display="
                + displayId);
    }

    static int applyMode(
            final Context context,
            final int displayId,
            final Selection selection) throws IOException {
        if (selection == null || selection.target == null
                || !selection.configurable) {
            return displayId;
        }
        final Mode target = selection.target;
        if (selection.controlPath == ControlPath.SOC) {
            SocDisplayModeBackends.applyExternalTiming(
                    selection.socBackendId,
                    target.timingKey());
            final int settledDisplayId = waitForMode(context, target);
            if (settledDisplayId <= Display.DEFAULT_DISPLAY) {
                throw new IOException(
                        "SoC display mode did not settle at " + target);
            }
            Log.i(TAG, "applied SoC display mode " + target
                    + " display=" + displayId + "->" + settledDisplayId);
            return settledDisplayId;
        }
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
        for (final DisplayTiming timing
                : DisplayTimingPolicy.parseNubiaModes(output)) {
            modes.add(new Mode(
                    timing.width(),
                    timing.height(),
                    timing.refreshRate(),
                    timing.pictureAspect()));
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
            final Mode target,
            final List<Mode> modes) {
        if (target == null || modes == null) {
            return VENDOR_SIZE_UNCHANGED;
        }
        final int[] shortSides = {1080, 1440, 2160};
        for (int sizeType = 0; sizeType < shortSides.length; sizeType++) {
            final Mode preferred = findVendorPreferredMode(
                    modes, shortSides[sizeType]);
            if (target.sameResolution(preferred)) {
                return sizeType;
            }
        }
        return VENDOR_SIZE_UNCHANGED;
    }

    private static List<Mode> consoleCompatibleModes(
            final Mode nativeMode,
            final List<Mode> modes) {
        final ArrayList<Mode> compatible = new ArrayList<>();
        for (final Mode mode : modes) {
            if (mode.sameResolution(nativeMode)
                    || resolveVendorSizeType(mode, modes)
                            != VENDOR_SIZE_UNCHANGED) {
                compatible.add(mode);
            }
        }
        return compatible;
    }

    private static Mode findVendorPreferredMode(
            final List<Mode> modes,
            final int shortSide) {
        Mode preferred = null;
        for (final Mode mode : modes) {
            if (Math.min(mode.width, mode.height) != shortSide
                    || mode.refreshRate < 60
                    || mode.refreshRate > 120) {
                continue;
            }
            if (preferred == null || vendorPrefers(mode, preferred, shortSide)) {
                preferred = mode;
            }
        }
        return preferred;
    }

    private static boolean vendorPrefers(
            final Mode candidate,
            final Mode current,
            final int shortSide) {
        final boolean candidate16By9 = is16By9(candidate);
        final boolean current16By9 = is16By9(current);
        if (candidate16By9 != current16By9) {
            return candidate16By9;
        }

        final int candidateLongSide = Math.max(
                candidate.width, candidate.height);
        final int currentLongSide = Math.max(current.width, current.height);
        if (candidateLongSide != currentLongSide) {
            return candidateLongSide < currentLongSide;
        }

        if (candidate.refreshRate != current.refreshRate) {
            if (shortSide == 1440 || shortSide == 2160) {
                return candidate.refreshRate < current.refreshRate;
            }
            return candidate.refreshRate > current.refreshRate;
        }
        return candidate.pictureAspect > current.pictureAspect;
    }

    private static boolean is16By9(final Mode mode) {
        final int longSide = Math.max(mode.width, mode.height);
        final int shortSide = Math.min(mode.width, mode.height);
        return longSide * 9 == shortSide * 16;
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
        final boolean systemDefaultRequested = target == null;
        if (target == null) {
            target = current;
        }
        return new Selection(
                current,
                target,
                availableModes,
                true,
                "Android DisplayManager",
                ControlPath.SYSTEM,
                systemDefaultRequested);
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
        return DisplayTimingPolicy.normalize(modes);
    }

    private static Mode findMode(
            final List<Mode> modes,
            final String timing) {
        return DisplayTimingPolicy.find(modes, timing);
    }

    private static Mode bestNativeResolution(final List<Mode> modes) {
        return DisplayTimingPolicy.bestNative(modes);
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
        final boolean systemDefaultRequested;
        final String socBackendId;

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
                    ControlPath.VENDOR,
                    false,
                    "");
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
                    ControlPath.NONE,
                    false,
                    "");
        }

        Selection(
                final Mode current,
                final Mode target,
                final List<Mode> availableModes,
                final boolean configurable,
                final String detail,
                final ControlPath controlPath,
                final boolean systemDefaultRequested) {
            this(
                    current,
                    target,
                    availableModes,
                    configurable,
                    detail,
                    controlPath,
                    systemDefaultRequested,
                    "");
        }

        Selection(
                final Mode current,
                final Mode target,
                final List<Mode> availableModes,
                final boolean configurable,
                final String detail,
                final ControlPath controlPath,
                final boolean systemDefaultRequested,
                final String socBackendId) {
            this.current = current;
            this.target = target;
            this.availableModes = availableModes;
            this.configurable = configurable;
            this.detail = detail == null ? "" : detail;
            this.controlPath = controlPath;
            this.systemDefaultRequested = systemDefaultRequested;
            this.socBackendId = socBackendId == null ? "" : socBackendId;
        }

        int vendorSizeType() {
            return controlPath == ControlPath.VENDOR
                    ? resolveVendorSizeType(target, availableModes)
                    : VENDOR_SIZE_UNCHANGED;
        }

        boolean requiresDeferredMode() {
            return (controlPath == ControlPath.VENDOR
                    || controlPath == ControlPath.SOC)
                    && target != null
                    && (controlPath == ControlPath.SOC
                            || vendorSizeType() == VENDOR_SIZE_UNCHANGED);
        }

        boolean supportsSystemDefault() {
            return configurable && controlPath == ControlPath.SYSTEM;
        }

        boolean isSystemDefaultRequested() {
            return supportsSystemDefault() && systemDefaultRequested;
        }

        Selection withPreferredTiming(final String preferredTiming) {
            if (!configurable) {
                return this;
            }
            Mode preferred = findMode(availableModes, preferredTiming);
            final boolean useSystemDefault = controlPath == ControlPath.SYSTEM
                    && preferred == null;
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
                    controlPath,
                    useSystemDefault,
                    socBackendId);
        }
    }

    private enum ControlPath {
        SYSTEM,
        VENDOR,
        SOC,
        NONE
    }

    static final class Mode implements DisplayTiming {
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

        @Override
        public boolean isValid() {
            return width > 0 && height > 0 && refreshRate > 0
                    && pictureAspect >= 0;
        }

        @Override
        public int width() {
            return width;
        }

        @Override
        public int height() {
            return height;
        }

        @Override
        public int refreshRate() {
            return refreshRate;
        }

        @Override
        public int pictureAspect() {
            return pictureAspect;
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

        @Override
        public String timingKey() {
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
