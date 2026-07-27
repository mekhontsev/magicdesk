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

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ConsoleInputBridgeCommand {
    private static final String TAG = "MagicDeskRightButton";
    private static final int DISPLAY_TYPE_EXTERNAL = 2;
    private static final int INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT = 1;
    private static final int INVALID_UID = -1;
    private static final String GETEVENT = "/system/bin/getevent";
    private static final String DUMPSYS = "/system/bin/dumpsys";
    private static final String SETTINGS = "/system/bin/settings";
    private static final String CONSOLE_DISPLAY_SETTING = "app_mirror_displayid";
    private static final Pattern EVENT_HUB_DEVICE =
            Pattern.compile("^\\s*-?\\d+:\\s+.+$");
    private static final Pattern INPUT_IDENTIFIER = Pattern.compile(
            ".*vendor=0x([0-9a-fA-F]+), product=0x([0-9a-fA-F]+).*");

    private final Set<String> mAssociatedInputPorts = new LinkedHashSet<>();
    private final RawMouseButtonWatcher mRawMouseButtonWatcher =
            new RawMouseButtonWatcher();
    private Object mDisplayManager;
    private Object mInputManager;
    private Class<?> mInputManagerInterface;
    private Method mNotePanelStatus;
    private Method mRemovePortAssociation;
    private Binder mPanelToken;
    private Process mGeteventProcess;
    private final List<MouseDevice> mMouseDevices = new ArrayList<>();
    private int mConsoleDisplayId = -1;
    private boolean mMouseInputSourceOverride;
    private Binder mRightButtonMonitorToken;
    private HandlerThread mRightButtonThread;
    private Handler mRightButtonHandler;
    private InputMonitor mRightButtonMonitor;
    private RightButtonInputReceiver mRightButtonReceiver;
    private Method mInjectInputEvent;
    private final Set<Integer> mDisabledMouseInputDeviceIds = new LinkedHashSet<>();
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

        mGeteventProcess = new ProcessBuilder(GETEVENT, "-lt")
                .redirectErrorStream(true)
                .start();
        if (consoleMode) {
            int activeMouseCount = 0;
            for (final MouseDevice mouse : mMouseDevices) {
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
                    startRightButtonTranslator();
                    for (final MouseDevice mouse : mMouseDevices) {
                        if (mouse.inputDeviceId >= 0) {
                            startRawMouseButtonWatcher(mouse);
                        }
                    }
                } catch (Exception e) {
                    System.err.println("MAGICDESK_RIGHT_BUTTON_UNAVAILABLE " + e);
                    e.printStackTrace(System.err);
                    stopRawMouseButtonWatchers();
                    stopRightButtonTranslator();
                }
            }
        }
        final Thread stopThread = new Thread(this::waitForStopRequest,
                "MagicDeskInputBridgeStop");
        stopThread.setDaemon(true);
        stopThread.start();

        System.out.println("MAGICDESK_INPUT_BRIDGE_READY console=" + consoleMode
                + " associations=" + mAssociatedInputPorts.size()
                + " panel=" + (mPanelToken != null)
                + " mouseSource=" + mMouseInputSourceOverride
                + " mouseRemap=" + countRemappedMice()
                + " rightButton=" + (mRightButtonReceiver != null));
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

    private void configureConsoleInput() throws Exception {
        final int displayPort = findExternalDisplayPort();
        if (displayPort < 0) {
            throw new IllegalStateException("external physical display port not found");
        }
        mConsoleDisplayId = findConsoleDisplayId();

        mInputManager = getService("input", "android.hardware.input.IInputManager");
        mInputManagerInterface = Class.forName(
                "android.hardware.input.IInputManager");
        final Method addPortAssociation = mInputManagerInterface.getMethod(
                "addPortAssociation", String.class, int.class);
        mRemovePortAssociation = mInputManagerInterface.getMethod(
                "removePortAssociation", String.class);
        for (final String inputPort : findExternalKeyboardInputPorts()) {
            addPortAssociation.invoke(mInputManager, inputPort, displayPort);
            mAssociatedInputPorts.add(inputPort);
        }
        if (mAssociatedInputPorts.isEmpty()) {
            throw new IllegalStateException("external alphabetic keyboard input port not found");
        }
        mMouseDevices.addAll(findExternalMouseDevices());
        for (final MouseDevice mouse : mMouseDevices) {
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

    private void startRightButtonTranslator() throws Exception {
        if (mConsoleDisplayId <= 0 || countActiveMice() == 0) {
            throw new IllegalStateException("right-button input target is unavailable");
        }

        mInjectInputEvent = findInjectInputEventMethod();
        final Method monitorGestureInput = mInputManagerInterface.getMethod(
                "monitorGestureInput", IBinder.class, String.class, int.class);
        final Method setActionButton = MotionEvent.class.getMethod(
                "setActionButton", int.class);

        final HandlerThread thread = new HandlerThread("MagicDeskRightButton");
        thread.start();
        final Handler handler = new Handler(thread.getLooper());
        final Binder monitorToken = new Binder();
        mRightButtonThread = thread;
        mRightButtonHandler = handler;
        mRightButtonMonitorToken = monitorToken;

        final CountDownLatch ready = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        handler.post(() -> {
            try {
                final InputMonitor monitor = (InputMonitor) monitorGestureInput.invoke(
                        mInputManager, monitorToken, "MagicDesk right button",
                        Integer.valueOf(mConsoleDisplayId));
                final RightButtonInputReceiver receiver = new RightButtonInputReceiver(
                        monitor, mConsoleDisplayId, setActionButton);
                mRightButtonMonitor = monitor;
                mRightButtonReceiver = receiver;
            } catch (Throwable e) {
                failure.set(e);
            } finally {
                ready.countDown();
            }
        });
        if (!ready.await(5, TimeUnit.SECONDS)) {
            throw new IOException("timed out creating right-button input monitor");
        }
        if (failure.get() != null) {
            throw new IOException("failed to create right-button input monitor", failure.get());
        }
    }

    private Method findInjectInputEventMethod() throws NoSuchMethodException {
        try {
            return mInputManagerInterface.getMethod(
                    "injectInputEvent", InputEvent.class, int.class);
        } catch (NoSuchMethodException ignored) {
            try {
                return mInputManagerInterface.getMethod(
                        "injectInputEventToTarget",
                        InputEvent.class, int.class, int.class);
            } catch (NoSuchMethodException ignoredAgain) {
                return mInputManagerInterface.getMethod(
                        "injectInputEvent",
                        InputEvent.class, int.class, int.class);
            }
        }
    }

    private boolean injectSecondaryButtonEvent(final MotionEvent event) throws Exception {
        final Method method = mInjectInputEvent;
        if (method == null) {
            return false;
        }
        final Object result;
        if (method.getParameterTypes().length == 2) {
            result = method.invoke(mInputManager, event,
                    Integer.valueOf(INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT));
        } else {
            result = method.invoke(mInputManager, event,
                    Integer.valueOf(INJECT_INPUT_EVENT_MODE_WAIT_FOR_RESULT),
                    Integer.valueOf(INVALID_UID));
        }
        return !(result instanceof Boolean) || ((Boolean) result).booleanValue();
    }

    private void handleRawMouseButtonEvent(final MouseDevice mouse,
            final boolean pressed) {
        final Handler handler = mRightButtonHandler;
        final RightButtonInputReceiver receiver = mRightButtonReceiver;
        if (handler != null && receiver != null && mouse.inputDeviceId >= 0) {
            handler.post(() -> receiver.setSecondaryButtonPressed(
                    mouse.inputDeviceId, pressed));
        }
    }

    private void startRawMouseButtonWatcher(final MouseDevice mouse)
            throws IOException {
        mRawMouseButtonWatcher.start(
                mouse.path,
                mouse.inputDeviceId,
                pressed -> handleRawMouseButtonEvent(mouse, pressed));
    }

    private void stopRawMouseButtonWatchers() {
        mRawMouseButtonWatcher.stop();
    }

    private void stopRightButtonTranslator() {
        final Handler handler = mRightButtonHandler;
        final HandlerThread thread = mRightButtonThread;
        if (handler != null && thread != null && thread.isAlive()) {
            final CountDownLatch stopped = new CountDownLatch(1);
            if (handler.post(() -> {
                disposeRightButtonTranslator();
                stopped.countDown();
            })) {
                try {
                    stopped.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            thread.quitSafely();
        } else {
            disposeRightButtonTranslator();
        }
        mRightButtonHandler = null;
        mRightButtonThread = null;
        mRightButtonMonitorToken = null;
        mInjectInputEvent = null;
    }

    private void disposeRightButtonTranslator() {
        final RightButtonInputReceiver receiver = mRightButtonReceiver;
        mRightButtonReceiver = null;
        if (receiver != null) {
            receiver.dispose();
        }
        final InputMonitor monitor = mRightButtonMonitor;
        mRightButtonMonitor = null;
        if (monitor != null) {
            monitor.dispose();
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

    private Set<String> findExternalKeyboardInputPorts() throws IOException,
            InterruptedException {
        final Process process = new ProcessBuilder(DUMPSYS, "input")
                .redirectErrorStream(true)
                .start();
        final Set<String> result = new LinkedHashSet<>();
        boolean inEventHub = false;
        String classes = null;
        String location = null;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                final String trimmed = line.trim();
                if ("Event Hub State:".equals(trimmed)) {
                    inEventHub = true;
                    continue;
                }
                if (!inEventHub) {
                    continue;
                }
                if (trimmed.startsWith("Input Reader State")) {
                    addKeyboardInputPort(result, classes, location);
                    break;
                }
                final Matcher deviceHeader = EVENT_HUB_DEVICE.matcher(line);
                if (deviceHeader.matches()) {
                    addKeyboardInputPort(result, classes, location);
                    classes = null;
                    location = null;
                    continue;
                }
                if (trimmed.startsWith("Classes:")) {
                    classes = trimmed.substring("Classes:".length()).trim();
                } else if (trimmed.startsWith("Location:")) {
                    location = trimmed.substring("Location:".length()).trim();
                }
            }
        }
        final int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("dumpsys input failed with exit code " + exitCode);
        }
        return result;
    }

    private void setMouseRightButtonRemapped(final MouseDevice mouse,
            final boolean remapped) throws Exception {
        final File executable = findMouseRemapExecutable();
        final Process process = new ProcessBuilder(executable.getAbsolutePath(),
                mouse.path, remapped ? "unknown" : "right")
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
            throw new IOException("mouse remap failed " + exitCode + ": " + output);
        }
        mouse.remapped = remapped;
        System.out.println(output);
        System.out.flush();
    }

    private List<MouseDevice> findExternalMouseDevices()
            throws IOException, InterruptedException {
        final Process process = new ProcessBuilder(DUMPSYS, "input")
                .redirectErrorStream(true)
                .start();
        final List<MouseDevice> result = new ArrayList<>();
        boolean inEventHub = false;
        String classes = null;
        String path = null;
        String location = null;
        int vendorId = -1;
        int productId = -1;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                final String trimmed = line.trim();
                if ("Event Hub State:".equals(trimmed)) {
                    inEventHub = true;
                    continue;
                }
                if (!inEventHub) {
                    continue;
                }
                if (trimmed.startsWith("Input Reader State")) {
                    addMouseDevice(result, classes, path, location,
                            vendorId, productId);
                    break;
                }
                if (EVENT_HUB_DEVICE.matcher(line).matches()) {
                    addMouseDevice(result, classes, path, location,
                            vendorId, productId);
                    classes = null;
                    path = null;
                    location = null;
                    vendorId = -1;
                    productId = -1;
                    continue;
                }
                if (trimmed.startsWith("Classes:")) {
                    classes = trimmed.substring("Classes:".length()).trim();
                } else if (trimmed.startsWith("Path:")) {
                    path = trimmed.substring("Path:".length()).trim();
                } else if (trimmed.startsWith("Location:")) {
                    location = trimmed.substring("Location:".length()).trim();
                } else if (trimmed.startsWith("Identifier:")) {
                    final Matcher identifier = INPUT_IDENTIFIER.matcher(trimmed);
                    if (identifier.matches()) {
                        vendorId = Integer.parseInt(identifier.group(1), 16);
                        productId = Integer.parseInt(identifier.group(2), 16);
                    }
                }
            }
        }
        final int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IOException("dumpsys input failed with exit code " + exitCode);
        }
        return result;
    }

    private void addMouseDevice(final List<MouseDevice> result, final String classes,
            final String path, final String location, final int vendorId, final int productId) {
        if (classes == null || path == null || location == null
                || vendorId < 0 || productId < 0) {
            return;
        }
        if (!classes.contains("CURSOR") || !classes.contains("EXTERNAL")
                || !path.startsWith("/dev/input/event")
                || location.isEmpty()) {
            return;
        }
        result.add(new MouseDevice(path, location, vendorId, productId));
    }

    private void resetPhysicalMouseInputDevice(final MouseDevice mouse)
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

    private void enablePhysicalMouseInputDevices() {
        if (mDisabledMouseInputDeviceIds.isEmpty()
                || mInputManager == null || mInputManagerInterface == null) {
            return;
        }
        try {
            final Method enableInputDevice = mInputManagerInterface.getMethod(
                    "enableInputDevice", int.class);
            for (final Integer deviceId
                    : new ArrayList<>(mDisabledMouseInputDeviceIds)) {
                try {
                    enableInputDevice.invoke(mInputManager, deviceId);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    System.err.println("MAGICDESK_INPUT_BRIDGE_CLEANUP mouse="
                            + deviceId + " error=" + e);
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            System.err.println("MAGICDESK_INPUT_BRIDGE_CLEANUP mouse=" + e);
        } finally {
            mDisabledMouseInputDeviceIds.clear();
        }
    }

    private int countActiveMice() {
        int count = 0;
        for (final MouseDevice mouse : mMouseDevices) {
            if (mouse.inputDeviceId >= 0) {
                count++;
            }
        }
        return count;
    }

    private int countRemappedMice() {
        int count = 0;
        for (final MouseDevice mouse : mMouseDevices) {
            if (mouse.remapped) {
                count++;
            }
        }
        return count;
    }

    private static File findMouseRemapExecutable() throws IOException {
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
        throw new IOException("packaged mouse remap executable not found");
    }

    private static void addKeyboardInputPort(final Set<String> result,
            final String classes, final String location) {
        if (classes == null || location == null || location.isEmpty()) {
            return;
        }
        if (classes.contains("KEYBOARD")
                && classes.contains("ALPHAKEY")
                && classes.contains("EXTERNAL")) {
            result.add(location);
        }
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
        stopRawMouseButtonWatchers();
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
        stopRawMouseButtonWatchers();
        stopRightButtonTranslator();
        for (final MouseDevice mouse : mMouseDevices) {
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
        enablePhysicalMouseInputDevices();
        mAssociatedInputPorts.clear();
        mMouseDevices.clear();
        mConsoleDisplayId = -1;
        mPanelToken = null;
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

    private final class RightButtonInputReceiver extends InputEventReceiver {
        private final int mDisplayId;
        private final Method mSetActionButton;
        private final Map<Integer, MotionEvent> mPointerTemplates = new HashMap<>();
        private final Set<Integer> mSecondaryButtonArmed = new LinkedHashSet<>();

        RightButtonInputReceiver(final InputMonitor monitor, final int displayId,
                final Method setActionButton) {
            super(monitor.getInputChannel(), mRightButtonThread.getLooper());
            mDisplayId = displayId;
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

        private void updatePointerTemplate(final MotionEvent event) {
            final Integer deviceId = Integer.valueOf(event.getDeviceId());
            final MotionEvent previous = mPointerTemplates.put(
                    deviceId, MotionEvent.obtain(event));
            if (previous != null) {
                previous.recycle();
            }
        }

        void setSecondaryButtonPressed(final int inputDeviceId,
                final boolean pressed) {
            final Integer deviceId = Integer.valueOf(inputDeviceId);
            if (pressed) {
                mSecondaryButtonArmed.add(deviceId);
                return;
            }
            if (!mSecondaryButtonArmed.remove(deviceId)) {
                return;
            }
            final MotionEvent pointerTemplate = mPointerTemplates.get(deviceId);
            if (pointerTemplate == null) {
                Log.w(TAG, "right click ignored before mouse position was observed"
                        + " device=" + inputDeviceId);
                return;
            }

            boolean pointerDown = false;
            final long sequenceDownTime = SystemClock.uptimeMillis();
            try {
                injectButtonAction(pointerTemplate, inputDeviceId,
                        sequenceDownTime, MotionEvent.ACTION_DOWN,
                        MotionEvent.BUTTON_SECONDARY, 0);
                pointerDown = true;
                injectButtonAction(pointerTemplate, inputDeviceId,
                        sequenceDownTime, MotionEvent.ACTION_BUTTON_PRESS,
                        MotionEvent.BUTTON_SECONDARY,
                        MotionEvent.BUTTON_SECONDARY);
                injectButtonAction(pointerTemplate, inputDeviceId,
                        sequenceDownTime, MotionEvent.ACTION_BUTTON_RELEASE, 0,
                        MotionEvent.BUTTON_SECONDARY);
                injectButtonAction(pointerTemplate, inputDeviceId,
                        sequenceDownTime, MotionEvent.ACTION_UP, 0, 0);
                pointerDown = false;
            } catch (Exception e) {
                Log.e(TAG, "secondary click injection failed", e);
                System.err.println("MAGICDESK_RIGHT_BUTTON_ERROR " + e);
                if (pointerDown) {
                    cancelSecondaryClickBestEffort(pointerTemplate,
                            inputDeviceId, sequenceDownTime);
                }
            }
        }

        private void cancelSecondaryClickBestEffort(
                final MotionEvent pointerTemplate, final int inputDeviceId,
                final long sequenceDownTime) {
            try {
                injectButtonAction(pointerTemplate, inputDeviceId,
                        sequenceDownTime, MotionEvent.ACTION_CANCEL, 0, 0);
            } catch (Exception cancelError) {
                Log.w(TAG, "failed to cancel partial secondary click", cancelError);
            }
        }

        private void injectButtonAction(final MotionEvent pointerTemplate,
                final int inputDeviceId, final long sequenceDownTime,
                final int action, final int buttonState, final int actionButton)
                throws Exception {
            final MotionEvent translated = createSecondaryButtonEvent(
                    pointerTemplate, inputDeviceId, sequenceDownTime,
                    action, buttonState, actionButton);
            try {
                if (!injectSecondaryButtonEvent(translated)) {
                    throw new IOException("secondary-button injection was rejected for "
                            + MotionEvent.actionToString(action));
                }
            } finally {
                translated.recycle();
            }
        }

        private MotionEvent createSecondaryButtonEvent(
                final MotionEvent source, final int inputDeviceId,
                final long sequenceDownTime, final int action,
                final int buttonState, final int actionButton)
                throws ReflectiveOperationException {
            final int pointerCount = source.getPointerCount();
            final MotionEvent.PointerProperties[] properties =
                    new MotionEvent.PointerProperties[pointerCount];
            final MotionEvent.PointerCoords[] coordinates =
                    new MotionEvent.PointerCoords[pointerCount];
            for (int i = 0; i < pointerCount; i++) {
                properties[i] = new MotionEvent.PointerProperties();
                coordinates[i] = new MotionEvent.PointerCoords();
                source.getPointerProperties(i, properties[i]);
                source.getPointerCoords(i, coordinates[i]);
            }
            final long eventTime = SystemClock.uptimeMillis();
            final MotionEvent translated = MotionEvent.obtain(
                    sequenceDownTime,
                    eventTime,
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
                mSetActionButton.invoke(translated,
                        Integer.valueOf(actionButton));
            }
            return translated;
        }

        @Override
        public void dispose() {
            for (final MotionEvent pointerTemplate : mPointerTemplates.values()) {
                pointerTemplate.recycle();
            }
            mPointerTemplates.clear();
            mSecondaryButtonArmed.clear();
            super.dispose();
        }
    }

    private boolean isActiveMouseInputDevice(final int inputDeviceId) {
        for (final MouseDevice mouse : mMouseDevices) {
            if (mouse.inputDeviceId == inputDeviceId) {
                return true;
            }
        }
        return false;
    }

    private static final class MouseDevice {
        final String path;
        final String location;
        final int vendorId;
        final int productId;
        int inputDeviceId = -1;
        boolean remapped;

        MouseDevice(final String path, final String location, final int vendorId,
                final int productId) {
            this.path = path;
            this.location = location;
            this.vendorId = vendorId;
            this.productId = productId;
        }
    }
}
