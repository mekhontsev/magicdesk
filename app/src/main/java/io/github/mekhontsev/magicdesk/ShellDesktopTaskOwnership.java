package io.github.mekhontsev.magicdesk;

import android.util.Log;
import android.view.Display;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Tracks session-wide desktop ownership independently of display drivers. */
final class ShellDesktopTaskOwnership {
    private static final String TAG = "MagicDeskTasks";
    private static final int ACTIVITY_TYPE_STANDARD = 1;
    private static final int WINDOWING_MODE_FULLSCREEN = 1;
    private static final int WINDOWING_MODE_FREEFORM = 5;
    private static final String SYSTEM_UI_PACKAGE = "com.android.systemui";

    private final Set<Integer> mDesktopTaskIds = new LinkedHashSet<>();
    private final Set<Integer> mPhoneFullscreenTaskIds =
            new LinkedHashSet<>();

    private int mDesktopDisplayId = Display.INVALID_DISPLAY;
    private int mDesktopHostTaskId = -1;

    synchronized void configure(final int displayId) {
        if (mDesktopDisplayId == displayId) {
            return;
        }
        mDesktopDisplayId = displayId;
        mDesktopHostTaskId = -1;
        mDesktopTaskIds.clear();
        mPhoneFullscreenTaskIds.clear();
    }

    synchronized void markDesktopHost(final int taskId) {
        if (taskId < 0) {
            return;
        }
        mDesktopHostTaskId = taskId;
        markDesktop(taskId);
    }

    synchronized void markDesktop(final int taskId) {
        if (taskId < 0) {
            return;
        }
        mDesktopTaskIds.add(Integer.valueOf(taskId));
        mPhoneFullscreenTaskIds.remove(Integer.valueOf(taskId));
    }

    synchronized void forget(final int taskId) {
        mDesktopTaskIds.remove(Integer.valueOf(taskId));
        mPhoneFullscreenTaskIds.remove(Integer.valueOf(taskId));
        if (mDesktopHostTaskId == taskId) {
            mDesktopHostTaskId = -1;
        }
    }

    synchronized boolean isRememberedDesktopTask(final int taskId) {
        return taskId >= 0
                && mDesktopTaskIds.contains(Integer.valueOf(taskId));
    }

    synchronized boolean isDesktopHostTask(final int taskId) {
        return taskId >= 0 && taskId == mDesktopHostTaskId;
    }

    synchronized int desktopHostTaskId() {
        return mDesktopHostTaskId;
    }

    synchronized int[] desktopTaskIds() {
        final int[] taskIds = new int[mDesktopTaskIds.size()];
        int index = 0;
        for (final Integer taskId : mDesktopTaskIds) {
            taskIds[index++] = taskId.intValue();
        }
        return taskIds;
    }

    synchronized List<Integer> observeTasks(
            final int displayId,
            final List<?> tasks) {
        final List<Integer> unexpectedPhoneFreeformTasks = new ArrayList<>();
        if (mDesktopDisplayId == Display.INVALID_DISPLAY || tasks == null) {
            return unexpectedPhoneFreeformTasks;
        }
        for (final Object task : tasks) {
            final Integer unexpectedTaskId = observeTaskLocked(
                    displayId, task);
            if (unexpectedTaskId != null) {
                unexpectedPhoneFreeformTasks.add(unexpectedTaskId);
            }
        }
        return unexpectedPhoneFreeformTasks;
    }

    synchronized void observeTask(final Object task) {
        if (task == null) {
            return;
        }
        observeTaskLocked(HiddenTaskApi.getTaskDisplayId(task), task);
    }

    synchronized boolean isDesktopTask(final Object task) {
        if (!isStandardTask(task)) {
            return false;
        }
        try {
            final int taskId = HiddenTaskApi.getTaskId(task);
            final boolean activeExternalDisplayTask =
                    mDesktopDisplayId > Display.DEFAULT_DISPLAY
                            && HiddenTaskApi.getTaskDisplayId(task)
                                    == mDesktopDisplayId;
            return isDesktopOwnedTask(
                    activeExternalDisplayTask,
                    mDesktopTaskIds.contains(Integer.valueOf(taskId)));
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "could not inspect desktop task ownership", error);
            return false;
        }
    }

    static boolean isDesktopOwnedTask(
            final boolean activeExternalDisplayTask,
            final boolean rememberedDesktopTask) {
        // Every standard task on the active external display belongs to that
        // desktop session, including tasks launched directly in fullscreen.
        // Display 0 is shared with ordinary Android tasks, so a sampled mode
        // can never establish ownership there. MagicDesk claims those tasks
        // before submitting its explicit launch or window transition.
        return activeExternalDisplayTask || rememberedDesktopTask;
    }

    static boolean shouldRestoreKnownPhoneFreeform(
            final boolean localDesktop,
            final boolean phoneDisplay,
            final boolean desktopOwned,
            final boolean knownPhoneFullscreen,
            final int currentMode) {
        return localDesktop
                && phoneDisplay
                && !desktopOwned
                && knownPhoneFullscreen
                && currentMode == WINDOWING_MODE_FREEFORM;
    }

    private Integer observeTaskLocked(
            final int displayId,
            final Object task) {
        if (!isStandardTask(task)
                || HiddenTaskApi.getTaskDisplayId(task) != displayId) {
            return null;
        }
        try {
            return observeStandardTaskState(
                    displayId,
                    HiddenTaskApi.getTaskDisplayId(task),
                    HiddenTaskApi.getTaskId(task),
                    HiddenTaskApi.getTaskWindowingMode(task));
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "could not classify desktop task", error);
        }
        return null;
    }

    synchronized Integer observeStandardTaskState(
            final int displayId,
            final int taskDisplayId,
            final int taskId,
            final int mode) {
        if (mDesktopDisplayId == Display.INVALID_DISPLAY
                || taskId < 0 || taskDisplayId != displayId) {
            return null;
        }
        final Integer taskKey = Integer.valueOf(taskId);
        if (mDesktopDisplayId > Display.DEFAULT_DISPLAY
                && displayId == mDesktopDisplayId) {
            // The external display itself is the ownership boundary. Publish
            // every standard task there, regardless of its current mode.
            mDesktopTaskIds.add(taskKey);
            mPhoneFullscreenTaskIds.remove(taskKey);
            return null;
        }
        if (mode == WINDOWING_MODE_FREEFORM) {
            final boolean restorePhoneTask =
                    shouldRestoreKnownPhoneFreeform(
                            mDesktopDisplayId == Display.DEFAULT_DISPLAY,
                            displayId == Display.DEFAULT_DISPLAY,
                            mDesktopTaskIds.contains(taskKey),
                            mPhoneFullscreenTaskIds.contains(taskKey),
                            mode);
            // Display 0 is shared. An unclaimed task may pass through freeform
            // during a SystemUI launch, so observation alone must not adopt
            // it into the desktop workspace.
            return restorePhoneTask ? taskKey : null;
        } else if (displayId == Display.DEFAULT_DISPLAY
                && mode == WINDOWING_MODE_FULLSCREEN
                && !mDesktopTaskIds.contains(taskKey)) {
            mPhoneFullscreenTaskIds.add(taskKey);
        }
        return null;
    }

    private boolean isStandardTask(final Object task) {
        if (task == null
                || mDesktopDisplayId == Display.INVALID_DISPLAY
                || SYSTEM_UI_PACKAGE.equals(
                        HiddenTaskApi.getTaskPackage(task))) {
            return false;
        }
        try {
            return HiddenTaskApi.getTaskActivityType(task)
                            == ACTIVITY_TYPE_STANDARD
                    && !DesktopTaskbarActivity.isTaskbarComponent(
                            HiddenTaskApi.getTaskComponent(task));
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "could not inspect desktop task type", error);
            return false;
        }
    }
}
