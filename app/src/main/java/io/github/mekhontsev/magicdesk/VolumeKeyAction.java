package io.github.mekhontsev.magicdesk;

import android.view.KeyEvent;

/** Own both halves of a volume press; switch windows only after release. */
final class VolumeKeyAction {
    private int key = KeyEvent.KEYCODE_UNKNOWN;

    static boolean accepts(int key) {
        return key == KeyEvent.KEYCODE_VOLUME_UP || key == KeyEvent.KEYCODE_VOLUME_DOWN
                || key == KeyEvent.KEYCODE_VOLUME_MUTE;
    }

    void reset() { key = KeyEvent.KEYCODE_UNKNOWN; }

    boolean dispatch(KeyEvent event, boolean enabled, Runnable action) {
        int code = event.getKeyCode();
        if (!accepts(code) || (!enabled && key != code)) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            key = enabled ? code : KeyEvent.KEYCODE_UNKNOWN;
        } else if (event.getAction() == KeyEvent.ACTION_UP && key == code) {
            reset();
            if (enabled && !event.isCanceled()) action.run();
        }
        return true;
    }
}
