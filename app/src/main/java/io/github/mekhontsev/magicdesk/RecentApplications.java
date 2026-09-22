package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.List;
import java.util.Map;
import java.util.EnumMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Shared launch history; no shell, Desktop, HOME, or Termux service prerequisite. */
final class RecentApplications {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "MagicDeskRecents"); thread.setDaemon(true); return thread;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private record Snapshot(List<RecentApplicationStore.Entry> entries, String error) { }
    private static volatile Map<RecentLaunchScope, Snapshot> snapshots = Map.of();

    static List<RecentApplicationStore.Entry> entries(RecentLaunchScope scope) {
        return snapshots.getOrDefault(scope, new Snapshot(List.of(), "")).entries();
    }
    static String error(RecentLaunchScope scope) {
        return snapshots.getOrDefault(scope, new Snapshot(List.of(), "")).error();
    }

    // Only the serial IO owner publishes snapshots, including read/write failures.
    private static void publish(RecentLaunchScope scope, List<RecentApplicationStore.Entry> entries, String error) {
        var next = new EnumMap<RecentLaunchScope, Snapshot>(RecentLaunchScope.class);
        next.putAll(snapshots);
        next.put(scope, new Snapshot(entries, error));
        snapshots = Map.copyOf(next);
    }

    static void refresh(Context context, Runnable complete) {
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            for (var scope : RecentLaunchScope.values()) {
                try { publish(scope, store(app, scope).read(), ""); }
                catch (java.io.IOException | RuntimeException failure) { failed(scope, failure); }
            }
            MAIN.post(complete);
        });
    }

    static RecentApplicationStore.Entry describe(Context context, DesktopApplicationShortcut shortcut, String sourcePath) {
        if (shortcut.application == null && shortcut.launchTarget != null)
            shortcut = shortcut.withApplication(AppProfile.current(context).application(shortcut.launchTarget.packageName));
        String termux = (shortcut.hasExecLaunch() && shortcut.execBackend != DesktopExecBackend.SHELL)
                || BuiltInRecentLaunch.usesTermux(shortcut)
                ? IntegrationPackage.TERMUX.selected() : "";
        return new RecentApplicationStore.Entry(shortcut, sourcePath, termux, System.currentTimeMillis());
    }

    static void record(Context context, RecentApplicationStore.Entry entry, RecentLaunchScope scope) {
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            try { publish(scope, store(app, scope).record(entry), ""); }
            catch (java.io.IOException | RuntimeException failure) { failed(scope, failure); }
        });
    }

    static void removeSource(Context context, String termuxPackage, String sourcePath,
            java.util.function.Consumer<Throwable> complete) {
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            Throwable error = null;
            for (var scope : RecentLaunchScope.values()) {
                try { publish(scope, store(app, scope).removeSource(termuxPackage, sourcePath), ""); }
                catch (java.io.IOException | RuntimeException failure) { failed(scope, failure); error = failure; }
            }
            final Throwable result = error;
            MAIN.post(() -> complete.accept(result));
        });
    }

    static void record(Context context, DesktopLaunchRequest request, RecentLaunchScope scope) {
        if (request.sourceShortcut != null) record(context, request.sourceShortcut, request.desktopFilePath, scope);
    }

    private static void record(Context context, DesktopApplicationShortcut shortcut, String sourcePath, RecentLaunchScope scope) {
        try { record(context, describe(context, shortcut, sourcePath), scope); }
        catch (RuntimeException failure) { IO.execute(() -> failed(scope, failure)); }
    }

    static void recordApp(Context context, AppItem app, RecentLaunchScope scope) {
        record(context, DesktopApplicationShortcut.forApp(app), "", scope);
    }

    static void recordTask(Context context, TaskRepository.TaskEntry task, List<AppItem> apps) {
        // Graphical hosts publish their launch recipe, not the generic manager component.
        if (task.packageName.equals(context.getPackageName())) {
            for (var session : X11Sessions.list()) if (session.recordTaskUse(task.taskId, RecentLaunchScope.DESKTOP)) return;
            for (var session : WaylandSessions.list()) if (session.recordTaskUse(task.taskId, RecentLaunchScope.DESKTOP)) return;
            // Built-in launches retain their own semantic recipe, not a generic Activity.
            return;
        }
        for (AppItem app : apps) if (app.matchesTask(task)) { recordApp(context, app, RecentLaunchScope.DESKTOP); return; }
    }

    static void recordBuiltIn(Context context, android.content.Intent intent, AppLaunchTarget target,
            RecentLaunchScope scope) {
        try {
            recordBuiltInLaunch(context, intent, target, scope);
        } catch (RuntimeException failure) { IO.execute(() -> failed(scope, failure)); }
    }

    private static void recordBuiltInLaunch(Context context, android.content.Intent intent, AppLaunchTarget target,
            RecentLaunchScope scope) {
        if (target.activityClassName.equals(X11Activity.class.getName())) {
            var session = X11Sessions.find(intent.getStringExtra(X11Activity.SESSION));
            if (session != null) session.recordUse(intent.getLongExtra(X11Activity.WINDOW, 0), scope);
            return;
        }
        if (target.activityClassName.equals(WaylandActivity.class.getName())) {
            var session = WaylandSessions.find(intent.getStringExtra(WaylandActivity.SESSION));
            if (session != null) session.recordUse(scope);
            return;
        }
        var shortcut = BuiltInRecentLaunch.describe(context, intent, target);
        if (shortcut != null) record(context, shortcut, "", scope);
    }

    static void requireEnvironment(Context context, RecentApplicationStore.Entry entry) {
        if (entry.shortcut().application != null) entry.shortcut().application.requireProfile(AppProfile.current(context));
        if (!entry.termuxPackage().isEmpty() && !entry.termuxPackage().equals(IntegrationPackage.TERMUX.selected()))
            throw new IllegalStateException("This launch belongs to a different Termux package: " + entry.termuxPackage());
    }

    private static RecentApplicationStore store(Context context, RecentLaunchScope scope) {
        return new RecentApplicationStore(context.getFilesDir().toPath().resolve("recent").resolve(scope.directory));
    }

    private static void failed(RecentLaunchScope scope, Exception failure) {
        String error = "Could not read or save recent applications: " + ShellAccess.usefulMessage(failure);
        publish(scope, entries(scope), error);
        Log.w("MagicDeskRecents", error, failure);
    }
    private RecentApplications() { }
}
