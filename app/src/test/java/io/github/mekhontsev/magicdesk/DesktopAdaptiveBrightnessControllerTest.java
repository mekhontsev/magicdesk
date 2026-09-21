package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

import android.provider.Settings;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class DesktopAdaptiveBrightnessControllerTest {
    @Test
    public void appliesToEveryEnabledDesktop() {
        assertFalse(DesktopAdaptiveBrightnessController.shouldDisable(
                false, DesktopDisplayTarget.wired(2)));
        assertTrue(DesktopAdaptiveBrightnessController.shouldDisable(
                true, DesktopDisplayTarget.phone()));
        assertTrue(DesktopAdaptiveBrightnessController.shouldDisable(
                true, DesktopDisplayTarget.simulated(3)));
        assertTrue(DesktopAdaptiveBrightnessController.shouldDisable(
                true, DesktopDisplayTarget.wired(2)));
        assertTrue(DesktopAdaptiveBrightnessController.shouldDisable(
                true, DesktopDisplayTarget.wireless(4)));
    }

    @Test
    public void restoresAutomaticModeChangedByMagicDesk() {
        final FakeBrightnessMode mode = new FakeBrightnessMode(
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
        final DesktopAdaptiveBrightnessController controller =
                new DesktopAdaptiveBrightnessController(mode, Runnable::run);

        controller.reconcile(true, List.of(DesktopDisplayTarget.wired(2)));
        controller.release();

        assertEquals(1, mode.preserved);
        assertEquals(List.of(
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC),
                mode.writes);
    }

    @Test
    public void leavesUserManualModeUnchanged() {
        final FakeBrightnessMode mode = new FakeBrightnessMode(
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
        final DesktopAdaptiveBrightnessController controller =
                new DesktopAdaptiveBrightnessController(mode, Runnable::run);

        controller.reconcile(true, List.of(DesktopDisplayTarget.wired(2)));
        controller.release();

        assertTrue(mode.writes.isEmpty());
        assertEquals(0, mode.preserved);
    }

    @Test public void overlappingWorkspacesKeepOnePolicyUntilLastClose() {
        var mode = new FakeBrightnessMode(Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
        var controller = new DesktopAdaptiveBrightnessController(mode, Runnable::run);
        controller.reconcile(true, List.of(DesktopDisplayTarget.phone()));
        controller.reconcile(true, List.of(DesktopDisplayTarget.phone(), DesktopDisplayTarget.simulated(2)));
        controller.reconcile(true, List.of(DesktopDisplayTarget.simulated(2)));
        assertEquals(1, mode.writes.size());
        assertEquals(1, mode.preserved);
        controller.reconcile(true, List.of());
        assertEquals(2, mode.writes.size());
    }

    @Test public void userModeChangeRelinquishesRestorationEvenAfterSwitchingBack() {
        var mode = new FakeBrightnessMode(Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
        var controller = new DesktopAdaptiveBrightnessController(mode, Runnable::run);
        controller.reconcile(true, List.of(DesktopDisplayTarget.phone()));
        mode.mode = Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
        mode.changed.run();
        controller.reconcile(true, List.of(DesktopDisplayTarget.phone()));
        assertEquals(1, mode.writes.size());
        mode.mode = Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL;
        mode.changed.run();
        controller.release();
        assertEquals(1, mode.writes.size());
    }

    @Test public void failedBrightnessCaptureDoesNotChangeMode() {
        var mode = new FakeBrightnessMode(Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
        mode.preserveFails = true;
        var controller = new DesktopAdaptiveBrightnessController(mode, Runnable::run);
        assertThrows(IOException.class, () -> controller.updateMode(true));
        controller.release();
        assertTrue(mode.writes.isEmpty());
    }

    @Test
    public void sessionEndSupersedesQueuedDisable() {
        final FakeBrightnessMode mode = new FakeBrightnessMode(
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC);
        final List<Runnable> operations = new ArrayList<>();
        final DesktopAdaptiveBrightnessController controller =
                new DesktopAdaptiveBrightnessController(
                        mode, operations::add);

        controller.reconcile(true, List.of(DesktopDisplayTarget.wired(2)));
        controller.release();
        operations.forEach(Runnable::run);

        assertTrue(mode.writes.isEmpty());
        assertEquals(
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC,
                mode.mode);
    }

    private static final class FakeBrightnessMode
            implements DesktopAdaptiveBrightnessController
                    .BrightnessModeAccess {
        int mode;
        final List<Integer> writes = new ArrayList<>();
        int preserved;
        Runnable changed;
        boolean preserveFails;

        FakeBrightnessMode(final int initialMode) {
            mode = initialMode;
        }

        @Override
        public int read() {
            return mode;
        }

        @Override
        public void write(final int newMode) throws IOException {
            mode = newMode;
            writes.add(newMode);
        }

        @Override public void preserveBrightness() throws IOException {
            if (preserveFails) throw new IOException("unavailable");
            preserved++;
        }
        @Override public void observe(Runnable observer) { changed = observer; }
    }
}
