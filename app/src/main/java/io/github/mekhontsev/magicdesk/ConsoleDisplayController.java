package io.github.mekhontsev.magicdesk;

import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class ConsoleDisplayController {
    static final long START_TIMEOUT_MS = 10_000L;
    static final long STATE_POLL_MS = 100L;

    private static final String TAG = "MagicDeskConsoleDisplay";
    private static final String SETTINGS = "/system/bin/settings";
    private static final String DISPLAY = "/system/bin/cmd display";
    private static final String WM = "/system/bin/wm";
    private static final String CONSOLE_DISPLAY_COMMAND =
            "io.github.mekhontsev.magicdesk.ConsoleDisplayCommand";
    private static final long DENSITY_APPLY_TIMEOUT_MS = 2_000L;
    private static final Pattern DISPLAY_UNIQUE_ID_PATTERN = Pattern.compile(
            "Display id (\\d+):.*?uniqueId \"([^\"]+)\"");
    private static final Pattern WM_SIZE_PATTERN =
            Pattern.compile("(?:Physical|Override) size: (\\d+)x(\\d+)");
    private static final Pattern WM_DENSITY_PATTERN =
            Pattern.compile("Override density: (\\d+)");

    private ConsoleDisplayController() {
    }

    static int getActiveConsoleDisplayId() {
        final int displayId = getMirrorDisplayId();
        return displayId > 0 && displayExists(displayId) ? displayId : -1;
    }

    static int findExternalDisplayId() {
        return findFirstDisplayId(runCommand(
                DISPLAY + " get-displays --ids-only --type external"));
    }

    static int findWirelessDisplayId() {
        return findFirstDisplayId(runCommand(
                DISPLAY + " get-displays --ids-only --type wifi"));
    }

    static int findFirstDisplayId(final String output) {
        if (output == null) {
            return -1;
        }
        for (final String line : output.split("\\r?\\n")) {
            try {
                final int displayId = Integer.parseInt(line.trim());
                if (displayId > 0) {
                    return displayId;
                }
            } catch (NumberFormatException ignored) {
                // Continue past diagnostics and unsupported display entries.
            }
        }
        return -1;
    }

    static boolean requestConsoleMode(final int externalDisplayId) {
        final String output = runCommand(
                AppProcessCommand.run(
                        CONSOLE_DISPLAY_COMMAND,
                        "expand " + externalDisplayId)).trim();
        if (!output.contains("display-command=expand")) {
            Log.w(TAG, "Console mode request failed output=" + output);
            CompatibilityDiagnostics.record(
                    "NUBIA-CONSOLE-003",
                    "The firmware rejected the external desktop request",
                    output);
            return false;
        }
        return true;
    }

    static boolean requestMirrorMode() {
        final String output = runCommand(
                AppProcessCommand.run(
                        CONSOLE_DISPLAY_COMMAND,
                        "mirror 0")).trim();
        if (output.contains("display-command=mirror")) {
            return true;
        }
        Log.w(TAG, "Mirror mode request failed output=" + output);
        return false;
    }

    static boolean isMirrorMode() {
        return "0".equals(runCommand(
                SETTINGS + " get global app_mirror_status").trim());
    }

    static int waitForConsoleDisplay() {
        final long deadline = SystemClock.uptimeMillis() + START_TIMEOUT_MS;
        while (SystemClock.uptimeMillis() < deadline) {
            final int displayId = getActiveConsoleDisplayId();
            if (displayId > 0) {
                return displayId;
            }
            SystemClock.sleep(STATE_POLL_MS);
        }
        return -1;
    }

    static boolean waitForConsoleStop() {
        final long deadline = SystemClock.uptimeMillis() + START_TIMEOUT_MS;
        while (SystemClock.uptimeMillis() < deadline) {
            if (getActiveConsoleDisplayId() <= 0) {
                return true;
            }
            SystemClock.sleep(STATE_POLL_MS);
        }
        return false;
    }

    static boolean displayExists(final int displayId) {
        final String output = runCommand(
                DISPLAY + " get-displays --ids-only");
        for (final String line : output.split("\\r?\\n")) {
            if (line.trim().equals(Integer.toString(displayId))) {
                return true;
            }
        }
        return false;
    }

    static void applyStartupDensity(final int displayId, final int dpi) {
        final String command = dpi == DesktopPreferences.SYSTEM_DESKTOP_DPI
                ? WM + " density reset -d " + displayId
                : WM + " density " + dpi + " -d " + displayId;
        final String output = runCommand(command).trim();
        Log.i(TAG, "prepared Console display density display=" + displayId
                + " dpi=" + dpi + " output="
                + output.replace('\n', ' '));
        final long deadline =
                SystemClock.uptimeMillis() + DENSITY_APPLY_TIMEOUT_MS;
        while (SystemClock.uptimeMillis() < deadline) {
            final String state = runCommand(
                    WM + " density -d " + displayId);
            final Matcher matcher = WM_DENSITY_PATTERN.matcher(state);
            final boolean hasOverride = matcher.find();
            if ((dpi == DesktopPreferences.SYSTEM_DESKTOP_DPI
                    && !hasOverride)
                    || (dpi > 0 && hasOverride
                    && Integer.toString(dpi).equals(matcher.group(1)))) {
                return;
            }
            SystemClock.sleep(STATE_POLL_MS);
        }
        Log.w(TAG, "Console display density did not settle display="
                + displayId + " dpi=" + dpi);
    }

    static void ensureLandscape(final int displayId)
            throws IOException {
        final String output = ShellAccess.run(
                WM + " size -d " + displayId);
        final Matcher matcher = WM_SIZE_PATTERN.matcher(output);
        int width = -1;
        int height = -1;
        while (matcher.find()) {
            width = Integer.parseInt(matcher.group(1));
            height = Integer.parseInt(matcher.group(2));
        }
        if (width <= 0 || height <= 0) {
            throw new IOException("could not read Console display size: "
                    + output.trim());
        }
        if (width < height) {
            ShellAccess.run(
                    WM + " size " + height + "x" + width
                            + " -d " + displayId);
        }
        ShellAccess.run(
                WM + " fixed-to-user-rotation -d " + displayId + " enabled");
        ShellAccess.run(
                WM + " user-rotation -d " + displayId + " lock 0");
    }

    static String getPhysicalDisplayId(final int displayId)
            throws IOException {
        if (displayId < 0) {
            throw new IllegalArgumentException("invalid logical display id");
        }
        final String output = ShellAccess.run(DISPLAY + " get-displays");
        final String uniqueId = parseDisplayUniqueId(output, displayId);
        if (uniqueId == null || !uniqueId.startsWith("local:")) {
            throw new IOException(
                    "physical display id unavailable for logical display "
                            + displayId);
        }
        return uniqueId.substring("local:".length());
    }

    static String parsePhysicalDisplayId(
            final String output,
            final int displayId) {
        final String uniqueId = parseDisplayUniqueId(output, displayId);
        return uniqueId != null && uniqueId.startsWith("local:")
                ? uniqueId.substring("local:".length()) : null;
    }

    static String getDisplayUniqueId(final int displayId)
            throws IOException {
        if (displayId < 0) {
            throw new IllegalArgumentException("invalid logical display id");
        }
        final String uniqueId = parseDisplayUniqueId(
                ShellAccess.run(DISPLAY + " get-displays"), displayId);
        if (uniqueId == null || uniqueId.isEmpty()) {
            throw new IOException(
                    "display unique id unavailable for logical display "
                            + displayId);
        }
        return uniqueId;
    }

    static String parseDisplayUniqueId(
            final String output,
            final int displayId) {
        final Matcher matcher = DISPLAY_UNIQUE_ID_PATTERN.matcher(
                output == null ? "" : output);
        while (matcher.find()) {
            if (Integer.parseInt(matcher.group(1)) == displayId) {
                return matcher.group(2);
            }
        }
        return null;
    }

    static DisplaySize getDisplaySize(final int displayId) throws IOException {
        if (displayId < 0) {
            throw new IllegalArgumentException("invalid logical display id");
        }
        final String output = ShellAccess.run(
                WM + " size -d " + displayId);
        final Matcher matcher = WM_SIZE_PATTERN.matcher(output);
        int width = -1;
        int height = -1;
        while (matcher.find()) {
            width = Integer.parseInt(matcher.group(1));
            height = Integer.parseInt(matcher.group(2));
        }
        if (width <= 0 || height <= 0) {
            throw new IOException(
                    "could not read display size for " + displayId + ": "
                            + output.trim());
        }
        return new DisplaySize(width, height);
    }

    private static int getMirrorDisplayId() {
        final String output = runCommand(
                SETTINGS + " get global app_mirror_displayid");
        try {
            return Integer.parseInt(output.trim());
        } catch (NumberFormatException error) {
            Log.w(TAG, "bad app_mirror_displayid: " + output);
            return -1;
        }
    }

    private static String runCommand(final String command) {
        if (!ShellAccess.isReady()) {
            return "";
        }
        try {
            return ShellAccess.run(command);
        } catch (IOException error) {
            Log.w(TAG, "display command failed: " + command, error);
            return "";
        }
    }

    static final class DisplaySize {
        final int width;
        final int height;

        DisplaySize(final int width, final int height) {
            this.width = width;
            this.height = height;
        }
    }

}
