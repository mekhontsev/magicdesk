package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.res.Configuration;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.view.InputDevice;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class RuntimeInputCoordinator implements InputManager.InputDeviceListener {
    interface Listener {
        void onInputStateChanged(
                Snapshot snapshot,
                boolean keyboardChanged,
                boolean mouseChanged,
                boolean inventoryChanged);
    }

    private static final long CHANGE_DEBOUNCE_MILLIS = 600;
    private static final String KEYBOARD_NAME = "MagicDesk Keyboard";
    private static final String MOUSE_NAME = "MagicDesk Mouse";

    private final Context mContext;
    private final Handler mHandler;
    private final Listener mListener;
    private final InputManager mInputManager;
    private final Runnable mRefresh = this::refresh;
    private Snapshot mSnapshot = new Snapshot(false, false, false, "");

    RuntimeInputCoordinator(
            final Context context,
            final Handler handler,
            final Listener listener) {
        mContext = context;
        mHandler = handler;
        mListener = listener;
        mInputManager = context.getSystemService(InputManager.class);
    }

    Snapshot start() {
        mSnapshot = inspect();
        if (mInputManager != null) {
            mInputManager.registerInputDeviceListener(this, mHandler);
        }
        return mSnapshot;
    }

    void stop() {
        mHandler.removeCallbacks(mRefresh);
        if (mInputManager != null) {
            mInputManager.unregisterInputDeviceListener(this);
        }
    }

    void scheduleRefresh() {
        mHandler.removeCallbacks(mRefresh);
        mHandler.postDelayed(mRefresh, CHANGE_DEBOUNCE_MILLIS);
    }

    void logState(final String tag) {
        final Configuration configuration = mContext.getResources().getConfiguration();
        Log.i(tag, "config keyboard=" + configuration.keyboard
                + " hardKeyboardHidden=" + configuration.hardKeyboardHidden
                + " keyboardHidden=" + configuration.keyboardHidden);
        for (final int deviceId : InputDevice.getDeviceIds()) {
            final InputDevice device = InputDevice.getDevice(deviceId);
            if (device == null) {
                continue;
            }
            Log.i(tag, "device id=" + deviceId
                    + " name=" + device.getName()
                    + " external=" + device.isExternal()
                    + " virtual=" + device.isVirtual()
                    + " sources=0x" + Integer.toHexString(device.getSources())
                    + " keyboardType=" + device.getKeyboardType());
        }
    }

    @Override
    public void onInputDeviceAdded(final int deviceId) {
        scheduleRefresh();
    }

    @Override
    public void onInputDeviceRemoved(final int deviceId) {
        scheduleRefresh();
    }

    @Override
    public void onInputDeviceChanged(final int deviceId) {
        scheduleRefresh();
    }

    private void refresh() {
        final Snapshot current = inspect();
        final boolean keyboardChanged =
                current.hardwareKeyboard != mSnapshot.hardwareKeyboard;
        final boolean mouseChanged = current.externalMouse != mSnapshot.externalMouse;
        final boolean inventoryChanged =
                current.magicDeskMouse != mSnapshot.magicDeskMouse
                        || !current.deviceSignature.equals(
                                mSnapshot.deviceSignature);
        if (!keyboardChanged && !mouseChanged && !inventoryChanged) {
            return;
        }
        mSnapshot = current;
        mListener.onInputStateChanged(
                current, keyboardChanged, mouseChanged, inventoryChanged);
    }

    private Snapshot inspect() {
        boolean keyboard = hasConfiguredHardKeyboard();
        boolean mouse = false;
        boolean magicDeskMouse = false;
        final List<String> devices = new ArrayList<>();
        for (final int deviceId : InputDevice.getDeviceIds()) {
            final InputDevice device = InputDevice.getDevice(deviceId);
            if (device == null) {
                continue;
            }
            if (MOUSE_NAME.equals(device.getName())) {
                magicDeskMouse = true;
                continue;
            }
            if (isMagicDeskDevice(device)
                    || device.isVirtual() || !device.isExternal()) {
                continue;
            }
            keyboard |= isAlphabeticKeyboard(device);
            mouse |= (device.getSources() & InputDevice.SOURCE_MOUSE)
                    == InputDevice.SOURCE_MOUSE;
            devices.add(deviceId + ":" + device.getDescriptor()
                    + ":" + device.getVendorId()
                    + ":" + device.getProductId()
                    + ":" + device.getSources()
                    + ":" + device.getKeyboardType());
        }
        Collections.sort(devices);
        return new Snapshot(
                keyboard, mouse, magicDeskMouse, devices.toString());
    }

    private boolean hasConfiguredHardKeyboard() {
        final Configuration configuration = mContext
                .getResources().getConfiguration();
        return configuration.keyboard == Configuration.KEYBOARD_QWERTY
                && configuration.hardKeyboardHidden
                        == Configuration.HARDKEYBOARDHIDDEN_NO;
    }

    private static boolean isAlphabeticKeyboard(final InputDevice device) {
        return (device.getSources() & InputDevice.SOURCE_KEYBOARD)
                        == InputDevice.SOURCE_KEYBOARD
                && device.getKeyboardType()
                        == InputDevice.KEYBOARD_TYPE_ALPHABETIC;
    }

    private static boolean isMagicDeskDevice(final InputDevice device) {
        final String name = device.getName();
        return name.startsWith(KEYBOARD_NAME) || MOUSE_NAME.equals(name);
    }

    static final class Snapshot {
        final boolean hardwareKeyboard;
        final boolean externalMouse;
        final boolean magicDeskMouse;
        final String deviceSignature;

        Snapshot(
                final boolean hardwareKeyboard,
                final boolean externalMouse,
                final boolean magicDeskMouse,
                final String deviceSignature) {
            this.hardwareKeyboard = hardwareKeyboard;
            this.externalMouse = externalMouse;
            this.magicDeskMouse = magicDeskMouse;
            this.deviceSignature = deviceSignature;
        }
    }
}
