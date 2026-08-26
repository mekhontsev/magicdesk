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

public final class WindowTransitionOwnershipTest {
    private static final Path MAIN_JAVA = Path.of("src", "main", "java");
    private static final String EXECUTOR =
            "ShellWindowTransitionExecutor.java";

    @Test
    public void onlyTransitionExecutorUsesRawTransitionApis()
            throws IOException {
        final List<String> violations = new ArrayList<>();
        for (final Path source : productionSources()) {
            if (EXECUTOR.equals(source.getFileName().toString())) {
                continue;
            }
            final String contents = read(source);
            if (contents.contains("\"startNewTransition\"")
                    || contents.contains("\"startTransition\"")
                    || contents.contains("\"finishTransition\"")) {
                violations.add(source.toString());
            }
        }
        assertTrue("Raw transition APIs outside executor: "
                + violations, violations.isEmpty());
    }

    @Test
    public void onlyTransitionExecutorAppliesWindowTransactions()
            throws IOException {
        final List<String> violations = new ArrayList<>();
        for (final Path source : productionSources()) {
            if (EXECUTOR.equals(source.getFileName().toString())) {
                continue;
            }
            if (read(source).contains("SyncWindowContainerTransaction.")) {
                violations.add(source.toString());
            }
        }
        assertTrue("Direct window transactions outside executor: "
                + violations, violations.isEmpty());
    }

    private static List<Path> productionSources() throws IOException {
        final List<Path> sources = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(MAIN_JAVA)) {
            paths.filter(path -> path.toString().endsWith(".java"))
                    .forEach(sources::add);
        }
        return sources;
    }

    private static String read(final Path source) throws IOException {
        return Files.readString(source, StandardCharsets.UTF_8);
    }
}
