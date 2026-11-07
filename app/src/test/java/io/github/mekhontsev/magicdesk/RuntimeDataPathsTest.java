package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import org.junit.Test;

public final class RuntimeDataPathsTest {
    @Test public void identitiesShareLayoutNotStorage() {
        Path app = Path.of("/data/user/12/io.github.mekhontsev.magicdesk");
        var local = RuntimeDataPaths.selected(1210647, app);
        var shell = RuntimeDataPaths.selected(2000, app);
        var root = RuntimeDataPaths.selected(0, app);
        assertEquals(app, local.root());
        assertEquals(Path.of("/data/local/tmp/magicdesk"), shell.root());
        assertNotEquals(shell.root(), root.root());
        assertEquals("linux", local.root().relativize(local.linux()).toString());
        assertEquals(shell.root().relativize(shell.runtime()), local.root().relativize(local.runtime()));
        var environment = new HashMap<String, String>();
        environment.put("FPATH", "/foreign/functions");
        ShellExecutionEnvironment.apply(environment, 1210647, false, local, "/apk/libmagicdesk_mksh.so");
        assertEquals("u12_a647", environment.get("USER"));
        assertEquals(app.toString(), environment.get("HOME"));
        assertEquals(local.linux().toString(), environment.get("MAGICDESK_GUEST_HOME"));
        assertFalse(environment.containsKey("FPATH"));
        assertThrows(IllegalArgumentException.class, () -> RuntimeDataPaths.privileged(10647));
    }

    @Test public void preparationDoesNotCreateLinuxOrFollowReplacementDirectories() throws Exception {
        Path root = Files.createTempDirectory("runtime-data-");
        var paths = new RuntimeDataPaths(root);
        try {
            paths.prepare();
            assertTrue(Files.isDirectory(paths.tools()));
            assertFalse(Files.exists(paths.linux()));
            Files.delete(paths.temporary());
            Files.createSymbolicLink(paths.temporary(), paths.files());
            assertThrows(java.io.IOException.class, paths::prepare);
        } finally { FileTreeDeletion.deleteIfExists(root); }
    }
}
