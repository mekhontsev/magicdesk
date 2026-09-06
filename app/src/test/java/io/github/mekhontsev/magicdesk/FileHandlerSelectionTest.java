package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public final class FileHandlerSelectionTest {
    @Test
    public void preferredHandlerWinsWithoutShowingChooser() {
        final var first = target("First");
        final var preferred = target("Preferred");
        final var selection = new FileHandlerRepository.Selection(
                List.of(first, preferred), preferred);

        assertSame(preferred, selection.directTarget(false));
        assertNull(selection.directTarget(true));
    }

    @Test
    public void soleHandlerLaunchesDirectlyUnlessUserRequestedOpenWith() {
        final var only = target("Only");
        final var selection = new FileHandlerRepository.Selection(List.of(only), null);

        assertSame(only, selection.directTarget(false));
        assertNull(selection.directTarget(true));
    }

    @Test
    public void ambiguousHandlersRequireChoice() {
        final var selection = new FileHandlerRepository.Selection(
                List.of(target("One"), target("Two")), null);

        assertNull(selection.directTarget(false));
    }

    @Test
    public void emptySelectionHasNoLaunchTarget() {
        final var selection = new FileHandlerRepository.Selection(List.of(), null);

        assertNull(selection.directTarget(false));
        assertNull(selection.directTarget(true));
    }

    @Test
    public void publishedSelectionDoesNotChangeWithDiscoveryList() {
        final var only = target("Only");
        final var targets = new ArrayList<>(List.of(only));
        final var selection = new FileHandlerRepository.Selection(targets, null);
        targets.add(target("Later"));

        assertEquals(1, selection.targets.size());
        assertSame(only, selection.directTarget(false));
        assertThrows(UnsupportedOperationException.class, selection.targets::clear);
    }

    private static FileHandlerRepository.Target target(final String name) {
        final var shortcut = new DesktopApplicationShortcut(name, "", "viewer %f",
                null, "", DesktopLaunchMode.AUTO, false, DesktopExecBackend.SHELL, false);
        final var entry = new DesktopApplicationRepository.Entry(
                shortcut, "/desktop/" + name + ".desktop", null);
        return new FileHandlerRepository.Target(null, entry, name, "Shell", null, 0);
    }
}
