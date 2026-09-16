package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Shared launch history; no shell, Desktop, HOME, or Termux service prerequisite. */
final class RecentApplications {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "MagicDeskRecents"); thread.setDaemon(true); return thread;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile List<RecentApplicationStore.Entry> entries = List.of();
    private static volatile String error = "";

    static List<RecentApplicationStore.Entry> entries() { return entries; }
    static String error() { return error; }

    static void refresh(Context context, Runnable complete) {
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            try { entries = store(app).read(); error = ""; }
            catch (java.io.IOException | RuntimeException failure) { failed(failure); }
            MAIN.post(complete);
        });
    }

    static RecentApplicationStore.Entry describe(Context context, DesktopApplicationShortcut shortcut, String sourcePath) {
        if (shortcut.application == null && shortcut.launchTarget != null)
            shortcut = shortcut.withApplication(AppProfile.current(context).application(shortcut.launchTarget.packageName));
        String termux = shortcut.hasExecLaunch() && shortcut.execBackend != DesktopExecBackend.SHELL
                ? IntegrationPackage.TERMUX.selected() : "";
        return new RecentApplicationStore.Entry(shortcut, sourcePath, termux, System.currentTimeMillis());
    }

    static void record(Context context, RecentApplicationStore.Entry entry) {
        Context app = context.getApplicationContext();
        IO.execute(() -> {
            try { entries = store(app).record(entry); error = ""; }
            catch (java.io.IOException | RuntimeException failure) { failed(failure); }
        });
    }

    static void record(Context context, DesktopLaunchRequest request) {
        if (request.sourceShortcut != null) record(context, request.sourceShortcut, request.desktopFilePath);
    }

    private static void record(Context context, DesktopApplicationShortcut shortcut, String sourcePath) {
        try { record(context, describe(context, shortcut, sourcePath)); }
        catch (RuntimeException failure) { failed(failure); }
    }

    static void recordApp(Context context, AppItem app) {
        record(context, DesktopApplicationShortcut.forApp(app), "");
    }

    static void recordTask(Context context, TaskRepository.TaskEntry task, List<AppItem> apps) {
        // X11 hosts publish their original recipe, not the generic X11 manager component.
        if (task.packageName.equals(context.getPackageName())
                && BuiltInDesktopAppCatalog.find(task) == BuiltInDesktopAppCatalog.findComponent(X11Activity.class.getName())) return;
        for (AppItem app : apps) if (app.matchesTask(task)) { recordApp(context, app); return; }
    }

    static void requireEnvironment(Context context, RecentApplicationStore.Entry entry) {
        if (entry.shortcut().application != null) entry.shortcut().application.requireProfile(AppProfile.current(context));
        if (!entry.termuxPackage().isEmpty() && !entry.termuxPackage().equals(IntegrationPackage.TERMUX.selected()))
            throw new IllegalStateException("This launch belongs to a different Termux package: " + entry.termuxPackage());
    }

    private static RecentApplicationStore store(Context context) {
        return new RecentApplicationStore(context.getFilesDir().toPath().resolve("recent"));
    }

    private static void failed(Exception failure) {
        error = "Could not read or save recent applications: " + ShellAccess.usefulMessage(failure);
        Log.w("MagicDeskRecents", error, failure);
    }
    private RecentApplications() { }
}
