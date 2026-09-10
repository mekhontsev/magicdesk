package io.github.mekhontsev.magicdesk;

import android.app.UiAutomation;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Set;

/** Explicit-display input transactions. Every pressed key/pointer is released even on failure. */
final class AndroidAutomationInput {
    private AndroidAutomationInput() { }

    static JSONObject gesture(final UiAutomation automation, final JSONObject args) throws JSONException {
        final int display = requiredInt(args, "displayId", Integer.MAX_VALUE);
        final String type = args.getString("type");
        if (!Set.of("tap", "long_press", "swipe", "drag").contains(type)) {
            throw new IllegalArgumentException("unsupported gesture type");
        }
        final JSONArray points = args.getJSONArray("points");
        final boolean path = type.equals("swipe") || type.equals("drag");
        if (points.length() < (path ? 2 : 1) || points.length() > (path ? 32 : 1)) {
            throw new IllegalArgumentException("tap/long_press needs one point; swipe/drag needs 2 to 32");
        }
        final int[] xs = new int[points.length()], ys = new int[points.length()];
        for (int i = 0; i < points.length(); i++) {
            xs[i] = requiredInt(points.getJSONObject(i), "x", 32768);
            ys[i] = requiredInt(points.getJSONObject(i), "y", 32768);
        }
        final int duration = AndroidUiSelector.integer(args, "durationMillis",
                path ? 400 : type.equals("long_press") ? 600 : 0, 0, 5000);
        final int hold = type.equals("drag")
                ? AndroidUiSelector.integer(args, "holdMillis", 600, 0, 2000) : 0;
        final long down = SystemClock.uptimeMillis();
        float x = xs[0], y = ys[0];
        boolean pressed = false;
        boolean completed = false;
        try {
            pressed = true;
            touch(automation, display, down, MotionEvent.ACTION_DOWN, x, y);
            if (hold > 0) RuntimeDelays.pause(RuntimeDelays.Reason.INPUT_GESTURE, hold);
            if (path) {
                final long start = SystemClock.uptimeMillis();
                final int steps = Math.max(points.length() - 1, Math.max(1, (duration + 15) / 16));
                for (int step = 1; step <= steps; step++) {
                    final long remaining = start + (long) duration * step / steps - SystemClock.uptimeMillis();
                    if (remaining > 0) RuntimeDelays.pause(RuntimeDelays.Reason.INPUT_GESTURE, remaining);
                    final double position = (double) step * (points.length() - 1) / steps;
                    final int segment = Math.min((int) position, points.length() - 2);
                    final float fraction = (float) (position - segment);
                    x = xs[segment] + (xs[segment + 1] - xs[segment]) * fraction;
                    y = ys[segment] + (ys[segment + 1] - ys[segment]) * fraction;
                    touch(automation, display, down, MotionEvent.ACTION_MOVE, x, y);
                }
            } else if (duration > 0) {
                RuntimeDelays.pause(RuntimeDelays.Reason.INPUT_GESTURE, duration);
            }
            completed = true;
        } finally {
            if (pressed) touch(automation, display, down,
                    completed ? MotionEvent.ACTION_UP : MotionEvent.ACTION_CANCEL, x, y);
        }
        return new JSONObject().put("injected", true).put("displayId", display).put("type", type);
    }

    static JSONObject keyChord(final UiAutomation automation, final JSONObject args) throws JSONException {
        final int display = requiredInt(args, "displayId", Integer.MAX_VALUE);
        final JSONArray values = args.getJSONArray("keys");
        if (values.length() < 1 || values.length() > 8) throw new IllegalArgumentException("keys needs 1 to 8 key codes");
        final int[] keys = new int[values.length()];
        final Set<Integer> unique = new HashSet<>();
        for (int i = 0; i < keys.length; i++) {
            final String name = values.getString(i);
            keys[i] = KeyEvent.keyCodeFromString(name.startsWith("KEYCODE_") ? name : "KEYCODE_" + name);
            if (keys[i] == KeyEvent.KEYCODE_UNKNOWN || !unique.add(keys[i])) {
                throw new IllegalArgumentException("unknown or duplicate key code: " + name);
            }
        }
        final long down = SystemClock.uptimeMillis();
        int pressed = 0, meta = 0;
        try {
            for (final int key : keys) {
                meta |= modifier(key);
                // Include a possibly rejected down in cleanup: dispatch acknowledgement can be lost.
                pressed++;
                key(automation, display, down, KeyEvent.ACTION_DOWN, key, meta);
            }
        } finally {
            RuntimeException failure = null;
            while (pressed > 0) {
                final int key = keys[--pressed];
                meta &= ~modifier(key);
                try { key(automation, display, down, KeyEvent.ACTION_UP, key, meta); }
                catch (RuntimeException error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
            }
            if (failure != null) throw failure;
        }
        return new JSONObject().put("injected", true).put("displayId", display);
    }

    private static int modifier(final int key) {
        return switch (key) {
            case KeyEvent.KEYCODE_CTRL_LEFT -> KeyEvent.META_CTRL_LEFT_ON;
            case KeyEvent.KEYCODE_CTRL_RIGHT -> KeyEvent.META_CTRL_RIGHT_ON;
            case KeyEvent.KEYCODE_ALT_LEFT -> KeyEvent.META_ALT_LEFT_ON;
            case KeyEvent.KEYCODE_ALT_RIGHT -> KeyEvent.META_ALT_RIGHT_ON;
            case KeyEvent.KEYCODE_SHIFT_LEFT -> KeyEvent.META_SHIFT_LEFT_ON;
            case KeyEvent.KEYCODE_SHIFT_RIGHT -> KeyEvent.META_SHIFT_RIGHT_ON;
            case KeyEvent.KEYCODE_META_LEFT -> KeyEvent.META_META_LEFT_ON;
            case KeyEvent.KEYCODE_META_RIGHT -> KeyEvent.META_META_RIGHT_ON;
            default -> 0;
        };
    }

    private static void key(final UiAutomation automation, final int display, final long down,
            final int action, final int key, final int meta) {
        inject(automation, display, new KeyEvent(down, SystemClock.uptimeMillis(), action, key, 0,
                KeyEvent.normalizeMetaState(meta), KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD));
    }

    private static void touch(final UiAutomation automation, final int display, final long down,
            final int action, final float x, final float y) {
        final MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                x, y, action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL ? 0 : 1,
                1, 0, 1, 1, KeyCharacterMap.VIRTUAL_KEYBOARD, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try { inject(automation, display, event); } finally { event.recycle(); }
    }

    private static void inject(final UiAutomation automation, final int display, final InputEvent event) {
        FrameworkUiAutomationApi.inject(automation, event, display);
    }

    private static int requiredInt(final JSONObject args, final String key, final int maximum) {
        final int value = AndroidUiSelector.integer(args, key, -1, 0, maximum);
        if (value < 0) throw new IllegalArgumentException(key + " is required");
        return value;
    }
}
