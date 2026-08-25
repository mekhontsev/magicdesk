package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.graphics.Rect;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Keeps a switchable stack of fullscreen tasks under a stable parent.
 *
 * <p>A singleton stays in its normal display task area. Once at least two
 * fullscreen tasks must be switched, the dedicated parent prevents Nubia's
 * freeform-oriented desktop area from changing their modes while z-order
 * changes. Phone sessions retain application immersive tasks in their session
 * parent. See
 * {@code docs/architecture.md#select-fullscreen-hierarchy-by-task-area-ownership}.
 */
@SuppressLint({"BlockedPrivateApi", "PrivateApi"})
final class ShellFullscreenTaskArea implements AutoCloseable {
    enum FocusResult {
        NOT_HANDLED,
        SESSION_FOREGROUND,
        FULLSCREEN_FOREGROUND
    }

    private static final String TAG = "MagicDeskFullscreenArea";
    private static final int FEATURE_ROOT = 0;
    private static final int ACTIVITY_TYPE_HOME = 2;
    private static final int WINDOWING_MODE_FULLSCREEN = 1;
    private static final int WINDOWING_MODE_FREEFORM = 5;

    private final Set<Integer> mTaskIds = new HashSet<>();
    private final Map<Integer, Rect> mAppRestoreBounds = new HashMap<>();
    private final ShellDesktopTaskOwnership mOwnership;

    private TaskDisplayAreaHandle mArea;
    private Object mAreaService;
    private int mBackstopTaskId = -1;
    private int mDisplayId = -1;
    private int mConfiguredDisplayId = -1;
    private int mParentFeatureId = FEATURE_ROOT;
    private Object mReleaseParentToken;
    private Boolean mAreaForeground;
    private DesktopTaskAreaPolicy mTaskAreaPolicy =
            DesktopTaskAreaPolicy.DEFAULT;

    ShellFullscreenTaskArea(final ShellDesktopTaskOwnership ownership) {
        if (ownership == null) {
            throw new IllegalArgumentException(
                    "desktop task ownership is required");
        }
        mOwnership = ownership;
    }

    synchronized FocusResult focusStack(
            final Object service,
            final int displayId,
            final int[] taskIds) {
        try {
            if (taskIds == null || taskIds.length == 0) {
                return FocusResult.NOT_HANDLED;
            }
            final int targetTaskId = taskIds[taskIds.length - 1];
            final Object targetTask = HiddenTaskApi.requireTask(
                    service, displayId, targetTaskId);
            if (targetTaskId == mBackstopTaskId) {
                return FocusResult.NOT_HANDLED;
            }
            if (mOwnership.isDesktopHostTask(targetTaskId)) {
                if (mArea == null || displayId != mDisplayId) {
                    return FocusResult.NOT_HANDLED;
                }
                return deactivateArea()
                        ? FocusResult.SESSION_FOREGROUND
                        : FocusResult.NOT_HANDLED;
            }
            if (!mOwnership.isDesktopTask(targetTask)) {
                return FocusResult.NOT_HANDLED;
            }
            final int[] focusTaskIds = desktopFocusTasks(
                    service, displayId, taskIds);
            if (shouldUseSessionParent(mTaskAreaPolicy)) {
                // Phone desktop already owns one stable session parent. Keep
                // fullscreen peers there and reorder only its children.
                TaskWindowingCommand.focusTasksWithinCurrentParent(
                        service, displayId, focusTaskIds);
                return FocusResult.SESSION_FOREGROUND;
            }
            final int[] appTaskIds = withoutInfrastructureTasks(focusTaskIds);
            if (mArea != null && displayId == mDisplayId
                    && hasPreparedHierarchy(appTaskIds)
                    && HiddenTaskApi.getWindowConfigurationValue(
                            targetTask, "getWindowingMode")
                            == WINDOWING_MODE_FULLSCREEN) {
                if (mTaskIds.contains(Integer.valueOf(targetTaskId))) {
                    focusExistingHierarchy(
                            service,
                            displayId,
                            targetTaskId,
                            true);
                    return FocusResult.FULLSCREEN_FOREGROUND;
                }
                if (isAppFullscreenOutsideArea(targetTaskId)) {
                    focusExistingHierarchy(
                            service,
                            displayId,
                            targetTaskId,
                            false);
                    return FocusResult.SESSION_FOREGROUND;
                }
            }
            if (!isFullscreenStack(service, displayId, appTaskIds)) {
                final FocusResult mixedResult = focusMixedStack(
                        service, displayId, focusTaskIds);
                if (mixedResult != FocusResult.NOT_HANDLED) {
                    return mixedResult;
                }
                // A freeform task may be focused while another task remains
                // in this area. Its own mode/display/removal events own the
                // area's lifetime; unrelated focus requests do not.
                return FocusResult.NOT_HANDLED;
            }
            if (!containsManagedFullscreenTask(appTaskIds)) {
                TaskWindowingCommand.focusTasks(
                        service, displayId, new int[]{targetTaskId});
                return FocusResult.SESSION_FOREGROUND;
            }
            ensureArea(service, displayId);
            return applyFocus(service, displayId, appTaskIds);
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "fullscreen task area unavailable", error);
            closeTaskArea(false);
            return FocusResult.NOT_HANDLED;
        }
    }

    private void focusExistingHierarchy(
            final Object service,
            final int displayId,
            final int targetTaskId,
            final boolean areaForeground)
            throws ReflectiveOperationException {
        final Class<?> tokenClass =
                Class.forName("android.window.WindowContainerToken");
        final Class<?> transactionClass = Class.forName(
                "android.window.WindowContainerTransaction");
        final Object transaction =
                transactionClass.getConstructor().newInstance();
        setAreaForeground(
                transactionClass, transaction, tokenClass, areaForeground);
        if (areaForeground) {
            TaskWindowingCommand.focusTasksWithinCurrentParent(
                    service,
                    displayId,
                    new int[]{targetTaskId},
                    transactionClass,
                    transaction);
        } else {
            TaskWindowingCommand.focusTasks(
                    service,
                    displayId,
                    new int[]{targetTaskId},
                    transactionClass,
                    transaction);
        }
    }

    private FocusResult focusMixedStack(
            final Object service,
            final int displayId,
            final int[] focusTaskIds) throws ReflectiveOperationException {
        boolean containsNonFullscreenTask = false;
        for (final int taskId : focusTaskIds) {
            final Object task = HiddenTaskApi.requireTask(
                    service, displayId, taskId);
            if (taskId == mBackstopTaskId
                    || HiddenTaskApi.getWindowConfigurationValue(
                    task, "getWindowingMode")
                    != WINDOWING_MODE_FULLSCREEN) {
                containsNonFullscreenTask = true;
                break;
            }
        }
        if (!containsNonFullscreenTask) {
            return FocusResult.NOT_HANDLED;
        }

        final List<Integer> fullscreenTaskIds = new ArrayList<>();
        for (final Object task : HiddenTaskApi.getTasks(service, displayId)) {
            final int taskId = HiddenTaskApi.getIntField(task, "taskId");
            if (taskId == mBackstopTaskId
                    || HiddenTaskApi.getWindowConfigurationValue(
                    task, "getWindowingMode")
                    != WINDOWING_MODE_FULLSCREEN
                    || !mOwnership.isDesktopTask(task)
                    || mOwnership.isDesktopHostTask(taskId)) {
                continue;
            }
            // Running tasks are top-first; WCT focus order is bottom-first.
            fullscreenTaskIds.add(0, Integer.valueOf(taskId));
        }
        final boolean hasManagedFullscreenBackground = mArea != null
                && displayId == mDisplayId
                && !mTaskIds.isEmpty();
        if (fullscreenTaskIds.size() < 2
                && !hasManagedFullscreenBackground) {
            // An unrelated lone fullscreen root does not need a hierarchy
            // change merely to focus a freeform task above it.
            return FocusResult.NOT_HANDLED;
        }

        if (mArea == null) {
            ensureArea(service, displayId);
        }
        final Class<?> tokenClass =
                Class.forName("android.window.WindowContainerToken");
        final Class<?> transactionClass = Class.forName(
                "android.window.WindowContainerTransaction");
        final Object transaction =
                transactionClass.getConstructor().newInstance();
        addMissingAreaTasks(
                service,
                displayId,
                fullscreenTaskIds,
                transactionClass,
                transaction,
                tokenClass);
        final int targetTaskId = focusTaskIds[focusTaskIds.length - 1];
        final Object targetTask = HiddenTaskApi.requireTask(
                service, displayId, targetTaskId);
        final boolean targetFullscreen =
                    HiddenTaskApi.getWindowConfigurationValue(
                            targetTask, "getWindowingMode")
                        == WINDOWING_MODE_FULLSCREEN;
        if (targetFullscreen) {
            final boolean targetOutsideArea =
                    isAppFullscreenOutsideArea(targetTaskId);
            setAreaForeground(
                    transactionClass,
                    transaction,
                    tokenClass,
                    !targetOutsideArea);
            if (targetOutsideArea) {
                TaskWindowingCommand.focusTasks(
                        service,
                        displayId,
                        new int[]{targetTaskId},
                        transactionClass,
                        transaction);
            } else {
                TaskWindowingCommand.focusTasksWithinCurrentParent(
                        service,
                        displayId,
                        new int[]{targetTaskId},
                        transactionClass,
                        transaction);
            }
        } else {
            // The selected window covers the fullscreen plane without
            // minimizing it. When that window is demoted or closed, the former
            // fullscreen foreground is exposed again with its mode intact.
            setAreaForeground(
                    transactionClass, transaction, tokenClass, true);
            TaskWindowingCommand.focusTasks(
                    service,
                    displayId,
                    nonFullscreenFocusTasks(
                            service, displayId, focusTaskIds),
                    transactionClass,
                    transaction);
        }
        Log.i(TAG, "preserved fullscreen tasks=" + fullscreenTaskIds
                + " while focusing mixed stack on display=" + displayId);
        return targetFullscreen
                && !isAppFullscreenOutsideArea(targetTaskId)
                        ? FocusResult.FULLSCREEN_FOREGROUND
                        : FocusResult.SESSION_FOREGROUND;
    }

    private int[] nonFullscreenFocusTasks(
            final Object service,
            final int displayId,
            final int[] taskIds) throws ReflectiveOperationException {
        final List<Integer> output = new ArrayList<>();
        for (final int taskId : taskIds) {
            final Object task = HiddenTaskApi.requireTask(
                    service, displayId, taskId);
            if (taskId != mBackstopTaskId
                    && HiddenTaskApi.getWindowConfigurationValue(
                            task, "getWindowingMode")
                            != WINDOWING_MODE_FULLSCREEN) {
                output.add(Integer.valueOf(taskId));
            }
        }
        return toIntArray(output);
    }

    private static int[] toIntArray(final List<Integer> taskIds) {
        final int[] output = new int[taskIds.size()];
        for (int index = 0; index < taskIds.size(); index++) {
            output[index] = taskIds.get(index).intValue();
        }
        return output;
    }

    private boolean hasPreparedHierarchy(final int[] taskIds) {
        for (final int taskId : taskIds) {
            if (!isAppFullscreenOutsideArea(taskId)
                    && !mTaskIds.contains(Integer.valueOf(taskId))) {
                return false;
            }
        }
        return true;
    }

    private boolean containsManagedFullscreenTask(final int[] taskIds) {
        for (final int taskId : taskIds) {
            if (!isAppFullscreenOutsideArea(taskId)) {
                return true;
            }
        }
        return false;
    }

    private int[] desktopFocusTasks(
            final Object service,
            final int displayId,
            final int[] taskIds) throws ReflectiveOperationException {
        final List<Integer> desktopTaskIds = new ArrayList<>();
        for (final int taskId : taskIds) {
            final Object task = HiddenTaskApi.requireTask(
                    service, displayId, taskId);
            if (taskId != mBackstopTaskId
                    && (mOwnership.isDesktopHostTask(taskId)
                            || mOwnership.isDesktopTask(task))) {
                desktopTaskIds.add(Integer.valueOf(taskId));
            }
        }
        final int[] output = new int[desktopTaskIds.size()];
        for (int index = 0; index < desktopTaskIds.size(); index++) {
            output[index] = desktopTaskIds.get(index).intValue();
        }
        return output;
    }

    private int[] withoutInfrastructureTasks(final int[] taskIds) {
        int appTaskCount = 0;
        for (final int taskId : taskIds) {
            if (taskId != mBackstopTaskId
                    && !mOwnership.isDesktopHostTask(taskId)) {
                appTaskCount++;
            }
        }
        if (appTaskCount == taskIds.length) {
            return taskIds;
        }
        final int[] appTaskIds = new int[appTaskCount];
        int outputIndex = 0;
        for (final int taskId : taskIds) {
            if (taskId != mBackstopTaskId
                    && !mOwnership.isDesktopHostTask(taskId)) {
                appTaskIds[outputIndex++] = taskId;
            }
        }
        return appTaskIds;
    }

    synchronized boolean beginAppFullscreen(
            final Object service,
            final int displayId,
            final int taskId,
            final Rect restoreBounds) {
        if (restoreBounds == null || restoreBounds.isEmpty()) {
            return false;
        }
        try {
            if (mDisplayId >= 0 && mDisplayId != displayId) {
                close();
            }
            mDisplayId = displayId;
            final Class<?> tokenClass =
                    Class.forName("android.window.WindowContainerToken");
            final Class<?> transactionClass = Class.forName(
                    "android.window.WindowContainerTransaction");
            final Object transaction =
                    transactionClass.getConstructor().newInstance();
            final Object taskToken = HiddenTaskApi.requireTaskToken(
                    service, displayId, taskId);
            final boolean joinPreparedArea = shouldJoinPreparedArea(
                    mTaskAreaPolicy, mArea != null, mTaskIds.size());
            final boolean useSessionParent = shouldUseSessionParent(
                    mTaskAreaPolicy);
            if (joinPreparedArea) {
                setAreaForeground(
                        transactionClass,
                        transaction,
                        tokenClass,
                        true);
            }
            transactionClass.getMethod(
                    "setWindowingMode", tokenClass, Integer.TYPE)
                    .invoke(transaction, taskToken,
                            Integer.valueOf(WINDOWING_MODE_FULLSCREEN));
            transactionClass.getMethod("setBounds", tokenClass, Rect.class)
                    .invoke(transaction, taskToken, new Rect());
            if (!joinPreparedArea && !useSessionParent) {
                transactionClass.getMethod(
                        "reorder", tokenClass, Boolean.TYPE)
                        .invoke(transaction, taskToken, Boolean.TRUE);
            }
            if (joinPreparedArea) {
                transactionClass.getMethod(
                        "reparent", tokenClass, tokenClass, Boolean.TYPE)
                        .invoke(transaction,
                                taskToken,
                                mArea.token(),
                                Boolean.TRUE);
            }
            TaskCaptionInsetsCommand.addCaptionInsetOperation(
                    transactionClass,
                    transaction,
                    tokenClass,
                    taskToken,
                    true);
            if (joinPreparedArea) {
                // Once a switchable fullscreen hierarchy exists, entering its
                // next member under a sibling root can demote an existing child
                // on Nubia. Reparent and activate it in the same TO_FRONT WCT.
                TaskWindowingCommand.focusTasks(
                        service,
                        displayId,
                        new int[]{taskId},
                        transactionClass,
                        transaction);
            } else if (useSessionParent) {
                // Application immersive remains in the phone session parent.
                TaskWindowingCommand.focusTasksWithinCurrentParent(
                        service,
                        displayId,
                        new int[]{taskId},
                        transactionClass,
                        transaction);
            } else {
                // A singleton retains the display's ordinary parent, matching
                // the proven 1.8 hierarchy.
                TaskFullscreenTransitionCommand.startTransition(
                        transactionClass, transaction);
            }
            if (joinPreparedArea) {
                mTaskIds.add(Integer.valueOf(taskId));
            }
            mAppRestoreBounds.put(
                    Integer.valueOf(taskId), new Rect(restoreBounds));
            Log.i(TAG, "entered app fullscreen task=" + taskId
                    + " display=" + displayId);
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "app fullscreen task area unavailable task="
                    + taskId, error);
            mAppRestoreBounds.remove(Integer.valueOf(taskId));
            return false;
        }
    }

    static boolean shouldJoinPreparedArea(
            final DesktopTaskAreaPolicy taskAreaPolicy,
            final boolean areaExists,
            final int managedTaskCount) {
        return taskAreaPolicy == DesktopTaskAreaPolicy.DEFAULT
                && areaExists
                && managedTaskCount > 0;
    }

    static boolean shouldUseSessionParent(
            final DesktopTaskAreaPolicy taskAreaPolicy) {
        return taskAreaPolicy == DesktopTaskAreaPolicy.SESSION;
    }

    synchronized boolean beginFullscreen(
            final Object service,
            final int displayId,
            final int taskId,
            final boolean refreshCaption) {
        if (shouldUseSessionParent(mTaskAreaPolicy)) {
            // The ordinary fullscreen transition preserves the phone session
            // parent; no secondary organizer hierarchy is needed.
            return false;
        }
        try {
            final List<Integer> fullscreenPeers =
                    findManagedFullscreenPeers(
                            service, displayId, taskId);
            if (mArea == null || mDisplayId != displayId
                    || mTaskIds.isEmpty()) {
                if (fullscreenPeers.isEmpty()) {
                    // Preserve the normal display parent for a singleton.
                    return false;
                }
                ensureArea(service, displayId);
            }
            final int captionSourceId = refreshCaption
                    ? TaskCaptionInsetsRefresher.captureCaptionSourceId(taskId)
                    : TaskLocalInsetsSourceParser.NO_SOURCE_ID;
            final Class<?> tokenClass =
                    Class.forName("android.window.WindowContainerToken");
            final Class<?> transactionClass = Class.forName(
                    "android.window.WindowContainerTransaction");
            final Object transaction =
                    transactionClass.getConstructor().newInstance();
            final Object taskToken = HiddenTaskApi.requireTaskToken(
                    service, displayId, taskId);
            setAreaForeground(
                    transactionClass,
                    transaction,
                    tokenClass,
                    true);
            addMissingAreaTasks(
                    service,
                    displayId,
                    fullscreenPeers,
                    transactionClass,
                    transaction,
                    tokenClass);
            transactionClass.getMethod(
                    "setWindowingMode", tokenClass, Integer.TYPE)
                    .invoke(transaction, taskToken,
                            Integer.valueOf(WINDOWING_MODE_FULLSCREEN));
            transactionClass.getMethod("setBounds", tokenClass, Rect.class)
                    .invoke(transaction, taskToken, new Rect());
            transactionClass.getMethod(
                    "reparent", tokenClass, tokenClass, Boolean.TYPE)
                    .invoke(transaction, taskToken,
                            mArea.token(), Boolean.TRUE);
            TaskCaptionInsetsCommand.addCaptionInsetOperation(
                    transactionClass,
                    transaction,
                    tokenClass,
                    taskToken,
                    true);
            mTaskIds.add(Integer.valueOf(taskId));
            TaskWindowingCommand.focusTasksWithinCurrentParent(
                    service,
                    displayId,
                    new int[]{taskId},
                    transactionClass,
                    transaction);
            TaskFullscreenTransitionCommand.refreshCaptionIfRequested(
                    service,
                    displayId,
                    taskId,
                    refreshCaption,
                    captionSourceId);
            Log.i(TAG, "entered managed fullscreen task=" + taskId
                    + " display=" + displayId);
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "managed fullscreen transition failed task="
                    + taskId, error);
            return false;
        }
    }

    private List<Integer> findManagedFullscreenPeers(
            final Object service,
            final int displayId,
            final int excludedTaskId) throws ReflectiveOperationException {
        final List<Integer> taskIds = new ArrayList<>();
        for (final Object task : HiddenTaskApi.getTasks(service, displayId)) {
            final int taskId = HiddenTaskApi.getIntField(task, "taskId");
            if (taskId == excludedTaskId
                    || taskId == mBackstopTaskId
                    || mOwnership.isDesktopHostTask(taskId)
                    || !mOwnership.isDesktopTask(task)
                    || isAppFullscreenOutsideArea(taskId)
                    || HiddenTaskApi.getWindowConfigurationValue(
                            task, "getWindowingMode")
                            != WINDOWING_MODE_FULLSCREEN) {
                continue;
            }
            // Running tasks are top-first; WCT reparent order is bottom-first.
            taskIds.add(0, Integer.valueOf(taskId));
        }
        return taskIds;
    }

    private boolean restoreAppFullscreen(
            final Object service,
            final int displayId,
            final int taskId) {
        final Rect restoreBounds = mAppRestoreBounds.get(
                Integer.valueOf(taskId));
        if (restoreBounds == null || displayId != mDisplayId) {
            return false;
        }
        boolean hidden = false;
        try {
            // Firmware may already have nominally changed the task to freeform.
            // Re-establish a hidden fullscreen boundary, detaching only when
            // our organizer parent owns the task, then reveal only the
            // canonical freeform geometry.
            final boolean detachFromFullscreenParent =
                    mTaskIds.contains(Integer.valueOf(taskId));
            if (detachFromFullscreenParent) {
                ShellPreparedTaskTransition.prepareDetachedFullscreen(
                        service,
                        displayId,
                        taskId,
                        mReleaseParentToken);
            } else {
                ShellPreparedTaskTransition.prepareFullscreen(
                        service, displayId, taskId);
            }
            hidden = true;
            TaskDisplayAreaLaunchCommand.waitForTaskVisibility(
                    service, displayId, taskId, false);
            forgetAppFullscreenTask(taskId);
            ShellPreparedTaskTransition.showPreparedFreeform(
                    service, displayId, taskId, restoreBounds);
            TaskDisplayAreaLaunchCommand.waitForTaskFreeformBounds(
                    service, displayId, taskId, restoreBounds);
            hidden = false;
            Log.i(TAG, "restored app fullscreen task=" + taskId
                    + " display=" + displayId);
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "app fullscreen restore failed task=" + taskId, error);
            forgetAppFullscreenTask(taskId);
            if (hidden) {
                try {
                    ShellPreparedTaskTransition.restorePreparedTask(
                            service,
                            displayId,
                            taskId,
                            WINDOWING_MODE_FREEFORM,
                            restoreBounds);
                    return true;
                } catch (ReflectiveOperationException
                        | RuntimeException restoreError) {
                    error.addSuppressed(restoreError);
                }
            }
            return false;
        }
    }

    private void forgetAppFullscreenTask(final int taskId) {
        mAppRestoreBounds.remove(Integer.valueOf(taskId));
        mTaskIds.remove(Integer.valueOf(taskId));
        if (mArea == null && mAppRestoreBounds.isEmpty()) {
            mDisplayId = -1;
        }
    }

    private FocusResult applyFocus(
            final Object service,
            final int displayId,
            final int[] appTaskIds) throws ReflectiveOperationException {
        final Class<?> tokenClass =
                Class.forName("android.window.WindowContainerToken");
        final Class<?> transactionClass =
                Class.forName("android.window.WindowContainerTransaction");
        final Object transaction = transactionClass.getConstructor().newInstance();
        final int targetTaskId = appTaskIds[appTaskIds.length - 1];
        addMissingAreaTasks(
                service,
                displayId,
                toIntegerList(appTaskIds),
                transactionClass,
                transaction,
                tokenClass);
        final boolean targetOutsideArea =
                isAppFullscreenOutsideArea(targetTaskId);
        setAreaForeground(
                transactionClass,
                transaction,
                tokenClass,
                !targetOutsideArea);
        if (targetOutsideArea) {
            // Session-owned application fullscreen remains outside the sibling
            // organizer, so changing which parent is foreground still belongs
            // to a normal WMShell transition.
            TaskWindowingCommand.focusTasks(
                    service,
                    displayId,
                    new int[]{targetTaskId},
                    transactionClass,
                    transaction);
        } else {
            TaskWindowingCommand.focusTasksWithinCurrentParent(
                    service,
                    displayId,
                    new int[]{targetTaskId},
                    transactionClass,
                    transaction);
        }
        return targetOutsideArea
                ? FocusResult.SESSION_FOREGROUND
                : FocusResult.FULLSCREEN_FOREGROUND;
    }

    private boolean isAppFullscreenOutsideArea(final int taskId) {
        return mTaskAreaPolicy.usesSessionFullscreenHierarchy()
                && mAppRestoreBounds.containsKey(Integer.valueOf(taskId))
                && !mTaskIds.contains(Integer.valueOf(taskId));
    }

    private void addMissingAreaTasks(
            final Object service,
            final int displayId,
            final List<Integer> taskIds,
            final Class<?> transactionClass,
            final Object transaction,
            final Class<?> tokenClass) throws ReflectiveOperationException {
        final Object areaToken = mArea.token();
        for (final Integer taskIdValue : taskIds) {
            final int taskId = taskIdValue.intValue();
            if (isAppFullscreenOutsideArea(taskId)) {
                continue;
            }
            if (!mTaskIds.add(taskIdValue)) {
                continue;
            }
            final Object taskToken = HiddenTaskApi.requireTaskToken(
                    service, displayId, taskId);
            transactionClass.getMethod(
                    "setWindowingMode", tokenClass, Integer.TYPE)
                    .invoke(transaction, taskToken,
                            Integer.valueOf(WINDOWING_MODE_FULLSCREEN));
            transactionClass.getMethod("setBounds", tokenClass, Rect.class)
                    .invoke(transaction, taskToken, new Rect());
            transactionClass.getMethod(
                    "reparent", tokenClass, tokenClass, Boolean.TYPE)
                    .invoke(transaction, taskToken, areaToken, Boolean.TRUE);
        }
    }

    private static List<Integer> toIntegerList(final int[] taskIds) {
        final List<Integer> output = new ArrayList<>(taskIds.length);
        for (final int taskId : taskIds) {
            output.add(Integer.valueOf(taskId));
        }
        return output;
    }

    private boolean isFullscreenStack(
            final Object service,
            final int displayId,
            final int[] taskIds) throws ReflectiveOperationException {
        if (taskIds == null
                || taskIds.length
                        < mTaskAreaPolicy
                                .minimumFullscreenTasksForSharedArea()) {
            return false;
        }
        for (final int taskId : taskIds) {
            final Object task = HiddenTaskApi.findTask(
                    service, displayId, taskId);
            if (task == null
                    || HiddenTaskApi.getWindowConfigurationValue(
                            task, "getWindowingMode")
                            != WINDOWING_MODE_FULLSCREEN) {
                return false;
            }
        }
        return true;
    }

    synchronized boolean restoreTask(
            final Object service,
            final int displayId,
            final int taskId,
            final Rect bounds) {
        if (mDisplayId != displayId || bounds == null || bounds.isEmpty()) {
            return false;
        }
        if (mAppRestoreBounds.containsKey(Integer.valueOf(taskId))) {
            return restoreAppFullscreen(service, displayId, taskId);
        }
        if (mArea == null
                || !mTaskIds.contains(Integer.valueOf(taskId))) {
            return false;
        }
        try {
            ShellPreparedTaskTransition.detachAndShowFreeform(
                    service,
                    displayId,
                    taskId,
                    bounds,
                    mReleaseParentToken);
            mAppRestoreBounds.remove(Integer.valueOf(taskId));
            mTaskIds.remove(Integer.valueOf(taskId));
            Log.i(TAG, "restored fullscreen task=" + taskId
                    + " display=" + displayId);
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "failed to restore fullscreen task=" + taskId, error);
            try {
                // Preserve the old two-step fallback only when the atomic
                // detach-and-restore transaction itself is unavailable.
                ShellPreparedTaskTransition.detachFullscreenParent(
                        service,
                        displayId,
                        taskId,
                        mReleaseParentToken);
                mAppRestoreBounds.remove(Integer.valueOf(taskId));
                mTaskIds.remove(Integer.valueOf(taskId));
            } catch (ReflectiveOperationException
                    | RuntimeException detachError) {
                error.addSuppressed(detachError);
            }
            return false;
        }
    }

    synchronized boolean closeTask(
            final Object service,
            final int displayId,
            final int taskId) {
        if (mArea == null || mDisplayId != displayId
                || !mTaskIds.contains(Integer.valueOf(taskId))) {
            return false;
        }
        final int survivorTaskId;
        try {
            survivorTaskId = findTopSurvivor(service, displayId, taskId);
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "failed to find fullscreen close survivor", error);
            return false;
        }
        if (survivorTaskId < 0) {
            return false;
        }

        try {
            TaskWindowingCommand.closeFullscreenAreaTask(
                    service,
                    displayId,
                    taskId,
                    survivorTaskId);
            mAppRestoreBounds.remove(Integer.valueOf(taskId));
            mTaskIds.remove(Integer.valueOf(taskId));
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "fullscreen close handoff failed task="
                    + taskId, error);
            return false;
        }
        Log.i(TAG, "closed fullscreen task=" + taskId
                + " survivor=" + survivorTaskId
                + " display=" + displayId);
        return true;
    }

    private int findTopSurvivor(
            final Object service,
            final int displayId,
            final int closingTaskId) throws ReflectiveOperationException {
        // ActivityTaskManager returns running tasks in top-first order.
        for (final Object task : HiddenTaskApi.getTasks(service, displayId)) {
            final int candidateTaskId = HiddenTaskApi.getIntField(
                    task, "taskId");
            if (candidateTaskId != closingTaskId
                    && mTaskIds.contains(Integer.valueOf(candidateTaskId))
                    && HiddenTaskApi.getWindowConfigurationValue(
                            task, "getWindowingMode")
                            == WINDOWING_MODE_FULLSCREEN) {
                return candidateTaskId;
            }
        }
        return -1;
    }

    private void ensureArea(
            final Object service,
            final int displayId) throws ReflectiveOperationException {
        if (mArea != null && mDisplayId == displayId) {
            return;
        }
        if (mArea != null || (mDisplayId >= 0 && mDisplayId != displayId)) {
            close();
        }

        if (mConfiguredDisplayId >= 0
                && displayId != mConfiguredDisplayId) {
            throw new IllegalStateException(
                    "fullscreen parent is not configured for display "
                            + displayId);
        }
        final TaskDisplayAreaHandle area = TaskDisplayAreaHandle.create(
                displayId,
                mParentFeatureId,
                "MagicDesk fullscreen stack");
        final Object areaToken = area.token();
        int backstopTaskId = -1;
        try {
            final Class<?> tokenClass =
                    Class.forName("android.window.WindowContainerToken");
            final Class<?> transactionClass =
                    Class.forName("android.window.WindowContainerTransaction");
            final Object transaction =
                    transactionClass.getConstructor().newInstance();
            transactionClass.getMethod(
                    "setWindowingMode", tokenClass, Integer.TYPE)
                    .invoke(transaction, areaToken,
                            Integer.valueOf(WINDOWING_MODE_FULLSCREEN));
            SyncWindowContainerTransaction.apply(
                    service, transactionClass, transaction);
            if (mTaskAreaPolicy.requiresFullscreenBackstop()) {
                // Display 0 retains this sibling organizer across task
                // lifecycles, so it must never be structurally empty.
                backstopTaskId = TaskDisplayAreaLaunchCommand
                        .launchFullscreenTask(
                                service,
                                displayId,
                                TaskAreaBackstopActivity.createIntent(
                                        "fullscreen:" + displayId + ':'
                                                + area.featureId()),
                                BuildConfig.APPLICATION_ID,
                                tokenClass,
                                areaToken,
                                ACTIVITY_TYPE_HOME);
                final Object backstop = HiddenTaskApi.requireTask(
                        service, displayId, backstopTaskId);
                if (!TaskAreaBackstopActivity.isBackstopComponent(
                                HiddenTaskApi.getTaskComponent(backstop))
                        || HiddenTaskApi.getIntField(
                                backstop, "displayAreaFeatureId")
                                != area.featureId()) {
                    throw new IllegalStateException(
                            "fullscreen backstop did not enter its task area");
                }
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            area.closeIfOnlyOwnedChildren(
                    service,
                    displayId,
                    backstopTaskId < 0
                            ? Collections.<Integer>emptySet()
                            : Collections.singleton(
                                    Integer.valueOf(backstopTaskId)));
            throw error;
        }

        mArea = area;
        mAreaService = service;
        mBackstopTaskId = backstopTaskId;
        mDisplayId = displayId;
        Log.i(TAG, "created fullscreen task area display=" + displayId
                + (backstopTaskId < 0
                        ? " policy=default"
                        : " backstop=" + backstopTaskId));
    }

    synchronized boolean onWindowingModeChanged(
            final int displayId,
            final int taskId,
            final int windowingMode,
            final boolean focused) {
        if (displayId != mDisplayId) {
            return false;
        }
        final Integer taskKey = Integer.valueOf(taskId);
        final boolean backgroundAppFullscreenReleased =
                shouldReleaseBackgroundAppFullscreen(
                        mTaskAreaPolicy,
                        focused,
                        windowingMode,
                        mAppRestoreBounds.containsKey(taskKey),
                        mTaskIds.contains(taskKey));
        if (backgroundAppFullscreenReleased) {
            forgetAppFullscreenTask(taskId);
            Log.i(TAG, "released background app fullscreen task=" + taskId
                    + " display=" + displayId);
        } else if (mTaskIds.contains(taskKey)
                && !mAppRestoreBounds.containsKey(taskKey)
                && windowingMode != WINDOWING_MODE_FULLSCREEN) {
            closeTaskArea(false);
        }
        return backgroundAppFullscreenReleased;
    }

    static boolean shouldReleaseBackgroundAppFullscreen(
            final DesktopTaskAreaPolicy taskAreaPolicy,
            final boolean focused,
            final int windowingMode,
            final boolean appFullscreenTracked,
            final boolean managedAreaMember) {
        return taskAreaPolicy == DesktopTaskAreaPolicy.DEFAULT
                && !focused
                && windowingMode != WINDOWING_MODE_FULLSCREEN
                && appFullscreenTracked
                && !managedAreaMember;
    }

    synchronized void onTaskRemoved(final int taskId) {
        mAppRestoreBounds.remove(Integer.valueOf(taskId));
        final boolean managedTaskRemoved =
                mTaskIds.remove(Integer.valueOf(taskId));
        if (taskId == mBackstopTaskId) {
            closeTaskArea(false);
        } else if (managedTaskRemoved && mTaskIds.isEmpty()) {
            if (mTaskAreaPolicy.requiresFullscreenBackstop()) {
                // Keep the sibling organizer area for the phone session.
                // Removing it from inside the finishing task callback can
                // overlap the vendor Back transition.
                deactivateArea();
            } else {
                closeTaskArea(false);
            }
        } else if (mArea == null && mAppRestoreBounds.isEmpty()) {
            mDisplayId = -1;
        }
    }

    synchronized void onTaskMovedToFront(
            final int displayId,
            final int taskId) {
        if (displayId == mDisplayId && taskId == mBackstopTaskId
                && mTaskIds.isEmpty()) {
            // Back can promote the structural child before TaskStackListener
            // publishes removal of the finishing application task.
            deactivateArea();
        }
    }

    synchronized void onTaskStackChanged() {
        if (mArea == null || mAreaService == null || mDisplayId < 0
                || mTaskAreaPolicy.requiresFullscreenBackstop()
                || !mTaskIds.isEmpty() || !mAppRestoreBounds.isEmpty()) {
            return;
        }
        // Restoring the last child starts an asynchronous WMShell transition.
        // A later stack-changed callback is the first committed boundary at
        // which the area can be removed without racing that transition.
        if (!mArea.closeIfEmpty(mAreaService, mDisplayId)) {
            return;
        }
        Log.i(TAG, "released empty fullscreen task area display=" + mDisplayId);
        mArea = null;
        mAreaService = null;
        mBackstopTaskId = -1;
        mDisplayId = -1;
        mAreaForeground = null;
    }

    private boolean deactivateArea() {
        final int hostTaskId = mOwnership.desktopHostTaskId();
        if (mArea == null || mAreaService == null || mDisplayId < 0
                || hostTaskId < 0) {
            return false;
        }
        try {
            if (HiddenTaskApi.findTask(
                    mAreaService, mDisplayId, hostTaskId) == null) {
                return false;
            }
            final Class<?> tokenClass = Class.forName(
                    "android.window.WindowContainerToken");
            final Class<?> transactionClass = Class.forName(
                    "android.window.WindowContainerTransaction");
            final Object transaction =
                    transactionClass.getConstructor().newInstance();
            setAreaForeground(
                    transactionClass,
                    transaction,
                    tokenClass,
                    false);
            // Queue the area/host z-order change behind Android's Back
            // transition. The transparent child remains structurally present
            // but cannot become the foreground desktop task.
            TaskWindowingCommand.focusTasks(
                    mAreaService,
                    mDisplayId,
                    new int[]{hostTaskId},
                    transactionClass,
                    transaction);
            Log.i(TAG, "deactivated fullscreen area behind desktop host="
                    + hostTaskId + " display=" + mDisplayId);
            return true;
        } catch (ReflectiveOperationException | RuntimeException error) {
            Log.w(TAG, "could not deactivate fullscreen area", error);
            return false;
        }
    }

    private void setAreaForeground(
            final Class<?> transactionClass,
            final Object transaction,
            final Class<?> tokenClass,
            final boolean onTop) throws ReflectiveOperationException {
        if (mAreaForeground != null
                && mAreaForeground.booleanValue() == onTop) {
            return;
        }
        if (mTaskAreaPolicy.requiresFullscreenBackstop()) {
            // The persistent phone sibling can remain an input target after
            // only changing z-order. Visibility and ordering therefore belong
            // to one WCT. Isolated displays retain the simpler 1.8 hierarchy.
            transactionClass.getMethod(
                    "setHidden", tokenClass, Boolean.TYPE)
                    .invoke(transaction, mArea.token(),
                            Boolean.valueOf(!onTop));
        }
        transactionClass.getMethod(
                "reorder", tokenClass, Boolean.TYPE)
                .invoke(transaction, mArea.token(), Boolean.valueOf(onTop));
        mAreaForeground = Boolean.valueOf(onTop);
    }

    synchronized void onTaskDisplayChanged(
            final int taskId,
            final int displayId) {
        if (displayId != mDisplayId) {
            if (taskId == mBackstopTaskId) {
                closeTaskArea(false);
            } else {
                onTaskRemoved(taskId);
            }
        }
    }

    synchronized void configure(
            final int displayId,
            final DesktopTaskAreaPolicy taskAreaPolicy,
            final int parentFeatureId,
            final Object releaseParentToken) {
        if (displayId < 0) {
            close();
            mConfiguredDisplayId = -1;
            mParentFeatureId = FEATURE_ROOT;
            mReleaseParentToken = null;
            mTaskAreaPolicy = DesktopTaskAreaPolicy.DEFAULT;
            return;
        }
        if (taskAreaPolicy == null) {
            throw new IllegalArgumentException(
                    "missing fullscreen task area policy");
        }
        if (parentFeatureId < 0) {
            throw new IllegalArgumentException(
                    "invalid fullscreen parent feature");
        }
        if (mConfiguredDisplayId != displayId
                || mTaskAreaPolicy != taskAreaPolicy
                || mParentFeatureId != parentFeatureId
                || mReleaseParentToken != releaseParentToken) {
            close();
        }
        mConfiguredDisplayId = displayId;
        mTaskAreaPolicy = taskAreaPolicy;
        mParentFeatureId = parentFeatureId;
        mReleaseParentToken = releaseParentToken;
    }

    @Override
    public synchronized void close() {
        closeTaskArea(true);
    }

    private void closeTaskArea(final boolean clearAppFullscreen) {
        final TaskDisplayAreaHandle area = mArea;
        final Object service = mAreaService;
        final int displayId = mDisplayId;
        final Set<Integer> ownedTaskIds = new HashSet<>(mTaskIds);
        final int backstopTaskId = mBackstopTaskId;
        mArea = null;
        mAreaService = null;
        mBackstopTaskId = -1;
        mDisplayId = clearAppFullscreen || mAppRestoreBounds.isEmpty()
                ? -1 : displayId;
        mAreaForeground = null;
        mTaskIds.clear();
        if (clearAppFullscreen) {
            mAppRestoreBounds.clear();
        }
        if (area != null) {
            try {
                // Drain application tasks first, but keep the structural HOME
                // child until framework-owned area deletion. This avoids an
                // observable empty task area during hierarchy traversal.
                // Dynamic feature IDs can remain in stale Recents metadata,
                // so only this area's own live task IDs are safe to detach.
                area.detachChildTasks(
                        service,
                        displayId,
                        ownedTaskIds,
                        mReleaseParentToken);
            } catch (ReflectiveOperationException | RuntimeException error) {
                Log.w(TAG, "could not detach fullscreen tasks before cleanup",
                        error);
            }
            if (!area.closeIfOnlyOwnedChildren(
                    service,
                    displayId,
                    backstopTaskId < 0
                            ? Collections.<Integer>emptySet()
                            : Collections.singleton(
                                    Integer.valueOf(backstopTaskId)))) {
                Log.w(TAG, "fullscreen task area retained after unsafe cleanup"
                        + " feature=" + area.featureId());
            }
        }
    }

}
