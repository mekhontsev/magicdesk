package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public final class FrameworkWindowingIsolationTest {
    private static final Path MAIN_JAVA = Path.of("src", "main", "java");
    private static final String COMPAT_SOURCE =
            "io/github/mekhontsev/magicdesk/FrameworkWindowingCompat.java";
    private static final String WINDOWING_API_SOURCE =
            "io/github/mekhontsev/magicdesk/FrameworkWindowingApi.java";
    private static final String TASK_API_SOURCE =
            "io/github/mekhontsev/magicdesk/HiddenTaskApi.java";
    private static final String TASK_SNAPSHOT_SOURCE =
            "io/github/mekhontsev/magicdesk/FrameworkTaskSnapshotSource.java";
    private static final String INPUT_SNAPSHOT_SOURCE =
            "io/github/mekhontsev/magicdesk/FrameworkInputSnapshotSource.java";
    private static final String INPUT_OBSERVATION_SOURCE =
            "io/github/mekhontsev/magicdesk/"
                    + "FrameworkInputWindowObservationSource.java";
    private static final String BOUNDED_AWAITER_SOURCE =
            "io/github/mekhontsev/magicdesk/BoundedStateAwaiter.java";
    private static final String RUNTIME_DELAYS_SOURCE =
            "io/github/mekhontsev/magicdesk/RuntimeDelays.java";
    private static final String EVENT_WAITS_SOURCE =
            "io/github/mekhontsev/magicdesk/EventDrivenWaits.java";
    private static final String[] HIDDEN_API_NAMES = {
        "\"requestedVisibleTypes\"",
        "\"setExcludeImeInsets\"",
        "\"addInsetsSource\"",
        "\"removeInsetsSource\"",
        "\"mExcludeInsetsTypes\""
    };

    @Test
    public void hiddenFrameworkVariantsStayInCompatLayer()
            throws IOException {
        final List<String> violations = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(MAIN_JAVA)) {
            for (final Path source : paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList()) {
                final String relative = MAIN_JAVA.relativize(source)
                        .toString().replace('\\', '/');
                if (COMPAT_SOURCE.equals(relative)) {
                    continue;
                }
                final String contents = Files.readString(
                        source, StandardCharsets.UTF_8);
                for (final String name : HIDDEN_API_NAMES) {
                    if (contents.contains(name)) {
                        violations.add(relative + ": " + name);
                    }
                }
            }
        }
        assertTrue("Hidden windowing API variants outside compat layer: "
                + violations, violations.isEmpty());
    }

    @Test
    public void windowContainerReflectionStaysInPrimitiveApi()
            throws IOException {
        assertNoSourceTokensOutside(
                "Window-container reflection outside primitive API",
                List.of(COMPAT_SOURCE, WINDOWING_API_SOURCE),
                "android.window.WindowContainerTransaction",
                "android.window.WindowContainerToken");
    }

    @Test
    public void rawTaskMemberAccessStaysInTaskSources()
            throws IOException {
        assertNoSourceTokensOutside(
                "Raw task member access outside task API",
                List.of(TASK_API_SOURCE, TASK_SNAPSHOT_SOURCE),
                "HiddenTaskApi.getField(",
                "HiddenTaskApi.getIntField(",
                "HiddenTaskApi.getBooleanField(",
                "HiddenTaskApi.getWindowConfigurationValue(");
    }

    @Test
    public void inputDumpingStaysInSnapshotSource() throws IOException {
        assertNoSourceTokensOutside(
                "Direct input dump outside snapshot source",
                List.of(INPUT_SNAPSHOT_SOURCE),
                "\"/system/bin/dumpsys\", \"input\"",
                "\"/system/bin/dumpsys input\"");
    }

    @Test
    public void inputWindowCallbacksStayInObservationSource()
            throws IOException {
        assertNoSourceTokensOutside(
                "Raw input-window callbacks outside observation source",
                List.of(INPUT_OBSERVATION_SOURCE),
                "WindowInfosListener",
                "InputWindowHandle");
    }

    @Test
    public void sleepsStayInExplicitTimingBoundaries() throws IOException {
        assertNoSourceTokensOutside(
                "Direct sleep outside timing boundaries",
                List.of(BOUNDED_AWAITER_SOURCE, RUNTIME_DELAYS_SOURCE),
                "Thread.sleep(",
                "SystemClock.sleep(",
                "android.os.SystemClock.sleep(");
    }

    @Test
    public void monitorWaitsStayInEventBoundary() throws IOException {
        assertNoSourceTokensOutside(
                "Direct monitor wait outside event boundary",
                List.of(EVENT_WAITS_SOURCE),
                ".wait(");
    }

    @Test
    public void textTaskQueriesStayInSelfTestDiagnostics()
            throws IOException {
        final List<String> violations = new ArrayList<>();
        visitMainSources((relative, contents) -> {
            if (contents.contains("activity stack list")
                    && !relative.contains("DesktopSelfTest")) {
                violations.add(relative);
            }
        });
        assertTrue("Production text task queries: " + violations,
                violations.isEmpty());
    }

    private static void assertNoSourceTokensOutside(
            final String message,
            final List<String> allowedSources,
            final String... tokens) throws IOException {
        final List<String> violations = new ArrayList<>();
        visitMainSources((relative, contents) -> {
            if (allowedSources.contains(relative)) {
                return;
            }
            for (final String token : tokens) {
                if (contents.contains(token)) {
                    violations.add(relative + ": " + token);
                }
            }
        });
        assertTrue(message + ": " + violations, violations.isEmpty());
    }

    private static void visitMainSources(final SourceVisitor visitor)
            throws IOException {
        try (Stream<Path> paths = Files.walk(MAIN_JAVA)) {
            for (final Path source : paths
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList()) {
                final String relative = MAIN_JAVA.relativize(source)
                        .toString().replace('\\', '/');
                visitor.visit(relative, Files.readString(
                        source, StandardCharsets.UTF_8));
            }
        }
    }

    private interface SourceVisitor {
        void visit(String relative, String contents);
    }
}
