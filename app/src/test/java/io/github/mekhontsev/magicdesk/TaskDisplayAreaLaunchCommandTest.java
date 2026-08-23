package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import org.junit.Test;

public final class TaskDisplayAreaLaunchCommandTest {
    @Test
    public void preservesIndependentDocumentLaunch() {
        final int originalFlags = Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                | Intent.FLAG_ACTIVITY_MULTIPLE_TASK;

        assertEquals(
                Intent.FLAG_ACTIVITY_NEW_TASK,
                TaskDisplayAreaLaunchCommand.additionalLaunchFlags(
                        originalFlags));
    }

    @Test
    public void reusesNormalApplicationTask() {
        assertEquals(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                TaskDisplayAreaLaunchCommand.additionalLaunchFlags(0));
    }

    @Test
    public void requestedBoundsAcceptPlatformMinimumSizeExpansion() {
        assertTrue(TaskDisplayAreaLaunchCommand.satisfiesRequestedBounds(
                30, 735, 745, 2420,
                30, 735, 638, 2420));
        assertTrue(TaskDisplayAreaLaunchCommand.satisfiesRequestedBounds(
                20, 700, 638, 2450,
                30, 735, 638, 2420));
    }

    @Test
    public void requestedBoundsRejectUnrelatedOrSmallerPlacement() {
        assertFalse(TaskDisplayAreaLaunchCommand.satisfiesRequestedBounds(
                31, 735, 745, 2420,
                30, 735, 638, 2420));
        assertFalse(TaskDisplayAreaLaunchCommand.satisfiesRequestedBounds(
                30, 735, 637, 2420,
                30, 735, 638, 2420));
        assertFalse(TaskDisplayAreaLaunchCommand.satisfiesRequestedBounds(
                0, 0, 0, 0,
                30, 735, 638, 2420));
    }

    @Test
    public void launchFailureContextIdentifiesRequestedTransition() {
        assertEquals(
                "operation=app, targetDisplay=6, bounds=[20,30][800,900]",
                TaskDisplayAreaLaunchCommand.transitionContext(new String[]{
                    "app", "6", "intent:#Intent;end",
                    "20", "30", "800", "900"
                }));
        assertEquals(
                "operation=move-root-observed, task=42, rootTask=17, "
                        + "sourceDisplay=0, targetDisplay=6, "
                        + "bounds=[20,30][800,900]",
                TaskDisplayAreaLaunchCommand.transitionContext(new String[]{
                    "move-root-observed", "42", "17", "0", "6",
                    "20", "30", "800", "900",
                    "6", "100", "200", "ff112233"
                }));
    }

    @Test
    public void launchFailureIncludesEveryCauseTypeAndMessage() {
        final IllegalArgumentException root =
                new IllegalArgumentException("invalid transaction");
        final ReflectiveOperationException outer =
                new ReflectiveOperationException("WCT invocation failed", root);

        assertEquals(
                "java.lang.ReflectiveOperationException: WCT invocation failed"
                        + " -> java.lang.IllegalArgumentException: "
                        + "invalid transaction",
                TaskDisplayAreaLaunchCommand.causeChain(outer));
    }
}
