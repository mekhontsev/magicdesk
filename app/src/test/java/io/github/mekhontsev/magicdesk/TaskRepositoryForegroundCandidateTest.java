package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class TaskRepositoryForegroundCandidateTest {
    @Test
    public void acceptsApplicationTask() {
        assertTrue(TaskRepository.isForegroundApplicationCandidate(
                "com.example/.MainActivity",
                "com.example/.MainActivity"));
    }

    @Test
    public void rejectsTaskbarAtEitherComponentLevel() {
        final String taskbar = BuildConfig.APPLICATION_ID
                + "/.DesktopTaskbarActivity";
        assertFalse(TaskRepository.isForegroundApplicationCandidate(
                taskbar,
                "com.example/.MainActivity"));
        assertFalse(TaskRepository.isForegroundApplicationCandidate(
                "com.example/.MainActivity",
                taskbar));
    }

    @Test
    public void rejectsBackstopAtEitherComponentLevel() {
        final String backstop = BuildConfig.APPLICATION_ID
                + "/.TaskAreaBackstopActivity";
        assertFalse(TaskRepository.isForegroundApplicationCandidate(
                backstop,
                "com.example/.MainActivity"));
        assertFalse(TaskRepository.isForegroundApplicationCandidate(
                "com.example/.MainActivity",
                backstop));
    }
}
