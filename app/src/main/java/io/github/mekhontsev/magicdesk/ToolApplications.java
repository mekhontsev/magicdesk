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
            case "x11" -> X11ManagerActivity.createIntent(context);
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
        final int display = source.getDisplay() == null ? 0 : source.getDisplay().getDisplayId();
        final int taskId = source.getTaskId();
        TaskCommandQueue.execute(() -> {
            try {
                final var placement = windowPlacement(display, taskId);
                open(source, intent, placement.target(), placement.uniqueId(), callback);
            } catch (java.io.IOException | RuntimeException error) {
                source.runOnUiThread(() -> { if (!source.isDestroyed() && !source.isFinishing()) callback.onComplete(error); });
            }
        });
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
