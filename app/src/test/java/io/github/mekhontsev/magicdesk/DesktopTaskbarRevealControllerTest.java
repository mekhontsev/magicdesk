package io.github.mekhontsev.magicdesk;

import static io.github.mekhontsev.magicdesk.DesktopTaskbarRevealController.Presentation.EDGE;
import static io.github.mekhontsev.magicdesk.DesktopTaskbarRevealController.Presentation.UNAVAILABLE;
import static io.github.mekhontsev.magicdesk.DesktopTaskbarRevealController.Presentation.VISIBLE;
import static io.github.mekhontsev.magicdesk.DesktopTaskbarRevealController.resolvePresentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DesktopTaskbarRevealControllerTest {
    @Test
    public void navigationOnlyRevealsLiveHiddenPhoneChromeOnce() throws Exception {
        RuntimeSourceFixture.verify("""
                enum Presentation { UNAVAILABLE, EDGE, VISIBLE }
                boolean mStarted, mReleased, mTouchEdgeEnabled, mAvailable,
                        mPolicyVisible, mAutoHide, mForcedVisible;
                static class RevealState {
                    boolean revealed; int requests;
                    boolean isRevealed() { return revealed; }
                    int reveal() {
                        if (revealed) return 0;
                        requests++; revealed = true; return 1;
                    }
                }
                RevealState mPointerState = new RevealState(), mTouchState = new RevealState();
                int updates;
                void applyTouchAction(int action, boolean afterDispatch) { if (action != 0) updates++; }
                public static void verify() {
                    for (int flags = 0; flags < 512; flags++) {
                        Fixture f = new Fixture();
                        f.mStarted = (flags & 1) != 0;
                        f.mReleased = (flags & 2) != 0;
                        f.mTouchEdgeEnabled = (flags & 4) != 0;
                        f.mAvailable = (flags & 8) != 0;
                        f.mPolicyVisible = (flags & 16) != 0;
                        f.mAutoHide = (flags & 32) != 0;
                        f.mForcedVisible = (flags & 64) != 0;
                        f.mPointerState.revealed = (flags & 128) != 0;
                        f.mTouchState.revealed = (flags & 256) != 0;
                        boolean expected = f.mStarted && !f.mReleased && f.mTouchEdgeEnabled
                                && f.mAvailable && !f.mForcedVisible
                                && !(f.mPolicyVisible && !f.mAutoHide)
                                && !f.mPointerState.revealed && !f.mTouchState.revealed;
                        f.reveal();
                        f.reveal();
                        check(f.updates == (expected ? 1 : 0), "unexpected UI mutation " + flags);
                        check(f.mTouchState.requests == f.updates, "repeat created reveal state");
                    }
                }
                """ + RuntimeSourceFixture.methods("DesktopTaskbarRevealController",
                        "reveal", "resolvePresentation"));
    }

    @Test
    public void managedFullscreenRetainsRevealWithEitherAutoHidePreference() {
        for (final boolean autoHide : new boolean[] { false, true }) {
            assertEquals(EDGE, resolvePresentation(
                    true, false, autoHide, false, false));
            assertEquals(VISIBLE, resolvePresentation(
                    true, false, autoHide, false, true));
        }
    }

    @Test
    public void foreignForegroundSuppressesPanelEvenWhenForcedOrRevealed() {
        for (int flags = 0; flags < 16; flags++) {
            assertEquals(UNAVAILABLE, resolvePresentation(
                    false, (flags & 1) != 0, (flags & 2) != 0,
                    (flags & 4) != 0, (flags & 8) != 0));
        }
    }

    @Test
    public void desktopPreferenceChoosesPinnedPanelOrRevealEdge() {
        assertEquals(VISIBLE, resolvePresentation(
                true, true, false, false, false));
        assertEquals(EDGE, resolvePresentation(
                true, true, true, false, false));
        assertEquals(VISIBLE, resolvePresentation(
                true, true, true, false, true));
    }

    @Test
    public void forcedVisibilityOverridesConcealmentButDoesNotChangePolicy() {
        assertEquals(VISIBLE, resolvePresentation(
                true, false, false, true, false));
        assertEquals(EDGE, resolvePresentation(
                true, false, false, false, false));
    }

    @Test
    public void fullscreenCanRevealAgainAfterPointerLeaves() {
        final PointerEdgeRevealState pointer = new PointerEdgeRevealState();
        pointer.onPointerEntered();
        pointer.setArmed(resolvePresentation(
                true, false, false, false, false) == EDGE);
        assertEquals(VISIBLE, resolvePresentation(
                true, false, false, false, pointer.isRevealed()));

        assertEquals(PointerEdgeRevealState.TimerAction.START_HIDE,
                pointer.onPointerExited());
        assertTrue(pointer.onHideTimeout());
        assertEquals(EDGE, resolvePresentation(
                true, false, false, false, pointer.isRevealed()));

        assertEquals(PointerEdgeRevealState.TimerAction.START_REVEAL,
                pointer.onPointerEntered());
        assertTrue(pointer.onRevealTimeout());
        assertEquals(VISIBLE, resolvePresentation(
                true, false, false, false, pointer.isRevealed()));
    }
}
