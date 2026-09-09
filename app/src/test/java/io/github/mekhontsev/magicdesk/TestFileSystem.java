package io.github.mekhontsev.magicdesk;

import com.google.common.jimfs.Configuration;
import com.google.common.jimfs.Jimfs;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.rules.ExternalResource;

/** Stable inode identities and symlinks, independent of the build host's filesystem. */
final class TestFileSystem extends ExternalResource {
    private FileSystem fileSystem;
    private Path root;

    @Override
    protected void before() throws IOException {
        fileSystem = Jimfs.newFileSystem(Configuration.forCurrentPlatform());
        root = Files.createDirectory(fileSystem.getPath("test").toAbsolutePath());
    }

    @Override
    protected void after() {
        try {
            fileSystem.close();
        } catch (IOException failure) {
            throw new AssertionError("could not close test filesystem", failure);
        }
    }

    Path root() { return root; }

    Path path(final String value) { return fileSystem.getPath(value); }

    Path newDirectory(final String name) throws IOException {
        return Files.createDirectory(root.resolve(name));
    }
}
