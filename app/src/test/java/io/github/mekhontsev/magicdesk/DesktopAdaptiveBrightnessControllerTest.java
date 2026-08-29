package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.provider.Settings;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class DesktopAdaptiveBrightnessControllerTest {
    @Test
    public void appliesOnlyToEnabledPhysicalExternalDesktop() {
        assertFalse(DesktopAdaptiveBrightnessController.shouldDisable(
                false, DesktopDisplayTarget.wired(2)));
        assertFalse(DesktopAdaptiveBrightnessController.shouldDisable(
                true, DesktopDisplayTarget.phone()));
        assertFalse(DesktopAdaptiveBrightnessController.shouldDisable(
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

        controller.reconcile(true, DesktopDisplayTarget.wired(2));
        controller.release();

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

        controller.reconcile(true, DesktopDisplayTarget.wired(2));
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

        controller.reconcile(true, DesktopDisplayTarget.wired(2));
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
    }
}
