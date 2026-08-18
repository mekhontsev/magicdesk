package io.github.mekhontsev.magicdesk;

/** Component names shared by the self-test orchestrator and its suites. */
final class DesktopSelfTestComponents {
    static final String PACKAGE_NAME =
            "io.github.mekhontsev.magicdesk";
    static final String FIXTURE_CLASS =
            PACKAGE_NAME + ".DesktopSelfTestActivity";
    static final String BROWSER_FIXTURE_CLASS =
            PACKAGE_NAME + ".DesktopSelfTestBrowserActivity";
    static final String DESKTOP_CLASS =
            PACKAGE_NAME + ".DesktopActivity";

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

    static boolean isFixtureComponent(final String componentName) {
        return DesktopSelfTestTasks.hasClass(componentName, FIXTURE_CLASS)
                || DesktopSelfTestTasks.hasClass(
                        componentName, BROWSER_FIXTURE_CLASS);
    }
}
