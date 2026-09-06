package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

public final class TaskFullscreenTransitionCommandTest {
    @Test
    public void disappearedTaskDoesNotReportSuccessfulCaptionRefresh() {
        final MissingTaskService service = new MissingTaskService();

        assertFalse(TaskFullscreenTransitionCommand.refreshCaptionIfRequested(
                service, 2, 42, true, 0x12340002));
        assertEquals(1, service.queries);
    }

    @Test
    public void missingSourceOrDisabledRefreshDoesNotQueryFramework() {
        final MissingTaskService service = new MissingTaskService();

        assertFalse(TaskFullscreenTransitionCommand.refreshCaptionIfRequested(
                service, 2, 42, false, 0x12340002));
        assertFalse(TaskFullscreenTransitionCommand.refreshCaptionIfRequested(
                service, 2, 42, true,
                TaskLocalInsetsSourceParser.NO_SOURCE_ID));
        assertEquals(0, service.queries);
    }

    public static final class MissingTaskService {
        int queries;

        public List<?> getTasks(
                final int maxTasks,
                final boolean filterVisibleRecents,
                final boolean keepIntentExtra,
                final int displayId) {
            queries++;
            return Collections.emptyList();
        }
    }
}
