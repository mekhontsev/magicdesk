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
    private static final long LANDSCAPE_APPLY_TIMEOUT_MS = 2_000L;
    private static final Pattern DISPLAY_REAL_SIZE_PATTERN =
            Pattern.compile("Display id (\\d+): .* real (\\d+) x (\\d+),");
    private static final Pattern EXTERNAL_PHYSICAL_DISPLAY_PATTERN =
            Pattern.compile("type EXTERNAL,.*?uniqueId \"local:([0-9]+)\"");
    private static final Pattern WM_SIZE_PATTERN =
            Pattern.compile("(?:Physical|Override) size: (\\d+)x(\\d+)");

    private ConsoleDisplayController() {
    }

    static int getActiveConsoleDisplayId() {
        final int displayId = getMirrorDisplayId();
        return displayId > 0 && displayExists(displayId) ? displayId : -1;
    }

    static int findExternalDisplayId() {
        final String output = ConsoleModeSwitcher.runRootCommand(
                DISPLAY + " get-displays --ids-only --type external");
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
        final String output = ConsoleModeSwitcher.runRootCommand(
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
        final String output = ConsoleModeSwitcher.runRootCommand(
                AppProcessCommand.run(
                        CONSOLE_DISPLAY_COMMAND,
                        "mirror 0")).trim();
        if (output.contains("display-command=mirror")) {
            return true;
        }
        Log.w(TAG, "Mirror mode request failed output=" + output);
        return false;
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
        final String output = ConsoleModeSwitcher.runRootCommand(
                DISPLAY + " get-displays --ids-only");
        for (final String line : output.split("\\r?\\n")) {
            if (line.trim().equals(Integer.toString(displayId))) {
                return true;
            }
        }
        return false;
    }

    static void ensureLandscape(final int displayId) {
        final int[] size = getDisplaySize(displayId);
        if (size == null) {
            Log.w(TAG, "cannot read Console display size display=" + displayId);
            return;
        }
        if (size[0] >= size[1]) {
            Log.i(TAG, "Console display is landscape display=" + displayId
                    + " size=" + size[0] + "x" + size[1]);
            return;
        }

        final int targetWidth = size[1];
        final int targetHeight = size[0];
        Log.i(TAG, "force Console display landscape display=" + displayId
                + " size=" + size[0] + "x" + size[1]
                + " target=" + targetWidth + "x" + targetHeight);
        ConsoleModeSwitcher.runRootCommand(
                WM + " size " + targetWidth + "x" + targetHeight
                        + " -d " + displayId);

        final long deadline =
                SystemClock.uptimeMillis() + LANDSCAPE_APPLY_TIMEOUT_MS;
        while (SystemClock.uptimeMillis() < deadline) {
            final int[] currentSize = getDisplaySize(displayId);
            if (currentSize != null
                    && currentSize[0] == targetWidth
                    && currentSize[1] == targetHeight) {
                Log.i(TAG, "Console display landscape applied display="
                        + displayId);
                return;
            }
            SystemClock.sleep(STATE_POLL_MS);
        }
        Log.w(TAG, "Console display landscape was not applied display="
                + displayId + " target=" + targetWidth + "x" + targetHeight);
        CompatibilityDiagnostics.record(
                "NUBIA-DISPLAY-001",
                "Console display stayed in the wrong orientation",
                "display=" + displayId + " target="
                        + targetWidth + "x" + targetHeight);
    }

    static void ensureLandscapeWithShizuku(final int displayId)
            throws IOException {
        final String output = PrivilegedCommandRunner.run(
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
            PrivilegedCommandRunner.run(
                    WM + " size " + height + "x" + width
                            + " -d " + displayId);
        }
        PrivilegedCommandRunner.run(
                WM + " fixed-to-user-rotation -d " + displayId + " enabled");
        PrivilegedCommandRunner.run(
                WM + " user-rotation -d " + displayId + " lock 0");
    }

    static String getExternalPhysicalDisplayId() throws IOException {
        final String output = PrivilegedCommandRunner.run(
                DISPLAY + " get-displays --type external");
        final Matcher matcher =
                EXTERNAL_PHYSICAL_DISPLAY_PATTERN.matcher(output);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static int[] getDisplaySize(final int displayId) {
        final String output = ConsoleModeSwitcher.runRootCommand(
                DISPLAY + " get-displays");
        for (final String line : output.split("\\r?\\n")) {
            final Matcher matcher = DISPLAY_REAL_SIZE_PATTERN.matcher(line);
            if (!matcher.find()) {
                continue;
            }
            try {
                if (Integer.parseInt(matcher.group(1)) == displayId) {
                    return new int[] {
                            Integer.parseInt(matcher.group(2)),
                            Integer.parseInt(matcher.group(3))
                    };
                }
            } catch (NumberFormatException ignored) {
                // Continue past malformed vendor diagnostics.
            }
        }
        return null;
    }

    private static int getMirrorDisplayId() {
        final String output = ConsoleModeSwitcher.runRootCommand(
                SETTINGS + " get global app_mirror_displayid");
        try {
            return Integer.parseInt(output.trim());
        } catch (NumberFormatException error) {
            Log.w(TAG, "bad app_mirror_displayid: " + output);
            return -1;
        }
    }

}
