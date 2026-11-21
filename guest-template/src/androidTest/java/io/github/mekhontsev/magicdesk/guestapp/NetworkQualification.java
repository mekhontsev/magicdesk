package io.github.mekhontsev.magicdesk.guestapp;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import static android.system.OsConstants.*;
import io.github.mekhontsev.magicdesk.GuestNetworkProfile;
import io.github.mekhontsev.magicdesk.StandaloneNetwork;
import java.util.Arrays;

/** Run under the target package UID, on each signed package variant, without contacting a server. */
public final class NetworkQualification {
    public static String verify(Context app,boolean online,String rootfs) throws Exception {
            var info=app.getPackageManager().getPackageInfo(app.getPackageName(),android.content.pm.PackageManager.GET_PERMISSIONS);
            var permissions=Arrays.asList(info.requestedPermissions);
            for (var p:new String[]{"android.permission.INTERNET","android.permission.ACCESS_LOCAL_NETWORK"})
                if (permissions.contains(p)!=online) throw new AssertionError(p);
            if (StandaloneNetwork.allowed(app)!=online) throw new AssertionError("Installed network permission");
            for (int family:new int[]{AF_INET,AF_INET6}) for (int type:new int[]{SOCK_STREAM,SOCK_DGRAM}) {
                try { Os.close(Os.socket(family,type,0)); if (!online) throw new AssertionError("Native socket allowed"); }
                catch (ErrnoException e) { if (online || (e.errno!=EACCES && e.errno!=EPERM)) throw e; }
            }
            var prefs=app.getSharedPreferences("linux-network",0);
            if (online) {
                if (!prefs.edit().putString("profile","bridge\n127.0.0.1:8080:80/tcp\nend\n").commit())
                    throw new AssertionError("Cannot store test profile");
            } else if (!prefs.contains("profile")) throw new AssertionError("Update did not preserve application data");
            if (StandaloneNetwork.read(app).mode()!=(online ? GuestNetworkProfile.Mode.BRIDGE : GuestNetworkProfile.Mode.NONE))
                throw new AssertionError("Launch policy");
            if (rootfs!=null) {
                var library=app.getFilesDir().toPath().resolve("network-test-library");
                var command=java.util.List.of(app.getApplicationInfo().nativeLibraryDir+"/libshroot_cli.so","--library",library.toString());
                if (!java.nio.file.Files.exists(library)) {
                    var install=new java.util.ArrayList<>(command); install.addAll(java.util.List.of("install",rootfs,"--rootfs","--name","probe"));
                    run(app,install,0);
                }
                var guest=new java.util.ArrayList<>(command); guest.addAll(java.util.List.of("exec","probe"));
                guest.addAll(StandaloneNetwork.read(app).arguments()); guest.addAll(java.util.List.of("--","/bin/client-guest","exit42"));
                run(app,guest,42);
            }
            return "manifest, IPv4/IPv6 TCP/UDP sockets and saved launch policy: online="+online+(rootfs!=null ? "; native guest executed" : "");
    }
    private static void run(Context app,java.util.List<String> argv,int expected) throws Exception {
        var log=new java.io.File(app.getCacheDir(),"network-qualification.log");
        var b=new ProcessBuilder(argv).redirectErrorStream(true).redirectOutput(log);
        b.environment().put("HOME",app.getDataDir().toString()); b.environment().put("TMPDIR",app.getCacheDir().toString());
        var child=b.start();
        try {
            // EVENT_WAIT: runtime completion; timeout is a failed test.
            if (!child.waitFor(30,java.util.concurrent.TimeUnit.SECONDS) || child.exitValue()!=expected)
                throw new AssertionError("Guest failed: "+new String(java.nio.file.Files.readAllBytes(log.toPath()), java.nio.charset.StandardCharsets.UTF_8));
        } finally { child.destroyForcibly(); }
    }
}
