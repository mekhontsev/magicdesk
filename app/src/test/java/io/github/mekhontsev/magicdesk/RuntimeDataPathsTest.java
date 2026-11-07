package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.HashMap;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;

public final class RuntimeDataPathsTest {
    @Rule public final TestFileSystem files = TestFileSystem.unix();

    @Test public void appIdentityUsesItsOwnDataDirectory() {
        Path app = files.path("/data/user/12/io.github.mekhontsev.magicdesk");
        var local = RuntimeDataPaths.selected(1210647, app);
        assertEquals(app, local.root());
        assertEquals("linux", local.root().relativize(local.linux()).toString());
        var environment = new HashMap<String, String>();
        environment.put("FPATH", "/foreign/functions");
        ShellExecutionEnvironment.apply(environment, 1210647, false, local, "/apk/libmagicdesk_mksh.so");
        assertEquals("u12_a647", environment.get("USER"));
        assertEquals(app.toString(), environment.get("HOME"));
        assertEquals(local.linux().toString(), environment.get("MAGICDESK_GUEST_HOME"));
        assertFalse(environment.containsKey("FPATH"));
        assertThrows(IllegalArgumentException.class, () -> RuntimeDataPaths.privileged(10647));
    }

    @Test public void privilegedIdentitiesShareLayoutNotStorage() {
        // Fixed Android paths use the default provider; Windows has no Unix root.
        assumeTrue("Android root paths require a Unix host", RuntimeDataPaths.SHELL_ROOT.isAbsolute());
        Path app = Path.of("/data/user/12/io.github.mekhontsev.magicdesk");
        var local = RuntimeDataPaths.selected(1210647, app);
        var shell = RuntimeDataPaths.selected(2000, app);
        var root = RuntimeDataPaths.selected(0, app);
        assertEquals(Path.of("/data/local/tmp/magicdesk"), shell.root());
        assertEquals(shell.root().resolve("root"), root.root());
        assertNotEquals(shell.root(), local.root());
        assertEquals(shell.root().relativize(shell.runtime()), local.root().relativize(local.runtime()));
    }

    @Test public void dataRootMustBeAbsoluteAndIsNormalized() {
        assertThrows(IllegalArgumentException.class, () -> new RuntimeDataPaths(null));
        assertThrows(IllegalArgumentException.class, () -> new RuntimeDataPaths(files.path("relative")));
        assertEquals(files.path("/data/app"), new RuntimeDataPaths(files.path("/data/other/../app")).root());
    }

    @Test public void preparationDoesNotCreateLinuxOrFollowReplacementDirectories() throws Exception {
        Path root = files.root().resolve("runtime-data");
        var paths = new RuntimeDataPaths(root);
        paths.prepare();
        paths.prepare();
        for (Path directory : List.of(root, paths.files(), paths.cache(), paths.temporary(),
                paths.tools(), paths.config(), paths.data(), paths.state(),
                root.resolve("no_backup"), paths.runtime())) {
            assertTrue(Files.isDirectory(directory));
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(directory));
        }
        assertFalse(Files.exists(paths.linux()));
        Files.delete(paths.temporary());
        Path target = files.newDirectory("unrelated");
        var targetPermissions = PosixFilePermissions.fromString("rwxr-xr-x");
        Files.setPosixFilePermissions(target, targetPermissions);
        Files.createSymbolicLink(paths.temporary(), target);
        assertThrows(java.io.IOException.class, paths::prepare);
        assertEquals(targetPermissions, Files.getPosixFilePermissions(target));
        assertEquals(target, Files.readSymbolicLink(paths.temporary()));
    }
}
