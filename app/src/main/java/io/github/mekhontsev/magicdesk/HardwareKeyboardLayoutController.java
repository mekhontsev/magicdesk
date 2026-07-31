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
    private static final Object LAYOUT_SINK_LOCK = new Object();
    private static LayoutSink sLayoutSink;

    interface LayoutSink {
        void select(int index) throws IOException;
    }

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
        runRefresh(hasLayoutSink() ? "catalog" : "sync", completion);
    }

    static void configureVirtualLayouts(final Runnable completion) {
        runRefresh("sync", completion);
    }

    private static void runRefresh(
            final String mode,
            final Runnable completion) {
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
                apply(mode);
            } finally {
                REFRESH_IN_PROGRESS.set(false);
                runCompletion(completion);
            }
        });
    }

    static int catalogLayoutCount() throws IOException {
        final String current = Settings.Global.getString(
                MagicDeskApplication.applicationContext()
                        .getContentResolver(),
                LAYOUT_STATE);
        final String output = ShizukuAccess.updateHardwareKeyboardLayout(
                "catalog", current).trim();
        final String count = parseOutputValue(output, "layouts");
        try {
            final int parsed = count == null ? -1 : Integer.parseInt(count);
            if (parsed <= 0) {
                throw new NumberFormatException("non-positive layout count");
            }
            return parsed;
        } catch (NumberFormatException error) {
            throw new IOException(
                    "invalid hardware keyboard catalog: " + output,
                    error);
        }
    }

    static void attachLayoutSink(final LayoutSink sink) {
        synchronized (LAYOUT_SINK_LOCK) {
            sLayoutSink = sink;
        }
    }

    static void detachLayoutSink(final LayoutSink sink) {
        synchronized (LAYOUT_SINK_LOCK) {
            if (sLayoutSink == sink) {
                sLayoutSink = null;
            }
        }
    }

    private static boolean hasLayoutSink() {
        synchronized (LAYOUT_SINK_LOCK) {
            return sLayoutSink != null;
        }
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
        final String descriptor =
                parseOutputValue(output, "descriptor");
        final String code = parseOutputValue(output, "code");
        final String name64 = parseOutputValue(output, "name64");
        final String indexValue = parseOutputValue(output, "index");
        if (descriptor == null || code == null
                || name64 == null || indexValue == null) {
            Log.w(TAG,
                    "hardware keyboard layout command failed output="
                            + output);
            return;
        }
        final int index;
        try {
            index = Integer.parseInt(indexValue);
        } catch (NumberFormatException error) {
            Log.w(TAG,
                    "invalid hardware keyboard layout index output="
                            + output,
                    error);
            return;
        }

        if (!RuntimeAccess.allowsShizukuCommands()
                || RuntimeAccess.allowsRootCommands()) {
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
                        SETTINGS + " put global " + LAYOUT_LABEL_STATE
                                + " " + shellQuote(code) + "; "
                                + SETTINGS + " put global " + LAYOUT_NAME_STATE
                                + " " + shellQuote(name) + "; "
                                + SETTINGS + " put global " + LAYOUT_STATE
                                + " " + shellQuote(descriptor));
            } catch (IOException e) {
                Log.w(TAG, "cannot persist hardware keyboard layout state", e);
                return;
            }
        }
        selectVirtualLayout(index);
        Log.i(TAG,
                "hardware keyboard "
                        + output.replace('\n', ' '));
    }

    private static void selectVirtualLayout(final int index) {
        final LayoutSink sink;
        synchronized (LAYOUT_SINK_LOCK) {
            sink = sLayoutSink;
        }
        if (sink == null) {
            return;
        }
        try {
            sink.select(index);
        } catch (IOException error) {
            Log.w(TAG,
                    "cannot select virtual keyboard layout " + index,
                    error);
        }
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
