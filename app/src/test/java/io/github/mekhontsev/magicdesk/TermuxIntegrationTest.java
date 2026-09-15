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
}
