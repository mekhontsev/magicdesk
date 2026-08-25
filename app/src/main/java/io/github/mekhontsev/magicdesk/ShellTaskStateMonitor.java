package io.github.mekhontsev.magicdesk;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Rect;
import android.util.Log;
import android.view.WindowInsets;

import java.io.Closeable;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ShellTaskStateMonitor implements Closeable {
    private static final String TAG = "MagicDeskTasks";

    interface Listener {
        void onTasksSampled(
                int displayId,
                List<?> tasks,
                List<TaskWindowState> windowStates);
        void onImmersiveRequest(
                int taskId, boolean requesting, boolean initialSample,
                boolean foreground);
        void onWindowingModeChanged(
                int displayId,
                int taskId,
                int previousMode,
                int currentMode,
                int previousCaptionSourceId,
                boolean focused);
        void onFreeformBoundsChanged(
                int taskId,
                String stateKey,
                int displayId,
                Rect bounds);
        void onError(String error);
    }

    static final class TaskWindowState {
        final Object task;
        final int taskId;
        final boolean visible;
        final boolean focused;
        final int requestedVisibleTypes;
        final int windowingMode;
        final int activityType;
        final ComponentName rootComponent;
        final ComponentName topComponent;
        final String packageName;
        final String topPackage;
        final Rect bounds;

        TaskWindowState(
                final Object rawTask,
                final int observedTaskId,
                final boolean observedVisible,
                final boolean observedFocused,
                final int observedVisibleTypes,
                final int observedWindowingMode,
                final int observedActivityType,
                final ComponentName observedRootComponent,
                final ComponentName observedTopComponent,
                final String observedPackageName,
                final String observedTopPackage,
                final Rect observedBounds) {
            task = rawTask;
            taskId = observedTaskId;
            visible = observedVisible;
            focused = observedFocused;
            requestedVisibleTypes = observedVisibleTypes;
            windowingMode = observedWindowingMode;
            activityType = observedActivityType;
            rootComponent = observedRootComponent;
            topComponent = observedTopComponent;
            packageName = observedPackageName;
            topPackage = observedTopPackage;
            bounds = new Rect(observedBounds);
        }

        boolean requestingImmersive() {
            return isRequestingImmersive(requestedVisibleTypes);
        }
    }

    private static final long POLL_INTERVAL_MILLIS = 150;
    private static final int MAX_TASKS_TO_SCAN = 16;
    private static final int ACTIVITY_TYPE_STANDARD = 1;
    private static final int WINDOWING_MODE_FREEFORM = 5;
    private static final String MAGICDESK_PACKAGE =
            "io.github.mekhontsev.magicdesk";
    private final Object mService;
    private final ActivityManager mActivityManager;
    private final Field mTopActivityInfo;
    private final Field mRequestedVisibleTypes;
    private final Listener mListener;
    private final boolean mRefreshCaptionAfterNativeFullscreen;
    private final Object mLock = new Object();
    private final Map<Integer, Integer> mLastVisibleTypes = new HashMap<>();
    private final Map<Integer, Integer> mLastProcessIds = new HashMap<>();
    private final Map<Integer, FreeformBoundsState> mLastFreeformBounds =
            new HashMap<>();
    private final Map<Integer, Integer> mLastWindowingModes = new HashMap<>();
    // InsetsSource IDs contain an owner identity and cannot be reconstructed
    // after WindowManager removes the source during fullscreen entry.
    private final Map<Integer, Integer> mCaptionSourceIds = new HashMap<>();
    private final Set<Integer> mCaptionCaptureAttempted = new HashSet<>();
    private final Thread mThread;

    private boolean mClosed;
    private long mSampleGeneration;
    private int mDisplayId = -1;
    private Rect mDisplayBounds = new Rect();
    private Rect mWorkAreaBounds = new Rect();
    private boolean mTaskFocusAuthoritative;

    ShellTaskStateMonitor(
            final Context context,
            final Object service,
            final boolean refreshCaptionAfterNativeFullscreen,
            final Listener listener) throws ReflectiveOperationException {
        if (context == null) {
            throw new IllegalArgumentException("missing task observer context");
        }
        mService = service;
        mActivityManager = context.getSystemService(ActivityManager.class);
        if (mActivityManager == null) {
            throw new IllegalStateException("activity manager unavailable");
        }
        mListener = listener;
        mRefreshCaptionAfterNativeFullscreen =
                refreshCaptionAfterNativeFullscreen;
        final Class<?> taskInfo = Class.forName("android.app.TaskInfo");
        mTopActivityInfo = taskInfo.getField("topActivityInfo");
        mRequestedVisibleTypes = taskInfo.getField("requestedVisibleTypes");
        mThread = new Thread(this::run, "MagicDeskTaskStateMonitor");
        mThread.setDaemon(true);
    }

    void start() {
        mThread.start();
    }

    void configure(
            final int displayId,
            final Rect displayBounds,
            final Rect workAreaBounds,
            final boolean taskFocusAuthoritative) {
        if (displayId < 0
                || displayBounds == null
                || displayBounds.isEmpty()
                || workAreaBounds == null
                || workAreaBounds.isEmpty()
                || !displayBounds.contains(workAreaBounds)) {
            throw new IllegalArgumentException("invalid task observer bounds");
        }
        synchronized (mLock) {
            if (mClosed) {
                throw new IllegalStateException("task state monitor is closed");
            }
            if (mDisplayId == displayId
                    && mDisplayBounds.equals(displayBounds)
                    && mWorkAreaBounds.equals(workAreaBounds)
                    && mTaskFocusAuthoritative == taskFocusAuthoritative) {
                return;
            }
            mDisplayId = displayId;
            mDisplayBounds = new Rect(displayBounds);
            mWorkAreaBounds = new Rect(workAreaBounds);
            mTaskFocusAuthoritative = taskFocusAuthoritative;
            mLastVisibleTypes.clear();
            mLastProcessIds.clear();
            mLastFreeformBounds.clear();
            mLastWindowingModes.clear();
            mCaptionSourceIds.clear();
            mCaptionCaptureAttempted.clear();
            mSampleGeneration++;
            mLock.notifyAll();
        }
    }

    void clearConfiguration() {
        synchronized (mLock) {
            if (mClosed || mDisplayId < 0) {
                return;
            }
            mDisplayId = -1;
            mDisplayBounds.setEmpty();
            mWorkAreaBounds.setEmpty();
            mLastVisibleTypes.clear();
            mLastProcessIds.clear();
            mLastFreeformBounds.clear();
            mLastWindowingModes.clear();
            mCaptionSourceIds.clear();
            mCaptionCaptureAttempted.clear();
            mSampleGeneration++;
            mLock.notifyAll();
        }
    }

    void requestSample() {
        synchronized (mLock) {
            if (!mClosed) {
                mSampleGeneration++;
                mLock.notifyAll();
            }
        }
    }

    @Override
    public void close() {
        synchronized (mLock) {
            if (mClosed) {
                return;
            }
            mClosed = true;
            mDisplayId = -1;
            mLastVisibleTypes.clear();
            mLastProcessIds.clear();
            mLastFreeformBounds.clear();
            mLastWindowingModes.clear();
            mCaptionSourceIds.clear();
            mCaptionCaptureAttempted.clear();
            mLock.notifyAll();
        }
        mThread.interrupt();
    }

    private void run() {
        boolean failureReported = false;
        while (true) {
            final int displayId;
            final Rect displayBounds;
            final Rect workAreaBounds;
            final long sampleGeneration;
            synchronized (mLock) {
                while (!mClosed
                        && (mDisplayId < 0 || mDisplayBounds.isEmpty())) {
                    try {
                        mLock.wait();
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                if (mClosed) {
                    return;
                }
                displayId = mDisplayId;
                displayBounds = new Rect(mDisplayBounds);
                workAreaBounds = new Rect(mWorkAreaBounds);
                sampleGeneration = mSampleGeneration;
            }
            try {
                final List<?> tasks = loadTasks(displayId);
                final List<TaskWindowState> windowStates =
                        readWindowStates(tasks);
                mListener.onTasksSampled(
                        displayId, tasks, windowStates);
                publishWindowChanges(displayId, windowStates);
                publishImmersiveChanges(displayId, windowStates);
                failureReported = false;
            } catch (ReflectiveOperationException | RuntimeException error) {
                if (!failureReported) {
                    mListener.onError(usefulMessage(error));
                    failureReported = true;
                }
            }
            synchronized (mLock) {
                if (!mClosed
                        && displayId == mDisplayId
                        && displayBounds.equals(mDisplayBounds)
                        && workAreaBounds.equals(mWorkAreaBounds)
                        && sampleGeneration == mSampleGeneration) {
                    try {
                        mLock.wait(POLL_INTERVAL_MILLIS);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    private List<?> loadTasks(final int displayId)
            throws ReflectiveOperationException {
        return HiddenTaskApi.getTasks(
                mService, displayId, MAX_TASKS_TO_SCAN);
    }

    private List<TaskWindowState> readWindowStates(final List<?> tasks)
            throws ReflectiveOperationException {
        final List<TaskWindowState> states = new ArrayList<>();
        if (tasks == null) {
            return states;
        }
        for (final Object task : tasks) {
            final int taskId = HiddenTaskApi.getIntField(task, "taskId");
            final boolean visible =
                    HiddenTaskApi.getBooleanField(task, "isVisible");
            final boolean focused =
                    HiddenTaskApi.getBooleanField(task, "isFocused");
            final int requestedVisibleTypes = visible
                    ? mRequestedVisibleTypes.getInt(task) : 0;
            final int windowingMode =
                    HiddenTaskApi.getWindowConfigurationValue(
                            task, "getWindowingMode");
            final int activityType =
                    HiddenTaskApi.getWindowConfigurationValue(
                            task, "getActivityType");
            final Object windowConfiguration =
                    HiddenTaskApi.getWindowConfiguration(task);
            final Rect bounds = (Rect) windowConfiguration.getClass()
                    .getMethod("getBounds")
                    .invoke(windowConfiguration);
            final ComponentName rootComponent =
                    HiddenTaskApi.getTaskComponent(task);
            final ComponentName topComponent =
                    HiddenTaskApi.getTaskTopComponent(task);
            final String packageName = rootComponent == null
                    ? null : rootComponent.getPackageName();
            final Object topActivityInfo = mTopActivityInfo.get(task);
            final String topPackage = topActivityInfo instanceof ActivityInfo
                    ? ((ActivityInfo) topActivityInfo).packageName
                    : topComponent == null
                            ? null : topComponent.getPackageName();
            states.add(new TaskWindowState(
                    task,
                    taskId,
                    visible,
                    focused,
                    requestedVisibleTypes,
                    windowingMode,
                    activityType,
                    rootComponent,
                    topComponent,
                    packageName,
                    topPackage,
                    bounds));
        }
        return states;
    }

    private void publishImmersiveChanges(
            final int displayId,
            final List<TaskWindowState> states)
            throws ReflectiveOperationException {
        if (states == null) {
            return;
        }
        final Set<Integer> liveTaskIds = new HashSet<>();
        final Map<Integer, Integer> visibleTypesByTask = new HashMap<>();
        final Map<Integer, Integer> processIdsByTask = new HashMap<>();
        final Map<Integer, Boolean> focusedByTask = new HashMap<>();
        final Map<ProcessIdentity, Integer> runningProcesses =
                loadRunningProcesses();
        for (final TaskWindowState state : states) {
            final Integer taskId = Integer.valueOf(state.taskId);
            liveTaskIds.add(taskId);
            if (!state.visible) {
                continue;
            }
            visibleTypesByTask.put(
                    taskId,
                    Integer.valueOf(state.requestedVisibleTypes));
            focusedByTask.put(taskId, Boolean.valueOf(state.focused));
            final Integer processId = findProcessId(
                    state.task, runningProcesses);
            if (processId != null) {
                processIdsByTask.put(taskId, processId);
            }
        }

        final List<ImmersiveEvent> events = new ArrayList<>();
        final boolean taskFocusAuthoritative;
        synchronized (mLock) {
            if (displayId != mDisplayId || mClosed) {
                return;
            }
            taskFocusAuthoritative = mTaskFocusAuthoritative;
            for (final Map.Entry<Integer, Integer> entry
                    : visibleTypesByTask.entrySet()) {
                final Integer previous = mLastVisibleTypes.put(
                        entry.getKey(), entry.getValue());
                final Integer processId = processIdsByTask.get(entry.getKey());
                final Integer previousProcessId = processId == null
                        ? mLastProcessIds.get(entry.getKey())
                        : mLastProcessIds.put(entry.getKey(), processId);
                // requestedVisibleTypes belongs to the activity client. Its
                // initial value after a process restart is not a new request.
                final boolean initialSample = isInitialClientSample(
                        previous, previousProcessId, processId);
                if (previous == null
                        || previous.intValue() != entry.getValue().intValue()
                        || initialSample) {
                    Log.i(TAG, "immersive state task=" + entry.getKey()
                            + " pid=" + processId
                            + " initial=" + initialSample
                            + " focused=" + Boolean.TRUE.equals(
                                    focusedByTask.get(entry.getKey()))
                            + " requesting="
                            + isRequestingImmersive(
                                    entry.getValue().intValue()));
                    events.add(new ImmersiveEvent(
                            entry.getKey().intValue(),
                            isRequestingImmersive(entry.getValue().intValue()),
                            initialSample,
                            Boolean.TRUE.equals(
                                    focusedByTask.get(entry.getKey()))));
                }
            }
            mLastVisibleTypes.keySet().retainAll(liveTaskIds);
            mLastProcessIds.keySet().retainAll(liveTaskIds);
        }
        String inputState = null;
        boolean inputStateRead = false;
        for (final ImmersiveEvent event : events) {
            boolean foreground = true;
            if (!event.requesting && !event.initialSample) {
                foreground = event.taskFocused;
                if (!taskFocusAuthoritative && !foreground
                        && !inputStateRead) {
                    inputStateRead = true;
                    try {
                        inputState = InputStateDump.read();
                    } catch (IOException error) {
                        Log.w(TAG, "could not inspect immersive input focus",
                                error);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        Log.w(TAG, "immersive input focus interrupted", error);
                    }
                }
                if (!taskFocusAuthoritative && !foreground) {
                    foreground = inputState != null
                            && TaskInputWindowParser.isTaskFocused(
                                    inputState, displayId, event.taskId);
                }
                Log.i(TAG, "immersive exit focus task=" + event.taskId
                        + " taskFocused=" + event.taskFocused
                        + " foreground=" + foreground
                        + (taskFocusAuthoritative
                                ? " source=task" : " source=task+input"));
            }
            mListener.onImmersiveRequest(
                    event.taskId,
                    event.requesting,
                    event.initialSample,
                    foreground);
        }
    }

    private Map<ProcessIdentity, Integer> loadRunningProcesses() {
        final Map<ProcessIdentity, Integer> result = new HashMap<>();
        final List<ActivityManager.RunningAppProcessInfo> processes =
                mActivityManager.getRunningAppProcesses();
        if (processes == null) {
            return result;
        }
        for (final ActivityManager.RunningAppProcessInfo process : processes) {
            if (process == null || process.pid <= 0 || process.processName == null) {
                continue;
            }
            final ProcessIdentity identity =
                    new ProcessIdentity(process.uid, process.processName);
            final Integer previousPid = result.get(identity);
            if (previousPid == null || process.pid > previousPid.intValue()) {
                result.put(identity, Integer.valueOf(process.pid));
            }
        }
        return result;
    }

    private Integer findProcessId(
            final Object task,
            final Map<ProcessIdentity, Integer> runningProcesses)
            throws IllegalAccessException {
        final Object value = mTopActivityInfo.get(task);
        if (!(value instanceof ActivityInfo)) {
            return null;
        }
        final ActivityInfo activity = (ActivityInfo) value;
        if (activity.applicationInfo == null || activity.processName == null) {
            return null;
        }
        return runningProcesses.get(new ProcessIdentity(
                activity.applicationInfo.uid,
                activity.processName));
    }

    private void publishWindowChanges(
            final int displayId,
            final List<TaskWindowState> states) {
        final Set<Integer> liveTaskIds = new HashSet<>();
        final Set<Integer> visibleTaskIds = new HashSet<>();
        final Set<String> observedStateKeys = new HashSet<>();
        final Map<Integer, Integer> windowingModes = new HashMap<>();
        final Map<Integer, FreeformBoundsState> freeformBounds =
                new HashMap<>();
        for (final TaskWindowState state : states) {
            if (state.activityType != ACTIVITY_TYPE_STANDARD
                    || !PackageNameValidator.isSafe(state.packageName)
                    || (state.topPackage != null
                            && !state.packageName.equals(state.topPackage))) {
                continue;
            }
            final String stateKey = BuiltInDesktopAppCatalog.appIdentityKey(
                    state.packageName,
                    state.rootComponent == null
                            ? null
                            : state.rootComponent.flattenToString());
            if (!AppWindowStateStore.isSafeStateKey(stateKey)) {
                continue;
            }
            final Integer taskKey = Integer.valueOf(state.taskId);
            if (state.windowingMode == WINDOWING_MODE_FREEFORM
                    && state.visible
                    && !state.bounds.isEmpty()
                    && observedStateKeys.add(stateKey)) {
                freeformBounds.put(
                        taskKey,
                        new FreeformBoundsState(stateKey, state.bounds));
            }
            if (MAGICDESK_PACKAGE.equals(state.packageName)) {
                continue;
            }
            liveTaskIds.add(taskKey);
            windowingModes.put(
                    taskKey, Integer.valueOf(state.windowingMode));
            if (state.visible) {
                visibleTaskIds.add(taskKey);
            }
        }

        final Set<Integer> captionCaptureCandidates = new HashSet<>();
        final List<WindowingModeEvent> modeChanges = new ArrayList<>();
        final List<FreeformBoundsEvent> boundsChanges = new ArrayList<>();
        synchronized (mLock) {
            if (displayId != mDisplayId || mClosed) {
                return;
            }
            for (final Map.Entry<Integer, Integer> entry
                    : windowingModes.entrySet()) {
                final Integer taskId = entry.getKey();
                final int currentMode = entry.getValue().intValue();
                final Integer previous = mLastWindowingModes.get(taskId);
                if (previous != null
                        && previous.intValue() != currentMode) {
                    final Integer sourceId = mCaptionSourceIds.get(taskId);
                    modeChanges.add(new WindowingModeEvent(
                            taskId.intValue(),
                            previous.intValue(),
                            currentMode,
                            sourceId == null
                                    ? TaskLocalInsetsSourceParser.NO_SOURCE_ID
                                    : sourceId.intValue(),
                            findFocusedState(states, taskId.intValue())));
                }
                if (currentMode == WINDOWING_MODE_FREEFORM) {
                    if (previous == null
                            || previous.intValue()
                                    != WINDOWING_MODE_FREEFORM) {
                        mCaptionSourceIds.remove(taskId);
                        mCaptionCaptureAttempted.remove(taskId);
                    }
                    if (mRefreshCaptionAfterNativeFullscreen
                            && visibleTaskIds.contains(taskId)
                            && previous != null
                            && previous.intValue() == WINDOWING_MODE_FREEFORM
                            && mCaptionCaptureAttempted.add(taskId)) {
                        captionCaptureCandidates.add(taskId);
                    }
                } else {
                    mCaptionSourceIds.remove(taskId);
                    mCaptionCaptureAttempted.remove(taskId);
                }
            }
            for (final Map.Entry<Integer, FreeformBoundsState> entry
                    : freeformBounds.entrySet()) {
                if (!entry.getValue().equals(
                        mLastFreeformBounds.get(entry.getKey()))) {
                    boundsChanges.add(new FreeformBoundsEvent(
                            entry.getKey().intValue(), entry.getValue()));
                }
            }
            mLastWindowingModes.clear();
            mLastWindowingModes.putAll(windowingModes);
            mLastFreeformBounds.clear();
            mLastFreeformBounds.putAll(freeformBounds);
            mCaptionSourceIds.keySet().retainAll(liveTaskIds);
            mCaptionCaptureAttempted.retainAll(liveTaskIds);
        }
        if (!captionCaptureCandidates.isEmpty()) {
            // The source vanishes during Nubia's native fullscreen transition.
            // Wait for a second stable freeform sample so the caption has had
            // time to attach, then capture it once; never poll dumpsys after
            // a successful or failed capture attempt.
            final Map<Integer, Integer> captured =
                    TaskCaptionInsetsRefresher.captureCaptionSourceIds(
                            captionCaptureCandidates);
            synchronized (mLock) {
                if (displayId == mDisplayId && !mClosed) {
                    for (final Map.Entry<Integer, Integer> entry
                            : captured.entrySet()) {
                        final Integer currentMode =
                                mLastWindowingModes.get(entry.getKey());
                        if (currentMode != null
                                && currentMode.intValue()
                                        == WINDOWING_MODE_FREEFORM) {
                            mCaptionSourceIds.put(
                                    entry.getKey(), entry.getValue());
                        }
                    }
                }
            }
        }
        for (final FreeformBoundsEvent event : boundsChanges) {
            mListener.onFreeformBoundsChanged(
                    event.taskId,
                    event.state.stateKey,
                    displayId,
                    event.state.bounds);
        }
        for (final WindowingModeEvent event : modeChanges) {
            mListener.onWindowingModeChanged(
                    displayId,
                    event.taskId,
                    event.previousMode,
                    event.currentMode,
                    event.previousCaptionSourceId,
                    event.focused);
        }
    }

    private static boolean isRequestingImmersive(
            final int requestedVisibleTypes) {
        return (requestedVisibleTypes & WindowInsets.Type.statusBars()) == 0;
    }

    private static boolean findFocusedState(
            final List<TaskWindowState> states,
            final int taskId) {
        for (final TaskWindowState state : states) {
            if (state.taskId == taskId) {
                return state.focused;
            }
        }
        return false;
    }

    static boolean isInitialClientSample(
            final Integer previousVisibleTypes,
            final Integer previousProcessId,
            final Integer processId) {
        return previousVisibleTypes == null
                || (previousProcessId != null
                        && processId != null
                        && previousProcessId.intValue() != processId.intValue());
    }

    private static String usefulMessage(final Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        final String message = cause.getMessage();
        return message == null || message.isEmpty()
                ? cause.getClass().getSimpleName() : message;
    }

    private static final class ImmersiveEvent {
        final int taskId;
        final boolean requesting;
        final boolean initialSample;
        final boolean taskFocused;

        ImmersiveEvent(
                final int taskId,
                final boolean requesting,
                final boolean initialSample,
                final boolean taskFocused) {
            this.taskId = taskId;
            this.requesting = requesting;
            this.initialSample = initialSample;
            this.taskFocused = taskFocused;
        }
    }

    private static final class FreeformBoundsEvent {
        final int taskId;
        final FreeformBoundsState state;

        FreeformBoundsEvent(
                final int taskId,
                final FreeformBoundsState state) {
            this.taskId = taskId;
            this.state = state;
        }
    }

    private static final class WindowingModeEvent {
        final int taskId;
        final int previousMode;
        final int currentMode;
        final int previousCaptionSourceId;
        final boolean focused;

        WindowingModeEvent(
                final int taskId,
                final int previousMode,
                final int currentMode,
                final int previousCaptionSourceId,
                final boolean focused) {
            this.taskId = taskId;
            this.previousMode = previousMode;
            this.currentMode = currentMode;
            this.previousCaptionSourceId = previousCaptionSourceId;
            this.focused = focused;
        }
    }

    private static final class FreeformBoundsState {
        final String stateKey;
        final Rect bounds;

        FreeformBoundsState(
                final String observedStateKey,
                final Rect bounds) {
            stateKey = observedStateKey;
            this.bounds = new Rect(bounds);
        }

        @Override
        public boolean equals(final Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof FreeformBoundsState)) {
                return false;
            }
            final FreeformBoundsState state =
                    (FreeformBoundsState) other;
            return stateKey.equals(state.stateKey)
                    && bounds.equals(state.bounds);
        }

        @Override
        public int hashCode() {
            return 31 * stateKey.hashCode() + bounds.hashCode();
        }
    }

    private static final class ProcessIdentity {
        final int uid;
        final String processName;

        ProcessIdentity(final int uid, final String processName) {
            this.uid = uid;
            this.processName = processName;
        }

        @Override
        public boolean equals(final Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof ProcessIdentity)) {
                return false;
            }
            final ProcessIdentity identity = (ProcessIdentity) other;
            return uid == identity.uid
                    && processName.equals(identity.processName);
        }

        @Override
        public int hashCode() {
            return 31 * uid + processName.hashCode();
        }
    }
}
