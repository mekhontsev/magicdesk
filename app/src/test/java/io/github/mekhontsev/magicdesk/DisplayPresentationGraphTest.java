package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import java.util.Map;

public final class DisplayPresentationGraphTest {
    @Test public void independentAndChainedViewersAreAllowed() {
        DisplayPresentationGraph.requireAcyclic(Map.of(0, 2, 4, 3));
        DisplayPresentationGraph.requireAcyclic(Map.of(0, 4, 4, 3));
    }
    @Test(expected = IllegalArgumentException.class) public void directFeedbackIsRejected() {
        DisplayPresentationGraph.requireAcyclic(Map.of(2, 2));
    }
    @Test(expected = IllegalArgumentException.class) public void indirectFeedbackIsRejected() {
        DisplayPresentationGraph.requireAcyclic(Map.of(2, 3, 3, 4, 4, 2));
    }
    @Test public void SwappingIndependentSourcesDoesNotChangeTheirIdentities() {
        DisplayPresentationGraph.requireAcyclic(Map.of(0, 2, 4, 3));
        DisplayPresentationGraph.requireAcyclic(Map.of(0, 3, 4, 2));
    }
    @Test(expected = IllegalArgumentException.class) public void phoneMirrorIntoItsOverlayIsRejected() {
        DisplayPresentationGraph.requireAcyclic(Map.of(2, 0), java.util.List.of(2));
    }
    @Test(expected = IllegalArgumentException.class) public void chainedPhonePreviewFeedbackIsRejected() {
        DisplayPresentationGraph.requireAcyclic(Map.of(2, 3, 3, 0), java.util.List.of(2));
    }
    @Test public void independentPhoneMirrorAndOverlaySourceAreAllowed() {
        DisplayPresentationGraph.requireAcyclic(Map.of(3, 0, 4, 2), java.util.List.of(2));
    }
}
