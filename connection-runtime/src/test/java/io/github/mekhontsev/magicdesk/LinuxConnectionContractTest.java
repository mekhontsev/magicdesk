package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;

public class LinuxConnectionContractTest {
    private static final String NATIVE = "magicdesk-0123456789abcdef-native";
    private static final String PULSE = "magicdesk-0123456789abcdef-pulse";

    @Test public void routesOnlyExplicitAudioEndpoints() {
        var args = LinuxConnectionContract.launchArguments(1, Map.of("pipewire", NATIVE, "pulse", PULSE));
        assertEquals(10, args.size());
        assertEquals("--socket-path", args.get(0));
        assertEquals(NATIVE, args.get(2));
        assertEquals("--socket-path", args.get(3));
        assertEquals(PULSE, args.get(5));
        assertFalse(args.contains("--magicdesk"));
        assertFalse(args.contains("--bind"));
    }

    @Test public void graphicsRetainsAuthenticationAndGuestAddressKinds() {
        var fields = new java.util.HashMap<>(Map.of("pipewire", NATIVE, "pulse", PULSE,
                "graphicsProtocol", "wayland", "graphicsEndpoint", "magicdesk-0123456789abcdef-graphics"));
        var wayland = LinuxConnectionContract.launchArguments(1, fields);
        assertEquals("--socket-path", wayland.get(10));
        assertTrue(wayland.contains("WAYLAND_DISPLAY=" + LinuxConnectionContract.WAYLAND_PATH));
        fields.put("graphicsProtocol", "x11"); fields.put("display", "42");
        assertThrows(IllegalArgumentException.class, () -> LinuxConnectionContract.launchArguments(1, fields));
        var x11 = LinuxConnectionContract.launchArguments(1, fields, "/private/xauth-scope");
        assertTrue(x11.contains("--socket-resource-path"));
        assertTrue(x11.contains("--socket-resource-abstract"));
        assertEquals("/tmp/.X11-unix/X42", x11.get(11));
        assertTrue(x11.contains("--bind-ro-create"));
        assertFalse(x11.contains("--bind"));
        int bind = x11.indexOf("--bind-ro-create");
        assertEquals(java.util.List.of("--bind-ro-create", "/private/xauth-scope", "/tmp/magicdesk-xauthority"),
                x11.subList(bind, bind + 3));
        assertTrue(x11.contains("XAUTHORITY=/tmp/magicdesk-xauthority/Xauthority"));
        fields.put("display", "65536");
        assertThrows(IllegalArgumentException.class, () -> LinuxConnectionContract.launchArguments(1, fields, "/private/auth"));
    }

    @Test public void sessionServicesDoNotGrantEachOtherOrImplyAudio() {
        var web=LinuxConnectionContract.launchArguments(1,Map.of("internetEndpoint",NATIVE));
        assertEquals(java.util.List.of("--socket-path","/tmp/magicdesk-web.sock",NATIVE,"--env",
                "MAGICDESK_WEB_ENDPOINT=/tmp/magicdesk-web.sock"),web);
        var mcp=LinuxConnectionContract.launchArguments(1,Map.of("automationEndpoint",NATIVE,"automationToken","a".repeat(64)));
        assertFalse(mcp.stream().anyMatch(s->s.contains("WEB_ENDPOINT") || s.contains("PIPEWIRE")));
        assertThrows(IllegalArgumentException.class,()->LinuxConnectionContract.launchArguments(1,Map.of("automationEndpoint",NATIVE)));
    }

    @Test public void rejectsUnknownOrIncompleteCapabilities() {
        assertThrows(IllegalArgumentException.class, () -> LinuxConnectionContract.launchArguments(2,
                Map.of("pipewire", NATIVE, "pulse", PULSE)));
        assertThrows(IllegalArgumentException.class, () -> LinuxConnectionContract.launchArguments(1, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> LinuxConnectionContract.launchArguments(1,
                Map.of("pipewire", NATIVE, "pulse", NATIVE)));
        for (String invalid : new String[]{"/data/private/socket", "@" + NATIVE, "--bind", NATIVE + "\n", "magicdesk-short"})
            assertThrows(IllegalArgumentException.class, () -> LinuxConnectionContract.launchArguments(1,
                    Map.of("pipewire", invalid, "pulse", PULSE)));
    }
}
