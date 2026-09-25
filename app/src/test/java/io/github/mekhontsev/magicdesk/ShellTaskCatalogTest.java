package io.github.mekhontsev.magicdesk;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ShellTaskCatalogTest {
    @Test public void observationAvailabilityPublishesWithoutInventingTaskChanges() {
        var catalog = new ShellTaskCatalog((task, action) -> fail("No command expected"));
        int[] changes = {0};
        catalog.listen(() -> changes[0]++);
        catalog.update(List.of(), true);
        assertTrue(catalog.available());
        catalog.update(List.of(), false);
        assertFalse(catalog.available());
        catalog.update(List.of(), false);
        assertEquals(2, changes[0]);
        catalog.update(List.of(), true);
        assertEquals(3, changes[0]);
        assertTrue(catalog.available());
    }

    private static ShellTaskCatalog.Task task(int id, String identity, boolean active) {
        return new ShellTaskCatalog.Task(id, identity, "title", "application", active, false, false, false);
    }

    @Test public void staleHandlesCannotAddressReusedTasksOrUnavailableObservations() {
        var actions = new ArrayList<ShellTaskCatalog.Task>();
        var catalog = new ShellTaskCatalog((task, action) -> actions.add(task));
        catalog.update(List.of(task(7, "a", false)), true);
        long first = catalog.snapshot().get(0).id();
        assertTrue(catalog.request(first, ShellTaskCatalog.Action.ACTIVATE));
        catalog.update(List.of(), false);
        assertFalse(catalog.request(first, ShellTaskCatalog.Action.CLOSE));
        assertEquals(1, catalog.snapshot().size());
        catalog.update(List.of(task(7, "b", false)), true);
        assertFalse(catalog.request(first, ShellTaskCatalog.Action.CLOSE));
        long replacement = catalog.snapshot().get(0).id();
        assertNotEquals(first, replacement);
        catalog.update(List.of(), true);
        catalog.update(List.of(task(7, "b", false)), true);
        assertNotEquals(replacement, catalog.snapshot().get(0).id());
        assertEquals(1, actions.size());
    }

    @Test public void metadataPublishesOnlyChangesAndCloseRevokesEveryAction() {
        var catalog = new ShellTaskCatalog((task, action) -> fail("Closed catalog acted"));
        int[] changes = {0};
        catalog.listen(() -> changes[0]++);
        var task = task(2, "a", false);
        catalog.update(List.of(task), true);
        long id = catalog.snapshot().get(0).id();
        catalog.update(List.of(task), true);
        assertEquals(1, changes[0]);
        catalog.update(List.of(task(2, "a", true)), true);
        assertEquals(id, catalog.snapshot().get(0).id());
        catalog.close(); catalog.close();
        assertEquals(3, changes[0]);
        assertTrue(catalog.snapshot().isEmpty());
        assertFalse(catalog.request(id, ShellTaskCatalog.Action.CLOSE));
    }
}
