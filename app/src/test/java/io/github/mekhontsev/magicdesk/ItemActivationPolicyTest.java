package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ItemActivationPolicyTest {
    @Test
    public void doubleClickActivatesOnlyTheSameItemWithinTimeout() {
        final ItemActivationPolicy policy =
                new ItemActivationPolicy(false, 300L);

        assertFalse(policy.shouldActivate("one", 100L));
        assertFalse(policy.shouldActivate("two", 200L));
        assertFalse(policy.shouldActivate("two", 501L));
        assertTrue(policy.shouldActivate("two", 700L));
        assertFalse(policy.shouldActivate("two", 710L));
    }

    @Test
    public void singleClickAndModeChangesResetPendingClick() {
        final ItemActivationPolicy policy =
                new ItemActivationPolicy(false, 300L);

        assertFalse(policy.shouldActivate("one", 100L));
        policy.setSingleClick(true);
        assertTrue(policy.shouldActivate("one", 150L));
        policy.setSingleClick(false);
        assertFalse(policy.shouldActivate("one", 200L));
    }
}
