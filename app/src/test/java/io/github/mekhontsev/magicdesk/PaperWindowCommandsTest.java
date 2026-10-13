package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class PaperWindowCommandsTest {
    @Test public void minimizationUsesHomeOrderAndExplicitConcealmentNotOcclusion() throws Exception {
        RuntimeSourceFixture.verify("""
            static class TaskRepository {
                record TaskEntry(int taskId, boolean home, boolean freeform, boolean visible) {
                    boolean isFreeform() { return freeform; }
                }
            }
            final Set<Integer> mTaskbarConcealedTaskIds = new HashSet<>(Set.of(5));
            static boolean isDesktopHostTask(TaskRepository.TaskEntry task) { return task.home; }
            public static void verify() {
                var f = new Fixture();
                var occluded = new TaskRepository.TaskEntry(1,false,true,false);
                var home = new TaskRepository.TaskEntry(2,true,false,true);
                var minimized = new TaskRepository.TaskEntry(3,false,true,false);
                var fullscreenPlane = new TaskRepository.TaskEntry(4,false,false,false);
                check(f.paperConcealedTasks(List.of(occluded,home,minimized,fullscreenPlane))
                        .equals(Set.of(3,5)), "occlusion or fullscreen plane mistaken for minimization");
                check(f.paperConcealedTasks(List.of(occluded,minimized)).equals(Set.of(5)),
                        "missing HOME invented a minimization boundary");
            }
            """ + RuntimeSourceFixture.methods("DesktopTaskController", "paperConcealedTasks"));
    }

}
