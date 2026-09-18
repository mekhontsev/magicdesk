package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class TermuxIntegrationTest {
    @Test
    public void recognizesNestedFirmwareAutoLaunchFailure() {
        final RuntimeException cause = new RuntimeException(
                "Blocked by AutoLaunch");

        assertTrue(TermuxIntegration.isAutoLaunchBlocked(
                new IllegalStateException("service start failed", cause)));
        assertTrue(!TermuxIntegration.isAutoLaunchBlocked(
                new IllegalStateException("background start denied")));
    }

    @Test
    public void desktopExecRejectsOversizedInput() {
        assertThrows(
                IllegalArgumentException.class,
                () -> DesktopExecCommand.normalize(
                        "x".repeat(DesktopExecCommand.MAX_LENGTH + 1)));
    }

    @Test
    public void termuxResultUsesAndroidResultOkAsSuccessErrno() {
        assertTrue(new TermuxIntegration.CommandResult(
                0, -1, "ok", "", "").success());
        assertTrue(!new TermuxIntegration.CommandResult(
                0, 0, "", "", "").success());
        assertTrue(!new TermuxIntegration.CommandResult(
                1, -1, "", "failed", "").success());
    }

    @Test public void connectionCheckRequiresExactSuccessfulReply() {
        assertTrue(TermuxIntegration.connectionVerified(new TermuxIntegration.CommandResult(
                0, -1, "magicdesk-termux-ok", "", "")));
        assertTrue(TermuxIntegration.connectionVerified(new TermuxIntegration.CommandResult(
                0, -1, "magicdesk-termux-ok\n", "", "")));
        assertTrue(!TermuxIntegration.connectionVerified(new TermuxIntegration.CommandResult(
                0, -1, "magicdesk-termux-ok\nunexpected output", "", "")));
        assertTrue(!TermuxIntegration.connectionVerified(null));
        assertTrue(!TermuxIntegration.connectionVerified(new TermuxIntegration.CommandResult(
                0, -1, "", "", "")));
        assertTrue(!TermuxIntegration.connectionVerified(new TermuxIntegration.CommandResult(
                1, -1, "magicdesk-termux-ok", "failed", "")));
        assertTrue(!TermuxIntegration.connectionVerified(new TermuxIntegration.CommandResult(
                0, 0, "magicdesk-termux-ok", "", "denied")));
    }

    @Test public void connectionCheckUsesSharedResultDeliveryWithoutCliOrProfileSideEffects() throws Exception {
        final String probe = RuntimeSourceFixture.methods("TermuxIntegration", "checkConnection");
        assertTrue(probe.contains("runForResult(context, shellIntent(endpoint,"));
        assertTrue(probe.contains("\"--noprofile\", \"--norc\", \"-c\""));
        assertTrue(!probe.contains("AutomationCommandRuntime"));
        assertTrue(!probe.contains("ShellAccess"));
        final String delivery = RuntimeSourceFixture.methods("TermuxIntegration", "runForResult");
        assertTrue(delivery.contains("TermuxCommandResultReceiver.cancel(registration)"));
        final String dialog = java.nio.file.Files.readString(java.nio.file.Path.of(
                RuntimeSourceFixture.MAIN + "TermuxSetupDialog.java"));
        assertTrue(dialog.contains("TermuxConnectionStatus.get().removeListener(statusListener)"));
        assertTrue(!dialog.contains("TermuxCommandResultReceiver.cancel"));
        assertTrue(dialog.contains("!dialog.isShowing() || activity.isFinishing() || activity.isDestroyed()"));
        assertTrue(dialog.contains("getLaunchIntentForPackage(endpoint.packageName)"));
    }
}
