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

    synchronized void configure(final int displayId) {
        if (mDesktopDisplayId == displayId) {
            return;
        }
        mDesktopDisplayId = displayId;
        mDesktopTaskIds.clear();
        mPhoneFullscreenTaskIds.clear();
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
            final int taskId = HiddenTaskApi.getIntField(task, "taskId");
            final int mode = HiddenTaskApi.getWindowConfigurationValue(
                    task, "getWindowingMode");
            return isDesktopOwnedMode(
                    mode,
                    mDesktopTaskIds.contains(Integer.valueOf(taskId)),
                    mPhoneFullscreenTaskIds.contains(Integer.valueOf(taskId)));
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "could not inspect desktop task ownership", error);
            return false;
        }
    }

    static boolean isDesktopOwnedMode(
            final int mode,
            final boolean rememberedDesktopTask,
            final boolean knownPhoneFullscreen) {
        // Freeform belongs to the desktop on every driver, except for the
        // transient firmware state of a task already known to belong to the
        // phone fullscreen plane.
        return rememberedDesktopTask
                || (mode == WINDOWING_MODE_FREEFORM
                        && !knownPhoneFullscreen);
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
            final Integer taskId = Integer.valueOf(
                    HiddenTaskApi.getIntField(task, "taskId"));
            final int mode = HiddenTaskApi.getWindowConfigurationValue(
                    task, "getWindowingMode");
            if (mode == WINDOWING_MODE_FREEFORM) {
                final boolean restorePhoneTask =
                        shouldRestoreKnownPhoneFreeform(
                                mDesktopDisplayId == Display.DEFAULT_DISPLAY,
                                displayId == Display.DEFAULT_DISPLAY,
                                mDesktopTaskIds.contains(taskId),
                                mPhoneFullscreenTaskIds.contains(taskId),
                                mode);
                // Do not adopt a known phone task while platform desktop mode
                // repeatedly exposes it as freeform. The stack sampler keeps
                // enforcing this until the task is stably fullscreen again.
                if (!restorePhoneTask) {
                    mDesktopTaskIds.add(taskId);
                }
                return restorePhoneTask ? taskId : null;
            } else if (displayId == Display.DEFAULT_DISPLAY
                    && mode == WINDOWING_MODE_FULLSCREEN
                    && !mDesktopTaskIds.contains(taskId)) {
                mPhoneFullscreenTaskIds.add(taskId);
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "could not classify desktop task", error);
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
            return HiddenTaskApi.getWindowConfigurationValue(
                    task, "getActivityType") == ACTIVITY_TYPE_STANDARD;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "could not inspect desktop task type", error);
            return false;
        }
    }
}
