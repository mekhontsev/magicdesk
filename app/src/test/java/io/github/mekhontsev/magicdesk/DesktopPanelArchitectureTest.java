package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class DesktopPanelArchitectureTest {
    @Test
    public void manifestNeedsNoDisplayOverOtherAppsPermission()
            throws IOException {
        final String manifest = read("src/main/AndroidManifest.xml");

        assertFalse(manifest.contains("SYSTEM_ALERT_WINDOW"));
        assertFalse(manifest.contains("ACTION_MANAGE_OVERLAY_PERMISSION"));
    }

    @Test
    public void chromeHostIsShellProtectedPersistentInfrastructure()
            throws IOException {
        final String manifest = read("src/main/AndroidManifest.xml");
        final int activity = manifest.indexOf(
                "android:name=\".DesktopChromeActivity\"");
        assertTrue("DesktopChromeActivity is declared", activity >= 0);
        final int end = manifest.indexOf("/>", activity);
        assertTrue("DesktopChromeActivity declaration is complete",
                end > activity);
        final String declaration = manifest.substring(activity, end);

        assertTrue(declaration.contains("android:exported=\"true\""));
        assertTrue(declaration.contains(
                "android:permission=\"android.permission.MANAGE_ACTIVITY_TASKS\""));
        assertTrue(declaration.contains("android:excludeFromRecents=\"true\""));
        assertTrue(declaration.contains(
                "android:taskAffinity=\"${applicationId}.desktop_chrome\""));
        assertFalse(manifest.contains(".DesktopPanelActivity"));
        assertFalse(manifest.contains(".DesktopTaskbarActivity"));
    }

    @Test
    public void desktopPanelsUseApplicationWindows() throws IOException {
        final String controller = read(
                "src/main/java/io/github/mekhontsev/magicdesk/"
                        + "DesktopPanelWindowController.java");

        assertTrue(controller.contains("TYPE_APPLICATION_PANEL"));
        assertFalse(controller.contains("TYPE_APPLICATION_OVERLAY"));
    }

    @Test
    public void chromeHostUsesSurfaceOrderedWorkspaceArea()
            throws IOException {
        final String controller = read(
                "src/main/java/io/github/mekhontsev/magicdesk/"
                        + "DesktopPanelWindowController.java");
        final String host = read(
                "src/main/java/io/github/mekhontsev/magicdesk/"
                        + "ShellDesktopChromeHost.java");
        final String styles = read("src/main/res/values/styles.xml");

        assertTrue(controller.contains("prepareDesktopChromeHost"));
        assertFalse(controller.contains("startActivity("));
        assertTrue(host.contains("launchFullscreenTaskBehind"));
        assertTrue(host.contains("createSurfaceOrdered"));
        assertTrue(host.contains(
                "TaskDisplayAreaHandle.Parent.DEFAULT_TASK_CONTAINER"));
        assertFalse(host.contains("TaskDisplayAreaHandle.Parent.ROOT"));
        assertTrue(host.contains("mSurfaceOrder.attachChrome(mArea)"));
        assertTrue(host.contains("mSurfaceOrder.detachChrome(area)"));
        assertFalse(host.contains("setSurfaceLayer"));
        final String planes = read(
                "src/main/java/io/github/mekhontsev/magicdesk/"
                        + "ShellFullscreenTaskPlanes.java");
        assertTrue(planes.contains("mSurfaceOrder.applyLayers(assignments)"));
        assertTrue(planes.contains("mSurfaceOrder.setVisible"));
        assertFalse(planes.contains("android.view.SurfaceControl"));
        assertTrue(host.contains("setFocusable"));
        assertFalse(host.contains("setAlwaysOnTop(transaction, mArea.token()"));
        assertFalse(host.contains("reorder(transaction, mArea.token()"));
        assertFalse(host.contains("setFocusable(transaction, mArea.token()"));
        assertFalse(host.contains("setWindowingMode(transaction, mArea.token()"));
        assertFalse(host.contains("void raise("));
        assertTrue(styles.contains("<style name=\"DesktopChromeTheme\""));
        assertTrue(styles.contains(
                "<item name=\"android:windowIsTranslucent\">true</item>"));
    }

    @Test
    public void chromePrioritySurvivesFrameworkLayerReassignment()
            throws IOException {
        final String host = read(
                "src/main/java/io/github/mekhontsev/magicdesk/"
                        + "ShellDesktopChromeHost.java");

        // WindowConfiguration.isAlwaysOnTop() ignores this flag in fullscreen.
        // TaskDisplayArea assigns a nested area's layer from its top root task.
        assertTrue(host.contains("WINDOWING_MODE_MULTI_WINDOW = 6"));
        assertTrue(host.contains(
                "transaction, taskToken, WINDOWING_MODE_MULTI_WINDOW"));
        assertTrue(host.contains(
                "setAlwaysOnTop(transaction, taskToken, true)"));
        assertTrue(host.contains("setFocusable(transaction, taskToken, false)"));
        assertTrue(host.contains("setBounds(transaction, taskToken, new Rect())"));
        assertFalse(host.contains("WINDOWING_MODE_FREEFORM"));
        assertFalse(host.contains("setAlwaysOnTop(transaction, mArea.token()"));
    }

    @Test
    public void windowOperationsDoNotAppendChromeOnlyCommits() throws IOException {
        final String coordinator = read(
                "src/main/java/io/github/mekhontsev/magicdesk/"
                        + "ShellDesktopWorkspaceCoordinator.java");
        final String observer = read(
                "src/main/java/io/github/mekhontsev/magicdesk/ShellTaskObserver.java");
        final String surfaces = read(
                "src/main/java/io/github/mekhontsev/magicdesk/"
                        + "ShellDesktopSurfaceOrder.java");

        assertFalse(coordinator.contains("mSurfaceOrder"));
        assertFalse(observer.contains("mSurfaceOrder.complete("));
        assertFalse(surfaces.contains("void restore()"));
        assertTrue(surfaces.contains("composeLayers(layers, mChrome)"));
    }

    private static String read(final String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }
}
