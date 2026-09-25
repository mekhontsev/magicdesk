package io.github.mekhontsev.magicdesk;

import io.github.mekhontsev.magicdesk.x11.X11Session;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class X11LaunchTrackerTest {
    @Test public void parentlessDialogCannotConsumeAnApplicationLaunch() {
        var tracker = new X11LaunchTracker();
        tracker.begin("launch", "a", "gimp");
        tracker.completed("launch");
        var dialog = new X11Session.Window(1, "About", true, null, X11Session.WindowRole.DIALOG,
                null, "gimp", "Gimp", io.github.mekhontsev.magicdesk.hosted.HostedWindowLayout.NONE);
        tracker.update("other", "a", List.of(dialog));
        assertFalse(tracker.reserved("other", 1));
        assertTrue(tracker.takeMatches().isEmpty());
        tracker.update("other", "a", List.of(dialog, window(2, "gimp")));
        assertEquals(2, tracker.takeMatches().get(0).window().id());
    }

    @Test public void initialToolkitClassDoesNotRaceAutomaticPresentation() {
        var tracker = new X11LaunchTracker();
        tracker.begin("calc", "a", "libreoffice-calc");
        tracker.update("writer", "a", List.of(window(1, "Soffice")));
        assertTrue(tracker.reserved("writer", 1));
        tracker.completed("calc");
        assertTrue(tracker.takeMatches().isEmpty());
        assertTrue(tracker.reserved("writer", 1));
        tracker.update("writer", "a", List.of(window(1, "libreoffice-calc")));
        assertEquals(1, tracker.takeMatches().size());
    }

    @Test public void alreadyPresentedWindowCannotAcquireASecondHost() {
        var tracker = new X11LaunchTracker();
        tracker.begin("launch", "a", "calc");
        tracker.update("other", "a", List.of(window(1, "")));
        tracker.presented("other", 1);
        tracker.update("other", "a", List.of(window(1, "calc")));
        tracker.completed("launch");
        assertTrue(tracker.takeMatches().isEmpty());
    }

    private static X11Session.Window window(long id, String cls) {
        return new X11Session.Window(id, "Document", true, null, X11Session.WindowRole.APPLICATION, null, "suite", cls, io.github.mekhontsev.magicdesk.hosted.HostedWindowLayout.NONE);
    }

    @Test public void forwardedWindowWaitsForSuccessfulCommandAndIsAssignedOnce() {
        var tracker = new X11LaunchTracker();
        tracker.update("writer", "termux:100", List.of(window(1, "writer")));
        tracker.begin("calc", "termux:100", "calc");
        tracker.update("writer", "termux:100", List.of(window(1, "writer"), window(2, "calc")));
        assertTrue(tracker.reserved("writer", 2));
        assertTrue(tracker.takeMatches().isEmpty());
        tracker.completed("calc");
        assertEquals(List.of(new X11LaunchTracker.Match("calc", new X11LaunchTracker.Window("writer", 2))), tracker.takeMatches());
        assertFalse(tracker.reserved("writer", 2));
        assertTrue(tracker.takeMatches().isEmpty());
        tracker.begin("again", "termux:100", "calc");
        tracker.completed("again");
        assertTrue(tracker.takeMatches().isEmpty());
    }

    @Test public void existingWindowsAndOtherExecutorsAreNotLaunchEvidence() {
        var tracker = new X11LaunchTracker();
        tracker.update("old", "a", List.of(window(1, "calc")));
        tracker.begin("new", "a", "calc");
        tracker.update("foreign", "b", List.of(window(2, "calc")));
        tracker.completed("new");
        assertTrue(tracker.takeMatches().isEmpty());
        assertFalse(tracker.reserved("old", 1));
        assertFalse(tracker.reserved("foreign", 2));
    }

    @Test public void ambiguityDoesNotHijackAnotherLaunch() {
        var tracker = new X11LaunchTracker();
        tracker.begin("one", "a", "calc");
        tracker.begin("two", "a", "calc");
        tracker.completed("one");
        tracker.update("other", "a", List.of(window(1, "calc")));
        assertTrue(tracker.takeMatches().isEmpty());
        tracker.completed("two");
        assertTrue(tracker.takeMatches().isEmpty());
        tracker.remove("two");
        assertTrue(tracker.takeMatches().isEmpty());
        tracker.begin("third", "a", "calc");
        tracker.completed("third");
        tracker.update("other", "a", List.of(window(1, "calc"), window(2, "calc"), window(3, "calc")));
        assertTrue(tracker.takeMatches().isEmpty());
        tracker.remove("one");
        assertFalse(tracker.reserved("other", 1));
    }

    @Test public void nativeWindowAndCancellationReleaseForeignCandidates() {
        var tracker = new X11LaunchTracker();
        tracker.begin("launch", "a", "calc");
        tracker.update("other", "a", List.of(window(1, "calc")));
        tracker.update("launch", "a", List.of(window(1, "calc")));
        tracker.completed("launch");
        assertTrue(tracker.takeMatches().isEmpty());
        assertFalse(tracker.reserved("other", 1));
        tracker.begin("cancel", "a", "calc");
        tracker.update("other", "a", List.of(window(1, "calc"), window(2, "calc")));
        tracker.remove("cancel");
        assertFalse(tracker.reserved("other", 2));
    }

    @Test public void classificationMayArriveLaterButCannotMakeAnOldWindowNew() {
        var tracker = new X11LaunchTracker();
        tracker.update("old", "a", List.of(window(1, "")));
        tracker.begin("launch", "a", "calc");
        tracker.completed("launch");
        tracker.update("old", "a", List.of(window(1, "calc")));
        assertTrue(tracker.takeMatches().isEmpty());
        var provisional = new X11Session.Window(2, "", true, null, X11Session.WindowRole.SPLASH, null, "", "calc", io.github.mekhontsev.magicdesk.hosted.HostedWindowLayout.NONE);
        tracker.update("old", "a", List.of(window(1, "calc"), provisional));
        assertTrue(tracker.takeMatches().isEmpty());
        tracker.update("old", "a", List.of(window(1, "calc"), window(2, "calc")));
        assertEquals(2, tracker.takeMatches().get(0).window().id());
    }
}
