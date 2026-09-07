package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PointerEdgeRevealStateTest {
    @Test
    public void dwellingInsideEdgeRevealsTaskbar() {
        final PointerEdgeRevealState state = armedState();

        assertEquals(
                PointerEdgeRevealState.TimerAction.START_REVEAL,
                state.onPointerEntered());
        assertTrue(state.onRevealTimeout());
        assertTrue(state.isRevealed());
        assertFalse(state.onRevealTimeout());
    }

    @Test
    public void leavingEdgeCancelsPendingReveal() {
        final PointerEdgeRevealState state = armedState();

        state.onPointerEntered();
        assertEquals(
                PointerEdgeRevealState.TimerAction.CANCEL_REVEAL,
                state.onPointerExited());
        assertFalse(state.onRevealTimeout());
    }

    @Test
    public void revealedTaskbarHidesOnlyAfterPointerLeaves() {
        final PointerEdgeRevealState state = armedState();
        state.onPointerEntered();
        state.onRevealTimeout();

        assertEquals(
                PointerEdgeRevealState.TimerAction.START_HIDE,
                state.onPointerExited());
        assertEquals(
                PointerEdgeRevealState.TimerAction.CANCEL_HIDE,
                state.onPointerEntered());
        assertFalse(state.onHideTimeout());

        state.onPointerExited();
        assertTrue(state.onHideTimeout());
        assertFalse(state.isRevealed());
    }

    @Test
    public void disarmingClearsTemporaryState() {
        final PointerEdgeRevealState state = armedState();
        state.onPointerEntered();
        state.onRevealTimeout();

        state.setArmed(false);

        assertFalse(state.isRevealed());
        assertEquals(
                PointerEdgeRevealState.TimerAction.NONE,
                state.onPointerEntered());
    }

    @Test
    public void policyHideKeepsTaskbarUnderExistingPointer() {
        final PointerEdgeRevealState state = new PointerEdgeRevealState();
        assertEquals(
                PointerEdgeRevealState.TimerAction.NONE,
                state.onPointerEntered());

        state.setArmed(true);

        assertTrue(state.isRevealed());
        assertEquals(
                PointerEdgeRevealState.TimerAction.START_HIDE,
                state.onPointerExited());
        assertTrue(state.onHideTimeout());
        assertFalse(state.isRevealed());
    }

    private static PointerEdgeRevealState armedState() {
        final PointerEdgeRevealState state = new PointerEdgeRevealState();
        state.setArmed(true);
        return state;
    }
}
