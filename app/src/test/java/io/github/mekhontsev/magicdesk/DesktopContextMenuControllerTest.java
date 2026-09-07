package io.github.mekhontsev.magicdesk;

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
        assertTrue(discovery.indexOf("DesktopLaunchIntegrationRegistry.actions(") < delivery);
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
        final int query = source.indexOf("new AppShortcutRepository(activity).loadAll(target)");
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
        assertTrue(validation < source.indexOf("new AppShortcutRepository(activity).loadAll(target)"));
        assertTrue(validation < source.indexOf("mMainHandler.post(() ->"));
        assertTrue(source.contains("catch (IllegalArgumentException error)"));
        assertTrue(source.contains("return DesktopActivityLaunchResult.failed(error.getMessage());"));
    }

    private static String read(final String name) throws IOException {
        return Files.readString(Path.of("src/main/java/io/github/mekhontsev/magicdesk/" + name));
    }

    private static String between(final String source, final String start, final String end) {
        final int first = source.indexOf(start);
        final int last = source.indexOf(end, first + start.length());
        assertTrue("method boundaries exist", first >= 0 && last > first);
        return source.substring(first, last);
    }
}
