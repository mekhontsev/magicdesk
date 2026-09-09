package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.Intent;

/** Built-in application entry points shared by phone UI and automation. */
final class ToolApplications {
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
            default -> throw new IllegalArgumentException("unknown built-in application: " + name);
        };
    }

    static void open(final Context context, final Intent intent, final ToolLaunchTarget target,
            final String uniqueId, final BuiltInWindowLauncher.Callback callback) {
        final android.content.ComponentName component = intent.getComponent();
        if (component == null || !context.getPackageName().equals(component.getPackageName())) {
            throw new IllegalArgumentException("built-in application must belong to MagicDesk");
        }
        final BuiltInDesktopAppCatalog.Entry entry =
                BuiltInDesktopAppCatalog.findComponent(component.getClassName());
        if (entry == null) { throw new IllegalArgumentException("unknown built-in application"); }
        BuiltInWindowLauncher.launch(context, intent, entry.launchTarget, target, uniqueId, callback);
    }
}
