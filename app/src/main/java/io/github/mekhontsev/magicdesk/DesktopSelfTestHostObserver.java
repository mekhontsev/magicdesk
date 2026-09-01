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
    private static long sRunId;
    private static long sStartedAt;
    private static String sStage = "PREPARE";
    private static boolean sHostSeen;
    private static boolean sRenderedFreeform;
    private static int sFirstFrameCount;
    private static boolean sReady;
    private static boolean sLostReadyUi;
    private static int sTaskbarHiddenGeneration;
    private static int sAltTabPanelGeneration;
    private static int sAltTabSelectionGeneration;
    private static int sAltTabSelectedTaskId = -1;
    private static int sGeneration;
    private static final List<String> EVENTS = new ArrayList<>();

    private DesktopSelfTestHostObserver() {
    }

    static synchronized void begin(final long runId) {
        if (runId <= 0L) {
            throw new IllegalArgumentException("self-test run is required");
        }
        sGeneration++;
        sActive = true;
        sRunId = runId;
        sStartedAt = SystemClock.uptimeMillis();
        sStage = "PREPARE";
        sHostSeen = false;
        sRenderedFreeform = false;
        sFirstFrameCount = 0;
        sReady = false;
        sLostReadyUi = false;
        sTaskbarHiddenGeneration = 0;
        sAltTabPanelGeneration = 0;
        sAltTabSelectionGeneration = 0;
        sAltTabSelectedTaskId = -1;
        EVENTS.clear();
    }

    static synchronized boolean isActive(final long runId) {
        return sActive && runId > 0L && sRunId == runId;
    }

    private static synchronized boolean isActive() {
        return sActive;
    }

    static synchronized String currentStage() {
        return sStage;
    }

    static void stage(final String stage) {
        boolean changed = false;
        long runId = 0L;
        synchronized (DesktopSelfTestHostObserver.class) {
            if (sActive && stage != null && !stage.isEmpty()
                    && !stage.equals(sStage)) {
                sStage = stage;
                runId = sRunId;
                changed = true;
            }
        }
        if (changed) {
            DesktopSelfTestRunState.stage(runId, stage);
            DesktopSelfTestTaskStackGuard.stage(stage);
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

    static synchronized void noteAltTabPanelShown(
            final int selectedTaskId) {
        if (sActive) {
            sAltTabPanelGeneration++;
            noteAltTabSelectionChanged(selectedTaskId);
        }
    }

    static synchronized int altTabSelectionGeneration() {
        return sAltTabSelectionGeneration;
    }

    static synchronized int altTabSelectedTaskId() {
        return sAltTabSelectedTaskId;
    }

    static synchronized void noteAltTabSelectionChanged(
            final int selectedTaskId) {
        if (sActive) {
            sAltTabSelectedTaskId = selectedTaskId;
            sAltTabSelectionGeneration++;
        }
    }

    static synchronized int taskbarHiddenGeneration() {
        return sTaskbarHiddenGeneration;
    }

    static synchronized void noteTaskbarVisibilityChanged(
            final int displayId,
            final boolean visible) {
        if (!sActive || visible) {
            return;
        }
        sTaskbarHiddenGeneration++;
        if (EVENTS.size() < MAX_EVENTS) {
            EVENTS.add("+" + (SystemClock.uptimeMillis() - sStartedAt)
                    + "ms " + sStage
                    + " taskbar-hidden display=" + displayId);
        }
    }

    static void observeNextFrame(
            final DesktopShellActivity activity,
            final String reason) {
        if (activity == null || !isActive()) {
            return;
        }
        final int generation = generation();
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
                        recordFrame(activity, reason, generation);
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
        sGeneration++;
        sActive = false;
        sRunId = 0L;
        sStartedAt = 0L;
        sStage = "PREPARE";
        sHostSeen = false;
        sRenderedFreeform = false;
        sFirstFrameCount = 0;
        sReady = false;
        sLostReadyUi = false;
        sTaskbarHiddenGeneration = 0;
        sAltTabPanelGeneration = 0;
        sAltTabSelectionGeneration = 0;
        sAltTabSelectedTaskId = -1;
        EVENTS.clear();
    }

    private static synchronized void recordFrame(
            final DesktopShellActivity activity,
            final String reason,
            final int generation) {
        if (!sActive || generation != sGeneration || activity == null) {
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

    private static synchronized int generation() {
        return sGeneration;
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
