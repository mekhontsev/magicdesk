package io.github.mekhontsev.magicdesk;

import android.os.Binder;
import android.os.IBinder;
import android.os.SystemClock;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class DesktopInputRoutingSession implements AutoCloseable {
    private static final int DISPLAY_TYPE_EXTERNAL = 2;
    private static final long VIRTUAL_KEYBOARD_TIMEOUT_MILLIS = 3_000L;
    private static final long VIRTUAL_KEYBOARD_POLL_MILLIS = 100L;
    private static final String VIRTUAL_KEYBOARD_LOCATION_PREFIX =
            "magicdesk-keyboard-";
    private static final String DUMPSYS = "/system/bin/dumpsys";
    private static final String SETTINGS = "/system/bin/settings";
    private static final String NUBIA_CONSOLE_DISPLAY_SETTING =
            "app_mirror_displayid";

    private final Set<String> mAssociatedInputPorts =
            new LinkedHashSet<>();

    private Object mInputManager;
    private Method mAddAssociation;
    private Method mRemoveAssociation;
    private Object mAssociationTarget;
    private Object mDisplayManager;
    private Method mNotePanelStatus;
    private Binder mPanelToken;
    private boolean mUsesNubiaConsoleHooks;
    private boolean mMouseInputSourceOverride;
    private int mDisplayId = -1;
    private int mKeyboardAssociationCount;
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
                waitForVirtualKeyboards(expectedVirtualKeyboardCount);
        final List<DesktopMouseDevice> mice =
                DesktopInputDeviceDiscovery.findRoutableMice();
        cleanupStaleAssociations();
        final DesktopInputRoutingSession session =
                new DesktopInputRoutingSession();
        try {
            session.start(displayId, keyboards, mice);
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
                        readInputDump());
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
        mUsesNubiaConsoleHooks = target.nubiaConsole;

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

        if (mUsesNubiaConsoleHooks) {
            mDisplayManager = getService(
                    "display", "android.hardware.display.IDisplayManager");
            final Class<?> displayManagerInterface =
                    Class.forName("android.hardware.display.IDisplayManager");
            mNotePanelStatus = displayManagerInterface.getMethod(
                    "noteMirrorInputPanelStatus", IBinder.class);
            mPanelToken = new Binder();
            mNotePanelStatus.invoke(mDisplayManager, mPanelToken);
            setMouseInputSourceOverride(true);
        }
    }

    synchronized int refreshAssociations() throws Exception {
        if (mClosed || mInputManager == null
                || mAddAssociation == null || mAssociationTarget == null) {
            return 0;
        }
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
        final StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() > 0) {
                    output.append('\n');
                }
                output.append(line);
            }
        }
        final int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException(
                    "display mirror input source failed "
                            + exitCode + ": " + output);
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

    private static RoutingTarget findRoutingTarget(final int displayId)
            throws Exception {
        final Object displayManager = getService(
                "display", "android.hardware.display.IDisplayManager");
        final Class<?> displayManagerInterface =
                Class.forName("android.hardware.display.IDisplayManager");
        if (displayId == findNubiaConsoleDisplayId()) {
            final int physicalPort = findExternalDisplayPort(
                    displayManager, displayManagerInterface);
            if (physicalPort >= 0) {
                return RoutingTarget.nubiaConsole(physicalPort);
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

    private static int findNubiaConsoleDisplayId()
            throws IOException, InterruptedException {
        final Process process = new ProcessBuilder(
                SETTINGS, "get", "global", NUBIA_CONSOLE_DISPLAY_SETTING)
                .redirectErrorStream(true)
                .start();
        final String value;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            value = reader.readLine();
        }
        final int exitCode = process.waitFor();
        if (exitCode != 0) {
            return -1;
        }
        try {
            final int displayId =
                    Integer.parseInt(value == null ? "" : value.trim());
            return displayId > 0 ? displayId : -1;
        } catch (NumberFormatException error) {
            return -1;
        }
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
        mUsesNubiaConsoleHooks = false;
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

    private static String readInputDump()
            throws IOException, InterruptedException {
        final Process process = new ProcessBuilder(DUMPSYS, "input")
                .redirectErrorStream(true)
                .start();
        final StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        final int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException(
                    "dumpsys input failed with exit code "
                            + exitCode);
        }
        return output.toString();
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
        final boolean nubiaConsole;
        final Object associationTarget;

        private RoutingTarget(
                final boolean physicalPort,
                final boolean nubiaConsole,
                final Object associationTarget) {
            this.physicalPort = physicalPort;
            this.nubiaConsole = nubiaConsole;
            this.associationTarget = associationTarget;
        }

        static RoutingTarget physical(final int displayPort) {
            return new RoutingTarget(
                    true, false, Integer.valueOf(displayPort));
        }

        static RoutingTarget nubiaConsole(final int displayPort) {
            return new RoutingTarget(
                    true, true, Integer.valueOf(displayPort));
        }

        static RoutingTarget uniqueId(final String displayUniqueId) {
            return new RoutingTarget(false, false, displayUniqueId);
        }
    }
}
