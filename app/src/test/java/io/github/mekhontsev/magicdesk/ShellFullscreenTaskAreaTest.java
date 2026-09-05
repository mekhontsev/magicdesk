package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class ShellFullscreenTaskAreaTest {
    @Test
    public void activationRetainsFullscreenBackgroundThroughOwnershipAdapter()
            throws ReflectiveOperationException {
        final DesktopWorkspaceCommand command = DesktopWorkspaceCommand.create(
                DesktopWorkspaceCommand.ACTIVATE, 4, 21, new int[]{21});
        final int[] taskIds = topology().desktopFocusTasks(
                new TaskService(), 4, command.backToFrontTaskIds);
        assertArrayEquals(new int[]{21}, taskIds);
        final ShellFullscreenTaskPlanes.MixedStackOrder order =
                ShellFullscreenTaskPlanes.buildMixedStackOrder(
                        21, 10, taskIds, Collections.singleton(30),
                        Collections.emptyList(), 30, true);
        assertEquals(30, order.fullscreenTaskId);
        assertArrayEquals(new int[]{21}, order.freeformTaskIds);
    }

    @Test
    public void preservesExplicitWorkspaceWithoutAddingHome()
            throws ReflectiveOperationException {
        assertArrayEquals(
                new int[]{21, 22},
                topology().desktopFocusTasks(
                        new TaskService(), 4, new int[]{21, 22}));
    }

    @Test
    public void preservesExplicitConcealedAndVisiblePartition()
            throws ReflectiveOperationException {
        assertArrayEquals(
                new int[]{21, 10, 22},
                topology().desktopFocusTasks(
                        new TaskService(), 4, new int[]{21, 10, 22}));
    }

    private static ShellFullscreenTaskArea topology() {
        final ShellDesktopTaskOwnership ownership = new ShellDesktopTaskOwnership();
        ownership.configure(4);
        ownership.markDesktopHost(10);
        return new ShellFullscreenTaskArea(ownership, new ShellDesktopSurfaceOrder());
    }

    public static final class TaskService {
        public List<Task> getTasks(final int limit, final boolean filtered,
                final boolean keepExtras, final int displayId) {
            return Arrays.asList(new Task(21), new Task(22), new Task(10));
        }
    }

    public static final class Task {
        public final int taskId;
        public final int displayId = 4;
        public final Configuration configuration = new Configuration();

        Task(final int taskId) {
            this.taskId = taskId;
        }
    }

    public static final class Configuration {
        public final WindowConfiguration windowConfiguration = new WindowConfiguration();
    }

    public static final class WindowConfiguration {
        public int getActivityType() {
            return FrameworkTaskSnapshot.ACTIVITY_TYPE_STANDARD;
        }
    }
}
