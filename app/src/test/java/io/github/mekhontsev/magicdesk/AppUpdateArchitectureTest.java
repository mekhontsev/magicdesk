package io.github.mekhontsev.magicdesk;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;
import static org.junit.Assert.*;

public final class AppUpdateArchitectureTest {
    private String source(String name) throws Exception {
        return Files.readString(Path.of("src/main/java/io/github/mekhontsev/magicdesk/" + name + ".java"));
    }

    @Test public void onlyTheUpdaterSurvivesTheApplication() throws Exception {
        final String launch = source("ShellServiceLauncher");
        assertTrue(launch.contains("COMMAND(ShellCommandService.class, \"command\", false)"));
        assertTrue(launch.contains("UPDATE(ShellAppUpdateService.class, \"app_update\", true)"));
        assertTrue(source("ShizukuServiceLauncher").contains(".daemon(service.independent)"));
        assertTrue(source("ShellServiceProcess").contains("if (!kind.independent)"));
        assertTrue(source("ShellServiceProcess").contains("owner.linkToDeath(destroy::run, 0)"));
        assertFalse(source("ShellCommandService").contains("ShellAppUpdate.commit("));
        final String worker = source("ShellAppUpdateService");
        assertTrue(worker.contains("FrameworkPackageInstallerApi.resultCallback("));
        assertTrue(worker.contains("AppUpdateResumeActivity.class.getName()"));
        assertTrue(worker.contains("FrameworkUserApi.startShellActivity("));
        assertFalse(worker.contains(".startActivity("));
        assertTrue(worker.contains("System.exit(0)"));
        assertTrue(source("AppUpdateResumeActivity").contains("MagicDeskRuntime.startAutomation(this)"));
    }

    @Test public void shellInstallerExplicitlyReplacesTheExistingPackage() throws Exception {
        assertTrue(source("ShellAppUpdate").contains("FrameworkPackageInstallerApi.replacementParams("));
        final String framework = source("FrameworkPackageInstallerApi");
        assertTrue(framework.contains("INSTALL_REPLACE_EXISTING"));
        assertTrue(framework.contains("flags.getInt(params) | replace"));
        assertFalse(framework.contains("INSTALL_ALLOW_DOWNGRADE"));
    }

    @Test public void installerRestartUsesShellIdentityAndTheRequestedProfile() throws Exception {
        final String framework = source("FrameworkUserApi");
        assertTrue(framework.contains("\"startActivityAsUser\""));
        assertTrue(framework.contains(".invoke(service, null, \"com.android.shell\""));
        assertTrue(framework.contains("-1, 0, null, null, userId)"));
        assertTrue(framework.contains("\"isStartResultSuccessful\""));
    }

    @Test public void runtimeEntryIsProtected() throws Exception {
        final String manifest = Files.readString(Path.of("src/main/AndroidManifest.xml"));
        final String declaration = manifest.substring(manifest.indexOf("android:name=\".AppUpdateResumeActivity\""))
                .split("/>", 2)[0];
        assertTrue(declaration.contains("android:permission=\"android.permission.INSTALL_PACKAGES\""));
        assertTrue(declaration.contains("@android:style/Theme.NoDisplay"));
        final String service = manifest.substring(manifest.indexOf("android:name=\".MagicDeskRuntimeService\""))
                .split("</service>", 2)[0];
        assertTrue(service.contains("android:exported=\"false\""));
    }
}
