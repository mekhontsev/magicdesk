package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ShellCommandLineTest {
    @Test
    public void quotesWhitespaceAndApostrophes() {
        assertEquals(
                "'/storage/emulated/0/Dmitry'\"'\"'s app.apk'",
                ShellCommandLine.quote(
                        "/storage/emulated/0/Dmitry's app.apk"));
    }

    @Test
    public void apkInstallCommandKeepsPathAsOneArgument() {
        assertEquals(
                "/system/bin/pm install -r "
                        + "'/data/local/tmp/test app.apk'",
                ShellPackageInstaller.command(
                        "/data/local/tmp/test app.apk"));
    }

    @Test
    public void installerAcceptsOnlyApkFiles() {
        assertTrue(ShellPackageInstaller.supports(file(
                "update.APK", "application/octet-stream", false)));
        assertTrue(ShellPackageInstaller.supports(file(
                "update.bin",
                "application/vnd.android.package-archive",
                false)));
        assertFalse(ShellPackageInstaller.supports(file(
                "folder.apk", "application/octet-stream", true)));
        assertFalse(ShellPackageInstaller.supports(file(
                "notes.txt", "text/plain", false)));
    }

    @Test
    public void shellScriptSeparatesWorkingDirectoryFromCommand() {
        final ShellFileInfo script = file(
                "Dmitry's script.sh", "application/octet-stream", false);

        assertTrue(ShellScriptLauncher.supports(script));
        assertEquals(
                "/system/bin/sh -- '/tmp/Dmitry'\"'\"'s script.sh'",
                ShellScriptLauncher.command(script));
        assertEquals(
                "/tmp",
                ShellScriptLauncher.workingDirectory(script.absolutePath));
        assertFalse(ShellScriptLauncher.supports(file(
                "script.txt", "text/plain", false)));
    }

    private static ShellFileInfo file(
            final String name,
            final String mimeType,
            final boolean directory) {
        return new ShellFileInfo(
                "/tmp/" + name,
                name,
                mimeType,
                "",
                0L,
                0L,
                1L,
                2L,
                2000,
                2000,
                0100644,
                directory,
                false,
                true,
                true,
                false,
                false);
    }
}
