package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.PopupWindow;

/** Android-themed, Activity-attached handles; selection belongs to the terminal. */
final class ConsoleSelectionHandles {
    interface Listener {
        void move(boolean start, float x, float y);
    }

    private final View mHost;
    private final Listener mListener;
    private final Handle mStart;
    private final Handle mEnd;
    private final int[] mLocation = new int[2];

    ConsoleSelectionHandles(final View host, final Listener listener) {
        mHost = host;
        mListener = listener;
        final TypedArray attributes = host.getContext().obtainStyledAttributes(new int[]{
                android.R.attr.textSelectHandleLeft, android.R.attr.textSelectHandleRight});
        try {
            mStart = new Handle(true, attributes.getDrawable(0));
            mEnd = new Handle(false, attributes.getDrawable(1));
        } finally {
            attributes.recycle();
        }
    }

    void update(final float startX, final float startY, final float endX, final float endY) {
        if (!mHost.isAttachedToWindow() || !mHost.hasWindowFocus()) {
            hide();
            return;
        }
        mHost.getLocationInWindow(mLocation);
        mStart.update(startX, startY);
        mEnd.update(endX, endY);
    }

    void hide() {
        mStart.popup.dismiss();
        mEnd.popup.dismiss();
    }

    private final class Handle {
        final PopupWindow popup;
        final boolean start;
        final float hotspot;
        float anchorX, anchorY, touchOffsetX, touchOffsetY;
        int lastX, lastY;
        final int[] screenLocation = new int[2];

        Handle(final boolean start, final Drawable drawable) {
            this.start = start;
            final float density = mHost.getResources().getDisplayMetrics().density;
            final int targetSize = Math.round(48 * density);
            final int drawableWidth = drawable == null ? 0 : drawable.getIntrinsicWidth();
            final int drawableHeight = drawable == null ? 0 : drawable.getIntrinsicHeight();
            // Wide popup hit areas overlap and steal the other endpoint's touches.
            final int width = Math.max(1, drawableWidth);
            final int height = Math.max(targetSize, drawableHeight);
            hotspot = (width - drawableWidth) / 2f + drawableWidth * (start ? 0.75f : 0.25f);
            final ImageView image = new ImageView(mHost.getContext()) {
                @Override public boolean onTouchEvent(final MotionEvent event) {
                    final boolean handled = Handle.this.onTouch(event);
                    if (event.getActionMasked() == MotionEvent.ACTION_UP) performClick();
                    return handled;
                }

                @Override public boolean performClick() {
                    super.performClick();
                    return true;
                }
            };
            image.setImageDrawable(drawable);
            image.setScaleType(ImageView.ScaleType.CENTER);
            image.setPadding(0, 0, 0, height - drawableHeight);
            image.setContentDescription(mHost.getContext().getString(start
                    ? R.string.console_selection_start : R.string.console_selection_end));
            popup = new PopupWindow(mHost.getContext(), null, android.R.attr.textSelectHandleWindowStyle);
            popup.setContentView(image);
            popup.setWidth(width);
            popup.setHeight(height);
            popup.setBackgroundDrawable(null);
            popup.setFocusable(false);
            popup.setOutsideTouchable(false);
            popup.setSplitTouchEnabled(true);
            // Keep the tip on its cell at screen edges instead of shifting the whole handle.
            popup.setClippingEnabled(false);
            popup.setWindowLayoutType(WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL);
            popup.setAnimationStyle(0);
            popup.setEnterTransition(null);
            popup.setExitTransition(null);
        }

        @SuppressLint("RtlHardcoded") // Terminal columns are physical left-to-right coordinates.
        void update(final float x, final float y) {
            anchorX = x;
            anchorY = y;
            if (y < 0 || y > mHost.getHeight()) {
                popup.dismiss();
                return;
            }
            final int windowX = Math.round(mLocation[0] + x - hotspot);
            final int windowY = Math.round(mLocation[1] + y);
            if (!popup.isShowing()) {
                popup.showAtLocation(mHost, Gravity.TOP | Gravity.LEFT, windowX, windowY);
            } else if (lastX != windowX || lastY != windowY) {
                popup.update(windowX, windowY, -1, -1);
            }
            lastX = windowX;
            lastY = windowY;
        }

        boolean onTouch(final MotionEvent event) {
            mHost.getLocationOnScreen(screenLocation);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    touchOffsetX = event.getRawX() - screenLocation[0] - anchorX;
                    touchOffsetY = event.getRawY() - screenLocation[1] - anchorY;
                    return true;
                case MotionEvent.ACTION_MOVE:
                case MotionEvent.ACTION_UP:
                    mListener.move(start, event.getRawX() - screenLocation[0] - touchOffsetX,
                            event.getRawY() - screenLocation[1] - touchOffsetY);
                    return true;
                default:
                    return true;
            }
        }
    }
}
