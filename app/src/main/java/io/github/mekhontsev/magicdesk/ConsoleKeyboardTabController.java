package io.github.mekhontsev.magicdesk;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import java.io.IOException;

final class ConsoleKeyboardTabController {
    private final RawKeyboardTabWatcher mWatcher =
            new RawKeyboardTabWatcher();
    private final ConsoleInputEventInjector mInjector;

    ConsoleKeyboardTabController(final ConsoleInputEventInjector injector) {
        mInjector = injector;
    }

    void start(final ConsoleKeyboardDevice keyboard) throws IOException {
        if (keyboard.inputDeviceId < 0) {
            throw new IOException("logical keyboard input device is unavailable");
        }
        mWatcher.start(
                keyboard.path,
                keyboard.inputDeviceId,
                new RawKeyboardTabWatcher.Listener() {
                    @Override
                    public void onTab(
                            final int action,
                            final int repeatCount,
                            final int metaState) {
                        handleTab(
                                keyboard, action, repeatCount, metaState);
                    }

                    @Override
                    public void onAltReleased() {
                        System.out.println("MAGICDESK_ALT_TAB_COMMIT");
                        System.out.flush();
                    }
                });
    }

    void stop() {
        mWatcher.stop();
    }

    private void handleTab(
            final ConsoleKeyboardDevice keyboard,
            final int action,
            final int repeatCount,
            final int metaState) {
        if ((metaState & KeyEvent.META_ALT_ON) != 0) {
            if (action == KeyEvent.ACTION_DOWN && repeatCount == 0) {
                final boolean reverse =
                        (metaState & KeyEvent.META_SHIFT_ON) != 0;
                System.out.println(
                        "MAGICDESK_ALT_TAB_ADVANCE "
                                + (reverse ? "reverse" : "forward"));
                System.out.flush();
            }
            return;
        }

        final long eventTime = SystemClock.uptimeMillis();
        if (action == KeyEvent.ACTION_DOWN && repeatCount == 0) {
            keyboard.tabDownTime = eventTime;
        } else if (keyboard.tabDownTime == 0) {
            keyboard.tabDownTime = eventTime;
        }
        final KeyEvent translated = new KeyEvent(
                keyboard.tabDownTime,
                eventTime,
                action,
                KeyEvent.KEYCODE_TAB,
                repeatCount,
                metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                0,
                KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_VIRTUAL_HARD_KEY,
                InputDevice.SOURCE_KEYBOARD);
        try {
            mInjector.targetDisplay(translated);
            if (!mInjector.inject(translated)) {
                throw new IOException("Tab injection was rejected");
            }
        } catch (Exception error) {
            System.err.println(
                    "MAGICDESK_TAB_INJECTION_ERROR source="
                            + keyboard.path + " error=" + error);
        } finally {
            if (action == KeyEvent.ACTION_UP) {
                keyboard.tabDownTime = 0;
            }
        }
    }
}
