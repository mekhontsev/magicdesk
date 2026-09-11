package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;

public final class MagicDeskCliTest {
    private String stdout;
    private String stderr;
    private String name;
    private JSONObject arguments;
    private final AtomicInteger calls = new AtomicInteger();

    private int run(String input, MagicDeskCli.Executor executor, String... args) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final ByteArrayOutputStream err = new ByteArrayOutputStream();
        final int status = MagicDeskCli.run(args, new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                new PrintStream(out), new PrintStream(err), (command, object) -> {
                    calls.incrementAndGet(); name = command; arguments = object;
                    return executor.execute(command, object);
                });
        stdout = out.toString(StandardCharsets.UTF_8);
        stderr = err.toString(StandardCharsets.UTF_8);
        return status;
    }

    private int run(String... args) {
        return run("", (name, arguments) -> DesktopAutomationResult.success("done", arguments).toJson(), args);
    }

    @Test public void helpNeedsNeitherServerNorTermux() {
        assertEquals(0, run("--help"));
        assertTrue(stdout.contains("get_state"));
        assertTrue(stdout.contains("terminal.open"));
        assertEquals(0, calls.get());
    }

    @Test public void everyCommandHasGeneratedHelpAndSchema() throws Exception {
        final var catalog = AutomationCommandCatalog.create();
        for (int i = 0; i < catalog.length(); i++) {
            final String command = catalog.getJSONObject(i).getString("name");
            assertEquals(command, 0, run(command, "--help"));
            assertTrue(stdout.contains(command));
            assertEquals(command, 0, run(command, "--schema"));
            assertEquals(command, new JSONObject(stdout).getString("name"));
        }
        assertEquals(0, calls.get());
    }

    @Test public void parsesPrimitiveOptionsWithoutChangingTheirNames() {
        assertEquals(0, run("list_tasks", "--displayId", "0", "--query=a b", "--limit", "10"));
        assertEquals("list_tasks", name);
        assertEquals(0, arguments.optInt("displayId"));
        assertEquals("a b", arguments.optString("query"));
        assertEquals(10, arguments.optInt("limit"));
    }

    @Test public void booleansCanBeBareOrExplicit() {
        assertEquals(0, run("get_self_test", "--includeReport"));
        assertTrue(arguments.optBoolean("includeReport"));
        assertEquals(0, run("get_self_test", "--includeReport=false"));
        assertFalse(arguments.optBoolean("includeReport"));
        assertEquals(0, run("get_self_test", "--includeReport", "false"));
        assertFalse(arguments.optBoolean("includeReport"));
    }

    @Test public void parsesNestedJsonWithoutFlattening() {
        assertEquals(0, run("wait_for_state", "--condition", "task_bounds", "--taskId", "4",
                "--bounds", "{\"left\":0,\"top\":1,\"right\":80,\"bottom\":90}"));
        assertEquals(90, arguments.optJSONObject("bounds").optInt("bottom"));
    }

    @Test public void entireObjectCanComeFromStdin() {
        assertEquals(0, run("{\"query\":\"hello\",\"limit\":3}",
                (name, args) -> DesktopAutomationResult.success("done", args).toJson(),
                "list_tasks", "--args", "-"));
        assertEquals("hello", arguments.optString("query"));
    }

    @Test public void dryRunNeverExecutesAnAction() throws Exception {
        assertEquals(0, run("close_desktop", "--dry-run"));
        assertEquals("close_desktop", new JSONObject(stdout).getString("name"));
        assertEquals(0, calls.get());
    }

    @Test public void malformedOrAmbiguousArgumentsAreRejected() {
        for (String[] args : new String[][] {
                {"nonexistent"}, {"get_state", "--unknown", "1"},
                {"list_tasks", "--limit", "not-a-number"}, {"list_tasks", "--limit", "1.5"},
                {"list_tasks", "--query"}, {"list_tasks", "--query", "a", "--query", "b"},
                {"list_tasks", "--args", "{}", "--limit", "1"},
                {"list_tasks", "--args", "{} garbage"}, {"wait_for_state"},
                {"wait_for_state", "--condition", "not-a-condition"},
                {"get_self_test", "--includeReport", "1"}}) {
            assertEquals(java.util.Arrays.toString(args), 2, run(args));
            assertTrue(stdout.isEmpty());
            assertFalse(stderr.isEmpty());
        }
        assertEquals(0, calls.get());
    }

    @Test public void completeOperationFailureIsPreserved() throws Exception {
        assertEquals(1, run("", (name, args) -> DesktopAutomationResult.failure(
                DesktopAutomationErrorCode.HOST_UNAVAILABLE, "service missing", false).toJson(), "get_state"));
        assertEquals(DesktopAutomationErrorCode.HOST_UNAVAILABLE,
                new JSONObject(stdout).getJSONObject("error").getString("code"));
        assertTrue(stderr.isEmpty());
        assertEquals(1, calls.get());
    }

    @Test public void transportFailureIsNotRetried() {
        assertEquals(3, run("", (name, args) -> { throw new java.io.EOFException("connection lost"); }, "close_desktop"));
        assertEquals(1, calls.get());
        assertTrue(stdout.isEmpty());
        assertTrue(stderr.contains("outcome is unknown"));
    }

    @Test public void malformedReplyIsATransportFailureNotAnArgumentError() {
        assertEquals(3, run("", (name, args) -> new JSONObject(), "get_state"));
        assertEquals(1, calls.get());
        assertTrue(stdout.isEmpty());
        assertFalse(stderr.isEmpty());
    }

    @Test public void argumentFileReadFailureDoesNotExecute() throws Exception {
        final var directory = java.nio.file.Files.createTempDirectory("magicdesk-cli-test");
        try {
            assertEquals(2, run("get_state", "--args", "@" + directory.resolve("missing.json")));
            assertEquals(0, calls.get());
            assertTrue(stdout.isEmpty());
            assertFalse(stderr.isEmpty());
        } finally { java.nio.file.Files.delete(directory); }
    }

    @Test public void entireObjectCanComeFromAFile() throws Exception {
        final var file = java.nio.file.Files.createTempFile("magicdesk-cli-test", ".json");
        try {
            java.nio.file.Files.writeString(file, "{\"limit\":3}");
            assertEquals(0, run("list_tasks", "--args", "@" + file));
            assertEquals(3, arguments.optInt("limit"));
        } finally { java.nio.file.Files.delete(file); }
    }

    @Test public void unmatchedWaitRemainsAnUnmatchedWait() throws Exception {
        assertEquals(0, run("", (name, args) -> DesktopAutomationResult.success("wait expired",
                        new JSONObject().put("matched", false).put("waitExpired", true)).toJson(),
                "wait_for_state", "--condition", "desktop_active"));
        assertFalse(new JSONObject(stdout).getJSONObject("data").getBoolean("matched"));
    }

    @Test public void protocolRoundTripsAndRejectsOversizedFrames() throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        AutomationCommandWire.write(out, new JSONObject().put("text", "a\nb"), 100);
        assertEquals("a\nb", AutomationCommandWire.read(new ByteArrayInputStream(out.toByteArray()), 100).getString("text"));
        out.reset();
        new DataOutputStream(out).writeInt(Integer.MAX_VALUE);
        assertThrows(java.io.IOException.class,
                () -> AutomationCommandWire.read(new ByteArrayInputStream(out.toByteArray()), 100));
    }

    @Test public void installedWrapperRequiresOnlyAndroid() {
        assertTrue(CommandShellEnvironment.SCRIPT.contains("exec /system/bin/app_process"));
        assertTrue(CommandShellEnvironment.SCRIPT.contains("\"$@\""));
        assertFalse(CommandShellEnvironment.SCRIPT.contains("python"));
        assertFalse(CommandShellEnvironment.SCRIPT.contains("com.termux"));
        assertFalse(CommandShellEnvironment.SCRIPT.contains("su "));
    }
}
