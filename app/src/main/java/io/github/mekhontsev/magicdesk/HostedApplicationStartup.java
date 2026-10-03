package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** Launch-owned window admission. Activities borrow clients; they never start their processes. */
final class HostedApplicationStartup {
    private static final Map<String, HostedApplicationStartup> PENDING = new HashMap<>();
    private final DesktopLaunchContext host;
    private final DesktopLaunchRequest request;
    private final GraphicalSessions.Session session;
    private final Context displayContext;
    private final HostedLaunchWindows windows = new HostedLaunchWindows();
    private final Runnable listener = this::changed;
    private final ArrayList<DesktopActivityLaunchResult.Completion> completions = new ArrayList<>();
    private final ToolLaunchTarget destination;
    private boolean ended, primarySubmitted;
    private DesktopActivityLaunchResult outcome;

    static boolean join(GraphicalSessions.Session session, DesktopLaunchContext host,
            DesktopActivityLaunchResult.Completion completion) {
        final HostedApplicationStartup pending;
        synchronized (PENDING) { pending = PENDING.get(session.id()); }
        if (pending == null) return false;
        host.onMain(() -> {
            if (pending.destination.displayId != host.destination().displayId
                    || pending.destination.desktop != host.destination().desktop) {
                var error = new IllegalStateException("Application startup is already assigned to another destination");
                if (completion == null) host.onFailure(pending.request, error);
                else completion.onComplete(DesktopActivityLaunchResult.failed(error));
            } else if (completion != null) {
                if (pending.ended) completion.onComplete(pending.outcome);
                else pending.completions.add(completion);
            }
        });
        return true;
    }

    static void start(DesktopLaunchContext host, DesktopLaunchRequest request,
            DesktopActivityLaunchResult.Completion completion) {
        host.onMain(() -> {
            try {
                if (host.isUnavailable()) throw new IllegalStateException("Launch host has closed");
                var destination = host.destination();
                if (!destination.desktop) OrdinaryActivityLaunch.requirePresentation(request.presentation);
                InteractiveActivityLaunch.requireDestination(host.context(), destination.displayId, host.destinationUniqueId());
                var display = host.context().getSystemService(DisplayManager.class).getDisplay(destination.displayId);
                if (display == null) throw new IllegalStateException("Launch display has disconnected");
                Context context = host.context().createDisplayContext(display);
                var recipe = request.sourceShortcut == null ? null
                        : RecentApplications.describe(context, request.sourceShortcut, request.desktopFilePath);
                if (recipe != null) RecentApplications.requireEnvironment(context, recipe);
                var session = GraphicalSessions.startApplication(context, request, recipe);
                new HostedApplicationStartup(host, request, session, context, destination, completion).begin();
            } catch (java.io.IOException | RuntimeException error) {
                if (completion == null) host.onFailure(request, error);
                else completion.onComplete(DesktopActivityLaunchResult.failed(error));
            }
        });
    }

    private HostedApplicationStartup(DesktopLaunchContext host, DesktopLaunchRequest request,
            GraphicalSessions.Session session, Context context, ToolLaunchTarget destination,
            DesktopActivityLaunchResult.Completion completion) {
        this.host = host; this.request = request; this.session = session;
        this.displayContext = context; this.destination = destination;
        if (completion != null) completions.add(completion);
    }

    private void begin() {
        synchronized (PENDING) { PENDING.put(session.id(), this); }
        session.presentation().beginLaunch();
        session.listen(listener);
        host.onStarted(request);
        changed();
    }

    private void changed() {
        if (ended || primarySubmitted) return;
        try {
            var redirect = session.redirect();
            if (redirect != null) {
                primarySubmitted = true;
                GraphicalApplicationLaunch.reopen(host, request, redirect, result -> {
                    if (result.succeeded()) redirect.session().recordUse(redirect.window(), RecentLaunchScope.of(destination));
                    complete(result);
                });
                return;
            }
            if (session.stopped()) {
                complete(DesktopActivityLaunchResult.failed(session.error().isEmpty()
                        ? "Application ended before opening its main window" : session.error()));
                return;
            }
            if (!session.ready()) return;
            if (host.isUnavailable()) throw new IllegalStateException("Launch host has closed");
            if (request.exec.graphics.desktop() && session.protocol() == GraphicalProtocol.X11) {
                launch(0, true, false); return;
            }
            var catalog = session.windows();
            var offers = windows.update(catalog.stream().map(window -> new HostedLaunchWindows.Window(
                    window.id(), window.mapped(), HostedLaunchWindows.Role.valueOf(
                            window.role().toUpperCase(java.util.Locale.ROOT)))).toList());
            for (var offer : offers) launch(offer.id(), offer.primary(), offer.replacesTemporary());
        } catch (RuntimeException error) { complete(DesktopActivityLaunchResult.failed(error)); }
    }

    private void launch(long window, boolean primary, boolean replacesTemporary) {
        if (ended) return;
        if (primary) primarySubmitted = true;
        var intent = session.windowIntent(host.context(), window);
        var presentation = request.presentation.withInstancePolicy(DesktopTaskInstancePolicy.CREATE_NEW);
        if (!primary) {
            var layout = session.windows().stream().filter(item -> item.id() == window).findFirst().orElseThrow().layout();
            float scale = session.protocol() == GraphicalProtocol.X11 ? 1
                    : (float) HostedUiScale.adjust(HostedUiScale.resolve(displayContext), session.scalePercent());
            HostedStartupWindow.bind(intent, layout, scale, !destination.desktop);
            session.presentation().claimSized(window);
            presentation = destination.desktop
                    ? ToolApplications.hostedWindowPresentation(destination.displayId, layout, scale)
                    : DesktopLaunchPresentation.automatic().withInstancePolicy(DesktopTaskInstancePolicy.CREATE_NEW);
        } else if (replacesTemporary) session.presentation().detach(window);
        var target = AppLaunchTarget.explicit(host.context().getPackageName(), intent.getComponent().getClassName(), "");
        var launch = new DesktopLaunchRequest(request.name, request.icon,
                AndroidLaunchSpec.intent(target, intent.toUri(Intent.URI_INTENT_SCHEME)), null, null,
                presentation, request.arguments, request.desktopFilePath);
        if (!host.launchAndroid(launch, null, result -> {
            if (ended) return;
            // A splash can disappear before its Android launch receipt. It is not the main launch result.
            if (!primary) {
                boolean alive = session.windows().stream().anyMatch(item -> item.id() == window && item.mapped());
                if (!result.succeeded() && alive) complete(result);
            } else {
                if (result.succeeded()) session.recordUse(window, RecentLaunchScope.of(destination));
                complete(result);
            }
        })) complete(DesktopActivityLaunchResult.failed("Application window is unavailable"));
    }

    private void complete(DesktopActivityLaunchResult result) {
        if (ended) return;
        ended = true;
        outcome = result;
        session.unlisten(listener);
        synchronized (PENDING) { PENDING.remove(session.id(), this); }
        if (!result.succeeded()) session.close();
        session.presentation().endLaunch();
        if (completions.isEmpty() && !result.succeeded())
            host.onFailure(request, new IllegalStateException(result.error));
        for (var completion : java.util.List.copyOf(completions)) completion.onComplete(result);
        completions.clear();
    }
}
