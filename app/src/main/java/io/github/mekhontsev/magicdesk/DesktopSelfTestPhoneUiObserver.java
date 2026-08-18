package io.github.mekhontsev.magicdesk;

import static io.github.mekhontsev.magicdesk.DesktopSelfTestTasks.hasClass;

import android.os.SystemClock;
import android.view.Display;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Observes phone-side tasks while a desktop self-test owns another display. */
final class DesktopSelfTestPhoneUiObserver {
    private static final int MAX_EVENTS = 24;
    private static final String TOUCHPAD_CLASS =
            "io.github.mekhontsev.magicdesk.MagicDeskTouchpadActivity";

    private static boolean sActive;
    private static long sStartedAt;
    private static int sDisplayId = Display.INVALID_DISPLAY;
    private static boolean sTouchpadExpected;
    private static boolean sTouchpadRequested;
    private static boolean sTouchpadSeen;
    private static boolean sTouchpadStopped;
    private static boolean sTouchpadMissingAfterSeen;
    private static boolean sLastTaskTouchpadVisible;
    private static int sAllowedPhoneFixtureTaskId = -1;
    private static boolean sFixtureExposed;
    private static final PhoneTaskModeGuard PHONE_TASK_MODES =
            new PhoneTaskModeGuard();
    private static final List<String> EVENTS = new ArrayList<>();

    private DesktopSelfTestPhoneUiObserver() {
    }

    static synchronized void begin(final int displayId) {
        cancel();
        sActive = true;
        sStartedAt = SystemClock.uptimeMillis();
        sDisplayId = displayId;
        sTouchpadExpected = PhoneTouchpadController.isSupported(displayId);
        sTouchpadRequested = PhoneTouchpadController
                .shouldRemainVisible(displayId);
        sTouchpadSeen = PhoneTouchpadController.isVisible();
        addEvent("begin expected=" + sTouchpadExpected
                + " requested=" + sTouchpadRequested
                + " visible=" + sTouchpadSeen);
    }

    static synchronized void refreshTouchpadExpectation(
            final int displayId) {
        if (!isTarget(displayId)) {
            return;
        }
        sTouchpadExpected = PhoneTouchpadController.isSupported(displayId);
        sTouchpadRequested |= PhoneTouchpadController
                .shouldRemainVisible(displayId);
        sTouchpadSeen |= PhoneTouchpadController.isVisible();
    }

    static synchronized void allowPhoneFixtureTask(final int taskId) {
        if (sActive && taskId >= 0) {
            sAllowedPhoneFixtureTaskId = taskId;
        }
    }

    static void sampleCurrentTasks() throws IOException {
        final List<TaskStackParser.Entry> tasks = TaskStackParser.parse(
                ShellAccess.run("/system/bin/cmd activity stack list"));
        synchronized (DesktopSelfTestPhoneUiObserver.class) {
            if (!sActive) {
                return;
            }
            boolean touchpadVisible = false;
            boolean fixtureVisible = false;
            for (final TaskStackParser.Entry task : tasks) {
                if (task.displayId != Display.DEFAULT_DISPLAY) {
                    continue;
                }
                final boolean fixture =
                        DesktopSelfTestComponents.isFixtureComponent(
                                task.componentName)
                                || DesktopSelfTestComponents
                                        .isFixtureComponent(
                                                task.topActivityName);
                observePhoneTaskMode(
                        task.taskId, fixture, task.windowingMode);
                if (!task.visible) {
                    continue;
                }
                final boolean touchpad =
                        hasClass(task.componentName, TOUCHPAD_CLASS)
                                || hasClass(task.topActivityName, TOUCHPAD_CLASS);
                touchpadVisible |= touchpad;
                fixtureVisible |= fixture
                        && task.taskId != sAllowedPhoneFixtureTaskId;
            }
            PHONE_TASK_MODES.completeBaseline();
            observeTaskState(touchpadVisible, fixtureVisible);
        }
    }

    static synchronized void observePhoneTasks(
            final List<TaskRepository.TaskEntry> phoneTasks) {
        if (!sActive || phoneTasks == null) {
            return;
        }
        boolean touchpadVisible = false;
        boolean fixtureVisible = false;
        for (final TaskRepository.TaskEntry task : phoneTasks) {
            if (task == null) {
                continue;
            }
            final boolean fixture =
                    DesktopSelfTestComponents.isFixtureComponent(
                            task.componentName);
            observePhoneTaskMode(
                    task.taskId, fixture, task.windowingMode);
            if (!task.visible) {
                continue;
            }
            final boolean touchpad =
                    hasClass(task.componentName, TOUCHPAD_CLASS);
            touchpadVisible |= touchpad;
            fixtureVisible |= fixture
                    && task.taskId != sAllowedPhoneFixtureTaskId;
        }
        observeTaskState(touchpadVisible, fixtureVisible);
    }

    static synchronized void noteTouchpadStarted(final int displayId) {
        if (!isTarget(displayId)) {
            return;
        }
        sTouchpadRequested |= PhoneTouchpadController
                .shouldRemainVisible(displayId);
        sTouchpadSeen = true;
        addEvent("touchpad started");
    }

    static synchronized void noteTouchpadStopped(final int displayId) {
        if (!isTarget(displayId)) {
            return;
        }
        if (PhoneTouchpadController.shouldRemainVisible(displayId)) {
            sTouchpadStopped = true;
            addEvent("touchpad stopped while requested");
        }
    }

    static synchronized Observation finish(final int displayId) {
        if (!isTarget(displayId)) {
            cancel();
            return Observation.notObserved();
        }
        sTouchpadRequested |= PhoneTouchpadController
                .shouldRemainVisible(displayId);
        final boolean visibleAtFinish = PhoneTouchpadController.isVisible();
        final Observation observation = new Observation(
                true,
                sTouchpadExpected,
                sTouchpadRequested,
                sTouchpadSeen,
                visibleAtFinish,
                sTouchpadStopped,
                sTouchpadMissingAfterSeen,
                sFixtureExposed,
                PHONE_TASK_MODES.violated(),
                String.join("; ", EVENTS));
        cancel();
        return observation;
    }

    static synchronized void cancel() {
        sActive = false;
        sStartedAt = 0L;
        sDisplayId = Display.INVALID_DISPLAY;
        sTouchpadExpected = false;
        sTouchpadRequested = false;
        sTouchpadSeen = false;
        sTouchpadStopped = false;
        sTouchpadMissingAfterSeen = false;
        sLastTaskTouchpadVisible = false;
        sAllowedPhoneFixtureTaskId = -1;
        sFixtureExposed = false;
        PHONE_TASK_MODES.reset();
        EVENTS.clear();
    }

    private static synchronized void observeTaskState(
            final boolean touchpadVisible,
            final boolean fixtureVisible) {
        if (!sActive) {
            return;
        }
        sTouchpadRequested |= PhoneTouchpadController
                .shouldRemainVisible(sDisplayId);
        if (touchpadVisible) {
            sTouchpadSeen = true;
        } else if (sLastTaskTouchpadVisible
                && PhoneTouchpadController.shouldRemainVisible(sDisplayId)) {
            sTouchpadMissingAfterSeen = true;
            addEvent("touchpad task became invisible while requested");
        }
        if (touchpadVisible != sLastTaskTouchpadVisible) {
            addEvent("touchpad task visible=" + touchpadVisible);
        }
        sLastTaskTouchpadVisible = touchpadVisible;
        if (fixtureVisible && !touchpadVisible) {
            sFixtureExposed = true;
            addEvent("fixture exposed on phone");
        }
    }

    private static synchronized void observePhoneTaskMode(
            final int taskId,
            final boolean fixture,
            final String windowingMode) {
        if (!sActive || fixture || taskId < 0 || windowingMode == null) {
            return;
        }
        final String violation = PHONE_TASK_MODES.observe(
                taskId, windowingMode);
        if (violation != null) {
            addEvent(violation);
        }
    }

    private static boolean isTarget(final int displayId) {
        return sActive && displayId == sDisplayId;
    }

    private static void addEvent(final String event) {
        if (EVENTS.size() >= MAX_EVENTS) {
            return;
        }
        EVENTS.add("+" + (SystemClock.uptimeMillis() - sStartedAt)
                + "ms " + event);
    }

    /** Enforces that desktop work cannot put unrelated phone tasks in windows. */
    static final class PhoneTaskModeGuard {
        private final Map<Integer, String> mModes = new HashMap<>();
        private boolean mBaselineComplete;
        private boolean mViolated;

        String observe(final int taskId, final String windowingMode) {
            final Integer key = Integer.valueOf(taskId);
            final String previous = mModes.put(key, windowingMode);
            if (previous != null && !previous.equals(windowingMode)) {
                mViolated = true;
                return "phone task " + taskId + " mode "
                        + previous + " -> " + windowingMode;
            }
            if (previous == null && mBaselineComplete
                    && "freeform".equals(windowingMode)) {
                mViolated = true;
                return "new phone task " + taskId
                        + " appeared in freeform";
            }
            return null;
        }

        void completeBaseline() {
            mBaselineComplete = true;
        }

        boolean violated() {
            return mViolated;
        }

        void reset() {
            mModes.clear();
            mBaselineComplete = false;
            mViolated = false;
        }
    }

    static final class Observation {
        final boolean observed;
        final boolean touchpadExpected;
        final boolean touchpadRequested;
        final boolean touchpadSeen;
        final boolean touchpadVisibleAtFinish;
        final boolean touchpadStopped;
        final boolean touchpadMissingAfterSeen;
        final boolean fixtureExposed;
        final boolean phoneTaskModeChanged;
        final String detail;

        Observation(
                final boolean observed,
                final boolean touchpadExpected,
                final boolean touchpadRequested,
                final boolean touchpadSeen,
                final boolean touchpadVisibleAtFinish,
                final boolean touchpadStopped,
                final boolean touchpadMissingAfterSeen,
                final boolean fixtureExposed,
                final boolean phoneTaskModeChanged,
                final String detail) {
            this.observed = observed;
            this.touchpadExpected = touchpadExpected;
            this.touchpadRequested = touchpadRequested;
            this.touchpadSeen = touchpadSeen;
            this.touchpadVisibleAtFinish = touchpadVisibleAtFinish;
            this.touchpadStopped = touchpadStopped;
            this.touchpadMissingAfterSeen = touchpadMissingAfterSeen;
            this.fixtureExposed = fixtureExposed;
            this.phoneTaskModeChanged = phoneTaskModeChanged;
            this.detail = detail;
        }

        boolean touchpadStable() {
            return touchpadExpected
                    && touchpadRequested
                    && touchpadSeen
                    && touchpadVisibleAtFinish;
        }

        static Observation notObserved() {
            return new Observation(
                    false, false, false, false, false,
                    false, false, false, false, "not observed");
        }
    }
}
