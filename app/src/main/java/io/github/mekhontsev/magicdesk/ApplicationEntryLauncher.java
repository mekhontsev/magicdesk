package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Dispatches Desktop Entry recipes independently of their UI or automation caller. */
final class ApplicationEntryLauncher {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static void launch(Context context, DesktopLaunchRequest request, ToolLaunchTarget target,
            String uniqueId, BooleanSupplier alive, Consumer<Throwable> complete) {
        TaskCommandQueue.execute(() -> {
            try {
                target.requireCurrent(DesktopRuntimeBridge.workspaceDisplayIds());
                InteractiveActivityLaunch.requireDestination(context, target.displayId, uniqueId);
                if (!alive.getAsBoolean()) return;
                if (target.desktop) {
                    boolean accepted = DesktopRuntimeBridge.launchAutomationRequest(request, target.displayId);
                    MAIN.post(() -> {
                        if (alive.getAsBoolean()) complete.accept(accepted ? null
                                : new IllegalStateException("application launch was rejected"));
                    });
                } else {
                    MAIN.post(() -> {
                        if (!alive.getAsBoolean()) return;
                        try {
                            boolean accepted = new DesktopLaunchCoordinator(new StandaloneDesktopLaunchContext(
                                    context, target.displayId, uniqueId)).launch(request);
                            complete.accept(accepted ? null : new IllegalStateException("application launch was rejected"));
                        } catch (RuntimeException error) { complete.accept(error); }
                    });
                }
            } catch (java.io.IOException | RuntimeException error) {
                MAIN.post(() -> { if (alive.getAsBoolean()) complete.accept(error); });
            }
        });
    }

    static DesktopLaunchRequest present(DesktopLaunchRequest request, ToolLaunchTarget target,
            DesktopLaunchPresentation presentation) {
        DesktopLaunchPresentation selected = presentation.mode != DesktopLaunchMode.AUTO ? presentation
                : target.desktop ? request.presentation.withInstancePolicy(presentation.instancePolicy)
                : DesktopLaunchPresentation.forMode(DesktopLaunchMode.FULLSCREEN)
                        .withInstancePolicy(presentation.instancePolicy);
        if (!target.desktop) OrdinaryActivityLaunch.requirePresentation(selected);
        return request.withPresentation(selected);
    }

    private ApplicationEntryLauncher() { }
}
