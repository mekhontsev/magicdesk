package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public final class LauncherIdentityTest {
    @Test public void executorAndProfileChangesFailClosedWithoutRequiringShellForTermux() throws Exception {
        RuntimeSourceFixture.verify("""
                static class Context { }
                static class android { static class system { static class Os {
                    static int getuid() { return 10647; }
                } } }
                static class AppProfile {
                    long serialNumber;
                    static AppProfile profile = new AppProfile();
                    static AppProfile current(Context c) { return profile; }
                }
                static class LauncherExportStore {
                    record Target(long profile, String termuxPackage, int termuxUid, int shellUid) { }
                }
                static class TermuxIntegration {
                    static class Endpoint {
                        boolean available = true;
                        String packageName = "com.termux";
                        int uid = 10123;
                        void requireAvailable() { if (!available) throw new IllegalStateException(); }
                    }
                    static Endpoint endpoint = new Endpoint();
                    static Endpoint inspect(Context c) { return endpoint; }
                }
                static class ShellAccess {
                    static boolean ready;
                    static class Snapshot { int uid = 2000; }
                    static Snapshot snapshot = new Snapshot();
                    static boolean isReady() { return ready; }
                    static Snapshot currentSnapshot() { return snapshot; }
                }
                static void rejected(Context c, LauncherExportStore.Target t) {
                    try { requireIdentity(c, t); throw new AssertionError("identity change accepted"); }
                    catch (IllegalStateException | SecurityException expected) { }
                }
                public static void verify() {
                    var c = new Context();
                    var user = new LauncherExportStore.Target(0, "", -1, 10647);
                    requireIdentity(c, user);
                    rejected(c, new LauncherExportStore.Target(0, "", -1, 10648));
                    var termux = new LauncherExportStore.Target(0, "com.termux", 10123, -1);
                    requireIdentity(c, termux);
                    TermuxIntegration.endpoint.uid++; rejected(c, termux);
                    TermuxIntegration.endpoint.uid--;
                    TermuxIntegration.endpoint.packageName = "com.other"; rejected(c, termux);
                    TermuxIntegration.endpoint.packageName = "com.termux";
                    TermuxIntegration.endpoint.available = false; rejected(c, termux);
                    TermuxIntegration.endpoint.available = true;
                    AppProfile.profile.serialNumber = 1; rejected(c, termux);
                    AppProfile.profile.serialNumber = 0;
                    var shell = new LauncherExportStore.Target(0, "", -1, 2000);
                    rejected(c, shell);
                    ShellAccess.ready = true; requireIdentity(c, shell);
                    ShellAccess.snapshot.uid = 0; rejected(c, shell);
                    requireIdentity(c, user);
                    requireIdentity(c, termux);
                }
                """ + RuntimeSourceFixture.methods("LauncherExports", "requireIdentity"));
    }

    @Test public void exportedAdapterAuthenticatesCallerAndUsesSharedLaunchPath() throws Exception {
        String activity = Files.readString(Path.of(RuntimeSourceFixture.MAIN, "LauncherEntryActivity.java"));
        assertTrue(activity.contains("getCallingPackage()"));
        assertFalse(activity.contains("getIntent()"));
        assertTrue(activity.contains("LauncherExports.authenticate"));
        assertTrue(activity.contains("LauncherExports.resolve"));
        assertTrue(activity.contains("ApplicationEntryLauncher.launch"));
        assertTrue(activity.contains("ToolLaunchTarget.resolve"));
        assertTrue(activity.contains("DesktopDisplayCatalog.require(displayId, null).uniqueId"));
        assertTrue(activity.contains("launch(this, request, target, uniqueId,"));
        String auth = RuntimeSourceFixture.methods("LauncherExports", "authenticate", "signedByUs");
        assertTrue(auth.contains("LauncherExportStore.callerId"));
        assertTrue(auth.contains("hasSigningCertificate"));
        String resolve = RuntimeSourceFixture.methods("LauncherExports", "resolve");
        assertTrue(resolve.contains("source.load(context)"));
        assertEquals(2, resolve.split("requireIdentity", -1).length - 1);
        String ui = Files.readString(Path.of(RuntimeSourceFixture.MAIN, "LauncherExportActivity.java"));
        assertTrue(ui.contains("Intent.ACTION_INSTALL_PACKAGE"));
        assertTrue(ui.contains("Intent.ACTION_UNINSTALL_PACKAGE"));
        assertFalse(ui.contains("ShellPackageInstaller"));
        assertFalse(ui.contains("Mcp"));
    }
}
