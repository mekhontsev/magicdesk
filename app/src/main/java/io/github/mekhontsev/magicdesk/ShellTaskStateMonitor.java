package io.github.mekhontsev.magicdesk;

import android.graphics.Rect;
import android.view.WindowInsets;

import java.io.Closeable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ShellTaskStateMonitor implements Closeable {
    interface Listener {
        void onTasksSampled(int displayId, List<?> tasks);
        void onImmersiveRequest(
                int taskId, boolean requesting, boolean initialSample);
        void onNativeMaximizeChanged(
                int taskId, boolean enteredFullscreen);
        void onError(String error);
    }

    private static final long POLL_INTERVAL_MILLIS = 150;
    private static final int MAX_TASKS_TO_SCAN = 16;
    private static final int WINDOWING_MODE_FREEFORM = 5;

    private final Object mService;
    private final Field mRequestedVisibleTypes;
    private final Listener mListener;
    private final Object mLock = new Object();
    private final Map<Integer, Integer> mLastVisibleTypes = new HashMap<>();
    private final Set<Integer> mFullscreenTasks = new HashSet<>();
    private final Set<Integer> mMaximizedTasks = new HashSet<>();
    private final Thread mThread;

    private boolean mClosed;
    private long mSampleGeneration;
    private int mDisplayId = -1;
    private Rect mDisplayBounds = new Rect();
    private Rect mWorkAreaBounds = new Rect();

    ShellTaskStateMonitor(
            final Object service,
            final Listener listener) throws ReflectiveOperationException {
        mService = service;
        mListener = listener;
        mRequestedVisibleTypes = Class.forName("android.app.TaskInfo")
                .getField("requestedVisibleTypes");
        mThread = new Thread(this::run, "MagicDeskTaskStateMonitor");
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
                throw new IllegalStateException("task state monitor is closed");
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
            mFullscreenTasks.clear();
            mMaximizedTasks.clear();
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
            mFullscreenTasks.clear();
            mMaximizedTasks.clear();
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
                mListener.onTasksSampled(displayId, tasks);
                publishNativeMaximizeTransitions(
                        displayId, displayBounds, workAreaBounds, tasks);
                publishImmersiveChanges(displayId, tasks);
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

    private void publishImmersiveChanges(
            final int displayId,
            final List<?> tasks) throws ReflectiveOperationException {
        if (tasks == null) {
            return;
        }
        final Set<Integer> liveTaskIds = new HashSet<>();
        final Map<Integer, Integer> visibleTypesByTask = new HashMap<>();
        for (final Object task : tasks) {
            final Integer taskId = Integer.valueOf(
                    HiddenTaskApi.getIntField(task, "taskId"));
            liveTaskIds.add(taskId);
            if (!HiddenTaskApi.getBooleanField(task, "isVisible")) {
                continue;
            }
            visibleTypesByTask.put(
                    taskId,
                    Integer.valueOf(mRequestedVisibleTypes.getInt(task)));
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
                if (previous == null
                        || previous.intValue() != entry.getValue().intValue()) {
                    events.add(new ImmersiveEvent(
                            entry.getKey().intValue(),
                            isRequestingImmersive(entry.getValue().intValue()),
                            previous == null));
                }
            }
            mLastVisibleTypes.keySet().retainAll(liveTaskIds);
        }
        for (final ImmersiveEvent event : events) {
            mListener.onImmersiveRequest(
                    event.taskId, event.requesting, event.initialSample);
        }
    }

    private void publishNativeMaximizeTransitions(
            final int displayId,
            final Rect displayBounds,
            final Rect workAreaBounds,
            final List<?> tasks) throws ReflectiveOperationException {
        final Set<Integer> fullscreenTasks = new HashSet<>();
        final Set<Integer> maximizedTasks = new HashSet<>();
        for (final Object task : tasks) {
            if (!HiddenTaskApi.getBooleanField(task, "isVisible")) {
                continue;
            }
            final Object windowConfiguration =
                    HiddenTaskApi.getWindowConfiguration(task);
            final int windowingMode =
                    HiddenTaskApi.getWindowConfigurationValue(
                            task, "getWindowingMode");
            if (windowingMode != WINDOWING_MODE_FREEFORM) {
                continue;
            }
            final Rect bounds = (Rect) windowConfiguration.getClass()
                    .getMethod("getBounds").invoke(windowConfiguration);
            if (displayBounds.equals(bounds)) {
                final Integer taskId = Integer.valueOf(
                        HiddenTaskApi.getIntField(task, "taskId"));
                fullscreenTasks.add(taskId);
                maximizedTasks.add(taskId);
            } else if (workAreaBounds.equals(bounds)) {
                maximizedTasks.add(Integer.valueOf(
                        HiddenTaskApi.getIntField(task, "taskId")));
            }
        }

        final List<Integer> enteredFullscreen = new ArrayList<>();
        final List<Integer> exitedMaximize = new ArrayList<>();
        synchronized (mLock) {
            if (displayId != mDisplayId
                    || !displayBounds.equals(mDisplayBounds)
                    || !workAreaBounds.equals(mWorkAreaBounds)
                    || mClosed) {
                return;
            }
            for (final Integer taskId : fullscreenTasks) {
                if (!mFullscreenTasks.contains(taskId)) {
                    enteredFullscreen.add(taskId);
                }
            }
            for (final Integer taskId : mMaximizedTasks) {
                if (!maximizedTasks.contains(taskId)) {
                    exitedMaximize.add(taskId);
                }
            }
            mFullscreenTasks.clear();
            mFullscreenTasks.addAll(fullscreenTasks);
            mMaximizedTasks.clear();
            mMaximizedTasks.addAll(maximizedTasks);
        }
        for (final Integer taskId : enteredFullscreen) {
            mListener.onNativeMaximizeChanged(taskId.intValue(), true);
        }
        for (final Integer taskId : exitedMaximize) {
            mListener.onNativeMaximizeChanged(taskId.intValue(), false);
        }
    }

    private static boolean isRequestingImmersive(
            final int requestedVisibleTypes) {
        return (requestedVisibleTypes & WindowInsets.Type.statusBars()) == 0;
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

        ImmersiveEvent(
                final int taskId,
                final boolean requesting,
                final boolean initialSample) {
            this.taskId = taskId;
            this.requesting = requesting;
            this.initialSample = initialSample;
        }
    }
}
