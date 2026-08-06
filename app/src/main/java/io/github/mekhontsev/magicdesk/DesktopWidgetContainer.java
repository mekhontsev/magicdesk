package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.widget.FrameLayout;

/** Preserves widget input while reserving a stationary long press for the host. */
final class DesktopWidgetContainer extends FrameLayout {
    private final GestureDetector mLongPressDetector;
    private boolean mLongPressTriggered;

    DesktopWidgetContainer(final Context context) {
        super(context);
        mLongPressDetector = new GestureDetector(
                context,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(final MotionEvent event) {
                        return true;
                    }

                    @Override
                    public void onLongPress(final MotionEvent event) {
                        mLongPressTriggered = performLongClick();
                    }
                });
    }

    @Override
    public boolean onInterceptTouchEvent(final MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            mLongPressTriggered = false;
        }
        mLongPressDetector.onTouchEvent(event);
        return mLongPressTriggered;
    }

    @Override
    public boolean onTouchEvent(final MotionEvent event) {
        final boolean handled = mLongPressTriggered;
        if (event.getActionMasked() == MotionEvent.ACTION_UP
                || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            mLongPressTriggered = false;
        }
        return handled || super.onTouchEvent(event);
    }
}
