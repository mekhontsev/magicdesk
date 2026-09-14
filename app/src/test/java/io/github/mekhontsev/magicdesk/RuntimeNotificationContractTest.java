package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/** Keeps notification entry points outside Android's trampoline restriction. */
public final class RuntimeNotificationContractTest {
    @Test
    public void userMessagesAreListedButInternalStatusIsNot() {
        assertTrue(DesktopNotificationMapper.includeNotification("magicdesk", "other", "status"));
        assertTrue(DesktopNotificationMapper.includeNotification(
                "magicdesk", "magicdesk", UserInteractions.CHANNEL));
        assertTrue(DesktopNotificationMapper.includeNotification(
                "magicdesk", "magicdesk", TerminalNotifications.CHANNEL));
        assertFalse(DesktopNotificationMapper.includeNotification("magicdesk", "magicdesk", "status"));
        assertFalse(DesktopNotificationMapper.includeNotification("magicdesk", "magicdesk", null));
    }

    @Test
    public void notificationEntryPointsLaunchActivitiesDirectly() throws Exception {
        final String source = Files.readString(Path.of(
                "src/main/java/io/github/mekhontsev/magicdesk/"
                        + "MagicDeskRuntimeService.java"));

        assertTrue(source.contains("PendingIntent.getActivity("));
        assertTrue(source.contains("ControlActivity.createLaunchIntent(this)"));
        assertTrue(source.contains("MagicDeskTouchpadActivity.createLaunchIntent("));
        assertTrue(source.contains("TerminalNotificationActivity.resumeIntent(this)"));
        assertTrue(source.contains("options.setLaunchDisplayId("));
        assertTrue(source.contains("PendingIntent.FLAG_IMMUTABLE"));
        assertFalse(source.contains("PendingIntent.getForegroundService("));
        assertFalse(source.contains("PendingIntent.getService("));
        assertFalse(source.contains("PendingIntent.getBroadcast("));
    }

    @Test
    public void terminalResumeSharesSessionActivationAndRejectsPreviousProcesses() throws Exception {
        final String source = Files.readString(Path.of(
                "src/main/java/io/github/mekhontsev/magicdesk/TerminalNotificationActivity.java"));
        assertTrue(source.contains("TerminalNotifications.PROCESS.equals("));
        assertTrue(source.contains("ConsoleTerminalRegistry.mostRecent()"));
        assertTrue(source.contains("TerminalSessions.open("));
        assertTrue(source.contains("CommandConsoleActivity.attachIntent("));
        assertFalse(source.contains("CommandConsoleActivity.createIntent("));
        assertFalse(source.contains("ToolApplications.open("));

        final String activity = Files.readString(Path.of(
                "src/main/java/io/github/mekhontsev/magicdesk/CommandConsoleActivity.java"));
        assertTrue(activity.contains("if (hasFocus) { ConsoleTerminalRegistry.focused(mTerminalRegistryId, this); }"));
    }
}
