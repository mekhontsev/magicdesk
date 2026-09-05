package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

public final class SelfTestTaskStackInvariantAnalyzerTest {
    private static final int DISPLAY_ID = 2;
    private static final int HOST_TASK_ID = 1;
    private static final int BACKSTOP_TASK_ID = 2;
    private static final int SECOND_BACKSTOP_TASK_ID = 3;
    private static final int THIRD_BACKSTOP_TASK_ID = 4;
    private static final int FIXTURE_TASK_ID = 10;
    private static final int SECOND_FIXTURE_TASK_ID = 11;
    private static final int THIRD_FIXTURE_TASK_ID = 12;
    private static final int HOST_FEATURE_ID = 1;
    private static final int FULLSCREEN_FEATURE_ID = 20_001;
    private static final int SECOND_FULLSCREEN_FEATURE_ID = 20_002;

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
    public void acceptsVisibleFreeformDuringFullscreenRestore() {
        final SelfTestTaskStackInvariantAnalyzer analyzer = analyzer();
        analyzer.begin("RESTORE", fullscreenPair(
                0, 1, true, 1, false, false));
        analyzer.sample("task-front", fullscreenPair(
                1, 5, true, 1, false, true), true);

        assertEquals(0, analyzer.finish(fullscreenPair(
                2, 5, false, 1, true, false)).anomalies.length);
    }

    @Test
    public void rejectsFullscreenVisibilityGapWhileClosingPeer() {
        final SelfTestTaskStackInvariantAnalyzer analyzer = analyzer();
        analyzer.begin("CLOSE", fullscreenPair(
                0, 1, true, 1, false, false));
        analyzer.sample("task-removed", snapshot(
                1,
                task(HOST_TASK_ID, DISPLAY_ID, 1,
                        true, false, false),
                task(SECOND_FIXTURE_TASK_ID, DISPLAY_ID, 1,
                        false, true, false)), true);

        assertContains(
                analyzer.finish(snapshot(
                        2,
                        task(HOST_TASK_ID, DISPLAY_ID, 1,
                                false, false, false),
                        task(SECOND_FIXTURE_TASK_ID, DISPLAY_ID, 1,
                                true, true, false))),
                "no fullscreen fixture is visible");
    }

    @Test
    public void acceptsOrganizerHostVisibilityMetadataChange() {
        final SelfTestTaskStackInvariantAnalyzer analyzer = analyzer();
        analyzer.begin("FOCUS", windowed(0, true));
        analyzer.sample("focus", windowed(1, false), true);

        assertEquals(0, analyzer.finish(windowed(2, true)).anomalies.length);
    }

    @Test
    public void acceptsPreCommitVisibilityGapResolvedByNextSnapshot() {
        final SelfTestTaskStackInvariantAnalyzer analyzer = analyzer();
        analyzer.begin("BACK", fullscreen(0, true, false));
        analyzer.sample("task-front", snapshot(
                1,
                task(HOST_TASK_ID, DISPLAY_ID, 1,
                        false, false, false)), true);
        analyzer.sample("task-removed", snapshot(
                2,
                task(HOST_TASK_ID, DISPLAY_ID, 1,
                        false, false, false)), true);

        assertEquals(0, analyzer.finish(hostOnly(3, true)).anomalies.length);
    }

    @Test
    public void rejectsVisibilityGapAtCommittedStackBoundary() {
        final SelfTestTaskStackInvariantAnalyzer analyzer = analyzer();
        analyzer.begin("BACK", fullscreen(0, true, false));
        final SelfTestTaskStackInvariantAnalyzer.Snapshot gap = snapshot(
                1,
                task(HOST_TASK_ID, DISPLAY_ID, 1,
                        false, false, false));
        analyzer.sample("task-front", gap, true);
        analyzer.sample("stack-changed", gap, true);

        assertContains(analyzer.finish(hostOnly(2, true)),
                "no task is visible on the desktop display");
    }

    @Test
    public void rejectsFreeformFixtureOutsideSelectedDisplay() {
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
                "left selected display " + DISPLAY_ID
                        + " for display 0 in mode 5");
    }

    @Test
    public void acceptsHiddenHostBehindOrganizerFreeformFixture() {
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

        assertEquals(0, analyzer.finish(phoneDesktop).anomalies.length);
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
    public void acceptsHiddenSourceModeAfterDisplayTransfer() {
        final SelfTestTaskStackInvariantAnalyzer analyzer = analyzer();
        analyzer.begin("MOVE", snapshot(
                0,
                task(HOST_TASK_ID, DISPLAY_ID, 1, true, false, false),
                task(FIXTURE_TASK_ID, 0, 1, true, true, false)));
        analyzer.sample("display", snapshot(
                1,
                task(HOST_TASK_ID, DISPLAY_ID, 1, true, false, false),
                task(FIXTURE_TASK_ID, DISPLAY_ID, 1,
                        false, true, false)), true);

        assertEquals(0, analyzer.finish(windowed(2, true)).anomalies.length);
    }

    @Test
    public void rejectsVisibleSourceModeAfterDisplayTransfer() {
        final SelfTestTaskStackInvariantAnalyzer analyzer = analyzer();
        analyzer.begin("MOVE", snapshot(
                0,
                task(HOST_TASK_ID, DISPLAY_ID, 1, true, false, false),
                task(FIXTURE_TASK_ID, 0, 1, true, true, false)));
        analyzer.sample("display", snapshot(
                1,
                task(HOST_TASK_ID, DISPLAY_ID, 1, true, false, false),
                task(FIXTURE_TASK_ID, DISPLAY_ID, 1,
                        true, true, false)), true);

        assertContains(
                analyzer.finish(windowed(2, true)),
                "observed=display2/mode1");
    }

    @Test
    public void collapsesRepeatedUnchangedCallbacks() {
        final SelfTestTaskStackInvariantAnalyzer analyzer = analyzer();
        analyzer.begin("WINDOW", windowed(0, true));
        for (int i = 1; i <= 256; i++) {
            analyzer.sample("stack", windowed(i, true), true);
        }

        final SelfTestTaskStackReport report =
                analyzer.finish(windowed(257, true));
        assertEquals(0, report.droppedSamples);
        assertEquals(0, report.anomalies.length);
        assertEquals(256, report.eventCount);
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
    public void rejectsUnsplitFixtureLifetime() {
        final SelfTestTaskStackInvariantAnalyzer analyzer = analyzer();
        analyzer.begin("ACTIVITY-RESULT-001", hostOnly(0, true));
        analyzer.sample("task-created", fullscreen(1, false, true), true);
        analyzer.sample("parent-ready", windowed(2, true), true);
        analyzer.sample("child-result", windowed(3, true), true);
        analyzer.sample("task-removed", hostOnly(4, true), true);

        assertContains(analyzer.finish(hostOnly(5, true)),
                "expected=absent -> absent observed=display2/mode1");
    }

    @Test
    public void acceptsActivityResultWithSeparateFixtureLifecycleStages() {
        final SelfTestTaskStackInvariantAnalyzer analyzer = analyzer();
        analyzer.begin("ACTIVITY-RESULT-PREPARE-001", hostOnly(0, true));
        analyzer.sample("task-created", fullscreen(1, false, true), true);
        analyzer.changeStage("ACTIVITY-RESULT-001", windowed(2, true));
        analyzer.sample("child-first-frame", windowed(3, true), true);
        analyzer.sample("child-result", windowed(4, true), true);
        analyzer.changeStage("ACTIVITY-RESULT-CLEANUP-001", windowed(5, true));
        analyzer.sample("task-removed", hostOnly(6, true), true);

        final SelfTestTaskStackReport report = analyzer.finish(hostOnly(7, true));
        assertEquals(3, report.stageCount);
        assertEquals(0, report.anomalies.length);
    }

    @Test
    public void rejectsModeChangeDuringActivityResultStage() {
        final SelfTestTaskStackInvariantAnalyzer analyzer = analyzer();
        analyzer.begin("ACTIVITY-RESULT-PREPARE-001", hostOnly(0, true));
        analyzer.changeStage("ACTIVITY-RESULT-001", windowed(1, true));
        analyzer.sample("child-first-frame", fullscreen(2, true, false), true);
        analyzer.changeStage("ACTIVITY-RESULT-CLEANUP-001", windowed(3, true));

        assertContains(analyzer.finish(hostOnly(4, true)),
                "expected=display2/mode5 -> display2/mode5 observed=display2/mode1");
    }

    @Test
    public void rejectsUnexpectedTaskDuringActivityResultStage() {
        final SelfTestTaskStackInvariantAnalyzer analyzer = analyzer();
        analyzer.begin("ACTIVITY-RESULT-PREPARE-001", hostOnly(0, true));
        analyzer.changeStage("ACTIVITY-RESULT-001", windowed(1, true));
        analyzer.sample("task-created", snapshot(2,
                task(HOST_TASK_ID, DISPLAY_ID, 1, true, false, false),
                task(FIXTURE_TASK_ID, DISPLAY_ID, 5, true, true, false),
                task(SECOND_FIXTURE_TASK_ID, DISPLAY_ID, 5, true, true, false)), true);
        analyzer.changeStage("ACTIVITY-RESULT-CLEANUP-001", windowed(3, true));

        assertContains(analyzer.finish(hostOnly(4, true)),
                "task=" + SECOND_FIXTURE_TASK_ID
                        + " expected=absent -> absent observed=display2/mode5");
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
    public void acceptsHiddenPreparationDuringFullscreenCreation() {
        final SelfTestTaskStackInvariantAnalyzer analyzer = analyzer();
        analyzer.begin("FULLSCREEN-LAUNCH", hostOnly(0, true));
        analyzer.sample("created", fullscreen(1, false, true), true);

        assertEquals(0,
                analyzer.finish(fullscreen(2, true, false)).anomalies.length);
    }

    @Test
    public void rejectsVisibleFreeformDuringFullscreenCreation() {
        final SelfTestTaskStackInvariantAnalyzer analyzer = analyzer();
        analyzer.begin("FULLSCREEN-LAUNCH", hostOnly(0, true));
        analyzer.sample("visible-freeform", windowed(1, false), true);

        assertContains(
                analyzer.finish(fullscreen(2, true, false)),
                "observed=display2/mode5");
    }

    @Test
    public void acceptsDirectFullscreenRemoval() {
        final SelfTestTaskStackInvariantAnalyzer analyzer = analyzer();
        analyzer.begin("FULLSCREEN-BACK", fullscreen(0, true, false));

        assertEquals(0,
                analyzer.finish(hostOnly(1, true)).anomalies.length);
    }

    @Test
    public void rejectsHiddenFullscreenBeforeRemoval() {
        final SelfTestTaskStackInvariantAnalyzer analyzer = analyzer();
        analyzer.begin("FULLSCREEN-BACK", fullscreen(0, true, false));
        analyzer.sample("hidden", fullscreen(1, false, true), true);

        assertContains(
                analyzer.finish(hostOnly(2, true)),
                "became hidden before removal");
    }

    @Test
    public void acceptsHiddenFullscreenWhileVisiblePeerSurvives() {
        final SelfTestTaskStackInvariantAnalyzer analyzer = analyzer();
        analyzer.begin("FULLSCREEN-CLOSE", fullscreenPair(
                0, 1, true, 1, false, false));
        analyzer.sample("peer-visible", fullscreenPair(
                1, 1, false, 1, true, false), true);

        assertEquals(0, analyzer.finish(snapshot(
                2,
                task(HOST_TASK_ID, DISPLAY_ID, 1,
                        false, false, false),
                task(SECOND_FIXTURE_TASK_ID, DISPLAY_ID, 1,
                        true, true, false))).anomalies.length);
    }

    @Test
    public void acceptsAppFullscreenInStableDefaultPlanes() {
        final SelfTestTaskStackInvariantAnalyzer analyzer = analyzer();
        final SelfTestTaskStackInvariantAnalyzer.Snapshot prepared = snapshot(
                0,
                taskInArea(HOST_TASK_ID, DISPLAY_ID, 1,
                        false, false, false, HOST_FEATURE_ID),
                backstop(BACKSTOP_TASK_ID, FULLSCREEN_FEATURE_ID, false),
                taskInArea(FIXTURE_TASK_ID, DISPLAY_ID, 1,
                        true, true, false, FULLSCREEN_FEATURE_ID),
                backstop(SECOND_BACKSTOP_TASK_ID,
                        SECOND_FULLSCREEN_FEATURE_ID, false),
                taskInArea(SECOND_FIXTURE_TASK_ID, DISPLAY_ID, 1,
                        false, true, false,
                        SECOND_FULLSCREEN_FEATURE_ID),
                backstop(THIRD_BACKSTOP_TASK_ID, 20_003, false),
                taskInArea(THIRD_FIXTURE_TASK_ID, DISPLAY_ID, 1,
                        false, true, false, 20_003));
        analyzer.begin("WINDOW-015", prepared);

        assertEquals(0, analyzer.finish(prepared).anomalies.length);
    }

    @Test
    public void rejectsAppFullscreenSharingAnotherTaskPlane() {
        final SelfTestTaskStackInvariantAnalyzer analyzer = analyzer();
        final SelfTestTaskStackInvariantAnalyzer.Snapshot split = snapshot(
                0,
                taskInArea(HOST_TASK_ID, DISPLAY_ID, 1,
                        false, false, false, HOST_FEATURE_ID),
                backstop(BACKSTOP_TASK_ID, FULLSCREEN_FEATURE_ID, false),
                taskInArea(FIXTURE_TASK_ID, DISPLAY_ID, 1,
                        true, true, false, FULLSCREEN_FEATURE_ID),
                taskInArea(SECOND_FIXTURE_TASK_ID, DISPLAY_ID, 1,
                        false, true, false, FULLSCREEN_FEATURE_ID),
                taskInArea(THIRD_FIXTURE_TASK_ID, DISPLAY_ID, 1,
                        false, true, false, FULLSCREEN_FEATURE_ID));
        analyzer.begin("WINDOW-015", split);

        assertContains(analyzer.finish(split),
                "fullscreen tasks share plane=" + FULLSCREEN_FEATURE_ID);
    }

    @Test
    public void acceptsPreparedDefaultFullscreenPlanes() {
        final SelfTestTaskStackInvariantAnalyzer analyzer = analyzer();
        final SelfTestTaskStackInvariantAnalyzer.Snapshot prepared =
                fullscreenPairInTaskArea(0);
        analyzer.begin("WINDOW-020-PREPARE", prepared);

        assertEquals(0, analyzer.finish(prepared).anomalies.length);
    }

    @Test
    public void rejectsPreparedFullscreenTasksSharingDesktopPlane() {
        final SelfTestTaskStackInvariantAnalyzer analyzer = analyzer();
        final SelfTestTaskStackInvariantAnalyzer.Snapshot unprepared = snapshot(
                0,
                taskInArea(HOST_TASK_ID, DISPLAY_ID, 1,
                        false, false, false, HOST_FEATURE_ID),
                taskInArea(FIXTURE_TASK_ID, DISPLAY_ID, 1,
                        true, true, false, HOST_FEATURE_ID),
                taskInArea(SECOND_FIXTURE_TASK_ID, DISPLAY_ID, 1,
                        false, true, false, HOST_FEATURE_ID));
        analyzer.begin("WINDOW-020-PREPARE", unprepared);

        assertContains(analyzer.finish(unprepared),
                "fullscreen tasks share plane=" + HOST_FEATURE_ID);
    }

    @Test
    public void rejectsFullscreenParentChangeDuringTargetOnlySwitch() {
        final SelfTestTaskStackInvariantAnalyzer analyzer = analyzer();
        analyzer.begin("WINDOW-020-PEER-1",
                fullscreenPairInTaskArea(0));
        final SelfTestTaskStackInvariantAnalyzer.Snapshot changed = snapshot(
                1,
                taskInArea(HOST_TASK_ID, DISPLAY_ID, 1,
                        false, false, false, HOST_FEATURE_ID),
                taskInArea(FIXTURE_TASK_ID, DISPLAY_ID, 1,
                        false, true, false, HOST_FEATURE_ID),
                taskInArea(SECOND_FIXTURE_TASK_ID, DISPLAY_ID, 1,
                        true, true, false,
                        SECOND_FULLSCREEN_FEATURE_ID));
        analyzer.sample("focus", changed, true);

        assertContains(analyzer.finish(changed), "parent changed");
    }

    @Test
    public void acceptsFullscreenPeerRetainedAfterRestore() {
        final SelfTestTaskStackInvariantAnalyzer analyzer = analyzer();
        analyzer.begin("FULLSCREEN-LIFECYCLE-001",
                fullscreenPairInTaskArea(0));
        final SelfTestTaskStackInvariantAnalyzer.Snapshot restored = snapshot(
                1,
                taskInArea(HOST_TASK_ID, DISPLAY_ID, 1,
                        false, false, false, HOST_FEATURE_ID),
                backstop(BACKSTOP_TASK_ID, FULLSCREEN_FEATURE_ID, false),
                taskInArea(FIXTURE_TASK_ID, DISPLAY_ID, 1,
                        false, true, false, FULLSCREEN_FEATURE_ID),
                backstop(SECOND_BACKSTOP_TASK_ID,
                        SECOND_FULLSCREEN_FEATURE_ID, true),
                taskInArea(SECOND_FIXTURE_TASK_ID, DISPLAY_ID, 5,
                        true, true, false, HOST_FEATURE_ID));
        analyzer.changeStage("FULLSCREEN-LIFECYCLE-002", restored);

        assertEquals(0, analyzer.finish(restored).anomalies.length);
    }

    @Test
    public void acceptsLoneFullscreenTaskRetainedInFullscreenParent() {
        final SelfTestTaskStackInvariantAnalyzer analyzer = analyzer();
        analyzer.begin("FULLSCREEN-LIFECYCLE-002",
                fullscreenPairInTaskArea(0));
        analyzer.changeStage("FULLSCREEN-LIFECYCLE-003", snapshot(
                1,
                taskInArea(HOST_TASK_ID, DISPLAY_ID, 1,
                        false, false, false, HOST_FEATURE_ID),
                backstop(BACKSTOP_TASK_ID, FULLSCREEN_FEATURE_ID, true),
                backstop(SECOND_BACKSTOP_TASK_ID,
                        SECOND_FULLSCREEN_FEATURE_ID, false),
                taskInArea(SECOND_FIXTURE_TASK_ID, DISPLAY_ID, 1,
                        true, true, false,
                        SECOND_FULLSCREEN_FEATURE_ID)));

        assertEquals(0, analyzer.finish(snapshot(
                2,
                taskInArea(HOST_TASK_ID, DISPLAY_ID, 1,
                        false, false, false, HOST_FEATURE_ID),
                backstop(BACKSTOP_TASK_ID, FULLSCREEN_FEATURE_ID, true),
                backstop(SECOND_BACKSTOP_TASK_ID,
                        SECOND_FULLSCREEN_FEATURE_ID, false),
                taskInArea(SECOND_FIXTURE_TASK_ID, DISPLAY_ID, 1,
                        true, true, false,
                        SECOND_FULLSCREEN_FEATURE_ID))).anomalies.length);
    }

    @Test
    public void rejectsDuplicateFullscreenSlotAnchors() {
        final SelfTestTaskStackInvariantAnalyzer analyzer = analyzer();
        analyzer.begin("FULLSCREEN-LIFECYCLE-002",
                fullscreenPairInTaskArea(0));
        final SelfTestTaskStackInvariantAnalyzer.Snapshot invalid = snapshot(
                1,
                taskInArea(HOST_TASK_ID, DISPLAY_ID, 1,
                        false, false, false, HOST_FEATURE_ID),
                backstop(BACKSTOP_TASK_ID, FULLSCREEN_FEATURE_ID, false),
                backstop(SECOND_BACKSTOP_TASK_ID,
                        FULLSCREEN_FEATURE_ID, false),
                taskInArea(SECOND_FIXTURE_TASK_ID, DISPLAY_ID, 1,
                        true, true, false, FULLSCREEN_FEATURE_ID));

        assertContains(analyzer.finish(invalid),
                "fullscreen slot=" + FULLSCREEN_FEATURE_ID
                        + " expected exactly one anchor, found=2");
    }

    @Test
    public void acceptsPersistentIdleSlotAfterPlaneExit() {
        final SelfTestTaskStackInvariantAnalyzer analyzer = analyzer();
        final SelfTestTaskStackInvariantAnalyzer.Snapshot fullscreen =
                fullscreenPairInTaskArea(0);
        analyzer.begin("FULLSCREEN-PLANE-EXIT", fullscreen);
        final SelfTestTaskStackInvariantAnalyzer.Snapshot restored = snapshot(
                1,
                taskInArea(HOST_TASK_ID, DISPLAY_ID, 1,
                        false, false, false, HOST_FEATURE_ID),
                backstop(BACKSTOP_TASK_ID, FULLSCREEN_FEATURE_ID, false),
                taskInArea(FIXTURE_TASK_ID, DISPLAY_ID, 1,
                        true, true, false, FULLSCREEN_FEATURE_ID),
                backstop(SECOND_BACKSTOP_TASK_ID,
                        SECOND_FULLSCREEN_FEATURE_ID, true),
                taskInArea(SECOND_FIXTURE_TASK_ID, DISPLAY_ID, 5,
                        true, true, false, HOST_FEATURE_ID));
        analyzer.sample("exit-complete", restored, true);

        assertEquals(0, analyzer.finish(restored).anomalies.length);
    }

    @Test
    public void rejectsFreeformFixtureLeftInsideFullscreenSlot() {
        final SelfTestTaskStackInvariantAnalyzer analyzer = analyzer();
        analyzer.begin("FULLSCREEN-PLANE-EXIT",
                fullscreenPairInTaskArea(0));
        final SelfTestTaskStackInvariantAnalyzer.Snapshot invalid = snapshot(
                1,
                taskInArea(HOST_TASK_ID, DISPLAY_ID, 1,
                        false, false, false, HOST_FEATURE_ID),
                backstop(BACKSTOP_TASK_ID, FULLSCREEN_FEATURE_ID, true),
                taskInArea(FIXTURE_TASK_ID, DISPLAY_ID, 1,
                        true, true, false, FULLSCREEN_FEATURE_ID),
                backstop(SECOND_BACKSTOP_TASK_ID,
                        SECOND_FULLSCREEN_FEATURE_ID, true),
                taskInArea(SECOND_FIXTURE_TASK_ID, DISPLAY_ID, 5,
                        true, true, false,
                        SECOND_FULLSCREEN_FEATURE_ID));

        assertContains(analyzer.finish(invalid),
                "fullscreen slot=" + SECOND_FULLSCREEN_FEATURE_ID
                        + " contains non-fullscreen fixture="
                        + SECOND_FIXTURE_TASK_ID);
    }

    @Test
    public void acceptsSeparateDesktopHostAndFullscreenSlots() {
        final SelfTestTaskStackInvariantAnalyzer analyzer = analyzer();
        analyzer.begin("FULLSCREEN-LIFECYCLE-002",
                fullscreenPairInTaskArea(0));

        assertEquals(0, analyzer.finish(
                fullscreenPairInTaskArea(1)).anomalies.length);
    }

    @Test
    public void rejectsDirectFullscreenLaunchInDesktopRoot() {
        final SelfTestTaskStackInvariantAnalyzer analyzer = analyzer();
        final SelfTestTaskStackInvariantAnalyzer.Snapshot rootFullscreen =
                snapshot(
                        0,
                        taskInArea(HOST_TASK_ID, DISPLAY_ID, 1,
                                false, false, false, HOST_FEATURE_ID),
                        taskInArea(FIXTURE_TASK_ID, DISPLAY_ID, 1,
                                true, true, false, HOST_FEATURE_ID));
        analyzer.begin("FULLSCREEN-LIFECYCLE-005", rootFullscreen);

        assertContains(
                analyzer.finish(rootFullscreen),
                "expected exactly one anchor, found=0");
    }

    @Test
    public void rejectsLifecycleFullscreenTaskInDesktopParent() {
        final SelfTestTaskStackInvariantAnalyzer analyzer = analyzer();
        analyzer.begin("FULLSCREEN-LIFECYCLE-002", snapshot(
                0,
                taskInArea(HOST_TASK_ID, DISPLAY_ID, 1,
                        false, false, false, HOST_FEATURE_ID),
                taskInArea(FIXTURE_TASK_ID, DISPLAY_ID, 1,
                        true, true, false, HOST_FEATURE_ID),
                taskInArea(SECOND_FIXTURE_TASK_ID, DISPLAY_ID, 1,
                        false, true, false, HOST_FEATURE_ID)));
        analyzer.changeStage("FULLSCREEN-LIFECYCLE-003", snapshot(
                1,
                taskInArea(HOST_TASK_ID, DISPLAY_ID, 1,
                        false, false, false, HOST_FEATURE_ID),
                taskInArea(SECOND_FIXTURE_TASK_ID, DISPLAY_ID, 1,
                        true, true, false, HOST_FEATURE_ID)));

        assertContains(analyzer.finish(snapshot(
                        2,
                        taskInArea(HOST_TASK_ID, DISPLAY_ID, 1,
                                false, false, false, HOST_FEATURE_ID),
                        taskInArea(SECOND_FIXTURE_TASK_ID, DISPLAY_ID, 1,
                                true, true, false, HOST_FEATURE_ID))),
                "expected exactly one anchor, found=0");
    }

    @Test
    public void acceptsLifecycleStageAfterFullscreenTaskCloses() {
        final SelfTestTaskStackInvariantAnalyzer analyzer = analyzer();
        final SelfTestTaskStackInvariantAnalyzer.Snapshot desktop =
                hostOnly(0, true);
        analyzer.begin("FULLSCREEN-LIFECYCLE-004", desktop);

        assertEquals(0, analyzer.finish(desktop).anomalies.length);
    }

    @Test
    public void rejectsMissingHostDuringFullscreenCreation() {
        final SelfTestTaskStackInvariantAnalyzer analyzer = analyzer();
        analyzer.begin("FULLSCREEN-LAUNCH", hostOnly(0, true));
        analyzer.sample("host-missing", snapshot(
                1,
                task(FIXTURE_TASK_ID, DISPLAY_ID, 1,
                        true, true, false)), true);

        assertContains(
                analyzer.finish(fullscreen(2, true, false)),
                "desktop host is missing");
    }

    @Test
    public void rejectsVisibilityGapDuringFullscreenCreation() {
        final SelfTestTaskStackInvariantAnalyzer analyzer = analyzer();
        analyzer.begin("FULLSCREEN-LAUNCH", hostOnly(0, true));
        analyzer.sample("gap", fullscreen(1, false, false), true);

        assertContains(
                analyzer.finish(fullscreen(2, true, false)),
                "no task is visible on the desktop display");
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
                task(20, DISPLAY_ID, 1, true, false, true),
                task(HOST_TASK_ID, DISPLAY_ID, 1, true, false, true),
                task(FIXTURE_TASK_ID, DISPLAY_ID, 5, true, true, false));
        analyzer.begin("WINDOW", snapshot);

        assertContains(analyzer.finish(snapshot), "Home task 20 became visible");
    }

    @Test
    public void acceptsHomeBelowDesktopContent() {
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

    @Test
    public void rejectsHomeAboveDesktopContent() {
        final SelfTestTaskStackInvariantAnalyzer analyzer =
                new SelfTestTaskStackInvariantAnalyzer(
                        0, HOST_TASK_ID, 0);
        final SelfTestTaskStackInvariantAnalyzer.Snapshot phoneHomeOnTop =
                snapshot(
                        0,
                        task(20, 0, 1,
                                true, false, true),
                        task(FIXTURE_TASK_ID, 0, 1,
                                true, true, false),
                        task(HOST_TASK_ID, 0, 1,
                                true, false, true));
        analyzer.begin("FULLSCREEN-BACK", phoneHomeOnTop);

        assertContains(
                analyzer.finish(phoneHomeOnTop),
                "Home task 20 became visible");
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

    private static SelfTestTaskStackInvariantAnalyzer.Snapshot hostOnly(
            final long time,
            final boolean hostVisible) {
        return snapshot(
                time,
                task(HOST_TASK_ID, DISPLAY_ID, 1,
                        hostVisible, false, false));
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

    private static SelfTestTaskStackInvariantAnalyzer.Snapshot fullscreenPair(
            final long time,
            final int firstMode,
            final boolean firstVisible,
            final int secondMode,
            final boolean secondVisible,
            final boolean hostVisible) {
        return snapshot(
                time,
                task(HOST_TASK_ID, DISPLAY_ID, 1,
                        hostVisible, false, false),
                task(FIXTURE_TASK_ID, DISPLAY_ID, firstMode,
                        firstVisible, true, false),
                task(SECOND_FIXTURE_TASK_ID, DISPLAY_ID, secondMode,
                        secondVisible, true, false));
    }

    private static SelfTestTaskStackInvariantAnalyzer.Snapshot
            fullscreenPairInTaskArea(final long time) {
        return snapshot(
                time,
                taskInArea(HOST_TASK_ID, DISPLAY_ID, 1,
                        false, false, false, HOST_FEATURE_ID),
                backstop(BACKSTOP_TASK_ID, FULLSCREEN_FEATURE_ID, false),
                taskInArea(FIXTURE_TASK_ID, DISPLAY_ID, 1,
                        true, true, false, FULLSCREEN_FEATURE_ID),
                backstop(SECOND_BACKSTOP_TASK_ID,
                        SECOND_FULLSCREEN_FEATURE_ID, false),
                taskInArea(SECOND_FIXTURE_TASK_ID, DISPLAY_ID, 1,
                        false, true, false,
                        SECOND_FULLSCREEN_FEATURE_ID));
    }

    private static SelfTestTaskStackInvariantAnalyzer.TaskState backstop(
            final int taskId,
            final int displayAreaFeatureId,
            final boolean visible) {
        return new SelfTestTaskStackInvariantAnalyzer.TaskState(
                taskId,
                DISPLAY_ID,
                1,
                visible,
                true,
                false,
                false,
                displayAreaFeatureId,
                true,
                TaskAreaBackstopRole.FULLSCREEN);
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
                home,
                SelfTestTaskStackInvariantAnalyzer
                        .DISPLAY_AREA_FEATURE_UNKNOWN);
    }

    private static SelfTestTaskStackInvariantAnalyzer.TaskState taskInArea(
            final int taskId,
            final int displayId,
            final int mode,
            final boolean visible,
            final boolean fixture,
            final boolean home,
            final int displayAreaFeatureId) {
        return new SelfTestTaskStackInvariantAnalyzer.TaskState(
                taskId,
                displayId,
                mode,
                visible,
                true,
                fixture,
                home,
                displayAreaFeatureId);
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
