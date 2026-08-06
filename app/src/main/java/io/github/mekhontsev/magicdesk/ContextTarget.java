package io.github.mekhontsev.magicdesk;

final class ContextTarget {
    final AppItem app;
    final TaskRepository.TaskEntry task;
    final DesktopFile file;
    final int appWidgetId;
    final String widgetLabel;
    final boolean widgetConfigurable;
    final int widgetResizeMode;
    final boolean desktopItem;

    private ContextTarget(
            final AppItem app,
            final TaskRepository.TaskEntry task,
            final DesktopFile file,
            final int appWidgetId,
            final String widgetLabel,
            final boolean widgetConfigurable,
            final int widgetResizeMode,
            final boolean desktopItem) {
        this.app = app;
        this.task = task;
        this.file = file;
        this.appWidgetId = appWidgetId;
        this.widgetLabel = widgetLabel;
        this.widgetConfigurable = widgetConfigurable;
        this.widgetResizeMode = widgetResizeMode;
        this.desktopItem = desktopItem;
    }

    static ContextTarget app(
            final AppItem app,
            final TaskRepository.TaskEntry task) {
        return new ContextTarget(
                app, task, null, -1, null, false, 0, false);
    }

    static ContextTarget desktopApp(final AppItem app) {
        return new ContextTarget(
                app, null, null, -1, null, false, 0, true);
    }

    static ContextTarget file(final DesktopFile file) {
        return new ContextTarget(
                null, null, file, -1, null, false, 0, true);
    }

    static ContextTarget widget(
            final int appWidgetId,
            final String label,
            final boolean configurable,
            final int resizeMode) {
        return new ContextTarget(
                null, null, null, appWidgetId, label, configurable,
                resizeMode, true);
    }
}
