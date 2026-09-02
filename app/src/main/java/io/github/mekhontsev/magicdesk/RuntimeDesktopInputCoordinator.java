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
    private final DesktopInputRelayPolicy mInputRelay;
    private final PlatformPointerDriver mPointer;
    private final PlatformPhoneUiDriver mPhoneUi;
    private final Runnable mHardwareKeyboardChanged;
    private final RuntimeInputCoordinator mInputDevices;
    private final DesktopInputRelaySession mRelaySession;
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
    private int mMouseBridgeSuspendedDisplayId = Display.INVALID_DISPLAY;
    private int mPointerViewportRecoveryDisplayId = Display.INVALID_DISPLAY;
    private int mInputSourceRefreshGeneration;
    private boolean mShowImeOverrideActive;
    private boolean mLastReportedPointerReady;
    private boolean mPointerReleaseExpected;
    private String mPreviousShowImeWithHardKeyboard;
    private int mPhoneImePolicyDisplayId = Display.INVALID_DISPLAY;
    private boolean mDestroyed;

    RuntimeDesktopInputCoordinator(
            final Context context,
            final Handler handler,
            final PlatformFeatures platformFeatures,
            final PlatformPointerDriver pointer,
            final PlatformPhoneUiDriver phoneUi,
            final Runnable hardwareKeyboardChanged) {
        mHandler = handler;
        mInputRelay = platformFeatures.inputRelay;
        mPointer = pointer;
        mPhoneUi = phoneUi;
        mHardwareKeyboardChanged = hardwareKeyboardChanged;
        mInputDevices = new RuntimeInputCoordinator(
                context, handler, this::handleInputStateChanged);
        mRelaySession = new DesktopInputRelaySession(
                context,
                mInputRelay,
                () -> mHandler.post(this::handleRelaySessionStateChanged));
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
        mPointerViewportRecoveryDisplayId = Display.INVALID_DISPLAY;
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

    void onConfigurationChanged() {
        scheduleDeviceRefresh();
        finalizePointerViewportRecovery();
    }

    void onDesktopDisplayRemoved(final int displayId) {
        if (mDestroyed || displayId <= Display.DEFAULT_DISPLAY
                || (displayId != mDesktopDisplayId
                        && displayId != mMouseBridgeSuspendedDisplayId)
                || !supportsAbsolutePointer(displayId)) {
            return;
        }
        mPointerViewportRecoveryDisplayId = displayId;
        finalizePointerViewportRecovery();
    }

    void setDesktopDisplay(
            final int displayId,
            final boolean ownershipChanged) {
        if (mDestroyed) {
            return;
        }
        final int previousDisplayId = mDesktopDisplayId;
        mDesktopDisplayId = displayId;
        if (displayId > Display.DEFAULT_DISPLAY) {
            mPointerViewportRecoveryDisplayId = Display.INVALID_DISPLAY;
        } else if (shouldRecoverPointerViewport(
                previousDisplayId, displayId, ownershipChanged)
                && supportsAbsolutePointer(previousDisplayId)) {
            // A desktop host can close while HDMI or a wireless display stays
            // connected. Complete the same pointer handoff used when a
            // desktop display is physically removed.
            mPointerViewportRecoveryDisplayId = previousDisplayId;
        }
        clearCompletedMouseBridgeSuspension(displayId);
        if (!ownershipChanged) {
            updateExternalImePolicy();
            finalizePointerViewportRecovery();
            return;
        }
        updateShowImeOverride();
        updateExternalImePolicy();
        updateInputBridges();
        if (ownsExternalDesktop()) {
            refreshDesktopInputSources();
        }
        finalizePointerViewportRecovery();
    }

    void reconcileRuntime(final int displayId) {
        if (mDestroyed) {
            return;
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
        final boolean relayRequired = active && requiresMouseRelay();
        final boolean relayReady = active
                && mRelaySession.isPointerReady(displayId);
        final boolean routingReady = active
                && (!requiresInputRouting()
                        || mRelaySession.isRoutingReady(
                                displayId));
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
                mRelaySession.captureMouseDiagnostics(),
                requiresInputRouting()
                        ? mRelaySession.captureKeyboardDiagnostics()
                        : KeyboardShortcutWatcher.captureDiagnostics(),
                pointer);
    }

    boolean suspendMouseBridgeForDisplayRemoval(final int displayId) {
        if (!isActiveDesktopDisplay(displayId)) {
            return false;
        }
        mMouseBridgeSuspendedDisplayId = displayId;
        // Release the physical source while its current display still exists.
        // Waiting for the display callback leaves vendor pointer controllers
        // processing virtual motion against an already removed display.
        updateInputBridges();
        return true;
    }

    void cancelMouseBridgeDisplayRemoval(final int displayId) {
        if (mDestroyed || mMouseBridgeSuspendedDisplayId != displayId) {
            return;
        }
        mMouseBridgeSuspendedDisplayId = Display.INVALID_DISPLAY;
        if (mPointerViewportRecoveryDisplayId == displayId) {
            mPointerViewportRecoveryDisplayId = Display.INVALID_DISPLAY;
        }
        updateInputBridges();
    }

    Point getPointerPosition(final int displayId) {
        return isActiveDesktopDisplay(displayId)
                && supportsAbsolutePointer(displayId)
                ? ShellAccess.getMousePosition(displayId) : null;
    }

    boolean updatePointerPosition(
            final int displayId,
            final int x,
            final int y,
            final int action,
            final long downTime) {
        return isActiveDesktopDisplay(displayId)
                && supportsAbsolutePointer(displayId)
                && ShellAccess.updateMousePosition(
                        displayId, x, y, action, downTime);
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
        final boolean injected = button == MotionEvent.BUTTON_SECONDARY
                && supportsAbsolutePointer(displayId)
                        ? ShellAccess.injectPointerClick(displayId, button)
                        : mRelaySession.clickPointer(button);
        if (injected && button == MotionEvent.BUTTON_PRIMARY) {
            endTextInput(displayId);
            beginTextInput(displayId);
        }
        return injected;
    }

    boolean scrollPointer(final int displayId, final float amount) {
        return isActiveDesktopDisplay(displayId)
                && mRelaySession.isPointerReady(displayId)
                && mRelaySession.scrollPointer(amount);
    }

    boolean updateTextInput(
            final int displayId,
            final int action,
            final String text,
            final int arg1,
            final int arg2,
            final int arg3) {
        if (!isActiveDesktopDisplay(displayId)) {
            return false;
        }
        if (DesktopRuntimeBridge.dispatchOverlayTextInput(
                displayId, action, text, arg1, arg2, arg3)) {
            return true;
        }
        return ShellAccess.updateMirrorTextInput(
                displayId, action, text, arg1, arg2, arg3);
    }

    boolean beginTextInput(final int displayId) {
        return isActiveDesktopDisplay(displayId)
                && (DesktopRuntimeBridge.hasOverlayTextInput(displayId)
                        || ShellAccess.beginMirrorTextInput(displayId));
    }

    void endTextInput(final int displayId) {
        if (isActiveDesktopDisplay(displayId)) {
            ShellAccess.endMirrorTextInput(displayId);
        }
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
        if (requiresInputRouting()) {
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
                !requiresInputRouting());
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
        final boolean mouseShouldRun =
                DesktopInputRelaySession.shouldRunPointerBridge(
                        ShellAccess.isReady(),
                        mDesktopDisplayId,
                        mMouseBridgeSuspendedDisplayId);
        if (mRelaySession.isMouseReady() && !mouseShouldRun) {
            mPointerReleaseExpected = true;
        }
        if (requiresInputRouting()) {
            updateKeyboardWatcher();
        } else {
            mRelaySession.reconcile(
                    ShellAccess.isReady(),
                    mDesktopDisplayId,
                    mHasHardwareKeyboard,
                    mMouseBridgeSuspendedDisplayId);
            updateKeyboardWatcher();
            return;
        }
        mRelaySession.reconcile(
                ShellAccess.isReady(),
                mDesktopDisplayId,
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

    private void finalizePointerViewportRecovery() {
        if (mPointerViewportRecoveryDisplayId <= Display.DEFAULT_DISPLAY
                || ownsExternalDesktop()
                || !supportsAbsolutePointer(
                        mPointerViewportRecoveryDisplayId)) {
            return;
        }
        // The display callback and configuration broadcast have no stable
        // ordering. Complete recovery from the ownership transition itself,
        // after the removed desktop can no longer be selected as a viewport.
        if (ShellAccess.refreshPointerViewport()) {
            Log.i(TAG, "phone pointer viewport finalized after desktop release="
                    + mPointerViewportRecoveryDisplayId);
            mPointerViewportRecoveryDisplayId = Display.INVALID_DISPLAY;
        }
    }

    private void refreshDesktopInputSources() {
        if (mDestroyed || !requiresInputRouting()
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
                    if (mDestroyed || !requiresInputRouting()
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

    private boolean requiresInputRouting() {
        return ownsExternalDesktop()
                && mInputRelay.isRequired();
    }

    private boolean requiresMouseRelay() {
        return ownsExternalDesktop() && mInputRelay.mouse;
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

    private void updateExternalImePolicy() {
        if (!ownsExternalDesktop()
                || !mPhoneUi.requiresPhoneImeRouting()) {
            mPhoneImePolicyDisplayId = Display.INVALID_DISPLAY;
            return;
        }
        if (!ShellAccess.isReady()
                || mPhoneImePolicyDisplayId == mDesktopDisplayId) {
            return;
        }
        try {
            if (!ShellAccess.routeImeToPhone(mDesktopDisplayId)) {
                throw new IOException(
                        "the phone fallback was not applied");
            }
            mPhoneImePolicyDisplayId = mDesktopDisplayId;
            Log.i(TAG, "IME routed to phone for desktop display="
                    + mDesktopDisplayId);
        } catch (IOException error) {
            Log.w(TAG, "could not route the IME to the phone", error);
            CompatibilityDiagnostics.record(
                    "INPUT-IME-003",
                    "Could not keep the on-screen keyboard on the phone",
                    "display=" + mDesktopDisplayId + " "
                            + error.getMessage(),
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

    static boolean shouldRecoverPointerViewport(
            final int previousDisplayId,
            final int displayId,
            final boolean ownershipChanged) {
        return ownershipChanged
                && previousDisplayId > Display.DEFAULT_DISPLAY
                && displayId <= Display.DEFAULT_DISPLAY;
    }
}
