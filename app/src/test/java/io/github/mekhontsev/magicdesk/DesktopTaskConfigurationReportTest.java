package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import android.graphics.Rect;

import org.junit.Test;

public final class DesktopTaskConfigurationReportTest {
    @Test
    public void savedStateIncludesModeAndRelativeBounds() {
        assertEquals(
                "windowed/bounds=100,200,3000,4000",
                DesktopTaskConfigurationReport.savedStateLabel(
                        new AppWindowState(
                                AppWindowState.Mode.WINDOWED,
                                new RelativeWindowBounds(
                                        100, 200, 3000, 4000))));
        assertEquals(
                "none",
                DesktopTaskConfigurationReport.savedStateLabel(null));
    }

    @Test
    public void presentationIsNotComparedOutsideActiveDesktop() {
        final FrameworkTaskSnapshot task = new FrameworkTaskSnapshot(
                null,
                10,
                10,
                0,
                1,
                FrameworkTaskSnapshot.WINDOWING_MODE_FULLSCREEN,
                FrameworkTaskSnapshot.ACTIVITY_TYPE_STANDARD,
                null,
                null,
                "net.sf.golly/.MainActivity",
                "net.sf.golly/.MainActivity",
                "net.sf.golly",
                "net.sf.golly",
                10_000,
                "net.sf.golly",
                new Rect(0, 0, 1216, 2688),
                false,
                false,
                null,
                new FrameworkTaskSnapshot.TaskConfiguration(
                        520, 374, 827, 374));

        assertEquals(
                "custom/scale=125%/applies=false/"
                        + "densityMatch=not-applicable",
                DesktopTaskConfigurationReport.presentationLabel(
                        task, new AppPresentationProfile(125), 43));
    }
}
