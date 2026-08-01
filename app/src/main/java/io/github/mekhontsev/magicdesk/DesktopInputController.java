package io.github.mekhontsev.magicdesk;

import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

final class DesktopInputController {
    private final DesktopShellActivity mActivity;

    private boolean mPanelBackDown;
    private boolean mContextButtonDown;
    private boolean mContextButtonTouchSequence;
    private float mLastPointerX;
    private float mLastPointerY;

    DesktopInputController(final DesktopShellActivity activity) {
        mActivity = activity;
    }

    boolean handleTouchEvent(
            final MotionEvent event,
            final boolean useRawCoordinates) {
        if (!mActivity.isDesktopShell() || event == null) {
            return false;
        }
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                && !mActivity.hasVisiblePanel()) {
            mActivity.captureInteractionStackForPanel();
        }
        updateLastPointer(event, useRawCoordinates);
        if (!event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            return false;
        }
        final int action = event.getActionMasked();
        final boolean contextButtonDown = hasContextButtonState(event);

        // A missing ACTION_UP must not consume the next primary click.
        if (mContextButtonTouchSequence && action == MotionEvent.ACTION_DOWN
                && !contextButtonDown) {
            resetContextButtonState();
        }
        if (action == MotionEvent.ACTION_DOWN && contextButtonDown) {
            mContextButtonTouchSequence = true;
            beginContextButtonClick();
            return true;
        }
        if (mContextButtonTouchSequence) {
            if (action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_CANCEL) {
                resetContextButtonState();
            }
            return true;
        }
        return false;
    }

    boolean handleGenericMotionEvent(
            final MotionEvent event,
            final boolean useRawCoordinates) {
        if (!mActivity.isDesktopShell() || event == null
                || !event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            return false;
        }
        updateLastPointer(event, useRawCoordinates);
        final int action = event.getActionMasked();
        final boolean contextButtonState = hasContextButtonState(event);
        final boolean contextPress =
                (action == MotionEvent.ACTION_BUTTON_PRESS
                        && isContextActionButton(event))
                        || (action == MotionEvent.ACTION_DOWN
                                && contextButtonState)
                        || (contextButtonState && !mContextButtonDown);
        if (contextPress) {
            beginContextButtonClick();
            return true;
        }
        if ((action == MotionEvent.ACTION_BUTTON_RELEASE
                && isContextActionButton(event))
                || (action == MotionEvent.ACTION_UP && mContextButtonDown)
                || (mContextButtonDown && !contextButtonState)) {
            if (!mContextButtonTouchSequence) {
                mContextButtonDown = false;
            }
            return true;
        }
        return false;
    }

    boolean handleKeyEvent(final KeyEvent event) {
        final int keyCode = event.getKeyCode();
        if (mActivity.isDesktopShell()
                && (keyCode == KeyEvent.KEYCODE_META_LEFT
                        || keyCode == KeyEvent.KEYCODE_META_RIGHT)) {
            if (event.getAction() == KeyEvent.ACTION_UP
                    && !KeyboardShortcutWatcher.isFullShortcutMode()) {
                mActivity.captureInteractionStackForPanel();
                mActivity.toggleStartMenu();
            }
            return true;
        }
        if (mActivity.isDesktopShell() && keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                mActivity.captureInteractionStackForPanel();
                mActivity.showDesktopContextMenu(
                        mActivity.getResources().getDisplayMetrics().widthPixels / 2f,
                        mActivity.getResources().getDisplayMetrics().heightPixels / 2f);
            }
            return true;
        }
        if ((keyCode == KeyEvent.KEYCODE_BACK
                || keyCode == KeyEvent.KEYCODE_ESCAPE)
                && (mActivity.hasVisiblePanel() || mPanelBackDown)) {
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && mActivity.hasVisiblePanel()) {
                mPanelBackDown = true;
                mActivity.resetAltTabState();
                mActivity.hideAllPanels();
                return true;
            }
            if (event.getAction() == KeyEvent.ACTION_UP && mPanelBackDown) {
                mPanelBackDown = false;
                return true;
            }
        }
        return false;
    }

    private void updateLastPointer(
            final MotionEvent event,
            final boolean useRawCoordinates) {
        mLastPointerX = useRawCoordinates ? event.getRawX() : event.getX();
        mLastPointerY = useRawCoordinates ? event.getRawY() : event.getY();
    }

    private boolean hasContextButtonState(final MotionEvent event) {
        return (event.getButtonState() & MotionEvent.BUTTON_SECONDARY) != 0;
    }

    private boolean isContextActionButton(final MotionEvent event) {
        return event.getActionButton() == MotionEvent.BUTTON_SECONDARY;
    }

    private void beginContextButtonClick() {
        if (mContextButtonDown) {
            return;
        }
        mContextButtonDown = true;
        mActivity.captureInteractionStackForPanel();
        mActivity.handleSecondaryClick(mLastPointerX, mLastPointerY);
    }

    private void resetContextButtonState() {
        mContextButtonTouchSequence = false;
        mContextButtonDown = false;
    }
}
