package io.github.mekhontsev.magicdesk;

import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

final class HardwareKeyboardLayoutController {
    private static final String TAG = "MagicDeskConsoleSwitcher";
    private static final String SETTINGS = "/system/bin/settings";
    private static final String LAYOUT_STATE =
            "magicdesk_hardware_keyboard_layout";
    private static final String LAYOUT_LABEL_STATE =
            "magicdesk_hardware_keyboard_layout_label";
    private static final String LAYOUT_NAME_STATE =
            "magicdesk_hardware_keyboard_layout_name";
    private static final String LAYOUT_COMMAND =
            "io.github.mekhontsev.magicdesk.HardwareKeyboardLayoutCommand";
    private static final AtomicBoolean REFRESH_IN_PROGRESS =
            new AtomicBoolean();

    private HardwareKeyboardLayoutController() {
    }

    static void toggle() {
        ConsoleModeSwitcher.executeSerialized(() -> {
            try {
                apply("next");
            } finally {
                ConsoleModeSwitcher.closeRootShell();
            }
        });
    }

    static void refresh() {
        if (!REFRESH_IN_PROGRESS.compareAndSet(false, true)) {
            Log.d(TAG, "hardware keyboard layout refresh already pending");
            return;
        }
        ConsoleModeSwitcher.executeSerialized(() -> {
            try {
                apply("sync");
            } finally {
                REFRESH_IN_PROGRESS.set(false);
                ConsoleModeSwitcher.closeRootShell();
            }
        });
    }

    private static void apply(final String mode) {
        final String command = "CURRENT=$(" + SETTINGS + " get global "
                + LAYOUT_STATE + "); "
                + "APK=$(/system/bin/pm path io.github.mekhontsev.magicdesk "
                + "| /system/bin/cut -d: -f2- | /system/bin/head -n 1); "
                + "CLASSPATH=\"$APK\" /system/bin/app_process / "
                + LAYOUT_COMMAND + " " + mode + " \"$CURRENT\"";
        final String output =
                ConsoleModeSwitcher.runRootCommand(command).trim();
        final String descriptor =
                parseOutputValue(output, "descriptor");
        final String code = parseOutputValue(output, "code");
        final String name64 = parseOutputValue(output, "name64");
        if (descriptor == null || code == null || name64 == null) {
            Log.w(TAG,
                    "hardware keyboard layout command failed output="
                            + output);
            return;
        }

        final String name;
        try {
            name = new String(
                    Base64.decode(name64, Base64.DEFAULT),
                    StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            Log.w(TAG,
                    "invalid hardware keyboard layout name output="
                            + output,
                    e);
            return;
        }
        ConsoleModeSwitcher.runRootCommand(
                SETTINGS + " put global " + LAYOUT_LABEL_STATE
                        + " " + shellQuote(code));
        ConsoleModeSwitcher.runRootCommand(
                SETTINGS + " put global " + LAYOUT_NAME_STATE
                        + " " + shellQuote(name));
        ConsoleModeSwitcher.runRootCommand(
                SETTINGS + " put global " + LAYOUT_STATE
                        + " " + shellQuote(descriptor));
        Log.i(TAG,
                "hardware keyboard "
                        + output.replace('\n', ' '));
    }

    private static String parseOutputValue(
            final String output,
            final String key) {
        final String prefix = key + "=";
        for (final String line : output.split("\\r?\\n")) {
            if (line.startsWith(prefix)) {
                final String value =
                        line.substring(prefix.length()).trim();
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }

    private static String shellQuote(final String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
