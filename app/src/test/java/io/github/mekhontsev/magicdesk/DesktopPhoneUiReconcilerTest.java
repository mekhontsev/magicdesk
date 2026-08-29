package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.Test;

public final class DesktopPhoneUiReconcilerTest {
    @Test
    public void taskSwitchDoesNotLookLikeAnEmptyWorkspace() {
        assertFalse(DesktopPhoneUiReconciler.externalWorkspaceBecameEmpty(
                taskIds(10, 11), taskIds(11)));
    }

    @Test
    public void lastTaskMinimizeEmptiesWorkspace() {
        assertTrue(DesktopPhoneUiReconciler.externalWorkspaceBecameEmpty(
                taskIds(10), Collections.emptySet()));
    }

    @Test
    public void alreadyEmptyWorkspaceDoesNotEmitAnotherTransition() {
        assertFalse(DesktopPhoneUiReconciler.externalWorkspaceBecameEmpty(
                Collections.emptySet(), Collections.emptySet()));
    }

    private static Set<Integer> taskIds(final Integer... taskIds) {
        return new LinkedHashSet<>(Arrays.asList(taskIds));
    }
}
