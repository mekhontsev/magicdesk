package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/** Optional virtual-first launch; a viewer failure never removes a created workspace. */
final class DesktopPresentationLauncher {
    interface Callback { void onComplete(DesktopDisplayInfo source, String error); }
    private DesktopPresentationLauncher() { }

    static void start(Context context, DesktopDisplayInfo output, Callback callback) {
        final Handler main = new Handler(Looper.getMainLooper());
        final Context application = context.getApplicationContext();
        TaskCommandQueue.execute(() -> {
            try {
                RuntimeCapabilities.requireDesktop();
                DesktopDisplayCatalog.require(output.id, output.uniqueId);
                final VirtualDisplaySpec spec = new VirtualDisplaySpec(
                        output.width, output.height, output.densityDpi);
                DisplayOperations.createDisplay(spec, false, (source, error) -> main.post(() -> {
                    if (source == null) { callback.onComplete(null, error); return; }
                    try {
                        DesktopOperations.showDesktop(source, result -> main.post(() -> {
                            if (!result.success) {
                                callback.onComplete(source, retained(source, result.message));
                                return;
                            }
                            // HOME acquisition and automatic phone UI belong to startup.
                            // Present the requested output only after that startup completes.
                            DisplayPresentations.open(application, source.id, output.id, true, launchError ->
                                    callback.onComplete(source, launchError == null ? null
                                            : retained(source, ShellAccess.usefulMessage(launchError))));
                        }));
                    } catch (RuntimeException failure) {
                        callback.onComplete(source, retained(source, ShellAccess.usefulMessage(failure)));
                    }
                }));
            } catch (Exception error) {
                main.post(() -> callback.onComplete(null, ShellAccess.usefulMessage(error)));
            }
        });
    }

    private static String retained(DesktopDisplayInfo source, String error) {
        return "Display " + source.id + " [" + source.uniqueId + "] retained: " + error;
    }
}
