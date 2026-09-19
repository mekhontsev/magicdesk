package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class TaskCaptionInsetsRefresherTest {
    private static final int FULLSCREEN = 1;
    private static final int FREEFORM = 5;
    private static final int CAPTION_SOURCE_ID = 0x12340002;

    @Test
    public void delayedFullscreenRefreshDoesNotEraseRestoredFreeformCaption() throws Exception {
        RuntimeSourceFixture.verify("""
                static final int WINDOWING_MODE_FULLSCREEN = 1;
                static int mode = 1, refreshes;
                static boolean present = true;
                static final Object token = new Object();
                static class HiddenTaskApi {
                    static Object findTask(Object service, int display, int task) { return present ? token : null; }
                    static int getTaskWindowingMode(Object task) { return mode; }
                    static Object getTaskToken(Object task) { return token; }
                }
                static void refresh(Object service, Object t, int source) {
                    check(t == token && source == 123, "wrong source"); refreshes++;
                }
                public static void verify() throws Exception {
                    check(refreshTask(null, 7, 42, 123), "fullscreen repair skipped");
                    mode = 5;
                    check(!refreshTask(null, 7, 42, 123), "freeform caption cleared");
                    present = false;
                    check(!refreshTask(null, 7, 42, 123), "removed task refreshed");
                    check(refreshes == 1, "unexpected refresh");
                }
                """ + RuntimeSourceFixture.methods("TaskCaptionInsetsRefresher", "refreshTask"));
    }

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
