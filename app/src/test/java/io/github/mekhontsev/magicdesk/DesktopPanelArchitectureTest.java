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
    public void panelHostIsShellProtectedShortLivedInfrastructure()
            throws IOException {
        final String manifest = read("src/main/AndroidManifest.xml");
        final int activity = manifest.indexOf(
                "android:name=\".DesktopPanelActivity\"");
        assertTrue("DesktopPanelActivity is declared", activity >= 0);
        final int end = manifest.indexOf("/>", activity);
        assertTrue("DesktopPanelActivity declaration is complete",
                end > activity);
        final String declaration = manifest.substring(activity, end);

        assertTrue(declaration.contains("android:exported=\"true\""));
        assertTrue(declaration.contains(
                "android:permission=\"android.permission.MANAGE_ACTIVITY_TASKS\""));
        assertTrue(declaration.contains("android:excludeFromRecents=\"true\""));
        assertTrue(declaration.contains(
                "android:taskAffinity=\"${applicationId}.desktop_panels\""));
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
    public void panelHostUsesStandardFullscreenTaskWithoutOrganizerPlane()
            throws IOException {
        final String controller = read(
                "src/main/java/io/github/mekhontsev/magicdesk/"
                        + "DesktopPanelWindowController.java");
        final String launcher = read(
                "src/main/java/io/github/mekhontsev/magicdesk/"
                        + "ShellDesktopPanelHostLauncher.java");
        final String styles = read("src/main/res/values/styles.xml");

        assertTrue(controller.contains("launchDesktopPanelHost"));
        assertFalse(controller.contains("startActivity("));
        assertTrue(launcher.contains("launchFullscreenTask"));
        assertFalse(launcher.contains("launchFullscreenTaskBehind"));
        assertTrue(launcher.contains("awaitFullscreen"));
        assertFalse(launcher.contains("TaskDisplayAreaHandle"));
        assertFalse(launcher.contains("TaskCaptionInsetsCommand"));
        assertFalse(launcher.contains("newTransaction"));
        assertFalse(launcher.contains("setWindowingMode"));
        assertFalse(launcher.contains("setForceTranslucent"));
        assertTrue(styles.contains("<style name=\"DesktopPanelTheme\""));
        assertTrue(styles.contains(
                "<item name=\"android:windowIsTranslucent\">true</item>"));
    }

    private static String read(final String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }
}
