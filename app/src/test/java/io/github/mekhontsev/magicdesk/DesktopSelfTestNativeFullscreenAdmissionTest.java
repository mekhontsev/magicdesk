package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class DesktopSelfTestNativeFullscreenAdmissionTest {
    @Test public void acceptsBothHomeAndIndependentDesktopHosts() throws Exception {
        verify("""
                for (String name : List.of("DesktopActivity", "PhoneHomeActivity", "IndependentDesktopActivity")) {
                    host.componentName = BuildConfig.APPLICATION_ID + "/." + name;
                    host.activityType = name.startsWith("Independent") ? 1 : 2;
                    check(isFixtureBesideDesktop(task, host), "desktop host rejected: " + name);
                }
                """);
    }

    @Test public void rejectsForeignHomeWrongTypeAndNonFullscreenHost() throws Exception {
        verify("""
                host.componentName = "example.launcher/.Home";
                check(!isFixtureBesideDesktop(task, host), "foreign HOME accepted");
                host.componentName = BuildConfig.APPLICATION_ID + "/.IndependentDesktopActivity";
                host.activityType = 2;
                check(!isFixtureBesideDesktop(task, host), "wrong Activity type accepted");
                host.activityType = 1; host.windowingMode = 5;
                check(!isFixtureBesideDesktop(task, host), "non-fullscreen host accepted");
                host.activityType = 0; host.componentName = "";
                check(!isFixtureBesideDesktop(task, host), "unknown host accepted");
                """);
    }

    @Test public void retainsFixtureModeAndParentRequirements() throws Exception {
        verify("""
                check(!isFixtureBesideDesktop(null, host) && !isFixtureBesideDesktop(task, null),
                        "missing task accepted");
                task.windowingMode = 1;
                check(!isFixtureBesideDesktop(task, host), "fullscreen fixture accepted");
                task.windowingMode = 5; task.displayAreaFeatureId = 99;
                check(!isFixtureBesideDesktop(task, host), "foreign area accepted");
                task.displayAreaFeatureId = 1; task.rootTaskId = 99;
                check(!isFixtureBesideDesktop(task, host), "nested fixture accepted");
                task.rootTaskId = task.taskId; task.componentName = "example.app/.Main";
                check(!isFixtureBesideDesktop(task, host), "ordinary application accepted");
                """);
    }

    private static void verify(String scenario) throws Exception {
        RuntimeSourceFixture.verify("""
                static class BuildConfig { static final String APPLICATION_ID = "example.magicdesk"; }
                static class FrameworkTaskSnapshot {
                    static final int WINDOWING_MODE_FULLSCREEN = 1, WINDOWING_MODE_FREEFORM = 5;
                    static final int ACTIVITY_TYPE_STANDARD = 1, ACTIVITY_TYPE_HOME = 2;
                    int taskId = 42, rootTaskId = 42, activityType = 2, windowingMode = 5, displayAreaFeatureId = 1;
                    String componentName = "fixture";
                }
                static class DesktopSelfTestComponents {
                    static boolean isFixtureComponent(String name) { return "fixture".equals(name); }
                }
                static class DesktopHostComponents {
                    static final String PACKAGE_NAME = BuildConfig.APPLICATION_ID;
                    static final String INDEPENDENT_CLASS = PACKAGE_NAME + ".IndependentDesktopActivity";
                    static final String EXTERNAL_HOME_CLASS = PACKAGE_NAME + ".DesktopActivity";
                    static final String PHONE_HOME_CLASS = PACKAGE_NAME + ".PhoneHomeActivity";
                """ + RuntimeSourceFixture.methods("DesktopHostComponents", "hostActivityType", "activityTypeForClass")
                + "}\n" + RuntimeSourceFixture.methods("DesktopSelfTestNativeFullscreenCommand", "isFixtureBesideDesktop")
                + """
                public static void verify() {
                    var task = new FrameworkTaskSnapshot();
                    var host = new FrameworkTaskSnapshot();
                    host.windowingMode = 1;
                    host.componentName = BuildConfig.APPLICATION_ID + "/.DesktopActivity";
                """ + scenario + "}");
    }
}
