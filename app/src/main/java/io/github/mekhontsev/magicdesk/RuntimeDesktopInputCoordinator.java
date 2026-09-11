package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.os.Handler;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;

import java.io.IOException;

/** Owns desktop routing, virtual pointer, shortcuts, and software-keyboard policy. */
final class RuntimeDesktopInputCoordinator {
    private static final String TAG = "MagicDeskInputRuntime";
    private static final String SETTINGS = "/system/bin/settings";
    private static final String SHOW_IME_WITH_HARD_KEYBOARD =
            "show_ime_with_hard_keyboard";

    private final Runnable mHardwareKeyboardChanged;
    private final RuntimeInputCoordinator mInputDevices;
    private final DesktopInputSession mInputSession;

    private boolean mHasHardwareKeyboard;
    private boolean mHasExternalMouse;
    private int mInputDisplayId = Display.INVALID_DISPLAY;
    private boolean mDesktopPrepared;
    private int mClosingInputDisplayId = Display.INVALID_DISPLAY;
    private boolean mShowImeOverrideActive;
    private boolean mLastReportedPointerReady;
    private boolean mPointerReleaseExpected;
    private String mPreviousShowImeWithHardKeyboard;
    private boolean mDestroyed;

    RuntimeDesktopInputCoordinator(
            final Context context,
            final Handler handler,
            final Runnable hardwareKeyboardChanged) {
        mHardwareKeyboardChanged = hardwareKeyboardChanged;
        mInputDevices = new RuntimeInputCoordinator(
                context, handler, this::handleInputStateChanged);
        mInputSession = new DesktopInputSession(context, handler, this::handleInputSessionStateChanged);
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

    void setInputTarget(
            final int displayId,
            final boolean ownershipChanged) {
        if (mDestroyed) {
            return;
        }
        final int previousDisplayId = mInputDisplayId;
        if (displayId != previousDisplayId) {
            mDesktopPrepared = false;
        }
        mInputDisplayId = displayId;
        clearCompletedInputClose(displayId);
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
        if (displayId != mInputDisplayId || !ShellAccess.isReady()) {
            mDesktopPrepared = false;
        }
        mInputDisplayId = displayId;
        clearCompletedInputClose(displayId);
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
                && mInputSession.isPointerReady(mInputDisplayId);
    }

    boolean isFullShortcutMode() {
        return !mDestroyed && DesktopShortcutService.isReady();
    }

    DesktopPointerState pointerState(
            final int displayId,
            final String provider) {
        final boolean active = isActiveDesktopDisplay(displayId);
        final boolean relayRequired = active && ownsExternalDesktop();
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

    void onDesktopPrepared(final int displayId) {
        if (!isActiveDesktopDisplay(displayId) || mDesktopPrepared
                || mClosingInputDisplayId == displayId) {
            return;
        }
        mDesktopPrepared = true;
        updateInputBridges();
    }

    void releaseForSessionClose(final int displayId, final Runnable completion) {
        if (!isActiveDesktopDisplay(displayId)) {
            completion.run();
            return;
        }
        mClosingInputDisplayId = displayId;
        mDesktopPrepared = false;
        mPointerReleaseExpected = true;
        mInputSession.stop(completion);
    }

    boolean movePointer(
            final int displayId,
            final float deltaX,
            final float deltaY) {
        return isActiveDesktopDisplay(displayId)
                && mInputSession.isPointerReady(displayId)
                && mInputSession.movePointer(deltaX, deltaY);
    }

    boolean setPointerButtonPressed(
            final int displayId,
            final int button,
            final boolean pressed) {
        return isActiveDesktopDisplay(displayId)
                && button == MotionEvent.BUTTON_PRIMARY
                && mInputSession.isPointerReady(displayId)
                && mInputSession.setPrimaryButtonPressed(pressed);
    }

    boolean clickPointer(final int displayId, final int button) {
        if (!isActiveDesktopDisplay(displayId)) {
            return false;
        }
        if (!mInputSession.isPointerReady(displayId)) {
            return false;
        }
        return mInputSession.clickPointer(button);
    }

    boolean scrollPointer(final int displayId, final float amount) {
        return isActiveDesktopDisplay(displayId)
                && mInputSession.isPointerReady(displayId)
                && mInputSession.scrollPointer(amount);
    }


    private boolean isActiveDesktopDisplay(final int displayId) {
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
        refreshDesktopInputSources();
    }

    private void handleInputSessionStateChanged() {
        if (!mDestroyed) {
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
            updateInputBridges();
        }
    }

    private void updateInputBridges() {
        final int inputDisplayId = mDesktopPrepared && ShellAccess.isReady()
                && mClosingInputDisplayId != mInputDisplayId
                ? mInputDisplayId : Display.INVALID_DISPLAY;
        if (mInputSession.isPointerReady(mInputDisplayId) && inputDisplayId < 0) {
            mPointerReleaseExpected = true;
        }
        mInputSession.reconcile(inputDisplayId);
    }

    private void clearCompletedInputClose(
            final int displayId) {
        if (mClosingInputDisplayId != Display.INVALID_DISPLAY
                && mClosingInputDisplayId != displayId) {
            mClosingInputDisplayId = Display.INVALID_DISPLAY;
        }
    }

    private void refreshDesktopInputSources() {
        if (!mDestroyed && mDesktopPrepared && ShellAccess.isReady()
                && mClosingInputDisplayId != mInputDisplayId) {
            mInputSession.refreshDevices();
        }
    }

    private boolean ownsExternalDesktop() {
        return mInputDisplayId > Display.DEFAULT_DISPLAY;
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

}
