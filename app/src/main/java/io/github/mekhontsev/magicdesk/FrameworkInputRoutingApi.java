package io.github.mekhontsev.magicdesk;

import android.view.InputDevice;

import android.os.IBinder;

import java.io.IOException;
import java.lang.reflect.Method;

/** Android 15+ input-location/display-identity association primitives. Shell only. */
final class FrameworkInputRoutingApi implements InputRoutingLease.Api {
    private final Object mInputManager;
    private final Method mAddUniqueId;
    private final Method mRemoveUniqueId;
    private final Method mAddPort;
    private final Method mRemovePort;
    private final Object mDisplayManager;
    private final Method mGetDisplayInfo;

    FrameworkInputRoutingApi() throws ReflectiveOperationException {
        final IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "input");
        mInputManager = Class.forName("android.hardware.input.IInputManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, binder);
        final Class<?> input = Class.forName("android.hardware.input.IInputManager");
        mAddUniqueId = input.getMethod("addUniqueIdAssociationByPort", String.class, String.class);
        mRemoveUniqueId = input.getMethod("removeUniqueIdAssociationByPort", String.class);
        mAddPort = input.getMethod("addPortAssociation", String.class, int.class);
        mRemovePort = input.getMethod("removePortAssociation", String.class);
        final Class<?> displays = Class.forName("android.hardware.display.DisplayManagerGlobal");
        mDisplayManager = displays.getMethod("getInstance").invoke(null);
        mGetDisplayInfo = displays.getMethod("getDisplayInfo", int.class);
    }

    String displayUniqueId(final int displayId) throws IOException {
        try {
            final Object info = mGetDisplayInfo.invoke(mDisplayManager, displayId);
            final String uniqueId = info == null ? null
                    : (String) info.getClass().getField("uniqueId").get(info);
            if (displayId < 0 || uniqueId == null || uniqueId.isEmpty()) {
                throw new IOException("display has no routable identity: " + displayId);
            }
            return uniqueId;
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IOException("cannot resolve input routing display", error);
        }
    }

    int[] keyboardDeviceIds(final int displayId) throws ReflectiveOperationException {
        // Public in API 37, hidden but present on the API 35 baseline. Resolve
        // here under shell identity, never from the accessibility key callback.
        final Method associatedDisplay = InputDevice.class.getMethod("getAssociatedDisplayId");
        final java.util.ArrayList<Integer> ids = new java.util.ArrayList<>();
        for (final int id : InputDevice.getDeviceIds()) {
            final InputDevice device = InputDevice.getDevice(id);
            if (device != null && !device.isVirtual() && device.isExternal()
                    && device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC
                    && ((Integer) associatedDisplay.invoke(device)) == displayId) {
                ids.add(id);
            }
        }
        return ids.stream().mapToInt(Integer::intValue).toArray();
    }

    @Override
    public FrameworkInputRoutingSnapshot snapshot() throws IOException {
        try {
            return FrameworkInputRoutingSnapshot.parse(FrameworkInputSnapshotSource.readLocal());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("input routing snapshot interrupted", error);
        }
    }

    @Override
    public void setUniqueId(final String port, final String uniqueId) throws IOException {
        if (uniqueId == null) {
            call(mRemoveUniqueId, port);
        } else {
            call(mAddUniqueId, port, uniqueId);
        }
    }

    @Override
    public void setDisplayPort(final String port, final Integer displayPort) throws IOException {
        if (displayPort == null) {
            call(mRemovePort, port);
        } else {
            call(mAddPort, port, displayPort);
        }
    }

    private void call(final Method method, final Object... args) throws IOException {
        try {
            method.invoke(mInputManager, args);
        } catch (ReflectiveOperationException | RuntimeException error) {
            throw new IOException("input association change failed", error);
        }
    }
}
