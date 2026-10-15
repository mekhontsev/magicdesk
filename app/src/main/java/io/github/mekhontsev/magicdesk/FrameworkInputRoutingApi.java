package io.github.mekhontsev.magicdesk;

import android.view.InputDevice;

import android.os.IBinder;

import java.io.IOException;
import java.lang.reflect.Method;

/** Android 14+ input-location/display-identity association primitives. Shell only. */
final class FrameworkInputRoutingApi implements InputRoutingLease.Api {
    private final Object mInputManager;
    private final Method mAddUniqueId;
    private final Method mRemoveUniqueId;
    private final Method mAddPort;
    private final Method mRemovePort;
    private final Method mGetInputDeviceIds;
    private final Method mGetInputDevice;
    private final Object mDisplayManager;
    private final Method mGetDisplayInfo;

    FrameworkInputRoutingApi() throws ReflectiveOperationException {
        final IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "input");
        mInputManager = Class.forName("android.hardware.input.IInputManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, binder);
        final Class<?> input = Class.forName("android.hardware.input.IInputManager");
        // Android 15 distinguishes port and descriptor associations. API 34's
        // unqualified methods have the same port-to-display semantics.
        final boolean byPort = android.os.Build.VERSION.SDK_INT >= 35;
        mAddUniqueId = input.getMethod(byPort ? "addUniqueIdAssociationByPort"
                : "addUniqueIdAssociation", String.class, String.class);
        mRemoveUniqueId = input.getMethod(byPort ? "removeUniqueIdAssociationByPort"
                : "removeUniqueIdAssociation", String.class);
        mAddPort = input.getMethod("addPortAssociation", String.class, int.class);
        mRemovePort = input.getMethod("removePortAssociation", String.class);
        mGetInputDeviceIds = input.getMethod("getInputDeviceIds");
        mGetInputDevice = input.getMethod("getInputDevice", int.class);
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

    int[] shortcutDeviceIds(final int displayId) throws ReflectiveOperationException {
        // Public in API 37, hidden but present on the API 34 baseline. Resolve
        // here under shell identity, never from the accessibility key callback.
        final Method associatedDisplay = InputDevice.class.getMethod("getAssociatedDisplayId");
        final java.util.ArrayList<Integer> ids = new java.util.ArrayList<>();
        // The app's device callback can precede this process's cache invalidation.
        // Read the committed Binder inventory, not InputManagerGlobal's local cache.
        for (final int id : (int[]) mGetInputDeviceIds.invoke(mInputManager)) {
            final InputDevice device = (InputDevice) mGetInputDevice.invoke(mInputManager, id);
            if (device != null && !device.isVirtual() && device.isExternal()
                    && (device.supportsSource(InputDevice.SOURCE_KEYBOARD)
                            || device.supportsSource(InputDevice.SOURCE_MOUSE))
                    && ((Integer) associatedDisplay.invoke(device)) == displayId) {
                ids.add(id);
            }
        }
        return ids.stream().mapToInt(Integer::intValue).toArray();
    }

    static boolean usesGlobalPointerRouting(final int sdk) {
        return sdk == 34;
    }

    static void requirePointerTarget(final int sdk, final int displayId,
            final FrameworkInputRoutingSnapshot state) throws IOException {
        if (!usesGlobalPointerRouting(sdk)) return;
        // API 34 drops mouse events when the associated viewport differs from
        // its shared pointer controller. An association alone is not readiness.
        // Before the first mouse exists, only the system's requested target exists;
        // the device-added refresh also verifies the instantiated controller.
        if (state.requestedPointerDisplayId != null
                && state.requestedPointerDisplayId == displayId
                && (!state.pointerControllerPresent
                        || state.pointerDisplayId != null && state.pointerDisplayId == displayId)) return;
        throw new IOException("Android 14 system pointer target does not match display " + displayId
                + " (requested=" + state.requestedPointerDisplayId
                + ", controller=" + state.pointerDisplayId + "). "
                + (displayId > 0
                        ? "Enable System desktop mode on external displays in Settings, reconnect the display, "
                                + "then select Control this display again. Android chooses one pointer display; "
                                + "another connected display may take priority."
                        : "Release external input and disconnect the external display or disable System desktop "
                                + "mode on external displays before reconnecting it."));
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
