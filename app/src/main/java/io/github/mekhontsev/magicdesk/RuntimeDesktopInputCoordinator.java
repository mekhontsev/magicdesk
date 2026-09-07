package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.graphics.Point;
import android.os.Handler;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owns desktop input bridges, routing, and software-keyboard policy. */
final class RuntimeDesktopInputCoordinator {
    private static final String TAG = "MagicDeskInputRuntime";
    private static final String SETTINGS = "/system/bin/settings";
    private static final String SHOW_IME_WITH_HARD_KEYBOARD =
            "show_ime_with_hard_keyboard";

    private final Handler mHandler;
    private final Context mContext;
    private final PlatformFeatures mPlatformFeatures;
    private DesktopInputRelayPolicy mInputRelay = DesktopInputRelayPolicy.NONE;
    private final PlatformPointerDriver mPointer;
    private final Runnable mHardwareKeyboardChanged;
    private final RuntimeInputCoordinator mInputDevices;
    private DesktopInputRelaySession mRelaySession;
    private final ExecutorService mInputSourceWorker =
            Executors.newSingleThreadExecutor(runnable -> {
                final Thread thread = new Thread(
                        runnable, "MagicDeskInputRefresh");
                thread.setDaemon(true);
                return thread;
            });

    private boolean mHasHardwareKeyboard;
    private boolean mHasExternalMouse;
    private boolean mKeyboardWatcherRunning;
    private int mDesktopDisplayId = Display.INVALID_DISPLAY;
    private boolean mDesktopPrepared;
    private int mMouseBridgeSuspendedDisplayId = Display.INVALID_DISPLAY;
    private int mInputSourceRefreshGeneration;
    private boolean mShowImeOverrideActive;
    private boolean mLastReportedPointerReady;
    private boolean mPointerReleaseExpected;
    private String mPreviousShowImeWithHardKeyboard;
    private boolean mDestroyed;

    RuntimeDesktopInputCoordinator(
            final Context context,
            final Handler handler,
            final PlatformFeatures platformFeatures,
            final PlatformPointerDriver pointer,
            final Runnable hardwareKeyboardChanged) {
        mHandler = handler;
        mContext = context.getApplicationContext();
        mPlatformFeatures = platformFeatures;
        mPointer = pointer;
        mHardwareKeyboardChanged = hardwareKeyboardChanged;
        mInputDevices = new RuntimeInputCoordinator(
                context, handler, this::handleInputStateChanged);
        mRelaySession = createRelaySession();
    }

    void start() {
        final RuntimeInputCoordinator.Snapshot inputState =
                mInputDevices.start();
        mHasHardwareKeyboard = inputState.hardwareKeyboard;
        mHasExternalMouse = inputState.externalMouse;
        logInputState();
        Log.i(TAG, "started, hardwareKeyboard=" + mHasHardwareKeyboard
                + " externalMouse=" + mHasExternalMouse);
    }

    void destroy() {
        mDestroyed = true;
        ++mInputSourceRefreshGeneration;
        mInputDevices.stop();
        KeyboardShortcutWatcher.stop();
        mRelaySession.stop();
        mInputSourceWorker.shutdownNow();
        restoreShowImeOverride();
        mKeyboardWatcherRunning = false;
    }

    boolean hasHardwareKeyboard() {
        return mHasHardwareKeyboard;
    }

    void scheduleDeviceRefresh() {
        mInputDevices.scheduleRefresh();
    }

    void setDesktopDisplay(
            final int displayId,
            final boolean ownershipChanged) {
        if (mDestroyed) {
            return;
        }
        final int previousDisplayId = mDesktopDisplayId;
        selectInputPolicyForNewSession(displayId);
        if (displayId != previousDisplayId) {
            mDesktopPrepared = false;
        }
        mDesktopDisplayId = displayId;
        clearCompletedMouseBridgeSuspension(displayId);
        if (!ownershipChanged) {
            return;
        }
        updateShowImeOverride();
        updateInputBridges();
        if (ownsExternalDesktop()) {
            refreshDesktopInputSources();
        }
    }

    void reconcileRuntime(final int displayId) {
        if (mDestroyed) {
            return;
        }
        selectInputPolicyForNewSession(displayId);
        if (displayId != mDesktopDisplayId || !ShellAccess.isReady()) {
            mDesktopPrepared = false;
        }
        mDesktopDisplayId = displayId;
        clearCompletedMouseBridgeSuspension(displayId);
        updateShowImeOverride();
        updateInputBridges();
    }

    void reconcileSoftwareKeyboardPolicy() {
        if (!mDestroyed) {
            updateShowImeOverride();
        }
    }

    boolean isMouseBridgeReady() {
        return !mDestroyed
                && mRelaySession.isPointerReady(mDesktopDisplayId);
    }

    boolean isFullShortcutMode() {
        return !mDestroyed && mRelaySession.isFullShortcutMode();
    }

    DesktopPointerState pointerState(
            final int displayId,
            final String provider) {
        final boolean active = isActiveDesktopDisplay(displayId);
        final boolean relayRequired = active && ownsExternalDesktop();
        final boolean relayReady = active
                && mRelaySession.isPointerReady(displayId);
        final boolean routingReady = active
                && mRelaySession.isRoutingReady(displayId);
        final Point position = active && supportsAbsolutePointer(displayId)
                ? ShellAccess.observeMousePosition(displayId) : null;
        return new DesktopPointerState(
                displayId,
                provider,
                relayRequired,
                relayReady,
                routingReady,
                position);
    }

    InputRelayRuntimeDiagnostics.Snapshot captureDiagnostics(
            final String pointerProvider) {
        if (mDestroyed) {
            return InputRelayRuntimeDiagnostics.Snapshot.unavailable();
        }
        final int displayId = mDesktopDisplayId;
        // Observe the pointer before the report waits for native relay replies.
        final DesktopPointerState pointer = pointerState(
                displayId, pointerProvider);
        return new InputRelayRuntimeDiagnostics.Snapshot(
                displayId,
                ownsExternalDesktop() ? mInputRelay : DesktopInputRelayPolicy.NONE,
                mRelaySession.captureMouseDiagnostics(),
                ownsExternalDesktop() && mInputRelay.keyboard
                        ? mRelaySession.captureKeyboardDiagnostics()
                        : KeyboardShortcutWatcher.captureDiagnostics(),
                pointer);
    }

    void onDesktopPrepared(final int displayId) {
        if (!isActiveDesktopDisplay(displayId) || mDesktopPrepared
                || mMouseBridgeSuspendedDisplayId == displayId) {
            return;
        }
        mDesktopPrepared = true;
        updateInputBridges();
    }

    void releaseForSessionClose(final int displayId) {
        if (!isActiveDesktopDisplay(displayId)) {
            return;
        }
        mMouseBridgeSuspendedDisplayId = displayId;
        mDesktopPrepared = false;
        // Close is one-way. Late preparation or device callbacks cannot
        // reacquire physical input while tasks and displays are torn down.
        updateInputBridges();
    }

    boolean movePointer(
            final int displayId,
            final float deltaX,
            final float deltaY) {
        return isActiveDesktopDisplay(displayId)
                && mRelaySession.isPointerReady(displayId)
                && mRelaySession.movePointer(deltaX, deltaY);
    }

    boolean setPointerButtonPressed(
            final int displayId,
            final int button,
            final boolean pressed) {
        return isActiveDesktopDisplay(displayId)
                && button == MotionEvent.BUTTON_PRIMARY
                && mRelaySession.isPointerReady(displayId)
                && mRelaySession.setPrimaryButtonPressed(pressed);
    }

    boolean clickPointer(final int displayId, final int button) {
        if (!isActiveDesktopDisplay(displayId)) {
            return false;
        }
        if (!mRelaySession.isPointerReady(displayId)) {
            return false;
        }
        return mRelaySession.clickPointer(button);
    }

    boolean scrollPointer(final int displayId, final float amount) {
        return isActiveDesktopDisplay(displayId)
                && mRelaySession.isPointerReady(displayId)
                && mRelaySession.scrollPointer(amount);
    }


    private boolean isActiveDesktopDisplay(final int displayId) {
        return !mDestroyed && displayId == mDesktopDisplayId;
    }

    private void handleInputStateChanged(
            final RuntimeInputCoordinator.Snapshot inputState,
            final boolean keyboardChanged,
            final boolean mouseChanged,
            final boolean inputInventoryChanged) {
        if (!keyboardChanged && !mouseChanged && !inputInventoryChanged) {
            return;
        }
        mHasHardwareKeyboard = inputState.hardwareKeyboard;
        mHasExternalMouse = inputState.externalMouse;
        Log.i(TAG, "hardwareKeyboard=" + mHasHardwareKeyboard
                + " externalMouse=" + mHasExternalMouse
                + " inputInventoryChanged=" + inputInventoryChanged);
        logInputState();
        if (keyboardChanged) {
            mHardwareKeyboardChanged.run();
        }
        updateInputBridges();
        if (relaysPhysicalInput()) {
            refreshDesktopInputSources();
            return;
        }
        if (!keyboardChanged
                && (mouseChanged || inputInventoryChanged)
                && mKeyboardWatcherRunning) {
            restartKeyboardWatcher();
        }
    }

    private void handleRelaySessionStateChanged() {
        if (!mDestroyed) {
            final boolean ready = mRelaySession.isPointerReady(
                    mDesktopDisplayId);
            if (ready != mLastReportedPointerReady) {
                mLastReportedPointerReady = ready;
                final boolean released = !ready && mPointerReleaseExpected;
                mPointerReleaseExpected = false;
                final String operation = ready
                        ? "pointer_ready"
                        : released ? "pointer_released" : "pointer_lost";
                try {
                    DesktopAutomationEventJournal.record(
                            "input",
                            operation,
                            ready || released,
                            "display=" + mDesktopDisplayId,
                            new org.json.JSONObject()
                                    .put("displayId", mDesktopDisplayId)
                                    .put("pointerReady", ready)
                                    .put("expectedRelease", released));
                } catch (org.json.JSONException ignored) {
                    DesktopAutomationEventJournal.record(
                            "input",
                            operation,
                            ready || released,
                            "display=" + mDesktopDisplayId);
                }
            }
            updateInputBridges();
        }
    }

    private void updateKeyboardWatcher() {
        final boolean shouldRun = shouldRunKeyboardWatcher(
                ShellAccess.isReady(),
                mHasHardwareKeyboard,
                relaysPhysicalInput() && mInputRelay.keyboard);
        if (shouldRun == mKeyboardWatcherRunning) {
            return;
        }

        if (shouldRun) {
            Log.i(TAG, "starting passive keyboard shortcut watcher");
            KeyboardShortcutWatcher.start();
        } else {
            Log.i(TAG, "stopping passive keyboard shortcut watcher");
            KeyboardShortcutWatcher.stop();
        }
        mKeyboardWatcherRunning = shouldRun;
    }

    private void restartKeyboardWatcher() {
        if (mKeyboardWatcherRunning) {
            KeyboardShortcutWatcher.stop();
            mKeyboardWatcherRunning = false;
        }
        updateKeyboardWatcher();
    }

    private void updateInputBridges() {
        final int relayDisplayId = mDesktopPrepared
                ? mDesktopDisplayId : Display.INVALID_DISPLAY;
        final boolean mouseShouldRun =
                DesktopInputRelaySession.shouldRunPointerBridge(
                        ShellAccess.isReady(),
                        relayDisplayId,
                        mMouseBridgeSuspendedDisplayId);
        if (mRelaySession.isMouseReady() && !mouseShouldRun) {
            mPointerReleaseExpected = true;
        }
        if (relaysPhysicalInput()) {
            updateKeyboardWatcher();
        } else {
            mRelaySession.reconcile(
                    ShellAccess.isReady(),
                    relayDisplayId,
                    mHasHardwareKeyboard,
                    mMouseBridgeSuspendedDisplayId);
            updateKeyboardWatcher();
            return;
        }
        mRelaySession.reconcile(
                ShellAccess.isReady(),
                relayDisplayId,
                mHasHardwareKeyboard,
                mMouseBridgeSuspendedDisplayId);
    }

    private void clearCompletedMouseBridgeSuspension(
            final int displayId) {
        if (mMouseBridgeSuspendedDisplayId != Display.INVALID_DISPLAY
                && mMouseBridgeSuspendedDisplayId != displayId) {
            mMouseBridgeSuspendedDisplayId = Display.INVALID_DISPLAY;
        }
    }

    private void refreshDesktopInputSources() {
        if (mDestroyed || !relaysPhysicalInput()
                || !ShellAccess.isReady()) {
            return;
        }
        final int generation = ++mInputSourceRefreshGeneration;
        mInputSourceWorker.execute(() -> {
            try {
                final String inputDump =
                        FrameworkInputSnapshotSource.readRemote();
                final List<DesktopKeyboardDevice> keyboards =
                        mInputRelay.keyboard
                                ? DesktopInputDeviceDiscovery.findKeyboards(
                                        inputDump)
                                : java.util.Collections.emptyList();
                final List<DesktopMouseDevice> mice =
                        mInputRelay.mouse
                                ? DesktopInputDeviceDiscovery.findMice(
                                        inputDump)
                                : java.util.Collections.emptyList();
                mHandler.post(() -> {
                    if (mDestroyed || !relaysPhysicalInput()
                            || generation != mInputSourceRefreshGeneration) {
                        return;
                    }
                    mRelaySession.refreshSources(keyboards, mice);
                });
            } catch (IOException error) {
                InputBridgeDiagnostics.noteSourceRefreshFailure(error);
                Log.w(TAG,
                        "Could not refresh desktop input sources", error);
            }
        });
    }

    private boolean ownsExternalDesktop() {
        return mDesktopDisplayId > Display.DEFAULT_DISPLAY;
    }

    private boolean relaysPhysicalInput() {
        return ownsExternalDesktop() && mDesktopPrepared
                && mMouseBridgeSuspendedDisplayId != mDesktopDisplayId
                && mInputRelay.isEnabled();
    }

    private DesktopInputRelaySession createRelaySession() {
        return new DesktopInputRelaySession(
                mContext,
                mInputRelay,
                mPointer.requiresSecondaryClickInjection(),
                () -> mHandler.post(this::handleRelaySessionStateChanged));
    }

    private void selectInputPolicyForNewSession(final int displayId) {
        if (ownsExternalDesktop() || displayId <= Display.DEFAULT_DISPLAY) {
            return;
        }
        final DesktopInputRelayPolicy selected = MagicDeskSettings.load()
                .inputRelayPolicy(mPlatformFeatures);
        if (selected.keyboard == mInputRelay.keyboard
                && selected.mouse == mInputRelay.mouse) {
            return;
        }
        // Preferences are latched at session entry. Never tear down live
        // capture or change device ownership in response to a settings edit.
        ++mInputSourceRefreshGeneration;
        mRelaySession.stop();
        mInputRelay = selected;
        mRelaySession = createRelaySession();
    }

    private boolean supportsAbsolutePointer(final int displayId) {
        return mPointer != null && mPointer.supportsDisplay(displayId);
    }

    private void updateShowImeOverride() {
        final boolean shouldBeActive = ownsExternalDesktop()
                && ShellAccess.isReady();
        if (shouldBeActive == mShowImeOverrideActive) {
            return;
        }
        if (!shouldBeActive) {
            restoreShowImeOverride();
            return;
        }
        try {
            final String previous = ShellAccess.run(
                    SETTINGS + " get secure "
                            + SHOW_IME_WITH_HARD_KEYBOARD).trim();
            ShellAccess.run(
                    SETTINGS + " put secure "
                            + SHOW_IME_WITH_HARD_KEYBOARD + " 1");
            mPreviousShowImeWithHardKeyboard =
                    "0".equals(previous) || "1".equals(previous)
                            ? previous : null;
            mShowImeOverrideActive = true;
            Log.i(TAG,
                    "software keyboard enabled for external desktop");
        } catch (IOException error) {
            Log.w(TAG,
                    "could not enable phone keyboard policy", error);
            CompatibilityDiagnostics.record(
                    "INPUT-IME-001",
                    "Could not enable the on-screen keyboard with hardware input",
                    error.getMessage(),
                    error);
        }
    }

    private void restoreShowImeOverride() {
        if (!mShowImeOverrideActive) {
            return;
        }
        try {
            final String command =
                    mPreviousShowImeWithHardKeyboard == null
                            ? SETTINGS + " delete secure "
                                    + SHOW_IME_WITH_HARD_KEYBOARD
                            : SETTINGS + " put secure "
                                    + SHOW_IME_WITH_HARD_KEYBOARD + " "
                                    + mPreviousShowImeWithHardKeyboard;
            ShellAccess.run(command);
            mShowImeOverrideActive = false;
            mPreviousShowImeWithHardKeyboard = null;
            Log.i(TAG, "software keyboard policy restored");
        } catch (IOException error) {
            Log.w(TAG,
                    "could not restore phone keyboard policy", error);
            CompatibilityDiagnostics.record(
                    "INPUT-IME-002",
                    "Could not restore the on-screen keyboard policy",
                    error.getMessage(),
                    error);
        }
    }


    private void logInputState() {
        mInputDevices.logState(TAG);
    }

    static boolean shouldRunKeyboardWatcher(
            final boolean shellReady,
            final boolean hardwareKeyboard,
            final boolean routingOwnedByRelaySession) {
        return shellReady
                && hardwareKeyboard
                && !routingOwnedByRelaySession;
    }
}
