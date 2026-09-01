package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ShellPhoneOverviewRouterTest {
    private static final String SYSTEM_RECENTS =
            "com.zte.mifavor.launcher/com.android.quickstep.RecentsActivity";

    @Test
    public void routesOnlyExactSystemRecentsComponent() {
        assertTrue(ShellPhoneOverviewRouter.shouldRoute(
                true,
                SYSTEM_RECENTS,
                SYSTEM_RECENTS));
        assertFalse(ShellPhoneOverviewRouter.shouldRoute(
                true,
                SYSTEM_RECENTS,
                "com.zte.mifavor.launcher/com.android.launcher3.Launcher"));
    }

    @Test
    public void doesNotRouteOutsideConfiguredSession() {
        assertFalse(ShellPhoneOverviewRouter.shouldRoute(
                false, SYSTEM_RECENTS, SYSTEM_RECENTS));
        assertFalse(ShellPhoneOverviewRouter.shouldRoute(
                true, null, SYSTEM_RECENTS));
        assertFalse(ShellPhoneOverviewRouter.shouldRoute(
                true, SYSTEM_RECENTS, null));
    }

    @Test
    public void identifiesOnlyTasksOwnedBySystemOverview() {
        assertTrue(ShellPhoneOverviewRouter.isSystemOverviewTask(
                SYSTEM_RECENTS, SYSTEM_RECENTS, null, null));
        assertTrue(ShellPhoneOverviewRouter.isSystemOverviewTask(
                SYSTEM_RECENTS, null, SYSTEM_RECENTS, null));
        assertTrue(ShellPhoneOverviewRouter.isSystemOverviewTask(
                SYSTEM_RECENTS, null, null, SYSTEM_RECENTS));
        assertFalse(ShellPhoneOverviewRouter.isSystemOverviewTask(
                SYSTEM_RECENTS,
                "com.example/.MainActivity",
                "com.example/.MainActivity",
                "com.example/.MainActivity"));
    }
}
