package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import android.graphics.Rect;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class PhoneTouchpadReconcilerTest {
    private static final String TOUCHPAD =
            "io.github.mekhontsev.magicdesk/"
                    + "io.github.mekhontsev.magicdesk.MagicDeskTouchpadActivity";

    @Test
    public void existingHiddenTouchpadIsRaisedOnceUntilStateChanges() {
        final PhoneTouchpadReconciler reconciler =
                new PhoneTouchpadReconciler();
        final List<TaskRepository.TaskEntry> hidden = Arrays.asList(
                task(10, "io.github.mekhontsev.magicdesk/.PhoneHomeActivity",
                        true),
                task(11, TOUCHPAD, false));

        assertEquals(PhoneTouchpadReconciler.RepairAction.BRING_EXISTING,
                reconciler.nextRepair(true, hidden));
        assertEquals(PhoneTouchpadReconciler.RepairAction.NONE,
                reconciler.nextRepair(true, hidden));
    }

    @Test
    public void observedVisibilityCompletesRepairAndAllowsLaterRepair() {
        final PhoneTouchpadReconciler reconciler =
                new PhoneTouchpadReconciler();
        final List<TaskRepository.TaskEntry> hidden = Arrays.asList(
                task(10, "io.github.mekhontsev.magicdesk/.PhoneHomeActivity",
                        true),
                task(11, TOUCHPAD, false));
        final List<TaskRepository.TaskEntry> visible = Arrays.asList(
                task(11, TOUCHPAD, true),
                task(10, "io.github.mekhontsev.magicdesk/.PhoneHomeActivity",
                        false));

        assertEquals(PhoneTouchpadReconciler.RepairAction.BRING_EXISTING,
                reconciler.nextRepair(true, hidden));
        assertEquals(PhoneTouchpadReconciler.RepairAction.NONE,
                reconciler.nextRepair(true, visible));
        assertEquals(PhoneTouchpadReconciler.RepairAction.BRING_EXISTING,
                reconciler.nextRepair(true, hidden));
    }

    @Test
    public void phoneApplicationSuspendsUnconfirmedRepairUntilHomeReturns() {
        final PhoneTouchpadReconciler reconciler =
                new PhoneTouchpadReconciler();
        final List<TaskRepository.TaskEntry> first = Arrays.asList(
                task(10, "io.github.mekhontsev.magicdesk/.PhoneHomeActivity",
                        true),
                task(11, TOUCHPAD, false));
        final List<TaskRepository.TaskEntry> changed = Arrays.asList(
                task(12, "com.example/.MainActivity", true),
                task(11, TOUCHPAD, false));

        assertEquals(PhoneTouchpadReconciler.RepairAction.BRING_EXISTING,
                reconciler.nextRepair(true, first));
        assertEquals(PhoneTouchpadReconciler.RepairAction.NONE,
                reconciler.nextRepair(true, changed));
        assertEquals(PhoneTouchpadReconciler.RepairAction.NONE,
                reconciler.nextRepair(true, changed));
        assertEquals(PhoneTouchpadReconciler.RepairAction.BRING_EXISTING,
                reconciler.nextRepair(true, first));
    }

    @Test
    public void phoneGuardAndOverviewAreOrdinaryPhoneDestinations() {
        for (final String component : Arrays.asList(
                "io.github.mekhontsev.magicdesk/.DesktopSelfTestPhoneGuardActivity",
                "io.github.mekhontsev.magicdesk/.PhoneOverviewActivity")) {
            final PhoneTouchpadReconciler reconciler = new PhoneTouchpadReconciler();
            assertEquals(PhoneTouchpadReconciler.RepairAction.NONE,
                    reconciler.nextRepair(true, Collections.singletonList(
                            task(12, component, true))));
            assertEquals(PhoneTouchpadReconciler.RepairAction.START_MISSING,
                    reconciler.nextRepair(true, Collections.singletonList(
                            task(12, component, false))));
        }
    }

    @Test
    public void visibleShortTouchpadComponentCompletesPendingRepair() {
        final PhoneTouchpadReconciler reconciler = new PhoneTouchpadReconciler();
        assertEquals(PhoneTouchpadReconciler.RepairAction.START_MISSING,
                reconciler.nextRepair(true, Collections.emptyList()));
        assertEquals(PhoneTouchpadReconciler.RepairAction.NONE,
                reconciler.nextRepair(true, Collections.singletonList(
                        task(11, "io.github.mekhontsev.magicdesk/.MagicDeskTouchpadActivity",
                                true))));
        assertEquals(PhoneTouchpadReconciler.RepairAction.START_MISSING,
                reconciler.nextRepair(true, Collections.emptyList()));
    }

    @Test
    public void missingRequestedTouchpadStartsOnce() {
        final PhoneTouchpadReconciler reconciler =
                new PhoneTouchpadReconciler();
        final List<TaskRepository.TaskEntry> tasks = Collections.singletonList(
                task(10, "io.github.mekhontsev.magicdesk/.PhoneHomeActivity",
                        true));

        assertEquals(PhoneTouchpadReconciler.RepairAction.START_MISSING,
                reconciler.nextRepair(true, tasks));
        assertEquals(PhoneTouchpadReconciler.RepairAction.NONE,
                reconciler.nextRepair(true, tasks));
    }

    @Test
    public void controlPanelCoversTouchpadWithoutCancellingItsRequest() {
        final PhoneTouchpadReconciler reconciler = new PhoneTouchpadReconciler();
        final List<TaskRepository.TaskEntry> panel = Arrays.asList(
                task(12, "io.github.mekhontsev.magicdesk/.ControlActivity", true),
                task(11, TOUCHPAD, false));
        assertEquals(PhoneTouchpadReconciler.RepairAction.NONE,
                reconciler.nextRepair(true, panel));
        assertEquals(PhoneTouchpadReconciler.RepairAction.NONE,
                reconciler.nextRepair(true, panel));

        final List<TaskRepository.TaskEntry> home = Arrays.asList(
                task(10, "io.github.mekhontsev.magicdesk/.PhoneHomeActivity", true),
                task(11, TOUCHPAD, false));
        assertEquals(PhoneTouchpadReconciler.RepairAction.BRING_EXISTING,
                reconciler.nextRepair(true, home));
    }

    @Test
    public void visibleControlPanelPreventsStartingMissingTouchpad() {
        final PhoneTouchpadReconciler reconciler = new PhoneTouchpadReconciler();
        assertEquals(PhoneTouchpadReconciler.RepairAction.NONE,
                reconciler.nextRepair(true, Collections.singletonList(
                        task(12, "io.github.mekhontsev.magicdesk/"
                                + "io.github.mekhontsev.magicdesk.ControlActivity",
                                true))));
    }

    @Test
    public void hiddenControlPanelDoesNotSuppressTouchpadRecovery() {
        final PhoneTouchpadReconciler reconciler = new PhoneTouchpadReconciler();
        assertEquals(PhoneTouchpadReconciler.RepairAction.BRING_EXISTING,
                reconciler.nextRepair(true, Arrays.asList(
                        task(10, "io.github.mekhontsev.magicdesk/.PhoneHomeActivity",
                                true),
                        task(12, "io.github.mekhontsev.magicdesk/.ControlActivity",
                                false),
                        task(11, TOUCHPAD, false))));
    }

    @Test
    public void inactiveRequestCancelsPendingRepair() {
        final PhoneTouchpadReconciler reconciler =
                new PhoneTouchpadReconciler();
        final List<TaskRepository.TaskEntry> tasks = Arrays.asList(
                task(10, "io.github.mekhontsev.magicdesk/.PhoneHomeActivity",
                        true),
                task(11, TOUCHPAD, false));

        assertEquals(PhoneTouchpadReconciler.RepairAction.BRING_EXISTING,
                reconciler.nextRepair(true, tasks));
        assertEquals(PhoneTouchpadReconciler.RepairAction.NONE,
                reconciler.nextRepair(false, tasks));
        assertEquals(PhoneTouchpadReconciler.RepairAction.BRING_EXISTING,
                reconciler.nextRepair(true, tasks));
    }

    private static TaskRepository.TaskEntry task(
            final int taskId,
            final String component,
            final boolean visible) {
        final int separator = component.indexOf('/');
        return new TaskRepository.TaskEntry(
                taskId,
                taskId,
                0,
                component.substring(0, separator),
                component,
                component,
                "fullscreen",
                new Rect(0, 0, 100, 100),
                component.endsWith("PhoneHomeActivity"),
                visible,
                visible);
    }
}
