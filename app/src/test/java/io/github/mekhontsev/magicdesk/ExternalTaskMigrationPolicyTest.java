package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ExternalTaskMigrationPolicyTest {
    @Test
    public void normalizesEveryFreeformTaskObservedOnPhone() {
        assertTrue(ExternalTaskMigrationPolicy.shouldNormalizeObservedTask(
                0, true, true));
        assertFalse(ExternalTaskMigrationPolicy.shouldNormalizeObservedTask(
                0, true, false));
        assertFalse(ExternalTaskMigrationPolicy.shouldNormalizeObservedTask(
                0, false, true));
        assertFalse(ExternalTaskMigrationPolicy.shouldNormalizeObservedTask(
                3, true, true));
    }
}
