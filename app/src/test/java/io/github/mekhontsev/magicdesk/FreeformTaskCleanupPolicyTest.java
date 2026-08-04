package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class FreeformTaskCleanupPolicyTest {
    private static final String PACKAGE = "net.sf.golly";

    @Test
    public void exactLiveFreeformTaskIsKept() {
        assertEquals(
                FreeformTaskCleanupPolicy.Action.KEEP,
                decide(true, PACKAGE, 0, true, false, null, -1));
    }

    @Test
    public void liveTaskInAnotherStateIsForgotten() {
        assertEquals(
                FreeformTaskCleanupPolicy.Action.FORGET,
                decide(true, PACKAGE, 0, false, true, PACKAGE, 0));
    }

    @Test
    public void reusedLiveTaskIdIsForgotten() {
        assertEquals(
                FreeformTaskCleanupPolicy.Action.FORGET,
                decide(true, "com.example.other", 0,
                        true, true, PACKAGE, 0));
    }

    @Test
    public void exactOrphanedRecentTaskIsRemoved() {
        assertEquals(
                FreeformTaskCleanupPolicy.Action.REMOVE_RECENT,
                decide(false, null, -1, false, true, PACKAGE, 0));
    }

    @Test
    public void mismatchedIdentityIsNeverRemoved() {
        assertEquals(
                FreeformTaskCleanupPolicy.Action.FORGET,
                decide(false, null, -1, false,
                        true, "com.example.other", 0));
        assertEquals(
                FreeformTaskCleanupPolicy.Action.FORGET,
                decide(false, null, -1, false, true, PACKAGE, 17));
        assertEquals(
                FreeformTaskCleanupPolicy.Action.FORGET,
                decide(false, null, -1, false, true, null, -1));
    }

    private static FreeformTaskCleanupPolicy.Action decide(
            final boolean live,
            final String livePackage,
            final int liveDisplayId,
            final boolean liveFreeform,
            final boolean recent,
            final String recentPackage,
            final int recentDisplayId) {
        return FreeformTaskCleanupPolicy.decide(
                PACKAGE, 0,
                live, livePackage, liveDisplayId, liveFreeform,
                recent, recentPackage, recentDisplayId);
    }
}
