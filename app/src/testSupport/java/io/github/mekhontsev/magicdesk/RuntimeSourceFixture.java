package io.github.mekhontsev.magicdesk;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.TryTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/** Executes production method bodies with host-only dependencies, not Android services. */
final class RuntimeSourceFixture {
    static final String MAIN = "src/main/java/io/github/mekhontsev/magicdesk/";

    private RuntimeSourceFixture() {
    }

    private static Path sourcePath(String file) {
        Path path = Path.of(file);
        return path.isAbsolute() ? path : Path.of(MAIN + file + ".java");
    }

    static String methods(final String file, final String... names) throws IOException {
        return methods(file, false, names);
    }

    static String topLevelMethods(final String file, final String... names) throws IOException {
        return methods(file, true, names);
    }

    private static String methods(final String file, boolean topLevel, final String... names) throws IOException {
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        final List<String> requested = Arrays.asList(names);
        final StringBuilder methods = new StringBuilder();
        final Path path = sourcePath(file);
        final String source = Files.readString(path);
        try (StandardJavaFileManager files = compiler.getStandardFileManager(
                null, null, StandardCharsets.UTF_8)) {
            final JavacTask task = (JavacTask) compiler.getTask(null, files, null,
                    List.of("-proc:none"), null,
                    files.getJavaFileObjects(path.toFile()));
            final var positions = Trees.instance(task).getSourcePositions();
            for (final CompilationUnitTree unit : task.parse()) {
                new TreeScanner<Void, Void>() {
                    @Override public Void visitClass(final ClassTree node, final Void unused) {
                        return !topLevel || node.getSimpleName().contentEquals(file)
                                ? super.visitClass(node, unused) : null;
                    }
                    @Override public Void visitMethod(final MethodTree method, final Void unused) {
                        if (method.getBody() != null && requested.contains(method.getName().toString())) {
                            methods.append(standaloneMethod(source.substring(
                                    (int) positions.getStartPosition(unit, method),
                                    (int) positions.getEndPosition(unit, method))))
                                    .append('\n');
                        }
                        return super.visitMethod(method, unused);
                    }
                }.scan(unit, null);
            }
        }
        return methods.toString();
    }

    static String standaloneMethod(final String source) {
        return source.replace("\r\n", "\n").replaceAll("@Override\\s+", "");
    }

    static String nestedClass(final String file, final String name) throws IOException {
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        final Path path = Path.of(MAIN + file + ".java");
        final String source = Files.readString(path);
        final StringBuilder result = new StringBuilder();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            final JavacTask task = (JavacTask) compiler.getTask(null, files, null, List.of("-proc:none"), null,
                    files.getJavaFileObjects(path.toFile()));
            final var positions = Trees.instance(task).getSourcePositions();
            for (final CompilationUnitTree unit : task.parse()) {
                new TreeScanner<Void, Void>() {
                    @Override public Void visitClass(ClassTree node, Void unused) {
                        if (node.getSimpleName().contentEquals(name)) {
                            result.append(source, (int) positions.getStartPosition(unit, node),
                                    (int) positions.getEndPosition(unit, node));
                            return null;
                        }
                        return super.visitClass(node, unused);
                    }
                }.scan(unit, null);
            }
        }
        if (result.isEmpty()) throw new IllegalArgumentException("missing class " + file + "." + name);
        return result.toString();
    }

    static void verify(final String members) throws Exception {
        verify("io.github.mekhontsev.magicdesk", members, new String[0]);
    }

    static String finallyBlock(final String file, final String methodName) throws IOException {
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        final StringBuilder result = new StringBuilder();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(
                null, null, StandardCharsets.UTF_8)) {
            final JavacTask task = (JavacTask) compiler.getTask(null, files, null,
                    List.of("-proc:none"), null,
                    files.getJavaFileObjects(Path.of(MAIN + file + ".java").toFile()));
            for (final CompilationUnitTree unit : task.parse()) {
                new TreeScanner<Void, Void>() {
                    @Override public Void visitMethod(final MethodTree method, final Void unused) {
                        return methodName.equals(method.getName().toString())
                                ? super.visitMethod(method, unused) : null;
                    }
                    @Override public Void visitTry(final TryTree statement, final Void unused) {
                        if (statement.getFinallyBlock() != null) {
                            result.append(statement.getFinallyBlock());
                        }
                        return null;
                    }
                }.scan(unit, null);
            }
        }
        if (result.length() == 0) {
            throw new IllegalArgumentException("missing finally block: " + file + "." + methodName);
        }
        return result.toString();
    }

    static void verify(final String packageName, final String members,
            final String... additionalSources) throws Exception {
        final Path directory = Files.createTempDirectory("magicdesk-runtime-fixture-");
        try {
            final Path source = directory.resolve("Fixture.java");
            Files.writeString(source, "package " + packageName + ";\n"
                    + "import java.io.*; import java.nio.file.*; import java.util.*;\n"
                    + "import java.util.concurrent.*; import java.lang.reflect.*;\n"
                    + "public class Fixture {\n"
                    + "static void check(boolean ok, String message) {"
                    + "if (!ok) throw new AssertionError(message); }\n"
                    + members + "\n}\n");
            final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            final List<String> arguments = new ArrayList<>(List.of(
                    "-proc:none", "--release", "17", "-encoding", "UTF-8",
                    "-d", directory.toString(),
                    source.toString()));
            for (final String file : additionalSources) {
                arguments.add(sourcePath(file).toString());
            }
            final ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
            if (compiler.run(null, diagnostics, diagnostics,
                    arguments.toArray(new String[0])) != 0) {
                throw new AssertionError("host fixture did not compile:\n"
                        + diagnostics.toString(StandardCharsets.UTF_8));
            }
            try (URLClassLoader loader = new URLClassLoader(
                    new java.net.URL[] {directory.toUri().toURL()},
                    ClassLoader.getPlatformClassLoader())) {
                try {
                    loader.loadClass(packageName + ".Fixture").getMethod("verify").invoke(null);
                } catch (InvocationTargetException error) {
                    final Throwable cause = error.getCause();
                    if (cause instanceof Exception) {
                        throw (Exception) cause;
                    }
                    if (cause instanceof Error) {
                        throw (Error) cause;
                    }
                    throw error;
                }
            }
        } finally {
            try (Stream<Path> paths = Files.walk(directory)) {
                for (final Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
    }
}
