package io.github.mekhontsev.magicdesk;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

final class RawMouseButtonWatcher {
    interface Listener {
        void onSecondaryButton(boolean pressed);
    }

    private static final int INPUT_EVENT_SIZE_32 = 16;
    private static final int INPUT_EVENT_TYPE_OFFSET_32 = 8;
    private static final int INPUT_EVENT_CODE_OFFSET_32 = 10;
    private static final int INPUT_EVENT_VALUE_OFFSET_32 = 12;
    private static final int INPUT_EVENT_SIZE_64 = 24;
    private static final int INPUT_EVENT_TYPE_OFFSET_64 = 16;
    private static final int INPUT_EVENT_CODE_OFFSET_64 = 18;
    private static final int INPUT_EVENT_VALUE_OFFSET_64 = 20;
    private static final int EV_KEY = 1;
    private static final int EV_MSC = 4;
    private static final int EV_SYN = 0;
    private static final int MSC_SCAN = 4;
    private static final int KEY_UNKNOWN = 0xf0;
    private static final int HID_BUTTON_TWO_SCAN = 0x00090002;

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
                "MagicDeskRawRightButton-" + inputDeviceId);
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
                ? INPUT_EVENT_TYPE_OFFSET_64
                : INPUT_EVENT_TYPE_OFFSET_32;
        final int codeOffset = is64Bit
                ? INPUT_EVENT_CODE_OFFSET_64
                : INPUT_EVENT_CODE_OFFSET_32;
        final int valueOffset = is64Bit
                ? INPUT_EVENT_VALUE_OFFSET_64
                : INPUT_EVENT_VALUE_OFFSET_32;
        final byte[] event = new byte[eventSize];
        final ByteBuffer decoded =
                ByteBuffer.wrap(event).order(ByteOrder.nativeOrder());
        boolean buttonTwoScanPending = false;
        try {
            while (!mStopped && readInputEvent(input, event)) {
                final int type =
                        decoded.getShort(typeOffset) & 0xffff;
                final int code =
                        decoded.getShort(codeOffset) & 0xffff;
                final int value = decoded.getInt(valueOffset);
                if (type == EV_MSC && code == MSC_SCAN) {
                    buttonTwoScanPending =
                            value == HID_BUTTON_TWO_SCAN;
                } else if (type == EV_KEY && code == KEY_UNKNOWN) {
                    if (!buttonTwoScanPending) {
                        continue;
                    }
                    if (value == 0 || value == 1) {
                        listener.onSecondaryButton(value == 1);
                    }
                    buttonTwoScanPending = false;
                } else if (type == EV_SYN) {
                    buttonTwoScanPending = false;
                }
            }
        } catch (IOException e) {
            if (!mStopped) {
                System.err.println(
                        "MAGICDESK_RIGHT_BUTTON_RAW_ERROR source="
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

    private static final class Watch {
        final FileInputStream input;
        Thread thread;

        Watch(final FileInputStream input) {
            this.input = input;
        }
    }
}
