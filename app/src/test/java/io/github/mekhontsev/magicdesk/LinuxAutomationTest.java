package io.github.mekhontsev.magicdesk;

import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class LinuxAutomationTest {
    @Test public void nativeLauncherUsesSessionOwnedBusWithoutChangingArguments() {
        String command = "exec code-oss --new-window '/tmp/a b'";
        String wrapped = LinuxAutomationEnvironment.command(command, "/bin/sh");
        assertTrue(wrapped.contains("exec \"$MAGICDESK_AUTOMATION_HELPER\" --session '/bin/sh' -c "
                + ShellCommandLine.quote(command)));
        assertFalse(wrapped.contains("exec dbus-run-session"));
        assertTrue(wrapped.endsWith("\nelse\n" + command + "\nfi\n"));
    }

    @Test public void frameDeadlineStartsAfterFirstByteIncludingTruncatedHeader() throws Exception {
        var started = new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(EOFException.class, () -> LinuxBusWire.read(new DataInputStream(new ByteArrayInputStream(new byte[0])), started::incrementAndGet));
        assertEquals(0, started.get());
        assertThrows(EOFException.class, () -> LinuxBusWire.read(new DataInputStream(new ByteArrayInputStream(new byte[]{0})), started::incrementAndGet));
        assertEquals(1, started.get());
    }

    @Test public void typedValuesPreserveLargeIntegersVariantsAndUnicode() throws Exception {
        JSONObject variant = LinuxBusWire.value("v", LinuxBusWire.value("t", "18446744073709551615"));
        JSONObject structure = LinuxBusWire.value("(sv)", new JSONArray().put(LinuxBusWire.value("s", "text\n\ud83d\ude00")).put(variant));
        JSONObject request = new JSONObject().put("destination", "org.test.Service").put("path", "/test")
                .put("interface", "org.test.Service").put("member", "Echo").put("arguments", new JSONArray().put(structure));
        byte[] bytes = LinuxBusWire.request(7, 1, 0, 1000, request);
        var input = new DataInputStream(new ByteArrayInputStream(bytes));
        for (int i = 0; i < 4; ++i) input.readInt();
        for (int i = 0; i < 4; ++i) input.skipNBytes(input.readInt());
        byte[] values = input.readAllBytes();
        var payload = new ByteArrayOutputStream(); var out = new DataOutputStream(payload);
        out.writeInt(7); out.writeInt(0); out.writeInt(0);
        for (int i = 0; i < 6; ++i) out.writeInt(0);
        out.write(values);
        var framed = new ByteArrayOutputStream(); var frame = new DataOutputStream(framed);
        frame.writeInt(payload.size()); frame.write(payload.toByteArray());
        var message = LinuxBusWire.read(new DataInputStream(new ByteArrayInputStream(framed.toByteArray())));
        assertEquals(7, message.id());
        assertEquals(structure.toString(), message.data().getJSONArray("values").getJSONObject(0).toString());
    }

    @Test public void wireRejectsUnboundedFramesAndMalformedUnicode() throws Exception {
        assertThrows(IOException.class, () -> LinuxBusWire.read(new DataInputStream(new ByteArrayInputStream(new byte[]{127, 0, 0, 0}))));
        JSONObject args = new JSONObject().put("rule", "\ud800");
        assertThrows(IOException.class, () -> LinuxBusWire.request(1, 2, 0, 1000, args));
        assertThrows(IOException.class, () -> LinuxBusWire.request(1, 2, 0, 1000, new JSONObject().put("rule", "a\0b")));
        JSONObject nested = LinuxBusWire.value("s", "text");
        for (int i = 0; i < 40; ++i) nested = LinuxBusWire.value("v", nested);
        JSONObject request = new JSONObject().put("destination", "org.test.Service").put("path", "/test")
                .put("interface", "org.test.Service").put("member", "Echo").put("arguments", new JSONArray().put(nested));
        assertThrows(IOException.class, () -> LinuxBusWire.request(1, 1, 0, 1000, request));
    }

    @Test public void applicationFlagsAreNeverRewritten() throws Exception {
        JSONObject args = new JSONObject().put("argv", new JSONArray().put("chromium").put("--disable-renderer-accessibility")
                .put("--disable-renderer-accessibility=1").put("--user-data-dir=/tmp/test").put("--").put("--disable-renderer-accessibility"));
        JSONObject launch = LinuxApplicationLaunch.prepare(args);
        assertEquals(args.getJSONArray("argv").toString(), launch.getJSONArray("argv").toString());
        assertEquals(0, launch.getJSONArray("environment").length());
    }

    @Test public void accessibilityEnvironmentAndContainerFlagsAreExplicit() throws Exception {
        JSONArray supplied = new JSONArray().put("flatpak").put("run").put("--env=GTK_A11Y=atspi")
                .put("org.chromium.Chromium").put("--force-renderer-accessibility");
        JSONObject args = new JSONObject().put("argv", supplied).put("environment",
                new JSONObject().put("QT_LINUX_ACCESSIBILITY_ALWAYS_ON", "1"));
        JSONObject launch = LinuxApplicationLaunch.prepare(args);
        assertEquals(supplied.toString(), launch.getJSONArray("argv").toString());
        assertEquals("QT_LINUX_ACCESSIBILITY_ALWAYS_ON=1", launch.getJSONArray("environment").getString(0));
        args.put("environment", new JSONObject().put("BAD=NAME", "x"));
        assertThrows(IllegalArgumentException.class, () -> LinuxApplicationLaunch.prepare(args));
    }

    @Test public void selectorDoesNotFabricateAndroidProperties() throws Exception {
        JSONObject criteria = new JSONObject().put("role", "push button").put("name", "Run");
        assertThrows(IllegalArgumentException.class, () -> new UiSelector(criteria));
        UiSelector selector = new UiSelector(criteria, java.util.Set.of("role", "name"));
        assertTrue(selector.matches(new JSONObject().put("role", "push button").put("name", "Run")));
        assertFalse(selector.matches(new JSONObject().put("className", "Button").put("text", "Run")));
        assertTrue(selector.couldMatchRedacted(new JSONObject().put("role", "push button").put("password", true)));
    }
}
