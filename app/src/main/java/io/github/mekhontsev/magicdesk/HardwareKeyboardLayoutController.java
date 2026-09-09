package io.github.mekhontsev.magicdesk;

import android.util.Log;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

final class HardwareKeyboardLayoutController {
    private static final String TAG = "MagicDeskDesktopOps";
    static final String LAYOUT_STATE =
            "magicdesk_hardware_keyboard_layout";
    static final String LAYOUT_LABEL_STATE =
            "magicdesk_hardware_keyboard_layout_label";
    static final String LAYOUT_NAME_STATE =
            "magicdesk_hardware_keyboard_layout_name";
    private static final AtomicBoolean REFRESH_IN_PROGRESS =
            new AtomicBoolean();
    private HardwareKeyboardLayoutController() {
    }

    static void toggle() {
        toggle(null);
    }

    static void toggle(final Runnable completion) {
        if (!ShellAccess.isReady()) {
            Log.w(TAG, "hardware keyboard layout control unavailable");
            runCompletion(completion);
            return;
        }
        DesktopOperations.executeSerialized(() -> {
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
        runRefresh(completion);
    }

    private static void runRefresh(
            final Runnable completion) {
        if (!ShellAccess.isReady()) {
            runCompletion(completion);
            return;
        }
        if (!REFRESH_IN_PROGRESS.compareAndSet(false, true)) {
            Log.d(TAG, "hardware keyboard layout refresh already pending");
            if (completion != null) {
                DesktopOperations.executeSerialized(completion);
            }
            return;
        }
        DesktopOperations.executeSerialized(() -> {
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
            output = ShellAccess.updateHardwareKeyboardLayout(mode).trim();
        } catch (IOException e) {
            Log.w(TAG, "hardware keyboard layout command failed", e);
            return;
        }
        if (isNoExternalKeyboard(output)) {
            Log.d(TAG, "no external hardware keyboard to configure");
            return;
        }
        final String descriptor =
                parseOutputValue(output, "descriptor");
        final String code = parseOutputValue(output, "code");
        final String name64 = parseOutputValue(output, "name64");
        if (descriptor == null || code == null
                || name64 == null) {
            Log.w(TAG,
                    "hardware keyboard layout command failed output="
                            + output);
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

    static boolean isNoExternalKeyboard(final String output) {
        return HardwareKeyboardLayoutCommand.STATUS_NO_EXTERNAL_KEYBOARD.equals(
                parseOutputValue(output, "status"));
    }

}
