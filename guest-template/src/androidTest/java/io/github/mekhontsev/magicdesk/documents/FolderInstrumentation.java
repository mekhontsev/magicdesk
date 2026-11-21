package io.github.mekhontsev.magicdesk.documents;

import android.app.Activity;
import android.app.Instrumentation;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Bundle;
import android.provider.DocumentsContract;
import io.github.mekhontsev.magicdesk.UnixSocketEndpoint;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** Real Android persisted permissions, cross-UID provider, admission and FUSE wire IO. */
public final class FolderInstrumentation extends Instrumentation {
    private Bundle options;
    @Override public void onCreate(Bundle args) { super.onCreate(args); options=args; start(); }
    @Override public void onStart() {
        var report=new Bundle();
        try {
            String outcome=options.containsKey("online") ? io.github.mekhontsev.magicdesk.guestapp.NetworkQualification.verify(
                    getTargetContext(),Boolean.parseBoolean(options.getString("online")),options.getString("rootfs")) : test();
            report.putString("stream","PASS "+outcome+"\n"); finish(Activity.RESULT_OK,report);
        }
        catch (Throwable e) { report.putString("stream",android.util.Log.getStackTraceString(e)); finish(Activity.RESULT_CANCELED,report); }
    }
    private static void check(boolean ok,String message) { if (!ok) throw new AssertionError(message); }
    private static ByteBuffer bytes(int size) { return ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN); }
    private static ByteBuffer call(LocalSocket socket,int op,long ino,byte[] body,int expected) throws Exception {
        var req=bytes(40+body.length); req.putInt(req.capacity()).putInt(op).putLong(71).putLong(ino);
        req.position(40); req.put(body);
        socket.getOutputStream().write(req.array());
        var in=new DataInputStream(socket.getInputStream()); var header=bytes(16); in.readFully(header.array());
        int size=header.getInt(),error=header.getInt();
        check(error==expected && header.getLong()==71 && size>=16 && size<=16400,"op="+op+" errno="+error);
        var reply=bytes(size-16); in.readFully(reply.array()); return reply;
    }
    private static void checkMount(String endpoint,boolean rw) throws Exception {
        try (var s=new LocalSocket()) {
            s.connect(new LocalSocketAddress(endpoint.substring(1))); s.setSoTimeout(5000);
            call(s,26,0,bytes(16).putInt(7).putInt(31).array(),0);
            long node=call(s,1,1,"token\0".getBytes(StandardCharsets.UTF_8),0).getLong();
            if (rw) call(s,14,node,bytes(8).putInt(3).array(),-android.system.OsConstants.EINVAL);
            call(s,14,node,bytes(8).putInt(2).array(),rw ? 0 : -android.system.OsConstants.EROFS);
            long handle=call(s,14,node,bytes(8).putInt(rw ? 2 : 0).array(),0).getLong();
            byte[] body=bytes(40).putLong(handle).putLong(0).putInt(9).array();
            check(new String(call(s,15,node,body,0).array(),StandardCharsets.UTF_8).equals("read-only"),"read token");
            if (rw) {
                var write=bytes(49); write.put(body).put("rewritten".getBytes(StandardCharsets.UTF_8));
                check(call(s,16,node,write.array(),0).getInt()==9,"write count");
                check(new String(call(s,15,node,body,0).array(),StandardCharsets.UTF_8).equals("rewritten"),"read after write");
            }
            call(s,18,node,bytes(24).putLong(handle).array(),0);
            byte[] create=bytes(20).putInt(2).putInt(0644).putInt(0).putInt(0).put(new byte[]{'n','e','w',0}).array();
            var created=call(s,35,1,create,rw ? 0 : -android.system.OsConstants.EROFS);
            if (rw) {
                long ino=created.getLong(),fh=created.getLong(128);
                call(s,18,ino,bytes(24).putLong(fh).array(),0);
                var rename=bytes(18).putLong(1).put("new\0moved\0".getBytes(StandardCharsets.UTF_8));
                call(s,12,1,rename.array(),0);
                check(call(s,1,1,"moved\0".getBytes(StandardCharsets.UTF_8),0).getLong()==ino,"rename retained open node");
                call(s,10,1,"moved\0".getBytes(StandardCharsets.UTF_8),0);
                call(s,1,1,"moved\0".getBytes(StandardCharsets.UTF_8),-android.system.OsConstants.ENOENT);
                long dir=call(s,27,1,bytes(8).array(),0).getLong();
                var entries=call(s,28,1,bytes(40).putLong(dir).putLong(0).putInt(4096).array(),0);
                check(new String(entries.array(),StandardCharsets.ISO_8859_1).contains("token"),"directory entries");
                call(s,29,1,bytes(24).putLong(dir).array(),0);
            }
        }
    }
    private String test() throws Exception {
        var app=getTargetContext();
        var catalog=new FolderMounts(app);
        var read=DocumentsContract.buildTreeDocumentUri(FixtureDocuments.AUTH,"read");
        var write=DocumentsContract.buildTreeDocumentUri(FixtureDocuments.AUTH,"write");
        String mode=options.getString("phase","");
        String glyph=new String(Character.toChars(0x1D11E));
        check(FolderMounts.alias(glyph.repeat(10000)).equals(glyph.repeat(45)),"bounded UTF-8 mount name");
        if (mode.equals("restore")) {
            catalog.releaseOrphans();
            var rows=catalog.list("restart");
            check(rows.size()==1 && catalog.available(rows.get(0)),"durable grant after process restart");
            catalog.forget(rows.get(0));
            return "grant and folder setting survived process restart";
        }
        // Like launching DocumentsUI, wake the freshly installed fixture provider
        // before requesting consent. This call grants no URI or shell authority.
        try (var reply=new android.os.ParcelFileDescriptor.AutoCloseInputStream(getUiAutomation()
                .executeShellCommand("content call --uri content://"+FixtureDocuments.TARGET+".test.grants --method ready"))) {
            check(new String(reply.readAllBytes(),StandardCharsets.UTF_8).contains("Bundle"),"fixture provider not ready");
        }
        app.getContentResolver().call(FixtureDocuments.TARGET+".test.grants","offerTestTrees",null,null);
        if (mode.equals("save")) {
            catalog.remember("restart",read,"Restart",3,false);
            return "saved read-only grant before process exit";
        }
        String rootfs=options.getString("rootfs");
        try {
            var a=catalog.remember("test",read,"Same",3,false);
            var b=catalog.remember("test",write,"Same",3,true);
            check(a.target().equals("/mnt/android/Same") && b.target().equals("/mnt/android/Same (2)"),"unique paths");
            try (var mounted=FolderMountLease.open(app,"test",android.os.Process.myUid(),(name,uid,timeout,receiver) ->
                    new UnixSocketEndpoint(name,uid,timeout,receiver,app.getCacheDir().toPath()))) {
                var args=mounted.arguments(); check(args.size()==6,"two trees");
                checkMount(args.get(1),false); checkMount(args.get(4),true);
            }
            var sibling=catalog.remember("other",write,"Same",3,false);
            catalog.forget(b);
            check(catalog.held(write)==FolderMounts.READ && catalog.available(sibling),"other scope read retained");
            catalog.takeTemporary(read,3); catalog.releaseTemporary(read,3);
            check(catalog.available(a) && catalog.held(read)==FolderMounts.READ,"temporary grant did not revoke folder");
            if (rootfs!=null) {
                catalog.remember("native",read,"Read only",3,false);
                catalog.remember("native",write,"Work",3,true);
                nativeGuest(app,rootfs);
            }
            app.getContentResolver().call(FixtureDocuments.TARGET+".test.grants","revokeReadTree",null,null);
            check(!catalog.available(a),"revoked grant detected");
            try (var mounts=FolderMountLease.open(app,"test",android.os.Process.myUid(),(name,uid,timeout,r) ->
                    new UnixSocketEndpoint(name,uid,timeout,r,app.getCacheDir().toPath())); var s=new LocalSocket()) {
                s.connect(new LocalSocketAddress(mounts.arguments().get(1).substring(1)));
                s.setSoTimeout(5000);
                call(s,26,0,bytes(16).putInt(7).putInt(31).array(),0);
                call(s,1,1,"token\0".getBytes(StandardCharsets.UTF_8),-android.system.OsConstants.EACCES);
            }
        } finally {
            for (String scope : new String[]{"test","other","native"})
                for (var row:catalog.list(scope)) catalog.forget(row);
        }
        check(catalog.held(read)==0 && catalog.held(write)==0,"released last connections");
        return "persisted grants, two mounts, read/write, read-only and forget"+(rootfs==null ? "" : "; native guest IO and umount twice");
    }
    private static void nativeGuest(android.content.Context app,String archive) throws Exception {
        var dir=java.nio.file.Files.createTempDirectory(app.getCacheDir().toPath(),"folder-guest-");
        var command=java.util.List.of(app.getApplicationInfo().nativeLibraryDir+"/libshroot_cli.so","--library",dir.resolve("library").toString());
        try (var mount=FolderMountLease.open(app,"native",android.os.Process.myUid(),(name,uid,timeout,recv) ->
                new UnixSocketEndpoint(name,uid,timeout,recv,app.getCacheDir().toPath()))) {
            var install=new java.util.ArrayList<>(command);
            install.addAll(java.util.List.of("install",archive,"--rootfs","--name","test"));
            run(app,dir,install);
            var guest=new java.util.ArrayList<>(command); guest.addAll(java.util.List.of("exec","test"));
            guest.addAll(mount.arguments()); guest.addAll(java.util.List.of("--","/bin/client-guest","external-folders"));
            run(app,dir,guest); run(app,dir,guest);
        } finally {
            try (var files=java.nio.file.Files.walk(dir)) {
                for (var path:files.sorted(java.util.Comparator.reverseOrder()).toList()) java.nio.file.Files.delete(path);
            }
        }
    }
    private static void run(android.content.Context app,java.nio.file.Path dir,java.util.List<String> args) throws Exception {
        var log=dir.resolve("output").toFile();
        var builder=new ProcessBuilder(args).directory(dir.toFile()).redirectErrorStream(true).redirectOutput(log);
        builder.environment().put("HOME",app.getDataDir().toString());
        builder.environment().put("TMPDIR",dir.toString());
        var p=builder.start();
        try {
            // EVENT_WAIT: guest completion; expiry is a failed acceptance check.
            check(p.waitFor(60,java.util.concurrent.TimeUnit.SECONDS),"guest timeout");
            check(p.exitValue()==0,"guest exit "+p.exitValue()+": "+java.nio.file.Files.readString(log.toPath()));
        } finally { p.destroyForcibly(); }
    }
}
