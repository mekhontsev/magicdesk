package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.graphics.Point;
import android.os.Handler;
import android.util.Log;
import android.view.Display;
import android.view.MotionEvent;

import java.io.IOException;
import java.util.List;

/** Owns desktop input bridges, routing, and software-keyboard policy. */
final class RuntimeDesktopInputCoordinator {
    private static final String TAG = "MagicDeskInputRuntime";
    private static final String SETTINGS = "/system/bin/settings";
    private static final String SHOW_IME_WITH_HARD_KEYBOARD =
            "show_ime_with_hard_keyboard";

    private final Handler mHandler;
    private final PlatformFeatures mPlatformFeatures;
    private final PlatformPhoneUiDriver mPhoneUi;
    private final Runnable mHardwareKeyboardChanged;
    private final RuntimeInputCoordinator mInputDevices;
    private final DesktopMouseBridge mMouseBridge;

    private boolean mHasHardwareKeyboard;
    private boolean mHasExternalMouse;
    private boolean mKeyboardWatcherRunning;
    private int mKeyboardRoutingDisplayId = Display.INVALID_DISPLAY;
    private int mDesktopDisplayId = Display.INVALID_DISPLAY;
    private int mInputSourceRefreshGeneration;
    private boolean mShowImeOverrideActive;
    private String mPreviousShowImeWithHardKeyboard;
    private int mPhoneImePolicyDisplayId = Display.INVALID_DISPLAY;
    private boolean mDestroyed;

    RuntimeDesktopInputCoordinator(
            final Context context,
            final Handler handler,
            final PlatformFeatures platformFeatures,
            final PlatformPhoneUiDriver phoneUi,
            final Runnable hardwareKeyboardChanged) {
        mHandler = handler;
        mPlatformFeatures = platformFeatures;
        mPhoneUi = phoneUi;
        mHardwareKeyboardChanged = hardwareKeyboardChanged;
        mInputDevices = new RuntimeInputCoordinator(
                context, handler, this::handleInputStateChanged);
        mMouseBridge = new DesktopMouseBridge(context);
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
        mMouseBridge.stop();
        restoreShowImeOverride();
        KeyboardShortcutWatcher.stop();
        mKeyboardWatcherRunning = false;
        mKeyboardRoutingDisplayId = Display.INVALID_DISPLAY;
    }

    boolean hasHardwareKeyboard() {
        return mHasHardwareKeyboard;
    }

    void scheduleDeviceRefresh() {
        mInputDevices.scheduleRefresh();
    }

    void onConsoleModeChanged() {
        ++mInputSourceRefreshGeneration;
    }

    void setDesktopDisplay(
            final int displayId,
            final boolean ownershipChanged) {
        if (mDestroyed) {
            return;
        }
        mDesktopDisplayId = displayId;
        if (!ownershipChanged) {
            updateExternalImePolicy();
            return;
        }
        updateShowImeOverride();
        updateExternalImePolicy();
        updateKeyboardWatcher();
        updateMouseBridge();
        if (ownsExternalDesktop()) {
            refreshDesktopInputSources();
        }
    }

    void reconcileRuntime(final int displayId) {
        if (mDestroyed) {
            return;
        }
        mDesktopDisplayId = displayId;
        updateShowImeOverride();
        updateKeyboardWatcher();
        updateMouseBridge();
    }

    void reconcileSoftwareKeyboardPolicy() {
        if (!mDestroyed) {
            updateShowImeOverride();
        }
    }

    boolean isMouseBridgeReady() {
        return !mDestroyed && mMouseBridge.isReady();
    }

    boolean capturePointerPosition() {
        return isMouseBridgeReady()
                && ShellAccess.capturePointerPosition();
    }

    void restorePointerPositionOnNextMotion() {
        if (!mDestroyed) {
            mMouseBridge.restorePointerPositionIfDisplacedOnNextMotion();
        }
    }

    void reactivatePointerOnNextMotion() {
        if (!mDestroyed) {
            mMouseBridge.reactivatePointerOnNextMotion();
        }
    }

    Point getPointerPosition(final int displayId) {
        return isActiveDesktopDisplay(displayId)
                ? ShellAccess.getMousePosition(displayId) : null;
    }

    boolean updatePointerPosition(
            final int displayId,
            final int x,
            final int y,
            final int action,
            final long downTime) {
        return isActiveDesktopDisplay(displayId)
                && ShellAccess.updateMousePosition(
                        displayId, x, y, action, downTime);
    }

    boolean activatePointer(final int displayId) {
        return isActiveDesktopDisplay(displayId)
                && mMouseBridge.activatePointer();
    }

    boolean clickPointer(final int displayId, final int button) {
        if (!isActiveDesktopDisplay(displayId)) {
            return false;
        }
        final boolean injected = ShellAccess.injectPointerClick(
                displayId, button);
        if (injected && button == MotionEvent.BUTTON_PRIMARY) {
            endTextInput(displayId);
            beginTextInput(displayId);
        }
        return injected;
    }

    boolean scrollPointer(final int displayId, final float amount) {
        return isActiveDesktopDisplay(displayId)
                && mMouseBridge.scrollPointer(amount);
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
        if (requiresExternalInputBridge()) {
            if (mHasHardwareKeyboard
                    && !KeyboardShortcutWatcher.isFullShortcutMode()) {
                restartKeyboardWatcher();
            } else {
                updateKeyboardWatcher();
            }
            updateMouseBridge();
            refreshDesktopInputSources();
            return;
        }
        if (keyboardChanged) {
            updateKeyboardWatcher();
        } else if ((mouseChanged || inputInventoryChanged)
                && mKeyboardWatcherRunning) {
            restartKeyboardWatcher();
        }
        updateMouseBridge();
    }

    private void updateKeyboardWatcher() {
        final int routingDisplayId = routingDisplayId(
                mDesktopDisplayId,
                mPlatformFeatures.externalInputBridge);
        final boolean shouldRun = shouldRunKeyboardWatcher(
                ShellAccess.isReady(),
                mHasHardwareKeyboard,
                routingDisplayId);
        if (mKeyboardWatcherRunning
                && mKeyboardRoutingDisplayId != routingDisplayId) {
            KeyboardShortcutWatcher.stop();
            mKeyboardWatcherRunning = false;
            mKeyboardRoutingDisplayId = Display.INVALID_DISPLAY;
        }
        if (shouldRun == mKeyboardWatcherRunning) {
            return;
        }

        if (shouldRun) {
            Log.i(TAG, "starting keyboard shortcut watcher display="
                    + routingDisplayId);
            KeyboardShortcutWatcher.start(routingDisplayId);
            mKeyboardRoutingDisplayId = routingDisplayId;
        } else {
            Log.i(TAG, "stopping keyboard shortcut watcher");
            KeyboardShortcutWatcher.stop();
            mKeyboardRoutingDisplayId = Display.INVALID_DISPLAY;
        }
        mKeyboardWatcherRunning = shouldRun;
    }

    private void restartKeyboardWatcher() {
        if (mKeyboardWatcherRunning) {
            KeyboardShortcutWatcher.stop();
            mKeyboardWatcherRunning = false;
            mKeyboardRoutingDisplayId = Display.INVALID_DISPLAY;
        }
        updateKeyboardWatcher();
    }

    private void updateMouseBridge() {
        if (shouldRunMouseBridge(
                ShellAccess.isReady(),
                mDesktopDisplayId,
                mPlatformFeatures.externalInputBridge)) {
            mMouseBridge.start();
        } else {
            mMouseBridge.stop();
        }
    }

    private void refreshDesktopInputSources() {
        if (!requiresExternalInputBridge() || !ShellAccess.isReady()) {
            return;
        }
        final int generation = ++mInputSourceRefreshGeneration;
        final Thread refreshThread = new Thread(() -> {
            try {
                final String inputDump = ShellAccess.run(
                        "/system/bin/dumpsys input");
                final List<DesktopKeyboardDevice> keyboards =
                        DesktopInputDeviceDiscovery.findKeyboards(inputDump);
                final List<DesktopMouseDevice> mice =
                        DesktopInputDeviceDiscovery.findMice(inputDump);
                mHandler.post(() -> {
                    if (mDestroyed || !requiresExternalInputBridge()
                            || generation != mInputSourceRefreshGeneration) {
                        return;
                    }
                    KeyboardShortcutWatcher.refreshDesktopInputSources(
                            keyboards);
                    mMouseBridge.refreshSources(mice);
                });
            } catch (IOException error) {
                InputBridgeDiagnostics.noteSourceRefreshFailure(error);
                Log.w(TAG,
                        "Could not refresh desktop input sources", error);
            }
        }, "MagicDeskInputRefresh");
        refreshThread.setDaemon(true);
        refreshThread.start();
    }

    private boolean ownsExternalDesktop() {
        return mDesktopDisplayId > Display.DEFAULT_DISPLAY;
    }

    private boolean requiresExternalInputBridge() {
        return ownsExternalDesktop()
                && mPlatformFeatures.externalInputBridge;
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
                || !mPhoneUi.usesMirrorInputPanel()) {
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

    static int routingDisplayId(
            final int desktopDisplayId,
            final boolean externalInputBridge) {
        return externalInputBridge
                && desktopDisplayId > Display.DEFAULT_DISPLAY
                        ? desktopDisplayId : Display.INVALID_DISPLAY;
    }

    static boolean shouldRunKeyboardWatcher(
            final boolean shellReady,
            final boolean hardwareKeyboard,
            final int routingDisplayId) {
        return shellReady
                && (hardwareKeyboard
                        || routingDisplayId > Display.DEFAULT_DISPLAY);
    }

    static boolean shouldRunMouseBridge(
            final boolean shellReady,
            final int desktopDisplayId,
            final boolean externalInputBridge) {
        return shellReady
                && externalInputBridge
                && desktopDisplayId > Display.DEFAULT_DISPLAY;
    }
}
