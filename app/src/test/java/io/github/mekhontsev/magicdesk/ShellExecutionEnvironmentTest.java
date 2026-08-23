package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public final class ShellExecutionEnvironmentTest {
    @Test
    public void interactiveEnvironmentIsStableAndSanitized() {
        final Map<String, String> environment = new HashMap<>();
        environment.put("TERMUX_VERSION", "test");
        environment.put("SHELL_CMD__PACKAGE_NAME", "com.termux");
        environment.put("PREFIX", "/termux");
        environment.put("PWD", "/termux/home");
        environment.put("LD_PRELOAD", "/unexpected.so");

        ShellExecutionEnvironment.apply(
                environment, ShellAccess.SHELL_UID, true, "/runtime/shell");

        assertEquals("/runtime/shell/home", environment.get("HOME"));
        assertEquals("/runtime/shell/tmp", environment.get("TMPDIR"));
        assertEquals("xterm-256color", environment.get("TERM"));
        assertEquals("truecolor", environment.get("COLORTERM"));
        assertEquals("shell", environment.get("USER"));
        assertTrue(environment.get("PATH").startsWith(
                "/runtime/shell/bin:/system/bin"));
        assertFalse(environment.containsKey("TERMUX_VERSION"));
        assertFalse(environment.containsKey("SHELL_CMD__PACKAGE_NAME"));
        assertFalse(environment.containsKey("PREFIX"));
        assertFalse(environment.containsKey("PWD"));
        assertFalse(environment.containsKey("LD_PRELOAD"));
    }

    @Test
    public void nonInteractiveRootUsesTheSameRuntimeContract() {
        final Map<String, String> environment = new HashMap<>();
        environment.put("COLORTERM", "inherited");

        ShellExecutionEnvironment.apply(
                environment, ShellAccess.ROOT_UID, false, "/runtime/root");

        assertEquals("root", environment.get("USER"));
        assertEquals("dumb", environment.get("TERM"));
        assertFalse(environment.containsKey("COLORTERM"));
        assertEquals("/runtime/root/bin", environment.get("MAGICDESK_TOOLS"));
        assertTrue(ShellExecutionEnvironment.diagnostics(ShellAccess.ROOT_UID)
                .contains("/data/local/tmp/magicdesk-root/home"));
        assertTrue(ShellExecutionEnvironment.diagnostics(ShellAccess.SHELL_UID)
                .contains("/data/local/tmp/magicdesk-shell/home"));
    }
}
