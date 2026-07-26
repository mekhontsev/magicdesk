package android.app;

import android.content.ComponentName;

public abstract class TaskStackListener {
    public void onTaskStackChanged() {
    }

    public void onTaskCreated(final int taskId, final ComponentName componentName) {
    }

    public void onTaskRemoved(final int taskId) {
    }

    public void onTaskMovedToFront(final ActivityManager.RunningTaskInfo taskInfo) {
    }

    public void onTaskMovedToBack(final ActivityManager.RunningTaskInfo taskInfo) {
    }

    public void onTaskDisplayChanged(final int taskId, final int newDisplayId) {
    }

    public void onTaskFocusChanged(final int taskId, final boolean focused) {
    }
}
