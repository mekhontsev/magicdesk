package io.github.mekhontsev.magicdesk;

import android.os.Binder;
import android.os.IBinder;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class ConsoleInputRoutingSession implements AutoCloseable {
    private static final int DISPLAY_TYPE_EXTERNAL = 2;
    private static final String DUMPSYS = "/system/bin/dumpsys";
    private static final String SETTINGS = "/system/bin/settings";
    private static final String CONSOLE_DISPLAY_SETTING =
            "app_mirror_displayid";

    private final Set<String> mAssociatedInputPorts =
            new LinkedHashSet<>();

    private Object mInputManager;
    private Method mRemovePortAssociation;
    private Object mDisplayManager;
    private Method mNotePanelStatus;
    private Binder mPanelToken;
    private boolean mMouseInputSourceOverride;
    private int mConsoleDisplayId = -1;
    private int mKeyboardAssociationCount;
    private boolean mClosed;

    private ConsoleInputRoutingSession() {
    }

    static ConsoleInputRoutingSession open(
            final List<ConsoleKeyboardDevice> keyboards,
            final List<ConsoleMouseDevice> mice) throws Exception {
        cleanupStaleAssociations();
        final ConsoleInputRoutingSession session =
                new ConsoleInputRoutingSession();
        try {
            session.start(keyboards, mice);
            return session;
        } catch (Exception error) {
            session.close();
            throw error;
        }
    }

    int consoleDisplayId() {
        return mConsoleDisplayId;
    }

    int associationCount() {
        return mAssociatedInputPorts.size();
    }

    int keyboardAssociationCount() {
        return mKeyboardAssociationCount;
    }

    static int cleanupStaleAssociations() throws Exception {
        final String inputDump = readInputDump();
        Set<String> ownedPorts =
                ConsoleInputRoutingOwnership.read();
        if (ownedPorts.isEmpty()) {
            ownedPorts =
                    ConsoleInputRoutingOwnership.findLegacyOwnedPorts(
                            inputDump);
        }
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
        removeAssociations(
                inputManager, removePortAssociation, ownedPorts);

        final Set<String> remaining =
                ConsoleInputRoutingOwnership.findRuntimeAssociations(
                        readInputDump());
        remaining.retainAll(ownedPorts);
        if (!remaining.isEmpty()) {
            throw new IOException(
                    "input associations remain after cleanup: "
                            + remaining);
        }
        ConsoleInputRoutingOwnership.clear();
        return ownedPorts.size();
    }

    private void start(
            final List<ConsoleKeyboardDevice> keyboards,
            final List<ConsoleMouseDevice> mice) throws Exception {
        final int displayPort = findExternalDisplayPort();
        if (displayPort < 0) {
            throw new IllegalStateException(
                    "external physical display port not found");
        }
        mConsoleDisplayId = findConsoleDisplayId();

        mInputManager = getService(
                "input", "android.hardware.input.IInputManager");
        final Class<?> inputManagerInterface =
                Class.forName("android.hardware.input.IInputManager");
        final Method addPortAssociation = inputManagerInterface.getMethod(
                "addPortAssociation", String.class, int.class);
        mRemovePortAssociation = inputManagerInterface.getMethod(
                "removePortAssociation", String.class);

        final Set<String> requestedPorts = new LinkedHashSet<>();
        for (final ConsoleKeyboardDevice keyboard : keyboards) {
            addRequestedPort(requestedPorts, keyboard.location);
        }
        for (final ConsoleMouseDevice mouse : mice) {
            addRequestedPort(requestedPorts, mouse.location);
        }
        ConsoleInputRoutingOwnership.record(requestedPorts);

        int keyboardAssociations = 0;
        for (final ConsoleKeyboardDevice keyboard : keyboards) {
            if (associatePort(
                    addPortAssociation, keyboard.location, displayPort)) {
                keyboardAssociations++;
            }
        }
        if (keyboardAssociations == 0) {
            throw new IllegalStateException(
                    "external alphabetic keyboard input port not found");
        }
        mKeyboardAssociationCount = keyboardAssociations;
        for (final ConsoleMouseDevice mouse : mice) {
            associatePort(addPortAssociation, mouse.location, displayPort);
        }

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

    private boolean associatePort(
            final Method addPortAssociation,
            final String location,
            final int displayPort) throws ReflectiveOperationException {
        if (location == null
                || location.isEmpty()
                || !mAssociatedInputPorts.add(location)) {
            return false;
        }
        try {
            addPortAssociation.invoke(
                    mInputManager, location, displayPort);
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

    private int findExternalDisplayPort() throws Exception {
        final Object displayManager = getService(
                "display", "android.hardware.display.IDisplayManager");
        final Class<?> displayManagerInterface =
                Class.forName("android.hardware.display.IDisplayManager");
        final Method getDisplayIds = displayManagerInterface.getMethod(
                "getDisplayIds", boolean.class);
        final Method getDisplayInfo = displayManagerInterface.getMethod(
                "getDisplayInfo", int.class);
        final int[] displayIds =
                (int[]) getDisplayIds.invoke(displayManager, true);
        for (final int displayId : displayIds) {
            final Object info =
                    getDisplayInfo.invoke(displayManager, displayId);
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
                // Non-physical display addresses do not expose a port.
            }
        }
        return -1;
    }

    private int findConsoleDisplayId()
            throws IOException, InterruptedException {
        final Process process = new ProcessBuilder(
                SETTINGS, "get", "global", CONSOLE_DISPLAY_SETTING)
                .redirectErrorStream(true)
                .start();
        final String value;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            value = reader.readLine();
        }
        final int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException(
                    "failed to read Console display setting: "
                            + exitCode);
        }
        try {
            final int displayId =
                    Integer.parseInt(value == null ? "" : value.trim());
            if (displayId <= 0) {
                throw new NumberFormatException(
                        "display id must be positive");
            }
            return displayId;
        } catch (NumberFormatException error) {
            throw new IOException(
                    "invalid Console display id: " + value, error);
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
        if (mRemovePortAssociation != null && mInputManager != null) {
            try {
                removeAssociations(
                        mInputManager,
                        mRemovePortAssociation,
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
                ConsoleInputRoutingOwnership.clear();
            } catch (IOException error) {
                System.err.println(
                        "MAGICDESK_INPUT_ROUTING_CLEANUP ownership="
                                + error);
            }
        }
        mAssociatedInputPorts.clear();
        mPanelToken = null;
        mConsoleDisplayId = -1;
        mKeyboardAssociationCount = 0;
    }

    private static void removeAssociations(
            final Object inputManager,
            final Method removePortAssociation,
            final Set<String> inputPorts)
            throws ReflectiveOperationException {
        for (final String inputPort : inputPorts) {
            if (ConsoleInputRoutingOwnership.SHIZUKU_KEYBOARD_LOCATION
                    .equals(inputPort)) {
                continue;
            }
            removePortAssociation.invoke(inputManager, inputPort);
        }
        if (inputPorts.contains(
                ConsoleInputRoutingOwnership
                        .SHIZUKU_KEYBOARD_LOCATION)) {
            removePortAssociation.invoke(
                    inputManager,
                    ConsoleInputRoutingOwnership
                            .SHIZUKU_KEYBOARD_LOCATION);
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
}
