package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/** Keeps panel dismissal outside the asynchronous workspace acknowledgement. */
public final class AltTabPanelContractTest {
    @Test
    public void finishDismissesPanelBeforeSubmittingActivation() throws Exception {
        final String source = source("AltTabController");
        final String finish = source.substring(
                source.indexOf("    void finish() {"),
                source.indexOf("    void reset() {"));
        final int submit = finish.indexOf("mActivity.focusTask(app, target);");
        assertTrue("selection must use the normal focus gateway", submit >= 0);
        final String selected = finish.substring(
                finish.indexOf("final TaskRepository.TaskEntry target"), submit);
        assertTrue("dismiss immediately after committing the selection",
                selected.indexOf("reset();")
                        < selected.indexOf("mActivity.hideAllPanels();"));
        assertTrue("dismiss on the successful path, not only missing apps",
                selected.indexOf("mActivity.hideAllPanels();")
                        < selected.indexOf("if (app == null)"));
        assertFalse("completion must not dismiss a later selection",
                finish.contains("mActivity::hideAllPanels"));
    }

    @Test
    public void selectionDoesNotExposeDelayedPanelCompletion() throws Exception {
        assertFalse(source("DesktopShellActivity").contains(
                "mAppTasks.focusTask(app, task, completion)"));
        final String source = source("AppTaskController");
        final String focus = source.substring(
                source.indexOf("    void focusTask("),
                source.indexOf("    private void focusTaskOnSuccess("));
        assertFalse(focus.contains("Runnable completion"));
        assertTrue(focus.contains("focusTaskWithResult(app, task, null);"));
    }

    @Test
    public void releaseDuringSnapshotLoadDoesNotFlashPanel() throws Exception {
        final String source = source("AltTabController");
        assertTrue(source.contains("if (mLoadInProgress) {\n"
                + "            mCommitPending = true;\n"
                + "            return;\n"
                + "        }"));
        assertTrue(source.contains("if (mCommitPending) {\n"
                + "                        finish();\n"
                + "                    } else if (!mActivity.showAltTabPanel())"));
    }

    private static String source(final String className) throws Exception {
        return Files.readString(Path.of(
                "src/main/java/io/github/mekhontsev/magicdesk/"
                        + className + ".java"));
    }
}
