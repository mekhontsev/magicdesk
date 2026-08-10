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

        assertTrue(components.isSecondaryTask(task(SECONDARY, true)));
        assertFalse(components.isSecondaryTask(task(PRIMARY, true)));
        assertFalse(components.isSecondaryTask(task(SECONDARY, false)));
    }

    @Test
    public void recognizesSecondaryDisplayClassFromLauncherPackage() {
        final PhoneHomeComponents components =
                PhoneHomeComponents.forTests(PRIMARY);

        assertTrue(components.isSecondaryTask(task(SECONDARY, true)));
    }

    private static TaskRepository.TaskEntry task(
            final String component,
            final boolean home) {
        return new TaskRepository.TaskEntry(
                1,
                2,
                0,
                "example.launcher",
                component,
                component,
                "fullscreen",
                new Rect(0, 0, 100, 100),
                home,
                true,
                false);
    }
}
