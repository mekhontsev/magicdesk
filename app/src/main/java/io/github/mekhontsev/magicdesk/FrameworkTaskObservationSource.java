package io.github.mekhontsev.magicdesk;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Rect;
import android.util.Log;
import android.view.WindowInsets;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Central hybrid source for framework task observations unavailable through a
 * complete callback API.
 */
final class FrameworkTaskObservationSource implements Closeable {
    private static final String TAG = "MagicDeskTasks";

    interface Listener {
        void onTasksSampled(
                int displayId,
                List<?> tasks,
                List<FrameworkTaskSnapshot> taskSnapshots);
        void onTaskStackChanged();
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

    private static final int ACTIVITY_TYPE_STANDARD = 1;
    private static final int WINDOWING_MODE_FREEFORM = 5;
    private final Object mService;
    private final FrameworkWindowingCompat mFrameworkCompat;
    private final FrameworkWindowingCompat.TaskObservationCapabilities
            mCapabilities;
    private final ActivityManager mActivityManager;
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
    private List<Long> mLastTaskStackFingerprint = new ArrayList<>();
    private final Thread mThread;

    private boolean mClosed;
    private boolean mTaskStackSampled;
    private long mSampleGeneration;
    private int mDisplayId = -1;
    private Rect mDisplayBounds = new Rect();
    private Rect mWorkAreaBounds = new Rect();

    FrameworkTaskObservationSource(
            final Context context,
            final Object service,
            final boolean refreshCaptionAfterNativeFullscreen,
            final Listener listener) throws ReflectiveOperationException {
        if (context == null) {
            throw new IllegalArgumentException("missing task observer context");
        }
        mService = service;
        mFrameworkCompat = FrameworkRuntime.current().windowingCompat();
        mCapabilities = mFrameworkCompat.capabilities().taskObservation;
        mActivityManager = context.getSystemService(ActivityManager.class);
        if (mActivityManager == null) {
            throw new IllegalStateException("activity manager unavailable");
        }
        mListener = listener;
        mRefreshCaptionAfterNativeFullscreen =
                refreshCaptionAfterNativeFullscreen
                        && mCapabilities.captionSource
                                != FrameworkWindowingCompat
                                        .ObservationProvenance.UNAVAILABLE;
        mThread = new Thread(this::run, "MagicDeskFrameworkTasks");
        mThread.setDaemon(true);
    }

    void start() {
        mThread.start();
    }

    void configure(
            final int displayId,
            final Rect displayBounds,
            final Rect workAreaBounds) {
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
                throw new IllegalStateException(
                        "framework task observation source is closed");
            }
            if (mDisplayId == displayId
                    && mDisplayBounds.equals(displayBounds)
                    && mWorkAreaBounds.equals(workAreaBounds)) {
                return;
            }
            mDisplayId = displayId;
            mDisplayBounds = new Rect(displayBounds);
            mWorkAreaBounds = new Rect(workAreaBounds);
            mLastVisibleTypes.clear();
            mLastProcessIds.clear();
            mLastFreeformBounds.clear();
            mLastWindowingModes.clear();
            mCaptionSourceIds.clear();
            mCaptionCaptureAttempted.clear();
            mLastTaskStackFingerprint.clear();
            mTaskStackSampled = false;
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
            mLastTaskStackFingerprint.clear();
            mTaskStackSampled = false;
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
            mLastTaskStackFingerprint.clear();
            mTaskStackSampled = false;
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
                        EventDrivenWaits.await(
                                mLock,
                                EventDrivenWaits.Reason
                                        .FRAMEWORK_OBSERVER_ACTIVATION);
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
                final FrameworkTaskSnapshotSource.Sample sample =
                        FrameworkTaskSnapshotSource.read(
                                mService,
                                displayId,
                                mCapabilities.taskLimit,
                                mFrameworkCompat);
                final List<?> tasks = sample.rawTasks;
                final List<FrameworkTaskSnapshot> taskSnapshots =
                        sample.snapshots;
                mListener.onTasksSampled(
                        displayId, tasks, taskSnapshots);
                publishTaskStackChanges(displayId, taskSnapshots);
                publishWindowChanges(displayId, taskSnapshots);
                publishImmersiveChanges(displayId, taskSnapshots);
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
                        EventDrivenWaits.await(
                                mLock,
                                EventDrivenWaits.Reason
                                        .FRAMEWORK_OBSERVER_RESAMPLE,
                                mCapabilities.fallbackIntervalMillis);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    private void publishTaskStackChanges(
            final int displayId,
            final List<FrameworkTaskSnapshot> states) {
        final List<Long> fingerprint = taskStackFingerprint(states);
        final boolean changed;
        synchronized (mLock) {
            if (mClosed || displayId != mDisplayId) {
                return;
            }
            changed = !mTaskStackSampled
                    || !fingerprint.equals(mLastTaskStackFingerprint);
            if (changed) {
                mLastTaskStackFingerprint = fingerprint;
                mTaskStackSampled = true;
            }
        }
        if (changed) {
            mListener.onTaskStackChanged();
        }
    }

    static List<Long> taskStackFingerprint(
            final List<FrameworkTaskSnapshot> states) {
        final List<Long> fingerprint = new ArrayList<>();
        if (states == null) {
            return fingerprint;
        }
        for (final FrameworkTaskSnapshot state : states) {
            if (state == null) {
                continue;
            }
            long value = ((long) state.taskId) << 32;
            value |= ((long) state.windowingMode & 0xffffL) << 16;
            value |= ((long) state.activityType & 0x3fffL) << 2;
            if (state.visible) {
                value |= 1L << 1;
            }
            if (state.focused) {
                value |= 1L;
            }
            fingerprint.add(Long.valueOf(value));
        }
        return fingerprint;
    }

    private void publishImmersiveChanges(
            final int displayId,
            final List<FrameworkTaskSnapshot> states)
            throws ReflectiveOperationException {
        if (states == null) {
            return;
        }
        final Set<Integer> liveTaskIds = new HashSet<>();
        final Map<Integer, Integer> visibleTypesByTask = new HashMap<>();
        final Map<Integer, Integer> processIdsByTask = new HashMap<>();
        final Map<Integer, Boolean> focusedByTask = new HashMap<>();
        final Map<ProcessIdentity, Integer> runningProcesses =
                mCapabilities.immersiveRequest
                        != FrameworkWindowingCompat.ObservationProvenance
                                .UNAVAILABLE
                        ? loadRunningProcesses()
                        : new HashMap<>();
        for (final FrameworkTaskSnapshot state : states) {
            final Integer taskId = Integer.valueOf(state.taskId);
            liveTaskIds.add(taskId);
            if (!state.visible) {
                continue;
            }
            if (state.requestedVisibleTypes == null) {
                continue;
            }
            visibleTypesByTask.put(taskId, state.requestedVisibleTypes);
            focusedByTask.put(taskId, Boolean.valueOf(state.focused));
            final Integer processId = findProcessId(
                    state, runningProcesses);
            if (processId != null) {
                processIdsByTask.put(taskId, processId);
            }
        }

        final List<ImmersiveEvent> events = new ArrayList<>();
        synchronized (mLock) {
            if (displayId != mDisplayId || mClosed) {
                return;
            }
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
            boolean foreground = event.taskFocused;
            // TaskInfo.isFocused is false for a focused child of an organizer
            // TaskDisplayArea on some firmware. Confirm every negative sample
            // against InputDispatcher before classifying the request. Entry
            // and exit must use the same observed source of foreground truth.
            if (!foreground && !inputStateRead) {
                inputStateRead = true;
                try {
                    inputState = FrameworkInputSnapshotSource.readLocal();
                } catch (IOException error) {
                    Log.w(TAG, "could not inspect immersive input focus",
                            error);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    Log.w(TAG, "immersive input focus interrupted", error);
                }
            }
            if (!foreground) {
                foreground = inputState != null
                        && TaskInputWindowParser.isTaskFocused(
                                inputState, displayId, event.taskId);
            }
            Log.i(TAG, "immersive focus task=" + event.taskId
                    + " requesting=" + event.requesting
                    + " taskFocused=" + event.taskFocused
                    + " foreground=" + foreground
                    + (event.taskFocused
                            ? " source=task" : " source=task+input"));
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
            final FrameworkTaskSnapshot task,
            final Map<ProcessIdentity, Integer> runningProcesses) {
        if (task.topUid < 0 || task.topProcessName == null
                || task.topProcessName.isEmpty()) {
            return null;
        }
        return runningProcesses.get(new ProcessIdentity(
                task.topUid,
                task.topProcessName));
    }

    private void publishWindowChanges(
            final int displayId,
            final List<FrameworkTaskSnapshot> states) {
        final Set<Integer> liveTaskIds = new HashSet<>();
        final Set<Integer> visibleTaskIds = new HashSet<>();
        final Map<Integer, Integer> windowingModes = new HashMap<>();
        final Map<Integer, FreeformBoundsState> freeformBounds =
                collectFreeformBounds(states);
        for (final FrameworkTaskSnapshot state : states) {
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
            // A valid built-in key identifies a user-facing MagicDesk window.
            // Infrastructure activities returned no key and were rejected
            // above, so native caption transitions must observe these tasks
            // just like applications from another package.
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

    static Map<Integer, FreeformBoundsState> collectFreeformBounds(
            final List<FrameworkTaskSnapshot> states) {
        final Map<Integer, FreeformBoundsState> result = new HashMap<>();
        if (states == null) {
            return result;
        }
        for (final FrameworkTaskSnapshot state : states) {
            if (state == null
                    || state.activityType != ACTIVITY_TYPE_STANDARD
                    || state.windowingMode != WINDOWING_MODE_FREEFORM
                    || !state.visible
                    || state.bounds.right <= state.bounds.left
                    || state.bounds.bottom <= state.bounds.top
                    || !PackageNameValidator.isSafe(state.packageName)) {
                continue;
            }
            final String stateKey = BuiltInDesktopAppCatalog.appIdentityKey(
                    state.packageName,
                    state.rootComponent == null
                            ? null
                            : state.rootComponent.flattenToString());
            final boolean fixture = DesktopSelfTestComponents
                    .isFixtureComponent(state.componentName)
                    || DesktopSelfTestComponents
                            .isFixtureComponent(state.topActivityName);
            if (!AppWindowStateStore.isSafeStateKey(stateKey) && !fixture) {
                continue;
            }
            final boolean persistable = AppWindowStateStore.isSafeStateKey(
                    stateKey)
                    && (state.topPackage == null
                            || state.packageName.equals(state.topPackage));
            result.put(
                    Integer.valueOf(state.taskId),
                    new FreeformBoundsState(
                            persistable ? stateKey : "",
                            state.bounds));
        }
        return result;
    }

    static boolean isRequestingImmersive(
            final int requestedVisibleTypes) {
        return (requestedVisibleTypes & WindowInsets.Type.statusBars()) == 0;
    }

    private static boolean findFocusedState(
            final List<FrameworkTaskSnapshot> states,
            final int taskId) {
        for (final FrameworkTaskSnapshot state : states) {
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

    static final class FreeformBoundsState {
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
