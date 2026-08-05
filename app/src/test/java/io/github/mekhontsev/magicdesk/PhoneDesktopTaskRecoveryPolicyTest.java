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
        assertFalse(environment.hasFocusCommand());
        assertFalse(environment.hasFullscreenGesture());
    }

    @Test
    public void liveFreeformTaskUsesNativeFullscreenTransition() {
        final FakeEnvironment environment = new FakeEnvironment(true);

        final PhoneDesktopTaskRecovery.Result result =
                PhoneDesktopTaskRecovery.recoverForTest(
                        -1, () -> true, environment);

        assertTrue(result.success);
        assertFalse(result.cancelled);
        assertTrue(environment.hasFocusCommand());
        assertTrue(environment.hasFullscreenGesture());
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
        assertTrue(environment.hasFocusCommand());
        assertTrue(environment.hasFullscreenGesture());
        assertTrue(environment.indexOfRevive()
                < environment.indexOfFocus());
    }

    private static final class FakeEnvironment
            implements PhoneDesktopTaskRecovery.Environment {
        final List<String> commands = new ArrayList<>();
        boolean taskPresent;
        boolean freeform = true;
        boolean repositoryContainsTask = true;

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
            if (command.contains("keycombination")) {
                freeform = false;
                repositoryContainsTask = false;
                return PhoneDesktopTaskRecovery.CommandResult.success("");
            }
            return PhoneDesktopTaskRecovery.CommandResult.success("");
        }

        boolean hasReviveCommand() {
            return indexOfRevive() >= 0;
        }

        boolean hasFocusCommand() {
            return indexOfFocus() >= 0;
        }

        boolean hasFullscreenGesture() {
            return findCommand("keycombination") >= 0;
        }

        int indexOfRevive() {
            return findCommand("PhoneDesktopTaskRecoveryCommand");
        }

        int indexOfFocus() {
            return findCommand("task focus 42");
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
                    + "\n";
        }
    }
}
