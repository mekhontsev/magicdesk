package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.util.Log;
import android.view.Display;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/** Owns the native input relays and their display-routing lease. */
final class DesktopInputRelaySession {
    private static final String TAG = "MagicDeskInputRelay";
    private static final String KEYBOARD_HELPER =
            "libmagicdesk_keyboard_bridge.so";
    private static final long RESTART_DELAY_MILLIS = 1_000L;

    private final Object mLock = new Object();
    private final Context mContext;
    private final DesktopInputRelayPolicy mPolicy;
    private final Runnable mStateChanged;
    private final DesktopMouseBridge mMouseBridge;
    private final NativeInputBridgeStatsClient mKeyboardStats =
            new NativeInputBridgeStatsClient("MAGICDESK_KEYBOARD_STATS");

    private boolean mRoutingRequested;
    private boolean mRoutingReady;
    private boolean mFullShortcutMode;
    private boolean mHardwareKeyboard;
    private int mRoutingDisplayId = Display.INVALID_DISPLAY;
    private int mGeneration;
    private Thread mSupervisorThread;
    private ShellStreamHandle mKeyboardStream;
    private ShellInputRoutingHandle mInputRouting;

    DesktopInputRelaySession(
            final Context context,
            final DesktopInputRelayPolicy policy,
            final Runnable stateChanged) {
        mContext = context.getApplicationContext();
        mPolicy = policy == null ? DesktopInputRelayPolicy.NONE : policy;
        mStateChanged = stateChanged;
        mMouseBridge = new DesktopMouseBridge(
                mContext, stateChanged);
    }

    void reconcile(
            final boolean shellReady,
            final int displayId,
            final boolean hardwareKeyboard,
            final int suspendedDisplayId) {
        final boolean runMouse = shouldRunMouseBridge(
                shellReady,
                displayId,
                mPolicy.mouse,
                suspendedDisplayId);
        if (runMouse) {
            mMouseBridge.start();
        }

        final boolean runRouting = shouldRunRouting(
                shellReady,
                displayId,
                mPolicy,
                !mPolicy.mouse || mMouseBridge.isReady());
        reconcileRouting(runRouting, displayId, hardwareKeyboard);

        if (!runMouse) {
            mMouseBridge.stop();
        }
    }

    void stop() {
        stopRouting();
        mMouseBridge.stop();
    }

    boolean isMouseReady() {
        return mMouseBridge.isReady();
    }

    boolean isRoutingReady(final int displayId) {
        synchronized (mLock) {
            return mRoutingRequested
                    && mRoutingReady
                    && mRoutingDisplayId == displayId;
        }
    }

    boolean isFullShortcutMode() {
        synchronized (mLock) {
            return mRoutingRequested && mFullShortcutMode;
        }
    }

    void refreshSources(
            final List<DesktopKeyboardDevice> keyboards,
            final List<DesktopMouseDevice> mice) {
        final ShellStreamHandle keyboardStream;
        final ShellInputRoutingHandle inputRouting;
        synchronized (mLock) {
            if (!mRoutingRequested) {
                return;
            }
            keyboardStream = mKeyboardStream;
            inputRouting = mInputRouting;
        }
        if (keyboardStream != null) {
            try {
                keyboardStream.writeLine(buildSourcesCommand(keyboards));
            } catch (IOException error) {
                InputBridgeDiagnostics.noteSourceRefreshFailure(error);
                Log.w(TAG, "Could not refresh keyboard sources", error);
            }
        }
        if (inputRouting != null) {
            try {
                inputRouting.refresh();
            } catch (IOException error) {
                InputBridgeDiagnostics.noteSourceRefreshFailure(error);
                Log.w(TAG, "Could not refresh desktop input routing", error);
            }
        }
        if (mPolicy.mouse) {
            mMouseBridge.refreshSources(mice);
        }
    }

    InputRelayRuntimeDiagnostics.BridgeSnapshot captureMouseDiagnostics() {
        return mMouseBridge.captureDiagnostics();
    }

    InputRelayRuntimeDiagnostics.BridgeSnapshot captureKeyboardDiagnostics() {
        final ShellStreamHandle stream;
        final boolean running;
        final boolean ready;
        final int generation;
        synchronized (mLock) {
            running = mRoutingRequested;
            ready = running && mFullShortcutMode
                    && mKeyboardStream != null;
            stream = ready ? mKeyboardStream : null;
            generation = mGeneration;
        }
        final NativeInputBridgeStatsClient.Result stats = ready
                ? mKeyboardStats.request(stream)
                : new NativeInputBridgeStatsClient.Result(
                        "",
                        running
                                ? "native keyboard relay not active"
                                : "not running");
        synchronized (mLock) {
            final boolean currentReady = mRoutingRequested
                    && mFullShortcutMode
                    && mKeyboardStream == stream;
            return new InputRelayRuntimeDiagnostics.BridgeSnapshot(
                    running,
                    currentReady,
                    currentReady,
                    generation,
                    stats.detail,
                    stats.error);
        }
    }

    void restorePointerPositionIfDisplacedOnNextMotion() {
        mMouseBridge.restorePointerPositionIfDisplacedOnNextMotion();
    }

    boolean movePointer(final float deltaX, final float deltaY) {
        return mMouseBridge.movePointer(deltaX, deltaY);
    }

    boolean clickPointer(final int button) {
        return mMouseBridge.clickPointer(button);
    }

    boolean setPrimaryButtonPressed(final boolean pressed) {
        return mMouseBridge.setPrimaryButtonPressed(pressed);
    }

    boolean scrollPointer(final float amount) {
        return mMouseBridge.scrollPointer(amount);
    }

    private void reconcileRouting(
            final boolean shouldRun,
            final int displayId,
            final boolean hardwareKeyboard) {
        final boolean restart;
        synchronized (mLock) {
            restart = mRoutingRequested
                    && (mRoutingDisplayId != displayId
                            || mHardwareKeyboard != hardwareKeyboard);
            if (!restart && shouldRun == mRoutingRequested) {
                return;
            }
        }
        if (restart || !shouldRun) {
            stopRouting();
        }
        if (shouldRun) {
            startRouting(displayId, hardwareKeyboard);
        }
    }

    private void startRouting(
            final int displayId,
            final boolean hardwareKeyboard) {
        final int generation;
        synchronized (mLock) {
            if (mRoutingRequested) {
                return;
            }
            mRoutingRequested = true;
            mRoutingReady = false;
            mFullShortcutMode = false;
            mRoutingDisplayId = displayId;
            mHardwareKeyboard = hardwareKeyboard;
            generation = ++mGeneration;
            mSupervisorThread = new Thread(
                    () -> runSupervisor(displayId, generation),
                    "MagicDeskInputRelay");
            mSupervisorThread.setDaemon(true);
            mSupervisorThread.start();
        }
    }

    private void stopRouting() {
        final ShellStreamHandle keyboardStream;
        final ShellInputRoutingHandle inputRouting;
        final Thread supervisor;
        final boolean notify;
        synchronized (mLock) {
            if (!mRoutingRequested && mKeyboardStream == null
                    && mInputRouting == null) {
                return;
            }
            mRoutingRequested = false;
            notify = mRoutingReady || mFullShortcutMode;
            mRoutingReady = false;
            mFullShortcutMode = false;
            mRoutingDisplayId = Display.INVALID_DISPLAY;
            mHardwareKeyboard = false;
            ++mGeneration;
            keyboardStream = mKeyboardStream;
            inputRouting = mInputRouting;
            supervisor = mSupervisorThread;
            mKeyboardStream = null;
            mInputRouting = null;
            mSupervisorThread = null;
            mLock.notifyAll();
        }
        // Mouse reports must stop before the display associations vanish.
        mMouseBridge.setCaptureEnabled(false);
        closeQuietly(inputRouting);
        closeQuietly(keyboardStream);
        if (supervisor != null) {
            supervisor.interrupt();
        }
        KeyboardShortcutWatcher.clearModifierState();
        if (notify) {
            mStateChanged.run();
        }
    }

    private void runSupervisor(
            final int displayId,
            final int generation) {
        while (isActive(generation)) {
            InputBridgeDiagnostics.noteAttempt(displayId);
            try {
                ShellAccess.cleanupInputRouting();
                runOnce(displayId, generation);
            } catch (IOException error) {
                if (isActive(generation)) {
                    InputBridgeDiagnostics.noteFailure(error);
                    Log.w(TAG, "Desktop input relay failed", error);
                    CompatibilityDiagnostics.record(
                            "INPUT-BRIDGE-001",
                            "The desktop input relay stopped",
                            "shell=" + ShellAccess.statusLabel()
                                    + " routingDisplay=" + displayId,
                            error);
                }
            }
            if (!isActive(generation)) {
                break;
            }
            try {
                RuntimeDelays.pauseInterruptibly(
                        RuntimeDelays.Reason.SUPERVISOR_BACKOFF,
                        RESTART_DELAY_MILLIS);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        synchronized (mLock) {
            if (mGeneration == generation) {
                mSupervisorThread = null;
            }
        }
        Log.i(TAG, "desktop input relay stopped");
    }

    private void runOnce(
            final int displayId,
            final int generation) throws IOException {
        ShellStreamHandle keyboardStream = null;
        ShellInputRoutingHandle inputRouting = null;
        BufferedReader keyboardReader = null;
        HardwareKeyboardLayoutController.LayoutSink layoutSink = null;
        try {
            final List<DesktopKeyboardDevice> keyboards =
                    mPolicy.keyboard
                            ? DesktopInputDeviceDiscovery.findKeyboards(
                                    FrameworkInputSnapshotSource.readRemote())
                            : java.util.Collections.emptyList();
            final int layoutCount = keyboards.isEmpty()
                    ? 0 : HardwareKeyboardLayoutController
                            .catalogLayoutCount();
            final boolean keyboardRelay = layoutCount > 0;

            if (keyboardRelay) {
                keyboardStream = ShellAccess.openOwnedStream(
                        buildKeyboardCommand(keyboards, layoutCount));
                if (!publishKeyboardStream(keyboardStream, generation)) {
                    return;
                }
                keyboardReader = new BufferedReader(new InputStreamReader(
                        keyboardStream.inputStream()));
                waitForLine(
                        keyboardReader,
                        "MAGICDESK_KEYBOARD_READY",
                        "keyboard bridge");
            }

            inputRouting = ShellAccess.openInputRouting(
                    displayId, layoutCount, mPolicy);
            if (inputRouting.virtualKeyboardCount() != layoutCount) {
                throw new IOException(
                        "virtual keyboard routing count mismatch");
            }
            if (!publishInputRouting(inputRouting, generation)) {
                return;
            }

            if (!keyboardRelay) {
                InputBridgeDiagnostics.noteReady(false);
                Log.i(TAG, "desktop input routing ready shell="
                        + ShellAccess.statusLabel()
                        + " display=" + displayId
                        + " keyboards=0 associations="
                        + inputRouting.associationCount()
                        + " relay=" + mPolicy.diagnosticDetail());
                waitUntilStopped(generation);
                return;
            }

            final ShellStreamHandle activeKeyboardStream = keyboardStream;
            layoutSink = index -> activeKeyboardStream.writeLine(
                    "layout " + index);
            HardwareKeyboardLayoutController.attachLayoutSink(layoutSink);
            syncHardwareKeyboardLayout();
            keyboardStream.writeLine("start");
            waitForLine(
                    keyboardReader,
                    "MAGICDESK_KEYBOARD_STARTED",
                    "keyboard capture");
            setFullShortcutMode(true, generation);
            InputBridgeDiagnostics.noteReady(true);
            Log.i(TAG, "desktop input relay ready shell="
                    + ShellAccess.statusLabel()
                    + " display=" + displayId
                    + " keyboards="
                    + inputRouting.keyboardAssociationCount()
                    + " associations="
                    + inputRouting.associationCount()
                    + " layouts=" + layoutCount);

            String line;
            while (isActive(generation)
                    && (line = keyboardReader.readLine()) != null) {
                handleKeyboardLine(
                        line, activeKeyboardStream, generation);
            }
            if (isActive(generation)) {
                throw new IOException(
                        "Keyboard bridge exited unexpectedly");
            }
        } finally {
            HardwareKeyboardLayoutController.detachLayoutSink(layoutSink);
            clearActiveHandles(
                    keyboardStream, inputRouting, generation);
            closeQuietly(inputRouting);
            closeQuietly(keyboardReader);
            closeQuietly(keyboardStream);
            if (isCurrentGeneration(generation)) {
                KeyboardShortcutWatcher.clearModifierState();
            }
            if (isActive(generation)) {
                Thread.interrupted();
            }
        }
    }

    private void handleKeyboardLine(
            final String line,
            final ShellStreamHandle keyboardStream,
            final int generation) {
        if (line.startsWith("MAGICDESK_KEYBOARD_STATS")) {
            synchronized (mLock) {
                if (isActiveLocked(generation)
                        && mKeyboardStream == keyboardStream) {
                    mKeyboardStats.accept(line);
                }
            }
            return;
        }
        if (line.startsWith("MAGICDESK_ALT_TAB_")
                || line.startsWith("MAGICDESK_SHORTCUT ")) {
            KeyboardShortcutWatcher.handleBridgeLine(
                    line,
                    () -> resumeKeyboard(keyboardStream, generation));
            return;
        }
        if (line.contains("_ERROR")) {
            InputBridgeDiagnostics.noteBridgeAnomaly(line);
            Log.w(TAG, line);
        } else if (!line.isEmpty()) {
            Log.d(TAG, line);
        }
    }

    private void resumeKeyboard(
            final ShellStreamHandle keyboardStream,
            final int generation) {
        if (!isActive(generation)) {
            return;
        }
        try {
            keyboardStream.writeLine("resume");
        } catch (IOException error) {
            Log.w(TAG, "Cannot resume keyboard bridge", error);
            closeQuietly(keyboardStream);
        }
    }

    private boolean publishKeyboardStream(
            final ShellStreamHandle keyboardStream,
            final int generation) {
        synchronized (mLock) {
            if (!isActiveLocked(generation)) {
                return false;
            }
            mKeyboardStream = keyboardStream;
            return true;
        }
    }

    private boolean publishInputRouting(
            final ShellInputRoutingHandle inputRouting,
            final int generation) {
        synchronized (mLock) {
            if (!isActiveLocked(generation)) {
                return false;
            }
            mInputRouting = inputRouting;
            mRoutingReady = true;
        }
        if (mPolicy.mouse) {
            mMouseBridge.setCaptureEnabled(true);
        }
        mStateChanged.run();
        return true;
    }

    private void setFullShortcutMode(
            final boolean enabled,
            final int generation) {
        synchronized (mLock) {
            if (isActiveLocked(generation)) {
                mFullShortcutMode = enabled;
            }
        }
        mStateChanged.run();
    }

    private void clearActiveHandles(
            final ShellStreamHandle keyboardStream,
            final ShellInputRoutingHandle inputRouting,
            final int generation) {
        final boolean notify;
        synchronized (mLock) {
            if (mGeneration != generation) {
                return;
            }
            notify = mRoutingReady || mFullShortcutMode;
            if (mKeyboardStream == keyboardStream) {
                mKeyboardStream = null;
            }
            if (mInputRouting == inputRouting) {
                mInputRouting = null;
            }
            mRoutingReady = false;
            mFullShortcutMode = false;
        }
        // Preserve teardown ordering even after an unexpected helper exit.
        mMouseBridge.setCaptureEnabled(false);
        if (notify) {
            mStateChanged.run();
        }
    }

    private void waitUntilStopped(final int generation) {
        synchronized (mLock) {
            while (isActiveLocked(generation)) {
                try {
                    EventDrivenWaits.await(
                            mLock,
                            EventDrivenWaits.Reason.INPUT_WORKER_STOP);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private boolean isActive(final int generation) {
        synchronized (mLock) {
            return isActiveLocked(generation);
        }
    }

    private boolean isCurrentGeneration(final int generation) {
        synchronized (mLock) {
            return mGeneration == generation;
        }
    }

    private boolean isActiveLocked(final int generation) {
        return mRoutingRequested && mGeneration == generation;
    }

    private String buildKeyboardCommand(
            final List<DesktopKeyboardDevice> keyboards,
            final int layoutCount) throws IOException {
        final File helper = new File(
                mContext.getApplicationInfo().nativeLibraryDir,
                KEYBOARD_HELPER);
        if (!helper.isFile()) {
            throw new IOException(
                    "packaged keyboard bridge is missing: " + helper);
        }
        final StringBuilder command = new StringBuilder("exec ")
                .append(ShellCommandLine.quote(helper.getAbsolutePath()))
                .append(" --layouts ")
                .append(layoutCount);
        for (final DesktopKeyboardDevice keyboard : keyboards) {
            command.append(' ').append(
                    ShellCommandLine.quote(keyboard.path));
        }
        return command.toString();
    }

    private static String buildSourcesCommand(
            final List<DesktopKeyboardDevice> keyboards) {
        final StringBuilder command = new StringBuilder("sources");
        for (final DesktopKeyboardDevice keyboard : keyboards) {
            command.append(' ').append(keyboard.path);
        }
        return command.toString();
    }

    private static String waitForLine(
            final BufferedReader reader,
            final String expectedPrefix,
            final String component) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.startsWith(expectedPrefix)) {
                Log.i(TAG, line);
                return line;
            }
            if (line.contains("_ERROR")) {
                throw new IOException(component + " failed: " + line);
            }
            if (!line.isEmpty()) {
                Log.d(TAG, line);
            }
        }
        throw new IOException(component + " exited before becoming ready");
    }

    private static void syncHardwareKeyboardLayout()
            throws IOException {
        final CountDownLatch complete = new CountDownLatch(1);
        HardwareKeyboardLayoutController.configureVirtualLayouts(
                complete::countDown);
        try {
            complete.await();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException(
                    "interrupted while applying the virtual keyboard layout",
                    error);
        }
    }

    static boolean shouldRunRouting(
            final boolean shellReady,
            final int displayId,
            final DesktopInputRelayPolicy policy,
            final boolean pointerReady) {
        return shellReady
                && policy != null
                && policy.isRequired()
                && displayId > Display.DEFAULT_DISPLAY
                && pointerReady;
    }

    static boolean shouldRunMouseBridge(
            final boolean shellReady,
            final int displayId,
            final boolean mouseRelay,
            final int suspendedDisplayId) {
        return shellReady
                && mouseRelay
                && displayId > Display.DEFAULT_DISPLAY
                && displayId != suspendedDisplayId;
    }

    private static void closeQuietly(final Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // A disconnected helper or Binder owner is already released.
        }
    }
}
