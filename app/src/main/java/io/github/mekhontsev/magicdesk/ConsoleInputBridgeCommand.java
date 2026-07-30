package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.os.IBinder;
import android.view.InputDevice;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class ConsoleInputBridgeCommand {
    private static final String GETEVENT = "/system/bin/getevent";
    private Object mInputManager;
    private Class<?> mInputManagerInterface;
    private Process mGeteventProcess;
    private final List<ConsoleKeyboardDevice> mKeyboardDevices = new ArrayList<>();
    private final List<ConsoleMouseDevice> mMouseDevices = new ArrayList<>();
    private int mConsoleDisplayId = -1;
    private ConsoleInputRoutingSession mInputRouting;
    private ConsoleInputEventInjector mInputEventInjector;
    private ConsoleKeyboardTabController mKeyboardTabController;
    private ConsoleRightButtonTranslator mRightButtonTranslator;
    private final Set<Integer> mDisabledMouseInputDeviceIds = new LinkedHashSet<>();
    private final Set<Integer> mDisabledKeyboardInputDeviceIds = new LinkedHashSet<>();
    private final Set<Integer> mMatchedMouseInputDeviceIds = new LinkedHashSet<>();
    private final Set<Integer> mMatchedKeyboardInputDeviceIds = new LinkedHashSet<>();
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
                    restoreMouseButtonAfterSetupFailure(mouse);
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
                + " associations=" + (mInputRouting == null
                        ? 0 : mInputRouting.associationCount())
                + " panel=" + (mInputRouting != null)
                + " mouseSource=" + (mInputRouting != null)
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
        mKeyboardDevices.addAll(
                ConsoleInputDeviceDiscovery.findKeyboards());
        mMouseDevices.addAll(
                ConsoleInputDeviceDiscovery.findMice());
        mInputRouting = ConsoleInputRoutingSession.open(
                mKeyboardDevices, mMouseDevices);
        mConsoleDisplayId = mInputRouting.consoleDisplayId();
        mInputManager = getService("input", "android.hardware.input.IInputManager");
        mInputManagerInterface = Class.forName(
                "android.hardware.input.IInputManager");
        mInputEventInjector = new ConsoleInputEventInjector(
                mInputManager, mInputManagerInterface, mConsoleDisplayId);
        mKeyboardTabController =
                new ConsoleKeyboardTabController(mInputEventInjector);
    }

    private void stopRightButtonTranslator() {
        final ConsoleRightButtonTranslator translator =
                mRightButtonTranslator;
        mRightButtonTranslator = null;
        if (translator != null) {
            translator.stop();
        }
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
                    || device.getProductId() != mouse.productId
                    || (mouse.inputDeviceId != deviceId
                            && mMatchedMouseInputDeviceIds.contains(
                                    Integer.valueOf(deviceId)))) {
                continue;
            }
            disableInputDevice.invoke(mInputManager, Integer.valueOf(deviceId));
            mDisabledMouseInputDeviceIds.add(Integer.valueOf(deviceId));
            final Method enableInputDevice = mInputManagerInterface.getMethod(
                    "enableInputDevice", int.class);
            enableInputDevice.invoke(mInputManager, Integer.valueOf(deviceId));
            mDisabledMouseInputDeviceIds.remove(Integer.valueOf(deviceId));
            mMatchedMouseInputDeviceIds.add(Integer.valueOf(deviceId));
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
                    || device.getProductId() != keyboard.productId
                    || (keyboard.inputDeviceId != deviceId
                            && mMatchedKeyboardInputDeviceIds.contains(
                                    Integer.valueOf(deviceId)))) {
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
            mMatchedKeyboardInputDeviceIds.add(
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

    private void restoreMouseButtonAfterSetupFailure(
            final ConsoleMouseDevice mouse) {
        if (!mouse.remapped) {
            return;
        }
        try {
            setMouseRightButtonRemapped(mouse, false);
            if (mouse.inputDeviceId >= 0) {
                resetPhysicalMouseInputDevice(mouse);
            }
        } catch (Exception e) {
            System.err.println(
                    "MAGICDESK_MOUSE_REMAP_ROLLBACK_ERROR source="
                            + mouse.path + " error=" + e);
        }
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
        if (mInputRouting != null) {
            mInputRouting.close();
            mInputRouting = null;
        }
        enablePhysicalKeyboardInputDevices();
        enablePhysicalMouseInputDevices();
        mKeyboardDevices.clear();
        mMouseDevices.clear();
        mConsoleDisplayId = -1;
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

}
