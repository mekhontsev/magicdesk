package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class DesktopWidgetControllerTest {
    @Test
    public void enumerationAndConfigurationResultsCannotCrossWorkspaceOwnership() throws Exception {
        RuntimeSourceFixture.verify("""
                static final int REQUEST_BIND = 1101, REQUEST_CONFIGURE = 1102;
                static final String TAG = "widgets";
                static class AppWidgetManager {
                    static final int INVALID_APPWIDGET_ID = -1;
                    static final String EXTRA_APPWIDGET_ID = "id";
                }
                static class Activity { static final int RESULT_OK = -1; }
                static class Intent {
                    final int id;
                    Intent(int id) { this.id = id; }
                    int getIntExtra(String key, int fallback) { return id; }
                }
                static class Log { static void w(String tag, String message, Throwable error) {} }
                static class AppWidgetProviderInfo {}
                static class Host {
                    final Set<Integer> ids = new LinkedHashSet<>();
                    final List<Integer> deleted = new ArrayList<>();
                    int[] getAppWidgetIds() { return ids.stream().mapToInt(Integer::intValue).toArray(); }
                    void deleteAppWidgetId(int id) { ids.remove(id); deleted.add(id); }
                }
                static class Lease {
                    final Host host = new Host();
                    boolean current = true;
                    boolean owns(int id) { return current && host.ids.contains(id); }
                }
                static class Manager {
                    final Map<Integer, AppWidgetProviderInfo> infos = new HashMap<>();
                    AppWidgetProviderInfo getAppWidgetInfo(int id) { return infos.get(id); }
                }
                private final Lease mLease = new Lease();
                private final Manager mManager = new Manager();
                private final Map<Integer, Object> mViews = new HashMap<>();
                private int mPendingWidgetId = -1;
                private boolean mPendingNewWidget;
                private int completed, bound;
                private final Runnable mChanged = () -> completed++;
                boolean ensureHost() { return mLease.current; }
                boolean owns(int id) { return mLease.owns(id); }
                void finishInitialBinding(int id) { bound = id; }
                public static void verify() {
                    Fixture f = new Fixture();
                    f.mLease.host.ids.addAll(List.of(10, 11, 12));
                    f.mManager.infos.put(10, new AppWidgetProviderInfo());
                    f.mManager.infos.put(20, new AppWidgetProviderInfo());
                    f.mPendingWidgetId = 11; f.mPendingNewWidget = true;
                    check(f.widgets().size() == 1 && f.widgets().get(0).appWidgetId == 10, "host-local enumeration");
                    check(f.mLease.host.deleted.equals(List.of(12)), "pending ID is not stale");
                    f.deleteWidgetId(20);
                    check(!f.mLease.host.deleted.contains(20), "cannot delete another host's widget");
                    check(!f.handleActivityResult(99, -1, null), "unrelated Activity result untouched");
                    f.handleActivityResult(REQUEST_BIND, -1, new Intent(20));
                    check(f.bound == 0 && f.mPendingWidgetId == 11, "foreign result cannot configure or cancel pending widget");
                    f.handleActivityResult(REQUEST_BIND, -1, new Intent(11));
                    check(f.bound == 11, "matching result continues binding");
                    f.handleActivityResult(REQUEST_CONFIGURE, 0, null);
                    check(f.mLease.host.deleted.equals(List.of(12, 11)), "cancel deletes only owned new widget");
                    f.mPendingWidgetId = 10; f.mPendingNewWidget = false;
                    f.handleActivityResult(REQUEST_CONFIGURE, 0, null);
                    check(f.mLease.host.ids.contains(10), "cancel reconfiguration retains widget");
                    f.mPendingWidgetId = 10;
                    f.handleActivityResult(REQUEST_CONFIGURE, -1, null);
                    check(f.completed == 1 && f.mPendingWidgetId == -1, "successful configuration publishes once");
                    f.mLease.current = false; f.mPendingWidgetId = 10; f.mPendingNewWidget = true;
                    f.handleActivityResult(REQUEST_CONFIGURE, 0, null);
                    check(f.mLease.host.ids.contains(10), "stale Activity cannot delete replacement's widget");
                }
                """
                + RuntimeSourceFixture.methods("DesktopWidgetController", "widgets", "deleteWidgetId",
                        "handleActivityResult", "resolveResultId", "clearPendingWidget", "completePendingWidget")
                + RuntimeSourceFixture.nestedClass("DesktopWidgetController", "WidgetEntry"));
    }

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
                source.indexOf("void releaseDesktopUiWindows()"));
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
