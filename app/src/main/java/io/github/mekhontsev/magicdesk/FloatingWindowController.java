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
        final String output = runCommand(CMD + " display get-displays");
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
        final Rect display = getWorkAreaBounds(displayId);
        final int width = Math.min(1200,
                Math.max(Math.min(640, display.width()),
                        Math.round(display.width() * 0.625f)));
        // Blindaje en las dimensiones de altura inicial para garantizar espacio suficiente
        // para los marcos y botones de control, evitando pantallas negras o colapsos gráficos en apps multimedia.
        final int height = Math.min(840,
                Math.max(Math.min(520, display.height()),
                        Math.round(display.height() * 0.72f)));
        final int left =
                display.left + (display.width() - width) / 2;
        final int top =
                display.top + (display.height() - height) / 2;
        return new Rect(left, top, left + width, top + height);
    }

    static Rect getWindowBounds(
            final int displayId,
            final RelativeWindowBounds preferred) throws IOException {
        if (preferred == null) {
            return getDefaultWindowBounds(displayId);
        }
        final Rect resolved = preferred.resolve(getWorkAreaBounds(displayId));
        return resolved.isEmpty()
                ? getDefaultWindowBounds(displayId) : resolved;
    }

    static Rect getWorkAreaBounds(final int displayId) throws IOException {
        final Rect desktopWorkArea =
                DesktopRuntimeBridge.getDesktopWorkAreaBounds(displayId);
        return desktopWorkArea == null || desktopWorkArea.isEmpty()
                ? getDisplayBounds(displayId) : desktopWorkArea;
    }

    private static String runCommand(final String command) throws IOException {
        return ShellAccess.run(command);
    }
}