package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.os.Handler;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;

import java.io.IOException;

/** Shared display input; Desktop preparation is one explicit acquisition source. */
final class RuntimeDisplayInputCoordinator {
    private static final String TAG = "MagicDeskInputRuntime";
    private static final String SETTINGS = "/system/bin/settings";
    private static final String SHOW_IME_WITH_HARD_KEYBOARD =
            "show_ime_with_hard_keyboard";

    private final Runnable mHardwareKeyboardChanged;
    private final RuntimeInputCoordinator mInputDevices;
    private final DisplayInputSession mInputSession;

    private boolean mHasHardwareKeyboard;
    private boolean mHasExternalMouse;
    private volatile int mInputDisplayId = Display.INVALID_DISPLAY;
    private final DisplayInputTarget mTarget = new DisplayInputTarget();
    private boolean mShowImeOverrideActive;
    private boolean mLastReportedPointerReady;
    private boolean mPointerReleaseExpected;
    private String mPreviousShowImeWithHardKeyboard;
    private boolean mDestroyed;

    RuntimeDisplayInputCoordinator(
            final Context context,
            final Handler handler,
            final Runnable hardwareKeyboardChanged) {
        mHardwareKeyboardChanged = hardwareKeyboardChanged;
        mInputDevices = new RuntimeInputCoordinator(
                context, handler, this::handleInputStateChanged);
        mInputSession = new DisplayInputSession(context, handler, this::handleInputSessionStateChanged);
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
        mInputDevices.stop();
        mInputSession.destroy();
        restoreShowImeOverride();
    }

    boolean hasHardwareKeyboard() {
        return mHasHardwareKeyboard;
    }

    void scheduleDeviceRefresh() {
        mInputDevices.scheduleRefresh();
    }

    void reconcileRuntime() {
        if (mDestroyed) { return; }
        final java.util.Map<Integer, String> workspaces = new java.util.LinkedHashMap<>();
        for (final var state : DesktopRuntimeBridge.getWorkspaces()) {
            if (state.target() == null) { continue; }
            final var workspace = DesktopRuntimeBridge.getWorkspaceRuntime(state.target().workspaceDisplayId);
            if (workspace != null) { workspaces.put(workspace.displayId, workspace.id); }
        }
        mTarget.reconcile(workspaces);
        if (!ShellAccess.isReady()) { mTarget.release(mTarget.requestedDisplay()); }
        updateInputBridges();
        updateShowImeOverride();
    }

    void selectDisplay(final int displayId) {
        if (mDestroyed) { throw new IllegalStateException("input runtime is closed"); }
        mTarget.select(displayId);
        updateInputBridges();
        updateShowImeOverride();
    }

    int requestedDisplay() { return mInputDisplayId; }
    int readyDisplay() { return mInputSession.readyDisplay(); }
    boolean transitioning() { return mInputSession.transitioning(); }
    String error() { return mInputSession.error(); }

    void reconcileSoftwareKeyboardPolicy() {
        if (!mDestroyed) {
            updateShowImeOverride();
        }
    }

    boolean isMouseBridgeReady() {
        return !mDestroyed
                && mInputSession.isPointerReady(mInputDisplayId);
    }

    boolean isFullShortcutMode() {
        return !mDestroyed && DesktopShortcutService.isReady();
    }

    DesktopPointerState pointerState(
            final int displayId,
            final String provider) {
        final boolean active = isInputDisplay(displayId);
        final boolean relayRequired = active && controlsExternalDisplay();
        final boolean relayReady = active
                && mInputSession.isPointerReady(displayId);
        final boolean routingReady = active
                && mInputSession.isRoutingReady(displayId);
        final PointerPosition position = active
                ? ShellAccess.observeMousePosition() : null;
        return new DesktopPointerState(
                displayId,
                provider,
                relayRequired,
                relayReady,
                routingReady,
                position);
    }

    DesktopInputDiagnostics.Snapshot captureDiagnostics(
            final String pointerProvider) {
        if (mDestroyed) {
            return DesktopInputDiagnostics.Snapshot.unavailable();
        }
        final int displayId = mInputDisplayId;
        // Observe the pointer before the report waits for native relay replies.
        final DesktopPointerState pointer = pointerState(
                displayId, pointerProvider);
        return new DesktopInputDiagnostics.Snapshot(
                displayId, mInputSession.captureMouseDiagnostics(),
                DesktopShortcutService.captureDiagnostics(), pointer);
    }

    void onDesktopPrepared(final DesktopWorkspaceRuntime workspace) {
        if (mDestroyed || workspace == null || workspace.isClosed()) { return; }
        mTarget.prepared(workspace.displayId, workspace.id);
        updateInputBridges();
        updateShowImeOverride();
    }

    void releaseForSessionClose(final int displayId, final Runnable completion) {
        if (!mTarget.release(displayId)) {
            completion.run();
            return;
        }
        mInputDisplayId = Display.INVALID_DISPLAY;
        mPointerReleaseExpected = true;
        PhoneTouchpadController.release(displayId);
        updateShowImeOverride();
        mInputSession.stop(completion);
    }

    boolean movePointer(
            final int displayId,
            final float deltaX,
            final float deltaY) {
        return isInputDisplay(displayId)
                && mInputSession.isPointerReady(displayId)
                && mInputSession.movePointer(deltaX, deltaY);
    }

    boolean setPointerButtonPressed(
            final int displayId,
            final int button,
            final boolean pressed) {
        return isInputDisplay(displayId)
                && button == MotionEvent.BUTTON_PRIMARY
                && mInputSession.isPointerReady(displayId)
                && mInputSession.setPrimaryButtonPressed(pressed);
    }

    boolean clickPointer(final int displayId, final int button) {
        if (!isInputDisplay(displayId)) {
            return false;
        }
        if (!mInputSession.isPointerReady(displayId)) {
            return false;
        }
        return mInputSession.clickPointer(button);
    }

    boolean scrollPointer(final int displayId, final float amount) {
        return isInputDisplay(displayId)
                && mInputSession.isPointerReady(displayId)
                && mInputSession.scrollPointer(amount);
    }


    private boolean isInputDisplay(final int displayId) {
        return !mDestroyed && displayId >= Display.DEFAULT_DISPLAY
                && displayId == mInputDisplayId;
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
        refreshInputSources();
    }

    private void handleInputSessionStateChanged() {
        if (!mDestroyed) {
            DesktopAutomationEventJournal.record("input", "routing_changed", mInputSession.error().isEmpty(),
                    "requested=" + mInputDisplayId + " ready=" + mInputSession.readyDisplay());
            final boolean ready = mInputSession.isPointerReady(
                    mInputDisplayId);
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
                            "display=" + mInputDisplayId,
                            new org.json.JSONObject()
                                    .put("displayId", mInputDisplayId)
                                    .put("pointerReady", ready)
                                    .put("expectedRelease", released));
                } catch (org.json.JSONException ignored) {
                    DesktopAutomationEventJournal.record(
                            "input",
                            operation,
                            ready || released,
                            "display=" + mInputDisplayId);
                }
            }
            mHardwareKeyboardChanged.run();
        }
    }

    private void updateInputBridges() {
        final int previous = mInputDisplayId;
        mInputDisplayId = mTarget.requestedDisplay();
        final int target = ShellAccess.isReady() ? mTarget.readyTarget() : Display.INVALID_DISPLAY;
        if (previous != mInputDisplayId) {
            mPointerReleaseExpected = true;
            PhoneTouchpadController.release(previous);
        }
        mInputSession.reconcile(target, target >= 0 && mTarget.desktopShortcuts());
    }

    private void refreshInputSources() {
        if (!mDestroyed && mTarget.readyTarget() >= 0 && ShellAccess.isReady()) {
            mInputSession.refreshDevices();
        }
    }

    private boolean controlsExternalDisplay() {
        return mInputDisplayId > Display.DEFAULT_DISPLAY;
    }

    private void updateShowImeOverride() {
        final boolean shouldBeActive = controlsExternalDisplay()
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
                    "software keyboard enabled for external display");
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

}
