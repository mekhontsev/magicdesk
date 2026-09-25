package io.github.mekhontsev.magicdesk;

import static io.github.mekhontsev.magicdesk.DesktopTaskbarRevealController.Presentation.EDGE;
import static io.github.mekhontsev.magicdesk.DesktopTaskbarRevealController.Presentation.UNAVAILABLE;
import static io.github.mekhontsev.magicdesk.DesktopTaskbarRevealController.Presentation.VISIBLE;
import static io.github.mekhontsev.magicdesk.DesktopTaskbarRevealController.resolvePresentation;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DesktopTaskbarRevealControllerTest {
    @Test public void externalLayersFollowFullscreenRevealAndAvailabilityWithoutChangingHome() {
        for (int flags = 0; flags < 32; flags++) {
            boolean available = (flags & 1) != 0, policy = (flags & 2) != 0,
                    automaticHold = (flags & 4) != 0, pointer = (flags & 8) != 0,
                    explicit = (flags & 16) != 0;
            var layers = DesktopTaskbarRevealController.resolveShellLayers(
                    available, policy, automaticHold, pointer, explicit);
            assertTrue(layers.contains(ShellSurface.Layer.BACKGROUND));
            assertTrue(layers.contains(ShellSurface.Layer.BOTTOM));
            assertEquals(explicit || available, layers.contains(ShellSurface.Layer.OVERLAY));
            assertEquals(explicit || available && (policy || automaticHold || pointer),
                    layers.contains(ShellSurface.Layer.TOP));
        }
    }

    @Test
    public void navigationOnlyRevealsLiveHiddenPhoneChromeOnce() throws Exception {
        RuntimeSourceFixture.verify("""
                enum Presentation { UNAVAILABLE, EDGE, VISIBLE }
                boolean mStarted, mReleased, mTouchEdgeEnabled, mAvailable,
                        mPolicyVisible, mAutoHide, mAutomaticHold, mInteractionHold;
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
                    for (int flags = 0; flags < 1024; flags++) {
                        Fixture f = new Fixture();
                        f.mStarted = (flags & 1) != 0;
                        f.mReleased = (flags & 2) != 0;
                        f.mTouchEdgeEnabled = (flags & 4) != 0;
                        f.mAvailable = (flags & 8) != 0;
                        f.mPolicyVisible = (flags & 16) != 0;
                        f.mAutoHide = (flags & 32) != 0;
                        f.mAutomaticHold = (flags & 64) != 0;
                        f.mPointerState.revealed = (flags & 128) != 0;
                        f.mTouchState.revealed = (flags & 256) != 0;
                        f.mInteractionHold = (flags & 512) != 0;
                        boolean expected = f.mStarted && !f.mReleased && f.mTouchEdgeEnabled
                                && !f.mTouchState.revealed && !f.mInteractionHold
                                && (!f.mAvailable || !f.mAutomaticHold
                                    && !(f.mPolicyVisible && !f.mAutoHide)
                                    && !f.mPointerState.revealed);
                        f.reveal();
                        f.reveal();
                        check(f.updates == (expected ? 1 : 0), "unexpected UI mutation " + flags);
                        check(f.mTouchState.requests == f.updates, "repeat created reveal state");
                    }
                }
                """ + RuntimeSourceFixture.methods("DesktopTaskbarRevealController",
                        "reveal", "currentPresentation", "isExplicitlyRevealed", "resolvePresentation"));
    }

    @Test
    public void managedFullscreenRetainsRevealWithEitherAutoHidePreference() {
        for (final boolean autoHide : new boolean[] { false, true }) {
            assertEquals(EDGE, resolvePresentation(
                    true, false, autoHide, false, false, false));
            assertEquals(VISIBLE, resolvePresentation(
                    true, false, autoHide, false, false, true));
        }
    }

    @Test
    public void independentForegroundSuppressesAutomaticPanelButAllowsExplicitReveal() {
        for (int flags = 0; flags < 16; flags++) {
            assertEquals(UNAVAILABLE, resolvePresentation(
                    false, (flags & 1) != 0, (flags & 2) != 0,
                    (flags & 4) != 0, (flags & 8) != 0, false));
            assertEquals(VISIBLE, resolvePresentation(
                    false, (flags & 1) != 0, (flags & 2) != 0,
                    (flags & 4) != 0, (flags & 8) != 0, true));
        }
    }

    @Test
    public void desktopPreferenceChoosesPinnedPanelOrRevealEdge() {
        assertEquals(VISIBLE, resolvePresentation(
                true, true, false, false, false, false));
        assertEquals(EDGE, resolvePresentation(
                true, true, true, false, false, false));
        assertEquals(VISIBLE, resolvePresentation(
                true, true, true, false, true, false));
    }

    @Test
    public void automaticHoldOverridesConcealmentOnlyWhenChromeIsAvailable() {
        assertEquals(VISIBLE, resolvePresentation(
                true, false, false, true, false, false));
        assertEquals(EDGE, resolvePresentation(
                true, false, false, false, false, false));
    }

    @Test
    public void fullscreenCanRevealAgainAfterPointerLeaves() {
        final PointerEdgeRevealState pointer = new PointerEdgeRevealState();
        pointer.onPointerEntered();
        pointer.setArmed(resolvePresentation(
                true, false, false, false, false, false) == EDGE);
        assertEquals(VISIBLE, resolvePresentation(
                true, false, false, false, pointer.isRevealed(), false));

        assertEquals(PointerEdgeRevealState.TimerAction.START_HIDE,
                pointer.onPointerExited());
        assertTrue(pointer.onHideTimeout());
        assertEquals(EDGE, resolvePresentation(
                true, false, false, false, pointer.isRevealed(), false));

        assertEquals(PointerEdgeRevealState.TimerAction.START_REVEAL,
                pointer.onPointerEntered());
        assertTrue(pointer.onRevealTimeout());
        assertEquals(VISIBLE, resolvePresentation(
                true, false, false, false, pointer.isRevealed(), false));
    }

    @Test
    public void explicitRevealSurvivesSnapshotChangesUntilUserDismissesIt() throws Exception {
        RuntimeSourceFixture.verify("""
                enum Presentation { UNAVAILABLE, EDGE, VISIBLE }
                boolean mStarted = true, mTouchEdgeEnabled = true, mReleased,
                        mAvailable, mPolicyVisible, mAutoHide, mAutomaticHold, mInteractionHold;
                static class PointerEdgeRevealState {
                """ + RuntimeSourceFixture.methods("PointerEdgeRevealState", "setArmed", "isRevealed") + """
                    boolean mArmed, mPointerInside, mRevealed, mRevealPending, mHidePending;
                }
                static class TouchEdgeRevealState {
                    enum Action { NONE, REVEAL, DISMISS }
                    boolean mArmed, mTracking, mRevealed, mDismissOnUp;
                    float mDownX, mDownY;
                """ + RuntimeSourceFixture.methods("TouchEdgeRevealState",
                        "setArmed", "reveal", "isRevealed", "onDown", "onUp", "dismiss") + """
                }
                PointerEdgeRevealState mPointerState = new PointerEdgeRevealState();
                TouchEdgeRevealState mTouchState = new TouchEdgeRevealState();
                void cancelTimers() {}
                void applyPresentation() {}
                public static void verify() {
                    Fixture f = new Fixture();
                    f.updateArmedState();
                    check(!f.mTouchState.mArmed, "independent app exposes an edge");
                    f.mTouchState.reveal();
                    for (boolean available : new boolean[] {true, false, true, false}) {
                        f.setAvailable(available);
                        check(f.mTouchState.isRevealed(), "snapshot cancelled explicit reveal");
                    }
                    check(!f.mTouchState.mArmed, "independent app arms edge gestures");
                    check(f.mTouchState.dismiss() == TouchEdgeRevealState.Action.DISMISS,
                            "outside touch must dismiss");
                    check(resolvePresentation(false, false, false, false, false,
                            f.mTouchState.isRevealed()) == Presentation.UNAVAILABLE,
                            "outside touch must restore independent-app policy");
                    f.mTouchState.reveal();
                    f.mTouchState.onDown(10, 10);
                    check(f.mTouchState.onUp() == TouchEdgeRevealState.Action.DISMISS,
                            "taskbar action must dismiss even without edge gestures");
                    f.mTouchState.reveal();
                    f.setVisibilityHolds(false, true);
                    check(!f.mTouchState.isRevealed(), "Start did not consume navigation reveal");
                    check(f.currentPresentation() == Presentation.VISIBLE, "Start did not retain taskbar");
                    f.mTouchState.dismiss();
                    check(f.currentPresentation() == Presentation.VISIBLE,
                            "interaction inside Start cancelled its taskbar hold");
                    f.setVisibilityHolds(true, true);
                    check(f.currentPresentation() == Presentation.VISIBLE, "IME hid Start taskbar");
                    f.setVisibilityHolds(true, false);
                    check(f.currentPresentation() == Presentation.UNAVAILABLE,
                            "independent IME retained chrome after Start dismissal");
                    f.setAvailable(true);
                    check(f.currentPresentation() == Presentation.VISIBLE, "managed IME hold lost");
                    f.setVisibilityHolds(false, false);
                    check(f.currentPresentation() == Presentation.EDGE, "managed fullscreen edge lost");
                    f.setVisibilityHolds(false, true);
                    check(f.currentPresentation() == Presentation.VISIBLE, "managed Start hold lost");
                    f.setAvailable(false);
                    check(f.currentPresentation() == Presentation.VISIBLE, "snapshot cancelled Start hold");
                    f.setVisibilityHolds(false, false);
                    check(f.currentPresentation() == Presentation.UNAVAILABLE, "Start dismissal left chrome visible");
                }
                """ + RuntimeSourceFixture.methods("DesktopTaskbarRevealController",
                        "setAvailable", "setVisibilityHolds", "updateArmedState",
                        "currentPresentation", "isExplicitlyRevealed", "resolvePresentation"));
    }
}
