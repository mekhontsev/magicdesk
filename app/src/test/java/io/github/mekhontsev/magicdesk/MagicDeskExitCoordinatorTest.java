package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public final class MagicDeskExitCoordinatorTest {
    @Test
    public void everyCleanupFailureStillReachesFinish() {
        final FakeOperations operations = new FakeOperations(false);
        final Set<MagicDeskExitCoordinator.Step> failures = EnumSet.noneOf(
                MagicDeskExitCoordinator.Step.class);

        new MagicDeskExitCoordinator(
                operations,
                (step, error) -> failures.add(step))
                .start();

        assertEquals(
                List.of("hardware", "screen", "tasks", "close", "phone", "finish"),
                operations.calls);
        assertEquals(EnumSet.allOf(MagicDeskExitCoordinator.Step.class), failures);
        assertTrue(operations.finished);
    }

    @Test
    public void synchronousFailureDoesNotBlockFollowingSteps() {
        final FakeOperations operations = new FakeOperations(true);
        final List<MagicDeskExitCoordinator.Step> failures = new ArrayList<>();

        new MagicDeskExitCoordinator(
                operations,
                (step, error) -> failures.add(step))
                .start();

        assertEquals(
                List.of("hardware", "screen", "tasks", "close", "phone", "finish"),
                operations.calls);
        assertEquals(
                List.of(MagicDeskExitCoordinator.Step.RESTORE_HARDWARE),
                failures);
        assertTrue(operations.finished);
    }

    private static final class FakeOperations
            implements MagicDeskExitCoordinator.Operations {
        final List<String> calls = new ArrayList<>();
        final boolean throwFirst;
        boolean finished;

        FakeOperations(final boolean throwFirst) {
            this.throwFirst = throwFirst;
        }

        @Override
        public void restoreHardware(final MagicDeskExitCoordinator.Callback callback) {
            calls.add("hardware");
            if (throwFirst) {
                throw new IllegalStateException("hardware unavailable");
            }
            callback.onComplete(false);
        }

        @Override
        public void restorePhoneScreen(final MagicDeskExitCoordinator.Callback callback) {
            calls.add("screen");
            callback.onComplete(throwFirst);
        }

        @Override
        public void returnConsoleTasks(final MagicDeskExitCoordinator.Callback callback) {
            calls.add("tasks");
            callback.onComplete(throwFirst);
        }

        @Override
        public void closeDesktop(final MagicDeskExitCoordinator.Callback callback) {
            calls.add("close");
            callback.onComplete(throwFirst);
        }

        @Override
        public void cleanPhoneTasks(final MagicDeskExitCoordinator.Callback callback) {
            calls.add("phone");
            callback.onComplete(throwFirst);
        }

        @Override
        public void finishExit() {
            calls.add("finish");
            finished = true;
        }
    }
}
