package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class GuestConfigurationTest {
    @Test public void resolverRequiresLiteralAddressesAndNeverInventsServers() {
        String text = GuestDnsConfiguration.servers(List.of("192.0.2.1", "2001:db8::1", "192.0.2.1"));
        assertTrue(text.contains("nameserver 192.0.2.1\n"));
        assertEquals(2, text.lines().filter(line -> line.startsWith("nameserver ")).count());
        for (String bad : List.of("example.org", "1.2.3", "256.1.1.1", "01.2.3.4", "127.1", "1.1.1.1\nsearch evil", "::1%lo"))
            assertThrows(IllegalArgumentException.class, () -> GuestDnsConfiguration.servers(List.of(bad)));
        assertThrows(IllegalArgumentException.class, () -> GuestDnsConfiguration.servers(List.of()));
    }

    @Test public void managedTerminalUsesImageAccountLoginAndStableStore() {
        var env = new LinuxLaunchRecipe.Environment(LinuxLaunchRecipe.Kind.MANAGED_GUEST, "/library/instances/id", DesktopExecBackend.SHELL, "");
        var recipe = LinuxLaunchRecipe.build("Work", env, "", "", "", LinuxLaunchRecipe.Presentation.TERMINAL);
        var arguments = DesktopExecTemplate.expandArguments(recipe.exec, DesktopLaunchArguments.empty(), "Work", "", "");
        assertEquals("'magicdesk-guest' 'image' 'login' '/library/instances/id' '--'", arguments);
    }

    @Test public void graphicsGetsKeyboardFromItsStoreAndRetainsFileIdentity() {
        var env = new LinuxLaunchRecipe.Environment(LinuxLaunchRecipe.Kind.MANAGED_GUEST, "/library/instances/id", DesktopExecBackend.SHELL, "");
        for (var protocol : GraphicalProtocol.values()) {
            var recipe = LinuxLaunchRecipe.build("Editor", env, "mousepad", "", "root", LinuxLaunchRecipe.Presentation.APPLICATION, protocol);
            assertEquals("guest:/library/instances/id", recipe.graphics.keyboardDirectory());
            assertEquals(GraphicalConnectionMode.ROUTED, recipe.graphics.connectionMode());
            assertTrue(recipe.exec.contains("libmagicdesk_guest_files.so"));
            assertTrue(recipe.exec.contains("MAGICDESK_GUEST_FILES_SOCKET"));
            assertFalse(recipe.exec.contains("proot"));
        }
        assertThrows(IllegalArgumentException.class, () -> HostedKeyboardSource.parse("guest:relative"));
    }
}
