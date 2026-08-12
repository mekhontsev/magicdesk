package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import android.content.Intent;

import org.junit.Test;

public final class TaskDisplayAreaLaunchCommandTest {
    @Test
    public void preservesIndependentDocumentLaunch() {
        final int originalFlags = Intent.FLAG_ACTIVITY_NEW_DOCUMENT
                | Intent.FLAG_ACTIVITY_MULTIPLE_TASK;

        assertEquals(
                Intent.FLAG_ACTIVITY_NEW_TASK,
                TaskDisplayAreaLaunchCommand.additionalLaunchFlags(
                        originalFlags));
    }

    @Test
    public void reusesNormalApplicationTask() {
        assertEquals(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
                TaskDisplayAreaLaunchCommand.additionalLaunchFlags(0));
    }
}
