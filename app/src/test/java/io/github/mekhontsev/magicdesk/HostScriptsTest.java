package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class HostScriptsTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    private Path shell;

    @Before
    public void requirePosixHost() {
        // These are POSIX host scripts, not Android device tests.
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"));
        final String prefix = System.getenv("PREFIX");
        shell = prefix == null ? Path.of("/bin/sh") : Path.of(prefix, "bin", "sh");
        assertTrue(Files.isExecutable(shell));
    }

    @Test
    public void smokeReportsSuccessForPassAndWarningsOnly() throws Exception {
        assertEquals(0, smoke("PASS").exitCode);
        assertEquals(0, smoke("WARN").exitCode);
    }

    @Test
    public void smokeRejectsFailedCancelledAndUnknownOutcomes() throws Exception {
        assertEquals(1, smoke("FAIL").exitCode);
        assertEquals(1, smoke("CANCELLED").exitCode);
        assertEquals(1, smoke("UNKNOWN").exitCode);
    }

    @Test
    public void coreApkRequiresEveryShellHelper() throws Exception {
        final var helpers = List.of("uinput_bridge", "keyboard_bridge", "pty_bridge");
        for (final String omitted : helpers) {
            final Path apk = coreApk(helpers.stream()
                    .filter(helper -> !helper.equals(omitted)).toList());
            final var result = run("verify-apks.sh", Map.of(), apk.toString());
            assertEquals(result.output, 1, result.exitCode);
            assertTrue(result.output.contains("libmagicdesk_" + omitted + ".so"));
        }
        final var result = run("verify-apks.sh", Map.of(), coreApk(helpers).toString());
        assertEquals(result.output, 0, result.exitCode);
    }

    @Test
    public void kernelAddonRejectsPtyHelperToo() throws Exception {
        final Path addon = temporary.newFile().toPath();
        try (var zip = new ZipOutputStream(Files.newOutputStream(addon))) {
            zip.putNextEntry(new ZipEntry("res/raw/dp_mode_reset.ko"));
            Files.copy(Path.of("..", "kernel-fixes", "src", "main",
                    "res", "raw", "dp_mode_reset.ko"), zip);
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("lib/arm64-v8a/libmagicdesk_pty_bridge.so"));
            zip.closeEntry();
        }
        final var result = run("verify-apks.sh", Map.of(),
                coreApk(List.of("uinput_bridge", "keyboard_bridge", "pty_bridge"))
                        .toString(), addon.toString());
        assertEquals(result.output, 1, result.exitCode);
        assertTrue(result.output.contains("must not contain a shell helper"));
    }

    @Test
    public void nextVersionRejectsMalformedAndOutOfRangeCodesWithoutWriting() throws Exception {
        for (final String code : List.of("0", "0194", "-1", "194\ninvalid",
                "2147483648", "999999999999999999999999999999999")) {
            final Path project = versionProject();
            final Path properties = project.resolve("gradle.properties");
            final String original = Files.readString(properties);
            final var result = nextVersion(project, code);
            assertEquals(result.output, 2, result.exitCode);
            assertEquals(original, Files.readString(properties));
        }
    }

    @Test
    public void nextVersionAcceptsPositiveIntAndPreservesUnrelatedProperties() throws Exception {
        for (final String code : List.of("194", "2147483647")) {
            final Path project = versionProject();
            final var result = nextVersion(project, code);
            assertEquals(result.output, 0, result.exitCode);
            assertEquals("# retained\nmagicDeskVersionName=1.9.4\n"
                            + "magicDeskVersionCode=" + code + "\nandroid.useAndroidX=true\n",
                    Files.readString(project.resolve("gradle.properties")));
        }
    }

    @Test
    public void nativeVerifierRunsEveryFixtureAndCleansTemporaryOutput() throws Exception {
        assumeTrue("Linux".equals(System.getProperty("os.name")));
        final var fixture = nativeVerifierFixture();
        final var result = nativeVerifier(fixture, "", false);
        assertEquals(result.output, 0, result.exitCode);
        final var expected = new ArrayList<>(List.of(
                "magicdesk_pty_working_directory_test:cwd",
                "magicdesk_pty_lifecycle_test:pressure",
                "magicdesk_pty_lifecycle_test:fragmented",
                "magicdesk_pty_lifecycle_test:metadata",
                "magicdesk_pty_lifecycle_test:hup",
                "magicdesk_pty_lifecycle_test:signal",
                "magicdesk_pty_lifecycle_test:oversized"));
        for (final String bridge : List.of("mouse", "keyboard")) {
            for (final String mode : List.of("lost", "multiple", "discard", "failure",
                    "held", "shortcuts")) {
                expected.add("magicdesk_input_" + bridge + "_test:" + mode);
            }
        }
        expected.add("magicdesk_input_keyboard_test:paused");
        expected.add("magicdesk_input_keyboard_test:queue-cleanup");
        expected.add("magicdesk_input_mouse_test:secondary-native");
        assertEquals(expected, Files.readAllLines(fixture.log));
        assertTrue(result.output.contains("verified (22 runs)"));
        assertEmptyDirectory(fixture.output);
    }

    @Test
    public void nativeVerifierPropagatesCompileAndFixtureFailuresAndCleansOutput() throws Exception {
        assumeTrue("Linux".equals(System.getProperty("os.name")));
        final var compileFailure = nativeVerifierFixture();
        final var compileResult = nativeVerifier(compileFailure, "", true);
        assertEquals(compileResult.output, 17, compileResult.exitCode);
        assertTrue(!Files.exists(compileFailure.log));
        assertEmptyDirectory(compileFailure.output);

        final var testFailure = nativeVerifierFixture();
        final var testResult = nativeVerifier(testFailure, "fragmented", false);
        assertEquals(testResult.output, 9, testResult.exitCode);
        assertEquals(3, Files.readAllLines(testFailure.log).size());
        assertTrue(!testResult.output.contains("verified (23 runs)"));
        assertEmptyDirectory(testFailure.output);

        final var inputFailure = nativeVerifierFixture();
        final var inputResult = nativeVerifier(inputFailure, "paused", false);
        assertEquals(inputResult.output, 9, inputResult.exitCode);
        assertEquals(20, Files.readAllLines(inputFailure.log).size());
        assertEmptyDirectory(inputFailure.output);
    }

    private NativeVerifierFixture nativeVerifierFixture() throws Exception {
        final Path project = temporary.newFolder().toPath().resolve("project with spaces");
        Files.createDirectories(project.resolve("scripts"));
        Files.copy(Path.of("..", "scripts", "verify-native.sh"),
                project.resolve("scripts/verify-native.sh"));
        final Path compiler = project.resolve("fake cc");
        Files.writeString(compiler, "#!" + shell + "\n"
                + "if [ \"$NATIVE_COMPILE_FAIL\" = true ]; then exit 17; fi\n"
                + "output=\n"
                + "while [ \"$#\" -gt 0 ]; do\n"
                + "  if [ \"$1\" = -o ]; then shift; output=$1; fi\n"
                + "  shift\n"
                + "done\n"
                + "test -n \"$output\" || exit 18\n"
                + "printf '#!%s\\n' \"$NATIVE_TEST_SHELL\" > \"$output\"\n"
                + "printf '%s\\n' 'printf \"%s:%s\\n\" \"${0##*/}\" \"${1:-cwd}\" >> \"$NATIVE_RUN_LOG\"' "
                + "'if [ \"${1:-cwd}\" = \"$NATIVE_FAIL_MODE\" ]; then exit 9; fi' >> \"$output\"\n"
                + "chmod +x \"$output\"\n");
        assertTrue(compiler.toFile().setExecutable(true));
        return new NativeVerifierFixture(project, compiler,
                Files.createDirectory(project.resolve("temporary output")),
                project.resolve("runs.log"));
    }

    private BoundedProcessRunner.Result nativeVerifier(final NativeVerifierFixture fixture,
            final String failedMode, final boolean compileFailure) throws Exception {
        final var builder = new ProcessBuilder(shell.toString(),
                fixture.project.resolve("scripts/verify-native.sh").toString())
                .redirectErrorStream(true);
        builder.environment().putAll(Map.of(
                "CC", fixture.compiler.toString(), "TMPDIR", fixture.output.toString(),
                "NATIVE_RUN_LOG", fixture.log.toString(),
                "NATIVE_FAIL_MODE", failedMode, "NATIVE_TEST_SHELL", shell.toString(),
                "NATIVE_COMPILE_FAIL", Boolean.toString(compileFailure)));
        return BoundedProcessRunner.run(builder.start(), 3_000L, 8_192);
    }

    private static void assertEmptyDirectory(final Path directory) throws Exception {
        try (var entries = Files.list(directory)) {
            assertEquals(0, entries.count());
        }
    }

    private record NativeVerifierFixture(Path project, Path compiler, Path output, Path log) {
    }

    private Path versionProject() throws Exception {
        final Path project = temporary.newFolder().toPath();
        Files.createDirectory(project.resolve("scripts"));
        Files.copy(Path.of("..", "scripts", "start-next-version.sh"),
                project.resolve("scripts/start-next-version.sh"));
        Files.writeString(project.resolve("gradle.properties"),
                "# retained\nmagicDeskVersionName=1.9.3\n"
                        + "magicDeskVersionCode=193\nandroid.useAndroidX=true\n");
        return project;
    }

    private BoundedProcessRunner.Result nextVersion(final Path project, final String code)
            throws Exception {
        return BoundedProcessRunner.run(new ProcessBuilder(shell.toString(),
                project.resolve("scripts/start-next-version.sh").toString(),
                "1.9.4", code).redirectErrorStream(true).start(), 3_000L, 8_192);
    }

    private BoundedProcessRunner.Result smoke(final String outcome) throws Exception {
        final Path adb = temporary.newFile().toPath();
        Files.writeString(adb, "#!" + shell + "\n"
                + "case \"$*\" in\n"
                + "  *' cat '*) printf 'Outcome: %s\\n' \"$TEST_OUTCOME\" ;;\n"
                + "esac\n");
        assertTrue(adb.toFile().setExecutable(true));
        return run("smoke-simulated-display.sh",
                Map.of("ADB", adb.toString(), "TEST_OUTCOME", outcome));
    }

    private Path coreApk(final List<String> helpers) throws Exception {
        final Path apk = temporary.newFile().toPath();
        try (var zip = new ZipOutputStream(Files.newOutputStream(apk))) {
            for (final String helper : helpers) {
                zip.putNextEntry(new ZipEntry("lib/arm64-v8a/libmagicdesk_"
                        + helper + ".so"));
                zip.closeEntry();
            }
        }
        return apk;
    }

    private BoundedProcessRunner.Result run(
            final String name, final Map<String, String> environment,
            final String... arguments) throws Exception {
        final var command = new ArrayList<>(List.of(shell.toString(),
                Path.of("..", "scripts", name).toString()));
        command.addAll(List.of(arguments));
        final var builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().putAll(environment);
        return BoundedProcessRunner.run(builder.start(), 3_000L, 8_192);
    }
}
