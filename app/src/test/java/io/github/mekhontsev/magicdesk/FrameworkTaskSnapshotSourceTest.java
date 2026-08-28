package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;

public final class FrameworkTaskSnapshotSourceTest {
    @Test
    public void rootHierarchyRestoresCrossParentZOrderAndRootIds()
            throws Exception {
        final FakeTask fullscreenPeer = new FakeTask(20);
        final FakeTask focusedWindow = new FakeTask(10);
        final FakeTask desktopHost = new FakeTask(31);

        final FrameworkTaskSnapshotSource.OrderedTasks ordered =
                FrameworkTaskSnapshotSource.orderByRootTaskHierarchy(
                        Arrays.asList(
                                fullscreenPeer,
                                focusedWindow,
                                desktopHost),
                        Arrays.asList(
                                new FakeRootTask(10, 10),
                                new FakeRootTask(20, 20),
                                new FakeRootTask(30, 31)));

        assertEquals(
                Arrays.asList(focusedWindow, fullscreenPeer, desktopHost),
                ordered.tasks);
        assertEquals(Integer.valueOf(10),
                ordered.rootTaskIds.get(Integer.valueOf(10)));
        assertEquals(Integer.valueOf(20),
                ordered.rootTaskIds.get(Integer.valueOf(20)));
        assertEquals(Integer.valueOf(30),
                ordered.rootTaskIds.get(Integer.valueOf(31)));
    }

    @Test
    public void tasksMissingFromRootSnapshotRetainBinderOrder() throws Exception {
        final FakeTask rooted = new FakeTask(10);
        final FakeTask transientTask = new FakeTask(40);

        final FrameworkTaskSnapshotSource.OrderedTasks ordered =
                FrameworkTaskSnapshotSource.orderByRootTaskHierarchy(
                        Arrays.asList(transientTask, rooted),
                        Arrays.asList(new FakeRootTask(10, 10)));

        assertEquals(Arrays.asList(rooted, transientTask), ordered.tasks);
        assertEquals(Integer.valueOf(40),
                ordered.rootTaskIds.get(Integer.valueOf(40)));
    }

    public static final class FakeTask {
        public final int taskId;

        FakeTask(final int taskId) {
            this.taskId = taskId;
        }
    }

    public static final class FakeRootTask {
        public final int taskId;
        public final int[] childTaskIds;

        FakeRootTask(final int taskId, final int... childTaskIds) {
            this.taskId = taskId;
            this.childTaskIds = childTaskIds;
        }
    }
}
