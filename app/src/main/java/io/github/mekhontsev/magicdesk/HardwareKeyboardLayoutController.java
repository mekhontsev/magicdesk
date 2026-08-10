package io.github.mekhontsev.magicdesk;

import android.provider.Settings;
import android.util.Log;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

final class HardwareKeyboardLayoutController {
    private static final String TAG = "MagicDeskConsoleSwitcher";
    static final String LAYOUT_STATE =
            "magicdesk_hardware_keyboard_layout";
    static final String LAYOUT_LABEL_STATE =
            "magicdesk_hardware_keyboard_layout_label";
    static final String LAYOUT_NAME_STATE =
            "magicdesk_hardware_keyboard_layout_name";
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
        if (!ShellAccess.isReady()) {
            Log.w(TAG, "hardware keyboard layout control unavailable");
            runCompletion(completion);
            return;
        }
        ConsoleModeSwitcher.executeSerialized(() -> {
            final boolean pointerCaptured = MagicDeskRuntimeService
                    .capturePointerPositionIfRunning();
            try {
                apply("next");
            } finally {
                if (pointerCaptured) {
                    MagicDeskRuntimeService
                            .restorePointerPositionOnNextMotionIfRunning();
                }
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

    static void syncWithInputMethod() {
        runRefresh("ime", null);
    }

    private static void runRefresh(
            final String mode,
            final Runnable completion) {
        if (!ShellAccess.isReady()) {
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
        final String output = ShellAccess.updateHardwareKeyboardLayout(
                "catalog", current).trim();
        if (isNoExternalKeyboard(output)) {
            return 0;
        }
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
            final String current = Settings.Global.getString(
                    MagicDeskApplication.applicationContext()
                            .getContentResolver(),
                    LAYOUT_STATE);
            output = ShellAccess.updateHardwareKeyboardLayout(
                    mode, current).trim();
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

    static boolean isNoExternalKeyboard(final String output) {
        return HardwareKeyboardLayoutCommand.STATUS_NO_EXTERNAL_KEYBOARD.equals(
                parseOutputValue(output, "status"));
    }

}
