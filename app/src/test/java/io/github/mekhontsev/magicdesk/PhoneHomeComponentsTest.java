package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;

import org.junit.Test;

public final class PhoneHomeComponentsTest {
    private static final String PRIMARY = "example.launcher/.PrimaryHome";
    private static final String SECONDARY =
            "example.launcher/.secondarydisplay.SecondaryHome";

    @Test
    public void recognizesResolvedSecondaryHome() {
        final PhoneHomeComponents components =
                PhoneHomeComponents.forTests(PRIMARY, SECONDARY);

        assertTrue(components.hasSecondaryHomeOnTop(
                task(SECONDARY, SECONDARY, true)));
        assertTrue(components.isDedicatedSecondaryTask(
                task(SECONDARY, SECONDARY, true)));
        assertFalse(components.hasSecondaryHomeOnTop(
                task(PRIMARY, PRIMARY, true)));
        assertFalse(components.hasSecondaryHomeOnTop(
                task(SECONDARY, SECONDARY, false)));
    }

    @Test
    public void recognizesSecondaryDisplayClassFromLauncherPackage() {
        final PhoneHomeComponents components =
                PhoneHomeComponents.forTests(PRIMARY);

        assertTrue(components.hasSecondaryHomeOnTop(
                task(SECONDARY, SECONDARY, true)));
    }

    @Test
    public void mixedPrimaryAndSecondaryTaskIsNotRemoved() {
        final PhoneHomeComponents components =
                PhoneHomeComponents.forTests(PRIMARY, SECONDARY);

        assertTrue(components.hasSecondaryHomeOnTop(
                task(PRIMARY, SECONDARY, true)));
        assertFalse(components.isDedicatedSecondaryTask(
                task(PRIMARY, SECONDARY, true)));
        assertFalse(components.hasSecondaryHomeOnTop(
                task(SECONDARY, PRIMARY, true)));
        assertFalse(components.isDedicatedSecondaryTask(
                task(SECONDARY, PRIMARY, true)));
    }

    private static TaskRepository.TaskEntry task(
            final String component,
            final String topActivity,
            final boolean home) {
        return new TaskRepository.TaskEntry(
                1,
                2,
                0,
                "example.launcher",
                component,
                topActivity,
                "fullscreen",
                new Rect(0, 0, 100, 100),
                home,
                true,
                false);
    }
}
