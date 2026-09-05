package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;

public final class DesktopWorkspaceQueueTest {
    @Test
    public void secondClickUsesAcknowledgedStateInsteadOfClickTimeState() {
        final Dispatcher dispatcher = new Dispatcher();
        final DesktopWorkspaceQueue queue = new DesktopWorkspaceQueue(dispatcher);
        final List<String> actions = new ArrayList<>();
        final List<DesktopWorkspaceQueue.Completion> commits = new ArrayList<>();
        final int[] activeTask = {10};
        final DesktopWorkspaceQueue.Operation clickGolly = completion -> {
            actions.add(activeTask[0] == 20 ? "demote" : "activate");
            commits.add(completion);
        };
        queue.enqueue(clickGolly, result -> activeTask[0] = 20);
        queue.enqueue(clickGolly, null);
        assertEquals(Arrays.asList("activate"), actions);
        assertTrue(queue.isRunning());

        commits.get(0).onComplete(success());
        assertEquals(Arrays.asList("activate"), actions);
        dispatcher.drain();
        assertEquals(Arrays.asList("activate", "demote"), actions);
        commits.get(1).onComplete(success());
        dispatcher.drain();
        assertFalse(queue.isRunning());
    }

    @Test
    public void activationShowDesktopAndTaskbarShareOneSequence() {
        final Dispatcher dispatcher = new Dispatcher();
        final DesktopWorkspaceQueue queue = new DesktopWorkspaceQueue(dispatcher);
        final List<String> actions = new ArrayList<>();
        final List<DesktopWorkspaceQueue.Completion> commits = new ArrayList<>();
        for (final String operation : Arrays.asList("activate", "show-desktop", "toggle")) {
            queue.enqueue(completion -> {
                actions.add(operation);
                commits.add(completion);
            }, null);
        }
        assertEquals(Arrays.asList("activate"), actions);
        commits.get(0).onComplete(success());
        dispatcher.drain();
        assertEquals(Arrays.asList("activate", "show-desktop"), actions);
        commits.get(1).onComplete(success());
        dispatcher.drain();
        assertEquals(Arrays.asList("activate", "show-desktop", "toggle"), actions);
    }

    @Test
    public void failedActivationDoesNotMakeNextClickADemotion() {
        final Dispatcher dispatcher = new Dispatcher();
        final DesktopWorkspaceQueue queue = new DesktopWorkspaceQueue(dispatcher);
        final List<String> actions = new ArrayList<>();
        final List<DesktopWorkspaceQueue.Completion> commits = new ArrayList<>();
        final int[] activeTask = {10};
        final DesktopWorkspaceQueue.Operation click = completion -> {
            actions.add(activeTask[0] == 20 ? "demote" : "activate");
            commits.add(completion);
        };
        queue.enqueue(click, result -> {
            if (result.success) {
                activeTask[0] = 20;
            }
        });
        queue.enqueue(click, null);
        commits.get(0).onComplete(new TaskRepository.ActionResult(false, "focus failed"));
        dispatcher.drain();
        assertEquals(Arrays.asList("activate", "activate"), actions);
    }

    @Test
    public void cancelledSessionIgnoresLateCommitAndCanStartANewSequence() {
        final Dispatcher dispatcher = new Dispatcher();
        final DesktopWorkspaceQueue queue = new DesktopWorkspaceQueue(dispatcher);
        final List<DesktopWorkspaceQueue.Completion> commits = new ArrayList<>();
        final List<Boolean> results = new ArrayList<>();
        queue.enqueue(commits::add, result -> results.add(result.success));
        queue.enqueue(completion -> {
            throw new AssertionError("cancelled operation ran");
        }, result -> results.add(result.success));
        queue.cancelAll("desktop closed");
        assertFalse(commits.get(0).isCurrent());
        assertEquals(Arrays.asList(false, false), results);
        queue.enqueue(commits::add, result -> results.add(result.success));
        commits.get(0).onComplete(success());
        dispatcher.drain();
        assertTrue(commits.get(1).isCurrent());
        assertEquals(Arrays.asList(false, false), results);
        commits.get(1).onComplete(success());
        dispatcher.drain();
        assertEquals(Arrays.asList(false, false, true), results);
    }

    @Test
    public void duplicateAcknowledgementCannotReleaseTheNextOperation() {
        final Dispatcher dispatcher = new Dispatcher();
        final DesktopWorkspaceQueue queue = new DesktopWorkspaceQueue(dispatcher);
        final List<DesktopWorkspaceQueue.Completion> commits = new ArrayList<>();
        final List<Boolean> results = new ArrayList<>();
        queue.enqueue(commits::add, result -> results.add(result.success));
        queue.enqueue(commits::add, result -> results.add(result.success));
        commits.get(0).onComplete(success());
        commits.get(0).onComplete(success());
        dispatcher.drain();
        assertEquals(Arrays.asList(true), results);
        assertTrue(commits.get(1).isCurrent());
    }

    @Test
    public void synchronousFailureCompletesAndReleasesTheQueue() {
        final Dispatcher dispatcher = new Dispatcher();
        final DesktopWorkspaceQueue queue = new DesktopWorkspaceQueue(dispatcher);
        final List<Boolean> results = new ArrayList<>();
        queue.enqueue(completion -> {
            throw new IllegalStateException("missing task");
        }, result -> results.add(result.success));
        queue.enqueue(completion -> completion.onComplete(success()),
                result -> results.add(result.success));
        dispatcher.drain();
        assertEquals(Arrays.asList(false, true), results);
        assertFalse(queue.isRunning());
    }

    private static TaskRepository.ActionResult success() {
        return new TaskRepository.ActionResult(true, "focused");
    }

    private static final class Dispatcher implements Executor {
        private final ArrayDeque<Runnable> pending = new ArrayDeque<>();

        @Override
        public void execute(final Runnable action) {
            pending.addLast(action);
        }

        void drain() {
            while (!pending.isEmpty()) {
                pending.removeFirst().run();
            }
        }
    }
}
