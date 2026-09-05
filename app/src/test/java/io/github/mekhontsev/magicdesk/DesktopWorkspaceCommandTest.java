package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DesktopWorkspaceCommandTest {
    @Test
    public void preservesSemanticOperationAndPhysicalPlan() {
        final int[] order = new int[]{10, 20, 30};
        final DesktopWorkspaceCommand command = DesktopWorkspaceCommand.create(
                DesktopWorkspaceCommand.DEMOTE, 4, 30, order);
        order[2] = 99;

        assertEquals(DesktopWorkspaceCommand.DEMOTE, command.operation);
        assertEquals(4, command.displayId);
        assertEquals(30, command.targetTaskId);
        assertArrayEquals(
                new int[]{10, 20, 30}, command.backToFrontTaskIds);
        assertEquals("demote", command.operationName());
        assertFalse(command.presentsDesktop());
    }

    @Test
    public void identifiesDesktopPresentationWithoutOverloadingDemote() {
        final DesktopWorkspaceCommand command = DesktopWorkspaceCommand.create(
                DesktopWorkspaceCommand.PRESENT_DESKTOP,
                7,
                12,
                new int[]{12});

        assertTrue(command.presentsDesktop());
        assertEquals("present-desktop", command.operationName());
    }

    @Test
    public void inputFocusIsRequiredOnlyForInteractiveActivation() {
        assertTrue(command(DesktopWorkspaceCommand.ACTIVATE)
                .requiresInputFocusCommit());
        assertTrue(command(DesktopWorkspaceCommand.DEMOTE)
                .requiresInputFocusCommit());
        assertTrue(command(DesktopWorkspaceCommand.PRESENT_DESKTOP)
                .requiresInputFocusCommit());
        assertTrue(command(DesktopWorkspaceCommand.RESTORE_WORKSPACE)
                .requiresInputFocusCommit());
        assertTrue(command(DesktopWorkspaceCommand.RESTORE_SESSION)
                .requiresInputFocusCommit());
        assertTrue(command(DesktopWorkspaceCommand.PRESENT_WORKSPACE)
                .requiresInputFocusCommit());
    }

    @Test
    public void namesDesktopWorkspacePresentationIndependently() {
        final DesktopWorkspaceCommand command = command(
                DesktopWorkspaceCommand.PRESENT_WORKSPACE);

        assertEquals("present-workspace", command.operationName());
        assertFalse(command.presentsDesktop());
    }

    @Test(expected = IllegalArgumentException.class)
    public void activationCannotRestoreAnImplicitWorkspace() {
        DesktopWorkspaceCommand.create(
                DesktopWorkspaceCommand.ACTIVATE,
                4, 20, new int[]{10, 20});
    }

    @Test
    public void workspaceRestoreCanRaiseSeveralExplicitTasks() {
        final DesktopWorkspaceCommand command = DesktopWorkspaceCommand.create(
                DesktopWorkspaceCommand.RESTORE_WORKSPACE,
                4, 20, new int[]{10, 20});
        assertArrayEquals(new int[]{10, 20}, command.backToFrontTaskIds);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsPlanWhoseTargetIsNotFrontmost() {
        DesktopWorkspaceCommand.create(
                DesktopWorkspaceCommand.ACTIVATE,
                4,
                20,
                new int[]{10, 20, 30});
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsDuplicatePhysicalTasks() {
        DesktopWorkspaceCommand.create(
                DesktopWorkspaceCommand.RESTORE_WORKSPACE,
                4,
                20,
                new int[]{10, 20, 20});
    }

    private static DesktopWorkspaceCommand command(final int operation) {
        return DesktopWorkspaceCommand.create(
                operation, 4, 20, new int[]{20});
    }
}
