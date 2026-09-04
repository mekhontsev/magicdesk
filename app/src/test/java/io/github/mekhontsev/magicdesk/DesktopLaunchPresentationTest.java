package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class DesktopLaunchPresentationTest {
    @Test
    public void explicitInstancePolicyIsIndependentFromWindowMode() {
        final DesktopLaunchPresentation presentation =
                DesktopLaunchPresentation.forMode(
                        DesktopLaunchMode.FULLSCREEN)
                        .withInstancePolicy(
                                DesktopTaskInstancePolicy.CREATE_NEW);

        assertEquals(DesktopLaunchMode.FULLSCREEN, presentation.mode);
        assertEquals(
                DesktopTaskInstancePolicy.CREATE_NEW,
                presentation.instancePolicy);
        assertEquals(-1, presentation.preferredTaskId);
    }

    @Test
    public void preferredTaskAlwaysSelectsReusePolicy() {
        final DesktopLaunchPresentation presentation =
                DesktopLaunchPresentation.forMode(
                        DesktopLaunchMode.WINDOWED)
                        .withInstancePolicy(
                                DesktopTaskInstancePolicy.CREATE_NEW)
                        .withPreferredTask(42);

        assertEquals(
                DesktopTaskInstancePolicy.REUSE_EXISTING,
                presentation.instancePolicy);
        assertEquals(42, presentation.preferredTaskId);
    }

    @Test(expected = IllegalArgumentException.class)
    public void createNewRejectsPreferredTask() {
        new DesktopLaunchPresentation(
                DesktopLaunchMode.AUTO,
                null,
                DesktopTaskInstancePolicy.CREATE_NEW,
                42);
    }

    @Test(expected = IllegalArgumentException.class)
    public void automaticModeRejectsPreferredTask() {
        DesktopLaunchPresentation.automatic().withPreferredTask(42);
    }

    @Test(expected = IllegalArgumentException.class)
    public void preferredTaskRejectsInitialBounds() {
        new DesktopLaunchPresentation(
                DesktopLaunchMode.WINDOWED,
                new RelativeWindowBounds(0, 0, 5000, 5000),
                DesktopTaskInstancePolicy.REUSE_EXISTING,
                42);
    }

    @Test(expected = IllegalArgumentException.class)
    public void boundsRequireWindowedMode() {
        new DesktopLaunchPresentation(
                DesktopLaunchMode.AUTO,
                new RelativeWindowBounds(0, 0, 5000, 5000),
                DesktopTaskInstancePolicy.REUSE_EXISTING,
                -1);
    }

    @Test
    public void wirePoliciesAreStrict() {
        assertEquals(
                DesktopTaskInstancePolicy.REUSE_EXISTING,
                DesktopTaskInstancePolicy.parse("reuse"));
        assertEquals(
                DesktopTaskInstancePolicy.CREATE_NEW,
                DesktopTaskInstancePolicy.parse("new"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void unknownWirePolicyIsRejected() {
        DesktopTaskInstancePolicy.parse("single-task");
    }
}
