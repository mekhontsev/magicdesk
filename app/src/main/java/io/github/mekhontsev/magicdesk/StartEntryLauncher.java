package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.Intent;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Adapts Start entries to shared launch services, using a captured destination. */
final class StartEntryLauncher {
    private StartEntryLauncher() { }

    static void open(Activity activity, StartMenuEntry entry, StartDisplaySelector.Target destination,
            DesktopLaunchPresentation presentation, BooleanSupplier alive,
            Runnable started, Consumer<Throwable> failed) {
        try {
            if (entry.recent != null) RecentApplications.requireEnvironment(activity, entry.recent);
            if (entry.task != null) {
                ApplicationTaskPlacement.place(entry.task, placement(destination), destination.uniqueId(),
                        presentation.withInstancePolicy(DesktopTaskInstancePolicy.REUSE_EXISTING),
                        result -> activity.runOnUiThread(() -> {
                            if (!alive.getAsBoolean()) { return; }
                            if (result.success) { started.run(); }
                            else { failed.accept(new IllegalStateException(result.message)); }
                        }));
            } else if (entry.app != null && entry.recent == null) {
                DisplayAppLauncher.launch(activity, entry.app, placement(destination), destination.uniqueId(),
                        presentation, alive, started, failed);
            } else if (entry.kind == StartMenuEntry.Kind.TERMINALS) {
                TerminalSessionsDialog.show(activity, placement(destination), destination.uniqueId(), presentation);
                started.run();
            } else if (entry.desktopApplication != null) {
                final DesktopLaunchRequest request = DesktopLaunchRequest.from(entry.desktopApplication.shortcut,
                        DesktopLaunchArguments.empty(), entry.desktopApplication.desktopFilePath);
                request(activity, ApplicationEntryLauncher.present(request, placement(destination), presentation),
                        destination, alive, started, failed);
            } else {
                final Intent intent;
                if (entry.file != null) {
                    intent = entry.file.directory
                            ? FileManagerActivity.createIntent(activity, entry.file.absolutePath)
                            : FileManagerActivity.createRevealIntent(activity, entry.file);
                } else if (entry.builtIn != null) {
                    intent = entry.builtIn.launchTarget.resolve(activity.getPackageManager());
                } else { throw new IllegalArgumentException("Start item is not an application"); }
                ToolApplications.open(activity, intent, placement(destination), destination.uniqueId(), presentation, error -> {
                    if (!alive.getAsBoolean()) { return; }
                    if (error == null) { started.run(); } else { failed.accept(error); }
                });
            }
        } catch (RuntimeException error) { failed.accept(error); }
    }

    static ToolLaunchTarget placement(StartDisplaySelector.Target destination) {
        return ToolLaunchTarget.resolve(destination.placement(), destination.displayId(), DesktopRuntimeBridge.workspaceDisplayIds());
    }

    static void request(Activity activity, DesktopLaunchRequest request, StartDisplaySelector.Target destination,
            BooleanSupplier alive, Runnable started, Consumer<Throwable> failed) {
        ApplicationEntryLauncher.launch(activity, request, placement(destination), destination.uniqueId(), alive,
                error -> { if (error == null) started.run(); else failed.accept(error); });
    }
}
