package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Evaluates event-driven task snapshots without depending on Android APIs. */
final class SelfTestTaskStackInvariantAnalyzer {
    static final int WINDOWING_MODE_FULLSCREEN = 1;
    static final int WINDOWING_MODE_FREEFORM = 5;
    static final int DISPLAY_AREA_FEATURE_UNKNOWN = Integer.MIN_VALUE;

    private static final int MAX_STAGE_SAMPLES = 128;
    private static final int MAX_ANOMALIES = 32;
    private final int mDisplayId;
    private final int mHostTaskId;
    private final long mStartedAt;
    private final List<String> mAnomalies = new ArrayList<>();
    private final Set<String> mAnomalyKeys = new LinkedHashSet<>();
    private Stage mStage;
    private int mStageCount;
    private int mSampleCount;
    private int mEventCount;
    private int mDroppedSamples;
    private final Map<Integer, Integer> mFullscreenParents =
            new LinkedHashMap<>();
    private String mPendingVisibilityGapKey;
    private String mPendingVisibilityGapDetail;

    SelfTestTaskStackInvariantAnalyzer(
            final int displayId,
            final int hostTaskId,
            final long startedAt) {
        mDisplayId = displayId;
        mHostTaskId = hostTaskId;
        mStartedAt = startedAt;
    }

    void begin(final String stage, final Snapshot snapshot) {
        if (mStage != null) {
            throw new IllegalStateException("task-stack guard already started");
        }
        mStage = new Stage(cleanStage(stage));
        mStageCount++;
        addSample("stage-start", snapshot, false);
    }

    void changeStage(final String stage, final Snapshot snapshot) {
        if (mStage == null) {
            begin(stage, snapshot);
            return;
        }
        addSample("stage-end", snapshot, false);
        analyzeStage(mStage);
        mStage = new Stage(cleanStage(stage));
        mStageCount++;
        addSample("stage-start", snapshot, false);
    }

    void sample(
            final String reason,
            final Snapshot snapshot,
            final boolean event) {
        if (mStage == null) {
            begin("WINDOW-STACK", snapshot);
            return;
        }
        addSample(reason, snapshot, event);
    }

    SelfTestTaskStackReport finish(final Snapshot snapshot) {
        if (mStage != null) {
            addSample("finish", snapshot, false);
            analyzeStage(mStage);
            mStage = null;
        }
        if (mDroppedSamples > 0) {
            addAnomaly("trace-overflow", "task-stack trace dropped "
                    + mDroppedSamples + " samples");
        }
        return new SelfTestTaskStackReport(
                true,
                mStageCount,
                mSampleCount,
                mEventCount,
                mDroppedSamples,
                mAnomalies.toArray(new String[0]),
                "");
    }

    private void addSample(
            final String reason,
            final Snapshot snapshot,
            final boolean event) {
        if (snapshot == null) {
            addAnomaly("missing-snapshot", stagePrefix()
                    + " task snapshot is unavailable");
            return;
        }
        mSampleCount++;
        if (event) {
            mEventCount++;
        }
        validateFullscreenParentContinuity(reason, snapshot);
        validateFullscreenTaskArea(reason, snapshot);
        observeFullscreenParents(snapshot);
        validateUniversal(reason, snapshot);
        validateVisibilityContinuity(reason, snapshot, event);
        if (!mStage.samples.isEmpty()
                && mStage.samples.get(mStage.samples.size() - 1)
                        .snapshot.sameState(snapshot)) {
            return;
        }
        if (mStage.samples.size() >= MAX_STAGE_SAMPLES) {
            mDroppedSamples++;
            return;
        }
        mStage.samples.add(new Sample(reason, snapshot));
    }

    private void validateUniversal(
            final String reason,
            final Snapshot snapshot) {
        final TaskState host = snapshot.find(mHostTaskId);
        if (host == null) {
            addAnomaly("host-missing:" + mStage.name,
                    formatSample(reason, snapshot)
                            + " desktop host is missing");
        } else if (host.displayId != mDisplayId
                || host.windowingMode != WINDOWING_MODE_FULLSCREEN) {
            addAnomaly("host-state:" + mStage.name + ':' + host.stateKey(),
                    formatSample(reason, snapshot)
                            + " desktop host=" + host.stateKey());
        }

        for (final TaskState task : snapshot.tasks) {
            if (task.fixture && task.displayId != mDisplayId
                    && task.windowingMode != WINDOWING_MODE_FULLSCREEN) {
                addAnomaly("fixture-display:" + mStage.name + ':'
                                + task.taskId + ':' + task.displayId,
                        formatSample(reason, snapshot)
                                + " fixture " + task.taskId
                                + " left selected display " + mDisplayId
                                + " for display " + task.displayId
                                + " in mode " + task.windowingMode);
            }
            if (task.taskId != mHostTaskId && !task.backstop
                    && !task.infrastructure
                    && task.home && task.displayId == mDisplayId
                    && task.visibilityKnown && task.visible) {
                if (!isHomeAboveDesktopContent(snapshot, task.taskId)) {
                    continue;
                }
                addAnomaly("visible-home:" + mStage.name + ':' + task.taskId,
                        formatSample(reason, snapshot)
                                + " Home task " + task.taskId
                                + " became visible");
            }
        }
    }

    private void validateVisibilityContinuity(
            final String reason,
            final Snapshot snapshot,
            final boolean event) {
        if (!snapshot.visibilityKnown) {
            return;
        }
        if (hasVisibleDesktopTask(snapshot)) {
            mPendingVisibilityGapKey = null;
            mPendingVisibilityGapDetail = null;
            return;
        }
        if (mPendingVisibilityGapKey == null) {
            final String key = "visibility-gap:" + mStage.name;
            final String detail = formatSample(reason, snapshot)
                    + " no task is visible on the desktop display";
            if (!event || !"task-front".equals(reason)) {
                addAnomaly(key, detail);
                return;
            }
            mPendingVisibilityGapKey = key;
            mPendingVisibilityGapDetail = detail;
        }
        // The remote onTaskMovedToFront callback can precede its matching
        // visibility update. Android coalesces onTaskStackChanged after that
        // event burst, so it is the first committed boundary at which this
        // specific gap is a real task-stack defect. Deferral can begin only
        // at task-front; a gap first seen in any other callback is immediately
        // invalid. Stage boundaries remain strict, without a delay or timeout.
        if (!event || "stack-changed".equals(reason)) {
            addAnomaly(
                    mPendingVisibilityGapKey,
                    mPendingVisibilityGapDetail);
        }
    }

    private boolean hasVisibleDesktopTask(final Snapshot snapshot) {
        for (final TaskState task : snapshot.tasks) {
            if (task.displayId == mDisplayId
                    && !task.backstop
                    && !task.infrastructure
                    && task.visibilityKnown
                    && task.visible) {
                return true;
            }
        }
        return false;
    }

    private boolean isHomeAboveDesktopContent(
            final Snapshot snapshot,
            final int homeTaskId) {
        // ActivityTaskManager returns running tasks top-first. Nubia keeps its
        // phone HOME task marked visible below the local desktop, so visibility
        // alone is not an error; crossing the desktop content in Z-order is.
        for (final TaskState task : snapshot.tasks) {
            if (task.displayId != mDisplayId
                    || !task.visibilityKnown
                    || !task.visible) {
                continue;
            }
            if (task.taskId == homeTaskId) {
                return true;
            }
            if (task.infrastructure) {
                continue;
            }
            if (task.taskId == mHostTaskId || task.fixture || task.backstop) {
                return false;
            }
        }
        return false;
    }

    private void analyzeStage(final Stage stage) {
        if (stage.samples.isEmpty()) {
            return;
        }
        final Snapshot last = stage.samples.get(
                stage.samples.size() - 1).snapshot;
        analyzeFullscreenHierarchyContract(stage, last);
        if (stage.samples.size() < 2) {
            return;
        }
        final Snapshot first = stage.samples.get(0).snapshot;
        final Set<Integer> fixtureIds = new LinkedHashSet<>();
        collectFixtureIds(first, fixtureIds);
        collectFixtureIds(last, fixtureIds);
        for (final Sample sample : stage.samples) {
            collectFixtureIds(sample.snapshot, fixtureIds);
        }
        for (final Integer taskId : fixtureIds) {
            analyzeFixture(stage, taskId.intValue());
        }
        analyzeVisibility(stage, first, last);
    }

    private void analyzeFullscreenHierarchyContract(
            final Stage stage,
            final Snapshot last) {
        if ("WINDOW-015".equals(stage.name)) {
            analyzeFullscreenHierarchy(stage, last);
            return;
        }
        if (!isFullscreenHierarchyStage(stage.name)) {
            return;
        }
        analyzeFullscreenHierarchy(stage, last);
        if (isStableFullscreenSwitchStage(stage.name)) {
            analyzeFullscreenParentStability(stage);
        }
    }

    private void analyzeFullscreenHierarchy(
            final Stage stage,
            final Snapshot snapshot) {
        final List<TaskState> fullscreenTasks =
                desktopFullscreenFixtures(snapshot);
        final int minimumTaskCount = "WINDOW-015".equals(stage.name) ? 1 : 2;
        if (fullscreenTasks.size() < minimumTaskCount) {
            addAnomaly("fullscreen-plane-count:" + stage.name,
                    formatSample("stage-end", snapshot)
                            + " expected at least " + minimumTaskCount
                            + " fullscreen tasks, found="
                            + fullscreenTasks.size());
            return;
        }

        final int sessionFeatureId = sessionParentFeatureId(snapshot);
        final Set<Integer> planeFeatureIds = new LinkedHashSet<>();
        for (final TaskState task : fullscreenTasks) {
            if (task.displayAreaFeatureId == DISPLAY_AREA_FEATURE_UNKNOWN) {
                addAnomaly("fullscreen-parent-unknown:" + stage.name + ':'
                                + task.taskId,
                        formatSample("stage-end", snapshot)
                                + " fullscreen task=" + task.taskId
                                + " has no display-area feature id");
                continue;
            }
            if (sessionFeatureId != DISPLAY_AREA_FEATURE_UNKNOWN) {
                if (task.displayAreaFeatureId != sessionFeatureId) {
                    addAnomaly("fullscreen-session-parent:" + stage.name
                                    + ':' + task.taskId,
                            formatSample("stage-end", snapshot)
                                    + " fullscreen task=" + task.taskId
                                    + " left session area=" + sessionFeatureId
                                    + " for area="
                                    + task.displayAreaFeatureId);
                }
                continue;
            }
            if (!planeFeatureIds.add(task.displayAreaFeatureId)) {
                addAnomaly("fullscreen-plane-shared:" + stage.name + ':'
                                + task.displayAreaFeatureId,
                        formatSample("stage-end", snapshot)
                                + " fullscreen tasks share plane="
                                + task.displayAreaFeatureId);
                continue;
            }
            final int anchorCount = countBackstops(
                    snapshot, task.displayAreaFeatureId);
            if (anchorCount != 1) {
                addAnomaly("fullscreen-slot-anchor:" + stage.name + ':'
                                + task.displayAreaFeatureId,
                        formatSample("stage-end", snapshot)
                                + " fullscreen plane="
                                + task.displayAreaFeatureId
                                + " expected exactly one anchor, found="
                                + anchorCount);
            }
        }
    }

    private void analyzeFullscreenParentStability(final Stage stage) {
        if (stage.samples.isEmpty()) {
            return;
        }
        final Snapshot first = stage.samples.get(0).snapshot;
        for (final TaskState expected : desktopFullscreenFixtures(first)) {
            for (final Sample sample : stage.samples) {
                final TaskState current = sample.snapshot.find(expected.taskId);
                if (current != null
                        && current.displayAreaFeatureId
                                != expected.displayAreaFeatureId) {
                    addAnomaly("fullscreen-parent-transition:"
                                    + stage.name + ':' + expected.taskId,
                            formatSample(sample.reason, sample.snapshot)
                                    + " task=" + expected.taskId
                                    + " parent changed "
                                    + expected.displayAreaFeatureId + " -> "
                                    + current.displayAreaFeatureId);
                    break;
                }
            }
        }
    }

    private List<TaskState> desktopFullscreenFixtures(
            final Snapshot snapshot) {
        final List<TaskState> tasks = new ArrayList<>();
        for (final TaskState task : snapshot.tasks) {
            if (isDesktopFullscreenFixture(task)) {
                tasks.add(task);
            }
        }
        return tasks;
    }

    private static boolean isFullscreenHierarchyStage(final String stage) {
        return "WINDOW-020-PREPARE".equals(stage)
                || "WINDOW-020".equals(stage)
                || stage.startsWith("WINDOW-020-PEER-")
                || "WINDOW-020-RETURN".equals(stage);
    }

    private static boolean isStableFullscreenSwitchStage(
            final String stage) {
        return stage.startsWith("WINDOW-020-PEER-")
                || "WINDOW-020-RETURN".equals(stage);
    }

    private void observeFullscreenParents(final Snapshot snapshot) {
        final Set<Integer> currentFullscreenTasks = new LinkedHashSet<>();
        for (final TaskState task : snapshot.tasks) {
            if (!isDesktopFullscreenFixture(task)) {
                continue;
            }
            currentFullscreenTasks.add(task.taskId);
            if (task.displayAreaFeatureId != DISPLAY_AREA_FEATURE_UNKNOWN) {
                mFullscreenParents.putIfAbsent(
                        task.taskId, task.displayAreaFeatureId);
            }
        }
        mFullscreenParents.keySet().retainAll(currentFullscreenTasks);
    }

    private boolean hasSessionBackstop(
            final Snapshot snapshot,
            final int featureId) {
        if (featureId == DISPLAY_AREA_FEATURE_UNKNOWN) {
            return false;
        }
        for (final TaskState task : snapshot.tasks) {
            if (task.backstop
                    && task.displayId == mDisplayId
                    && task.displayAreaFeatureId == featureId
                    && (task.backstopRole
                            == TaskAreaBackstopRole.SESSION
                            || task.backstopRole
                                    == TaskAreaBackstopRole.FULLSCREEN
                            || task.backstopRole
                                    == TaskAreaBackstopRole.UNKNOWN)) {
                return true;
            }
        }
        return false;
    }

    private void validateFullscreenTaskArea(
            final String reason,
            final Snapshot snapshot) {
        final TaskState host = snapshot.find(mHostTaskId);
        final int hostFeatureId = host == null
                ? DISPLAY_AREA_FEATURE_UNKNOWN
                : host.displayAreaFeatureId;
        int sessionBackstopCount = 0;
        final Map<Integer, Integer> slotAnchorCounts =
                new LinkedHashMap<>();
        for (final TaskState task : snapshot.tasks) {
            if (!task.backstop || task.displayId != mDisplayId) {
                continue;
            }
            if (task.backstopRole == TaskAreaBackstopRole.HOST) {
                if (hostFeatureId == DISPLAY_AREA_FEATURE_UNKNOWN
                        || task.displayAreaFeatureId != hostFeatureId) {
                    addAnomaly("host-backstop-parent:" + mStage.name + ':'
                                    + task.taskId,
                            formatSample(reason, snapshot)
                                    + " host anchor=" + task.taskId
                                    + " is outside desktop host area="
                                    + hostFeatureId);
                }
                sessionBackstopCount++;
                continue;
            }
            if (hostFeatureId != DISPLAY_AREA_FEATURE_UNKNOWN
                    && task.displayAreaFeatureId == hostFeatureId) {
                sessionBackstopCount++;
                continue;
            }
            if (task.displayAreaFeatureId
                    == DISPLAY_AREA_FEATURE_UNKNOWN) {
                addAnomaly("fullscreen-slot-parent:" + mStage.name + ':'
                                + task.taskId,
                        formatSample(reason, snapshot)
                                + " fullscreen slot anchor=" + task.taskId
                                + " has no display-area feature id");
                continue;
            }
            final Integer count = slotAnchorCounts.get(
                    Integer.valueOf(task.displayAreaFeatureId));
            slotAnchorCounts.put(
                    Integer.valueOf(task.displayAreaFeatureId),
                    Integer.valueOf(count == null ? 1 : count + 1));
        }
        if (sessionBackstopCount > 1) {
            addAnomaly("fullscreen-backstop-count:" + mStage.name + ':'
                            + sessionBackstopCount,
                    formatSample(reason, snapshot)
                            + " expected at most one session backstop,"
                            + " found=" + sessionBackstopCount);
        }
        for (final Map.Entry<Integer, Integer> entry
                : slotAnchorCounts.entrySet()) {
            final int featureId = entry.getKey().intValue();
            final int anchorCount = entry.getValue().intValue();
            if (anchorCount != 1) {
                addAnomaly("fullscreen-slot-anchor-count:" + mStage.name
                                + ':' + featureId,
                        formatSample(reason, snapshot)
                                + " fullscreen slot=" + featureId
                                + " expected exactly one anchor, found="
                                + anchorCount);
            }
            int fullscreenFixtureCount = 0;
            for (final TaskState child : snapshot.tasks) {
                if (!child.fixture
                        || child.displayId != mDisplayId
                        || child.displayAreaFeatureId != featureId) {
                    continue;
                }
                if (child.windowingMode != WINDOWING_MODE_FULLSCREEN) {
                    addAnomaly("fullscreen-slot-windowing:" + mStage.name
                                    + ':' + featureId + ':' + child.taskId,
                            formatSample(reason, snapshot)
                                    + " fullscreen slot=" + featureId
                                    + " contains non-fullscreen fixture="
                                    + child.taskId + " mode="
                                    + child.windowingMode);
                } else {
                    fullscreenFixtureCount++;
                }
            }
            if (fullscreenFixtureCount > 1) {
                addAnomaly("fullscreen-slot-app-count:" + mStage.name + ':'
                                + featureId,
                        formatSample(reason, snapshot)
                                + " fullscreen slot=" + featureId
                                + " contains " + fullscreenFixtureCount
                                + " fullscreen fixtures");
            }
        }
    }

    private int countBackstops(
            final Snapshot snapshot,
            final int featureId) {
        if (featureId == DISPLAY_AREA_FEATURE_UNKNOWN) {
            return 0;
        }
        int count = 0;
        for (final TaskState task : snapshot.tasks) {
            if (task.backstop
                    && task.displayId == mDisplayId
                    && task.displayAreaFeatureId == featureId) {
                count++;
            }
        }
        return count;
    }

    private void validateFullscreenParentContinuity(
            final String reason,
            final Snapshot snapshot) {
        for (final TaskState task : snapshot.tasks) {
            if (!isDesktopFullscreenFixture(task)
                    || task.displayAreaFeatureId
                            == DISPLAY_AREA_FEATURE_UNKNOWN) {
                continue;
            }
            final Integer expected = mFullscreenParents.get(task.taskId);
            if (expected != null
                    && expected.intValue() != task.displayAreaFeatureId) {
                addAnomaly("fullscreen-parent-changed:" + task.taskId,
                        formatSample(reason, snapshot)
                                + " fullscreen task=" + task.taskId
                                + " parent changed " + expected + " -> "
                                + task.displayAreaFeatureId);
            }
        }
    }

    private int sessionParentFeatureId(final Snapshot snapshot) {
        final TaskState host = snapshot.find(mHostTaskId);
        if (host == null
                || host.displayAreaFeatureId
                        == DISPLAY_AREA_FEATURE_UNKNOWN
                || !hasSessionBackstop(
                        snapshot, host.displayAreaFeatureId)) {
            return DISPLAY_AREA_FEATURE_UNKNOWN;
        }
        return host.displayAreaFeatureId;
    }

    private boolean isDesktopFullscreenFixture(final TaskState task) {
        return task.fixture
                && task.displayId == mDisplayId
                && task.windowingMode == WINDOWING_MODE_FULLSCREEN;
    }

    private void analyzeFixture(final Stage stage, final int taskId) {
        final TaskState firstState =
                stage.samples.get(0).snapshot.find(taskId);
        final TaskState lastState = stage.samples.get(
                stage.samples.size() - 1).snapshot.find(taskId);
        final String first = stateKey(firstState);
        final String last = stateKey(lastState);
        boolean reachedFinal = first.equals(last);
        for (final Sample sample : stage.samples) {
            final TaskState currentState = sample.snapshot.find(taskId);
            final String current = stateKey(currentState);
            if (becameHiddenBeforeRemoval(
                    firstState, lastState, currentState)
                    && !hasVisibleFullscreenFixture(sample.snapshot)) {
                addAnomaly(
                        "task-hidden-before-removal:"
                                + stage.name + ':' + taskId,
                        formatSample(sample.reason, sample.snapshot)
                                + " task=" + taskId
                                + " became hidden before removal");
                return;
            }
            if (first.equals(last)) {
                if (!first.equals(current)) {
                    addTaskTransitionAnomaly(
                            stage, sample, taskId, first, last, current);
                    return;
                }
                continue;
            }
            if (first.equals(current) && !reachedFinal) {
                continue;
            }
            if (last.equals(current)) {
                reachedFinal = true;
                continue;
            }
            if (isHiddenPreparation(firstState, lastState, currentState)) {
                continue;
            }
            addTaskTransitionAnomaly(
                    stage, sample, taskId, first, last, current);
            return;
        }
    }

    private static boolean becameHiddenBeforeRemoval(
            final TaskState first,
            final TaskState last,
            final TaskState current) {
        return first != null
                && last == null
                && current != null
                && first.visibilityKnown
                && first.visible
                && current.visibilityKnown
                && !current.visible;
    }

    private void addTaskTransitionAnomaly(
            final Stage stage,
            final Sample sample,
            final int taskId,
            final String first,
            final String last,
            final String current) {
        addAnomaly("task-transition:" + stage.name + ':' + taskId,
                formatSample(sample.reason, sample.snapshot)
                        + " task=" + taskId
                        + " expected=" + first + " -> " + last
                        + " observed=" + current);
    }

    private void analyzeVisibility(
            final Stage stage,
            final Snapshot first,
            final Snapshot last) {
        if (!allVisibilityKnown(stage)) {
            return;
        }
        // Fullscreen peers can occupy both endpoints while the active task is
        // intentionally restored to another mode between them.
        final boolean stableFullscreen = hasVisibleFullscreenFixture(first)
                && hasVisibleFullscreenFixture(last)
                && !hasEndpointFixtureModeTransition(first, last);
        for (final Sample sample : stage.samples) {
            if (stableFullscreen
                    && !hasVisibleFullscreenFixture(sample.snapshot)) {
                addAnomaly("fullscreen-visibility:" + stage.name,
                        formatSample(sample.reason, sample.snapshot)
                                + " no fullscreen fixture is visible"
                                + " during fullscreen switching");
                return;
            }
        }
    }

    private boolean hasEndpointFixtureModeTransition(
            final Snapshot first,
            final Snapshot last) {
        for (final TaskState firstState : first.tasks) {
            if (!firstState.fixture) {
                continue;
            }
            final TaskState lastState = last.find(firstState.taskId);
            if (lastState != null
                    && lastState.fixture
                    && (firstState.displayId != lastState.displayId
                            || firstState.windowingMode
                                    != lastState.windowingMode)) {
                return true;
            }
        }
        return false;
    }

    private boolean allVisibilityKnown(final Stage stage) {
        for (final Sample sample : stage.samples) {
            if (!sample.snapshot.visibilityKnown) {
                return false;
            }
        }
        return true;
    }

    private boolean hasVisibleFullscreenFixture(final Snapshot snapshot) {
        for (final TaskState task : snapshot.tasks) {
            if (task.fixture
                    && task.displayId == mDisplayId
                    && task.windowingMode == WINDOWING_MODE_FULLSCREEN
                    && task.visibilityKnown
                    && task.visible) {
                return true;
            }
        }
        return false;
    }

    private static void collectFixtureIds(
            final Snapshot snapshot,
            final Set<Integer> destination) {
        for (final TaskState task : snapshot.tasks) {
            if (task.fixture) {
                destination.add(Integer.valueOf(task.taskId));
            }
        }
    }

    private static String stateKey(final TaskState state) {
        return state == null ? "absent" : state.stateKey();
    }

    private static boolean isHiddenPreparation(
            final TaskState first,
            final TaskState last,
            final TaskState current) {
        if (last == null
                || current == null
                || !current.visibilityKnown
                || current.visible) {
            return false;
        }
        if (first == null) {
            // Android may create a task in its parent's default mode before
            // applying the requested launch mode. It is safe only while the
            // task remains hidden on its final display.
            return current.displayId == last.displayId;
        }
        if (first.displayId == last.displayId
                && current.displayId == last.displayId
                && current.windowingMode == first.windowingMode) {
            // An application may reach the final mode before WMShell has
            // restored its decoration. Re-entering the source mode is safe
            // while hidden and gives the following transition a real mode
            // boundary without exposing that intermediate state.
            return true;
        }
        return first.displayId != last.displayId
                && ((current.displayId == first.displayId
                            && current.windowingMode == last.windowingMode)
                        || (current.displayId == last.displayId
                            && current.windowingMode
                                    == first.windowingMode));
    }

    private String formatSample(
            final String reason,
            final Snapshot snapshot) {
        return "+" + Math.max(0L, snapshot.uptimeMillis - mStartedAt)
                + "ms " + stagePrefix() + '/' + cleanReason(reason)
                + " " + snapshot.summary(mHostTaskId);
    }

    private String stagePrefix() {
        return mStage == null ? "WINDOW-STACK" : mStage.name;
    }

    private void addAnomaly(final String key, final String detail) {
        if (!mAnomalyKeys.add(key) || mAnomalies.size() >= MAX_ANOMALIES) {
            return;
        }
        mAnomalies.add(detail);
    }

    private static String cleanStage(final String stage) {
        return stage == null || stage.isEmpty() ? "WINDOW-STACK" : stage;
    }

    private static String cleanReason(final String reason) {
        return reason == null || reason.isEmpty() ? "event" : reason;
    }

    static final class Snapshot {
        final long uptimeMillis;
        final List<TaskState> tasks;
        final boolean visibilityKnown;

        Snapshot(
                final long uptimeMillis,
                final List<TaskState> tasks,
                final boolean visibilityKnown) {
            this.uptimeMillis = uptimeMillis;
            this.tasks = Collections.unmodifiableList(
                    new ArrayList<>(tasks));
            this.visibilityKnown = visibilityKnown;
        }

        TaskState find(final int taskId) {
            for (final TaskState task : tasks) {
                if (task.taskId == taskId) {
                    return task;
                }
            }
            return null;
        }

        boolean sameState(final Snapshot other) {
            if (other == null
                    || visibilityKnown != other.visibilityKnown
                    || tasks.size() != other.tasks.size()) {
                return false;
            }
            for (final TaskState task : tasks) {
                final TaskState candidate = other.find(task.taskId);
                if (candidate == null || !task.sameState(candidate)) {
                    return false;
                }
            }
            return true;
        }

        String summary(final int hostTaskId) {
            final StringBuilder output = new StringBuilder();
            for (final TaskState task : tasks) {
                if (task.taskId != hostTaskId && !task.fixture && !task.home
                        && !task.backstop && !task.infrastructure) {
                    continue;
                }
                if (output.length() > 0) {
                    output.append(',');
                }
                if (task.taskId == hostTaskId) {
                    output.append("host");
                } else if (task.backstop) {
                    output.append("backstop");
                } else if (task.infrastructure) {
                    output.append("infrastructure");
                } else {
                    output.append(task.taskId);
                }
                output.append('=')
                        .append(task.stateKey())
                        .append(task.visibilityKnown
                                ? task.visible ? "/visible" : "/hidden"
                                : "/visibility-unknown");
            }
            return output.length() == 0 ? "tasks=none" : output.toString();
        }
    }

    static final class TaskState {
        final int taskId;
        final int displayId;
        final int windowingMode;
        final boolean visible;
        final boolean visibilityKnown;
        final boolean fixture;
        final boolean home;
        final int displayAreaFeatureId;
        final boolean backstop;
        final TaskAreaBackstopRole backstopRole;
        final boolean infrastructure;

        TaskState(
                final int taskId,
                final int displayId,
                final int windowingMode,
                final boolean visible,
                final boolean visibilityKnown,
                final boolean fixture,
                final boolean home,
                final int displayAreaFeatureId) {
            this(taskId, displayId, windowingMode, visible, visibilityKnown,
                    fixture, home, displayAreaFeatureId, false);
        }

        TaskState(
                final int taskId,
                final int displayId,
                final int windowingMode,
                final boolean visible,
                final boolean visibilityKnown,
                final boolean fixture,
                final boolean home,
                final int displayAreaFeatureId,
                final boolean backstop) {
            this(taskId, displayId, windowingMode, visible, visibilityKnown,
                    fixture, home, displayAreaFeatureId, backstop,
                    backstop ? TaskAreaBackstopRole.UNKNOWN
                            : TaskAreaBackstopRole.NONE);
        }

        TaskState(
                final int taskId,
                final int displayId,
                final int windowingMode,
                final boolean visible,
                final boolean visibilityKnown,
                final boolean fixture,
                final boolean home,
                final int displayAreaFeatureId,
                final boolean backstop,
                final TaskAreaBackstopRole backstopRole) {
            this(taskId, displayId, windowingMode, visible, visibilityKnown,
                    fixture, home, displayAreaFeatureId, backstop,
                    backstopRole, false);
        }

        TaskState(
                final int taskId,
                final int displayId,
                final int windowingMode,
                final boolean visible,
                final boolean visibilityKnown,
                final boolean fixture,
                final boolean home,
                final int displayAreaFeatureId,
                final boolean backstop,
                final TaskAreaBackstopRole backstopRole,
                final boolean infrastructure) {
            this.taskId = taskId;
            this.displayId = displayId;
            this.windowingMode = windowingMode;
            this.visible = visible;
            this.visibilityKnown = visibilityKnown;
            this.fixture = fixture;
            this.home = home;
            this.displayAreaFeatureId = displayAreaFeatureId;
            this.backstop = backstop;
            this.backstopRole = backstopRole;
            this.infrastructure = infrastructure;
        }

        String stateKey() {
            return "display" + displayId + "/mode" + windowingMode;
        }

        boolean sameState(final TaskState other) {
            return other != null
                    && taskId == other.taskId
                    && displayId == other.displayId
                    && windowingMode == other.windowingMode
                    && visible == other.visible
                    && visibilityKnown == other.visibilityKnown
                    && fixture == other.fixture
                    && home == other.home
                    && displayAreaFeatureId == other.displayAreaFeatureId
                    && backstop == other.backstop
                    && backstopRole == other.backstopRole
                    && infrastructure == other.infrastructure;
        }
    }

    private static final class Stage {
        final String name;
        final List<Sample> samples = new ArrayList<>();

        Stage(final String name) {
            this.name = name;
        }
    }

    private static final class Sample {
        final String reason;
        final Snapshot snapshot;

        Sample(final String reason, final Snapshot snapshot) {
            this.reason = reason;
            this.snapshot = snapshot;
        }
    }
}
