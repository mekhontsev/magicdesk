package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class AutomationCliWireTest {
    private record Reply(int status, String out, String err, String requested) { }
    private Reply run(String input, String... argv) throws Exception {
        var request = new ByteArrayOutputStream();
        var writer = new DataOutputStream(request);
        writer.writeInt(argv.length);
        for (String arg : argv) AutomationCliWire.write(writer, arg);
        AutomationCliWire.write(writer, input);
        var result = new ByteArrayOutputStream();
        AutomationCliWire.serve(new DataInputStream(new ByteArrayInputStream(request.toByteArray())),
                new DataOutputStream(result), (name, args) -> DesktopAutomationResult.success(name, args).toJson());
        var reader = new DataInputStream(new ByteArrayInputStream(result.toByteArray()));
        StringBuilder out = new StringBuilder(), err = new StringBuilder();
        String requested = null;
        while (true) {
            int kind = reader.readInt();
            if (kind == AutomationCliWire.EXIT) return new Reply(reader.readInt(), out.toString(), err.toString(), requested);
            String text = AutomationCliWire.read(reader, AutomationCommandWire.RESPONSE_LIMIT);
            if (kind == AutomationCliWire.READ) requested = text;
            else if (kind == AutomationCliWire.OUT) out.append(text);
            else if (kind == AutomationCliWire.ERR) err.append(text);
            else fail("Unknown reply kind");
        }
    }
    @Test public void sharedParserPreservesWhitespaceUnicodeAndFieldProjection() throws Exception {
        var reply = run("", "list_tasks", "--query", "line one\n\u4e8c", "--field", "data.query");
        assertEquals(0, reply.status); assertEquals("line one\n\u4e8c\n", reply.out);
        assertEquals("", reply.err); assertNull(reply.requested);
    }
    @Test public void helpSchemaAndDryRunUseTheSameCatalog() throws Exception {
        assertTrue(run("", "--help").out.contains("terminal.open"));
        assertTrue(run("", "console.open", "--schema").out.contains("inputSchema"));
        assertTrue(run("", "close_desktop", "--dry-run").out.contains("close_desktop"));
    }
    @Test public void guestInputIsRequestedOnlyWhenSharedParserNeedsIt() throws Exception {
        for (String source : new String[]{"-", "@/guest/path.json"}) {
            var reply = run("{\"query\":\"guest file\"}", "list_tasks", "--args", source, "--field", "data.query");
            assertEquals(source, reply.requested); assertEquals("guest file\n", reply.out);
            assertEquals(0, reply.status);
        }
        var literal = run("", "list_tasks", "--query", "--args", "--field", "data.query");
        assertNull(literal.requested); assertEquals("--args\n", literal.out);
    }
    @Test public void invalidOptionsNeverBecomeHostFileReads() throws Exception {
        var reply = run("{}", "get_state", "--wrong", "@/host/secret");
        assertNull(reply.requested); assertEquals(2, reply.status); assertEquals("", reply.out);
        assertFalse(reply.err.isEmpty());
    }
    @Test public void frameLimitsAndNulRejectBeforeExecution() throws Exception {
        for (int count : new int[]{-1, 4097}) {
            var request = new ByteArrayOutputStream(); new DataOutputStream(request).writeInt(count);
            assertThrows(IOException.class, () -> AutomationCliWire.serve(
                    new DataInputStream(new ByteArrayInputStream(request.toByteArray())),
                    new DataOutputStream(new ByteArrayOutputStream()), (n,a) -> { fail(); return null; }));
        }
        assertThrows(IOException.class, () -> run("", "get_state\0"));
        assertThrows(IOException.class, () -> run("", "x".repeat(AutomationCommandWire.REQUEST_LIMIT+1)));
    }
    @Test public void lostResultDoesNotReplay() throws Exception {
        var request = new ByteArrayOutputStream(); var writer = new DataOutputStream(request);
        writer.writeInt(1); AutomationCliWire.write(writer, "get_state");
        var calls = new AtomicInteger();
        var broken = new OutputStream() { public void write(int b) throws IOException { throw new IOException("lost"); } };
        assertThrows(IOException.class, () -> AutomationCliWire.serve(new DataInputStream(new ByteArrayInputStream(request.toByteArray())),
                new DataOutputStream(broken), (n,a) -> { calls.incrementAndGet(); return DesktopAutomationResult.success("ok", a).toJson(); }));
        assertEquals(1, calls.get());
    }
}
