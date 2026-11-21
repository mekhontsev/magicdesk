package io.github.mekhontsev.magicdesk.guestapp;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import io.github.mekhontsev.magicdesk.ILinuxConnection;
import io.github.mekhontsev.magicdesk.LinuxConnectionContract;
import io.github.mekhontsev.magicdesk.UnixEndpointBroker;
import java.io.Closeable;
import java.io.IOException;
import java.util.List;

/** Client-owned optional lease; losing its host does not stop this APK's Linux. */
final class HostConnection implements Closeable {
    private final UnixEndpointBroker endpoints;
    private final String hostPackage;
    private final Runnable ended;
    private final java.nio.file.Path temporary;
    private final IBinder.DeathRecipient died = this::close;
    private boolean closed;
    private ILinuxConnection connection;
    private List<String> arguments = List.of();
    private boolean scoped,internet;
    private java.nio.file.Path authority;

    HostConnection(Context context, Runnable ended) throws Exception {
        this.ended = ended;
        try (var input = context.getAssets().open("environment.json")) {
            byte[] bytes = input.readNBytes(65537);
            if (bytes.length > 65536) throw new IOException("Linux application configuration is too large");
            var config = new org.json.JSONObject(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            if (config.getInt("format") != 1) throw new IOException("Unsupported Linux application configuration");
            hostPackage = config.getString("hostPackage");
        }
        int uid = context.getPackageManager().getPackageUid(hostPackage, 0);
        temporary=java.nio.file.Files.createDirectories(context.getCacheDir().toPath().resolve("tmp"));
        endpoints = new UnixEndpointBroker(uid, temporary, this::close);
    }

    Intent request(boolean privateNetwork) {
        Bundle extras = new Bundle(); extras.putBinder("endpoints", endpoints);
        return new Intent(LinuxConnectionContract.ACTION).setPackage(hostPackage).putExtras(extras)
                .putExtra("sessionServices",true).putExtra("privateNetwork",privateNetwork);
    }

    void accept(Intent result) throws RemoteException, IOException {
        Bundle extras = result == null ? null : result.getExtras();
        IBinder binder = extras == null ? null : extras.getBinder("connection");
        if (binder == null) throw new IOException("No Linux connection returned");
        ILinuxConnection incoming = ILinuxConnection.Stub.asInterface(binder);
        synchronized (this) {
            if (closed) { incoming.close(); throw new IOException("Linux connection cancelled"); }
            if (connection != null) throw new IOException("Linux connection already delivered");
            connection = incoming;
            binder.linkToDeath(died, 0);
        }
        Bundle description = incoming.describe();
        var values = new java.util.HashMap<String, String>();
        values.put("pipewire", description.getString("pipewire"));
        values.put("pulse", description.getString("pulse"));
        for (String key : List.of("graphicsProtocol", "graphicsEndpoint", "display","automationEndpoint","automationToken","internetEndpoint"))
            if (description.containsKey(key)) values.put(key,description.getString(key));
        synchronized (this) {
            if (closed) throw new IOException("Linux connection cancelled");
            if ("x11".equals(values.get("graphicsProtocol"))) {
                byte[] auth=description.getByteArray("xauthority");
                if (auth==null || auth.length==0 || auth.length>4096) throw new IOException("Invalid Xauthority grant");
                authority=java.nio.file.Files.createTempDirectory(temporary,"xauth-");
                java.nio.file.Files.write(authority.resolve("Xauthority"),auth);
            }
            arguments = LinuxConnectionContract.launchArguments(description.getInt("format"), values,
                    authority==null ? null : authority.toString());
            internet=values.containsKey("internetEndpoint");
            scoped=internet || values.containsKey("automationEndpoint");
        }
    }

    synchronized List<String> arguments() { return closed ? List.of() : arguments; }
    synchronized boolean ready() { return !closed && !arguments.isEmpty(); }
    synchronized boolean sessionScoped() { return !closed && scoped; }
    synchronized boolean internet() { return !closed && internet; }

    @Override public void close() {
        ILinuxConnection previous;
        java.nio.file.Path auth;
        synchronized (this) {
            if (closed) return;
            closed = true; arguments = List.of(); previous = connection; connection = null;
            auth=authority; authority=null;
        }
        if (previous != null) {
            previous.asBinder().unlinkToDeath(died, 0);
            try { previous.close(); } catch (RemoteException ignored) { }
        }
        endpoints.release(); ended.run();
        if (auth!=null) try {
            java.nio.file.Files.deleteIfExists(auth.resolve("Xauthority"));
            java.nio.file.Files.deleteIfExists(auth);
        }
        catch (IOException error) { android.util.Log.w("LinuxConnection","Cannot remove private authority",error); }
    }
}
