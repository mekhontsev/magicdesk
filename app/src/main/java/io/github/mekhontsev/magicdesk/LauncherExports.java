package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.pm.PackageManager;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Export admission and live source resolution, shared by installation and authenticated activation. */
final class LauncherExports {
    static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> new Thread(r, "MagicDeskLaunchers"));

    static LauncherExportStore store(Context context) {
        return new LauncherExportStore(context.getFilesDir().toPath().resolve("launcher-exports"));
    }

    static LauncherExportStore.Target capture(Context context, DesktopApplicationRepository.Entry entry, String termuxPackage) {
        if (!entry.shortcut.hasExecLaunch() || entry.desktopFilePath.isBlank())
            throw new IllegalArgumentException("A saved Linux application is required");
        var catalog = ApplicationCatalog.get(context).snapshot();
        String path = entry.desktopFilePath;
        String source = catalog.guest().entries().stream().anyMatch(item -> item.desktopFilePath.equals(path)) ? "guest"
                : catalog.termux().entries().stream().anyMatch(item -> item.desktopFilePath.equals(path)) ? "termux" : "desktop";
        String endpoint = "";
        int termuxUid = -1;
        if (entry.shortcut.execBackend == DesktopExecBackend.TERMUX) {
            var termux = TermuxIntegration.inspect(context);
            termux.requireAvailable();
            if (termuxPackage != null && !termuxPackage.isEmpty() && !termux.packageName.equals(termuxPackage))
                throw new IllegalStateException("The selected Termux identity has changed");
            endpoint = termux.packageName; termuxUid = termux.uid;
        }
        int shellUid = source.equals("termux") ? -1
                : source.equals("guest") && entry.shortcut.execBackend == DesktopExecBackend.APP ? android.system.Os.getuid()
                : ShellAccess.currentSnapshot().isReady() ? ShellAccess.currentSnapshot().uid : -1;
        return new LauncherExportStore.Target(source, path, endpoint, termuxUid, shellUid,
                AppProfile.current(context).serialNumber);
    }

    static void requireIdentity(Context context, LauncherExportStore.Target target) {
        if (target.profile() != AppProfile.current(context).serialNumber)
            throw new SecurityException("Launcher belongs to another Android profile");
        if (!target.termuxPackage().isEmpty()) {
            var endpoint = TermuxIntegration.inspect(context);
            endpoint.requireAvailable();
            if (!target.termuxPackage().equals(endpoint.packageName) || target.termuxUid() != endpoint.uid)
                throw new IllegalStateException("The launcher Termux identity is unavailable");
        }
        if (target.shellUid() >= 10000 ? target.shellUid() != android.system.Os.getuid()
                : target.shellUid() >= 0 && (!ShellAccess.isReady() || ShellAccess.currentSnapshot().uid != target.shellUid()))
            throw new IllegalStateException("The launcher shell identity is unavailable");
    }

    static DesktopApplicationShortcut resolve(Context context, LauncherExportStore.Target target) throws IOException {
        requireIdentity(context, target);
        var source = DesktopEntrySource.parse(target.source());
        DesktopEntry entry = source == DesktopEntrySource.DESKTOP ? source.read(context, target.path())
                : DesktopEntrySource.find(source.load(context), target.path()).shortcut;
        requireIdentity(context, target);
        if (!(entry instanceof DesktopApplicationShortcut shortcut) || !shortcut.hasExecLaunch()
                || (shortcut.execBackend == DesktopExecBackend.TERMUX) != !target.termuxPackage().isEmpty()
                || target.source().equals("guest") && (shortcut.execBackend == DesktopExecBackend.APP) != (target.shellUid() >= 10000))
            throw new IOException("The exported Linux application is no longer available");
        return shortcut;
    }

    static LauncherExportStore.Entry authenticate(Context context, String caller) throws IOException {
        var entry = store(context).get(LauncherExportStore.callerId(context.getPackageName(), caller));
        if (!signedByUs(context, entry)) throw new SecurityException("Launcher signature is not authorized");
        return entry;
    }

    static boolean signedByUs(Context context, LauncherExportStore.Entry entry) {
        return context.getPackageManager().hasSigningCertificate(entry.packageName(context.getPackageName()),
                java.util.HexFormat.of().parseHex(entry.certificate()), PackageManager.CERT_INPUT_SHA256);
    }

    static boolean installed(Context context, LauncherExportStore.Entry entry) {
        try {
            context.getPackageManager().getPackageInfo(entry.packageName(context.getPackageName()), PackageManager.PackageInfoFlags.of(0));
            return true;
        } catch (PackageManager.NameNotFoundException error) { return false; }
    }
    private LauncherExports() { }
}
