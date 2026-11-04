package io.github.mekhontsev.magicdesk;

import android.app.UiAutomation;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;
import org.json.JSONException;
import org.json.JSONObject;

/** Android display adapter for the common input transaction. */
final class AndroidAutomationInput {
    static JSONObject gesture(UiAutomation automation, JSONObject args) throws JSONException {
        int display = display(args);
        return AutomationInputSequence.gesture(sink(automation, display), args).put("displayId", display);
    }
    static JSONObject keyChord(UiAutomation automation, JSONObject args) throws JSONException {
        int display = display(args);
        return AutomationInputSequence.keyChord(sink(automation, display), args).put("displayId", display);
    }
    private static int display(JSONObject args) {
        int display = UiSelector.integer(args, "displayId", -1, 0, Integer.MAX_VALUE);
        if (display < 0) throw new IllegalArgumentException("displayId is required");
        return display;
    }
    private static AutomationInputSequence.Sink sink(UiAutomation automation, int display) {
        long down = SystemClock.uptimeMillis();
        return new AutomationInputSequence.Sink() {
            public void key(int action, int key, int meta) {
                FrameworkUiAutomationApi.inject(automation, new KeyEvent(down, SystemClock.uptimeMillis(), action, key, 0,
                        KeyEvent.normalizeMetaState(meta), KeyCharacterMap.VIRTUAL_KEYBOARD, 0,
                        KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD), display);
            }
            public void pointer(int action, float x, float y) {
                MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y,
                        action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL ? 0 : 1,
                        1, 0, 1, 1, KeyCharacterMap.VIRTUAL_KEYBOARD, 0);
                event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                try { FrameworkUiAutomationApi.inject(automation, event, display); }
                finally { event.recycle(); }
            }
        };
    }
    private AndroidAutomationInput() { }
}
