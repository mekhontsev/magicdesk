package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.util.Arrays;

public final class PlatformProjectionDriverTest {
    @Test
    public void preferredTimingSelectsAvailableMode() {
        final PlatformProjectionDriver.Mode nativeMode = mode("1920x1200@120");
        final PlatformProjectionDriver.Mode fullHd = mode("1920x1080@120");
        final PlatformProjectionDriver.ModeSelection selection = selection(
                nativeMode, nativeMode, nativeMode, fullHd);

        assertSame(
                fullHd,
                selection.withPreferredTiming(fullHd.timingKey).target);
    }

    @Test
    public void nullOrUnknownTimingReturnsDriverDefault() {
        final PlatformProjectionDriver.Mode current = mode("1920x1080@60");
        final PlatformProjectionDriver.Mode nativeMode = mode("1920x1200@120");
        final PlatformProjectionDriver.ModeSelection selection = selection(
                current, current, nativeMode, current, nativeMode);

        assertSame(nativeMode, selection.withPreferredTiming(null).target);
        assertSame(
                nativeMode,
                selection.withPreferredTiming("3840x2160@60").target);
    }

    @Test
    public void fixedSelectionIgnoresPreferredTiming() {
        final PlatformProjectionDriver.Mode current = mode("1920x1080@60");
        final PlatformProjectionDriver.ModeSelection selection =
                new PlatformProjectionDriver.ModeSelection(
                        current,
                        current,
                        current,
                        Arrays.asList(current),
                        false);

        assertSame(selection, selection.withPreferredTiming(null));
    }

    private static PlatformProjectionDriver.ModeSelection selection(
            final PlatformProjectionDriver.Mode current,
            final PlatformProjectionDriver.Mode target,
            final PlatformProjectionDriver.Mode defaultTarget,
            final PlatformProjectionDriver.Mode... modes) {
        return new PlatformProjectionDriver.ModeSelection(
                current,
                target,
                defaultTarget,
                Arrays.asList(modes),
                true);
    }

    private static PlatformProjectionDriver.Mode mode(final String timing) {
        return new PlatformProjectionDriver.Mode(timing, timing);
    }
}
