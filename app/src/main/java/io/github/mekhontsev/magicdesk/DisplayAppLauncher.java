package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.Intent;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Application launch policy is independent of the role of the Start host. */
final class DisplayAppLauncher {
    private DisplayAppLauncher() { }

    static void launch(Activity activity, AppItem app, int displayId, String uniqueId,
            BooleanSupplier canLaunch, Runnable onStarted, Consumer<Throwable> onFailure) {
        launch(activity, app, displayId, uniqueId, DesktopLaunchPresentation.automatic(),
                canLaunch, onStarted, onFailure);
    }

    static void launch(Activity activity, AppItem app, int displayId, String uniqueId,
            DesktopLaunchPresentation presentation, BooleanSupplier canLaunch,
            Runnable onStarted, Consumer<Throwable> onFailure) {
        launch(activity, app, ToolLaunchTarget.resolve("auto", displayId,
                DesktopRuntimeBridge.workspaceDisplayIds()), uniqueId, presentation,
                canLaunch, onStarted, onFailure);
    }

    static void launch(Activity activity, AppItem app, ToolLaunchTarget target, String uniqueId,
            DesktopLaunchPresentation presentation, BooleanSupplier canLaunch,
            Runnable onStarted, Consumer<Throwable> onFailure) {
        TaskCommandQueue.execute(() -> {
            try {
                app.identity.requireProfile(AppProfile.current(activity));
                RuntimeCapabilities.current(activity).require(activity,
                        BuiltInDesktopAppCatalog.requiredService(app.launchTarget));
                InteractiveActivityLaunch.requireDestination(activity, target.displayId, uniqueId);
                target.requireCurrent(DesktopRuntimeBridge.workspaceDisplayIds());
                if (!canLaunch.getAsBoolean()) { return; }
                if (target.desktop) {
                    final boolean accepted = DesktopRuntimeBridge.launchApplication(
                            app.identity, app.launchTarget, presentation, target.displayId);
                    if (!accepted) { throw new IllegalStateException("desktop launch was rejected"); }
                } else {
                    OrdinaryActivityLaunch.requirePresentation(presentation);
                    final Intent source = app.launchTarget.resolve(activity.getPackageManager());
                    if (source == null) { throw new IllegalStateException("launcher activity is unavailable"); }
                    final Intent intent = presentation.instancePolicy.applyTo(activity.getPackageManager(), source);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    InteractiveActivityLaunch.launch(activity, intent,
                            AndroidLaunchSpec.Delivery.SHELL_INTENT, target.displayId);
                    RecentApplications.recordApp(activity, app, RecentLaunchScope.of(target));
                }
                activity.runOnUiThread(() -> { if (canLaunch.getAsBoolean()) { onStarted.run(); } });
            } catch (java.io.IOException | RuntimeException error) {
                activity.runOnUiThread(() -> { if (canLaunch.getAsBoolean()) { onFailure.accept(error); } });
            }
        });
    }
}
