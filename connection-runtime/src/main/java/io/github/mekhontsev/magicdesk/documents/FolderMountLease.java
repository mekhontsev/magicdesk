package io.github.mekhontsev.magicdesk.documents;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import android.system.Os;
import android.system.OsConstants;
import io.github.mekhontsev.magicdesk.ExecutorSocketEndpoint;
import java.io.Closeable;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** Same executor-local admission for the standalone APK, User and Shell. */
public final class FolderMountLease implements Closeable {
    private final List<ExecutorSocketEndpoint> listeners = new ArrayList<>();
    private final HashMap<ParcelFileDescriptor,android.os.CancellationSignal> clients = new HashMap<>();
    private final List<String> arguments = new ArrayList<>();
    private boolean closed;
    private volatile boolean healthy=true;

    public static FolderMountLease open(Context context, String scope, int uid, ExecutorSocketEndpoint.Factory factory) throws IOException {
        var lease = new FolderMountLease();
        var catalog = new FolderMounts(context);
        try {
            for (var row : catalog.list(scope)) {
                var app=context.getApplicationContext();
                var endpoint = new ExecutorSocketEndpoint(uid, 0, factory,
                        fd -> lease.accept(app,catalog,row,fd),
                        error -> { lease.healthy=false; android.util.Log.w("AndroidFolders","Folder endpoint stopped", error); });
                lease.listeners.add(endpoint);
                lease.arguments.addAll(List.of(row.writable() ? "--bind-fuse" : "--bind-fuse-ro", "@" + endpoint.name, row.target()));
            }
            return lease;
        } catch (IOException | RuntimeException e) { lease.close(); throw e; }
    }
    public List<String> arguments() { return List.copyOf(arguments); }
    public boolean healthy() { return healthy; }
    /** Remove from the launch catalog without disconnecting already mounted filesystems. */
    public void retire() { for (var listener : listeners) listener.close(); }
    private synchronized void accept(Context app, FolderMounts catalog, FolderMounts.Mount row, ParcelFileDescriptor offered) throws IOException {
        if (closed || clients.size() >= 64) return;
        var fd = ParcelFileDescriptor.dup(offered.getFileDescriptor());
        var cancel=new android.os.CancellationSignal();
        clients.put(fd,cancel);
        new Thread(() -> {
            try (var session = new FolderFuseSession(new DocumentTree(app.getContentResolver(),row,catalog.available(row),cancel));
                 var input = new FileInputStream(fd.getFileDescriptor());
                 var output = new FileOutputStream(fd.getFileDescriptor())) {
                session.serve(input,output);
            } catch (IOException | RuntimeException e) {
                synchronized (this) { if (!closed) android.util.Log.d("AndroidFolders","Folder peer disconnected",e); }
            } finally {
                synchronized (this) { clients.remove(fd); }
                try { fd.close(); } catch (IOException ignored) { }
            }
        }, "AndroidFolderFuse").start();
    }
    @Override public void close() {
        List<android.os.CancellationSignal> cancellations;
        synchronized (this) {
            if (closed) return;
            closed = true;
            cancellations=List.copyOf(clients.values());
            for (var fd : clients.keySet()) try { Os.shutdown(fd.getFileDescriptor(), OsConstants.SHUT_RDWR); }
                catch (android.system.ErrnoException ignored) { }
        }
        cancellations.forEach(android.os.CancellationSignal::cancel);
        retire();
    }
}
