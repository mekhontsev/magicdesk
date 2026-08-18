package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public final class TaskManagerActivity extends Activity
        implements ShellAccess.StateListener {
    private static final long REFRESH_INTERVAL_MILLIS = 3_000L;
    private static final int PROCESS_MEMORY_REFRESH_CYCLES = 4;

    private TaskManagerView mView;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final SystemMonitorRepository mMonitor =
            new SystemMonitorRepository();
    private final Runnable mScheduledRefresh = this::refresh;
    private boolean mDestroyed;
    private boolean mStarted;
    private boolean mLoading;
    private boolean mHasRenderedContent;
    private int mLoadGeneration;
    private int mRefreshCycle;

    static Intent createIntent(final Context context) {
        return new Intent(context, TaskManagerActivity.class);
    }

    static AppLaunchTarget launchTarget() {
        return BuiltInDesktopAppCatalog.taskManagerTarget();
    }

    @Override
    protected void onCreate(final Bundle state) {
        super.onCreate(state);
        DesktopTaskDescription.apply(
                this,
                R.string.task_manager_title,
                R.drawable.ic_magicdesk);
        BuiltInWindowRegistry.register(this);
        mView = new TaskManagerView(
                this,
                this::refresh,
                new TaskManagerView.Actions() {
                    @Override
                    public void focus(final TaskRepository.TaskEntry task) {
                        TaskManagerActivity.this.focus(task);
                    }

                    @Override
                    public void openLogs(final TaskRepository.TaskEntry task) {
                        TaskManagerActivity.this.openLogs(task);
                    }

                    @Override
                    public void close(final TaskRepository.TaskEntry task) {
                        closeTask(task);
                    }

                    @Override
                    public void forceStop(
                            final TaskRepository.TaskEntry task) {
                        confirmForceStop(task);
                    }
                });
        setContentView(mView.root());
    }

    @Override
    protected void onStart() {
        super.onStart();
        mStarted = true;
        ShellAccess.addStateListener(this);
    }

    @Override
    protected void onStop() {
        mStarted = false;
        mLoading = false;
        mLoadGeneration++;
        mHandler.removeCallbacks(mScheduledRefresh);
        ShellAccess.removeStateListener(this);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        mDestroyed = true;
        mLoadGeneration++;
        mHandler.removeCallbacks(mScheduledRefresh);
        mMonitor.close();
        BuiltInWindowRegistry.unregister(this);
        super.onDestroy();
    }

    @Override
    public void onShellStateChanged(final ShellAccess.Snapshot snapshot) {
        runOnUiThread(() -> {
            if (mDestroyed || !mStarted) {
                return;
            }
            if (snapshot != null && snapshot.isReady()) {
                refresh();
            } else {
                mLoading = false;
                mLoadGeneration++;
                mHandler.removeCallbacks(mScheduledRefresh);
                mView.showUnavailable(
                        snapshot == null ? "unknown" : snapshot.error);
            }
        });
    }

    private void refresh() {
        mHandler.removeCallbacks(mScheduledRefresh);
        if (!mStarted || mDestroyed) {
            return;
        }
        if (!ShellAccess.isReady()) {
            mView.showWaiting();
            return;
        }
        if (mLoading) {
            return;
        }
        mLoading = true;
        if (!mHasRenderedContent) {
            mView.showInitialLoading();
        }
        final int generation = ++mLoadGeneration;
        final boolean includeProcessMemory =
                !mHasRenderedContent
                        || ++mRefreshCycle
                        % PROCESS_MEMORY_REFRESH_CYCLES == 0;
        TaskRepository.load(-1, snapshot -> runOnUiThread(() -> {
            if (mDestroyed || generation != mLoadGeneration) {
                return;
            }
            if (!snapshot.available) {
                mView.showUnavailable(snapshot.error);
                finishRefresh();
                return;
            }
            mMonitor.load(includeProcessMemory, monitor -> runOnUiThread(() -> {
                if (mDestroyed || generation != mLoadGeneration) {
                    return;
                }
                render(snapshot.tasks, monitor);
                finishRefresh();
            }));
        }));
    }

    private void finishRefresh() {
        mLoading = false;
        if (mStarted && ShellAccess.isReady()) {
            mHandler.postDelayed(
                    mScheduledRefresh,
                    REFRESH_INTERVAL_MILLIS);
        }
    }

    private void render(
            final List<TaskRepository.TaskEntry> rawTasks,
            final SystemMonitorRepository.Snapshot monitor) {
        final List<TaskRepository.TaskEntry> tasks = new ArrayList<>();
        for (final TaskRepository.TaskEntry task : rawTasks) {
            if (DesktopManagedTaskPolicy.isManagedApplicationTask(task)
                    && task.taskId != getTaskId()) {
                tasks.add(task);
            }
        }
        mView.render(tasks, monitor);
        mHasRenderedContent = true;
    }

    private void focus(final TaskRepository.TaskEntry task) {
        TaskRepository.bringToFront(task, this::showActionResult);
    }

    private void closeTask(final TaskRepository.TaskEntry task) {
        TaskRepository.closeTask(task, result -> {
            showActionResult(result);
            if (result.success) {
                runOnUiThread(this::refresh);
            }
        });
    }

    private void confirmForceStop(final TaskRepository.TaskEntry task) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.task_manager_force_stop)
                .setMessage(getString(
                        R.string.task_manager_force_stop_message,
                        mView.labelForPackage(task.packageName)))
                .setPositiveButton(R.string.task_manager_force_stop,
                        (dialog, which) -> TaskRepository.forceStop(
                                task.packageName,
                                result -> {
                                    showActionResult(result);
                                    if (result.success) {
                                        runOnUiThread(this::refresh);
                                    }
                                }))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void openLogs(final TaskRepository.TaskEntry task) {
        BuiltInWindowLauncher.launch(
                this,
                AppLogViewerActivity.createIntent(
                        this,
                        task.packageName,
                        mView.labelForPackage(task.packageName)),
                AppLogViewerActivity.launchTarget(),
                error -> {
                    if (error != null) {
                        Toast.makeText(
                                this,
                                getString(
                                        R.string.task_manager_action_failed,
                                        ShellAccess.usefulMessage(error)),
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void showActionResult(final TaskRepository.ActionResult result) {
        runOnUiThread(() -> {
            if (!mDestroyed && !result.success) {
                Toast.makeText(
                        this,
                        getString(
                                R.string.task_manager_action_failed,
                                result.message),
                        Toast.LENGTH_LONG).show();
            }
        });
    }
}
