package io.github.mekhontsev.magicdesk;

import android.os.IBinder;
import android.os.SystemClock;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class DesktopInputRoutingSession implements AutoCloseable {
    private static final int DISPLAY_TYPE_EXTERNAL = 2;
    private static final long VIRTUAL_DEVICE_TIMEOUT_MILLIS = 3_000L;
    private static final long VIRTUAL_DEVICE_POLL_MILLIS = 100L;
    private static final String VIRTUAL_KEYBOARD_LOCATION_PREFIX =
            "magicdesk-keyboard-";
    private static final String VIRTUAL_MOUSE_LOCATION = "magicdesk-mouse";

    private final Set<String> mAssociatedInputPorts =
            new LinkedHashSet<>();
    private Object mInputManager;
    private Method mAddAssociation;
    private Method mRemoveAssociation;
    private Object mAssociationTarget;
    private int mDisplayId = -1;
    private int mVirtualKeyboardCount;
    private boolean mClosed;

    private DesktopInputRoutingSession() {
    }

    static DesktopInputRoutingSession open(
            final int displayId,
            final int expectedVirtualKeyboardCount) throws Exception {
        if (displayId <= 0) {
            throw new IllegalArgumentException(
                    "input routing requires a secondary display");
        }
        if (expectedVirtualKeyboardCount < 0) {
            throw new IllegalArgumentException(
                    "virtual keyboard count must not be negative");
        }
        final List<DesktopKeyboardDevice> keyboards =
                expectedVirtualKeyboardCount > 0
                        ? waitForVirtualKeyboards(
                                expectedVirtualKeyboardCount)
                        : Collections.emptyList();
        final List<DesktopMouseDevice> mice = waitForVirtualMouse();
        cleanupStaleAssociations();
        final DesktopInputRoutingSession session =
                new DesktopInputRoutingSession();
        try {
            session.start(displayId, keyboards, mice);
            session.mVirtualKeyboardCount = keyboards.size();
            return session;
        } catch (Exception error) {
            session.close();
            throw error;
        }
    }

    int displayId() {
        return mDisplayId;
    }

    int associationCount() {
        return mAssociatedInputPorts.size();
    }

    int virtualKeyboardCount() {
        return mVirtualKeyboardCount;
    }

    static int cleanupStaleAssociations() throws Exception {
        final Set<String> ownedPorts =
                DesktopInputRoutingOwnership.read();
        if (ownedPorts.isEmpty()) {
            return 0;
        }

        final Object inputManager = getService(
                "input", "android.hardware.input.IInputManager");
        final Class<?> inputManagerInterface =
                Class.forName("android.hardware.input.IInputManager");
        final Method removePortAssociation =
                inputManagerInterface.getMethod(
                        "removePortAssociation", String.class);
        final Method removeUniqueIdAssociation =
                inputManagerInterface.getMethod(
                        "removeUniqueIdAssociationByPort", String.class);
        removeAssociations(
                inputManager, removePortAssociation, ownedPorts);
        removeAssociations(
                inputManager, removeUniqueIdAssociation, ownedPorts);

        final Set<String> remaining =
                DesktopInputRoutingOwnership.findActiveAssociations(
                        FrameworkInputSnapshotSource.readLocal());
        remaining.retainAll(ownedPorts);
        if (!remaining.isEmpty()) {
            throw new IOException(
                    "input associations remain after cleanup: "
                            + remaining);
        }
        DesktopInputRoutingOwnership.clear();
        return ownedPorts.size();
    }

    private void start(
            final int displayId,
            final List<DesktopKeyboardDevice> keyboards,
            final List<DesktopMouseDevice> mice) throws Exception {
        mInputManager = getService(
                "input", "android.hardware.input.IInputManager");
        final Class<?> inputManagerInterface =
                Class.forName("android.hardware.input.IInputManager");
        final RoutingTarget target = findRoutingTarget(displayId);
        mDisplayId = displayId;
        mAssociationTarget = target.associationTarget;
        if (target.physicalPort) {
            mAddAssociation = inputManagerInterface.getMethod(
                    "addPortAssociation", String.class, int.class);
            mRemoveAssociation = inputManagerInterface.getMethod(
                    "removePortAssociation", String.class);
        } else {
            mAddAssociation = inputManagerInterface.getMethod(
                    "addUniqueIdAssociationByPort",
                    String.class,
                    String.class);
            mRemoveAssociation = inputManagerInterface.getMethod(
                    "removeUniqueIdAssociationByPort", String.class);
        }
        associateRelayPorts(keyboards, mice);
    }

    private void associateRelayPorts(
            final List<DesktopKeyboardDevice> keyboards,
            final List<DesktopMouseDevice> mice) throws IOException, ReflectiveOperationException {
        final Set<String> requestedPorts = new LinkedHashSet<>();
        for (final DesktopKeyboardDevice keyboard : keyboards) {
            addRequestedPort(requestedPorts, keyboard.location);
        }
        for (final DesktopMouseDevice mouse : mice) {
            addRequestedPort(requestedPorts, mouse.location);
        }
        DesktopInputRoutingOwnership.record(requestedPorts);

        for (final String port : requestedPorts) {
            associatePort(port);
        }
    }

    private boolean associatePort(final String location)
            throws ReflectiveOperationException {
        if (location == null
                || location.isEmpty()
                || !mAssociatedInputPorts.add(location)) {
            return false;
        }
        try {
            mAddAssociation.invoke(
                    mInputManager, location, mAssociationTarget);
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            mAssociatedInputPorts.remove(location);
            throw error;
        }
    }

    private static void addRequestedPort(
            final Set<String> ports,
            final String location) {
        if (location != null && !location.isEmpty()) {
            ports.add(location);
        }
    }

    private static RoutingTarget findRoutingTarget(final int displayId)
            throws Exception {
        final Object displayManager = getService(
                "display", "android.hardware.display.IDisplayManager");
        final Class<?> displayManagerInterface =
                Class.forName("android.hardware.display.IDisplayManager");
        final Method getDisplayInfo = displayManagerInterface.getMethod(
                "getDisplayInfo", int.class);
        final Object info = getDisplayInfo.invoke(displayManager, displayId);
        if (info == null) {
            throw new IllegalStateException(
                    "target display is unavailable: " + displayId);
        }
        if (getIntField(info, "type") == DISPLAY_TYPE_EXTERNAL) {
            final Object address = getField(info, "address");
            try {
                final Object port = address.getClass()
                        .getMethod("getPort").invoke(address);
                if (port instanceof Number) {
                    return RoutingTarget.physical(
                            ((Number) port).intValue());
                }
            } catch (NullPointerException
                    | ReflectiveOperationException ignored) {
                // Non-physical display addresses do not expose a port.
            }
        }
        final Object uniqueIdValue = getField(info, "uniqueId");
        final String uniqueId = uniqueIdValue == null
                ? "" : uniqueIdValue.toString().trim();
        if (uniqueId.isEmpty()) {
            throw new IllegalStateException(
                    "target display has no routable identity: " + displayId);
        }
        return RoutingTarget.uniqueId(uniqueId);
    }

    @Override
    public synchronized void close() {
        if (mClosed) {
            return;
        }
        mClosed = true;

        boolean associationsRemoved = mAssociatedInputPorts.isEmpty();
        if (mRemoveAssociation != null && mInputManager != null) {
            try {
                removeAssociations(
                        mInputManager,
                        mRemoveAssociation,
                        mAssociatedInputPorts);
                associationsRemoved = true;
            } catch (ReflectiveOperationException
                    | RuntimeException error) {
                System.err.println(
                        "MAGICDESK_INPUT_ROUTING_CLEANUP ports="
                                + error);
            }
        }
        if (associationsRemoved) {
            try {
                DesktopInputRoutingOwnership.clear();
            } catch (IOException error) {
                System.err.println(
                        "MAGICDESK_INPUT_ROUTING_CLEANUP ownership="
                                + error);
            }
        }
        mAssociatedInputPorts.clear();
        mDisplayId = -1;
        mAssociationTarget = null;
        mVirtualKeyboardCount = 0;
    }

    private static List<DesktopKeyboardDevice> waitForVirtualKeyboards(
            final int expectedCount)
            throws IOException, InterruptedException {
        final long deadline = SystemClock.uptimeMillis()
                + VIRTUAL_DEVICE_TIMEOUT_MILLIS;
        List<DesktopKeyboardDevice> keyboards;
        do {
            keyboards = selectRelayKeyboards(
                    DesktopInputDeviceDiscovery.findRoutableKeyboards());
            if (keyboards.size() == expectedCount) {
                return keyboards;
            }
            BoundedStateAwaiter.pauseInterruptibly(
                    BoundedStateAwaiter.Reason.INPUT_DEVICE,
                    VIRTUAL_DEVICE_POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException(
                "Expected " + expectedCount
                        + " MagicDesk virtual keyboards in EventHub");
    }

    private static List<DesktopMouseDevice> waitForVirtualMouse()
            throws IOException, InterruptedException {
        final long deadline = SystemClock.uptimeMillis()
                + VIRTUAL_DEVICE_TIMEOUT_MILLIS;
        List<DesktopMouseDevice> mice;
        do {
            mice = selectRelayMice(DesktopInputDeviceDiscovery.findRoutableMice());
            if (mice.size() == 1) {
                return mice;
            }
            BoundedStateAwaiter.pauseInterruptibly(
                    BoundedStateAwaiter.Reason.INPUT_DEVICE,
                    VIRTUAL_DEVICE_POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException(
                "MagicDesk virtual mouse is missing from EventHub");
    }

    static List<DesktopMouseDevice> selectRelayMice(
            final List<DesktopMouseDevice> mice) {
        final List<DesktopMouseDevice> result = new ArrayList<>();
        for (final DesktopMouseDevice mouse : mice) {
            if (VIRTUAL_MOUSE_LOCATION.equals(mouse.location)) {
                result.add(mouse);
            }
        }
        return result;
    }

    static List<DesktopKeyboardDevice> selectRelayKeyboards(
            final List<DesktopKeyboardDevice> keyboards) {
        final List<DesktopKeyboardDevice> result = new ArrayList<>();
        // EVIOCGRAB sources keep their system route. Associating them as well
        // disables the physical keyboard on display loss, triggering a global
        // configuration transition while WM is still removing that display.
        for (final DesktopKeyboardDevice keyboard : keyboards) {
            if (keyboard.location.startsWith(VIRTUAL_KEYBOARD_LOCATION_PREFIX)) {
                result.add(keyboard);
            }
        }
        return result;
    }

    private static void removeAssociations(
            final Object inputManager,
            final Method removePortAssociation,
            final Set<String> inputPorts)
            throws ReflectiveOperationException {
        for (final String inputPort : inputPorts) {
            removePortAssociation.invoke(inputManager, inputPort);
        }
    }

    private static Object getService(
            final String name,
            final String interfaceName) throws Exception {
        final Class<?> serviceManager =
                Class.forName("android.os.ServiceManager");
        final Object binder = serviceManager
                .getMethod("getService", String.class)
                .invoke(null, name);
        final Class<?> stub = Class.forName(interfaceName + "$Stub");
        return stub.getMethod("asInterface", IBinder.class)
                .invoke(null, binder);
    }

    private static Object getField(
            final Object target,
            final String fieldName) throws ReflectiveOperationException {
        final Field field = target.getClass().getField(fieldName);
        return field.get(target);
    }

    private static int getIntField(
            final Object target,
            final String fieldName) throws ReflectiveOperationException {
        final Field field = target.getClass().getField(fieldName);
        return field.getInt(target);
    }

    private static final class RoutingTarget {
        final boolean physicalPort;
        final Object associationTarget;

        private RoutingTarget(
                final boolean physicalPort,
                final Object associationTarget) {
            this.physicalPort = physicalPort;
            this.associationTarget = associationTarget;
        }

        static RoutingTarget physical(final int displayPort) {
            return new RoutingTarget(
                    true, Integer.valueOf(displayPort));
        }

        static RoutingTarget uniqueId(final String displayUniqueId) {
            return new RoutingTarget(false, displayUniqueId);
        }
    }
}
