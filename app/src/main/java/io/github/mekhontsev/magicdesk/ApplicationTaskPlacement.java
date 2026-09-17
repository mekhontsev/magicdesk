package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import java.io.IOException;

/** Shared application placement. Desktop owns its tasks, Android owns the rest. */
final class ApplicationTaskPlacement {
    private ApplicationTaskPlacement() { }

    static TaskRepository.Snapshot independentSnapshot(final TaskRepository.Snapshot all,
            final int displayId, final boolean desktop) {
        if (!all.available) { return all; }
        final var owned = desktop ? MagicDeskRuntime.selectDesktopTaskSnapshot(displayId, all) : null;
        if (owned != null && !owned.available) { return owned; }
        final java.util.Set<Integer> ids = new java.util.HashSet<>();
        if (owned != null) { for (final var task : owned.tasks) { ids.add(task.taskId); } }
        return new TaskRepository.Snapshot(all.tasks.stream()
                .filter(task -> task.displayId == displayId && !ids.contains(task.taskId)
                        && DesktopManagedTaskPolicy.isControllableApplicationTask(task))
                .toList(), true, "");
    }

    static boolean isManaged(final TaskRepository.TaskEntry task) throws IOException {
        if (!DesktopRuntimeBridge.hasWorkspace(task.displayId)) { return false; }
        final TaskRepository.Snapshot snapshot = MagicDeskRuntime.selectDesktopTaskSnapshot(
                task.displayId, TaskRepository.loadNow(task.displayId));
        if (!snapshot.available) { throw new IOException(snapshot.error); }
        return snapshot.tasks.stream().anyMatch(owned -> owned.taskId == task.taskId
                && owned.userId == task.userId);
    }

    static String ownership(final TaskRepository.TaskEntry task, final TaskRepository.Snapshot snapshot) {
        if (!DesktopManagedTaskPolicy.isControllableApplicationTask(task)) { return "system"; }
        if (!DesktopRuntimeBridge.hasWorkspace(task.displayId)) { return "independent"; }
        final var owned = MagicDeskRuntime.selectDesktopTaskSnapshot(task.displayId, snapshot);
        if (!owned.available) { return "unknown"; }
        return owned.tasks.stream().anyMatch(candidate -> candidate.taskId == task.taskId
                && candidate.userId == task.userId) ? "desktop" : "independent";
    }

    static TaskRepository.TaskEntry requireLive(final TaskRepository.TaskEntry requested)
            throws IOException {
        final TaskRepository.Snapshot snapshot = TaskRepository.loadAllNow();
        if (!snapshot.available) { throw new IOException(snapshot.error); }
        for (final TaskRepository.TaskEntry task : snapshot.tasks) {
            if (task.taskId == requested.taskId && task.userId == requested.userId
                    && task.packageName.equals(requested.packageName)
                    && task.displayId == requested.displayId && TaskRepository.isTransferable(task)) {
                return task;
            }
        }
        throw new IOException("task moved or closed before placement");
    }

    static void release(final TaskRepository.TaskEntry task) throws IOException {
        if (isManaged(task)) { ShellAccess.releaseDesktopTasks(task.displayId, new int[]{task.taskId}); }
    }

    static void prepare(final TaskRepository.TaskEntry requested, final ToolLaunchTarget target,
            final String uniqueId, final DesktopLaunchPresentation presentation) throws IOException {
        if (DesktopOperations.isSessionTransitionInProgress()) {
            throw new IOException("desktop transition is in progress");
        }
        target.requireCurrent(DesktopRuntimeBridge.workspaceDisplayIds());
        DesktopDisplayCatalog.require(target.displayId, uniqueId);
        final TaskRepository.TaskEntry task = requireLive(requested);
        final boolean managed = isManaged(task);
        if (!target.desktop) {
            OrdinaryActivityLaunch.requirePresentation(presentation);
            if (managed) { ShellAccess.releaseDesktopTasks(task.displayId, new int[]{task.taskId}); }
            ShellAccess.moveOrdinaryTask(task, target.displayId);
            return;
        }
        final AppIdentity application = AppProfile.current(MagicDeskApplication.applicationContext()).application(task);
        if (application == null) { throw new IOException("task belongs to another profile"); }
        final int density = DesktopTaskPresentationPolicy.resolveDensityDpi(application, target.displayId);
        final boolean fullscreen = presentation.mode == DesktopLaunchMode.FULLSCREEN
                || presentation.mode == DesktopLaunchMode.AUTO && managed && !task.isFreeform();
        final Rect bounds = !fullscreen && task.displayId == target.displayId
                && task.isFreeform() && task.hasBounds() && presentation.bounds == null
                ? new Rect(task.bounds)
                : fullscreen ? null : FloatingWindowController.getWindowBounds(target.displayId, presentation.bounds);
        if (task.displayId != target.displayId) {
            if (managed) { ShellAccess.releaseDesktopTasks(task.displayId, new int[]{task.taskId}); }
            if (fullscreen) {
                DesktopTaskTransfer.moveFullscreen(task.taskId, task.displayId, target.displayId, density);
            } else {
                DesktopTaskTransfer.moveFreeform(task.taskId, task.displayId, target.displayId, bounds, density);
            }
        } else if (managed && (fullscreen ? !task.isFreeform() : task.isFreeform())
                && presentation.bounds == null) {
            return;
        }
        final boolean attached = fullscreen
                ? MagicDeskRuntime.attachFullscreenTask(target.displayId, task.taskId, density)
                : MagicDeskRuntime.attachWindowedTask(target.displayId, task.taskId, bounds, density);
        if (!attached) { throw new IOException("could not attach application to Desktop"); }
    }

    static void place(final TaskRepository.TaskEntry task, final ToolLaunchTarget target,
            final String uniqueId, final DesktopLaunchPresentation presentation,
            final TaskRepository.ActionCallback callback) {
        TaskCommandQueue.execute(() -> {
            try {
                prepare(task, target, uniqueId, presentation);
                if (target.desktop) { MagicDeskRuntime.focusDesktopTask(target.displayId, task.taskId, callback); }
                else if (callback != null) { callback.onComplete(new TaskRepository.ActionResult(true, "application placed")); }
            } catch (IOException | RuntimeException error) {
                if (callback != null) { callback.onComplete(new TaskRepository.ActionResult(false, ShellAccess.usefulMessage(error))); }
            }
        });
    }

    static void controlIndependent(final TaskRepository.TaskEntry requested,
            final String displayUniqueId, final boolean close,
            final TaskRepository.ActionCallback callback) {
        TaskCommandQueue.execute(() -> {
            try {
                if (DesktopOperations.isSessionTransitionInProgress()) {
                    throw new IOException("desktop transition is in progress");
                }
                final var task = requireLive(requested);
                DesktopDisplayCatalog.require(task.displayId, displayUniqueId);
                if (isManaged(task)) { throw new IOException("task now belongs to Desktop"); }
                if (close) { ShellAccess.run(TaskRepository.createTaskControlCommand("remove", task.taskId)); }
                else { ShellAccess.moveOrdinaryTask(task, task.displayId); }
                callback.onComplete(new TaskRepository.ActionResult(true, close ? "close requested" : "show requested"));
            } catch (IOException | RuntimeException error) {
                callback.onComplete(new TaskRepository.ActionResult(false, ShellAccess.usefulMessage(error)));
            }
        });
    }

    static TaskRepository.TaskEntry selectExisting(final LaunchActivityIdentity identity,
            final TaskRepository.Snapshot snapshot, final int displayId) {
        TaskRepository.TaskEntry otherDisplay = null;
        for (final var task : snapshot.tasks) {
            if (!task.home && identity.matchesTask(task)) {
                if (task.displayId == displayId) { return task; }
                if (otherDisplay == null) { otherDisplay = task; }
            }
        }
        return otherDisplay;
    }

    static void rejectExistingInstance(final LaunchActivityIdentity identity) {
        // A restricted Activity may reuse an existing task despite new-task
        // flags. Do not silently turn an unverified new-instance request into reuse.
        if (!ShellAccess.isReady()) {
            throw new IllegalStateException("Shell access is required to verify a new instance of this app. "
                    + "Open it without requesting a new window.");
        }
        final TaskRepository.Snapshot snapshot = TaskRepository.loadAllNow();
        if (!snapshot.available) {
            throw new IllegalStateException("Could not check existing application tasks: " + snapshot.error);
        }
        if (selectExisting(identity, snapshot, -1) != null) {
            throw new IllegalArgumentException(
                    "This app is already open and does not support another window. "
                            + "Open it without requesting a new window.");
        }
    }

    static void prepareIndependentLaunch(final Context context, final Intent intent,
            final int displayId) throws IOException {
        if (intent.getComponent() == null || (intent.getFlags() & Intent.FLAG_ACTIVITY_MULTIPLE_TASK) != 0) {
            return;
        }
        final TaskRepository.Snapshot snapshot = TaskRepository.loadAllNow();
        if (!snapshot.available) { throw new IOException(snapshot.error); }
        final var identity = LaunchActivityIdentity.resolve(AppProfile.current(context).userId,
                context.getPackageManager(), intent.getComponent());
        final var task = selectExisting(identity, snapshot, displayId);
        if (task != null && (task.displayId != displayId || task.isFreeform() || isManaged(task))) {
            prepare(task, ToolLaunchTarget.resolve("display", displayId, java.util.Set.of()),
                    null, DesktopLaunchPresentation.automatic());
        }
    }
}
