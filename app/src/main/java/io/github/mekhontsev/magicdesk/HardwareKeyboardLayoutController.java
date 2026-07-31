package io.github.mekhontsev.magicdesk;

import android.provider.Settings;
import android.util.Base64;
import android.util.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

final class HardwareKeyboardLayoutController {
    private static final String TAG = "MagicDeskConsoleSwitcher";
    private static final String SETTINGS = "/system/bin/settings";
    static final String LAYOUT_STATE =
            "magicdesk_hardware_keyboard_layout";
    static final String LAYOUT_LABEL_STATE =
            "magicdesk_hardware_keyboard_layout_label";
    static final String LAYOUT_NAME_STATE =
            "magicdesk_hardware_keyboard_layout_name";
    private static final String LAYOUT_COMMAND =
            "io.github.mekhontsev.magicdesk.HardwareKeyboardLayoutCommand";
    private static final AtomicBoolean REFRESH_IN_PROGRESS =
            new AtomicBoolean();

    private HardwareKeyboardLayoutController() {
    }

    static void toggle() {
        toggle(null);
    }

    static void toggle(final Runnable completion) {
        if (!RuntimeAccess.has(
                RuntimeAccess.Capability.KEYBOARD_LAYOUT_CONTROL)) {
            Log.w(TAG, "hardware keyboard layout control unavailable");
            runCompletion(completion);
            return;
        }
        ConsoleModeSwitcher.executeSerialized(() -> {
            try {
                apply("next");
            } finally {
                runCompletion(completion);
            }
        });
    }

    static void refresh() {
        refresh(null);
    }

    static void refresh(final Runnable completion) {
        if (!RuntimeAccess.has(
                RuntimeAccess.Capability.KEYBOARD_LAYOUT_CONTROL)) {
            runCompletion(completion);
            return;
        }
        if (!REFRESH_IN_PROGRESS.compareAndSet(false, true)) {
            Log.d(TAG, "hardware keyboard layout refresh already pending");
            if (completion != null) {
                ConsoleModeSwitcher.executeSerialized(completion);
            }
            return;
        }
        ConsoleModeSwitcher.executeSerialized(() -> {
            try {
                apply("sync");
            } finally {
                REFRESH_IN_PROGRESS.set(false);
                runCompletion(completion);
            }
        });
    }

    private static void runCompletion(final Runnable completion) {
        if (completion != null) {
            completion.run();
        }
    }

    private static void apply(final String mode) {
        final String output;
        try {
            if (RuntimeAccess.allowsShizukuCommands()
                    && !RuntimeAccess.allowsRootCommands()) {
                final String current = Settings.Global.getString(
                        MagicDeskApplication.applicationContext()
                                .getContentResolver(),
                        LAYOUT_STATE);
                output = ShizukuAccess.updateHardwareKeyboardLayout(
                        mode, current).trim();
            } else {
                final String command = "CURRENT=$(" + SETTINGS + " get global "
                        + LAYOUT_STATE + "); "
                        + AppProcessCommand.run(
                                LAYOUT_COMMAND,
                                mode + " \"$CURRENT\"");
                output = PrivilegedCommandRunner.run(command).trim();
            }
        } catch (IOException e) {
            Log.w(TAG, "hardware keyboard layout command failed", e);
            return;
        }
        if (RuntimeAccess.allowsShizukuCommands()
                && !RuntimeAccess.allowsRootCommands()) {
            Log.i(TAG,
                    "hardware keyboard "
                            + output.replace('\n', ' '));
            return;
        }

        final String descriptor =
                parseOutputValue(output, "descriptor");
        final String code = parseOutputValue(output, "code");
        final String name64 = parseOutputValue(output, "name64");
        final String subtype = parseOutputValue(output, "subtype");
        if (descriptor == null || code == null
                || name64 == null || subtype == null) {
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
        try {
            PrivilegedCommandRunner.run(
                    SETTINGS + " put secure selected_input_method_subtype "
                            + shellQuote(subtype) + "; "
                            + SETTINGS + " put global " + LAYOUT_LABEL_STATE
                            + " " + shellQuote(code) + "; "
                            + SETTINGS + " put global " + LAYOUT_NAME_STATE
                            + " " + shellQuote(name) + "; "
                            + SETTINGS + " put global " + LAYOUT_STATE
                            + " " + shellQuote(descriptor));
        } catch (IOException e) {
            Log.w(TAG, "cannot persist hardware keyboard layout state", e);
            return;
        }
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
