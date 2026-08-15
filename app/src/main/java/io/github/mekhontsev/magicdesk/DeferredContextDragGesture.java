package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/** Resolves one pointer gesture into either a deferred context menu or drag. */
final class DeferredContextDragGesture
        implements View.OnTouchListener, View.OnLongClickListener {
    interface Listener {
        boolean onStartDrag(View target, MotionEvent event);

        void onShowContextMenu(View target);

        default void onPointerEvent(final MotionEvent event) {
        }
    }

    private final Listener mListener;
    private final boolean mRequireLongPressForDrag;
    private final boolean mContextMenuEnabled;
    private final int mTouchSlop;
    private float mDownX;
    private float mDownY;
    private boolean mLongPressRecognized;
    private boolean mDragging;
    private boolean mContextMenuPending;

    DeferredContextDragGesture(
            final View target,
            final boolean requireLongPressForDrag,
            final boolean contextMenuEnabled,
            final Listener listener) {
        mListener = listener;
        mRequireLongPressForDrag = requireLongPressForDrag;
        mContextMenuEnabled = contextMenuEnabled;
        mTouchSlop = ViewConfiguration.get(target.getContext())
                .getScaledTouchSlop();
        target.setOnTouchListener(this);
        target.setOnLongClickListener(contextMenuEnabled
                || requireLongPressForDrag ? this : null);
    }

    @Override
    public boolean onLongClick(final View view) {
        mLongPressRecognized = true;
        mContextMenuPending = mContextMenuEnabled;
        return true;
    }

    @Override
    @SuppressLint("ClickableViewAccessibility")
    public boolean onTouch(final View target, final MotionEvent event) {
        mListener.onPointerEvent(event);
        final int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            mDownX = event.getX();
            mDownY = event.getY();
            reset();
        } else if (action == MotionEvent.ACTION_MOVE
                && !mDragging
                && movedPastSlop(event)
                && (!mRequireLongPressForDrag || mLongPressRecognized)) {
            target.cancelLongPress();
            mContextMenuPending = false;
            mDragging = mListener.onStartDrag(target, event);
            return mDragging;
        } else if (action == MotionEvent.ACTION_UP) {
            if (!mDragging && mContextMenuPending) {
                mListener.onShowContextMenu(target);
                reset();
                return true;
            }
            if (mDragging) {
                reset();
                return true;
            }
            reset();
        } else if (action == MotionEvent.ACTION_CANCEL) {
            reset();
        }
        return false;
    }

    private boolean movedPastSlop(final MotionEvent event) {
        return Math.abs(event.getX() - mDownX) > mTouchSlop
                || Math.abs(event.getY() - mDownY) > mTouchSlop;
    }

    private void reset() {
        mLongPressRecognized = false;
        mDragging = false;
        mContextMenuPending = false;
    }
}
