package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.Intent;

/** Built-in application entry points shared by phone UI and automation. */
final class ToolApplications {
    record WindowPlacement(ToolLaunchTarget target, String uniqueId, DesktopLaunchPresentation presentation) { }
    private ToolApplications() { }

    static Intent intent(final Context context, final String name) {
        return switch (name) {
            case "files" -> FileManagerActivity.createIntent(context);
            case "console" -> CommandConsoleActivity.createIntent(context);
            case "termux" -> CommandConsoleActivity.createTermuxIntent(context);
            case "task_manager" -> TaskManagerActivity.createIntent(context);
            case "settings" -> SettingsActivity.createIntent(context);
            case "app_profiles" -> AppPresentationSettingsActivity.createIntent(context);
            case "diagnostics" -> DiagnosticsActivity.createIntent(context);
            case "activity_explorer" -> ActivityExplorerActivity.createIntent(context);
            case "display_viewer" -> DisplayViewerActivity.createIntent(context);
            case "graphics" -> GraphicalSessionsActivity.createIntent(context);
            default -> throw new IllegalArgumentException("unknown built-in application: " + name);
        };
    }

    static void open(final Context context, final Intent intent, final ToolLaunchTarget target,
            final String uniqueId, final BuiltInWindowLauncher.Callback callback) {
        open(context, intent, target, uniqueId, null, callback);
    }

    static void open(final Context context, final Intent intent, final ToolLaunchTarget target,
            final String uniqueId, final DesktopLaunchPresentation presentation,
            final BuiltInWindowLauncher.Callback callback) {
        final android.content.ComponentName component = intent.getComponent();
        if (component == null || !context.getPackageName().equals(component.getPackageName())) {
            throw new IllegalArgumentException("built-in application must belong to MagicDesk");
        }
        final BuiltInDesktopAppCatalog.Entry entry =
                BuiltInDesktopAppCatalog.findComponent(component.getClassName());
        if (entry == null) { throw new IllegalArgumentException("unknown built-in application"); }
        RuntimeCapabilities.current(context).require(context,
                BuiltInDesktopAppCatalog.requiredService(entry.launchTarget));
        BuiltInWindowLauncher.launch(context, intent, entry.launchTarget, target, uniqueId, presentation, callback);
    }

    /** A child tool follows its source task, including an independent task over an active Desktop. */
    static void openSibling(final android.app.Activity source, final Intent intent,
            final BuiltInWindowLauncher.Callback callback) {
        openSibling(source, intent, null, callback);
    }

    static void openSibling(final android.app.Activity source, final Intent intent,
            final DesktopLaunchPresentation presentation, final BuiltInWindowLauncher.Callback callback) {
        final int display = source.getDisplay() == null ? 0 : source.getDisplay().getDisplayId();
        final int taskId = source.getTaskId();
        TaskCommandQueue.execute(() -> {
            try {
                final var placement = windowPlacement(display, taskId);
                open(source, intent, placement.target(), placement.uniqueId(),
                        placement.presentation().mode == DesktopLaunchMode.WINDOWED ? presentation : null, callback);
            } catch (java.io.IOException | RuntimeException error) {
                source.runOnUiThread(() -> { if (!source.isDestroyed() && !source.isFinishing()) callback.onComplete(error); });
            }
        });
    }

    static DesktopLaunchPresentation childPresentation(android.app.Activity parent,
            io.github.mekhontsev.magicdesk.hosted.HostedWindowLayout layout, float scale) {
        if (layout.parent() == 0 || layout.width() < 1 || layout.height() < 1 || !parent.isInMultiWindowMode()) return null;
        int display = parent.getDisplay() == null ? 0 : parent.getDisplay().getDisplayId();
        var work = DesktopRuntimeBridge.getDesktopWorkAreaBounds(display);
        if (work == null || work.isEmpty()) return null;
        var metrics = parent.getWindowManager().getCurrentWindowMetrics();
        var origin = metrics.getBounds();
        var decor = metrics.getWindowInsets().getInsets(android.view.WindowInsets.Type.systemBars());
        int width = Math.min(work.width(), Math.round(layout.constraints().width(layout.width()) * scale) + decor.left + decor.right);
        int height = Math.min(work.height(), Math.round(layout.constraints().height(layout.height()) * scale) + decor.top + decor.bottom);
        int left = Math.max(work.left, Math.min(work.right - width, origin.centerX() - width / 2));
        int top = Math.max(work.top, Math.min(work.bottom - height, origin.centerY() - height / 2));
        var relative = RelativeWindowBounds.from(new android.graphics.Rect(left, top, left + width, top + height), work);
        return new DesktopLaunchPresentation(DesktopLaunchMode.WINDOWED, relative, DesktopTaskInstancePolicy.CREATE_NEW, -1);
    }

    /** One-shot ownership capture on the command queue; never infers ownership from the display alone. */
    static WindowPlacement windowPlacement(int display, int taskId) throws java.io.IOException {
        boolean managed = false;
        DesktopLaunchMode mode = DesktopLaunchMode.FULLSCREEN;
        RelativeWindowBounds bounds = null;
        if (DesktopRuntimeBridge.hasWorkspace(display)) {
            final var snapshot = TaskRepository.loadNow(display);
            if (!snapshot.available) throw new java.io.IOException(snapshot.error);
            final var task = snapshot.tasks.stream().filter(item -> item.taskId == taskId)
                    .findFirst().orElseThrow(() -> new java.io.IOException("Source window has closed or moved"));
            final String ownership = ApplicationTaskPlacement.ownership(task, snapshot);
            if ("unknown".equals(ownership)) throw new java.io.IOException("Source window ownership is unavailable");
            managed = "desktop".equals(ownership);
            if (managed && task.isFreeform()) {
                mode = DesktopLaunchMode.WINDOWED;
                bounds = RelativeWindowBounds.from(task.bounds, FloatingWindowController.getWorkAreaBounds(display));
            }
        }
        return new WindowPlacement(ToolLaunchTarget.resolve(managed ? "desktop" : "display", display,
                DesktopRuntimeBridge.workspaceDisplayIds()), DesktopDisplayCatalog.require(display, null).uniqueId,
                new DesktopLaunchPresentation(mode, bounds, DesktopTaskInstancePolicy.CREATE_NEW, -1));
    }

    /** Pure position changes need not deliver Activity.onConfigurationChanged. Read the final local bounds. */
    static DesktopLaunchPresentation replacementPresentation(android.app.Activity activity, WindowPlacement placement) {
        if (activity.getDisplay() == null || activity.getDisplay().getDisplayId() != placement.target().displayId) {
            throw new IllegalStateException("Window moved away from its verified destination");
        }
        final var previous = placement.presentation();
        if (previous.mode != DesktopLaunchMode.WINDOWED) return previous;
        final var bounds = activity.getWindowManager().getCurrentWindowMetrics().getBounds();
        final var workArea = DesktopRuntimeBridge.getDesktopWorkAreaBounds(placement.target().displayId);
        final var relative = RelativeWindowBounds.from(bounds, workArea);
        if (relative == null) throw new IllegalStateException("Final window geometry is unavailable");
        return new DesktopLaunchPresentation(DesktopLaunchMode.WINDOWED, relative,
                DesktopTaskInstancePolicy.CREATE_NEW, -1);
    }
}
