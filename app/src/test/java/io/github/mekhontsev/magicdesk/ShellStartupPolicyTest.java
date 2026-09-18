package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public final class ShellStartupPolicyTest {
    @Test public void policyLimitsEitherRootTransportWithoutElevatingShell() {
        final var root = new RuntimeLimits.Values(RuntimeLimits.Access.ROOT, true, true);
        final var shell = RuntimeLimits.DEFAULT;
        final var app = new RuntimeLimits.Values(RuntimeLimits.Access.APP_ONLY, true, true);
        assertEquals(0, root.targetUid(0));
        assertEquals(2000, root.targetUid(2000));
        assertEquals(2000, shell.targetUid(0));
        assertEquals(2000, shell.targetUid(2000));
        root.verifyServiceUid(0);
        root.verifyServiceUid(2000);
        shell.verifyServiceUid(2000);
        org.junit.Assert.assertThrows(SecurityException.class, () -> shell.verifyServiceUid(0));
        for (int uid : new int[]{0, 2000}) {
            org.junit.Assert.assertThrows(SecurityException.class, () -> app.targetUid(uid));
            org.junit.Assert.assertThrows(SecurityException.class, () -> app.verifyServiceUid(uid));
        }
        for (var policy : new RuntimeLimits.Values[]{root, shell, app}) {
            for (int uid : new int[]{-1, 1000, 10001}) {
                org.junit.Assert.assertThrows(SecurityException.class, () -> policy.targetUid(uid));
                org.junit.Assert.assertThrows(SecurityException.class, () -> policy.verifyServiceUid(uid));
            }
        }
    }

    @Test public void bootstrapMustReportOnePositivePid() throws Exception {
        RuntimeSourceFixture.verify(RuntimeSourceFixture.methods("ShellProcessLauncher", "parsePid") + """
                public static void verify() throws Exception {
                    check(parsePid("notice\\nMAGICDESK_PID=1234\\n") == 1234, "valid PID not parsed");
                    for (String output : new String[]{"", "1234", "MAGICDESK_PID=", "MAGICDESK_PID=0",
                            "MAGICDESK_PID=-1", "MAGICDESK_PID=x", "MAGICDESK_PID=999999999999",
                            "MAGICDESK_PID=12\\nMAGICDESK_PID=13"}) {
                        try { parsePid(output); throw new AssertionError("invalid PID accepted: " + output); }
                        catch (IOException expected) { }
                    }
                }
                """);
    }

    @Test public void shizukuApiHasOneOwner() throws Exception {
        try (var sources = Files.walk(Path.of(RuntimeSourceFixture.MAIN))) {
            final var owners = sources.filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> {
                        try { return Files.readString(path).contains("import rikka.shizuku.Shizuku;"); }
                        catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
                    }).map(path -> path.getFileName().toString()).toList();
            assertEquals(java.util.List.of("ShizukuServiceLauncher.java"), owners);
        }
    }

    @Test public void standaloneStartupHasNoDesktopOwnerAndAuthenticatesItsCaller() throws Exception {
        final String process = Files.readString(Path.of(RuntimeSourceFixture.MAIN + "ShellServiceProcess.java"));
        assertTrue(process.indexOf("ShellServiceStartup.verifyIdentity(") < process.indexOf("Looper.prepareMainLooper("));
        assertTrue(process.contains("Binder.getCallingUid() != mClientUid"));
        assertTrue(process.contains("owner.linkToDeath(destroy::run, 0)"));
        assertFalse(process.contains("DesktopRuntime"));
        final String provider = Files.readString(Path.of(RuntimeSourceFixture.MAIN + "ShellServiceProvider.java"));
        assertTrue(provider.contains("Binder.getCallingPid()"));
        assertTrue(provider.contains("Binder.getCallingUid()"));
        final String manifest = Files.readString(Path.of("src/main/AndroidManifest.xml"));
        assertTrue(manifest.contains("android:name=\".ShellServiceProvider\""));
        assertTrue(manifest.contains("android:permission=\"android.permission.INTERACT_ACROSS_USERS_FULL\""));
    }
}
