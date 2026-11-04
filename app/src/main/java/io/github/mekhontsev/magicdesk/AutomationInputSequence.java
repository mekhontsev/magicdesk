package io.github.mekhontsev.magicdesk;

import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.HashSet;
import java.util.Set;

/** Shared bounded input choreography; the sink owns addressing and protocol translation. */
final class AutomationInputSequence {
    interface Sink {
        default void validatePoint(float x, float y) { }
        void pointer(int action, float x, float y);
        void key(int action, int key, int meta);
    }
    private AutomationInputSequence() { }

    static JSONObject gesture(final Sink sink, final JSONObject args) throws JSONException {
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
            sink.validatePoint(xs[i], ys[i]);
        }
        final int duration = UiSelector.integer(args, "durationMillis",
                path ? 400 : type.equals("long_press") ? 600 : 0, 0, 5000);
        final int hold = type.equals("drag")
                ? UiSelector.integer(args, "holdMillis", 600, 0, 2000) : 0;
        float x = xs[0], y = ys[0];
        boolean pressed = false;
        boolean completed = false;
        try {
            pressed = true;
            sink.pointer(MotionEvent.ACTION_DOWN, x, y);
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
                    sink.pointer(MotionEvent.ACTION_MOVE, x, y);
                }
            } else if (duration > 0) {
                RuntimeDelays.pause(RuntimeDelays.Reason.INPUT_GESTURE, duration);
            }
            completed = true;
        } finally {
            if (pressed) sink.pointer(
                    completed ? MotionEvent.ACTION_UP : MotionEvent.ACTION_CANCEL, x, y);
        }
        return new JSONObject().put("injected", true).put("type", type);
    }

    static JSONObject keyChord(final Sink sink, final JSONObject args) throws JSONException {
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
        keys(sink, keys);
        return new JSONObject().put("injected", true);
    }

    static void keys(Sink sink, int[] keys) {
        int pressed = 0, meta = 0;
        try {
            for (final int key : keys) {
                meta |= modifier(key);
                // Include a possibly rejected down in cleanup: dispatch acknowledgement can be lost.
                pressed++;
                sink.key(KeyEvent.ACTION_DOWN, key, meta);
            }
        } finally {
            RuntimeException failure = null;
            while (pressed > 0) {
                final int key = keys[--pressed];
                meta &= ~modifier(key);
                try { sink.key(KeyEvent.ACTION_UP, key, meta); }
                catch (RuntimeException error) { if (failure == null) failure = error; else failure.addSuppressed(error); }
            }
            if (failure != null) throw failure;
        }
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

    private static int requiredInt(final JSONObject args, final String key, final int maximum) {
        final int value = UiSelector.integer(args, key, -1, 0, maximum);
        if (value < 0) throw new IllegalArgumentException(key + " is required");
        return value;
    }
}
