package io.github.mekhontsev.magicdesk;

import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Applies stored presentation profiles to tasks already owned by a desktop. */
final class AppPresentationRuntimeController {
    interface DensityApplier {
        boolean apply(
                int[] taskIds,
                int densityDpi,
                TaskRepository.ActionCallback callback);
    }

    private static final String TAG = "MagicDeskPresentation";

    private final DensityApplier mDensityApplier;
    private final Runnable mRefresh;
    private final Map<Integer, AppliedState> mApplied = new LinkedHashMap<>();

    private Map<String, AppPresentationProfile> mProfiles =
            Collections.emptyMap();

    AppPresentationRuntimeController(
            final DensityApplier densityApplier,
            final Runnable refresh) {
        mDensityApplier = densityApplier;
        mRefresh = refresh;
    }

    void start() {
        mApplied.clear();
        reloadProfiles();
    }

    void stop() {
        mApplied.clear();
        mProfiles = Collections.emptyMap();
    }

    void resetAttempts() {
        mApplied.clear();
    }

    void forgetTask(final int taskId) {
        mApplied.remove(Integer.valueOf(taskId));
    }

    void observe(
            final List<TaskRepository.TaskEntry> tasks,
            final int displayDensityDpi) {
        if (tasks == null || tasks.isEmpty() || mProfiles.isEmpty()
                || displayDensityDpi <= 0) {
            retainLiveTasks(tasks);
            return;
        }
        retainLiveTasks(tasks);
        final Map<Integer, List<TaskRepository.TaskEntry>> updates =
                new LinkedHashMap<>();
        for (final TaskRepository.TaskEntry task : tasks) {
            if (!isApplicationTask(task)) {
                continue;
            }
            final AppPresentationProfile profile =
                    mProfiles.get(task.packageName);
            if (profile == null) {
                continue;
            }
            final int desiredDensity =
                    DesktopTaskPresentationPolicy.resolveDensityDpi(
                            profile, displayDensityDpi);
            final AppliedState applied = mApplied.get(
                    Integer.valueOf(task.taskId));
            if (task.densityDpi == desiredDensity
                    || (applied != null
                            && applied.matches(
                                    task.packageName,
                                    desiredDensity,
                                    task.densityDpi))) {
                mApplied.put(
                        Integer.valueOf(task.taskId),
                        new AppliedState(
                                task.packageName,
                                desiredDensity,
                                task.densityDpi));
                continue;
            }
            updates.computeIfAbsent(
                    Integer.valueOf(desiredDensity),
                    ignored -> new ArrayList<>()).add(task);
        }
        for (final Map.Entry<Integer, List<TaskRepository.TaskEntry>> update
                : updates.entrySet()) {
            submitAutomatic(update.getValue(), update.getKey().intValue());
        }
    }

    boolean applyStoredPackage(
            final String packageName,
            final int densityDpi,
            final List<TaskRepository.TaskEntry> tasks,
            final TaskRepository.ActionCallback callback) {
        reloadProfiles();
        final List<TaskRepository.TaskEntry> matchingTasks = new ArrayList<>();
        if (tasks != null) {
            for (final TaskRepository.TaskEntry task : tasks) {
                if (isApplicationTask(task)
                        && packageName.equals(task.packageName)) {
                    matchingTasks.add(task);
                    mApplied.remove(Integer.valueOf(task.taskId));
                }
            }
        }
        if (matchingTasks.isEmpty()) {
            complete(callback, true, "no live application tasks");
            return true;
        }
        markApplied(matchingTasks, densityDpi);
        final boolean accepted = mDensityApplier.apply(
                taskIds(matchingTasks),
                densityDpi,
                result -> {
                    if (mRefresh != null) {
                        mRefresh.run();
                    }
                    if (callback != null) {
                        callback.onComplete(result);
                    }
                });
        return accepted;
    }

    private void submitAutomatic(
            final List<TaskRepository.TaskEntry> tasks,
            final int densityDpi) {
        markApplied(tasks, densityDpi);
        final boolean accepted = mDensityApplier.apply(
                taskIds(tasks),
                densityDpi,
                result -> {
                    if (!result.success) {
                        Log.w(TAG, "automatic task density failed: "
                                + result.message);
                    }
                    if (mRefresh != null) {
                        mRefresh.run();
                    }
                });
        if (!accepted) {
            // The observer lifecycle will clear attempts when it reconnects.
            Log.w(TAG, "automatic task density was not accepted");
        }
    }

    private void reloadProfiles() {
        mProfiles = AppPresentationProfileStore.loadAll();
    }

    private void retainLiveTasks(
            final List<TaskRepository.TaskEntry> tasks) {
        if (mApplied.isEmpty()) {
            return;
        }
        final Set<Integer> liveTaskIds = new HashSet<>();
        if (tasks != null) {
            for (final TaskRepository.TaskEntry task : tasks) {
                if (task != null && task.taskId >= 0) {
                    liveTaskIds.add(Integer.valueOf(task.taskId));
                }
            }
        }
        mApplied.keySet().retainAll(liveTaskIds);
    }

    private void markApplied(
            final List<TaskRepository.TaskEntry> tasks,
            final int densityDpi) {
        for (final TaskRepository.TaskEntry task : tasks) {
            mApplied.put(
                    Integer.valueOf(task.taskId),
                    new AppliedState(
                            task.packageName,
                            densityDpi,
                            task.densityDpi));
        }
    }

    private static boolean isApplicationTask(
            final TaskRepository.TaskEntry task) {
        return task != null
                && task.taskId >= 0
                && !BuildConfig.APPLICATION_ID.equals(task.packageName)
                && DesktopManagedTaskPolicy
                        .isControllableApplicationTask(task);
    }

    private static int[] taskIds(
            final List<TaskRepository.TaskEntry> tasks) {
        final int[] taskIds = new int[tasks.size()];
        for (int index = 0; index < tasks.size(); index++) {
            taskIds[index] = tasks.get(index).taskId;
        }
        return taskIds;
    }

    private static void complete(
            final TaskRepository.ActionCallback callback,
            final boolean success,
            final String message) {
        if (callback != null) {
            callback.onComplete(new TaskRepository.ActionResult(
                    success, message));
        }
    }

    private static final class AppliedState {
        final String packageName;
        final int densityDpi;
        final int observedDensityDpi;

        AppliedState(
                final String packageName,
                final int densityDpi,
                final int observedDensityDpi) {
            this.packageName = packageName;
            this.densityDpi = densityDpi;
            this.observedDensityDpi = observedDensityDpi;
        }

        boolean matches(
                final String candidatePackage,
                final int candidateDensityDpi,
                final int candidateObservedDensityDpi) {
            return packageName.equals(candidatePackage)
                    && densityDpi == candidateDensityDpi
                    && observedDensityDpi == candidateObservedDensityDpi;
        }
    }
}
