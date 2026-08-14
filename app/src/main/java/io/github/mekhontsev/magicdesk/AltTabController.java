package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class AltTabController {
    private final DesktopShellActivity mActivity;

    private boolean mActive;
    private boolean mLoadInProgress;
    private boolean mCommitPending;
    private int mPendingOffset;
    private int mSelectedIndex = -1;
    private int mStartingTaskId = -1;
    private List<TaskRepository.TaskEntry> mTasks =
            Collections.emptyList();

    AltTabController(final DesktopShellActivity activity) {
        mActivity = activity;
    }

    boolean isSelected(final TaskRepository.TaskEntry task) {
        return mActive
                && mSelectedIndex >= 0
                && mSelectedIndex < mTasks.size()
                && mTasks.get(mSelectedIndex).taskId == task.taskId;
    }

    void advance(final boolean reverse) {
        final int offset = reverse ? -1 : 1;
        if (mActive) {
            if (mLoadInProgress) {
                mPendingOffset += offset;
            } else {
                selectOffset(offset);
                mActivity.populateTaskOverview(
                        mActivity.getTaskSnapshot());
            }
            return;
        }

        mActive = true;
        mLoadInProgress = true;
        mCommitPending = false;
        mPendingOffset = offset;
        mSelectedIndex = -1;
        mTasks = Collections.emptyList();
        // Preserve app focus at key-down. A concurrent desktop-host refresh
        // can make the asynchronous snapshot contain no active app task.
        mStartingTaskId = findActiveTaskId(
                mActivity.getTaskSnapshot());
        mActivity.captureInteractionStackForPanel();
        mActivity.hideAllPanels();

        final int displayId = mActivity.getCurrentDisplayId();
        TaskRepository.load(displayId, snapshot ->
                mActivity.runOnUiThread(() -> {
                    if (!mActive
                            || mActivity.isActivityUnavailable()
                            || displayId
                                    != mActivity.getCurrentDisplayId()) {
                        return;
                    }
                    mLoadInProgress = false;
                    if (!snapshot.available) {
                        reset();
                        mActivity.setStatus(mActivity.getString(
                                R.string.status_switch_failed,
                                snapshot.error.length() == 0
                                        ? "task snapshot"
                                        : snapshot.error));
                        return;
                    }

                    mActivity.setTaskSnapshot(snapshot);
                    final List<TaskRepository.TaskEntry> tasks =
                            new ArrayList<>();
                    for (final TaskRepository.TaskEntry task :
                            snapshot.tasks) {
                        if (mActivity.isAltTabTask(task)) {
                            tasks.add(task);
                        }
                    }
                    if (tasks.isEmpty()) {
                        reset();
                        return;
                    }
                    mTasks = tasks;
                    int activeIndex = -1;
                    for (int index = 0; index < tasks.size(); index++) {
                        if (tasks.get(index).active) {
                            activeIndex = index;
                            break;
                        }
                    }
                    if (activeIndex < 0 && mStartingTaskId >= 0) {
                        activeIndex = findTaskIndex(
                                tasks, mStartingTaskId);
                    }
                    if (activeIndex < 0) {
                        activeIndex = mPendingOffset < 0 ? 0 : -1;
                    }
                    mSelectedIndex = Math.floorMod(
                            activeIndex + mPendingOffset,
                            tasks.size());
                    mPendingOffset = 0;
                    mActivity.populateTaskOverview(snapshot);
                    if (mCommitPending) {
                        finish();
                    } else if (!mActivity.showTaskOverviewPanel()) {
                        reset();
                    }
                }));
    }

    void finish() {
        if (!mActive) {
            return;
        }
        if (mLoadInProgress) {
            mCommitPending = true;
            return;
        }
        if (mSelectedIndex < 0 || mSelectedIndex >= mTasks.size()) {
            reset();
            mActivity.hideAllPanels();
            return;
        }

        final TaskRepository.TaskEntry target =
                mTasks.get(mSelectedIndex);
        final AppItem app = mActivity.findOrLoadApp(
                mActivity.getLauncherApps(), target.packageName);
        reset();
        mActivity.hideAllPanels();
        if (app == null) {
            mActivity.clearInteractionVisibleTasks();
            mActivity.setStatus(mActivity.getString(
                    R.string.status_switch_failed,
                    target.packageName));
            return;
        }
        mActivity.focusTask(app, target);
    }

    void reset() {
        mActive = false;
        mLoadInProgress = false;
        mCommitPending = false;
        mPendingOffset = 0;
        mSelectedIndex = -1;
        mStartingTaskId = -1;
        mTasks = Collections.emptyList();
    }

    private int findActiveTaskId(
            final TaskRepository.Snapshot snapshot) {
        if (snapshot == null || !snapshot.available) {
            return -1;
        }
        for (final TaskRepository.TaskEntry task : snapshot.tasks) {
            if (task.active && mActivity.isAltTabTask(task)) {
                return task.taskId;
            }
        }
        return -1;
    }

    private static int findTaskIndex(
            final List<TaskRepository.TaskEntry> tasks,
            final int taskId) {
        for (int index = 0; index < tasks.size(); index++) {
            if (tasks.get(index).taskId == taskId) {
                return index;
            }
        }
        return -1;
    }

    private void selectOffset(final int offset) {
        if (mTasks.isEmpty()) {
            return;
        }
        final int current = mSelectedIndex < 0 ? 0 : mSelectedIndex;
        mSelectedIndex = Math.floorMod(
                current + offset, mTasks.size());
    }
}
