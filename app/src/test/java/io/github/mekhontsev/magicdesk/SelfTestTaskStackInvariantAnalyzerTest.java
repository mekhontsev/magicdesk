package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public final class SelfTestTaskStackInvariantAnalyzerTest {
    private static final int DISPLAY_ID = 2;
    private static final int HOST_TASK_ID = 1;
    private static final int FIXTURE_TASK_ID = 10;

    @Test
    public void acceptsStableWindowedOperation() {
        final SelfTestTaskStackInvariantAnalyzer analyzer = analyzer();
        analyzer.begin("WINDOW", windowed(0, true));
        analyzer.sample("focus", windowed(1, true), true);

        assertEquals(0, analyzer.finish(windowed(2, true)).anomalies.length);
    }

    @Test
    public void acceptsSingleModeTransition() {
        final SelfTestTaskStackInvariantAnalyzer analyzer = analyzer();
        analyzer.begin("FULLSCREEN", windowed(0, true));
        analyzer.sample("mode", fullscreen(1, true, false), true);

        assertEquals(0,
                analyzer.finish(fullscreen(2, true, false)).anomalies.length);
    }

    @Test
    public void rejectsTransientWindowingMode() {
        final SelfTestTaskStackInvariantAnalyzer analyzer = analyzer();
        analyzer.begin("ALT-TAB", fullscreen(0, true, false));
        analyzer.sample("mode", windowed(1, true), true);

        assertContains(
                analyzer.finish(fullscreen(2, true, false)),
                "observed=display2/mode5");
    }

    @Test
    public void acceptsHiddenSourceModeDuringDecorationRestore() {
        final SelfTestTaskStackInvariantAnalyzer analyzer = analyzer();
        analyzer.begin("RESTORE", fullscreen(0, true, false));
        analyzer.sample("client-restore", windowed(1, true), true);
        analyzer.sample("hidden-rebuild", fullscreen(2, false, true), true);

        assertEquals(0,
                analyzer.finish(windowed(3, true)).anomalies.length);
    }

    @Test
    public void rejectsFullscreenVisibilityGap() {
        final SelfTestTaskStackInvariantAnalyzer analyzer = analyzer();
        analyzer.begin("ALT-TAB", fullscreen(0, true, false));
        analyzer.sample("focus", fullscreen(1, false, true), true);

        assertContains(
                analyzer.finish(fullscreen(2, true, false)),
                "no fullscreen fixture is visible");
    }

    @Test
    public void rejectsHiddenDesktopHostDuringWindowedOperation() {
        final SelfTestTaskStackInvariantAnalyzer analyzer = analyzer();
        analyzer.begin("FOCUS", windowed(0, true));
        analyzer.sample("focus", windowed(1, false), true);

        assertContains(
                analyzer.finish(windowed(2, true)),
                "desktop host became invisible");
    }

    @Test
    public void rejectsPhoneFreeformFixture() {
        final SelfTestTaskStackInvariantAnalyzer analyzer = analyzer();
        analyzer.begin("MOVE", windowed(0, true));
        analyzer.sample("display", snapshot(
                1,
                task(HOST_TASK_ID, DISPLAY_ID, 1, true, false, false),
                task(FIXTURE_TASK_ID, 0, 5, true, true, false)), true);

        assertContains(
                analyzer.finish(snapshot(
                        2,
                        task(HOST_TASK_ID, DISPLAY_ID, 1, true, false, false),
                        task(FIXTURE_TASK_ID, 0, 1, true, true, false))),
                "entered phone/freeform");
    }

    @Test
    public void acceptsFreeformFixtureWhenPhoneIsDesktopTarget() {
        final SelfTestTaskStackInvariantAnalyzer analyzer =
                new SelfTestTaskStackInvariantAnalyzer(
                        0, HOST_TASK_ID, 0);
        final SelfTestTaskStackInvariantAnalyzer.Snapshot phoneDesktop =
                snapshot(
                        0,
                        task(HOST_TASK_ID, 0, 1,
                                false, false, false),
                        task(FIXTURE_TASK_ID, 0, 5,
                                true, true, false));
        analyzer.begin("WINDOW", phoneDesktop);

        assertEquals(0,
                analyzer.finish(phoneDesktop).anomalies.length);
    }

    @Test
    public void acceptsHiddenTargetModeBeforeDisplayTransfer() {
        final SelfTestTaskStackInvariantAnalyzer analyzer = analyzer();
        analyzer.begin("MOVE", windowed(0, true));
        analyzer.sample("prepared", snapshot(
                1,
                task(HOST_TASK_ID, DISPLAY_ID, 1, true, false, false),
                task(FIXTURE_TASK_ID, DISPLAY_ID, 1,
                        false, true, false)), true);

        assertEquals(0,
                analyzer.finish(snapshot(
                        2,
                        task(HOST_TASK_ID, DISPLAY_ID, 1,
                                true, false, false),
                        task(FIXTURE_TASK_ID, 0, 1,
                                true, true, false))).anomalies.length);
    }

    @Test
    public void rejectsVisibleTargetModeBeforeDisplayTransfer() {
        final SelfTestTaskStackInvariantAnalyzer analyzer = analyzer();
        analyzer.begin("MOVE", windowed(0, true));
        analyzer.sample("prepared", snapshot(
                1,
                task(HOST_TASK_ID, DISPLAY_ID, 1, true, false, false),
                task(FIXTURE_TASK_ID, DISPLAY_ID, 1,
                        true, true, false)), true);

        assertContains(
                analyzer.finish(snapshot(
                        2,
                        task(HOST_TASK_ID, DISPLAY_ID, 1,
                                true, false, false),
                        task(FIXTURE_TASK_ID, 0, 1,
                                true, true, false))),
                "observed=display2/mode1");
    }

    @Test
    public void acceptsHiddenDefaultModeDuringTaskCreation() {
        final SelfTestTaskStackInvariantAnalyzer analyzer = analyzer();
        final SelfTestTaskStackInvariantAnalyzer.Snapshot absent = snapshot(
                0,
                task(HOST_TASK_ID, DISPLAY_ID, 1,
                        true, false, false));
        analyzer.begin("LAUNCH", absent);
        analyzer.sample("created", snapshot(
                1,
                task(HOST_TASK_ID, DISPLAY_ID, 1, true, false, false),
                task(FIXTURE_TASK_ID, DISPLAY_ID, 1,
                        false, true, false)), true);

        assertEquals(0,
                analyzer.finish(windowed(2, true)).anomalies.length);
    }

    @Test
    public void rejectsVisibleDefaultModeDuringTaskCreation() {
        final SelfTestTaskStackInvariantAnalyzer analyzer = analyzer();
        final SelfTestTaskStackInvariantAnalyzer.Snapshot absent = snapshot(
                0,
                task(HOST_TASK_ID, DISPLAY_ID, 1,
                        true, false, false));
        analyzer.begin("LAUNCH", absent);
        analyzer.sample("created", snapshot(
                1,
                task(HOST_TASK_ID, DISPLAY_ID, 1, true, false, false),
                task(FIXTURE_TASK_ID, DISPLAY_ID, 1,
                        true, true, false)), true);

        assertContains(
                analyzer.finish(windowed(2, true)),
                "observed=display2/mode1");
    }

    @Test
    public void acceptsDesktopHostReportedAsHomeActivity() {
        final SelfTestTaskStackInvariantAnalyzer analyzer = analyzer();
        final SelfTestTaskStackInvariantAnalyzer.Snapshot snapshot = snapshot(
                0,
                task(HOST_TASK_ID, DISPLAY_ID, 1, true, false, true),
                task(FIXTURE_TASK_ID, DISPLAY_ID, 5, true, true, false));
        analyzer.begin("WINDOW", snapshot);

        assertEquals(0, analyzer.finish(snapshot).anomalies.length);
    }

    @Test
    public void rejectsAnotherVisibleHomeTask() {
        final SelfTestTaskStackInvariantAnalyzer analyzer = analyzer();
        final SelfTestTaskStackInvariantAnalyzer.Snapshot snapshot = snapshot(
                0,
                task(HOST_TASK_ID, DISPLAY_ID, 1, true, false, true),
                task(FIXTURE_TASK_ID, DISPLAY_ID, 5, true, true, false),
                task(20, DISPLAY_ID, 1, true, false, true));
        analyzer.begin("WINDOW", snapshot);

        assertContains(analyzer.finish(snapshot), "Home task 20 became visible");
    }

    @Test
    public void acceptsPhoneHomeBelowPhoneDesktop() {
        final SelfTestTaskStackInvariantAnalyzer analyzer =
                new SelfTestTaskStackInvariantAnalyzer(
                        0, HOST_TASK_ID, 0);
        final SelfTestTaskStackInvariantAnalyzer.Snapshot phoneDesktop =
                snapshot(
                        0,
                        task(HOST_TASK_ID, 0, 1,
                                true, false, true),
                        task(FIXTURE_TASK_ID, 0, 1,
                                true, true, false),
                        task(20, 0, 1,
                                true, false, true));
        analyzer.begin("FULLSCREEN", phoneDesktop);

        assertEquals(0,
                analyzer.finish(phoneDesktop).anomalies.length);
    }

    private static SelfTestTaskStackInvariantAnalyzer analyzer() {
        return new SelfTestTaskStackInvariantAnalyzer(
                DISPLAY_ID, HOST_TASK_ID, 0);
    }

    private static SelfTestTaskStackInvariantAnalyzer.Snapshot windowed(
            final long time,
            final boolean hostVisible) {
        return snapshot(
                time,
                task(HOST_TASK_ID, DISPLAY_ID, 1,
                        hostVisible, false, false),
                task(FIXTURE_TASK_ID, DISPLAY_ID, 5,
                        true, true, false));
    }

    private static SelfTestTaskStackInvariantAnalyzer.Snapshot fullscreen(
            final long time,
            final boolean fixtureVisible,
            final boolean hostVisible) {
        return snapshot(
                time,
                task(HOST_TASK_ID, DISPLAY_ID, 1,
                        hostVisible, false, false),
                task(FIXTURE_TASK_ID, DISPLAY_ID, 1,
                        fixtureVisible, true, false));
    }

    private static SelfTestTaskStackInvariantAnalyzer.Snapshot snapshot(
            final long time,
            final SelfTestTaskStackInvariantAnalyzer.TaskState... tasks) {
        return new SelfTestTaskStackInvariantAnalyzer.Snapshot(
                time, Arrays.asList(tasks), true);
    }

    private static SelfTestTaskStackInvariantAnalyzer.TaskState task(
            final int taskId,
            final int displayId,
            final int mode,
            final boolean visible,
            final boolean fixture,
            final boolean home) {
        return new SelfTestTaskStackInvariantAnalyzer.TaskState(
                taskId,
                displayId,
                mode,
                visible,
                true,
                fixture,
                home);
    }

    private static void assertContains(
            final SelfTestTaskStackReport report,
            final String expected) {
        for (final String anomaly : report.anomalies) {
            if (anomaly.contains(expected)) {
                return;
            }
        }
        assertTrue("missing " + expected + " in "
                + Arrays.toString(report.anomalies), false);
    }
}
