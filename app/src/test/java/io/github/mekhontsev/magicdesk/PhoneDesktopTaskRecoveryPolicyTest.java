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
    public void homeTaskIsNotRecovered() {
        assertFalse(PhoneDesktopTaskRecovery.isRecoverable(
                "com.zte.mifavor.launcher", true));
    }

    @Test
    public void cancellationAfterDiscoveryPreventsTaskMutation() {
        final FakeEnvironment environment = new FakeEnvironment(true);

        final PhoneDesktopTaskRecovery.Result result =
                PhoneDesktopTaskRecovery.recoverForTest(
                        -1,
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
                        -1, () -> true, environment);

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
                        -1, () -> true, environment);

        assertTrue(result.success);
        assertTrue(environment.hasReviveCommand());
        assertTrue(environment.hasFullscreenTransition());
        assertTrue(environment.indexOfRevive()
                < environment.indexOfFullscreenTransition());
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
    public void timeoutIgnoresRepositoryIdsWithoutLiveTasks() {
        final FakeEnvironment environment = new FakeEnvironment(false);
        environment.repositoryContainsTask = false;
        environment.removedRepositoryContainsTask = true;

        final PhoneDesktopTaskRecovery.Result result =
                PhoneDesktopTaskRecovery.recoverRemovedDisplayForTest(
                        95, true, () -> true, environment);

        assertTrue(result.success);
        assertFalse(result.pending);
        assertFalse(environment.hasMoveToDeskCommand());
        assertFalse(environment.hasFullscreenTransition());
        assertFalse(environment.hasReviveCommand());
    }

    private static final class FakeEnvironment
            implements PhoneDesktopTaskRecovery.Environment {
        final List<String> commands = new ArrayList<>();
        boolean taskPresent;
        boolean freeform = true;
        boolean repositoryContainsTask = true;
        boolean removedRepositoryContainsTask;
        boolean removedRepositoryContainsSecondTask;
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
            if (command.contains("PhoneDesktopTaskRecoveryCommand")) {
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
            if (command.contains("desktopmode moveTaskToDesk")) {
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
            return stackOutput(freeform);
        }

        private static String stackOutput(final boolean freeform) {
            return "RootTask id=42 bounds=[0,0][1080,2400]"
                    + " displayId=0 userId=0\n"
                    + " configuration={mWindowingMode="
                    + (freeform ? "freeform" : "fullscreen")
                    + " mActivityType=standard}\n"
                    + " taskId=42: net.sf.golly/.MainActivity "
                    + "topActivity=ComponentInfo{net.sf.golly/.MainActivity} "
                    + "visible=true bounds=[100,100][800,1200]\n";
        }

        private String repositoryOutput() {
            return "DesktopUserRepositories:\n"
                    + "  currentUserId=0\n"
                    + "  DesktopRepository\n"
                    + "    userId=0\n"
                    + "    Display #0:\n"
                    + "      activeTasks="
                    + (repositoryContainsTask ? "[42]" : "[]")
                    + "\n"
                    + "    Display #95:\n"
                    + "      activeTasks="
                    + (removedRepositoryContainsTask
                            ? (removedRepositoryContainsSecondTask
                                    ? "[42, 43]" : "[42]")
                            : "[]")
                    + "\n";
        }
    }
}
