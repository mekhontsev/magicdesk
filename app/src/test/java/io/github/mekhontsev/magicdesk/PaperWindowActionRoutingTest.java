package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class PaperWindowActionRoutingTest {
    @Test public void typedActionsKeepTilingSeparateFromNativeFullscreen() throws Exception {
        RuntimeSourceFixture.verify(
                RuntimeSourceFixture.nestedClass("DesktopWindowAction", "DesktopWindowAction")
                + RuntimeSourceFixture.methods("DesktopTaskController",
                        "handleActiveTaskShortcutInternal", "applyNativeTaskShortcut")
                + """
            static class DesktopPaperWindowController {
            """
                + RuntimeSourceFixture.nestedClass("DesktopPaperWindowController", "Command")
                + RuntimeSourceFixture.methods("DesktopPaperWindowController", "commandFor")
                + """
                boolean owns(int id) { return id == 42; }
            }
            static class TaskRepository {
                static class TaskEntry {
                    int taskId=42;
                    boolean freeform=true;
                    boolean isBoundedFreeform() { return freeform; }
                }
            }
            int mDisplayId=3, nativeSelections, nativeActions, layoutActions;
            int selectedTask;
            DesktopWindowAction nativeAction;
            DesktopPaperWindowController.Command layoutAction;
            DesktopPaperWindowController policy=new DesktopPaperWindowController();
            DesktopPaperWindowController paperPolicy() { return policy; }
            class Transitions {
                void applyAction(TaskRepository.TaskEntry task,DesktopWindowAction action) {
                    nativeActions++; nativeAction=action;
                }
            }
            final Transitions mWindowTransitions=new Transitions();
            void recordFocusEvent(String event,int display,int task,boolean success,String detail) {}
            void handleNativeTaskShortcut(DesktopWindowAction action,int task) {
                nativeSelections++; nativeAction=action; selectedTask=task;
            }
            void arrangePaperWindows(DesktopPaperWindowController.Command action,int task,Object callback) {
                layoutActions++; layoutAction=action; selectedTask=task;
            }
            public static void verify() {
                Fixture f=new Fixture();
                f.handleActiveTaskShortcutInternal(DesktopWindowAction.PAPER_TOGGLE,-1);
                check(f.layoutActions==1 && f.selectedTask==-1,
                        "tiling toggle must work without an active application");
                check(f.layoutAction==DesktopPaperWindowController.Command.TOGGLE && f.nativeSelections==0,
                        "tiling must use its typed layout command");
                f.handleActiveTaskShortcutInternal(DesktopWindowAction.FULLSCREEN,42);
                check(f.nativeSelections==1 && f.nativeAction==DesktopWindowAction.FULLSCREEN,
                        "fullscreen must retain the native target-selection path");
                TaskRepository.TaskEntry task=new TaskRepository.TaskEntry();
                f.applyNativeTaskShortcut(DesktopWindowAction.MAXIMIZE,task);
                check(f.layoutAction==DesktopPaperWindowController.Command.MAXIMIZE && f.nativeActions==0,
                        "tiled maximize must remain column maximization");
                f.applyNativeTaskShortcut(DesktopWindowAction.RESTORE,task);
                check(f.layoutAction==DesktopPaperWindowController.Command.UNMAXIMIZE,
                        "tiled restore must restore column width");
                int layouts=f.layoutActions;
                task.freeform=false;
                f.applyNativeTaskShortcut(DesktopWindowAction.RESTORE,task);
                check(f.nativeActions==1 && f.nativeAction==DesktopWindowAction.RESTORE && f.layoutActions==layouts,
                        "a suspended tiled fullscreen task must restore through upstream fullscreen handling");
                task.freeform=true; task.taskId=99;
                f.applyNativeTaskShortcut(DesktopWindowAction.MAXIMIZE,task);
                check(f.nativeActions==2 && f.layoutActions==layouts,"floating windows use native maximize");
                f.policy=null;
                task.taskId=42;
                f.applyNativeTaskShortcut(DesktopWindowAction.MAXIMIZE,task);
                check(f.nativeActions==3 && f.layoutActions==layouts,"disabled tiling preserves native actions");
            }
            """);
    }
}
