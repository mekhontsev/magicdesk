package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

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
}
