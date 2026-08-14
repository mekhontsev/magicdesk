package io.github.mekhontsev.magicdesk;

/** Component names shared by the self-test orchestrator and its suites. */
final class DesktopSelfTestComponents {
    static final String PACKAGE_NAME =
            "io.github.mekhontsev.magicdesk";
    static final String FIXTURE_CLASS =
            PACKAGE_NAME + ".DesktopSelfTestActivity";
    static final String DESKTOP_CLASS =
            PACKAGE_NAME + ".DesktopActivity";

    private DesktopSelfTestComponents() {
    }

    static boolean isFixtureTask(final TaskRepository.TaskEntry task) {
        return task != null
                && (DesktopSelfTestTasks.hasClass(
                        task.componentName, FIXTURE_CLASS)
                    || DesktopSelfTestTasks.hasClass(
                            task.topActivityName, FIXTURE_CLASS));
    }
}
