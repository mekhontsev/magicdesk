package io.github.mekhontsev.magicdesk;

/** Component names shared by the self-test orchestrator and its suites. */
final class DesktopSelfTestComponents {
    static final String PACKAGE_NAME =
            DesktopHostComponents.PACKAGE_NAME;
    static final String FIXTURE_CLASS =
            PACKAGE_NAME + ".DesktopSelfTestActivity";
    static final String BROWSER_FIXTURE_CLASS =
            PACKAGE_NAME + ".DesktopSelfTestBrowserActivity";
    static final String DESKTOP_CLASS =
            DesktopHostComponents.EXTERNAL_HOME_CLASS;
    static final String PHONE_DESKTOP_HOME_CLASS =
            DesktopHostComponents.PHONE_HOME_CLASS;

    private DesktopSelfTestComponents() {
    }

    static boolean isFixtureTask(final TaskRepository.TaskEntry task) {
        return task != null
                && (isFixtureComponent(task.componentName)
                    || isFixtureComponent(task.topActivityName));
    }

    static boolean isFixtureTask(final TaskStackParser.Entry task) {
        return task != null
                && (isFixtureComponent(task.componentName)
                    || isFixtureComponent(task.topActivityName));
    }

    static boolean isDesktopTask(final TaskStackParser.Entry task) {
        return task != null
                && (isDesktopComponent(task.componentName)
                        || isDesktopComponent(task.topActivityName));
    }

    private static boolean isDesktopComponent(final String componentName) {
        return DesktopHostComponents.isHostComponentName(componentName);
    }

    static boolean isFixtureComponent(final String componentName) {
        return DesktopSelfTestTasks.hasClass(componentName, FIXTURE_CLASS)
                || DesktopSelfTestTasks.hasClass(
                        componentName, BROWSER_FIXTURE_CLASS);
    }
}
