package io.github.mekhontsev.magicdesk;

import android.app.*;
import android.content.*;
import android.os.*;
import android.system.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Installed only by the two-package qualification fixture; neither component is in MagicDesk's manifest. */
public final class CapsuleQualification extends Instrumentation {
    private static final String HOST="io.github.mekhontsev.magicdesk.capsulecheck", PEER=HOST+".peer";
    public static final class Host extends Service {
        private final OperationResources resources=new OperationResources();
        private HandlerThread worker;
        @Override public IBinder onBind(Intent ignored) {
            worker=new HandlerThread("Capsule fixture host"); worker.start();
            return new Messenger(new Handler(worker.getLooper(),message->{
                Bundle response=new Bundle();
                try {
                    int uid=getPackageManager().getPackageUid(PEER,0);
                    if (message.sendingUid!=uid || getPackageManager().checkSignatures(getPackageName(),PEER)!=0)
                        throw new SecurityException("Wrong fixture owner");
                    if (message.what==1 || message.what==2) {
                        var endpoints=IExecutorUnixEndpoints.Stub.asInterface(message.getData().getBinder("endpoints"));
                        response.putBinder("connection",LinuxConnections.acquire(this,uid,endpoints,null,false,
                                message.what==1,message.what==2,PEER,resources));
                    } else {
                        var sessions=LinuxSessionCommands.execute("linux.list",new org.json.JSONObject()).getJSONArray("sessions");
                        if (message.what==3) {
                            if (sessions.length()!=1) throw new AssertionError("Expected one capsule in linux.list: "+sessions);
                            var info=sessions.getJSONObject(0);
                            if (info.getInt("executorUid")!=uid || !PEER.equals(info.getString("environment"))) throw new AssertionError(info);
                            var bus=LinuxAutomationLaunch.target(info.getString("sessionId")).bus();
                            var answer=bus.call(2,"org.freedesktop.DBus","/org/freedesktop/DBus","org.freedesktop.DBus",
                                    "ListNames",new org.json.JSONArray(),10000);
                            response.putString("accessibility",answer.toString());
                        } else if (sessions.length()!=0) throw new AssertionError("Retained revoked handle");
                    }
                } catch (Throwable error) { response.putString("error",android.util.Log.getStackTraceString(error)); }
                try { var reply=Message.obtain(); reply.setData(response); message.replyTo.send(reply); }
                catch (RemoteException ignoredPeer) { }
                return true;
            })).getBinder();
        }
        @Override public void onDestroy() { resources.close(); if (worker!=null) worker.quitSafely(); super.onDestroy(); }
    }
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        var result=new Bundle();
        try { verify(); result.putString("result","PASS offline UID, apk add, independent MCP/AT-SPI bus, revocation"); }
        catch (Throwable error) { result.putString("error",android.util.Log.getStackTraceString(error)); }
        finish(result.containsKey("error") ? Activity.RESULT_CANCELED : Activity.RESULT_OK,result);
    }
    private Messenger host;
    private final android.os.Handler main=new android.os.Handler(Looper.getMainLooper());
    private Bundle request(int op,UnixEndpointBroker broker) throws Exception {
        var completed=new CompletableFuture<Bundle>();
        var message=Message.obtain(); message.what=op;
        if (broker!=null) message.getData().putBinder("endpoints",broker);
        message.replyTo=new Messenger(new Handler(main.getLooper(),reply->{ completed.complete(reply.getData()); return true; }));
        host.send(message);
        // EVENT_WAIT: fixture host finishes the exact Binder request or the test fails.
        var response=completed.get(20,TimeUnit.SECONDS);
        if (response.containsKey("error")) throw new IOException(response.getString("error"));
        return response;
    }
    private void verify() throws Exception {
        var app=getTargetContext();
        if (!PEER.equals(app.getPackageName())) throw new AssertionError("Use the offline test APK");
        try { Os.close(Os.socket(OsConstants.AF_INET,OsConstants.SOCK_STREAM,0)); throw new AssertionError("Guest has internet permission"); }
        catch (ErrnoException denied) { if (denied.errno!=OsConstants.EACCES && denied.errno!=OsConstants.EPERM) throw denied; }
        var ready=new CompletableFuture<Messenger>();
        var binding=new ServiceConnection() {
            public void onServiceConnected(ComponentName c,IBinder b) { ready.complete(new Messenger(b)); }
            public void onServiceDisconnected(ComponentName c) { }
        };
        if (!app.bindService(new Intent().setComponent(new ComponentName(HOST,Host.class.getName())),binding,Context.BIND_AUTO_CREATE))
            throw new IOException("Cannot bind fixture host");
        Path data=app.getFilesDir().toPath(), store=data.resolve("library");
        String nativeDir=app.getApplicationInfo().nativeLibraryDir;
        try {
            // EVENT_WAIT: test-only peer service connection, expiry fails the test.
            host=ready.get(10,TimeUnit.SECONDS);
            var cli=List.of(nativeDir+"/libshroot_cli.so","--library",store.toString());
            if (!Files.exists(store)) {
                var rootfs=data.resolve("rootfs.tar.gz");
                try (var in=app.getAssets().open("rootfs.tar.gz")) { Files.copy(in,rootfs,StandardCopyOption.REPLACE_EXISTING); }
                var argv=new ArrayList<>(cli); argv.addAll(List.of("install",rootfs.toString(),"--rootfs","--name","guest"));
                run(argv,data);
            }
            for (int op:new int[]{1,2}) {
                var broker=new UnixEndpointBroker(app.getPackageManager().getPackageUid(HOST,0),data,()->{});
                try {
                    var lease=ILinuxConnection.Stub.asInterface(request(op,broker).getBinder("connection"));
                    try {
                        var description=lease.describe();
                        var opts=new HashMap<String,String>();
                        for (var k:List.of("internetEndpoint","automationToken","automationEndpoint"))
                            if (description.containsKey(k)) opts.put(k,description.getString(k));
                        var argv=new ArrayList<>(cli); argv.addAll(List.of("exec","guest","--network","none","--bind-ro-create",
                                nativeDir,LinuxConnectionContract.HELPERS));
                        argv.addAll(LinuxConnectionContract.launchArguments(description.getInt("format"),opts));
                        argv.addAll(List.of("--","/bin/sh","-c","exec "+LinuxConnectionContract.SESSION_HELPER+" --connected-login /bin/sh -c \"$1\"","fixture",
                                op==1 ? "apk add --no-cache dbus at-spi2-core gsettings-desktop-schemas" : "echo ready; read reply"));
                        if (op==1) run(argv,data);
                        else {
                            var p=process(argv,data).start();
                            try (var input=new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                                var line=CompletableFuture.supplyAsync(()->input.lines().findFirst().orElse(""));
                                // EVENT_WAIT: guest announces an authenticated bus or startup fails.
                                if (!line.get(30,TimeUnit.SECONDS).equals("ready")) throw new IOException("Guest MCP startup: "+line.get());
                                if (!request(3,null).getString("accessibility").contains("org.freedesktop.DBus"))
                                    throw new AssertionError("No accessibility bus");
                                lease.close(); request(4,null);
                                p.getOutputStream().close();
                            } finally { p.destroyForcibly(); }
                        }
                    } finally { lease.close(); }
                } finally { broker.release(); }
            }
        } finally { app.unbindService(binding); }
    }
    private static ProcessBuilder process(List<String> argv,Path dir) {
        var builder=new ProcessBuilder(argv).directory(dir.toFile()).redirectErrorStream(true);
        builder.environment().put("HOME",dir.toString()); builder.environment().put("TMPDIR",dir.toString()); return builder;
    }
    private static void run(List<String> argv,Path dir) throws Exception {
        Path log=dir.resolve("fixture.log");
        var p=process(argv,dir).redirectOutput(log.toFile()).start();
        try {
            // EVENT_WAIT: bounded package transfer and command completion; never infer success from elapsed time.
            if (!p.waitFor(120,TimeUnit.SECONDS) || p.exitValue()!=0) throw new IOException(Files.readString(log));
        } finally { p.destroyForcibly(); }
    }
}
