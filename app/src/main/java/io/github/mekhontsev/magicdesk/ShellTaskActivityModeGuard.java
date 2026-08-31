package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Rect;
import android.os.SystemClock;
import android.util.Log;
import android.view.Display;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Preserves an explicitly selected task mode across activity handoffs. */
final class ShellTaskActivityModeGuard implements
        ShellTaskLauncher.Listener,
        ShellActivityStartController.Listener {
    interface Listener {
        void onTaskCorrected(
                int taskId, String activityName, String restoredMode);
        void onError(String message);
    }

    private static final String TAG = "MagicDeskWindowLaunch";
    private static final int WINDOWING_MODE_FULLSCREEN = 1;
    private static final int WINDOWING_MODE_FREEFORM = 5;
    private static final int NEW_TASK_FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK
            | Intent.FLAG_ACTIVITY_NEW_DOCUMENT
            | Intent.FLAG_ACTIVITY_MULTIPLE_TASK;
    private static final int MAX_PENDING_STARTS = 8;
    private static final long PENDING_START_LIFETIME_MILLIS = 8_000L;

    private final Object mService;
    private final Listener mListener;
    private final boolean mRefreshFullscreenCaption;
    private final Map<Integer, TaskRecord> mTasks = new HashMap<>();
    private final ArrayDeque<PendingStart> mPendingStarts = new ArrayDeque<>();

    private LaunchActivityIdentity mInitialLaunchIdentity;
    private int mInitialLaunchWindowingMode;
    private int mDisplayId = Display.INVALID_DISPLAY;

    ShellTaskActivityModeGuard(
            final Object service,
            final Listener listener,
            final boolean refreshFullscreenCaption) {
        mService = service;
        mListener = listener;
        mRefreshFullscreenCaption = refreshFullscreenCaption;
    }

    synchronized void configure(final int displayId) {
        if (mDisplayId == displayId) {
            return;
        }
        mDisplayId = displayId;
        mInitialLaunchIdentity = null;
        mInitialLaunchWindowingMode = 0;
        mTasks.clear();
        mPendingStarts.clear();
    }

    @Override
    public synchronized void onTaskLaunchStarting(
            final LaunchActivityIdentity identity,
            final int windowingMode) {
        mInitialLaunchIdentity = identity;
        mInitialLaunchWindowingMode = windowingMode;
    }

    @Override
    public synchronized void onTaskIdentified(
            final int taskId,
            final ComponentName component,
            final int displayId,
            final Rect bounds,
            final int windowingMode) {
        if (displayId != mDisplayId
                || component == null
                || !isSupportedMode(windowingMode)
                || (windowingMode == WINDOWING_MODE_FREEFORM
                        && (bounds == null || bounds.isEmpty()))) {
            return;
        }
        mInitialLaunchIdentity = null;
        mInitialLaunchWindowingMode = 0;
        final TaskRecord record = new TaskRecord(
                taskId,
                component,
                displayId,
                bounds == null ? new Rect() : bounds,
                windowingMode);
        if (windowingMode == WINDOWING_MODE_FULLSCREEN) {
            // A vendor organizer may report the newly created task as
            // freeform before the requested launch mode is committed.
            record.activityState.arm(null, component.getPackageName());
        }
        mTasks.put(
                Integer.valueOf(taskId),
                record);
    }

    synchronized boolean onExplicitFullscreenTaskIdentified(
            final int taskId,
            final ComponentName component,
            final int displayId) {
        if (taskId < 0 || displayId != mDisplayId || component == null) {
            return false;
        }
        final TaskRecord previous = mTasks.get(Integer.valueOf(taskId));
        if (previous != null
                && previous.preferredWindowingMode
                        == WINDOWING_MODE_FULLSCREEN) {
            return true;
        }
        mTasks.put(
                Integer.valueOf(taskId),
                new TaskRecord(
                        taskId,
                        component,
                        displayId,
                        previous == null ? new Rect() : previous.bounds,
                        WINDOWING_MODE_FULLSCREEN));
        return true;
    }

    @Override
    public synchronized void onTaskLaunchFinished(
            final LaunchActivityIdentity identity,
            final int windowingMode) {
        if (identity != null
                && identity == mInitialLaunchIdentity
                && windowingMode == mInitialLaunchWindowingMode) {
            mInitialLaunchIdentity = null;
            mInitialLaunchWindowingMode = 0;
        }
    }

    synchronized void onTaskRemoved(final int taskId) {
        mTasks.remove(Integer.valueOf(taskId));
    }

    synchronized void onTaskDisplayChanged(
            final int taskId,
            final int displayId) {
        if (displayId != mDisplayId) {
            onTaskRemoved(taskId);
        }
    }

    @Override
    public synchronized boolean onActivityStarting(
            final Intent intent,
            final String packageName) {
        if (intent == null || mDisplayId == Display.INVALID_DISPLAY) {
            return true;
        }
        final ComponentName component = intent.getComponent();
        if (mInitialLaunchIdentity != null
                && (mInitialLaunchIdentity.matches(component)
                        || (component == null
                                && mInitialLaunchIdentity.matchesPackage(
                                        PackageNameValidator.isSafe(
                                                intent.getPackage())
                                                ? intent.getPackage()
                                                : packageName)))) {
            return true;
        }
        if ((intent.getFlags() & NEW_TASK_FLAGS) != 0) {
            return true;
        }
        final String requestedPackage = component != null
                ? component.getPackageName()
                : PackageNameValidator.isSafe(intent.getPackage())
                        ? intent.getPackage() : packageName;
        if (!PackageNameValidator.isSafe(requestedPackage)) {
            return true;
        }
        final long now = SystemClock.uptimeMillis();
        prunePendingStarts(now);
        while (mPendingStarts.size() >= MAX_PENDING_STARTS) {
            mPendingStarts.removeFirst();
        }
        mPendingStarts.addLast(new PendingStart(
                component == null ? null : component.flattenToShortString(),
                requestedPackage,
                now));
        return true;
    }

    void observeTasks(
            final int displayId,
            final List<FrameworkTaskSnapshot> tasks) {
        if (displayId != configuredDisplayId() || tasks == null) {
            return;
        }
        final List<Correction> corrections = new ArrayList<>();
        synchronized (this) {
            if (displayId != mDisplayId) {
                return;
            }
            final List<ObservedTask> observedTasks = new ArrayList<>();
            for (final FrameworkTaskSnapshot observation
                    : tasks) {
                if (!observation.visible) {
                    continue;
                }
                TaskRecord record = mTasks.get(
                        Integer.valueOf(observation.taskId));
                if (record == null
                        && observation.windowingMode
                                == WINDOWING_MODE_FREEFORM
                        && observation.rootComponent != null
                        && !observation.bounds.isEmpty()) {
                    final boolean pendingFullscreen =
                            isInitialFullscreenTask(
                                    observation.rootComponent);
                    record = new TaskRecord(
                            observation.taskId,
                            observation.rootComponent,
                            displayId,
                            observation.bounds,
                            pendingFullscreen
                                    ? WINDOWING_MODE_FULLSCREEN
                                    : WINDOWING_MODE_FREEFORM);
                    if (pendingFullscreen) {
                        record.activityState.arm(
                                null,
                                observation.rootComponent.getPackageName());
                    }
                    mTasks.put(Integer.valueOf(observation.taskId), record);
                }
                if (record == null) {
                    continue;
                }
                if (observation.windowingMode == WINDOWING_MODE_FREEFORM
                        && !observation.bounds.isEmpty()) {
                    record.bounds.set(observation.bounds);
                }
                observedTasks.add(new ObservedTask(record, observation));
            }
            correlatePendingStarts(observedTasks);
            for (final ObservedTask observed : observedTasks) {
                final TaskRecord record = observed.record;
                final FrameworkTaskSnapshot observation =
                        observed.observation;
                if (record.preferredWindowingMode
                                == WINDOWING_MODE_FULLSCREEN
                        && observation.windowingMode
                                == WINDOWING_MODE_FREEFORM
                        && !record.activityState.isArmed()) {
                    // A mode change without an activity start is a deliberate
                    // restore, so later handoffs follow the new windowed mode.
                    mTasks.remove(Integer.valueOf(record.taskId));
                    continue;
                }
                final String topComponent = observation.topComponent == null
                        ? null
                        : observation.topComponent.flattenToShortString();
                final String topPackage = observation.topComponent == null
                        ? null
                        : observation.topComponent.getPackageName();
                final TaskActivityModeState.Decision decision =
                        record.activityState.observe(
                                topComponent,
                                topPackage,
                                observation.windowingMode,
                                observation.requestingImmersive());
                if (decision
                        == TaskActivityModeState.Decision.RESTORE_FREEFORM
                        || decision
                        == TaskActivityModeState.Decision.RESTORE_FULLSCREEN) {
                    corrections.add(new Correction(
                            record,
                            activityLabel(observation.topComponent,
                                    topPackage),
                            decision));
                }
                record.observeTop(topComponent, topPackage,
                        observation.windowingMode);
            }
        }
        for (final Correction correction : corrections) {
            restorePreferredMode(correction);
        }
    }

    private void restorePreferredMode(final Correction correction) {
        try {
            if (correction.decision
                    == TaskActivityModeState.Decision.RESTORE_FULLSCREEN) {
                TaskFullscreenTransitionCommand.applyFullscreen(
                        correction.record.displayId,
                        correction.record.taskId,
                        false,
                        mRefreshFullscreenCaption);
            } else {
                ShellPreparedTaskTransition.applyFreeform(
                        mService,
                        correction.record.displayId,
                        correction.record.taskId,
                        new Rect(correction.record.bounds));
            }
            synchronized (this) {
                correction.record.activityState.correctionApplied();
            }
            Log.i(TAG, "restored activity handoff mode="
                    + modeLabel(correction.record.preferredWindowingMode)
                    + " task="
                    + correction.record.taskId
                    + " activity=" + correction.activityName);
            if (mListener != null) {
                mListener.onTaskCorrected(
                        correction.record.taskId,
                        correction.activityName,
                        modeLabel(correction.record.preferredWindowingMode));
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            synchronized (this) {
                correction.record.activityState.correctionFailed();
            }
            report("could not restore activity handoff mode="
                    + modeLabel(correction.record.preferredWindowingMode)
                    + " task="
                    + correction.record.taskId + ": "
                    + usefulMessage(error));
        }
    }

    private synchronized int configuredDisplayId() {
        return mDisplayId;
    }

    private boolean isInitialFullscreenTask(
            final ComponentName component) {
        return mInitialLaunchWindowingMode == WINDOWING_MODE_FULLSCREEN
                && mInitialLaunchIdentity != null
                && mInitialLaunchIdentity.matchesPackage(component);
    }

    private static boolean isSupportedMode(final int windowingMode) {
        return windowingMode == WINDOWING_MODE_FULLSCREEN
                || windowingMode == WINDOWING_MODE_FREEFORM;
    }

    private void correlatePendingStarts(final List<ObservedTask> observations) {
        final long now = SystemClock.uptimeMillis();
        prunePendingStarts(now);
        final Iterator<PendingStart> pending = mPendingStarts.iterator();
        while (pending.hasNext()) {
            final PendingStart candidate = pending.next();
            ObservedTask match = null;
            boolean ambiguous = false;
            for (final ObservedTask observation : observations) {
                if (!candidate.matches(observation)
                        || !observation.changedFor(candidate)) {
                    continue;
                }
                if (match != null) {
                    ambiguous = true;
                    break;
                }
                match = observation;
            }
            if (ambiguous || match == null) {
                continue;
            }
            match.record.activityState.arm(
                    candidate.component, candidate.packageName);
            Log.d(TAG, "armed observed activity handoff task="
                    + match.record.taskId + " activity=" + candidate.label());
            pending.remove();
        }
    }

    private void prunePendingStarts(final long now) {
        // IActivityController reports starts before a task identity exists.
        // Keep that evidence only across the normal activity-start window;
        // an unmatched global start must never affect a later task transition.
        while (!mPendingStarts.isEmpty()
                && now - mPendingStarts.peekFirst().createdUptimeMillis
                        > PENDING_START_LIFETIME_MILLIS) {
            mPendingStarts.removeFirst();
        }
    }

    private void report(final String message) {
        Log.w(TAG, message);
        if (mListener != null) {
            mListener.onError(message);
        }
    }

    private static String activityLabel(
            final ComponentName component,
            final String packageName) {
        return component == null
                ? String.valueOf(packageName)
                : component.flattenToShortString();
    }

    private static String usefulMessage(final Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        final String message = cause.getMessage();
        return message == null || message.trim().isEmpty()
                ? cause.getClass().getSimpleName() : message;
    }

    private static final class TaskRecord {
        final int taskId;
        final int displayId;
        final Rect bounds;
        final TaskActivityModeState activityState;
        final String rootComponent;
        final int preferredWindowingMode;
        String topComponent;
        String topPackage;
        int windowingMode;
        boolean topObserved;

        TaskRecord(
                final int observedTaskId,
                final ComponentName component,
                final int targetDisplayId,
                final Rect initialBounds,
                final int targetWindowingMode) {
            taskId = observedTaskId;
            displayId = targetDisplayId;
            bounds = new Rect(initialBounds);
            rootComponent = component.flattenToShortString();
            preferredWindowingMode = targetWindowingMode;
            activityState = new TaskActivityModeState(
                    component.getPackageName(), targetWindowingMode);
        }

        void observeTop(
                final String component,
                final String packageName,
                final int observedWindowingMode) {
            topComponent = component;
            topPackage = packageName;
            windowingMode = observedWindowingMode;
            topObserved = true;
        }
    }

    private static final class ObservedTask {
        final TaskRecord record;
        final FrameworkTaskSnapshot observation;

        ObservedTask(
                final TaskRecord taskRecord,
                final FrameworkTaskSnapshot
                        taskObservation) {
            record = taskRecord;
            observation = taskObservation;
        }

        boolean changedFor(final PendingStart candidate) {
            final String component = observation.topComponent == null
                    ? null : observation.topComponent.flattenToShortString();
            final String packageName = observation.topComponent == null
                    ? null : observation.topComponent.getPackageName();
            if (!record.topObserved) {
                return candidate.component != null
                        ? !candidate.component.equals(record.rootComponent)
                        : !candidate.packageName.equals(
                                record.activityState.rootPackage());
            }
            return !java.util.Objects.equals(component, record.topComponent)
                    || !java.util.Objects.equals(packageName, record.topPackage)
                    || (record.windowingMode == WINDOWING_MODE_FREEFORM
                            && observation.windowingMode
                                    != WINDOWING_MODE_FREEFORM);
        }
    }

    private static final class PendingStart {
        final String component;
        final String packageName;
        final long createdUptimeMillis;

        PendingStart(
                final String expectedComponent,
                final String expectedPackage,
                final long createdAt) {
            component = expectedComponent;
            packageName = expectedPackage;
            createdUptimeMillis = createdAt;
        }

        boolean matches(final ObservedTask observed) {
            final ComponentName top = observed.observation.topComponent;
            if (component != null) {
                return top != null && component.equals(top.flattenToShortString());
            }
            return top != null && packageName.equals(top.getPackageName());
        }

        String label() {
            return component == null ? packageName : component;
        }
    }

    private static final class Correction {
        final TaskRecord record;
        final String activityName;
        final TaskActivityModeState.Decision decision;

        Correction(
                final TaskRecord taskRecord,
                final String correctedActivityName,
                final TaskActivityModeState.Decision correctedDecision) {
            record = taskRecord;
            activityName = correctedActivityName;
            decision = correctedDecision;
        }
    }

    private static String modeLabel(final int windowingMode) {
        return windowingMode == WINDOWING_MODE_FULLSCREEN
                ? "fullscreen" : "freeform";
    }
}
