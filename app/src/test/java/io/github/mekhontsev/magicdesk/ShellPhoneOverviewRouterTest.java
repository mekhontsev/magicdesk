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
                true, false, SYSTEM_RECENTS, SYSTEM_RECENTS));
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

    @Test
    public void missingComponentLeavesSystemRecentsAvailable() throws Exception {
        verifyStartup("""
                router.resolvedComponent = null;
                router.start(true);
                check(!router.mEnabled, "missing component enabled routing");
                check(router.preparations == 0, "prepared tasks without a component");
                check(router.reports.size() == 1, "missing capability was not reported");
                """);
    }

    @Test
    public void missingHomeCallbackLeavesSystemRecentsAvailable() throws Exception {
        verifyStartup("""
                router.mActivityLauncher = null;
                router.start(true);
                check(!router.mEnabled, "missing HOME callback enabled routing");
                check(router.preparations == 0, "prepared tasks without HOME callback");
                check(router.reports.size() == 1, "missing callback was not reported");
                """);
    }

    @Test
    public void preparationFailuresDoNotEscapeOptionalRouting() throws Exception {
        verifyStartup("""
                router.preparationFailure = new ReflectiveOperationException("task API missing");
                router.start(true);
                check(!router.mEnabled, "failed preparation enabled routing");
                check(router.reports.size() == 1, "API failure was not reported");
                check(router.reports.get(0).contains("task API missing"), "lost failure detail");

                router.preparationFailure = new SecurityException("permission denied");
                router.start(true);
                check(!router.mEnabled, "permission failure enabled routing");
                check(router.reports.size() == 2, "permission failure was not reported");
                """);
    }

    @Test
    public void componentLookupFailureDoesNotEscapeOptionalRouting() throws Exception {
        verifyStartup("""
                router.lookupFailure = new IllegalStateException("resources unavailable");
                router.start(true);
                check(!router.mEnabled, "lookup failure enabled routing");
                check(router.preparations == 0, "prepared tasks after failed lookup");
                check(router.reports.size() == 1, "lookup failure was not reported");
                """);
    }

    @Test
    public void routingIsEnabledOnlyAfterPreparationSucceeds() throws Exception {
        verifyStartup("""
                router.start(true);
                check(router.mEnabled, "available routing was not enabled");
                check(!router.enabledDuringPreparation, "intercepted before preparation completed");
                check(router.preparations == 1, "missing preparation");
                router.start(true);
                check(router.preparations == 1, "repeated start repeated preparation");
                check(router.reports.isEmpty(), "reported an error on success");
                """);
    }

    @Test
    public void disabledPreferenceDoesNotProbeOrPrepare() throws Exception {
        verifyStartup("""
                router.lookupFailure = new IllegalStateException("must not probe");
                router.start(false);
                check(!router.mEnabled, "disabled preference enabled routing");
                check(router.preparations == 0, "disabled preference prepared tasks");
                check(router.reports.isEmpty(), "disabled preference reported an error");
                """);
    }

    @Test
    public void failedStartCanBeRetriedByExplicitConfiguration() throws Exception {
        verifyStartup("""
                router.preparationFailure = new SecurityException("permission denied");
                router.start(true);
                check(!router.mEnabled, "failed start left interception active");
                router.preparationFailure = null;
                router.start(true);
                check(router.mEnabled, "explicit reconfiguration did not recover");
                router.start(false);
                check(!router.mEnabled, "disable left interception active");
                """);
    }

    private static void verifyStartup(final String scenario) throws Exception {
        RuntimeSourceFixture.verify("""
                boolean mClosed;
                boolean mEnabled;
                Object mContext;
                Object mActivityLauncher = new Object();
                String mSystemRecents;
                String resolvedComponent = "com.example/.Recents";
                RuntimeException lookupFailure;
                Exception preparationFailure;
                int preparations;
                boolean enabledDuringPreparation;
                final List<String> reports = new ArrayList<>();
                String resolveSystemRecentsComponent(Object context) {
                    if (lookupFailure != null) throw lookupFailure;
                    return resolvedComponent;
                }
                void removeExistingSystemOverviewTasks() throws ReflectiveOperationException {
                    preparations++;
                    enabledDuringPreparation = mEnabled;
                    if (preparationFailure instanceof ReflectiveOperationException error) {
                        throw error;
                    }
                    if (preparationFailure instanceof RuntimeException error) throw error;
                }
                void report(String message) { reports.add(message); }
                public static void verify() throws Exception {
                    Fixture router = new Fixture();
                """ + scenario + "}\n" + RuntimeSourceFixture.methods(
                        "ShellPhoneOverviewRouter", "start", "stop", "usefulMessage"));
    }
}
