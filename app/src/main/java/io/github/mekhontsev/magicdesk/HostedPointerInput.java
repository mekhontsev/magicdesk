package io.github.mekhontsev.magicdesk;

import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/** Android pointer semantics; output coordinates and buttons are protocol-independent. */
final class HostedPointerInput {
    private static final HostedSurfaceOutput.Button[] BUTTONS = HostedSurfaceOutput.Button.values();
    private final View view;
    private final float slopSquared, horizontalFactor, verticalFactor;
    private final Runnable longPress = this::longPress;
    private final long[] observed = new long[32];
    private final int[] observedDevices = new int[observed.length];
    private int observedCount;
    private HostedSurfaceOutput output;
    private HostedViewport viewport = HostedViewport.EMPTY;
    private float x = .5f, y = .5f, startX, startY, previousX, previousY;
    private float scrollX, scrollY;
    private int buttons, physicalButtons;
    private boolean contact, pendingTap, syntheticButton, scrolling, relative, moved, windowGesture;

    HostedPointerInput(View view) {
        this.view = view;
        ViewConfiguration config = ViewConfiguration.get(view.getContext());
        slopSquared = (float) config.getScaledTouchSlop() * config.getScaledTouchSlop();
        horizontalFactor = Math.max(1, config.getScaledHorizontalScrollFactor());
        verticalFactor = Math.max(1, config.getScaledVerticalScrollFactor());
    }

    void bind(HostedSurfaceOutput next) { release(); output = next; }
    void viewport(HostedViewport next) {
        if (!viewport.equals(next) && !windowGesture) release();
        viewport = next;
    }
    boolean dragging() { return (buttons & MotionEvent.BUTTON_PRIMARY) != 0; }

    /** The host now tracks raw display coordinates until the window gesture releases the button. */
    void windowGesture() { windowGesture = true; view.removeCallbacks(longPress); }

    void release() {
        physicalButtons = 0;
        syntheticButton = false;
        updateButtons();
        forget();
    }

    /** Android drag-and-drop now owns the pressed button and its eventual release. */
    void handoff() {
        forget();
        buttons = physicalButtons = 0;
        syntheticButton = false;
    }

    private void forget() {
        windowGesture = false;
        view.removeCallbacks(longPress);
        contact = pendingTap = scrolling = moved = false;
        scrollX = scrollY = 0;
    }

    private void longPress() {
        if (!contact || !pendingTap || relative || output == null) return;
        pendingTap = false;
        position(startX, startY);
        syntheticButton = true;
        updateButtons();
    }

    boolean event(MotionEvent event) {
        if (output == null || !viewport.available()) return false;
        int action = event.getActionMasked();
        boolean classified = event.getClassification() == MotionEvent.CLASSIFICATION_TWO_FINGER_SWIPE;
        boolean finger = event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER;
        boolean touchpad = event.isFromSource(InputDevice.SOURCE_TOUCHPAD);
        boolean mouse = event.isFromSource(InputDevice.SOURCE_MOUSE)
                || event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE;
        boolean rawPad = touchpad && !mouse;
        observe(event, classified ? "scroll" : finger ? rawPad ? "touchpad" : "touch" : "pointer");

        if (action == MotionEvent.ACTION_CANCEL) { release(); return true; }
        if (action == MotionEvent.ACTION_SCROLL) {
            cancelTouch();
            if (!rawPad) position(event.getX(), event.getY());
            physicalButtons = event.getButtonState();
            contact = physicalButtons != 0;
            updateButtons();
            output.scroll(x, y, event.getAxisValue(MotionEvent.AXIS_HSCROLL),
                    event.getAxisValue(MotionEvent.AXIS_VSCROLL));
            return true;
        }
        if (action == MotionEvent.ACTION_HOVER_MOVE || action == MotionEvent.ACTION_HOVER_ENTER
                || action == MotionEvent.ACTION_HOVER_EXIT) {
            // HOVER_EXIT can carry a pressed button before DOWN; hover does not own button edges.
            if (!rawPad) position(event.getX(), event.getY());
            return true;
        }
        if (action == MotionEvent.ACTION_BUTTON_PRESS || action == MotionEvent.ACTION_BUTTON_RELEASE) {
            pendingTap = syntheticButton = false;
            view.removeCallbacks(longPress);
            if (!rawPad) position(event.getX(), event.getY());
            physicalButtons = event.getButtonState();
            if (action == MotionEvent.ACTION_BUTTON_PRESS) contact = true;
            updateButtons();
            return true;
        }
        if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE
                && action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_POINTER_DOWN
                && action != MotionEvent.ACTION_POINTER_UP) return false;

        if (action == MotionEvent.ACTION_DOWN) {
            release();
            contact = true;
            relative = rawPad;
            startX = previousX = event.getX();
            startY = previousY = event.getY();
            if (!relative) position(startX, startY);
            physicalButtons = event.getButtonState();
            pendingTap = finger && physicalButtons == 0 && !classified;
            syntheticButton = !finger && !mouse && !classified;
            updateButtons();
            if (pendingTap && !relative) view.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
        }
        // Focus loss, Surface replacement and drag handoff invalidate the rest of a contact stream.
        if (!contact) return true;
        physicalButtons = event.getButtonState();
        if (mouse && !finger && !classified && !scrolling) {
            position(event.getX(), event.getY());
            updateButtons();
            if (action == MotionEvent.ACTION_UP) release();
            return true;
        }
        if (classified || (finger && event.getPointerCount() > 1 && physicalButtons == 0)) {
            pendingTap = syntheticButton = false;
            view.removeCallbacks(longPress);
            updateButtons();
            float cx = centroid(event, true), cy = centroid(event, false);
            if (classified) {
                // These axes are per-sample pixel deltas; batched history must not be dropped.
                for (int i = 0; i < event.getHistorySize(); i++)
                    scrollPixels(event.getHistoricalAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_X_DISTANCE, i),
                            event.getHistoricalAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_Y_DISTANCE, i));
                scrollPixels(event.getAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_X_DISTANCE),
                        event.getAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_Y_DISTANCE));
            } else if (scrolling && action == MotionEvent.ACTION_MOVE) {
                scrollPixels(previousX - cx, previousY - cy);
            }
            // Rebase when fingers arrive/leave; lifting one must neither jump nor resume a drag.
            previousX = cx; previousY = cy;
            scrolling = true;
        } else if (!scrolling) {
            float px = event.getX(), py = event.getY();
            float dx = px - startX, dy = py - startY;
            if (dx * dx + dy * dy > slopSquared) {
                moved = true;
                if (pendingTap) {
                    pendingTap = false;
                    view.removeCallbacks(longPress);
                    syntheticButton = !relative;
                    if (syntheticButton) position(startX, startY);
                    updateButtons();
                }
            }
            if (relative) {
                x = Math.max(0, Math.min(1, x + (px - previousX) / viewport.width()));
                y = Math.max(0, Math.min(1, y + (py - previousY) / viewport.height()));
                output.pointer(x, y);
            } else position(px, py);
            previousX = px; previousY = py;
            updateButtons();
        }
        if (action == MotionEvent.ACTION_UP) {
            boolean tap = pendingTap && !moved && !scrolling;
            if (tap) { syntheticButton = true; updateButtons(); }
            release();
            if (tap) view.performClick();
        }
        return true;
    }

    private void cancelTouch() {
        syntheticButton = false;
        forget();
    }

    private void position(float px, float py) {
        x = viewport.contentX(px); y = viewport.contentY(py);
        output.pointer(x, y);
    }

    private static float centroid(MotionEvent event, boolean horizontal) {
        float sum = 0;
        int count = 0;
        for (int i = 0; i < event.getPointerCount(); i++) {
            if (event.getActionMasked() == MotionEvent.ACTION_POINTER_UP && i == event.getActionIndex()) continue;
            sum += horizontal ? event.getX(i) : event.getY(i);
            count++;
        }
        return count == 0 ? 0 : sum / count;
    }

    private void scrollPixels(float horizontal, float vertical) {
        if (!Float.isFinite(horizontal) || !Float.isFinite(vertical)) return;
        // Android content offsets grow right/down; wheel axes grow right/up.
        scrollX += horizontal / horizontalFactor;
        scrollY -= vertical / verticalFactor;
        int h = (int) scrollX, v = (int) scrollY;
        scrollX -= h; scrollY -= v;
        if (h != 0 || v != 0) output.scroll(x, y, h, v);
    }

    private void updateButtons() {
        int next = physicalButtons | (syntheticButton ? MotionEvent.BUTTON_PRIMARY : 0);
        for (HostedSurfaceOutput.Button button : BUTTONS) {
            int mask = switch (button) {
                case PRIMARY -> MotionEvent.BUTTON_PRIMARY;
                case MIDDLE -> MotionEvent.BUTTON_TERTIARY;
                case SECONDARY -> MotionEvent.BUTTON_SECONDARY;
            };
            if (output != null && ((buttons ^ next) & mask) != 0)
                output.button(x, y, button, (next & mask) != 0);
        }
        buttons = next;
    }

    private void observe(MotionEvent event, String route) {
        if (observedCount == observed.length) return;
        int deviceId = event.getDeviceId();
        long shape = Integer.toUnsignedLong(event.getSource())
                | (long) event.getActionMasked() << 32 | (long) event.getToolType(0) << 40
                | (long) event.getPointerCount() << 44 | (long) event.getClassification() << 50
                | (long) event.getButtonState() << 54;
        for (int i = 0; i < observedCount; i++)
            if (observed[i] == shape && observedDevices[i] == deviceId) return;
        observed[observedCount] = shape;
        observedDevices[observedCount++] = deviceId;
        DesktopAutomationEventJournal.record("hosted_input", "pointer_route", true,
                "route=" + route + " source=0x" + Integer.toHexString(event.getSource())
                        + " device=" + deviceId + " action=" + event.getActionMasked()
                        + " tool=" + event.getToolType(0) + " pointers=" + event.getPointerCount()
                        + " classification=" + event.getClassification() + " buttons=" + event.getButtonState()
                        + " wheel=" + event.getAxisValue(MotionEvent.AXIS_HSCROLL) + "," + event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                        + " scrollPixels=" + event.getAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_X_DISTANCE)
                        + "," + event.getAxisValue(MotionEvent.AXIS_GESTURE_SCROLL_Y_DISTANCE));
    }
}
