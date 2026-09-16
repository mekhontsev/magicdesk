package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Reuses or creates a portable workspace; presentation never owns its lifetime. */
final class DesktopPresentationLauncher {
    interface Callback { void onComplete(DesktopDisplayInfo source, String error); }
    private static Request sPending;
    private DesktopPresentationLauncher() { }

    static void start(Context context, DesktopDisplayInfo output, Callback callback) {
        final Handler main = new Handler(Looper.getMainLooper());
        final Context application = context.getApplicationContext();
        main.post(() -> {
            if (sPending != null) {
                if (sameDisplay(sPending.output, output)) sPending.callbacks.add(callback);
                else callback.onComplete(null, "Another portable desktop launch is in progress");
                return;
            }
            final Request request = new Request(main, output, callback);
            sPending = request;
            prepare(application, request);
        });
    }

    private static void prepare(Context context, Request request) {
        TaskCommandQueue.execute(() -> {
            try {
                RuntimeCapabilities.requireDesktop();
                if (DesktopOperations.isSessionTransitionInProgress()) {
                    throw new IllegalStateException("Another desktop transition is in progress");
                }
                final DesktopDisplayInfo reference = DesktopDisplayCatalog.require(request.output.id, request.output.uniqueId);
                request.attachment = DisplayPresentations.forOutput(reference.id);
                if (request.attachment != null) {
                    request.source = DesktopDisplayCatalog.require(request.attachment.source.id,
                            request.attachment.source.uniqueId);
                    request.main.post(() -> launch(context, request));
                    return;
                }
                final DisplayProfiles.CreationDefaults defaults = DisplayProfiles.desktopCreationDefaults(reference,
                        DisplayMetrics.DENSITY_DEVICE_STABLE);
                request.source = selectSource(reference, DesktopDisplayCatalog.read(), defaults.originProfileKey);
                if (request.source != null) {
                    request.main.post(() -> launch(context, request));
                    return;
                }
                final VirtualDisplaySpec spec = defaults.spec(defaults.width, defaults.height, defaults.densityDpi, false);
                DisplayOperations.createDisplay(spec, false, (source, error) -> request.main.post(() -> {
                    request.source = source;
                    if (source == null || error != null) { request.finish(error == null ? "Display creation failed" : error); return; }
                    launch(context, request);
                }));
            } catch (Exception error) {
                request.main.post(() -> request.finish(ShellAccess.usefulMessage(error)));
            }
        });
    }

    private static DesktopDisplayInfo selectSource(DesktopDisplayInfo output,
            DesktopDisplayInfo[] catalog, String origin) throws IOException {
        DesktopDisplayInfo best = null;
        int bestCount = Integer.MAX_VALUE;
        boolean bestOrigin = false;
        TaskRepository.Snapshot all = null;
        for (DesktopDisplayInfo candidate : catalog) {
            if (!candidate.owned || !"virtual".equals(candidate.source) || !candidate.canHostDesktop
                    || candidate.width != output.width || candidate.height != output.height
                    || !DisplayPresentations.canAttachOutput(candidate, output)) continue;
            int count = 0;
            if (DesktopRuntimeBridge.hasWorkspace(candidate.id)) {
                if (all == null) all = TaskRepository.loadAllNow();
                if (!all.available) throw new IOException(all.error);
                final TaskRepository.Snapshot managed = MagicDeskRuntime.selectDesktopTaskSnapshot(candidate.id, all);
                if (!managed.available) throw new IOException(managed.error);
                count = (int) managed.tasks.stream().filter(task -> task.displayId == candidate.id
                        && DesktopManagedTaskPolicy.isManagedApplicationTask(task)).count();
            }
            final boolean sameOrigin = origin.equals(DisplayProfiles.origin(
                    DisplayProfileStore.load(DisplayProfiles.key(candidate), candidate.densityDpi)));
            if (best == null || count < bestCount || count == bestCount
                    && (sameOrigin && !bestOrigin || sameOrigin == bestOrigin && candidate.id < best.id)) {
                best = candidate;
                bestCount = count;
                bestOrigin = sameOrigin;
            }
        }
        return best;
    }

    private static void launch(Context context, Request request) {
        try {
            DesktopOperations.showDesktop(request.source, result -> request.main.post(() -> {
                if (!result.success) { request.finish(result.message); return; }
                // Startup owns HOME and automatic phone UI. Attach comes last.
                DisplayPresentations.attachForDesktop(context, request.source, request.output, request.attachment,
                        error -> request.main.post(() -> request.finish(error == null ? null : ShellAccess.usefulMessage(error))));
            }));
        } catch (RuntimeException error) { request.finish(ShellAccess.usefulMessage(error)); }
    }

    private static boolean sameDisplay(DesktopDisplayInfo first, DesktopDisplayInfo second) {
        return first.id == second.id && first.uniqueId.equals(second.uniqueId);
    }

    private static final class Request {
        final Handler main;
        final DesktopDisplayInfo output;
        final List<Callback> callbacks = new ArrayList<>();
        DesktopDisplayInfo source;
        DisplayPresentations.Session attachment;
        boolean finished;
        Request(Handler main, DesktopDisplayInfo output, Callback callback) {
            this.main = main;
            this.output = output;
            callbacks.add(callback);
        }
        void finish(String error) {
            if (finished) return;
            finished = true;
            sPending = null;
            for (Callback callback : callbacks) callback.onComplete(source,
                    error == null || source == null ? error : retained(source, error));
        }
    }

    private static String retained(DesktopDisplayInfo source, String error) {
        return "Display " + source.id + " [" + source.uniqueId + "] retained: " + error;
    }
}
