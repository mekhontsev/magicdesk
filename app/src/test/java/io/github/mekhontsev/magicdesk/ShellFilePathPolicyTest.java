package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ShellFilePathPolicyTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void requiresAndNormalizesAbsolutePath() throws IOException {
        final Path root = temporary.newFolder("root").toPath();

        assertEquals(
                root.resolve("child"),
                ShellFilePathPolicy.absolute(
                        root.resolve("nested/../child").toString()));
        assertThrows(IllegalArgumentException.class,
                () -> ShellFilePathPolicy.absolute("relative/path"));
    }

    @Test
    public void normalizesAndroidShellPathIndependentlyOfBuildHost() {
        assertEquals(
                "/storage/emulated/0/Documents",
                ShellFilePathPolicy.normalizeShellAbsolute(
                        "/storage/emulated/0/Desktop/../Documents"));
        assertEquals(
                "/storage/emulated/0",
                ShellFilePathPolicy.shellParent(
                        "/storage/emulated/0/Documents"));
        assertThrows(IllegalArgumentException.class,
                () -> ShellFilePathPolicy.normalizeShellAbsolute("Desktop"));
    }

    @Test
    public void rootCannotBeMutableEntry() {
        assertThrows(IllegalArgumentException.class,
                () -> ShellFilePathPolicy.mutableEntry("/"));
    }

    @Test
    public void rejectsSameAndRecursiveDestination() throws IOException {
        final Path source = temporary.newFolder("source").toPath();

        assertThrows(IllegalArgumentException.class,
                () -> ShellFilePathPolicy.rejectRecursiveTarget(
                        source, source));
        assertThrows(IllegalArgumentException.class,
                () -> ShellFilePathPolicy.rejectRecursiveTarget(
                        source, source.resolve("nested/copy")));
    }

    @Test
    public void acceptsSiblingCopyName() throws IOException {
        final Path source = temporary.newFile("sample.txt").toPath();

        ShellFilePathPolicy.rejectRecursiveTarget(
                source, source.resolveSibling("sample (2).txt"));
    }

    @Test
    public void symlinkCanBeAddressedWithoutFollowingIt() throws IOException {
        final Path target = temporary.newFile("target").toPath();
        final Path link = target.resolveSibling("link");
        Files.createSymbolicLink(link, target);

        assertEquals(link, ShellFilePathPolicy.existing(link.toString()));
    }
}
