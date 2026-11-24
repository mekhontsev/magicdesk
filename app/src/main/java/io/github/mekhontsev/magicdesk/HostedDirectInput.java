package io.github.mekhontsev.magicdesk;

import android.view.InputDevice;
import android.view.MotionEvent;
import io.github.mekhontsev.magicdesk.hosted.HostedTouchPhase;

/** Direct contacts are distinct from touchpad gestures and core-pointer emulation. */
final class HostedDirectInput {
    private int contacts;
    private int device;
    private boolean tablet, tip;

    void cancel(HostedSurfaceOutput output) {
        if (output != null && (contacts != 0 || tablet)) output.cancelContacts();
        forget();
    }

    void forget() {
        contacts = 0;
        tablet = tip = false;
    }

    boolean dragging() { return contacts != 0 || tip; }

    boolean event(MotionEvent event, HostedSurfaceOutput output, HostedViewport viewport) {
        int tool = event.getToolType(0), action = event.getActionMasked();
        boolean pen = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER;
        boolean touch = tool == MotionEvent.TOOL_TYPE_FINGER && event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN);
        if (!(pen && output.supportsTablet()) && !(touch && output.supportsTouch())) return false;
        if ((contacts != 0 || tablet) && device != event.getDeviceId()) cancel(output);
        device = event.getDeviceId();
        if (action == MotionEvent.ACTION_CANCEL) { cancel(output); return true; }
        if (pen) {
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_HOVER_ENTER) tablet = true;
            if (!tablet) return true;
            if (action == MotionEvent.ACTION_DOWN) tip = true;
            if (action == MotionEvent.ACTION_UP) tip = false;
            if (action == MotionEvent.ACTION_HOVER_EXIT && !tip) {
                cancel(output);
                return true;
            }
            // HOVER_EXIT commonly precedes DOWN and must not synthesize a tip press.
            if (action == MotionEvent.ACTION_HOVER_EXIT) return true;
            if (action == MotionEvent.ACTION_MOVE)
                for (int h = 0; h < event.getHistorySize(); h++) tablet(event, output, viewport, h);
            tablet(event, output, viewport, -1);
            return true;
        }
        if (action == MotionEvent.ACTION_DOWN) cancel(output);
        int index = event.getActionIndex();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            int id = event.getPointerId(index);
            if (id < 0 || id > 31) return true;
            contacts |= 1 << id;
            contact(event, index, -1, HostedTouchPhase.BEGIN, output, viewport);
            output.touchFrame();
        } else if (action == MotionEvent.ACTION_MOVE && contacts != 0) {
            for (int h = 0; h <= event.getHistorySize(); h++) {
                for (int i = 0; i < event.getPointerCount(); i++)
                    contact(event, i, h == event.getHistorySize() ? -1 : h, HostedTouchPhase.UPDATE, output, viewport);
                output.touchFrame();
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP) {
            int id = event.getPointerId(index);
            if (id >= 0 && id <= 31 && (contacts & (1 << id)) != 0) {
                contact(event, index, -1, HostedTouchPhase.END, output, viewport);
                contacts &= ~(1 << id);
                output.touchFrame();
            }
            if (action == MotionEvent.ACTION_UP) cancel(output);
        }
        return true;
    }

    private void contact(MotionEvent e, int index, int history, HostedTouchPhase phase,
            HostedSurfaceOutput output, HostedViewport viewport) {
        int id = e.getPointerId(index);
        if (id < 0 || id > 31 || (contacts & (1 << id)) == 0) return;
        float x = history < 0 ? e.getX(index) : e.getHistoricalX(index, history);
        float y = history < 0 ? e.getY(index) : e.getHistoricalY(index, history);
        float pressure = history < 0 ? e.getPressure(index) : e.getHistoricalPressure(index, history);
        output.touch(id, phase, viewport.contentX(x), viewport.contentY(y), unit(pressure));
    }

    private void tablet(MotionEvent e, HostedSurfaceOutput output, HostedViewport viewport, int history) {
        float x = history < 0 ? e.getX() : e.getHistoricalX(0, history);
        float y = history < 0 ? e.getY() : e.getHistoricalY(0, history);
        float pressure = history < 0 ? e.getPressure() : e.getHistoricalPressure(0, history);
        float tilt = axis(e, MotionEvent.AXIS_TILT, history), orientation = axis(e, MotionEvent.AXIS_ORIENTATION, history);
        tilt = Math.max(0, Math.min((float) Math.PI / 2, tilt));
        float tx = (float) Math.atan2(-Math.sin(orientation) * Math.sin(tilt), Math.cos(tilt));
        float ty = (float) Math.atan2(Math.cos(orientation) * Math.sin(tilt), Math.cos(tilt));
        int buttons = (tip ? 1 : 0)
                | ((e.getButtonState() & MotionEvent.BUTTON_STYLUS_PRIMARY) != 0 ? 2 : 0)
                | ((e.getButtonState() & MotionEvent.BUTTON_STYLUS_SECONDARY) != 0 ? 4 : 0);
        output.tablet(e.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER, true,
                viewport.contentX(x), viewport.contentY(y), tip ? unit(pressure) : 0, tx, ty, buttons);
    }

    private static float axis(MotionEvent e, int axis, int history) {
        float value = history < 0 ? e.getAxisValue(axis) : e.getHistoricalAxisValue(axis, history);
        return Float.isFinite(value) ? value : 0;
    }
    private static float unit(float value) { return Float.isFinite(value) ? Math.max(0, Math.min(1, value)) : 0; }
}
