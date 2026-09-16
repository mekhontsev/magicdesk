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
                final DesktopLaunchPresentation selected = presentation.mode != DesktopLaunchMode.AUTO ? presentation
                        : placement(destination).desktop ? request.presentation.withInstancePolicy(presentation.instancePolicy)
                        : DesktopLaunchPresentation.forMode(DesktopLaunchMode.FULLSCREEN)
                                .withInstancePolicy(presentation.instancePolicy);
                request(activity, request.withPresentation(selected),
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
        TaskCommandQueue.execute(() -> {
            try {
                DesktopDisplayCatalog.require(destination.displayId(), destination.uniqueId());
                if (!alive.getAsBoolean()) { return; }
                if (placement(destination).desktop) {
                    final boolean accepted = DesktopRuntimeBridge.launchAutomationRequest(request, destination.displayId());
                    activity.runOnUiThread(() -> {
                        if (!alive.getAsBoolean()) { return; }
                        if (accepted) { started.run(); }
                        else { failed.accept(new IllegalStateException("application launch was rejected")); }
                    });
                    return;
                }
                activity.runOnUiThread(() -> {
                    if (!alive.getAsBoolean()) { return; }
                    try {
                        final boolean accepted = new DesktopLaunchCoordinator(new StandaloneDesktopLaunchContext(
                                activity, destination.displayId(), destination.uniqueId())).launch(request);
                        if (accepted) { started.run(); }
                        else { failed.accept(new IllegalStateException("application launch was rejected")); }
                    } catch (RuntimeException error) { failed.accept(error); }
                });
            } catch (java.io.IOException | RuntimeException error) {
                activity.runOnUiThread(() -> { if (alive.getAsBoolean()) { failed.accept(error); } });
            }
        });
    }
}
