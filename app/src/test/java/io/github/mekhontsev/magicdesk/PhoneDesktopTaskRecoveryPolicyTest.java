package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public final class PhoneDesktopTaskRecoveryPolicyTest {
    @Test
    public void userApplicationCanBeRecovered() {
        assertTrue(PhoneDesktopTaskRecovery.isRecoverable(
                "net.sf.golly", false));
    }

    @Test
    public void magicDeskTaskIsNotRecoveredAsUserApplication() {
        assertFalse(PhoneDesktopTaskRecovery.isRecoverable(
                "io.github.mekhontsev.magicdesk", false));
    }

    @Test
    public void magicDeskUiTaskIsRecovered() {
        assertTrue(PhoneDesktopTaskRecovery.isRecoverable(
                "io.github.mekhontsev.magicdesk",
                "io.github.mekhontsev.magicdesk/.DiagnosticsActivity",
                false));
    }

    @Test
    public void magicDeskDesktopHostIsNotRecoveredToPhone() {
        assertFalse(PhoneDesktopTaskRecovery.isRecoverable(
                "io.github.mekhontsev.magicdesk",
                "io.github.mekhontsev.magicdesk/.DesktopActivity",
                false));
        assertFalse(PhoneDesktopTaskRecovery.isRecoverable(
                "io.github.mekhontsev.magicdesk",
                "io.github.mekhontsev.magicdesk/"
                        + "io.github.mekhontsev.magicdesk.DesktopActivity",
                false));
    }

    @Test
    public void homeTaskIsNotRecovered() {
        assertFalse(PhoneDesktopTaskRecovery.isRecoverable(
                "com.zte.mifavor.launcher", true));
    }

    @Test
    public void cancellationAfterDiscoveryPreventsTaskMutation() {
        final FakeEnvironment environment = new FakeEnvironment(true);

        final PhoneDesktopTaskRecovery.Result result =
                PhoneDesktopTaskRecovery.recoverForTest(
                        () -> environment.commands.size() < 2,
                        environment);

        assertTrue(result.success);
        assertTrue(result.cancelled);
        assertEquals(2, environment.commands.size());
        assertFalse(environment.hasFullscreenTransition());
    }

    @Test
    public void liveFreeformTaskUsesNativeFullscreenTransition() {
        final FakeEnvironment environment = new FakeEnvironment(true);

        final PhoneDesktopTaskRecovery.Result result =
                PhoneDesktopTaskRecovery.recoverForTest(
                        () -> true, environment);

        assertTrue(result.success);
        assertFalse(result.cancelled);
        assertTrue(environment.hasFullscreenTransition());
        assertFalse(environment.hasInputKeyCombination());
        assertFalse(environment.freeform);
    }

    @Test
    public void missingRepositoryTaskIsRevivedBeforeTransition() {
        final FakeEnvironment environment = new FakeEnvironment(false);

        final PhoneDesktopTaskRecovery.Result result =
                PhoneDesktopTaskRecovery.recoverForTest(
                        () -> true, environment);

        assertTrue(result.success);
        assertTrue(environment.hasReviveCommand());
        assertTrue(environment.hasFullscreenTransition());
        assertTrue(environment.indexOfRevive()
                < environment.indexOfFullscreenTransition());
    }

    @Test
    public void revivedMagicDeskTaskIsExcludedFromRecovery() {
        final FakeEnvironment environment = new FakeEnvironment(false);
        environment.packageName = "io.github.mekhontsev.magicdesk";
        environment.componentName = ".DesktopActivity";

        final PhoneDesktopTaskRecovery.Result result =
                PhoneDesktopTaskRecovery.recoverForTest(
                        () -> true, environment);

        assertTrue(result.success);
        assertTrue(environment.hasReviveCommand());
        assertFalse(environment.hasFullscreenTransition());
    }

    @Test
    public void desktopHostRemainsExcludedWithSystemActivityOnTop() {
        final FakeEnvironment environment = new FakeEnvironment(true);
        environment.packageName = "io.github.mekhontsev.magicdesk";
        environment.componentName = ".DesktopActivity";
        environment.topActivityComponent =
                "com.android.settings/.Settings";

        final PhoneDesktopTaskRecovery.Result result =
                PhoneDesktopTaskRecovery.recoverForTest(
                        () -> true, environment);

        assertTrue(result.success);
        assertFalse(environment.hasFullscreenTransition());
    }

    @Test
    public void taskMigratedFromRemovedDisplayIsReconciled() {
        final FakeEnvironment environment = new FakeEnvironment(true);
        environment.freeform = false;
        environment.repositoryContainsTask = false;
        environment.removedRepositoryContainsTask = true;

        final PhoneDesktopTaskRecovery.Result result =
                PhoneDesktopTaskRecovery.recoverRemovedDisplayForTest(
                        95, () -> true, environment);

        assertTrue(result.success);
        assertTrue(environment.hasMoveToDeskCommand());
        assertTrue(environment.hasFullscreenTransition());
        assertFalse(environment.removedRepositoryContainsTask);
    }

    @Test
    public void android15RecoveryUsesLegacyDesktopCommand() {
        final FakeEnvironment environment = new FakeEnvironment(true);
        environment.freeform = false;
        environment.repositoryContainsTask = false;
        environment.removedRepositoryContainsTask = true;
        environment.desktopMoveAction = "moveToDesktop";

        final PhoneDesktopTaskRecovery.Result result =
                PhoneDesktopTaskRecovery.recoverRemovedDisplayForTest(
                        95, () -> true, environment);

        assertTrue(result.success);
        assertTrue(environment.hasDesktopMoveCommand("moveToDesktop"));
        assertFalse(environment.hasDesktopMoveCommand("moveTaskToDesk"));
    }

    @Test
    public void resumesAfterLateTaskMigrationFromRemovedDisplay() {
        final FakeEnvironment environment = new FakeEnvironment(false);
        environment.freeform = false;
        environment.repositoryContainsTask = false;
        environment.removedRepositoryContainsTask = true;
        environment.taskAppearsAfterStackReads = 2;

        final PhoneDesktopTaskRecovery.Result pending =
                PhoneDesktopTaskRecovery.recoverRemovedDisplayForTest(
                        95, () -> true, environment);

        assertTrue(pending.success);
        assertTrue(pending.pending);
        assertFalse(environment.hasMoveToDeskCommand());
        assertFalse(environment.hasFullscreenTransition());

        final PhoneDesktopTaskRecovery.Result result =
                PhoneDesktopTaskRecovery.recoverRemovedDisplayForTest(
                        95, () -> true, environment);

        assertTrue(result.success);
        assertFalse(result.pending);
        assertTrue(environment.stackReads >= 2);
        assertTrue(environment.hasMoveToDeskCommand());
        assertTrue(environment.hasFullscreenTransition());
        assertFalse(environment.hasReviveCommand());
    }

    @Test
    public void removedDisplaySettlementRequiresRepositoryOrPhoneTask() {
        final FakeEnvironment environment = new FakeEnvironment(false);
        environment.repositoryContainsTask = false;
        environment.removedRepositoryContainsTask = true;
        final String removedRepository = environment.repositoryOutput();
        assertFalse(PhoneDesktopTaskRecovery.removedDisplayTransitionSettled(
                "", removedRepository, 95));
        assertTrue(PhoneDesktopTaskRecovery.removedDisplayTransitionSettled(
                FakeEnvironment.stackOutput(false),
                removedRepository,
                95));
        environment.removedRepositoryContainsSecondTask = true;
        assertFalse(PhoneDesktopTaskRecovery.removedDisplayTransitionSettled(
                FakeEnvironment.stackOutput(false),
                environment.repositoryOutput(),
                95));
        environment.removedRepositoryContainsSecondTask = false;
        environment.removedRepositoryContainsTask = false;
        assertTrue(PhoneDesktopTaskRecovery.removedDisplayTransitionSettled(
                "",
                environment.repositoryOutput(),
                95));
    }

    @Test
    public void timeoutRevivesRepositoryTaskWithoutLiveTask() {
        final FakeEnvironment environment = new FakeEnvironment(false);
        environment.repositoryContainsTask = false;
        environment.removedRepositoryContainsTask = true;

        final PhoneDesktopTaskRecovery.Result result =
                PhoneDesktopTaskRecovery.recoverRemovedDisplayForTest(
                        95, true, () -> true, environment);

        assertTrue(result.success);
        assertFalse(result.pending);
        assertTrue(environment.hasFullscreenTransition());
        assertTrue(environment.hasReviveCommand());
        assertTrue(environment.indexOfRevive()
                < environment.indexOfFullscreenTransition());
    }

    @Test
    public void timeoutRecoversLiveTasksBeforeReportingUnavailableIds() {
        final FakeEnvironment environment = new FakeEnvironment(true);
        environment.freeform = false;
        environment.repositoryContainsTask = false;
        environment.removedRepositoryContainsTask = true;
        environment.removedRepositoryContainsSecondTask = true;
        environment.reviveMissingTask = false;

        final PhoneDesktopTaskRecovery.Result result =
                PhoneDesktopTaskRecovery.recoverRemovedDisplayForTest(
                        95, true, () -> true, environment);

        assertFalse(result.success);
        assertTrue(environment.hasMoveToDeskCommand());
        assertTrue(environment.hasFullscreenTransition());
        assertFalse(environment.removedRepositoryContainsTask);
        assertTrue(environment.removedRepositoryContainsSecondTask);
    }

    @Test
    public void unavailablePhoneTaskDoesNotBlockLiveTaskRecovery() {
        final FakeEnvironment environment = new FakeEnvironment(true);
        environment.phoneRepositoryContainsSecondTask = true;
        environment.reviveMissingTask = false;

        final PhoneDesktopTaskRecovery.Result result =
                PhoneDesktopTaskRecovery.recoverForTest(
                        () -> true, environment);

        assertTrue(result.success);
        assertTrue(environment.hasFullscreenTransition());
        assertFalse(environment.repositoryContainsTask);
        assertTrue(environment.phoneRepositoryContainsSecondTask);
        assertTrue(result.message.contains("unavailable=[43]"));
    }

    private static final class FakeEnvironment
            implements PhoneDesktopTaskRecovery.Environment {
        final List<String> commands = new ArrayList<>();
        boolean taskPresent;
        boolean freeform = true;
        boolean repositoryContainsTask = true;
        boolean phoneRepositoryContainsSecondTask;
        boolean removedRepositoryContainsTask;
        boolean removedRepositoryContainsSecondTask;
        boolean reviveMissingTask = true;
        String desktopMoveAction = "moveTaskToDesk";
        String packageName = "net.sf.golly";
        String componentName = ".MainActivity";
        String topActivityComponent;
        int stackReads;
        int taskAppearsAfterStackReads = -1;

        FakeEnvironment(final boolean taskPresent) {
            this.taskPresent = taskPresent;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public PhoneDesktopTaskRecovery.CommandResult run(
                final String command) {
            commands.add(command);
            if (command.contains("activity stack list")) {
                stackReads++;
                if (!taskPresent
                        && taskAppearsAfterStackReads > 0
                        && stackReads >= taskAppearsAfterStackReads) {
                    taskPresent = true;
                }
                return PhoneDesktopTaskRecovery.CommandResult.success(
                        taskPresent ? stackOutput() : "");
            }
            if (command.contains("dumpsys activity service")) {
                return PhoneDesktopTaskRecovery.CommandResult.success(
                        repositoryOutput());
            }
            if (command.contains("wmshell-passthrough help")) {
                return PhoneDesktopTaskRecovery.CommandResult.success(
                        "desktopmode " + desktopMoveAction + " <taskId>");
            }
            if (command.contains("PhoneDesktopTaskRecoveryCommand")) {
                if (!reviveMissingTask) {
                    return PhoneDesktopTaskRecovery.CommandResult.failure(
                            "phone-desktop-recovery unresolved=1");
                }
                taskPresent = true;
                freeform = true;
                return PhoneDesktopTaskRecovery.CommandResult.success(
                        "phone-desktop-recovery revived=1");
            }
            if (command.contains(
                    "TaskClientPreservingFullscreenTransitionCommand")) {
                freeform = false;
                repositoryContainsTask = false;
                removedRepositoryContainsTask = false;
                return PhoneDesktopTaskRecovery.CommandResult.success("");
            }
            if (command.contains(
                    "desktopmode " + desktopMoveAction)) {
                freeform = true;
                return PhoneDesktopTaskRecovery.CommandResult.success("");
            }
            return PhoneDesktopTaskRecovery.CommandResult.success("");
        }

        boolean hasReviveCommand() {
            return indexOfRevive() >= 0;
        }

        boolean hasFullscreenTransition() {
            return indexOfFullscreenTransition() >= 0;
        }

        boolean hasInputKeyCombination() {
            return findCommand("keycombination") >= 0;
        }

        boolean hasMoveToDeskCommand() {
            return findCommand("desktopmode moveTaskToDesk") >= 0;
        }

        boolean hasDesktopMoveCommand(final String action) {
            return findCommand("desktopmode " + action) >= 0;
        }

        int indexOfRevive() {
            return findCommand("PhoneDesktopTaskRecoveryCommand");
        }

        int indexOfFullscreenTransition() {
            return findCommand(
                    "TaskClientPreservingFullscreenTransitionCommand");
        }

        private int findCommand(final String text) {
            for (int index = 0; index < commands.size(); index++) {
                if (commands.get(index).contains(text)) {
                    return index;
                }
            }
            return -1;
        }

        private String stackOutput() {
            return stackOutput(
                    freeform,
                    packageName,
                    componentName,
                    topActivityComponent == null
                            ? packageName + "/" + componentName
                            : topActivityComponent);
        }

        private static String stackOutput(final boolean freeform) {
            return stackOutput(
                    freeform,
                    "net.sf.golly",
                    ".MainActivity",
                    "net.sf.golly/.MainActivity");
        }

        private static String stackOutput(
                final boolean freeform,
                final String packageName,
                final String componentName,
                final String topActivityComponent) {
            return "RootTask id=42 bounds=[0,0][1080,2400]"
                    + " displayId=0 userId=0\n"
                    + " configuration={mWindowingMode="
                    + (freeform ? "freeform" : "fullscreen")
                    + " mActivityType=standard}\n"
                    + " taskId=42: " + packageName + "/" + componentName + " "
                    + "topActivity=ComponentInfo{" + topActivityComponent + "} "
                    + "visible=true bounds=[100,100][800,1200]\n";
        }

        private String repositoryOutput() {
            final StringBuilder removedTasks = new StringBuilder();
            if (removedRepositoryContainsTask) {
                removedTasks.append("42");
            }
            if (removedRepositoryContainsSecondTask) {
                if (removedTasks.length() > 0) {
                    removedTasks.append(", ");
                }
                removedTasks.append("43");
            }
            return "DesktopUserRepositories:\n"
                    + "  currentUserId=0\n"
                    + "  DesktopRepository\n"
                    + "    userId=0\n"
                    + "    Display #0:\n"
                    + "      activeTasks=" + phoneTasks()
                    + "\n"
                    + "    Display #95:\n"
                    + "      activeTasks=[" + removedTasks + "]\n";
        }

        private String phoneTasks() {
            final StringBuilder tasks = new StringBuilder();
            if (repositoryContainsTask) {
                tasks.append("42");
            }
            if (phoneRepositoryContainsSecondTask) {
                if (tasks.length() > 0) {
                    tasks.append(", ");
                }
                tasks.append("43");
            }
            return "[" + tasks + "]";
        }
    }
}
