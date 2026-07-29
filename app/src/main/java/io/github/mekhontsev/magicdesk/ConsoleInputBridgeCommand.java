package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.os.Binder;
import android.os.IBinder;
import android.view.InputDevice;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class ConsoleInputBridgeCommand {
    private static final int DISPLAY_TYPE_EXTERNAL = 2;
    private static final String GETEVENT = "/system/bin/getevent";
    private static final String DUMPSYS = "/system/bin/dumpsys";
    private static final String SETTINGS = "/system/bin/settings";
    private static final String CONSOLE_DISPLAY_SETTING = "app_mirror_displayid";
    private final Set<String> mAssociatedInputPorts = new LinkedHashSet<>();
    private Object mDisplayManager;
    private Object mInputManager;
    private Class<?> mInputManagerInterface;
    private Method mNotePanelStatus;
    private Method mRemovePortAssociation;
    private Binder mPanelToken;
    private Process mGeteventProcess;
    private final List<ConsoleKeyboardDevice> mKeyboardDevices = new ArrayList<>();
    private final List<ConsoleMouseDevice> mMouseDevices = new ArrayList<>();
    private int mConsoleDisplayId = -1;
    private boolean mMouseInputSourceOverride;
    private ConsoleInputEventInjector mInputEventInjector;
    private ConsoleKeyboardTabController mKeyboardTabController;
    private ConsoleRightButtonTranslator mRightButtonTranslator;
    private final Set<Integer> mDisabledMouseInputDeviceIds = new LinkedHashSet<>();
    private final Set<Integer> mDisabledKeyboardInputDeviceIds = new LinkedHashSet<>();
    private boolean mCleanedUp;
    private volatile boolean mStopRequested;

    private ConsoleInputBridgeCommand() {
    }

    public static void main(final String[] args) {
        final boolean consoleMode = args.length == 1 && "console".equals(args[0]);
        final ConsoleInputBridgeCommand bridge = new ConsoleInputBridgeCommand();
        Runtime.getRuntime().addShutdownHook(
                new Thread(bridge::cleanup, "MagicDeskInputBridgeCleanup"));
        try {
            bridge.run(consoleMode);
        } catch (Exception e) {
            System.err.println("MAGICDESK_INPUT_BRIDGE_ERROR " + e);
            e.printStackTrace(System.err);
            System.exit(1);
        } finally {
            bridge.cleanup();
        }
    }

    private void run(final boolean consoleMode) throws Exception {
        if (consoleMode) {
            configureConsoleInput();
        }

        if (consoleMode) {
            for (final ConsoleKeyboardDevice keyboard : mKeyboardDevices) {
                try {
                    setKeyboardTabRemapped(keyboard, true);
                    resetPhysicalKeyboardInputDevice(keyboard);
                    mKeyboardTabController.start(keyboard);
                } catch (Exception e) {
                    System.err.println("MAGICDESK_TAB_REMAP_UNAVAILABLE source="
                            + keyboard.path + " error=" + e);
                    e.printStackTrace(System.err);
                    restoreKeyboardTabAfterSetupFailure(keyboard);
                }
            }
            int activeMouseCount = 0;
            for (final ConsoleMouseDevice mouse : mMouseDevices) {
                try {
                    setMouseRightButtonRemapped(mouse, true);
                    resetPhysicalMouseInputDevice(mouse);
                    activeMouseCount++;
                } catch (Exception e) {
                    System.err.println("MAGICDESK_MOUSE_REMAP_UNAVAILABLE source="
                            + mouse.path + " error=" + e);
                    e.printStackTrace(System.err);
                }
            }
            if (activeMouseCount > 0) {
                try {
                    mRightButtonTranslator = new ConsoleRightButtonTranslator(
                            mInputManager,
                            mInputManagerInterface,
                            mInputEventInjector,
                            mConsoleDisplayId,
                            mMouseDevices);
                    mRightButtonTranslator.start();
                } catch (Exception e) {
                    System.err.println("MAGICDESK_RIGHT_BUTTON_UNAVAILABLE " + e);
                    e.printStackTrace(System.err);
                    stopRightButtonTranslator();
                }
            }
        }
        mGeteventProcess = new ProcessBuilder(GETEVENT, "-lt")
                .redirectErrorStream(true)
                .start();
        final Thread stopThread = new Thread(this::waitForStopRequest,
                "MagicDeskInputBridgeStop");
        stopThread.setDaemon(true);
        stopThread.start();

        System.out.println("MAGICDESK_INPUT_BRIDGE_READY console=" + consoleMode
                + " associations=" + mAssociatedInputPorts.size()
                + " panel=" + (mPanelToken != null)
                + " mouseSource=" + mMouseInputSourceOverride
                + " tabRemap=" + countRemappedKeyboards()
                + " mouseRemap=" + countRemappedMice()
                + " rightButton=" + (mRightButtonTranslator != null
                        && mRightButtonTranslator.isReady()));
        System.out.flush();

        try {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(mGeteventProcess.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(line);
                    System.out.flush();
                }
            }
        } catch (IOException e) {
            if (!mStopRequested) {
                throw e;
            }
        }
        mGeteventProcess.waitFor();
    }

    @SuppressLint("BlockedPrivateApi")
    private void configureConsoleInput() throws Exception {
        final int displayPort = findExternalDisplayPort();
        if (displayPort < 0) {
            throw new IllegalStateException("external physical display port not found");
        }
        mConsoleDisplayId = findConsoleDisplayId();

        mInputManager = getService("input", "android.hardware.input.IInputManager");
        mInputManagerInterface = Class.forName(
                "android.hardware.input.IInputManager");
        mInputEventInjector = new ConsoleInputEventInjector(
                mInputManager, mInputManagerInterface, mConsoleDisplayId);
        mKeyboardTabController =
                new ConsoleKeyboardTabController(mInputEventInjector);
        final Method addPortAssociation = mInputManagerInterface.getMethod(
                "addPortAssociation", String.class, int.class);
        mRemovePortAssociation = mInputManagerInterface.getMethod(
                "removePortAssociation", String.class);
        mKeyboardDevices.addAll(ConsoleInputDeviceDiscovery.findKeyboards());
        for (final ConsoleKeyboardDevice keyboard : mKeyboardDevices) {
            addPortAssociation.invoke(
                    mInputManager, keyboard.location, displayPort);
            mAssociatedInputPorts.add(keyboard.location);
        }
        if (mAssociatedInputPorts.isEmpty()) {
            throw new IllegalStateException("external alphabetic keyboard input port not found");
        }
        mMouseDevices.addAll(ConsoleInputDeviceDiscovery.findMice());
        for (final ConsoleMouseDevice mouse : mMouseDevices) {
            if (mAssociatedInputPorts.add(mouse.location)) {
                addPortAssociation.invoke(mInputManager, mouse.location, displayPort);
            }
        }

        mDisplayManager = getService("display", "android.hardware.display.IDisplayManager");
        final Class<?> displayManagerInterface = Class.forName(
                "android.hardware.display.IDisplayManager");
        mNotePanelStatus = displayManagerInterface.getMethod(
                "noteMirrorInputPanelStatus", IBinder.class);
        mPanelToken = new Binder();
        mNotePanelStatus.invoke(mDisplayManager, mPanelToken);
        setMouseInputSourceOverride(true);
    }

    private void setMouseInputSourceOverride(final boolean enabled)
            throws IOException, InterruptedException {
        final Process process = new ProcessBuilder(DUMPSYS, "display", "dmctrl",
                "inputSource", enabled ? "mouse" : "none")
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
            throw new IOException("display mirror input source failed " + exitCode
                    + ": " + output);
        }
        mMouseInputSourceOverride = enabled;
    }

    private int findConsoleDisplayId() throws IOException, InterruptedException {
        final Process process = new ProcessBuilder(
                SETTINGS, "get", "global", CONSOLE_DISPLAY_SETTING)
                .redirectErrorStream(true)
                .start();
        String value;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            value = reader.readLine();
        }
        final int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("failed to read Console display setting: " + exitCode);
        }
        try {
            final int displayId = Integer.parseInt(value == null ? "" : value.trim());
            if (displayId <= 0) {
                throw new NumberFormatException("display id must be positive");
            }
            return displayId;
        } catch (NumberFormatException e) {
            throw new IOException("invalid Console display id: " + value, e);
        }
    }

    private void stopRightButtonTranslator() {
        final ConsoleRightButtonTranslator translator =
                mRightButtonTranslator;
        mRightButtonTranslator = null;
        if (translator != null) {
            translator.stop();
        }
    }

    private int findExternalDisplayPort() throws Exception {
        final Object displayManager = getService(
                "display", "android.hardware.display.IDisplayManager");
        final Class<?> displayManagerInterface = Class.forName(
                "android.hardware.display.IDisplayManager");
        final Method getDisplayIds = displayManagerInterface.getMethod(
                "getDisplayIds", boolean.class);
        final Method getDisplayInfo = displayManagerInterface.getMethod(
                "getDisplayInfo", int.class);
        final int[] displayIds = (int[]) getDisplayIds.invoke(displayManager, true);
        for (final int displayId : displayIds) {
            final Object info = getDisplayInfo.invoke(displayManager, displayId);
            if (info == null || getIntField(info, "type") != DISPLAY_TYPE_EXTERNAL) {
                continue;
            }
            final Object address = getField(info, "address");
            if (address == null) {
                continue;
            }
            try {
                final Object port = address.getClass().getMethod("getPort").invoke(address);
                if (port instanceof Number) {
                    return ((Number) port).intValue();
                }
            } catch (ReflectiveOperationException ignored) {
                // Non-physical display addresses do not expose a port.
            }
        }
        return -1;
    }

    private void setMouseRightButtonRemapped(final ConsoleMouseDevice mouse,
            final boolean remapped) throws Exception {
        final String output = runInputRemap(
                mouse.path, remapped ? "unknown" : "right");
        mouse.remapped = remapped;
        System.out.println(output);
        System.out.flush();
    }

    private void setKeyboardTabRemapped(
            final ConsoleKeyboardDevice keyboard,
            final boolean remapped) throws Exception {
        if (remapped) {
            keyboard.remapped = true;
        }
        final String output;
        try {
            output = runInputRemap(
                    keyboard.path,
                    remapped ? "tab-filter" : "tab-restore");
        } catch (Exception e) {
            if (!remapped) {
                keyboard.remapped = true;
            }
            throw e;
        }
        keyboard.remapped = remapped;
        System.out.println(output);
        System.out.flush();
    }

    private void restoreKeyboardTabAfterSetupFailure(
            final ConsoleKeyboardDevice keyboard) {
        if (!keyboard.remapped) {
            return;
        }
        try {
            setKeyboardTabRemapped(keyboard, false);
            resetPhysicalKeyboardInputDevice(keyboard);
        } catch (Exception e) {
            System.err.println("MAGICDESK_TAB_REMAP_ROLLBACK_ERROR source="
                    + keyboard.path + " error=" + e);
        }
    }

    private String runInputRemap(
            final String path,
            final String mode) throws Exception {
        final File executable = findInputRemapExecutable();
        final Process process = new ProcessBuilder(executable.getAbsolutePath(),
                path, mode)
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
            throw new IOException("input remap failed " + exitCode
                    + ": " + output);
        }
        return output.toString();
    }

    private void resetPhysicalMouseInputDevice(final ConsoleMouseDevice mouse)
            throws Exception {
        if (mInputManager == null || mInputManagerInterface == null) {
            return;
        }

        final Method getInputDeviceIds = mInputManagerInterface.getMethod("getInputDeviceIds");
        final Method getInputDevice = mInputManagerInterface.getMethod(
                "getInputDevice", int.class);
        final Method disableInputDevice = mInputManagerInterface.getMethod(
                "disableInputDevice", int.class);
        final int[] deviceIds = (int[]) getInputDeviceIds.invoke(mInputManager);
        for (final int deviceId : deviceIds) {
            final InputDevice device = (InputDevice) getInputDevice.invoke(
                    mInputManager, Integer.valueOf(deviceId));
            if (device == null || !device.isExternal()
                    || (device.getSources() & InputDevice.SOURCE_MOUSE)
                    != InputDevice.SOURCE_MOUSE
                    || device.getVendorId() != mouse.vendorId
                    || device.getProductId() != mouse.productId) {
                continue;
            }
            disableInputDevice.invoke(mInputManager, Integer.valueOf(deviceId));
            mDisabledMouseInputDeviceIds.add(Integer.valueOf(deviceId));
            final Method enableInputDevice = mInputManagerInterface.getMethod(
                    "enableInputDevice", int.class);
            enableInputDevice.invoke(mInputManager, Integer.valueOf(deviceId));
            mDisabledMouseInputDeviceIds.remove(Integer.valueOf(deviceId));
            mouse.inputDeviceId = deviceId;
            System.out.println("MAGICDESK_MOUSE_SOURCE_RESET id=" + deviceId);
            System.out.flush();
            return;
        }
        throw new IllegalStateException("physical mouse input device not found: vendor=0x"
                + Integer.toHexString(mouse.vendorId) + " product=0x"
                + Integer.toHexString(mouse.productId));
    }

    private void resetPhysicalKeyboardInputDevice(
            final ConsoleKeyboardDevice keyboard) throws Exception {
        if (mInputManager == null || mInputManagerInterface == null) {
            return;
        }

        final Method getInputDeviceIds =
                mInputManagerInterface.getMethod("getInputDeviceIds");
        final Method getInputDevice = mInputManagerInterface.getMethod(
                "getInputDevice", int.class);
        final Method disableInputDevice = mInputManagerInterface.getMethod(
                "disableInputDevice", int.class);
        final Method enableInputDevice = mInputManagerInterface.getMethod(
                "enableInputDevice", int.class);
        final int[] deviceIds =
                (int[]) getInputDeviceIds.invoke(mInputManager);
        for (final int deviceId : deviceIds) {
            final InputDevice device = (InputDevice) getInputDevice.invoke(
                    mInputManager, Integer.valueOf(deviceId));
            if (device == null || !device.isExternal()
                    || device.getKeyboardType()
                    != InputDevice.KEYBOARD_TYPE_ALPHABETIC
                    || device.getVendorId() != keyboard.vendorId
                    || device.getProductId() != keyboard.productId) {
                continue;
            }
            disableInputDevice.invoke(
                    mInputManager, Integer.valueOf(deviceId));
            mDisabledKeyboardInputDeviceIds.add(
                    Integer.valueOf(deviceId));
            enableInputDevice.invoke(
                    mInputManager, Integer.valueOf(deviceId));
            mDisabledKeyboardInputDeviceIds.remove(
                    Integer.valueOf(deviceId));
            keyboard.inputDeviceId = deviceId;
            System.out.println(
                    "MAGICDESK_KEYBOARD_SOURCE_RESET id=" + deviceId);
            System.out.flush();
            return;
        }
        throw new IllegalStateException(
                "physical keyboard input device not found: vendor=0x"
                        + Integer.toHexString(keyboard.vendorId)
                        + " product=0x"
                        + Integer.toHexString(keyboard.productId));
    }

    private void enablePhysicalKeyboardInputDevices() {
        enablePhysicalInputDevices(
                mDisabledKeyboardInputDeviceIds, "keyboard");
    }

    private void enablePhysicalMouseInputDevices() {
        enablePhysicalInputDevices(
                mDisabledMouseInputDeviceIds, "mouse");
    }

    private void enablePhysicalInputDevices(
            final Set<Integer> disabledDeviceIds,
            final String type) {
        if (disabledDeviceIds.isEmpty()
                || mInputManager == null || mInputManagerInterface == null) {
            return;
        }
        try {
            final Method enableInputDevice = mInputManagerInterface.getMethod(
                    "enableInputDevice", int.class);
            for (final Integer deviceId
                    : new ArrayList<>(disabledDeviceIds)) {
                try {
                    enableInputDevice.invoke(mInputManager, deviceId);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    System.err.println("MAGICDESK_INPUT_BRIDGE_CLEANUP "
                            + type + "=" + deviceId + " error=" + e);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("MAGICDESK_INPUT_BRIDGE_CLEANUP "
                    + type + "=" + e);
        } finally {
            disabledDeviceIds.clear();
        }
    }

    private int countRemappedMice() {
        int count = 0;
        for (final ConsoleMouseDevice mouse : mMouseDevices) {
            if (mouse.remapped) {
                count++;
            }
        }
        return count;
    }

    private int countRemappedKeyboards() {
        int count = 0;
        for (final ConsoleKeyboardDevice keyboard : mKeyboardDevices) {
            if (keyboard.remapped) {
                count++;
            }
        }
        return count;
    }

    private static File findInputRemapExecutable() throws IOException {
        final String classPath = System.getProperty("java.class.path", "");
        for (final String entry : classPath.split(Pattern.quote(File.pathSeparator))) {
            final File apk = new File(entry);
            final File appDirectory = apk.getParentFile();
            if (appDirectory == null) {
                continue;
            }
            final File executable = new File(appDirectory,
                    "lib/arm64/libmagicdesk_mouse_remap.so");
            if (executable.isFile()) {
                return executable;
            }
        }
        throw new IOException("packaged input remap executable not found");
    }

    private void waitForStopRequest() {
        try {
            while (System.in.read() >= 0) {
                // The parent closes stdin to request a graceful shutdown.
            }
        } catch (IOException ignored) {
            // A broken control pipe is also a stop request.
        }
        mStopRequested = true;
        final Process process = mGeteventProcess;
        if (process != null) {
            process.destroy();
        }
        if (mKeyboardTabController != null) {
            mKeyboardTabController.stop();
        }
        stopRightButtonTranslator();
    }

    private synchronized void cleanup() {
        if (mCleanedUp) {
            return;
        }
        mCleanedUp = true;

        final Process process = mGeteventProcess;
        if (process != null) {
            process.destroy();
            mGeteventProcess = null;
        }
        if (mKeyboardTabController != null) {
            mKeyboardTabController.stop();
        }
        stopRightButtonTranslator();
        for (final ConsoleKeyboardDevice keyboard : mKeyboardDevices) {
            if (!keyboard.remapped) {
                continue;
            }
            try {
                setKeyboardTabRemapped(keyboard, false);
                resetPhysicalKeyboardInputDevice(keyboard);
            } catch (Exception e) {
                System.err.println("MAGICDESK_INPUT_BRIDGE_CLEANUP tabRemap="
                        + keyboard.path + " error=" + e);
            }
        }
        for (final ConsoleMouseDevice mouse : mMouseDevices) {
            if (!mouse.remapped) {
                continue;
            }
            try {
                setMouseRightButtonRemapped(mouse, false);
                resetPhysicalMouseInputDevice(mouse);
            } catch (Exception e) {
                System.err.println("MAGICDESK_INPUT_BRIDGE_CLEANUP mouseRemap="
                        + mouse.path + " error=" + e);
            }
        }
        if (mMouseInputSourceOverride) {
            try {
                setMouseInputSourceOverride(false);
            } catch (Exception e) {
                System.err.println("MAGICDESK_INPUT_BRIDGE_CLEANUP mouseSource=" + e);
            }
        }
        if (mNotePanelStatus != null && mDisplayManager != null) {
            try {
                mNotePanelStatus.invoke(mDisplayManager, new Object[] {null});
            } catch (ReflectiveOperationException | RuntimeException e) {
                System.err.println("MAGICDESK_INPUT_BRIDGE_CLEANUP panel=" + e);
            }
        }
        if (mRemovePortAssociation != null && mInputManager != null) {
            for (final String inputPort : mAssociatedInputPorts) {
                try {
                    mRemovePortAssociation.invoke(mInputManager, inputPort);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    System.err.println("MAGICDESK_INPUT_BRIDGE_CLEANUP port="
                            + inputPort + " error=" + e);
                }
            }
        }
        enablePhysicalKeyboardInputDevices();
        enablePhysicalMouseInputDevices();
        mAssociatedInputPorts.clear();
        mKeyboardDevices.clear();
        mMouseDevices.clear();
        mConsoleDisplayId = -1;
        mPanelToken = null;
        mKeyboardTabController = null;
        mInputEventInjector = null;
    }

    private static Object getService(final String name, final String interfaceName)
            throws Exception {
        final Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        final Object binder = serviceManager.getMethod("getService", String.class)
                .invoke(null, name);
        final Class<?> stub = Class.forName(interfaceName + "$Stub");
        return stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
    }

    private static Object getField(final Object target, final String fieldName)
            throws ReflectiveOperationException {
        final Field field = target.getClass().getField(fieldName);
        return field.get(target);
    }

    private static int getIntField(final Object target, final String fieldName)
            throws ReflectiveOperationException {
        final Field field = target.getClass().getField(fieldName);
        return field.getInt(target);
    }

}
