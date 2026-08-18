package io.github.mekhontsev.magicdesk;

import static io.github.mekhontsev.magicdesk.DesktopSelfTestTasks.POLL_MILLIS;
import static io.github.mekhontsev.magicdesk.DesktopSelfTestTasks.STEP_TIMEOUT_MILLIS;

import android.content.Context;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Coordinates observable marker state written by the self-test fixture. */
final class DesktopSelfTestFixtureState {
    private DesktopSelfTestFixtureState() {
    }

    static void clearLaunchMarkers(final Context context) {
        clear(context, DesktopSelfTestActivity.FIRST_FRAME_MARKER_FILE);
        clearText(context);
    }

    static void clearText(final Context context) {
        clear(context, DesktopSelfTestActivity.TEXT_MARKER_FILE);
    }

    static void clearImmersive(final Context context) {
        clear(context, DesktopSelfTestActivity.IMMERSIVE_MARKER_FILE);
    }

    static void awaitFirstFrame(
            final Context context,
            final String token,
            final int displayId) throws IOException {
        await(context,
                DesktopSelfTestActivity.FIRST_FRAME_MARKER_FILE,
                token + "|" + displayId + "|freeform",
                displayId);
    }

    static void awaitText(
            final Context context,
            final String token,
            final int displayId,
            final String digit) throws IOException {
        await(context,
                DesktopSelfTestActivity.TEXT_MARKER_FILE,
                token + "|" + displayId + "|" + digit,
                displayId);
    }

    static void awaitImmersive(
            final Context context,
            final String token,
            final int displayId,
            final boolean enabled) throws IOException {
        await(context,
                DesktopSelfTestActivity.IMMERSIVE_MARKER_FILE,
                token + "|" + displayId + "|" + enabled,
                displayId);
    }

    private static void clear(
            final Context context,
            final String fileName) {
        context.deleteFile(fileName);
    }

    private static void await(
            final Context context,
            final String fileName,
            final String expected,
            final int displayId) throws IOException {
        final File marker = new File(context.getFilesDir(), fileName);
        final long deadline = SystemClock.uptimeMillis() + STEP_TIMEOUT_MILLIS;
        String actual;
        do {
            actual = readFile(marker);
            if (expected.equals(actual)) {
                return;
            }
            SystemClock.sleep(POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException("expected marker=" + expected
                + ", actual=" + actual
                + ", tasks=" + taskStateDetail(displayId));
    }

    private static String taskStateDetail(final int displayId) {
        try {
            final StringBuilder detail = new StringBuilder();
            for (final TaskStackParser.Entry task : TaskStackParser.parse(
                    ShellAccess.run(
                            "/system/bin/cmd activity stack list"))) {
                if (task.displayId == displayId && !task.isHome()) {
                    if (detail.length() > 0) {
                        detail.append(';');
                    }
                    detail.append(task.taskId)
                            .append('/')
                            .append(task.componentName)
                            .append('/')
                            .append(task.windowingMode)
                            .append('/')
                            .append(task.visible ? "visible" : "hidden")
                            .append('/')
                            .append(DesktopSelfTestGeometry.format(task.bounds));
                }
            }
            return detail.length() == 0 ? "none" : detail.toString();
        } catch (IOException ignored) {
            return "unavailable";
        }
    }

    private static String readFile(final File file) {
        if (file == null || !file.isFile()) {
            return "";
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            final String line = reader.readLine();
            return line == null ? "" : line.trim();
        } catch (IOException ignored) {
            return "";
        }
    }
}
