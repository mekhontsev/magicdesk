package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class DesktopContextMenuControllerTest {
    @Test
    public void slowShortcutAndProviderDiscoveryUsesTheExistingRequestWorker()
            throws IOException {
        final String source = read("DesktopContextMenuController.java");
        final String discovery = between(source, "request.submit(cancelled ->",
                "private void cancelShortcutRequest()");
        assertTrue(source.contains("AndroidDesktopActionDispatcher.createContentScope()"));
        final int delivery = discovery.indexOf("request.deliver(mActivity::runOnUiThread");
        assertTrue(delivery > 0);
        assertTrue(discovery.indexOf("mShortcuts.load(app)") >= 0);
        assertTrue(discovery.indexOf("mShortcuts.load(app)") < delivery);
        assertTrue(discovery.indexOf("mActivity.hasDesktopWidgets(app.packageName)") < delivery);
        assertTrue(discovery.contains("if (request != mShortcutRequest)"));
        assertTrue(discovery.contains("mActivity.isActivityUnavailable() || !panels.isRequested(mMenuRoot)"));
    }

    @Test
    public void replacingOrDetachingTheMenuCancelsItsRequest() throws IOException {
        final String source = read("DesktopContextMenuController.java");
        assertTrue(source.contains("public void onViewDetachedFromWindow(final View view) {\n"
                + "                cancelShortcutRequest();"));
        assertTrue(source.contains("request.close();"));
        for (final String method : new String[] {"private void populateDesktopMenu(",
                "private void showFileMenu(", "private void prepareMenuTitle(",
                "private void prepareAppMenuTitle(", "private void prepareSubmenuTitle("}) {
            final int start = source.indexOf(method);
            final int end = source.indexOf("panels.hide(mMenuRoot);", start);
            assertTrue(start >= 0 && end > start);
            assertTrue(source.substring(start, end).contains("cancelShortcutRequest();"));
        }
    }

    @Test
    public void observedShortcutDiscoveryStaysOnTheExistingNonUiCaller() throws IOException {
        final String source = between(read("DesktopUiGateway.java"),
                "DesktopActivityLaunchResult invokeAppActionObserved(",
                "void showTransientStatus(");
        final int query = source.indexOf("new AppShortcutRepository(activity).loadAll(application, target)");
        final int ui = source.indexOf("mMainHandler.post(() ->");
        assertTrue(query > source.indexOf("Looper.myLooper() == Looper.getMainLooper()"));
        assertTrue(query < ui);
        assertTrue(source.indexOf("activity.launchShortcut(") > ui);
    }

    @Test
    public void observedShortcutsValidateTheIntegrationContractBeforeDiscoveryOrDispatch()
            throws IOException {
        final String source = between(read("DesktopUiGateway.java"),
                "DesktopActivityLaunchResult invokeAppActionObserved(",
                "void showTransientStatus(");
        final int validation = source.indexOf(
                "AndroidIntegrationGateway.requireShortcutPresentation(presentation);");
        assertTrue(validation >= 0);
        assertTrue(validation < source.indexOf("new AppShortcutRepository(activity).loadAll(application, target)"));
        assertTrue(validation < source.indexOf("mMainHandler.post(() ->"));
        assertTrue(source.contains("catch (IllegalArgumentException error)"));
        assertTrue(source.contains("return DesktopActivityLaunchResult.failed(error.getMessage());"));
    }

    private static String read(final String name) throws IOException {
        return Files.readString(Path.of("src/main/java/io/github/mekhontsev/magicdesk/" + name));
    }

    @Test public void taskbarSettingsAreStableCheckableActionsAndRefreshEveryHost() throws Exception {
        final String menu = between(read("DesktopContextMenuController.java"),
                "private void populateTaskbarMenu(", "void showForRegisteredView(");
        assertTrue(menu.contains("R.string.action_show_desktop"));
        assertTrue(menu.contains("R.string.task_manager_title"));
        assertTrue(menu.contains("R.string.action_settings"));
        assertTrue(menu.contains("addCheckableAction(R.string.settings_taskbar_auto_hide"));
        assertTrue(menu.contains("addCheckableAction(R.string.settings_keyboard_on_app_display"));
        assertTrue(menu.contains("positionAndShow(x, y)"));
        final String save = between(read("DesktopShellActivity.java"),
                "private void saveTaskbarSetting(", "DesktopViewport getDesktopViewport(");
        assertTrue(save.contains("DesktopRuntimeBridge.refreshSettings()"));
        assertTrue(save.contains("MagicDeskRuntime.refreshSettings(completion)"));
        final String checkable = between(read("DesktopContextMenuController.java"),
                "private void addCheckableAction(", "private Button addMenuItem(");
        assertTrue(checkable.contains("addMenuItem(button, null, true, false, false"));
        assertTrue(checkable.contains("action.accept(button.isChecked(), () ->"));
        assertTrue(checkable.indexOf("panels.hide(mMenuRoot)") > checkable.indexOf("action.accept("));
        assertTrue(checkable.contains("button.isShown() && button.getParent() == mPanel"));
        final String runtime = read("MagicDeskRuntimeService.java");
        assertTrue(runtime.contains("mDisplayInput.refreshSettings(settings, completion)"));
        final String factory = read("DesktopUiFactory.java");
        assertTrue(factory.contains("new android.widget.CheckBox(mContext)"));
        assertTrue(factory.contains("button.setChecked(checked)"));
    }

    @Test
    public void taskbarMenuKeepsPointerFocusUnchangedAndExcludesIme() throws IOException {
        final String source = read("DesktopContextMenuController.java");
        final String taskbar = between(source,
                "private void populateTaskbarMenu(", "void showForRegisteredView(");
        final String mouse = between(source,
                "void handleSecondaryClick(", "void showStartButtonMenu(");
        assertTrue(mouse.contains("mRequestKeyboardFocus = false;"));
        assertTrue(mouse.contains("populateTaskbarMenu(x, y);"));
        assertTrue(taskbar.contains("mRetainOwnerPanel = false;"));
        assertTrue(taskbar.contains("positionAndShow(x, y);"));
        assertFalse(taskbar.contains("mRequestKeyboardFocus = true;"));
        final String keyboard = between(source,
                "void showTaskbarMenu(", "private void populateTaskbarMenu(");
        assertTrue(keyboard.contains("mRequestKeyboardFocus = true;"));
        final String placement = between(source,
                "private void positionAndShow(", "private int getWidth(");
        assertTrue(placement.matches("(?s).*mRequestKeyboardFocus,\\s*false,.*"));
        assertFalse(placement.contains("inputMethodTarget"));
    }

    private static String between(final String source, final String start, final String end) {
        final int first = source.indexOf(start);
        final int last = source.indexOf(end, first + start.length());
        assertTrue("method boundaries exist", first >= 0 && last > first);
        return source.substring(first, last);
    }
}
