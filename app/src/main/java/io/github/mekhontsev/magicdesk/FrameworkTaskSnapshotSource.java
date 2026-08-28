package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.graphics.Rect;
import android.view.Display;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Single typed reader for Android framework task state. */
final class FrameworkTaskSnapshotSource {
    private FrameworkTaskSnapshotSource() {
    }

    static Sample read(
            final Object service,
            final int displayId,
            final int limit,
            final FrameworkWindowingCompat compat)
            throws ReflectiveOperationException {
        return read(service, displayId, limit, compat, true);
    }

    static Sample read(
            final Object service,
            final int displayId,
            final int limit,
            final FrameworkWindowingCompat compat,
            final boolean includeClientState)
            throws ReflectiveOperationException {
        if (service == null || limit <= 0 || compat == null) {
            throw new IllegalArgumentException("invalid task snapshot request");
        }
        final List<?> queriedTasks = HiddenTaskApi.getTasks(
                service, displayId, limit);
        final OrderedTasks orderedTasks = orderByRootTaskHierarchy(
                queriedTasks,
                HiddenTaskApi.getRootTaskInfos(service, displayId));
        final List<FrameworkTaskSnapshot> snapshots = new ArrayList<>();
        for (final Object task : orderedTasks.tasks) {
            final int taskId = HiddenTaskApi.getTaskId(task);
            snapshots.add(readTask(
                    task,
                    orderedTasks.rootTaskIds.getOrDefault(
                            Integer.valueOf(taskId), Integer.valueOf(taskId))
                            .intValue(),
                    compat,
                    includeClientState));
        }
        return new Sample(orderedTasks.tasks, snapshots);
    }

    static FrameworkTaskSnapshot[] readArray(
            final Object service,
            final int displayId,
            final int limit,
            final FrameworkWindowingCompat compat)
            throws ReflectiveOperationException {
        final int targetDisplay = displayId < 0
                ? Display.INVALID_DISPLAY : displayId;
        final List<FrameworkTaskSnapshot> snapshots = read(
                service, targetDisplay, limit, compat, false).snapshots;
        return snapshots.toArray(new FrameworkTaskSnapshot[0]);
    }

    static List<FrameworkTaskSnapshot> readWindowState(
            final Object service,
            final int displayId,
            final int limit) throws ReflectiveOperationException {
        return read(
                service,
                displayId,
                limit,
                FrameworkRuntime.current().windowingCompat(),
                false).snapshots;
    }

    static FrameworkTaskSnapshot findTask(
            final Object service,
            final int displayId,
            final int taskId) throws ReflectiveOperationException {
        for (final FrameworkTaskSnapshot task
                : readWindowState(service, displayId, 100)) {
            if (task.taskId == taskId) {
                return task;
            }
        }
        return null;
    }

    private static FrameworkTaskSnapshot readTask(
            final Object task,
            final int rootTaskId,
            final FrameworkWindowingCompat compat,
            final boolean includeClientState)
            throws ReflectiveOperationException {
        final int taskId = HiddenTaskApi.getIntField(task, "taskId");
        final int displayId = HiddenTaskApi.getTaskDisplayId(task);
        final boolean visible = readBoolean(task, "isVisible", false);
        final boolean focused = readBoolean(task, "isFocused", false);
        final Object windowConfiguration =
                HiddenTaskApi.getWindowConfiguration(task);
        final Rect bounds = (Rect) windowConfiguration.getClass()
                .getMethod("getBounds")
                .invoke(windowConfiguration);
        final ComponentName component = HiddenTaskApi.getTaskComponent(task);
        final ComponentName topActivity =
                HiddenTaskApi.getTaskTopComponent(task);
        final Object topActivityInfo = readField(task, "topActivityInfo");
        final String topPackage = topActivityInfo instanceof ActivityInfo
                ? ((ActivityInfo) topActivityInfo).packageName
                : topActivity == null ? null : topActivity.getPackageName();
        final ActivityInfo activityInfo = topActivityInfo instanceof ActivityInfo
                ? (ActivityInfo) topActivityInfo : null;
        return new FrameworkTaskSnapshot(
                task,
                rootTaskId,
                taskId,
                displayId,
                readInt(task, "displayAreaFeatureId", -1),
                HiddenTaskApi.getWindowConfigurationValue(
                        task, "getWindowingMode"),
                HiddenTaskApi.getWindowConfigurationValue(
                        task, "getActivityType"),
                component,
                topActivity,
                flatten(component),
                flatten(topActivity),
                component == null ? null : component.getPackageName(),
                topPackage,
                activityInfo == null || activityInfo.applicationInfo == null
                        ? -1 : activityInfo.applicationInfo.uid,
                activityInfo == null ? null : activityInfo.processName,
                bounds,
                visible,
                focused,
                visible && includeClientState
                        ? compat.readRequestedVisibleTypes(task) : null);
    }

    static OrderedTasks orderByRootTaskHierarchy(
            final List<?> queriedTasks,
            final List<?> rootTasks) throws ReflectiveOperationException {
        final Map<Integer, Object> tasksById = new LinkedHashMap<>();
        if (queriedTasks != null) {
            for (final Object task : queriedTasks) {
                tasksById.put(
                        Integer.valueOf(HiddenTaskApi.getTaskId(task)), task);
            }
        }

        final List<Object> ordered = new ArrayList<>();
        final Map<Integer, Integer> rootTaskIds = new HashMap<>();
        final Set<Integer> emitted = new HashSet<>();
        if (rootTasks != null) {
            for (final Object rootTask : rootTasks) {
                final int rootTaskId = HiddenTaskApi.getTaskId(rootTask);
                final int[] childTaskIds =
                        HiddenTaskApi.getRootTaskChildTaskIds(rootTask);
                for (final int childTaskId : childTaskIds) {
                    final Integer childKey = Integer.valueOf(childTaskId);
                    rootTaskIds.put(childKey, Integer.valueOf(rootTaskId));
                    final Object task = tasksById.get(childKey);
                    if (task != null && emitted.add(childKey)) {
                        ordered.add(task);
                    }
                }
                final Integer rootKey = Integer.valueOf(rootTaskId);
                rootTaskIds.putIfAbsent(rootKey, rootKey);
                final Object rootEntry = tasksById.get(rootKey);
                if (rootEntry != null && emitted.add(rootKey)) {
                    ordered.add(rootEntry);
                }
            }
        }
        for (final Map.Entry<Integer, Object> entry : tasksById.entrySet()) {
            if (emitted.add(entry.getKey())) {
                rootTaskIds.putIfAbsent(entry.getKey(), entry.getKey());
                ordered.add(entry.getValue());
            }
        }
        return new OrderedTasks(ordered, rootTaskIds);
    }

    private static String flatten(final ComponentName component) {
        return component == null ? "" : component.flattenToShortString();
    }

    private static Object readField(final Object target, final String name) {
        try {
            final Field field = target.getClass().getField(name);
            return field.get(target);
        } catch (ReflectiveOperationException | RuntimeException error) {
            return null;
        }
    }

    private static boolean readBoolean(
            final Object target,
            final String name,
            final boolean fallback) {
        try {
            return HiddenTaskApi.getBooleanField(target, name);
        } catch (ReflectiveOperationException | RuntimeException error) {
            return fallback;
        }
    }

    private static int readInt(
            final Object target,
            final String name,
            final int fallback) {
        try {
            return HiddenTaskApi.getIntField(target, name);
        } catch (ReflectiveOperationException | RuntimeException error) {
            return fallback;
        }
    }

    static final class Sample {
        final List<?> rawTasks;
        final List<FrameworkTaskSnapshot> snapshots;

        Sample(
                final List<?> rawTasks,
                final List<FrameworkTaskSnapshot> snapshots) {
            this.rawTasks = rawTasks == null
                    ? Collections.emptyList() : rawTasks;
            this.snapshots = Collections.unmodifiableList(
                    new ArrayList<>(snapshots));
        }
    }

    static final class OrderedTasks {
        final List<?> tasks;
        final Map<Integer, Integer> rootTaskIds;

        OrderedTasks(
                final List<?> tasks,
                final Map<Integer, Integer> rootTaskIds) {
            this.tasks = Collections.unmodifiableList(
                    new ArrayList<>(tasks));
            this.rootTaskIds = Collections.unmodifiableMap(
                    new HashMap<>(rootTaskIds));
        }
    }
}
