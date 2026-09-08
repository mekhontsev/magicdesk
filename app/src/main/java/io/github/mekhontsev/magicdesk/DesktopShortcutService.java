package io.github.mekhontsev.magicdesk;

import android.accessibilityservice.AccessibilityService;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.io.IOException;

/** Key-only pre-policy filtering; never requests window content or accessibility events. */
public final class DesktopShortcutService extends AccessibilityService
        implements InputManager.InputDeviceListener {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile DesktopShortcutService sInstance;
    private static volatile int sTargetDisplay = Display.INVALID_DISPLAY;
    private final Map<Integer, KeyboardShortcutStateMachine> mKeyboards = new HashMap<>();
    private volatile long mShortcutCount;
    private volatile Set<Integer> mRoutedKeyboards = Set.of();
    private int mDeviceGeneration;

    static void setTargetDisplay(final int displayId) {
        final boolean changed = sTargetDisplay != displayId;
        sTargetDisplay = displayId;
        final Runnable update = () -> {
            final DesktopShortcutService service = sInstance;
            if (service != null) {
                if (changed) service.clearKeys();
                service.refreshRouting();
            }
        };
        if (Looper.myLooper() == MAIN.getLooper()) update.run();
        else MAIN.post(update);
    }

    static boolean isReady() {
        return sInstance != null && sTargetDisplay >= 0;
    }

    static DesktopInputDiagnostics.BridgeSnapshot captureDiagnostics() {
        final DesktopShortcutService service = sInstance;
        return new DesktopInputDiagnostics.BridgeSnapshot(
                service != null, isReady(), 0,
                "mechanism=accessibility-key-filter, devices="
                        + (service == null ? Set.of() : service.mRoutedKeyboards)
                        + ", shortcuts="
                        + (service == null ? 0 : service.mShortcutCount),
                service == null ? "service not connected" : "");
    }

    @Override
    protected void onServiceConnected() {
        sInstance = this;
        getSystemService(InputManager.class).registerInputDeviceListener(this, MAIN);
        refreshRouting();
    }

    @Override
    protected boolean onKeyEvent(final KeyEvent event) {
        if (sTargetDisplay < 0 || event == null
                || (event.getAction() != KeyEvent.ACTION_DOWN
                        && event.getAction() != KeyEvent.ACTION_UP)) return false;
        final InputDevice device = event.getDevice();
        if (device == null || device.isVirtual() || !device.isExternal()
                || !mRoutedKeyboards.contains(event.getDeviceId())) return false;
        final KeyboardShortcutStateMachine keyboard = mKeyboards.computeIfAbsent(
                event.getDeviceId(), id -> new KeyboardShortcutStateMachine());
        final KeyboardShortcutStateMachine.Result result = keyboard.accept(
                event.getKeyCode(), event.getAction() == KeyEvent.ACTION_DOWN,
                event.getRepeatCount(), event.isCtrlPressed(), event.isAltPressed(),
                event.isShiftPressed(), event.isMetaPressed());
        if (result.action != KeyboardShortcutStateMachine.Action.NONE) {
            mShortcutCount++;
            DesktopShortcutActions.dispatch(result.action);
        }
        return result.consumed;
    }

    @Override public void onAccessibilityEvent(final AccessibilityEvent event) {}
    @Override public void onInterrupt() { clearKeys(); }
    @Override public void onInputDeviceAdded(final int id) { refreshRouting(); }
    @Override public void onInputDeviceChanged(final int id) { refreshRouting(); }

    @Override
    public void onInputDeviceRemoved(final int id) {
        final KeyboardShortcutStateMachine keyboard = mKeyboards.remove(id);
        if (keyboard != null && keyboard.reset()) DesktopOperations.cancelAltTab();
        refreshRouting();
    }

    @Override
    public void onDestroy() {
        getSystemService(InputManager.class).unregisterInputDeviceListener(this);
        if (sInstance == this) sInstance = null;
        clearKeys();
        super.onDestroy();
    }

    private void clearKeys() {
        boolean cancel = false;
        for (final KeyboardShortcutStateMachine keyboard : mKeyboards.values()) {
            cancel |= keyboard.reset();
        }
        mKeyboards.clear();
        if (cancel) DesktopOperations.cancelAltTab();
    }

    private void refreshRouting() {
        final int displayId = sTargetDisplay;
        final int generation = ++mDeviceGeneration;
        if (displayId < 0) {
            mRoutedKeyboards = Set.of();
            return;
        }
        // Device-generation callbacks also arrive after InputReader commits an
        // association. An unconfirmed device remains Android's, not our shortcut source.
        DesktopOperations.executeSerialized(() -> {
            final Set<Integer> ids = new HashSet<>();
            try {
                for (final int id : ShellAccess.routedKeyboardDeviceIds(displayId)) ids.add(id);
            } catch (IOException error) {
                CompatibilityDiagnostics.record("INPUT-SHORTCUTS-001",
                        "Could not observe shortcut keyboard routing", error.getMessage(), error);
            }
            MAIN.post(() -> {
                if (sInstance == this && sTargetDisplay == displayId
                        && mDeviceGeneration == generation) {
                    mRoutedKeyboards = Set.copyOf(ids);
                }
            });
        });
    }
}
