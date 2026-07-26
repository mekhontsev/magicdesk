package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class FloatingWindowController {
    private static final String CMD = "/system/bin/cmd";
    private static final Pattern DISPLAY_SIZE_PATTERN =
            Pattern.compile("Display id (\\d+): .* real (\\d+) x (\\d+),");

    private FloatingWindowController() {
    }

    static Rect getDisplayBounds(final int displayId) throws IOException {
        final String output = runRootCommand(CMD + " display get-displays");
        final String[] lines = output.split("\\r?\\n");
        for (final String line : lines) {
            final Matcher matcher = DISPLAY_SIZE_PATTERN.matcher(line);
            if (matcher.find() && Integer.parseInt(matcher.group(1)) == displayId) {
                return new Rect(0, 0, Integer.parseInt(matcher.group(2)),
                        Integer.parseInt(matcher.group(3)));
            }
        }
        throw new IOException("display " + displayId + " not found");
    }

    static Rect getDefaultWindowBounds(final int displayId) throws IOException {
        final Rect display = getDisplayBounds(displayId);
        final int width = Math.min(1200,
                Math.max(Math.min(640, display.width()),
                        Math.round(display.width() * 0.625f)));
        final int height = Math.min(840,
                Math.max(Math.min(520, display.height()),
                        Math.round(display.height() * 0.72f)));
        final int left = (display.width() - width) / 2;
        final int top = (display.height() - height) / 2;
        return new Rect(left, top, left + width, top + height);
    }

    private static String runRootCommand(final String command) throws IOException {
        final Process process = new ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start();
        final StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        try {
            final int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("root command failed " + exitCode + ": "
                        + output.toString().trim());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("root command interrupted", e);
        } finally {
            process.destroy();
        }
        return output.toString();
    }
}
