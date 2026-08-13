package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class TaskCaptionInsetsRefresherTest {
    private static final int FULLSCREEN = 1;
    private static final int FREEFORM = 5;
    private static final int CAPTION_SOURCE_ID = 0x12340002;

    @Test
    public void refreshesOnlyFreeformToFullscreenWithKnownCaption() {
        assertTrue(TaskCaptionInsetsRefresher
                .shouldRefreshAfterWindowingModeChange(
                        FREEFORM, FULLSCREEN, CAPTION_SOURCE_ID));

        assertFalse(TaskCaptionInsetsRefresher
                .shouldRefreshAfterWindowingModeChange(
                        FULLSCREEN, FREEFORM, CAPTION_SOURCE_ID));
        assertFalse(TaskCaptionInsetsRefresher
                .shouldRefreshAfterWindowingModeChange(
                        FREEFORM, FREEFORM, CAPTION_SOURCE_ID));
        assertFalse(TaskCaptionInsetsRefresher
                .shouldRefreshAfterWindowingModeChange(
                        FREEFORM,
                        FULLSCREEN,
                        TaskLocalInsetsSourceParser.NO_SOURCE_ID));
    }
}
