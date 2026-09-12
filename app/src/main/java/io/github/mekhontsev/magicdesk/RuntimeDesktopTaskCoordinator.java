package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.os.Handler;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Owns a task controller per workspace; preparation can precede its HOME host. */
final class RuntimeDesktopTaskCoordinator {
    enum Mode { DISABLED, OBSERVING, ACTIVE }

    private final Context mContext;
    private final Handler mHandler;
    private final Runnable mTaskStackChanged;
    private final Consumer<DesktopWorkspaceRuntime> mDesktopPrepared;
    private final Map<Integer, Entry> mEntries = new java.util.concurrent.ConcurrentHashMap<>();
    private final DesktopTaskParkingController mParking = new DesktopTaskParkingController();
    private boolean mDestroyed;

    RuntimeDesktopTaskCoordinator(final Context context, final Handler handler,
            final Runnable taskStackChanged,
            final Consumer<DesktopWorkspaceRuntime> desktopPrepared) {
        mContext = context;
        mHandler = handler;
        mTaskStackChanged = taskStackChanged;
        mDesktopPrepared = desktopPrepared;
    }

    void prepare(final int displayId) {
        if (mDestroyed || displayId < 0) {
            throw new IllegalStateException("invalid desktop preparation");
        }
        mEntries.computeIfAbsent(displayId, Entry::new).tasks.setTaskWatcherEnabled(true);
    }

    void reconcile(final List<DesktopSessionSnapshot> sessions, final boolean shellReady) {
        if (mDestroyed) { return; }
        for (final DesktopSessionSnapshot session : sessions) {
            if (session.target() != null) {
                mEntries.computeIfAbsent(session.target().workspaceDisplayId, Entry::new);
            }
        }
        for (final Entry entry : List.copyOf(mEntries.values())) {
            final DesktopSessionSnapshot session = DesktopRuntimeBridge.getSessionSnapshot(entry.displayId);
            final DesktopWorkspaceRuntime workspace = DesktopRuntimeBridge.getWorkspaceRuntime(entry.displayId);
            if (workspace != entry.workspace) {
                entry.workspace = workspace;
                entry.preparedHostTaskId = -1;
                if (workspace != null && session.target() != null) {
                    mParking.restoreWhenReady(session.target());
                }
            }
            final Mode mode = modeFor(session, shellReady);
            if (mode == Mode.ACTIVE) {
                entry.tasks.setTaskWatcherEnabled(true);
                entry.tasks.start(entry.displayId);
            } else {
                entry.preparedHostTaskId = -1;
                if (entry.mode == Mode.ACTIVE) { entry.tasks.stop(); }
                entry.tasks.setTaskWatcherEnabled(mode == Mode.OBSERVING);
            }
            entry.mode = mode;
        }
    }

    void destroy() {
        if (mDestroyed) { return; }
        mDestroyed = true;
        for (final Entry entry : mEntries.values()) { entry.tasks.destroy(); }
        mEntries.clear();
        mParking.clear();
    }

    void releaseSession(final Runnable completion) {
        final List<Entry> entries = List.copyOf(mEntries.values());
        mEntries.clear();
        releaseEntries(entries, 0, completion);
    }

    private void releaseEntries(final List<Entry> entries, final int index, final Runnable completion) {
        if (index == entries.size()) { completion.run(); return; }
        releaseEntry(entries.get(index), () -> releaseEntries(entries, index + 1, completion));
    }

    void releaseWorkspace(final DesktopWorkspaceRuntime workspace, final Runnable completion) {
        final Entry entry = workspace == null ? null : mEntries.get(workspace.displayId);
        final DesktopWorkspaceRuntime admitted = workspace == null ? null
                : DesktopRuntimeBridge.getWorkspaceRuntime(workspace.displayId);
        if (entry == null || !canReleaseWorkspace(workspace, entry.workspace, admitted)) {
            completion.run();
            return;
        }
        mEntries.remove(workspace.displayId, entry);
        releaseEntry(entry, completion);
    }

    void releaseFailedPreparation(final int displayId, final Runnable completion) {
        final Entry entry = mEntries.get(displayId);
        if (entry == null || DesktopRuntimeBridge.hasWorkspace(displayId)) {
            completion.run();
            return;
        }
        mEntries.remove(displayId, entry);
        releaseEntry(entry, completion);
    }

    private void releaseEntry(final Entry entry, final Runnable completion) {
        mParking.retire(entry.displayId);
        entry.tasks.stop();
        entry.tasks.setTaskWatcherEnabled(false, () -> mHandler.post(() -> {
            entry.tasks.destroy();
            completion.run();
        }));
    }

    static boolean canReleaseWorkspace(final DesktopWorkspaceRuntime requested,
            final DesktopWorkspaceRuntime active, final DesktopWorkspaceRuntime admitted) {
        return requested != null && (active == null || active == requested)
                && (admitted == null || admitted == requested);
    }

    DesktopTaskRuntime operations(final int displayId) {
        final Entry entry = mEntries.get(displayId);
        return entry == null ? null : entry.tasks;
    }

    DesktopTaskParkingRuntime parking() { return mParking; }

    static Mode modeFor(final DesktopSessionSnapshot session, final boolean shellReady) {
        if (!shellReady) { return Mode.DISABLED; }
        return session != null && session.hasHost() ? Mode.ACTIVE : Mode.OBSERVING;
    }

    private final class Entry {
        final int displayId;
        final DesktopTaskController tasks;
        Mode mode = Mode.OBSERVING;
        DesktopWorkspaceRuntime workspace;
        int preparedHostTaskId = -1;

        Entry(final int displayId) {
            this.displayId = displayId;
            tasks = new DesktopTaskController(displayId, mContext, mHandler, mTaskStackChanged,
                    (id, observed, workArea, ownershipReady, ownedTaskIds) -> {
                        mParking.observe(id, observed, workArea, ownershipReady, ownedTaskIds);
                        final DesktopSessionSnapshot session = DesktopRuntimeBridge.getSessionSnapshot(id);
                        final int hostTaskId = session.hostTaskId();
                        if (hostTaskId >= 0 && hostTaskId != preparedHostTaskId
                                && workspace != null && !workspace.isClosed()
                                && workspace == DesktopRuntimeBridge.getWorkspaceRuntime(id)
                                && ownershipReady && DesktopRuntimeBridge.isDesktopReadyOnDisplay(id)
                                && mParking.isWorkspacePrepared(session)) {
                            preparedHostTaskId = hostTaskId;
                            mDesktopPrepared.accept(workspace);
                        }
                    });
        }
    }
}
