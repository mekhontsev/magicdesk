package io.github.mekhontsev.magicdesk;

import android.view.KeyEvent;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

final class RawKeyboardTabWatcher {
    interface Listener {
        void onTab(int action, int repeatCount, int metaState);

        void onAltReleased();
    }

    private static final int INPUT_EVENT_SIZE_32 = 16;
    private static final int INPUT_EVENT_TYPE_OFFSET_32 = 8;
    private static final int INPUT_EVENT_CODE_OFFSET_32 = 10;
    private static final int INPUT_EVENT_VALUE_OFFSET_32 = 12;
    private static final int INPUT_EVENT_SIZE_64 = 24;
    private static final int INPUT_EVENT_TYPE_OFFSET_64 = 16;
    private static final int INPUT_EVENT_CODE_OFFSET_64 = 18;
    private static final int INPUT_EVENT_VALUE_OFFSET_64 = 20;
    private static final int EV_SYN = 0;
    private static final int EV_KEY = 1;
    private static final int EV_MSC = 4;
    private static final int MSC_SCAN = 4;
    private static final int KEY_UNKNOWN = 240;
    private static final int KEY_LEFTCTRL = 29;
    private static final int KEY_LEFTSHIFT = 42;
    private static final int KEY_LEFTALT = 56;
    private static final int KEY_RIGHTSHIFT = 54;
    private static final int KEY_RIGHTALT = 100;
    private static final int KEY_RIGHTCTRL = 97;
    private static final int KEY_LEFTMETA = 125;
    private static final int KEY_RIGHTMETA = 126;
    private static final int HID_TAB_SCAN = 0x0007002b;

    private final List<Watch> mWatches = new ArrayList<>();
    private volatile boolean mStopped;

    synchronized void start(
            final String path,
            final int inputDeviceId,
            final Listener listener) throws IOException {
        mStopped = false;
        final FileInputStream input = new FileInputStream(path);
        final Watch watch = new Watch(input);
        final Thread readerThread = new Thread(
                () -> readLoop(path, input, listener),
                "MagicDeskRawTab-" + inputDeviceId);
        readerThread.setDaemon(true);
        watch.thread = readerThread;
        mWatches.add(watch);
        readerThread.start();
    }

    synchronized void stop() {
        mStopped = true;
        for (final Watch watch : mWatches) {
            try {
                watch.input.close();
            } catch (IOException ignored) {
                // Closing the event node is the normal shutdown path.
            }
            if (watch.thread != null) {
                watch.thread.interrupt();
            }
        }
        mWatches.clear();
    }

    private void readLoop(
            final String path,
            final FileInputStream input,
            final Listener listener) {
        final boolean is64Bit = android.os.Process.is64Bit();
        final int eventSize =
                is64Bit ? INPUT_EVENT_SIZE_64 : INPUT_EVENT_SIZE_32;
        final int typeOffset = is64Bit
                ? INPUT_EVENT_TYPE_OFFSET_64 : INPUT_EVENT_TYPE_OFFSET_32;
        final int codeOffset = is64Bit
                ? INPUT_EVENT_CODE_OFFSET_64 : INPUT_EVENT_CODE_OFFSET_32;
        final int valueOffset = is64Bit
                ? INPUT_EVENT_VALUE_OFFSET_64 : INPUT_EVENT_VALUE_OFFSET_32;
        final byte[] event = new byte[eventSize];
        final ByteBuffer decoded =
                ByteBuffer.wrap(event).order(ByteOrder.nativeOrder());
        final ModifierState modifiers = new ModifierState();
        boolean tabScanPending = false;
        int repeatCount = 0;
        try {
            while (!mStopped && readInputEvent(input, event)) {
                final int type = decoded.getShort(typeOffset) & 0xffff;
                final int code = decoded.getShort(codeOffset) & 0xffff;
                final int value = decoded.getInt(valueOffset);
                if (type == EV_MSC && code == MSC_SCAN) {
                    tabScanPending = value == HID_TAB_SCAN;
                    continue;
                }
                if (type == EV_KEY) {
                    modifiers.update(code, value);
                    if ((code == KEY_LEFTALT || code == KEY_RIGHTALT)
                            && value == 0) {
                        listener.onAltReleased();
                    }
                    if (code == KEY_UNKNOWN && tabScanPending) {
                        if (value == 1) {
                            repeatCount = 0;
                            listener.onTab(KeyEvent.ACTION_DOWN, repeatCount,
                                    modifiers.metaState());
                        } else if (value == 2) {
                            listener.onTab(KeyEvent.ACTION_DOWN, ++repeatCount,
                                    modifiers.metaState());
                        } else if (value == 0) {
                            listener.onTab(KeyEvent.ACTION_UP, 0,
                                    modifiers.metaState());
                            repeatCount = 0;
                        }
                    }
                    tabScanPending = false;
                } else if (type == EV_SYN) {
                    tabScanPending = false;
                }
            }
        } catch (IOException e) {
            if (!mStopped) {
                System.err.println("MAGICDESK_TAB_RAW_ERROR source="
                        + path + " error=" + e);
            }
        }
    }

    private static boolean readInputEvent(
            final FileInputStream input,
            final byte[] event) throws IOException {
        int offset = 0;
        while (offset < event.length) {
            final int count =
                    input.read(event, offset, event.length - offset);
            if (count < 0) {
                return false;
            }
            offset += count;
        }
        return true;
    }

    private static final class ModifierState {
        private int mMetaState;

        void update(final int code, final int value) {
            final boolean down = value != 0;
            if (code == KEY_LEFTALT) {
                set(KeyEvent.META_ALT_LEFT_ON, down);
            } else if (code == KEY_RIGHTALT) {
                set(KeyEvent.META_ALT_RIGHT_ON, down);
            } else if (code == KEY_LEFTCTRL) {
                set(KeyEvent.META_CTRL_LEFT_ON, down);
            } else if (code == KEY_RIGHTCTRL) {
                set(KeyEvent.META_CTRL_RIGHT_ON, down);
            } else if (code == KEY_LEFTSHIFT) {
                set(KeyEvent.META_SHIFT_LEFT_ON, down);
            } else if (code == KEY_RIGHTSHIFT) {
                set(KeyEvent.META_SHIFT_RIGHT_ON, down);
            } else if (code == KEY_LEFTMETA) {
                set(KeyEvent.META_META_LEFT_ON, down);
            } else if (code == KEY_RIGHTMETA) {
                set(KeyEvent.META_META_RIGHT_ON, down);
            }
        }

        int metaState() {
            return mMetaState;
        }

        private void set(final int mask, final boolean down) {
            if (down) {
                mMetaState |= mask;
            } else {
                mMetaState &= ~mask;
            }
            normalizeGenericModifiers();
        }

        private void normalizeGenericModifiers() {
            mMetaState = setGenericModifier(
                    mMetaState,
                    KeyEvent.META_ALT_LEFT_ON | KeyEvent.META_ALT_RIGHT_ON,
                    KeyEvent.META_ALT_ON);
            mMetaState = setGenericModifier(
                    mMetaState,
                    KeyEvent.META_CTRL_LEFT_ON | KeyEvent.META_CTRL_RIGHT_ON,
                    KeyEvent.META_CTRL_ON);
            mMetaState = setGenericModifier(
                    mMetaState,
                    KeyEvent.META_SHIFT_LEFT_ON | KeyEvent.META_SHIFT_RIGHT_ON,
                    KeyEvent.META_SHIFT_ON);
            mMetaState = setGenericModifier(
                    mMetaState,
                    KeyEvent.META_META_LEFT_ON | KeyEvent.META_META_RIGHT_ON,
                    KeyEvent.META_META_ON);
        }

        private static int setGenericModifier(
                final int state,
                final int directionalMask,
                final int genericMask) {
            if ((state & directionalMask) != 0) {
                return state | genericMask;
            }
            return state & ~genericMask;
        }
    }

    private static final class Watch {
        final FileInputStream input;
        Thread thread;

        Watch(final FileInputStream input) {
            this.input = input;
        }
    }
}
