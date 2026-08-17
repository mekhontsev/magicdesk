package io.github.mekhontsev.magicdesk;

import android.os.SystemClock;
import android.view.View;
import android.view.ViewTreeObserver;

import java.util.ArrayList;
import java.util.List;

/** Records rendered desktop-host windowing states while a self-test is active. */
final class DesktopSelfTestHostObserver {
    private static final int MAX_EVENTS = 24;

    private static boolean sActive;
    private static long sStartedAt;
    private static String sStage = "PREPARE";
    private static boolean sHostSeen;
    private static boolean sRenderedFreeform;
    private static int sFirstFrameCount;
    private static boolean sReady;
    private static boolean sLostReadyUi;
    private static int sAltTabPanelGeneration;
    private static final List<String> EVENTS = new ArrayList<>();

    private DesktopSelfTestHostObserver() {
    }

    static synchronized void begin() {
        sActive = true;
        sStartedAt = SystemClock.uptimeMillis();
        sStage = "PREPARE";
        sHostSeen = false;
        sRenderedFreeform = false;
        sFirstFrameCount = 0;
        sReady = false;
        sLostReadyUi = false;
        sAltTabPanelGeneration = 0;
        EVENTS.clear();
    }

    static synchronized boolean isActive() {
        return sActive;
    }

    static synchronized void stage(final String stage) {
        if (sActive && stage != null && !stage.isEmpty()) {
            sStage = stage;
        }
    }

    static synchronized void markReady() {
        if (sActive) {
            sReady = true;
        }
    }

    static synchronized int altTabPanelGeneration() {
        return sAltTabPanelGeneration;
    }

    static synchronized void noteAltTabPanelShown() {
        if (sActive) {
            sAltTabPanelGeneration++;
        }
    }

    static void observeNextFrame(
            final DesktopShellActivity activity,
            final String reason) {
        if (activity == null || !isActive()) {
            return;
        }
        final View decor = activity.getWindow().getDecorView();
        decor.getViewTreeObserver().addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        final ViewTreeObserver observer =
                                decor.getViewTreeObserver();
                        if (observer.isAlive()) {
                            observer.removeOnPreDrawListener(this);
                        }
                        recordFrame(activity, reason);
                        return true;
                    }
                });
    }

    static synchronized Observation finish(final int expectedDisplayId) {
        final Observation observation = new Observation(
                sHostSeen,
                sRenderedFreeform,
                sFirstFrameCount > 1,
                sLostReadyUi,
                "expected-display=" + expectedDisplayId + "; "
                        + "first-frames=" + sFirstFrameCount + "; "
                        + String.join("; ", EVENTS));
        cancel();
        return observation;
    }

    static synchronized void cancel() {
        sActive = false;
        sStartedAt = 0L;
        sStage = "PREPARE";
        sHostSeen = false;
        sRenderedFreeform = false;
        sFirstFrameCount = 0;
        sReady = false;
        sLostReadyUi = false;
        sAltTabPanelGeneration = 0;
        EVENTS.clear();
    }

    private static synchronized void recordFrame(
            final DesktopShellActivity activity,
            final String reason) {
        if (!sActive || activity == null) {
            return;
        }
        final int displayId = activity.getCurrentDisplayId();
        final boolean freeform = activity.isInMultiWindowMode();
        sHostSeen = true;
        sRenderedFreeform |= freeform;
        if ("first-frame".equals(reason)) {
            sFirstFrameCount++;
        }
        if (sReady && (!activity.isDesktopHostReady()
                || !activity.isTaskbarVisible())) {
            sLostReadyUi = true;
        }
        if (EVENTS.size() < MAX_EVENTS) {
            EVENTS.add("+" + (SystemClock.uptimeMillis() - sStartedAt)
                    + "ms " + sStage
                    + " " + reason
                    + " display=" + displayId
                    + " task=" + activity.getTaskId()
                    + " mode=" + (freeform ? "freeform" : "fullscreen"));
        }
    }

    static final class Observation {
        final boolean hostSeen;
        final boolean renderedFreeform;
        final boolean recreated;
        final boolean lostReadyUi;
        final String detail;

        Observation(
                final boolean hostSeen,
                final boolean renderedFreeform,
                final boolean recreated,
                final boolean lostReadyUi,
                final String detail) {
            this.hostSeen = hostSeen;
            this.renderedFreeform = renderedFreeform;
            this.recreated = recreated;
            this.lostReadyUi = lostReadyUi;
            this.detail = detail;
        }
    }
}
