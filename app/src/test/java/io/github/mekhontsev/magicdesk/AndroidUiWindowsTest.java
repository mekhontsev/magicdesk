package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import java.util.List;
import org.json.JSONObject;
import org.junit.Test;

public class AndroidUiWindowsTest {
    @Test public void taskIncludesItsDialogsNotOtherInstancesOrSystemWindows() throws Exception {
        final var main = window(0, 1, 42);
        final var dialog = window(0, 2, 42);
        final var inventory = inventory(main, window(0, 3, 43), dialog, window(0, 4, -1));
        final var result = inventory.select(task(42), -1);
        assertEquals(List.of(main, dialog), result.windows());
        assertEquals(0, result.displayId());
        assertTrue(result.available());
        assertTrue(result.complete());
    }

    @Test public void newObservationFollowsTaskButOldHandlesRejectMovement() throws Exception {
        final var original = window(0, 1, 42);
        final var moved = window(8, 1, 42);
        assertEquals(0, inventory(original).select(task(42), -1).displayId());
        final var next = inventory(moved);
        assertEquals(8, next.select(task(42), -1).displayId());
        assertThrows(IllegalArgumentException.class, () -> next.require(original.identity(), task(42)));
        next.require(moved.identity(), task(42));
    }

    @Test public void missingTargetNeverProvesAbsence() throws Exception {
        final var inventory = inventory(window(0, 1, 42));
        for (var result : List.of(inventory.select(task(99), -1), inventory.select(display(0), 999),
                inventory.select(display(3), -1))) {
            assertFalse(result.available());
            assertFalse(result.complete());
            assertFalse(result.unavailableReason().isEmpty());
            assertFalse(AndroidUiSelector.satisfied(false, 0, result.complete(), true));
        }
    }

    @Test public void narrowedWindowMustBelongToSelectedTask() throws Exception {
        final var main = window(0, 1, 42);
        final var other = window(0, 2, 43);
        final var inventory = inventory(main, other);
        assertEquals(List.of(main), inventory.select(task(42), 1).windows());
        assertFalse(inventory.select(task(42), 2).available());
        assertThrows(IllegalArgumentException.class, () -> inventory.require(other.identity(), task(42)));
        final var narrowed = AndroidUiScope.parse(new JSONObject().put("displayId", 0).put("windowId", 1));
        assertThrows(IllegalArgumentException.class, () -> inventory.require(other.identity(), narrowed));
    }

    @Test public void unavailableTaskMappingDoesNotDisableDisplayInspectionOrInventOwners() throws Exception {
        final var unknown = window(0, 1, null);
        final var inventory = inventory(unknown, window(0, 2, -1));
        assertEquals(2, inventory.select(display(0), -1).windows().size());
        assertTrue(inventory.select(display(0), -1).complete());
        final var result = inventory.select(task(42), -1);
        assertFalse(result.available());
        assertEquals("task_window_mapping_unavailable", result.unavailableReason());
        inventory.require(unknown.identity(), display(0));
    }

    @Test public void partialOwnershipCannotProveAbsence() throws Exception {
        final var result = inventory(window(0, 1, 42), window(0, 2, null)).select(task(42), -1);
        assertTrue(result.available());
        assertFalse(result.complete());
        assertEquals(1, result.windows().size());
    }

    @Test public void conflictingDisplaysAreNotAUsableTaskObservation() throws Exception {
        final var result = inventory(window(0, 1, 42), window(8, 2, 42)).select(task(42), -1);
        assertFalse(result.available());
        assertFalse(result.complete());
        assertEquals(-1, result.displayId());
        assertEquals("task_windows_span_displays", result.unavailableReason());
    }

    @Test public void changedMissingAndUnverifiableOwnersRejectActions() throws Exception {
        final var original = window(0, 1, 42);
        for (var next : List.of(inventory(window(0, 1, 43)), inventory(window(0, 1, null)),
                inventory(), new AndroidUiWindows(false, List.of(original)))) {
            assertThrows(RuntimeException.class, () -> next.require(original.identity(), display(0)));
        }
    }

    private static AndroidUiWindows.Window window(int display, int window, Integer task) {
        return new AndroidUiWindows.Window(null, new AndroidUiWindows.Identity(display, window, task));
    }
    private static AndroidUiWindows inventory(AndroidUiWindows.Window... windows) {
        return new AndroidUiWindows(true, List.of(windows));
    }
    private static AndroidUiScope task(int id) throws Exception {
        return AndroidUiScope.parse(new JSONObject().put("taskId", id));
    }
    private static AndroidUiScope display(int id) throws Exception {
        return AndroidUiScope.parse(new JSONObject().put("displayId", id));
    }
}
