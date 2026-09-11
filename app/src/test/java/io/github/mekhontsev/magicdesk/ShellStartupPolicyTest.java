package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public final class ShellStartupPolicyTest {
    @Test public void policyLimitsEitherRootTransportWithoutElevatingShell() throws Exception {
        RuntimeSourceFixture.verify("""
                static boolean forced;
                static boolean forceShell() { return forced; }
                static class ShellAccess {
                    static boolean isSupportedServiceUid(int uid) { return uid == 0 || uid == 2000; }
                }
                """ + RuntimeSourceFixture.methods("ShellPrivilegePolicy", "targetUid", "verifyServiceUid") + """
                public static void verify() {
                    check(targetUid(0) == 0 && targetUid(2000) == 2000, "identity changed without opt-in");
                    verifyServiceUid(0);
                    verifyServiceUid(2000);
                    forced = true;
                    check(targetUid(0) == 2000 && targetUid(2000) == 2000, "shell restriction not applied");
                    verifyServiceUid(2000);
                    try { verifyServiceUid(0); throw new AssertionError("root accepted under shell policy"); }
                    catch (SecurityException expected) { }
                    for (boolean force : new boolean[]{true, false}) {
                        forced = force;
                        check(targetUid(10001) == 10001, "unknown UID elevated");
                        for (int uid : new int[]{-1, 1000, 10001}) {
                            try { verifyServiceUid(uid); throw new AssertionError("unsupported UID accepted"); }
                            catch (SecurityException expected) { }
                        }
                    }
                }
                """);
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
