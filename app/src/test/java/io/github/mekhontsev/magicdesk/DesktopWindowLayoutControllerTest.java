package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;

import android.graphics.Rect;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class DesktopWindowLayoutControllerTest {
    private static final WindowLayoutPolicy.Bounds ORIGINAL = new WindowLayoutPolicy.Bounds(30, 40, 700, 600);
    private static final WindowLayoutPolicy.Bounds TILE = new WindowLayoutPolicy.Bounds(0, 0, 600, 800);
    private static final Rect AREA = DesktopWindowLayoutController.rect(new WindowLayoutPolicy.Bounds(0, 0, 1200, 800));
    private final DesktopTaskRuntimeRegistry states = new DesktopTaskRuntimeRegistry();
    private final List<TaskRepository.ActionCallback> submissions = new ArrayList<>();
    private final List<Boolean> results = new ArrayList<>();
    private final List<List<TaskRepository.TaskEntry>> batches = new ArrayList<>();
    private boolean current = true;
    private final DesktopWindowLayoutController controller = new DesktopWindowLayoutController(
            states, Runnable::run, (display, tasks, bounds, callback) -> {
                assertEquals(3, display);
                batches.add(tasks);
                submissions.add(callback);
            }, new FloatingWindowLayoutPolicy());

    @Test public void defaultResizeUsesExecutorAndReleasesPendingStateAtAcknowledgement() {
        execute(List.of(task(10, "freeform")), WindowLayoutPolicy.Retention.FLOATING);
        assertEquals(1, submissions.size());
        assertNotNull(states.state(10).boundsTransition());
        assertTrue(states.state(10).isWindowedTransition());
        assertTrue(results.isEmpty());
        submissions.get(0).onComplete(new TaskRepository.ActionResult(true, "submitted"));
        assertEquals(List.of(true), results);
        assertNull(states.state(10).boundsTransition());
        assertFalse(states.state(10).isWindowedTransition());
        // No observed geometry was fabricated by successful submission.
        assertNull(states.state(10).lastWindowBounds());
    }

    @Test public void targetObservationDoesNotEndRelayoutProtectionBeforeCommandCompletion() {
        execute(List.of(task(10, "freeform")), WindowLayoutPolicy.Retention.ARRANGEMENT);
        var state = states.state(10);
        state.clearBoundsTransition(state.boundsTransition());
        assertTrue(state.isWindowedTransition());
        submissions.get(0).onComplete(new TaskRepository.ActionResult(true, "complete"));
        assertFalse(state.isWindowedTransition());
    }

    @Test public void oldCommandCompletionDoesNotReleaseNewRelayoutProtection() {
        execute(List.of(task(10, "freeform")), WindowLayoutPolicy.Retention.ARRANGEMENT);
        execute(List.of(task(10, "freeform")), WindowLayoutPolicy.Retention.ARRANGEMENT);
        submissions.get(0).onComplete(new TaskRepository.ActionResult(false, "old"));
        assertTrue(states.state(10).isWindowedTransition());
        submissions.get(1).onComplete(new TaskRepository.ActionResult(false, "declined"));
        assertFalse(states.state(10).isWindowedTransition());
    }

    @Test public void batchFailureRestoresAllGeometryOwnership() {
        execute(List.of(task(10, "freeform"), task(20, "freeform")), WindowLayoutPolicy.Retention.LAYOUT);
        assertEquals(2, batches.get(0).size());
        assertTrue(states.state(10).layoutOwnership().isOwned());
        submissions.get(0).onComplete(new TaskRepository.ActionResult(false, "declined"));
        for (int id : new int[] {10, 20}) {
            assertFalse(states.state(id).layoutOwnership().isOwned());
            assertNull(states.state(id).boundsTransition());
        }
        assertEquals(List.of(false), results);
    }

    @Test public void releaseKeepsOriginalCoordinatesUntilBackendAcknowledges() {
        List<TaskRepository.TaskEntry> tasks = List.of(task(10, "freeform"));
        execute(tasks, WindowLayoutPolicy.Retention.LAYOUT);
        submissions.get(0).onComplete(new TaskRepository.ActionResult(true, "submitted"));
        assertEquals(ORIGINAL, DesktopWindowLayoutController.bounds(controller.floatingBounds(tasks).get(10)));
        var workspace = controller.workspace(3, AREA, tasks, Set.of(), 10);
        var plan = controller.plan(workspace, WindowLayoutPolicy.Request.release());
        assertEquals(ORIGINAL, plan.placements().get(0).bounds());
        controller.execute(workspace, plan, tasks, () -> current, result -> results.add(result.success));
        assertTrue(states.state(10).layoutOwnership().isOwned());
        submissions.get(1).onComplete(new TaskRepository.ActionResult(true, "submitted"));
        assertFalse(states.state(10).layoutOwnership().isOwned());
    }

    @Test public void validationIsAtomicAndCannotPullFullscreenBackIntoFreeform() {
        List<TaskRepository.TaskEntry> tasks = List.of(task(10, "freeform"), task(20, "fullscreen"));
        assertThrows(IllegalArgumentException.class, () -> execute(tasks, WindowLayoutPolicy.Retention.LAYOUT));
        assertTrue(submissions.isEmpty());
        assertNull(states.find(10));
    }

    @Test public void cancelledSessionAndDuplicateCallbackCannotAcknowledgeNewWork() {
        execute(List.of(task(10, "freeform")), WindowLayoutPolicy.Retention.LAYOUT);
        current = false;
        states.clear();
        submissions.get(0).onComplete(new TaskRepository.ActionResult(true, "late"));
        submissions.get(0).onComplete(new TaskRepository.ActionResult(true, "duplicate"));
        assertEquals(List.of(false), results);
        assertNull(states.find(10));
    }

    @Test public void policyReplacementPreservesFullscreenResidencyAndRestoresItsFloatingDestination() {
        states.state(10).layoutOwnership().acquire(ORIGINAL, TILE);
        states.state(10).setFullscreenRestoreBounds(DesktopWindowLayoutController.rect(TILE));
        controller.replacePolicy(new FloatingWindowLayoutPolicy());
        assertEquals(ORIGINAL, DesktopWindowLayoutController.bounds(states.state(10).fullscreenRestoreBounds()));
        assertFalse(states.state(10).layoutOwnership().isOwned());
    }

    @Test public void policyContinuationCommitsOnlyAfterSuccessfulSubmission() {
        WindowLayoutPolicy next = (workspace, request) -> WindowLayoutPolicy.Plan.EMPTY;
        var tasks = List.of(task(10, "freeform"));
        var workspace = controller.workspace(3, AREA, tasks, Set.of(), 10);
        var plan = new WindowLayoutPolicy.Plan(List.of(new WindowLayoutPolicy.Placement(
                10, TILE, WindowLayoutPolicy.Retention.LAYOUT)), next);
        controller.execute(workspace, plan, tasks, () -> current, result -> results.add(result.success));
        assertFalse(controller.observesWorkspace());
        submissions.get(0).onComplete(new TaskRepository.ActionResult(false, "rejected"));
        assertFalse(controller.observesWorkspace());
        controller.execute(workspace, plan, tasks, () -> current, result -> results.add(result.success));
        submissions.get(1).onComplete(new TaskRepository.ActionResult(true, "submitted"));
        assertTrue(controller.observesWorkspace());
    }

    @Test public void metadataOnlyPlanDoesNotSubmitAnEmptyAndroidTransaction() {
        WindowLayoutPolicy next = (workspace, request) -> WindowLayoutPolicy.Plan.EMPTY;
        var tasks = List.of(task(10, "freeform"));
        var workspace = controller.workspace(3, AREA, tasks, Set.of(), 10);
        var plan = new WindowLayoutPolicy.Plan(List.of(), next);
        assertFalse(plan.isEmpty());
        controller.execute(workspace, plan, tasks, () -> current, result -> results.add(result.success));
        assertTrue(submissions.isEmpty());
        assertTrue(controller.observesWorkspace());
    }

    @Test public void cancelledMetadataOnlyPlanCannotCommitContinuation() {
        var tasks = List.of(task(10, "freeform"));
        var workspace = controller.workspace(3, AREA, tasks, Set.of(), 10);
        var plan = new WindowLayoutPolicy.Plan(List.of(),
                (nextWorkspace, request) -> WindowLayoutPolicy.Plan.EMPTY);
        current = false;
        controller.execute(workspace, plan, tasks, () -> current, result -> results.add(result.success));
        assertEquals(List.of(false), results);
        assertFalse(controller.observesWorkspace());
        assertTrue(submissions.isEmpty());
    }

    @Test public void lateCallbackCannotCommitContinuationForAReusedTaskId() {
        var tasks = List.of(task(10, "freeform"));
        var workspace = controller.workspace(3, AREA, tasks, Set.of(), 10);
        var plan = new WindowLayoutPolicy.Plan(List.of(new WindowLayoutPolicy.Placement(
                10, TILE, WindowLayoutPolicy.Retention.LAYOUT)),
                (nextWorkspace, request) -> WindowLayoutPolicy.Plan.EMPTY);
        controller.execute(workspace, plan, tasks, () -> current, result -> results.add(result.success));
        var previous = states.state(10);
        states.clear();
        var replacement = states.state(10);
        submissions.get(0).onComplete(new TaskRepository.ActionResult(true, "late"));
        assertEquals(List.of(false), results);
        assertNotSame(previous, replacement);
        assertFalse(replacement.layoutOwnership().isOwned());
        assertNull(replacement.boundsTransition());
        assertFalse(controller.observesWorkspace());
    }

    @Test public void duplicateCallbackCannotRestoreAReplacedPolicy() {
        var tasks = List.of(task(10, "freeform"));
        var workspace = controller.workspace(3, AREA, tasks, Set.of(), 10);
        var plan = new WindowLayoutPolicy.Plan(List.of(new WindowLayoutPolicy.Placement(
                10, TILE, WindowLayoutPolicy.Retention.LAYOUT)),
                (nextWorkspace, request) -> WindowLayoutPolicy.Plan.EMPTY);
        controller.execute(workspace, plan, tasks, () -> current, result -> results.add(result.success));
        submissions.get(0).onComplete(new TaskRepository.ActionResult(true, "submitted"));
        assertTrue(controller.observesWorkspace());
        controller.replacePolicy(new FloatingWindowLayoutPolicy());
        submissions.get(0).onComplete(new TaskRepository.ActionResult(true, "duplicate"));
        assertFalse(controller.observesWorkspace());
        assertEquals(List.of(true), results);
        assertFalse(states.state(10).layoutOwnership().isOwned());
    }

    @Test public void releasingLayoutRetainsNativeMaximizeRestoreHistory() {
        var tasks = List.of(task(10, "freeform"));
        states.state(10).setWindowRestoreBounds(DesktopWindowLayoutController.rect(ORIGINAL));
        execute(tasks, WindowLayoutPolicy.Retention.LAYOUT);
        submissions.get(0).onComplete(new TaskRepository.ActionResult(true, "submitted"));
        var workspace = controller.workspace(3, AREA, tasks, Set.of(), 10);
        controller.execute(workspace, controller.plan(workspace, WindowLayoutPolicy.Request.release()),
                tasks, () -> current, result -> results.add(result.success));
        submissions.get(1).onComplete(new TaskRepository.ActionResult(true, "submitted"));
        assertEquals(ORIGINAL, DesktopWindowLayoutController.bounds(states.state(10).windowRestoreBounds()));
    }

    @Test public void revealPlanRunsBeforeActivationInTheSameWorkspaceOperation() {
        List<String> order = new ArrayList<>();
        DesktopWorkspaceQueue queue = new DesktopWorkspaceQueue(Runnable::run);
        var policy = (WindowLayoutPolicy) (workspace, request) -> {
            assertEquals(WindowLayoutPolicy.Event.REVEAL, request.event());
            return new WindowLayoutPolicy.Plan(List.of(new WindowLayoutPolicy.Placement(
                    request.taskId(), TILE, WindowLayoutPolicy.Retention.LAYOUT)));
        };
        var layout = new DesktopWindowLayoutController(states, Runnable::run,
                (display, tasks, bounds, callback) -> { order.add("geometry"); submissions.add(callback); }, policy);
        var tasks = List.of(task(10, "freeform"));
        queue.enqueue(completion -> {
            var workspace = layout.workspace(3, AREA, tasks, Set.of(), 10);
            layout.execute(workspace, layout.plan(workspace, WindowLayoutPolicy.Request.reveal(10)),
                    tasks, completion::isCurrent, result -> {
                        if (!completion.isCurrent()) return;
                        if (result.success) order.add("activate");
                        completion.onComplete(result);
                    });
        }, result -> order.add("done"));
        queue.enqueue(completion -> { order.add("next"); completion.onComplete(new TaskRepository.ActionResult(true, "")); }, null);
        assertEquals(List.of("geometry"), order);
        submissions.get(0).onComplete(new TaskRepository.ActionResult(true, "submitted"));
        assertEquals(List.of("geometry", "activate", "done", "next"), order);
    }

    private void execute(List<TaskRepository.TaskEntry> tasks, WindowLayoutPolicy.Retention retention) {
        var workspace = controller.workspace(3, AREA, tasks, Set.of(), 10);
        var plan = new WindowLayoutPolicy.Plan(tasks.stream().map(task ->
                new WindowLayoutPolicy.Placement(task.taskId, TILE, retention)).toList());
        controller.execute(workspace, plan, tasks, () -> current, result -> results.add(result.success));
    }

    private static TaskRepository.TaskEntry task(int id, String mode) {
        var task = new TaskRepository.TaskEntry(id, id, 3, "example.app", "example.app/.Main",
                "example.app/.Main", mode, null, false, true, true);
        task.bounds.left = ORIGINAL.left(); task.bounds.top = ORIGINAL.top();
        task.bounds.right = ORIGINAL.right(); task.bounds.bottom = ORIGINAL.bottom();
        return task;
    }
}
