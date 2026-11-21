package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import java.nio.file.Files;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class LauncherExportStoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private LauncherExportStore store() { return new LauncherExportStore(temporary.getRoot().toPath().resolve("exports")); }
    private static LauncherExportStore.Target target() {
        return new LauncherExportStore.Target("termux", "/data/data/com.termux/files/usr/share/applications/gimp.desktop",
                "com.termux", 10601, -1, 0);
    }
    private static final String CERT = "0123456789abcdef".repeat(4);

    @Test public void identitySurvivesRenameAndIsIndependentOfHistory() throws Exception {
        var original = store().put(target(), "GIMP", CERT);
        var renamed = store().put(target(), "Image editor", CERT);
        assertEquals(original.id(), renamed.id());
        assertEquals("Image editor", store().get(original.id()).label());
        assertEquals(1, store().read().size());
        assertEquals(original.id(), LauncherExportStore.callerId("example.host", original.packageName("example.host")));
        var json = Files.readString(temporary.getRoot().toPath().resolve("exports/" + original.id() + ".json"));
        assertFalse(json.contains("exec"));
        assertFalse(json.contains("displayId"));
    }

    @Test public void profileExecutorAndSourceArePartOfIdentity() throws Exception {
        var a = store().put(target(), "One", CERT);
        var b = store().put(new LauncherExportStore.Target("termux", target().path(), "com.termux", 20601, -1, 1), "Two", CERT);
        var c = store().put(new LauncherExportStore.Target("guest", "/magicdesk-guest/abc/applications/gimp.desktop", "", -1, 2000, 0), "Three", CERT);
        var d = store().put(new LauncherExportStore.Target("guest", c.target().path(), "", -1, 0, 0), "Four", CERT);
        assertEquals(4, java.util.Set.of(a.id(), b.id(), c.id(), d.id()).size());
        assertEquals(4, store().read().size());
        store().remove(b.id()); assertEquals(3, store().read().size());
        assertThrows(java.io.IOException.class, () -> store().get(b.id()));
    }

    @Test public void missingAndMalformedCallersNeverSelectAnEntry() throws Exception {
        for (String caller : new String[]{null, "example.host", "other.host.launcher.e" + "a".repeat(32),
                "example.host.launcher.e../private", "example.host.launcher.e" + "a".repeat(33)})
            assertThrows(RuntimeException.class, () -> LauncherExportStore.callerId("example.host", caller));
        assertThrows(IllegalArgumentException.class, () -> store().get("../private"));
        assertThrows(java.io.IOException.class, () -> store().get("a".repeat(32)));
        assertFalse(Files.exists(temporary.getRoot().toPath().resolve("exports")));
    }

    @Test public void replacementKeyCannotSilentlyTakeOverExistingLauncher() throws Exception {
        var entry = store().put(target(), "GIMP", CERT);
        assertThrows(java.io.IOException.class, () -> store().put(target(), "GIMP", "f".repeat(64)));
        assertEquals(CERT, store().get(entry.id()).certificate());
    }

    @Test public void invalidSourceIdentityRejectedBeforeStorage() {
        assertThrows(IllegalArgumentException.class, () -> new LauncherExportStore.Target("termux", "/a", "", -1, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new LauncherExportStore.Target("guest", "/a", "", -1, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new LauncherExportStore.Target("guest", "relative", "", -1, 2000, 0));
        assertThrows(IllegalArgumentException.class, () -> new LauncherExportStore.Target("unknown", "/a", "", -1, 2000, 0));
        assertThrows(IllegalArgumentException.class, () -> new LauncherExportStore.Target("environment", "/a", "com.termux", 10601, 2000, 0));
    }

    @Test public void privateEnvironmentCannotActAsADelegatedLauncher() throws Exception {
        var shell=store().put(new LauncherExportStore.Target("environment","/linux/store","",-1,2000,0),"Alpine",CERT);
        var user=store().put(new LauncherExportStore.Target("environment","/linux/store","",-1,10647,0),"Alpine",CERT);
        assertNotEquals(shell.id(),user.id());
        assertTrue(user.packageName("example.host").startsWith("example.host.linux.e"));
        assertThrows(SecurityException.class,() -> LauncherExportStore.callerId("example.host",user.packageName("example.host")));
        assertEquals(user,store().get(user.id()));
    }

    @Test public void mismatchedRecordIdAndOversizedRecordFailClosed() throws Exception {
        var a = store().put(target(), "GIMP", CERT);
        var path = temporary.getRoot().toPath().resolve("exports/" + a.id() + ".json");
        Files.writeString(path, Files.readString(path).replace(a.id(), "b".repeat(32)));
        assertThrows(java.io.IOException.class, () -> store().get(a.id()));
        Files.writeString(path, "x".repeat(32769));
        assertThrows(java.io.IOException.class, () -> store().get(a.id()));
    }
}
