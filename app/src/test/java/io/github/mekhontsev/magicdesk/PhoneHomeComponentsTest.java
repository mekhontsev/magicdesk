package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
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

    @Test
    public void recognizesPrimaryComponentAndSubprocess() {
        final PhoneHomeComponents components =
                PhoneHomeComponents.forTests(PRIMARY, SECONDARY);

        assertTrue(components.isPrimaryComponent(
                "example.launcher", "example.launcher.PrimaryHome"));
        assertTrue(components.isPrimaryProcess("example.launcher:quickstep"));
        assertTrue(components.isPrimaryPackage("example.launcher"));
        assertFalse(components.isPrimaryComponent(
                "example.launcher",
                "example.launcher.secondarydisplay.SecondaryHome"));
        assertFalse(components.isPrimaryProcess("example.other"));
        assertFalse(components.isPrimaryPackage("example.other"));
    }

    @Test
    public void recognizesOnlyPrimaryHomeStarts() {
        final PhoneHomeComponents components =
                PhoneHomeComponents.forTests(PRIMARY, SECONDARY);
        assertTrue(components.isPrimaryHomeStart(
                "example.launcher",
                "example.launcher.PrimaryHome",
                null,
                false,
                "example.launcher"));
        assertTrue(components.isPrimaryHomeStart(
                null,
                null,
                Intent.ACTION_MAIN,
                true,
                "example.launcher"));
        assertFalse(components.isPrimaryHomeStart(
                null,
                null,
                Intent.ACTION_MAIN,
                true,
                "example.other"));
        assertFalse(components.isPrimaryHomeStart(
                null,
                null,
                "example.SETTINGS",
                false,
                "example.launcher"));
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
