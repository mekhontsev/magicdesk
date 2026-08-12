package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.os.Binder;
import android.os.IBinder;
import android.os.SystemClock;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class DesktopInputRoutingSession implements AutoCloseable {
    private static final int DISPLAY_TYPE_EXTERNAL = 2;
    private static final long VIRTUAL_KEYBOARD_TIMEOUT_MILLIS = 3_000L;
    private static final long VIRTUAL_KEYBOARD_POLL_MILLIS = 100L;
    private static final long DISPLAY_COMMAND_TIMEOUT_MILLIS = 3_000L;
    private static final int DISPLAY_COMMAND_OUTPUT_LIMIT_BYTES = 64 * 1024;
    private static final String VIRTUAL_KEYBOARD_LOCATION_PREFIX =
            "magicdesk-keyboard-";
    private static final String DUMPSYS = "/system/bin/dumpsys";

    private final Set<String> mAssociatedInputPorts =
            new LinkedHashSet<>();

    private Object mInputManager;
    private Method mAddAssociation;
    private Method mRemoveAssociation;
    private Object mAssociationTarget;
    private Object mDisplayManager;
    private Method mNotePanelStatus;
    private Binder mPanelToken;
    private boolean mUsesPlatformConsoleHooks;
    private boolean mMouseInputSourceOverride;
    private int mDisplayId = -1;
    private int mKeyboardAssociationCount;
    private int mVirtualKeyboardCount;
    private boolean mClosed;

    private DesktopInputRoutingSession() {
    }

    static DesktopInputRoutingSession open(
            final Context context,
            final int displayId,
            final int expectedVirtualKeyboardCount) throws Exception {
        if (context == null) {
            throw new IllegalArgumentException(
                    "input routing requires a service context");
        }
        if (displayId <= 0) {
            throw new IllegalArgumentException(
                    "input routing requires a secondary display");
        }
        if (expectedVirtualKeyboardCount < 0) {
            throw new IllegalArgumentException(
                    "virtual keyboard count must not be negative");
        }
        final List<DesktopKeyboardDevice> keyboards =
                waitForVirtualKeyboards(expectedVirtualKeyboardCount);
        final List<DesktopMouseDevice> mice =
                DesktopInputDeviceDiscovery.findRoutableMice();
        cleanupStaleAssociations();
        final DesktopInputRoutingSession session =
                new DesktopInputRoutingSession();
        try {
            session.start(context, displayId, keyboards, mice);
            session.mVirtualKeyboardCount =
                    countVirtualKeyboards(keyboards);
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

    int keyboardAssociationCount() {
        return mKeyboardAssociationCount;
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
                        InputStateDump.read());
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
            final Context context,
            final int displayId,
            final List<DesktopKeyboardDevice> keyboards,
            final List<DesktopMouseDevice> mice) throws Exception {
        mInputManager = getService(
                "input", "android.hardware.input.IInputManager");
        final Class<?> inputManagerInterface =
                Class.forName("android.hardware.input.IInputManager");
        final RoutingTarget target = findRoutingTarget(context, displayId);
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
        mUsesPlatformConsoleHooks = target.platformConsole;

        final Set<String> requestedPorts = new LinkedHashSet<>();
        for (final DesktopKeyboardDevice keyboard : keyboards) {
            addRequestedPort(requestedPorts, keyboard.location);
        }
        for (final DesktopMouseDevice mouse : mice) {
            addRequestedPort(requestedPorts, mouse.location);
        }
        DesktopInputRoutingOwnership.record(requestedPorts);

        int keyboardAssociations = 0;
        for (final DesktopKeyboardDevice keyboard : keyboards) {
            if (associatePort(
                    keyboard.location)) {
                keyboardAssociations++;
            }
        }
        mKeyboardAssociationCount = keyboardAssociations;
        for (final DesktopMouseDevice mouse : mice) {
            associatePort(mouse.location);
        }

        if (PlatformDrivers.current().phoneUi().usesMirrorInputPanel()) {
            initializePanelRegistration();
        }
        registerPanelToken();
        if (mUsesPlatformConsoleHooks) {
            setMouseInputSourceOverride(true);
        } else {
            PlatformDrivers.current().pointer().refreshViewport();
        }
    }

    synchronized int refreshAssociations() throws Exception {
        if (mClosed || mInputManager == null
                || mAddAssociation == null || mAssociationTarget == null) {
            return 0;
        }
        registerPanelToken();
        int added = 0;
        for (final DesktopKeyboardDevice keyboard
                : DesktopInputDeviceDiscovery.findRoutableKeyboards()) {
            if (associatePort(
                    keyboard.location)) {
                mKeyboardAssociationCount++;
                added++;
            }
        }
        for (final DesktopMouseDevice mouse
                : DesktopInputDeviceDiscovery.findRoutableMice()) {
            if (associatePort(mouse.location)) {
                added++;
            }
        }
        if (added > 0) {
            DesktopInputRoutingOwnership.record(mAssociatedInputPorts);
        }
        return added;
    }

    private void initializePanelRegistration() {
        try {
            mDisplayManager = getService(
                    "display", "android.hardware.display.IDisplayManager");
            mNotePanelStatus = resolvePanelStatusMethod();
            mPanelToken = new Binder();
        } catch (Exception error) {
            mDisplayManager = null;
            mNotePanelStatus = null;
            mPanelToken = null;
            System.err.println(
                    "MAGICDESK_INPUT_ROUTING_PANEL unavailable="
                            + error);
        }
    }

    static void verifyMirrorPanelApi() throws ReflectiveOperationException {
        resolvePanelStatusMethod();
    }

    private static Method resolvePanelStatusMethod()
            throws ReflectiveOperationException {
        return Class.forName("android.hardware.display.IDisplayManager")
                .getMethod("noteMirrorInputPanelStatus", IBinder.class);
    }

    private void registerPanelToken() {
        if (mNotePanelStatus != null
                && mDisplayManager != null
                && mPanelToken != null) {
            try {
                mNotePanelStatus.invoke(mDisplayManager, mPanelToken);
            } catch (ReflectiveOperationException | RuntimeException error) {
                System.err.println(
                        "MAGICDESK_INPUT_ROUTING_PANEL registration="
                                + error);
            }
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

    private void setMouseInputSourceOverride(final boolean enabled)
            throws IOException, InterruptedException {
        final Process process = new ProcessBuilder(
                DUMPSYS, "display", "dmctrl", "inputSource",
                enabled ? "mouse" : "none")
                .redirectErrorStream(true)
                .start();
        final BoundedProcessRunner.Result result = BoundedProcessRunner.run(
                process,
                DISPLAY_COMMAND_TIMEOUT_MILLIS,
                DISPLAY_COMMAND_OUTPUT_LIMIT_BYTES);
        if (result.exitCode != 0 || result.truncated) {
            throw new IOException(
                    "display mirror input source failed "
                            + result.exitCode + ": " + result.output);
        }
        mMouseInputSourceOverride = enabled;
    }

    private static void addRequestedPort(
            final Set<String> ports,
            final String location) {
        if (location != null && !location.isEmpty()) {
            ports.add(location);
        }
    }

    private static RoutingTarget findRoutingTarget(
            final Context context,
            final int displayId)
            throws Exception {
        final Object displayManager = getService(
                "display", "android.hardware.display.IDisplayManager");
        final Class<?> displayManagerInterface =
                Class.forName("android.hardware.display.IDisplayManager");
        if (displayId == PlatformDrivers.current().projection()
                .activeDesktopDisplayId(context)) {
            final int physicalPort = findExternalDisplayPort(
                    displayManager, displayManagerInterface);
            if (physicalPort >= 0) {
                return RoutingTarget.platformConsole(physicalPort);
            }
        }
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

    private static int findExternalDisplayPort(
            final Object displayManager,
            final Class<?> displayManagerInterface) throws Exception {
        final Method getDisplayIds = displayManagerInterface.getMethod(
                "getDisplayIds", boolean.class);
        final Method getDisplayInfo = displayManagerInterface.getMethod(
                "getDisplayInfo", int.class);
        final int[] displayIds = (int[]) getDisplayIds.invoke(
                displayManager, true);
        for (final int candidateDisplayId : displayIds) {
            final Object info = getDisplayInfo.invoke(
                    displayManager, candidateDisplayId);
            if (info == null
                    || getIntField(info, "type")
                            != DISPLAY_TYPE_EXTERNAL) {
                continue;
            }
            final Object address = getField(info, "address");
            if (address == null) {
                continue;
            }
            try {
                final Object port = address.getClass()
                        .getMethod("getPort").invoke(address);
                if (port instanceof Number) {
                    return ((Number) port).intValue();
                }
            } catch (ReflectiveOperationException ignored) {
                // Wireless display addresses do not expose a physical port.
            }
        }
        return -1;
    }

    @Override
    public synchronized void close() {
        if (mClosed) {
            return;
        }
        mClosed = true;

        if (mMouseInputSourceOverride) {
            try {
                setMouseInputSourceOverride(false);
            } catch (Exception error) {
                System.err.println(
                        "MAGICDESK_INPUT_ROUTING_CLEANUP mouseSource="
                                + error);
            }
            mMouseInputSourceOverride = false;
        }
        if (mNotePanelStatus != null && mDisplayManager != null) {
            try {
                mNotePanelStatus.invoke(
                        mDisplayManager, new Object[] {null});
            } catch (ReflectiveOperationException | RuntimeException error) {
                System.err.println(
                        "MAGICDESK_INPUT_ROUTING_CLEANUP panel="
                                + error);
            }
        }
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
        mPanelToken = null;
        mDisplayId = -1;
        mAssociationTarget = null;
        mUsesPlatformConsoleHooks = false;
        mKeyboardAssociationCount = 0;
        mVirtualKeyboardCount = 0;
    }

    private static List<DesktopKeyboardDevice> waitForVirtualKeyboards(
            final int expectedCount)
            throws IOException, InterruptedException {
        final long deadline = SystemClock.uptimeMillis()
                + VIRTUAL_KEYBOARD_TIMEOUT_MILLIS;
        List<DesktopKeyboardDevice> keyboards;
        do {
            keyboards = DesktopInputDeviceDiscovery.findRoutableKeyboards();
            if (countVirtualKeyboards(keyboards) == expectedCount) {
                return keyboards;
            }
            Thread.sleep(VIRTUAL_KEYBOARD_POLL_MILLIS);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new IOException(
                "Expected " + expectedCount
                        + " MagicDesk virtual keyboards in EventHub");
    }

    private static int countVirtualKeyboards(
            final List<DesktopKeyboardDevice> keyboards) {
        int count = 0;
        for (final DesktopKeyboardDevice keyboard : keyboards) {
            if (keyboard.location.startsWith(
                    VIRTUAL_KEYBOARD_LOCATION_PREFIX)) {
                count++;
            }
        }
        return count;
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
        final boolean platformConsole;
        final Object associationTarget;

        private RoutingTarget(
                final boolean physicalPort,
                final boolean platformConsole,
                final Object associationTarget) {
            this.physicalPort = physicalPort;
            this.platformConsole = platformConsole;
            this.associationTarget = associationTarget;
        }

        static RoutingTarget physical(final int displayPort) {
            return new RoutingTarget(
                    true, false, Integer.valueOf(displayPort));
        }

        static RoutingTarget platformConsole(final int displayPort) {
            return new RoutingTarget(
                    true, true, Integer.valueOf(displayPort));
        }

        static RoutingTarget uniqueId(final String displayUniqueId) {
            return new RoutingTarget(false, false, displayUniqueId);
        }
    }
}
