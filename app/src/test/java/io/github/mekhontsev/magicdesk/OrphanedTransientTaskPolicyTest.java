package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class OrphanedTransientTaskPolicyTest {
    private static final String APP = "example.app";
    private static final String TRANSIENT = "system.transient";

    @Test
    public void removesSoleExcludedActivityAfterOriginalDisappears() {
        assertTrue(OrphanedTransientTaskPolicy.shouldRemove(
                APP, TRANSIENT, TRANSIENT, TRANSIENT, 1, true));
    }

    @Test
    public void keepsTransientWhileOriginalActivityRemains() {
        assertFalse(OrphanedTransientTaskPolicy.shouldRemove(
                APP, APP, TRANSIENT, TRANSIENT, 2, true));
    }

    @Test
    public void keepsOriginalApplicationsExcludedActivity() {
        assertFalse(OrphanedTransientTaskPolicy.shouldRemove(
                APP, APP, APP, APP, 1, true));
    }

    @Test
    public void keepsSoleForeignApplicationActivity() {
        assertFalse(OrphanedTransientTaskPolicy.shouldRemove(
                APP, TRANSIENT, TRANSIENT, TRANSIENT, 1, false));
    }

    @Test
    public void keepsInconsistentTaskSnapshot() {
        assertFalse(OrphanedTransientTaskPolicy.shouldRemove(
                APP, TRANSIENT, TRANSIENT, "stale.info", 1, true));
    }
}
