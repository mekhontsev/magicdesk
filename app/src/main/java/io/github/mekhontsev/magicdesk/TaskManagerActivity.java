package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** Lifecycle-scoped observation; resource sampling never creates a Desktop task observer. */
public final class TaskManagerActivity extends Activity implements ShellAccess.StateListener {
    private static final long REFRESH_INTERVAL_MILLIS = 3000;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final SystemMonitorRepository mMonitor = new SystemMonitorRepository();
    private final Runnable mScheduledRefresh = () -> refresh(false);
    private TaskManagerView mView;
    private TaskManagerActions mActions;
    private TaskRepository.Snapshot mStandaloneSnapshot;
    private TmuxSessionProvider.Snapshot mTmux;
    private String mTmuxError = "";
    private boolean mStarted, mDestroyed, mLoading, mTmuxLoading;
    private int mLoadGeneration;
    private int mSessionGeneration;

    static Intent createIntent(Context context) { return new Intent(context, TaskManagerActivity.class); }
    static AppLaunchTarget launchTarget() { return BuiltInDesktopAppCatalog.taskManagerTarget(); }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        DesktopTaskDescription.apply(this, R.string.task_manager_title, R.drawable.ic_magicdesk);
        BuiltInWindowRegistry.register(this);
        mActions = new TaskManagerActions(this, this::refresh, entry -> mView.showProcesses(entry.processes(), entry.title()));
        mView = new TaskManagerView(this, this::refresh, mActions);
        setContentView(mView.root());
    }
    @Override protected void onStart() {
        super.onStart(); mStarted = true; mSessionGeneration++; ShellAccess.addStateListener(this);
    }
    @Override protected void onStop() {
        mStarted = false; mLoading = false; mTmuxLoading = false;
        mStandaloneSnapshot = null; mLoadGeneration++; mSessionGeneration++;
        mHandler.removeCallbacks(mScheduledRefresh);
        ShellAccess.removeStateListener(this);
        super.onStop();
    }
    @Override protected void onDestroy() {
        mDestroyed = true; mLoadGeneration++;
        mHandler.removeCallbacks(mScheduledRefresh);
        mMonitor.close(); BuiltInWindowRegistry.unregister(this); super.onDestroy();
    }
    @Override public void onShellStateChanged(ShellAccess.Snapshot snapshot) {
        runOnUiThread(() -> {
            if (!mStarted || mDestroyed) return;
            if (snapshot != null && snapshot.isReady()) refresh();
            else {
                mLoading = false; mLoadGeneration++; mSessionGeneration++; mTmuxLoading = false; mStandaloneSnapshot = null;
                mHandler.removeCallbacks(mScheduledRefresh);
                mView.showUnavailable(snapshot == null ? "unknown" : snapshot.error);
            }
        });
    }

    private void refresh() { refresh(true); }
    private void refresh(boolean requestTasks) {
        mHandler.removeCallbacks(mScheduledRefresh);
        if (!mStarted || mDestroyed || mLoading) return;
        if (!ShellAccess.isReady()) { mView.showUnavailable("Shell access unavailable"); return; }
        mLoading = true;
        final int generation = ++mLoadGeneration;
        if (requestTasks) refreshTmux();
        if (requestTasks && DesktopRuntimeBridge.workspaceDisplayIds().isEmpty()) {
            TaskRepository.load(-1, snapshot -> runOnUiThread(() -> {
                if (!current(generation)) return;
                mStandaloneSnapshot = DesktopRuntimeBridge.workspaceDisplayIds().isEmpty() ? snapshot : null;
                refreshMonitor(generation);
            }));
        } else refreshMonitor(generation);
    }

    private void refreshTmux() {
        if (mTmuxLoading) return;
        final var endpoint = TermuxIntegration.inspect(this);
        if (!endpoint.available()) { mTmux = null; mTmuxError = ""; return; }
        mTmuxLoading = true;
        final int generation = mSessionGeneration;
        TmuxSessionProvider.list(this, (snapshot, error) -> runOnUiThread(() -> {
            if (!mStarted || mDestroyed || generation != mSessionGeneration) return;
            mTmuxLoading = false;
            mTmux = snapshot;
            mTmuxError = error == null ? "" : "tmux: " + ShellAccess.usefulMessage(error);
        }));
    }

    private void refreshMonitor(int generation) {
        mMonitor.load(monitor -> runOnUiThread(() -> {
            if (!current(generation)) return;
            final TaskRepository.Snapshot tasks = taskSnapshot();
            final var entries = TaskManagerApplications.collect(this,
                    tasks != null && tasks.available ? allTasks(tasks) : List.of(),
                    ConsoleTerminalRegistry.list(), mTmux, monitor, getTaskId());
            final String taskError = tasks == null ? "Window observation unavailable"
                    : tasks.available ? "" : tasks.error;
            mView.render(entries, monitor, String.join(" ", taskError, mTmuxError).trim());
            mLoading = false;
            mHandler.postDelayed(mScheduledRefresh, REFRESH_INTERVAL_MILLIS);
        }));
    }

    private boolean current(int generation) { return mStarted && !mDestroyed && generation == mLoadGeneration; }

    private TaskRepository.Snapshot taskSnapshot() {
        final var displays = DesktopRuntimeBridge.workspaceDisplayIds();
        if (displays.isEmpty()) return mStandaloneSnapshot;
        mStandaloneSnapshot = null;
        final var tasks = new LinkedHashMap<Integer, TaskRepository.TaskEntry>();
        for (int display : displays) {
            final var observed = MagicDeskRuntime.observedTaskSnapshot(display);
            if (observed == null || !observed.available)
                return new TaskRepository.Snapshot(List.of(), false, observed == null ? "Window observation unavailable" : observed.error);
            for (var task : allTasks(observed)) tasks.put(task.taskId, task);
        }
        return new TaskRepository.Snapshot(new ArrayList<>(tasks.values()), true, "");
    }

    static List<TaskRepository.TaskEntry> allTasks(TaskRepository.Snapshot snapshot) {
        final var tasks = new LinkedHashMap<Integer, TaskRepository.TaskEntry>();
        for (var task : snapshot.tasks) tasks.put(task.taskId, task);
        for (var task : snapshot.phoneTasks) tasks.putIfAbsent(task.taskId, task);
        return new ArrayList<>(tasks.values());
    }
}
