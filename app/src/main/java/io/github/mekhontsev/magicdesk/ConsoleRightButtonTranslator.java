package io.github.mekhontsev.magicdesk;

import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InputMonitor;
import android.view.MotionEvent;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class ConsoleRightButtonTranslator {
    private static final String TAG = "MagicDeskRightButton";

    private final Object mInputManager;
    private final Class<?> mInputManagerInterface;
    private final ConsoleInputEventInjector mInjector;
    private final int mDisplayId;
    private final List<ConsoleMouseDevice> mMouseDevices;
    private final RawMouseButtonWatcher mRawWatcher =
            new RawMouseButtonWatcher();

    private HandlerThread mThread;
    private Handler mHandler;
    private InputMonitor mMonitor;
    private RightButtonInputReceiver mReceiver;

    ConsoleRightButtonTranslator(
            final Object inputManager,
            final Class<?> inputManagerInterface,
            final ConsoleInputEventInjector injector,
            final int displayId,
            final List<ConsoleMouseDevice> mouseDevices) {
        mInputManager = inputManager;
        mInputManagerInterface = inputManagerInterface;
        mInjector = injector;
        mDisplayId = displayId;
        mMouseDevices = mouseDevices;
    }

    void start() throws Exception {
        if (mDisplayId <= 0 || countActiveMice() == 0) {
            throw new IllegalStateException(
                    "right-button input target is unavailable");
        }

        final Method monitorGestureInput = mInputManagerInterface.getMethod(
                "monitorGestureInput",
                IBinder.class,
                String.class,
                int.class);
        final Method setActionButton = MotionEvent.class.getMethod(
                "setActionButton", int.class);

        final HandlerThread thread =
                new HandlerThread("MagicDeskRightButton");
        thread.start();
        final Handler handler = new Handler(thread.getLooper());
        final Binder monitorToken = new Binder();
        mThread = thread;
        mHandler = handler;

        final CountDownLatch ready = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        handler.post(() -> {
            try {
                final InputMonitor monitor =
                        (InputMonitor) monitorGestureInput.invoke(
                                mInputManager,
                                monitorToken,
                                "MagicDesk right button",
                                Integer.valueOf(mDisplayId));
                mMonitor = monitor;
                mReceiver = new RightButtonInputReceiver(
                        monitor, setActionButton);
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                ready.countDown();
            }
        });
        if (!ready.await(5, TimeUnit.SECONDS)) {
            throw new IOException(
                    "timed out creating right-button input monitor");
        }
        if (failure.get() != null) {
            throw new IOException(
                    "failed to create right-button input monitor",
                    failure.get());
        }

        for (final ConsoleMouseDevice mouse : mMouseDevices) {
            if (mouse.inputDeviceId >= 0) {
                mRawWatcher.start(
                        mouse.path,
                        mouse.inputDeviceId,
                        pressed -> handleRawButton(mouse, pressed));
            }
        }
    }

    void stop() {
        mRawWatcher.stop();
        final Handler handler = mHandler;
        final HandlerThread thread = mThread;
        if (handler != null && thread != null && thread.isAlive()) {
            final CountDownLatch stopped = new CountDownLatch(1);
            if (handler.post(() -> {
                disposeMonitor();
                stopped.countDown();
            })) {
                try {
                    stopped.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            }
            thread.quitSafely();
        } else {
            disposeMonitor();
        }
        mHandler = null;
        mThread = null;
    }

    boolean isReady() {
        return mReceiver != null;
    }

    private void handleRawButton(
            final ConsoleMouseDevice mouse,
            final boolean pressed) {
        final Handler handler = mHandler;
        final RightButtonInputReceiver receiver = mReceiver;
        if (handler != null && receiver != null && mouse.inputDeviceId >= 0) {
            handler.post(() -> receiver.setSecondaryButtonPressed(
                    mouse.inputDeviceId, pressed));
        }
    }

    private int countActiveMice() {
        int count = 0;
        for (final ConsoleMouseDevice mouse : mMouseDevices) {
            if (mouse.inputDeviceId >= 0) {
                count++;
            }
        }
        return count;
    }

    private boolean isActiveMouseInputDevice(final int inputDeviceId) {
        for (final ConsoleMouseDevice mouse : mMouseDevices) {
            if (mouse.inputDeviceId == inputDeviceId) {
                return true;
            }
        }
        return false;
    }

    private void disposeMonitor() {
        final RightButtonInputReceiver receiver = mReceiver;
        mReceiver = null;
        if (receiver != null) {
            receiver.dispose();
        }
        final InputMonitor monitor = mMonitor;
        mMonitor = null;
        if (monitor != null) {
            monitor.dispose();
        }
    }

    private final class RightButtonInputReceiver
            extends InputEventReceiver {
        private final Method mSetActionButton;
        private final Map<Integer, MotionEvent> mPointerTemplates =
                new HashMap<>();
        private final Set<Integer> mSecondaryButtonArmed =
                new LinkedHashSet<>();

        RightButtonInputReceiver(
                final InputMonitor monitor,
                final Method setActionButton) {
            super(monitor.getInputChannel(), mThread.getLooper());
            mSetActionButton = setActionButton;
        }

        @Override
        public void onInputEvent(final InputEvent inputEvent) {
            try {
                if (inputEvent instanceof MotionEvent
                        && isActiveMouseInputDevice(inputEvent.getDeviceId())
                        && inputEvent.isFromSource(InputDevice.SOURCE_MOUSE)) {
                    updatePointerTemplate((MotionEvent) inputEvent);
                }
            } finally {
                finishInputEvent(inputEvent, false);
            }
        }

        void setSecondaryButtonPressed(
                final int inputDeviceId,
                final boolean pressed) {
            final Integer deviceId = Integer.valueOf(inputDeviceId);
            if (pressed) {
                mSecondaryButtonArmed.add(deviceId);
                return;
            }
            if (!mSecondaryButtonArmed.remove(deviceId)) {
                return;
            }
            final MotionEvent pointerTemplate =
                    mPointerTemplates.get(deviceId);
            if (pointerTemplate == null) {
                Log.w(TAG,
                        "right click ignored before mouse position was observed"
                                + " device=" + inputDeviceId);
                return;
            }

            boolean pointerDown = false;
            final long sequenceDownTime = SystemClock.uptimeMillis();
            try {
                injectButtonAction(
                        pointerTemplate,
                        inputDeviceId,
                        sequenceDownTime,
                        MotionEvent.ACTION_DOWN,
                        MotionEvent.BUTTON_SECONDARY,
                        0);
                pointerDown = true;
                injectButtonAction(
                        pointerTemplate,
                        inputDeviceId,
                        sequenceDownTime,
                        MotionEvent.ACTION_BUTTON_PRESS,
                        MotionEvent.BUTTON_SECONDARY,
                        MotionEvent.BUTTON_SECONDARY);
                injectButtonAction(
                        pointerTemplate,
                        inputDeviceId,
                        sequenceDownTime,
                        MotionEvent.ACTION_BUTTON_RELEASE,
                        0,
                        MotionEvent.BUTTON_SECONDARY);
                injectButtonAction(
                        pointerTemplate,
                        inputDeviceId,
                        sequenceDownTime,
                        MotionEvent.ACTION_UP,
                        0,
                        0);
                pointerDown = false;
            } catch (Exception error) {
                Log.e(TAG, "secondary click injection failed", error);
                System.err.println(
                        "MAGICDESK_RIGHT_BUTTON_ERROR " + error);
                if (pointerDown) {
                    cancelSecondaryClickBestEffort(
                            pointerTemplate,
                            inputDeviceId,
                            sequenceDownTime);
                }
            }
        }

        @Override
        public void dispose() {
            for (final MotionEvent pointerTemplate
                    : mPointerTemplates.values()) {
                pointerTemplate.recycle();
            }
            mPointerTemplates.clear();
            mSecondaryButtonArmed.clear();
            super.dispose();
        }

        private void updatePointerTemplate(final MotionEvent event) {
            final Integer deviceId = Integer.valueOf(event.getDeviceId());
            final MotionEvent previous = mPointerTemplates.put(
                    deviceId, MotionEvent.obtain(event));
            if (previous != null) {
                previous.recycle();
            }
        }

        private void cancelSecondaryClickBestEffort(
                final MotionEvent pointerTemplate,
                final int inputDeviceId,
                final long sequenceDownTime) {
            try {
                injectButtonAction(
                        pointerTemplate,
                        inputDeviceId,
                        sequenceDownTime,
                        MotionEvent.ACTION_CANCEL,
                        0,
                        0);
            } catch (Exception error) {
                Log.w(TAG,
                        "failed to cancel partial secondary click",
                        error);
            }
        }

        private void injectButtonAction(
                final MotionEvent pointerTemplate,
                final int inputDeviceId,
                final long sequenceDownTime,
                final int action,
                final int buttonState,
                final int actionButton) throws Exception {
            final MotionEvent translated = createSecondaryButtonEvent(
                    pointerTemplate,
                    inputDeviceId,
                    sequenceDownTime,
                    action,
                    buttonState,
                    actionButton);
            try {
                if (!mInjector.inject(translated)) {
                    throw new IOException(
                            "secondary-button injection was rejected for "
                                    + MotionEvent.actionToString(action));
                }
            } finally {
                translated.recycle();
            }
        }

        private MotionEvent createSecondaryButtonEvent(
                final MotionEvent source,
                final int inputDeviceId,
                final long sequenceDownTime,
                final int action,
                final int buttonState,
                final int actionButton)
                throws ReflectiveOperationException {
            final int pointerCount = source.getPointerCount();
            final MotionEvent.PointerProperties[] properties =
                    new MotionEvent.PointerProperties[pointerCount];
            final MotionEvent.PointerCoords[] coordinates =
                    new MotionEvent.PointerCoords[pointerCount];
            for (int index = 0; index < pointerCount; index++) {
                properties[index] = new MotionEvent.PointerProperties();
                coordinates[index] = new MotionEvent.PointerCoords();
                source.getPointerProperties(index, properties[index]);
                source.getPointerCoords(index, coordinates[index]);
            }
            final MotionEvent translated = MotionEvent.obtain(
                    sequenceDownTime,
                    SystemClock.uptimeMillis(),
                    action,
                    pointerCount,
                    properties,
                    coordinates,
                    source.getMetaState(),
                    buttonState,
                    source.getXPrecision(),
                    source.getYPrecision(),
                    inputDeviceId,
                    source.getEdgeFlags(),
                    source.getSource(),
                    mDisplayId,
                    0,
                    source.getClassification());
            if (actionButton != 0) {
                mSetActionButton.invoke(
                        translated, Integer.valueOf(actionButton));
            }
            return translated;
        }
    }
}
