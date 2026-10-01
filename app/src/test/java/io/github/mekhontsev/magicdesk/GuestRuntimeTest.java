package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeFalse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class GuestRuntimeTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void launchPlanKeepsHostAndGuestPathsAndArgumentsSeparate() {
        var plan = new GuestLaunchPlan(new GuestEnvironment("/host/a ' b", "/home/shell", ""),
                "/guest/c d", List.of("/bin/sh", "-c", "echo '$HOME'"));
        assertEquals(List.of("magicdesk-guest", "--store", "/host/a ' b", "--home", "/home/shell",
                "--cwd", "/guest/c d", "--", "/bin/sh", "-c", "echo '$HOME'"), plan.arguments());
        GuestLaunchPlan.requireIdentity(2000);
        GuestLaunchPlan.requireIdentity(0);
        for (int uid : List.of(10000, -1)) assertThrows(IllegalStateException.class, () -> GuestLaunchPlan.requireIdentity(uid));
    }

    @Test public void rejectsIncompleteAndAmbiguousPlans() {
        for (String invalid : List.of("", "relative", "/", "/a\0b"))
            assertThrows(IllegalArgumentException.class, () -> new GuestEnvironment(invalid, "/tmp", ""));
        var environment = new GuestEnvironment("/guest/store", "/tmp", "");
        assertThrows(IllegalArgumentException.class, () -> new GuestLaunchPlan(environment, "relative", List.of("/bin/sh")));
        assertThrows(IllegalArgumentException.class, () -> new GuestLaunchPlan(environment, "/", List.of("sh")));
        assertThrows(IllegalArgumentException.class, () -> new GuestLaunchPlan(environment, "/", List.of("/bin/sh", "\0")));
    }

    @Test public void guestUserIsAnExplicitLaunchArgumentNotAnExecutorSwitch() {
        var plan = new GuestLaunchPlan(new GuestEnvironment("/store", "/tmp", " 1000:200 "),
                "/", List.of("/bin/sh"));
        assertEquals(List.of("magicdesk-guest", "--store", "/store", "--home", "/tmp",
                "--cwd", "/", "--user", "1000:200", "--", "/bin/sh"), plan.arguments());
        for (String invalid : List.of("root:bad:group", "-1", "root;id", "root:", "root\nother"))
            assertThrows(IllegalArgumentException.class, () -> new GuestEnvironment("/store", "/tmp", invalid));
    }

    private Path source() throws Exception {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"));
        Path source = temporary.newFolder().toPath();
        for (String name : GuestRuntimeArtifacts.FILES) {
            Path file = Files.writeString(source.resolve(name), "original " + name);
            assertTrue(file.toFile().setExecutable(true));
        }
        return source;
    }

    @Test public void updatingArtifactsNeverReplacesAnActiveVersion() throws Exception {
        Path source = source(), root = temporary.newFolder().toPath();
        Path first = GuestRuntimeArtifacts.prepare(source, root);
        assertEquals(first, GuestRuntimeArtifacts.prepare(source, root));
        Files.writeString(source.resolve(GuestRuntimeArtifacts.FILES.get(0)), "replacement");
        Path second = GuestRuntimeArtifacts.prepare(source, root);
        assertNotEquals(first, second);
        assertTrue(Files.readString(first.resolve(GuestRuntimeArtifacts.FILES.get(0))).startsWith("original "));
        assertEquals("replacement", Files.readString(second.resolve(GuestRuntimeArtifacts.FILES.get(0))));
    }

    @Test public void concurrentPreparationPublishesOneCompleteBundle() throws Exception {
        Path source = source(), root = temporary.newFolder().toPath();
        var executor = Executors.newFixedThreadPool(4);
        try {
            var tasks = java.util.stream.IntStream.range(0, 8)
                    .mapToObj(i -> (java.util.concurrent.Callable<Path>) () -> GuestRuntimeArtifacts.prepare(source, root)).toList();
            var results = executor.invokeAll(tasks);
            Path target = results.get(0).get();
            for (var result : results) assertEquals(target, result.get());
            try (var paths = Files.list(root)) { assertEquals(1, paths.count()); }
        } finally { executor.shutdownNow(); }
    }

    @Test public void corruptBundleIsRejectedWithoutRepairingItUnderRunningProcesses() throws Exception {
        Path source = source(), root = temporary.newFolder().toPath();
        Path target = GuestRuntimeArtifacts.prepare(source, root);
        Path file = target.resolve(GuestRuntimeArtifacts.FILES.get(0));
        assertTrue(file.toFile().setWritable(true));
        Files.writeString(file, "modified");
        assertThrows(IOException.class, () -> GuestRuntimeArtifacts.prepare(source, root));
        assertEquals("modified", Files.readString(file));
    }

    @Test public void rejectsSymlinksAndMissingArtifacts() throws Exception {
        Path source = source(), root = temporary.newFolder().toPath();
        Path file = source.resolve(GuestRuntimeArtifacts.FILES.get(0));
        Files.delete(file);
        assertThrows(IOException.class, () -> GuestRuntimeArtifacts.prepare(source, root));
        Files.createSymbolicLink(file, source.resolve(GuestRuntimeArtifacts.FILES.get(1)));
        assertThrows(IOException.class, () -> GuestRuntimeArtifacts.prepare(source, root));
    }

    @Test public void preparationDoesNotConsumeInheritedGraphicsConnection() {
        assertTrue(GuestRuntimeCommand.SCRIPT.contains("md_guest=$(CLASSPATH="));
        assertTrue(GuestRuntimeCommand.SCRIPT.contains("exec \"$md_guest/libmagicdesk_guest_run.so\" \"$@\""));
        assertFalse(GuestRuntimeCommand.SCRIPT.contains("exec /system/bin/app_process"));
        assertTrue(GuestRuntimeCommand.SCRIPT.contains("--probe >/dev/null || exit $?"));
        assertTrue(GuestRuntimeArtifacts.FILES.contains("libmagicdesk_guest_supervisor.so"));
        assertTrue(GuestRuntimeCommand.SCRIPT.contains("\"$md_guest/libmagicdesk_guest_supervisor.so\" \"$md_guest/libmagicdesk_guest_bootstrap.so\""));
        assertFalse(GuestRuntimeCommand.SCRIPT.contains("--probe) exec \"$md_guest/libmagicdesk_guest_bootstrap.so\""));
    }

    @Test public void imageToolIsStagedWithItsRunnerButOnlyExecutionProbesKernel() {
        assertTrue(GuestRuntimeArtifacts.FILES.contains("libmagicdesk_guest_image.so"));
        String script = GuestRuntimeCommand.SCRIPT;
        int start = script.indexOf("image) shift");
        int end = script.indexOf("--import) exec", start);
        assertTrue(start > 0 && end > start);
        String branch = script.substring(start, end);
        assertTrue(branch.contains("if [ \"${1-}\" = run ]; then"));
        assertTrue(branch.contains("--probe >/dev/null || exit $?"));
        assertTrue(branch.contains("exec \"$md_guest/libmagicdesk_guest_image.so\" \"$@\""));
        assertFalse(branch.contains("su "));
    }

    @Test public void graphicalConnectionBelongsToRecipeNotIdentityOrFileEnvironment() {
        assertTrue(GraphicalConnectionMode.AUTO.namedEndpoint(0, 10001));
        assertTrue(GraphicalConnectionMode.AUTO.namedEndpoint(10001, 10001));
        assertFalse(GraphicalConnectionMode.AUTO.namedEndpoint(2000, 10001));
        assertFalse(GraphicalConnectionMode.INHERITED.namedEndpoint(0, 10001));
        assertFalse(GraphicalConnectionMode.INHERITED.namedEndpoint(2000, 10001));
        assertFalse(GraphicalConnectionMode.ROUTED.namedEndpoint(2000, 10001));
        assertFalse(GraphicalConnectionMode.ROUTED.namedEndpoint(0, 10001));
        assertEquals(GraphicalConnectionMode.ROUTED, GraphicalConnectionMode.parse("routed"));
        for (var protocol : GraphicalProtocol.values())
            assertEquals(GraphicalConnectionMode.ROUTED, new GraphicalLaunchOptions(
                    protocol, false, "", "", "", GraphicalConnectionMode.ROUTED).connectionMode());
        assertThrows(IllegalArgumentException.class, () -> new GraphicalLaunchOptions(
                GraphicalProtocol.X11, false, "", "", "", GraphicalConnectionMode.INHERITED));
        assertNull(DesktopEntryFile.parse("[Desktop Entry]\nType=Application\nName=Invalid\nExec=app\n"
                + "X-MagicDesk-GraphicsConnection=inherited\n"));
    }

    @Test public void routedInvocationPreservesLiteralGuestArgumentsAndDynamicEndpoint() throws Exception {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"));
        Path tools = temporary.newFolder().toPath();
        Path stub = Files.writeString(tools.resolve("magicdesk-guest"), "#!/bin/sh\nprintf '%s\\n' \"$@\"\n");
        assertTrue(stub.toFile().setExecutable(true));
        var plan = new GuestLaunchPlan(new GuestEnvironment("/host/a ' b", "/home/shell", ""), "/guest dir",
                List.of("/bin/sh", "-c", "echo '$HOME'"));
        for (var protocol : GraphicalProtocol.values()) {
            var builder = new ProcessBuilder("sh", "-c", GuestGraphicalConnection.invocation(plan, protocol));
            builder.environment().put("PATH", tools + ":" + System.getenv("PATH"));
            builder.environment().put("MAGICDESK_GRAPHICS_ENDPOINT", "opaque ' endpoint");
            builder.environment().put("DISPLAY", ":37");
            var process = builder.start();
            String[] arguments = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\n");
            assertEquals(0, process.waitFor());
            var expected = new java.util.ArrayList<>(plan.launcherArguments().subList(1, plan.launcherArguments().size()));
            String source = protocol == GraphicalProtocol.X11 ? "/tmp/.X11-unix/X37" : GuestGraphicalConnection.WAYLAND_PATH;
            expected.addAll(List.of("--socket-path", source, "opaque ' endpoint"));
            if (protocol == GraphicalProtocol.X11) expected.addAll(List.of("--socket-abstract", source, "opaque ' endpoint"));
            expected.add("--"); expected.addAll(plan.command());
            assertEquals(expected, List.of(arguments));
        }
    }
}
