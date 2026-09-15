package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.assertThrows;
import static io.github.mekhontsev.magicdesk.DisplayPresentationGraph.Edge;

public final class DisplayPresentationGraphTest {
    @Test public void independentAndChainedViewersAreAllowed() {
        DisplayPresentationGraph.requireAcyclic(List.of(new Edge(0, 2), new Edge(4, 3)), List.of());
        DisplayPresentationGraph.requireAcyclic(List.of(new Edge(0, 4), new Edge(4, 3)), List.of());
    }
    @Test(expected = IllegalArgumentException.class) public void directFeedbackIsRejected() {
        DisplayPresentationGraph.requireAcyclic(List.of(new Edge(2, 2)), List.of());
    }
    @Test(expected = IllegalArgumentException.class) public void indirectFeedbackIsRejected() {
        DisplayPresentationGraph.requireAcyclic(List.of(new Edge(2, 3), new Edge(3, 4), new Edge(4, 2)), List.of());
    }
    @Test public void SwappingIndependentSourcesDoesNotChangeTheirIdentities() {
        DisplayPresentationGraph.requireAcyclic(List.of(new Edge(0, 2), new Edge(4, 3)), List.of());
        DisplayPresentationGraph.requireAcyclic(List.of(new Edge(0, 3), new Edge(4, 2)), List.of());
    }
    @Test(expected = IllegalArgumentException.class) public void phoneMirrorIntoItsOverlayIsRejected() {
        DisplayPresentationGraph.requireAcyclic(List.of(new Edge(2, 0)), List.of(2));
    }
    @Test(expected = IllegalArgumentException.class) public void chainedPhonePreviewFeedbackIsRejected() {
        DisplayPresentationGraph.requireAcyclic(List.of(new Edge(2, 3), new Edge(3, 0)), List.of(2));
    }
    @Test public void independentPhoneMirrorAndOverlaySourceAreAllowed() {
        DisplayPresentationGraph.requireAcyclic(List.of(new Edge(3, 0), new Edge(4, 2)), List.of(2));
    }

    @Test public void severalMirrorsCanShareBothEndpointsWithoutHidingFeedback() {
        DisplayPresentationGraph.requireAcyclic(List.of(new Edge(0, 2), new Edge(0, 2),
                new Edge(0, 3), new Edge(4, 2)), List.of());
        assertThrows(IllegalArgumentException.class, () -> DisplayPresentationGraph.requireAcyclic(
                List.of(new Edge(0, 2), new Edge(0, 3), new Edge(2, 0)), List.of()));
        assertThrows(IllegalArgumentException.class, () -> DisplayPresentationGraph.requireAcyclic(
                List.of(new Edge(2, 3), new Edge(2, 4), new Edge(3, 0)), List.of(2)));
    }
}
