package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.List;

public final class WindowLayoutSessionTest {
    private static final WindowLayoutPolicy.Bounds AREA = new WindowLayoutPolicy.Bounds(0, 0, 1200, 800);
    private static final WindowLayoutPolicy.Bounds FLOATING = new WindowLayoutPolicy.Bounds(30, 40, 700, 600);
    private static final WindowLayoutPolicy.Bounds TILE = new WindowLayoutPolicy.Bounds(0, 0, 600, 800);
    private static final WindowLayoutPolicy.Workspace WORKSPACE = new WindowLayoutPolicy.Workspace(
            3, AREA, List.of(new WindowLayoutPolicy.Window(10, true, false, TILE, FLOATING)), 10);

    @Test public void observationDeduplicationUsesValueEqualityAndCanBeCleared() {
        var session = new WindowLayoutSession(new FloatingWindowLayoutPolicy());
        assertFalse(session.observesWorkspace());
        assertTrue(session.acceptObservation(WORKSPACE));
        assertFalse(session.acceptObservation(new WindowLayoutPolicy.Workspace(
                3, AREA, WORKSPACE.windows(), 10)));
        assertTrue(session.acceptObservation(new WindowLayoutPolicy.Workspace(
                3, AREA, WORKSPACE.windows(), -1)));
        session.clearObservation();
        assertTrue(session.acceptObservation(WORKSPACE));
    }

    @Test public void rejectedPlanStillConsumesTheObservation() {
        var session = new WindowLayoutSession((workspace, request) -> {
            throw new IllegalArgumentException("invalid plan");
        });
        assertTrue(session.acceptObservation(WORKSPACE));
        assertThrows(IllegalArgumentException.class,
                () -> session.plan(WORKSPACE, WindowLayoutPolicy.Request.observe()));
        assertFalse(session.acceptObservation(WORKSPACE));
    }

    @Test public void planningDoesNotCommitContinuationOrResetObservation() {
        WindowLayoutPolicy next = new FloatingWindowLayoutPolicy();
        var candidate = new WindowLayoutPolicy.Plan(List.of(), next);
        var session = new WindowLayoutSession((workspace, request) -> candidate);
        assertTrue(session.acceptObservation(WORKSPACE));
        WindowLayoutPolicy original = session.policy();
        assertSame(candidate, session.plan(WORKSPACE, WindowLayoutPolicy.Request.observe()));
        assertSame(original, session.policy());
        assertTrue(session.observesWorkspace());
        session.acknowledge(candidate);
        assertSame(next, session.policy());
        assertFalse(session.observesWorkspace());
        assertFalse(session.acceptObservation(WORKSPACE));
        session.acknowledge(WindowLayoutPolicy.Plan.EMPTY);
        assertFalse(session.observesWorkspace());
    }

    @Test public void replacementClearsObservationAndSelectsTheNewPolicy() {
        var session = new WindowLayoutSession(new FloatingWindowLayoutPolicy());
        session.acceptObservation(WORKSPACE);
        WindowLayoutPolicy next = (workspace, request) -> WindowLayoutPolicy.Plan.EMPTY;
        session.replacePolicy(next);
        assertSame(next, session.policy());
        assertTrue(session.observesWorkspace());
        assertTrue(session.acceptObservation(WORKSPACE));
        assertSame(WindowLayoutPolicy.Plan.EMPTY,
                session.plan(WORKSPACE, WindowLayoutPolicy.Request.observe()));
    }

    @Test public void releaseNeverCallsTheSelectedPolicy() {
        var session = new WindowLayoutSession((workspace, request) -> {
            throw new AssertionError("release called the selected policy");
        });
        var plan = session.plan(WORKSPACE, WindowLayoutPolicy.Request.release());
        assertEquals(List.of(new WindowLayoutPolicy.Placement(
                10, FLOATING, WindowLayoutPolicy.Retention.RELEASE_LAYOUT)), plan.placements());
        assertNull(plan.continuation());
    }

    @Test public void validatesPolicyResultsBeforeReturningThemToTheExecutor() {
        var session = new WindowLayoutSession((workspace, request) ->
                new WindowLayoutPolicy.Plan(List.of(new WindowLayoutPolicy.Placement(
                        999, TILE, WindowLayoutPolicy.Retention.LAYOUT))));
        assertThrows(IllegalArgumentException.class,
                () -> session.plan(WORKSPACE, WindowLayoutPolicy.Request.observe()));
    }
}
