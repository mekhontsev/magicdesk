package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class DesktopWidgetControllerTest {
    @Test
    public void refreshingWidgetsDoesNotDeleteAnUnboundPendingId() throws IOException {
        final String source = source();
        final String widgets = source.substring(source.indexOf("List<WidgetEntry> widgets()"),
                source.indexOf("AppWidgetHostView createView("));
        assertTrue(widgets.contains("} else if (appWidgetId != mPendingWidgetId) {"));
        assertTrue(widgets.indexOf("appWidgetId != mPendingWidgetId")
                < widgets.indexOf("deleteWidgetId(appWidgetId)"));
    }

    @Test
    public void pendingBindingCannotBeOverwrittenByAnotherBindOrConfigure() throws IOException {
        final String source = source();
        for (final String method : new String[] {
                "void addWidget()", "void addWidgets(final String packageName)",
                "private void bindWidget(final AppWidgetProviderInfo info)",
                "void configure(final int appWidgetId)"}) {
            assertTrue(source.contains(method + " {\n"
                    + "        if (mPendingWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {\n"
                    + "            return;\n        }"));
        }
    }

    @Test
    public void cancellationStillReleasesAnOwnedNewWidget() throws IOException {
        final String source = source();
        final String result = source.substring(source.indexOf("boolean handleActivityResult("),
                source.indexOf("private void finishInitialBinding("));
        assertTrue(result.contains("if (mPendingNewWidget"));
        assertTrue(result.contains("deleteWidgetId(mPendingWidgetId);"));
    }

    @Test
    public void pendingIdAndNewIdOwnershipUseMatchingSavedStateKeys() throws IOException {
        final String source = source();
        final String save = source.substring(source.indexOf("void saveInstanceState("),
                source.indexOf("void restoreInstanceState("));
        final String restore = source.substring(source.indexOf("void restoreInstanceState("),
                source.indexOf("void start()"));
        assertTrue(save.contains("outState.putInt(STATE_PENDING_ID, mPendingWidgetId);"));
        assertTrue(save.contains("outState.putBoolean(STATE_PENDING_NEW, mPendingNewWidget);"));
        assertTrue(restore.contains("if (state == null) {\n            return;"));
        assertTrue(restore.contains("mPendingWidgetId = state.getInt(\n"
                + "                STATE_PENDING_ID, AppWidgetManager.INVALID_APPWIDGET_ID);"));
        assertTrue(restore.contains("mPendingNewWidget = mPendingWidgetId != "
                + "AppWidgetManager.INVALID_APPWIDGET_ID\n"
                + "                && state.getBoolean(STATE_PENDING_NEW, false);"));
        assertFalse(restore.contains("deleteWidgetId("));
    }

    @Test
    public void workspaceForwardsStateWithoutEnumeratingWidgets() throws IOException {
        final String source = source("DesktopWorkspaceController");
        final String state = source.substring(source.indexOf("void saveInstanceState("),
                source.indexOf("DesktopGridLayout createGrid()"));
        assertTrue(state.contains("mWidgets.saveInstanceState(outState);"));
        assertTrue(state.contains("mWidgets.restoreInstanceState(state);"));
        assertFalse(state.contains("mWidgets.widgets()"));
    }

    @Test
    public void hostRestoresPendingWidgetBeforeAnyRenderingOrRuntimeCallbacks() throws IOException {
        final String source = source("DesktopShellActivity");
        final String create = source.substring(source.indexOf("protected void onCreate("),
                source.indexOf("protected void onSaveInstanceState("));
        final int restore = create.indexOf(
                "mDesktopWorkspaceController.restoreInstanceState(savedInstanceState);");
        assertTrue(restore > create.indexOf("new DesktopWorkspaceController(this, mUi);"));
        assertTrue(restore < create.indexOf("setContentView(createDesktopContentView());"));
        assertTrue(restore < create.indexOf("MagicDeskRuntime.start(this);"));
        assertTrue(restore < create.indexOf("renderApps();"));
    }

    @Test
    public void hostSavesWidgetStateWhenWorkspaceWasCreated() throws IOException {
        final String source = source("DesktopShellActivity");
        final String save = source.substring(source.indexOf("protected void onSaveInstanceState("),
                source.indexOf("private static DesktopDisplayTarget.Kind parseTargetKind("));
        assertTrue(save.contains("if (mDesktopWorkspaceController != null) {\n"
                + "            mDesktopWorkspaceController.saveInstanceState(outState);\n"
                + "        }"));
        assertTrue(save.indexOf("mDesktopWorkspaceController.saveInstanceState(outState);")
                < save.indexOf("super.onSaveInstanceState(outState);"));
    }

    private static String source() throws IOException {
        return source("DesktopWidgetController");
    }

    private static String source(final String name) throws IOException {
        return Files.readString(Path.of(
                "src/main/java/io/github/mekhontsev/magicdesk/" + name + ".java"));
    }
}
