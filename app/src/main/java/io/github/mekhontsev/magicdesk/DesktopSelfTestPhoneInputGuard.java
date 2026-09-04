package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Owns the event-driven phone input barrier for non-phone self-tests. */
final class DesktopSelfTestPhoneInputGuard {
    private static final int MAX_EVENTS = 16;
    private static final long LIFECYCLE_TIMEOUT_MILLIS = 5_000L;
    private static final List<String> EVENTS = new ArrayList<>();

    private static boolean sActive;
    private static boolean sClosing;
    private static boolean sSeen;
    private static boolean sLost;
    private static long sStartedAt;
    private static int sTouchCount;
    private static int sKeyCount;

    private DesktopSelfTestPhoneInputGuard() {
    }

    static String begin(final Context context, final long runId)
            throws IOException {
        synchronized (DesktopSelfTestPhoneInputGuard.class) {
            reset();
            sActive = true;
            sStartedAt = SystemClock.uptimeMillis();
            addEvent("guard requested");
        }
        final boolean started;
        try {
            started = DesktopSelfTestPhoneGuardActivity.showAndWait(
                    context, runId, LIFECYCLE_TIMEOUT_MILLIS);
        } catch (RuntimeException error) {
            cancel();
            throw new IOException("could not open phone input guard", error);
        }
        if (!started || !DesktopSelfTestPhoneGuardActivity.isVisible()) {
            cancel();
            final String detail = DesktopSelfTestPhoneGuardActivity.lastError();
            throw new IOException("phone input guard did not become visible"
                    + (detail.isEmpty() ? "" : ": " + detail));
        }
        return "visible on display 0";
    }

    static Observation finish() {
        final boolean active;
        synchronized (DesktopSelfTestPhoneInputGuard.class) {
            active = sActive;
            if (active) {
                sClosing = true;
            }
        }
        final boolean closed = DesktopSelfTestPhoneGuardActivity.hideAndWait(
                LIFECYCLE_TIMEOUT_MILLIS);
        if (!active) {
            return Observation.notObserved();
        }
        synchronized (DesktopSelfTestPhoneInputGuard.class) {
            final Observation observation = new Observation(
                    true,
                    sSeen,
                    !sLost,
                    closed,
                    sTouchCount,
                    sKeyCount,
                    String.join("; ", EVENTS));
            reset();
            return observation;
        }
    }

    static void cancel() {
        synchronized (DesktopSelfTestPhoneInputGuard.class) {
            sClosing = true;
        }
        DesktopSelfTestPhoneGuardActivity.hideAndWait(
                LIFECYCLE_TIMEOUT_MILLIS);
        synchronized (DesktopSelfTestPhoneInputGuard.class) {
            reset();
        }
    }

    static synchronized void noteWindowShown() {
        if (!sActive) {
            return;
        }
        sSeen = true;
        addEvent("guard visible");
    }

    static synchronized void noteWindowHidden() {
        if (!sActive || sClosing) {
            return;
        }
        sLost = true;
        addEvent("guard window removed stage="
                + DesktopSelfTestHostObserver.currentStage());
    }

    static synchronized void recordTouch(final MotionEvent event) {
        if (!sActive || event == null
                || event.getActionMasked() != MotionEvent.ACTION_DOWN) {
            return;
        }
        sTouchCount++;
        addEvent("touch stage=" + DesktopSelfTestHostObserver.currentStage()
                + " display=0"
                + " device=" + event.getDeviceId()
                + " source=0x" + Integer.toHexString(event.getSource())
                + " tool=" + event.getToolType(0)
                + " at=" + Math.round(event.getX())
                + "," + Math.round(event.getY()));
    }

    static synchronized void recordKey(final KeyEvent event) {
        if (!sActive || event == null
                || event.getAction() != KeyEvent.ACTION_DOWN) {
            return;
        }
        sKeyCount++;
        addEvent("key stage=" + DesktopSelfTestHostObserver.currentStage()
                + " display=0"
                + " device=" + event.getDeviceId()
                + " source=0x" + Integer.toHexString(event.getSource())
                + " code=" + KeyEvent.keyCodeToString(event.getKeyCode()));
    }

    private static void addEvent(final String event) {
        if (EVENTS.size() >= MAX_EVENTS) {
            return;
        }
        EVENTS.add("+" + (SystemClock.uptimeMillis() - sStartedAt)
                + "ms " + event);
    }

    private static void reset() {
        sActive = false;
        sClosing = false;
        sSeen = false;
        sLost = false;
        sStartedAt = 0L;
        sTouchCount = 0;
        sKeyCount = 0;
        EVENTS.clear();
    }

    static final class Observation {
        final boolean observed;
        final boolean seen;
        final boolean stayedForeground;
        final boolean closed;
        final int touchCount;
        final int keyCount;
        final String detail;

        Observation(
                final boolean observed,
                final boolean seen,
                final boolean stayedForeground,
                final boolean closed,
                final int touchCount,
                final int keyCount,
                final String detail) {
            this.observed = observed;
            this.seen = seen;
            this.stayedForeground = stayedForeground;
            this.closed = closed;
            this.touchCount = touchCount;
            this.keyCount = keyCount;
            this.detail = detail;
        }

        boolean isolated() {
            return observed && seen && stayedForeground && closed
                    && touchCount == 0 && keyCount == 0;
        }

        static Observation notObserved() {
            return new Observation(
                    false, false, false, true, 0, 0, "not observed");
        }
    }
}
