package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class TouchEdgeRevealStateTest {
    private static final int TOUCH_SLOP = 12;

    @Test
    public void navigationRevealIsIdempotentAndDismissedByOutsideTouch() {
        final TouchEdgeRevealState state = armedState();
        assertEquals(TouchEdgeRevealState.Action.REVEAL, state.reveal());
        assertEquals(TouchEdgeRevealState.Action.NONE, state.reveal());
        assertTrue(state.isRevealed());
        assertEquals(TouchEdgeRevealState.Action.DISMISS, state.onOutside());
        assertFalse(state.isRevealed());
    }

    @Test
    public void navigationRevealSurvivesTemporaryPolicyVisibility() {
        final TouchEdgeRevealState state = new TouchEdgeRevealState();
        assertEquals(TouchEdgeRevealState.Action.REVEAL, state.reveal());
        state.setArmed(true);
        state.setArmed(false);
        state.setArmed(true);
        assertTrue(state.isRevealed());
        assertEquals(TouchEdgeRevealState.Action.NONE, state.reveal());
        assertEquals(TouchEdgeRevealState.Action.DISMISS, state.onOutside());
    }

    @Test
    public void taskbarActionDismissesNavigationReveal() {
        final TouchEdgeRevealState state = armedState();
        state.reveal();
        state.onDown(50, 50);
        assertEquals(TouchEdgeRevealState.Action.DISMISS, state.onUp());
        assertFalse(state.isRevealed());
    }

    @Test
    public void upwardSwipeRevealsAndReleaseKeepsTaskbarVisible() {
        final TouchEdgeRevealState state = armedState();

        state.onDown(50, 100);
        assertEquals(
                TouchEdgeRevealState.Action.REVEAL,
                state.onMove(52, 80, TOUCH_SLOP));
        assertEquals(TouchEdgeRevealState.Action.NONE, state.onUp());
        assertTrue(state.isRevealed());
    }

    @Test
    public void shortOrHorizontalMovementDoesNotReveal() {
        final TouchEdgeRevealState state = armedState();

        state.onDown(50, 100);
        assertEquals(
                TouchEdgeRevealState.Action.NONE,
                state.onMove(50, 90, TOUCH_SLOP));
        assertEquals(
                TouchEdgeRevealState.Action.NONE,
                state.onMove(80, 78, TOUCH_SLOP));
        assertFalse(state.isRevealed());
    }

    @Test
    public void nextTaskbarInteractionDismissesRevealedTaskbar() {
        final TouchEdgeRevealState state = revealedState();

        state.onDown(50, 50);
        assertEquals(TouchEdgeRevealState.Action.DISMISS, state.onUp());
        assertFalse(state.isRevealed());
    }

    @Test
    public void outsideTouchDismissesRevealedTaskbar() {
        final TouchEdgeRevealState state = revealedState();

        assertEquals(
                TouchEdgeRevealState.Action.DISMISS,
                state.onOutside());
        assertFalse(state.isRevealed());
    }

    @Test
    public void disarmingStopsGesturesButExplicitRevealStillNeedsDismissal() {
        final TouchEdgeRevealState state = revealedState();

        state.setArmed(false);

        assertTrue(state.isRevealed());
        state.onDown(50, 100);
        assertEquals(
                TouchEdgeRevealState.Action.NONE,
                state.onMove(50, 70, TOUCH_SLOP));
        assertEquals(TouchEdgeRevealState.Action.DISMISS, state.onUp());
        assertFalse(state.isRevealed());
    }

    private static TouchEdgeRevealState armedState() {
        final TouchEdgeRevealState state = new TouchEdgeRevealState();
        state.setArmed(true);
        return state;
    }

    private static TouchEdgeRevealState revealedState() {
        final TouchEdgeRevealState state = armedState();
        state.onDown(50, 100);
        state.onMove(50, 70, TOUCH_SLOP);
        state.onUp();
        return state;
    }
}
